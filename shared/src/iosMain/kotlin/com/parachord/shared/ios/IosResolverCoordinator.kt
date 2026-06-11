package com.parachord.shared.ios

import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.plugin.IosJsRuntime
import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.resolver.ResolvedSource
import com.parachord.shared.resolver.ResolverScoring
import com.parachord.shared.resolver.scoreConfidence
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * iOS's `.axe`-only equivalent of Android's `ResolverManager`.
 *
 * iOS has NO native resolvers — every source is resolved by running the
 * same `.axe` plugins desktop + Android use, here through one
 * JavaScriptCore context ([IosJsRuntime]). This mirrors Android's
 * `ResolverManager.resolve`:
 *   1. fan out `resolve()` across stream-capable active resolvers,
 *   2. each `.axe` result maps to a [ResolvedSource] with a placeholder
 *      0.9 confidence + the resolver's matched title/artist,
 *   3. re-score every source via [scoreConfidence] (0.95 when BOTH title
 *      and artist substring-match, 0.50 for single-axis), and
 *   4. rank with [ResolverScoring.selectRanked], whose 0.60 floor drops
 *      the 0.50 single-axis (wrong-song) matches.
 *
 * ## Concurrency through one JSC context
 *
 * The shared `PluginManager.resolve` reads a bare `(async()=>…)()` IIFE
 * synchronously, which yields `[object Promise]` on JSC (CLAUDE.md mistake
 * #27). We instead kick off the async resolve storing its outcome under a
 * UNIQUE per-call key in `window.__resolveResults`, then poll. A single
 * shared global (like `__lastPluginResult`) would be clobbered when several
 * resolves run concurrently; unique keys keep each call isolated, so the
 * `async { … }` fan-out can interleave on the JSC run loop (each `delay`
 * yields; each fetch callback fires independently).
 */
class IosResolverCoordinator(
    private val jsRuntime: IosJsRuntime,
    private val pluginManager: PluginManager,
    private val scoring: ResolverScoring,
    private val settingsStore: SettingsStore,
    private val spotifyClient: SpotifyClient,
    private val httpClient: HttpClient,
    private val appleMusicDeveloperToken: String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Main-confined (all coroutines here run on Dispatchers.Main), so a
    // plain counter is race-free — there is no true parallelism.
    private var callCounter = 0

    /**
     * Resolve a track across stream-capable active resolvers and return the
     * ranked, floor-filtered sources (best first). Empty when nothing
     * matched above the confidence floor.
     */
    suspend fun resolveSources(artist: String, title: String, album: String?): List<ResolvedSource> {
        pluginManager.ensureInitialized()

        val loaded = pluginManager.plugins.value.map { it.id }.toSet()
        val active = settingsStore.getActiveResolvers()
        val ids = STREAMING_RESOLVERS.filter { id ->
            id in loaded && (active.isEmpty() || id in active)
        }

        // Native Spotify branch (Android parity): resolve Spotify via the shared
        // SpotifyClient instead of spotify.axe. Gate on (a) spotify being active
        // and (b) a present access token — skip entirely when not connected to
        // avoid 401 spam. The result joins the SAME list that re-scores via
        // scoreConfidence + selectRanked below, so it's ranked identically.
        val spotifyActive = active.isEmpty() || "spotify" in active
        val spotifyConnected = spotifyActive && settingsStore.getSpotifyAccessToken() != null

        // Native Apple Music branch (Android parity). Android resolves Apple Music
        // via the MusicKit catalog, not the iTunes-Search .axe — the .axe missed
        // tracks Apple Music actually has. This hits the SAME catalog API MusicKit
        // uses (Bearer dev token) for far better matching. `applemusic` is excluded
        // from the .axe STREAMING_RESOLVERS so only this native path runs.
        val amActive = active.isEmpty() || "applemusic" in active
        val amAvailable = amActive && appleMusicDeveloperToken.isNotBlank()

        if (ids.isEmpty() && !spotifyConnected && !amAvailable) return emptyList()

        val resolved: List<ResolvedSource> = coroutineScope {
            val axeDeferred = ids.map { id -> async { resolveOne(id, artist, title, album) } }
            val spotifyDeferred = if (spotifyConnected) {
                async {
                    try {
                        spotifyClient.searchTrack("$artist $title")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            } else {
                null
            }
            val amDeferred = if (amAvailable) {
                async { resolveAppleMusicNative(artist, title) }
            } else {
                null
            }
            (axeDeferred + listOfNotNull(spotifyDeferred, amDeferred)).awaitAll()
        }.filterNotNull()

        // Re-score against the target (Android ResolverManager parity): the
        // .axe placeholder 0.9 becomes 0.95 (both match) or 0.50 (single-axis),
        // and selectRanked's floor drops the wrong-song 0.50s.
        val scored = resolved.map { source ->
            if (source.matchedTitle != null || source.matchedArtist != null) {
                source.copy(
                    confidence = scoreConfidence(title, artist, source.matchedTitle, source.matchedArtist),
                )
            } else {
                source
            }
        }

        return scoring.selectRanked(scored)
    }

    /** Resolve a single track via one `.axe` resolver, awaiting its Promise. */
    private suspend fun resolveOne(
        resolverId: String,
        artist: String,
        title: String,
        album: String?,
    ): ResolvedSource? {
        val key = "res_${callCounter++}"
        val a = artist.jsEsc()
        val t = title.jsEsc()
        val alb = if (album != null) "'${album.jsEsc()}'" else "null"

        jsRuntime.evaluate(
            """
            (function() {
                window.__resolveResults = window.__resolveResults || {};
                window.__resolveResults['$key'] = 'pending';
                (async () => {
                    try {
                        var r = window.__resolverLoader.getResolver('$resolverId');
                        if (!r || !r.resolve) { window.__resolveResults['$key'] = 'null'; return; }
                        var result = await r.resolve('$a', '$t', $alb, r.config || {});
                        window.__resolveResults['$key'] = result ? JSON.stringify(result) : 'null';
                    } catch (e) {
                        window.__resolveResults['$key'] = 'error:' + ((e && e.message) ? e.message : String(e));
                    }
                })();
            })();
            """.trimIndent(),
        )

        repeat(POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            val raw = jsRuntime.evaluate("window.__resolveResults['$key']")
            if (raw != null && raw != "pending") {
                // Free the slot so the map doesn't grow unbounded across plays.
                jsRuntime.evaluate("delete window.__resolveResults['$key']; null")
                if (raw == "null" || raw.startsWith("error:")) return null
                val parsed = runCatching { json.decodeFromString<AxeResolveResult>(raw) }.getOrNull()
                    ?: return null

                // ID-based resolvers (Apple Music, Spotify) return NO top-level
                // `url` — they carry a catalog ID (appleMusicId / spotifyUri) and
                // route through MusicKit / Spotify Connect, not AVPlayer. Only
                // URL-based resolvers (soundcloud, bandcamp, direct) carry a
                // playable stream `url`. Accept a source if it has EITHER a
                // playable URL or a routable ID. (Android's AxeResolveResult
                // required `url` because native-first means it only ever runs
                // url-based .axe like bandcamp/youtube — applemusic.axe.resolve
                // never executes there.)
                val playableUrl = parsed.url
                    ?: parsed.previewUrl
                    ?: parsed.soundcloudUrl
                    ?: parsed.appleMusicUrl
                    ?: parsed.spotifyUrl
                val hasRoutableId = parsed.appleMusicId != null ||
                    parsed.spotifyUri != null || parsed.spotifyId != null ||
                    parsed.soundcloudId != null
                if (playableUrl == null && !hasRoutableId) return null

                return ResolvedSource(
                    // For AM/Spotify the router keys off the ID; url is the
                    // best-available stream (e.g. AM 30s previewUrl) or empty.
                    url = playableUrl ?: "",
                    sourceType = parsed.sourceType ?: resolverId,
                    resolver = resolverId,
                    spotifyUri = parsed.spotifyUri,
                    spotifyId = parsed.spotifyId,
                    soundcloudId = parsed.soundcloudId,
                    soundcloudUrl = parsed.soundcloudUrl,
                    appleMusicId = parsed.appleMusicId,
                    // Placeholder — overridden by scoreConfidence in resolveSources.
                    confidence = 0.9,
                    matchedTitle = parsed.title,
                    matchedArtist = parsed.artist,
                    matchedDurationMs = parsed.duration,
                    artworkUrl = parsed.albumArt,
                )
            }
        }
        return null
    }

    /**
     * Native Apple Music catalog resolution (Android parity). Searches the
     * catalog `songs` endpoint with the dev-token Bearer — the same catalog
     * MusicKit queries — and returns the best title+artist match as a routable
     * `applemusic` source (appleMusicId + artwork + 30s preview). The placeholder
     * 0.9 confidence is overridden by [scoreConfidence] in [resolveSources], so a
     * wrong-song catalog hit is still dropped by the 0.60 floor.
     */
    private suspend fun resolveAppleMusicNative(artist: String, title: String): ResolvedSource? {
        val storefront = settingsStore.getAppleMusicStorefront()?.ifBlank { null } ?: "us"
        val term = "$artist $title".encodeURLParameter()
        val url = "https://api.music.apple.com/v1/catalog/$storefront/search?types=songs&limit=10&term=$term"
        val body = try {
            httpClient.get(url) { header("Authorization", "Bearer $appleMusicDeveloperToken") }.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (body.isBlank()) return null

        val data = runCatching {
            json.parseToJsonElement(body).jsonObject["results"]?.jsonObject
                ?.get("songs")?.jsonObject?.get("data")?.jsonArray
        }.getOrNull() ?: return null
        if (data.isEmpty()) return null

        fun norm(s: String?) = (s ?: "").lowercase().filter { it.isLetterOrDigit() }
        val nt = norm(title)
        val na = norm(artist)
        // Prefer the first song whose title AND artist both substring-match the
        // target; else the catalog's top hit (scoreConfidence then validates).
        val best = data.firstOrNull { el ->
            val at = el.jsonObject["attributes"]?.jsonObject
            norm(at?.get("name")?.jsonPrimitive?.contentOrNull).contains(nt) &&
                norm(at?.get("artistName")?.jsonPrimitive?.contentOrNull).contains(na)
        } ?: data.first()

        val attrs = best.jsonObject["attributes"]?.jsonObject ?: return null
        val id = best.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val artwork = attrs["artwork"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?.replace("{w}", "600")?.replace("{h}", "600")?.replace("{f}", "jpg")
        val preview = attrs["previews"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("url")?.jsonPrimitive?.contentOrNull

        return ResolvedSource(
            url = preview ?: "",
            sourceType = "applemusic",
            resolver = "applemusic",
            appleMusicId = id,
            confidence = 0.9, // overridden by scoreConfidence in resolveSources
            matchedTitle = attrs["name"]?.jsonPrimitive?.contentOrNull,
            matchedArtist = attrs["artistName"]?.jsonPrimitive?.contentOrNull,
            artworkUrl = artwork,
        )
    }

    private fun String.jsEsc(): String = replace("\\", "\\\\").replace("'", "\\'")

    private companion object {
        /**
         * CLAUDE.md `stream: true` content resolvers run through `.axe`.
         * `spotify` AND `applemusic` are intentionally ABSENT — both resolve
         * natively (Android parity): Spotify via the shared [SpotifyClient],
         * Apple Music via the catalog API ([resolveAppleMusicNative]). Their
         * `.axe` plugins stay loaded but dormant for resolution. localfiles is a
         * no-op on iOS today.
         */
        val STREAMING_RESOLVERS = listOf("soundcloud", "localfiles")
        const val POLL_ATTEMPTS = 50
        const val POLL_INTERVAL_MS = 100L
    }
}

/**
 * Wire shape of an `.axe` resolver's `resolve()` JSON result — mirrors
 * Android's `AxeResolveResult`. Mapped to [ResolvedSource] (the `.axe`
 * returns no confidence; that's computed via [scoreConfidence]).
 */
@Serializable
private data class AxeResolveResult(
    val url: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val duration: Long? = null,
    val sourceType: String? = null,
    val spotifyUri: String? = null,
    val spotifyId: String? = null,
    val spotifyUrl: String? = null,
    val soundcloudId: String? = null,
    val soundcloudUrl: String? = null,
    val appleMusicId: String? = null,
    val appleMusicUrl: String? = null,
    val previewUrl: String? = null,
    val albumArt: String? = null,
)
