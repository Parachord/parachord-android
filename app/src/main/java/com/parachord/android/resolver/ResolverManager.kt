package com.parachord.android.resolver

import android.util.Log
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.api.SpotifyApi
import com.parachord.android.data.db.dao.TrackDao
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.handlers.MusicKitWebBridge
import com.parachord.android.plugin.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages track resolution using native Kotlin resolvers.
 *
 * Replaces the JS bridge approach with direct API calls. Each resolver
 * searches its source for a matching track and returns a [ResolvedSource]
 * with the playback URI/URL and metadata needed by the playback handlers.
 */
@Singleton
class ResolverManager @Inject constructor(
    private val spotifyApi: SpotifyApi,
    private val settingsStore: SettingsStore,
    private val oAuthManager: OAuthManager,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val musicKitBridge: MusicKitWebBridge,
    private val trackDao: TrackDao,
    private val pluginManager: PluginManager,
) {
    companion object {
        private const val TAG = "ResolverManager"
        private const val SC_API_BASE = "https://api.soundcloud.com"
        /** Only check token freshness once per 5 minutes to avoid unnecessary API calls. */
        private const val TOKEN_CHECK_INTERVAL_MS = 5 * 60 * 1000L
    }

    /** Timestamp of last proactive token freshness check (epochMs). */
    @Volatile private var lastTokenCheck = 0L

    /** Native resolvers that have Kotlin implementations (always available). */
    private val nativeResolverIds = setOf("spotify", "applemusic", "soundcloud", "localfiles")

    private val _resolvers = MutableStateFlow(
        listOf(
            ResolverInfo(id = "spotify", name = "Spotify", enabled = true),
            ResolverInfo(id = "applemusic", name = "Apple Music", enabled = true),
            ResolverInfo(id = "soundcloud", name = "SoundCloud", enabled = true),
            ResolverInfo(id = "localfiles", name = "Local Files", enabled = true),
            // .axe-only resolvers — no native Kotlin implementation, executed via JsBridge
            ResolverInfo(id = "bandcamp", name = "Bandcamp", enabled = true),
            ResolverInfo(id = "youtube", name = "YouTube", enabled = true),
        )
    )
    val resolvers: StateFlow<List<ResolverInfo>> = _resolvers.asStateFlow()

    /**
     * Proactively ensure the Spotify access token is fresh.
     * Debounced to once per 5 minutes to avoid unnecessary API calls.
     *
     * Makes a lightweight "me" API call; if it 401s, refreshes the token once.
     * This way, by the time individual resolveSpotify() calls run, the token is valid.
     */
    suspend fun ensureTokensFresh() {
        val now = System.currentTimeMillis()
        if (now - lastTokenCheck < TOKEN_CHECK_INTERVAL_MS) return
        lastTokenCheck = now

        val token = settingsStore.getSpotifyAccessToken()
        if (!token.isNullOrBlank()) {
            try {
                spotifyApi.getCurrentUser("Bearer $token")
            } catch (e: HttpException) {
                if (e.code() == 401) {
                    Log.d(TAG, "Spotify token stale, proactively refreshing")
                    if (oAuthManager.refreshSpotifyToken()) {
                        Log.d(TAG, "Spotify token proactively refreshed")
                    } else {
                        Log.w(TAG, "Spotify proactive token refresh failed — re-auth may be needed")
                    }
                }
            } catch (_: Exception) {
                // Network error etc — resolve() will handle individual failures
            }
        }
    }

    /**
     * Resolve a track query through all enabled and configured resolvers in parallel.
     * Only resolvers that are active (per user settings) and have valid credentials
     * will be included in the pipeline.
     *
     * @param targetTitle Original track title for confidence scoring
     * @param targetArtist Original track artist for confidence scoring
     */
    suspend fun resolve(
        query: String,
        targetTitle: String? = null,
        targetArtist: String? = null,
    ): List<ResolvedSource> = coroutineScope {
        // Proactively refresh stale tokens before resolving
        ensureTokensFresh()
        val activeResolvers = settingsStore.getActiveResolvers()

        // Build resolver tasks for enabled resolvers only.
        // Empty activeResolvers list means all are enabled (no filtering).
        val tasks = buildList {
            if (activeResolvers.isEmpty() || "spotify" in activeResolvers) {
                add(async { resolveSpotify(query) })
            }
            if (activeResolvers.isEmpty() || "applemusic" in activeResolvers) {
                add(async { resolveAppleMusic(query) })
            }
            if (activeResolvers.isEmpty() || "soundcloud" in activeResolvers) {
                add(async { resolveSoundCloud(query) })
            }
            if (activeResolvers.isEmpty() || "localfiles" in activeResolvers) {
                add(async { resolveLocalFile(targetTitle, targetArtist) })
            }
            // .axe-only resolvers (bandcamp, youtube, etc.) — executed via JsBridge.
            // Native resolvers take priority (faster, no JS bridge overhead);
            // .axe resolvers fill gaps for sources with no native implementation.
            val axeResolverIds = pluginManager.plugins.value
                .filter { it.capabilities["resolve"] == true && it.id !in nativeResolverIds }
                .map { it.id }
            for (resolverId in axeResolverIds) {
                if (activeResolvers.isEmpty() || resolverId in activeResolvers) {
                    add(async { resolveViaPlugin(resolverId, query, targetTitle, targetArtist) })
                }
            }
        }

        var results = tasks.mapNotNull { it.await() }

        // Apply confidence scoring if target title/artist are available
        if (targetTitle != null && targetArtist != null) {
            results = results.map { source ->
                if (source.matchedTitle != null || source.matchedArtist != null) {
                    val scored = scoreConfidence(
                        targetTitle, targetArtist,
                        source.matchedTitle, source.matchedArtist,
                    )
                    source.copy(confidence = scored)
                } else {
                    source
                }
            }
        }

        Log.d(TAG, "Resolved '$query' → ${results.size} sources: ${results.map { "${it.resolver}(${String.format("%.0f%%", (it.confidence ?: 0.0) * 100)})" }}")
        results
    }

    /**
     * Resolve using pre-existing IDs (from metadata providers).
     * Verifies that ID-based sources are actually playable before trusting them.
     * Falls back to search-based resolution for all enabled resolvers.
     *
     * @param targetTitle Original track title for confidence scoring
     * @param targetArtist Original track artist for confidence scoring
     */
    suspend fun resolveWithHints(
        query: String,
        spotifyId: String? = null,
        soundcloudId: String? = null,
        appleMusicId: String? = null,
        targetTitle: String? = null,
        targetArtist: String? = null,
    ): List<ResolvedSource> = coroutineScope {
        val results = mutableListOf<ResolvedSource>()

        // If we have a Spotify ID from metadata, verify it's actually playable
        // before trusting it (metadata IDs can reference tracks unavailable in
        // the user's market)
        if (spotifyId != null) {
            val verified = async { verifySpotifyTrack(spotifyId) }
            val source = verified.await()
            if (source != null) {
                results.add(source)
            }
        }

        // If we have a SoundCloud ID, use it directly
        if (soundcloudId != null) {
            results.add(
                ResolvedSource(
                    url = "$SC_API_BASE/tracks/$soundcloudId",
                    sourceType = "soundcloud",
                    resolver = "soundcloud",
                    soundcloudId = soundcloudId,
                    confidence = 0.95, // High confidence — direct ID match
                )
            )
        }

        // If we have an Apple Music ID, use it directly
        if (appleMusicId != null) {
            results.add(
                ResolvedSource(
                    url = "applemusic:song:$appleMusicId",
                    sourceType = "applemusic",
                    resolver = "applemusic",
                    appleMusicId = appleMusicId,
                    confidence = 0.95, // High confidence — direct ID match
                )
            )
        }

        // Also run the regular resolve pipeline for other sources
        // (with target title/artist for confidence scoring)
        val others = resolve(query, targetTitle, targetArtist)
            .filter { it.resolver !in results.map { r -> r.resolver } }
        results.addAll(others)

        results
    }

    private suspend fun resolveSpotify(query: String): ResolvedSource? {
        val token = settingsStore.getSpotifyAccessToken()
        if (token.isNullOrBlank()) return null

        return try {
            searchSpotifyTrack(query, token)
        } catch (e: HttpException) {
            if (e.code() == 401 && oAuthManager.refreshSpotifyToken()) {
                val newToken = settingsStore.getSpotifyAccessToken() ?: return null
                try {
                    searchSpotifyTrack(query, newToken)
                } catch (e2: Exception) {
                    Log.w(TAG, "Spotify resolve failed after refresh for '$query': ${e2.message}")
                    null
                }
            } else {
                Log.w(TAG, "Spotify resolve failed for '$query': ${e.message}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify resolve failed for '$query': ${e.message}")
            null
        }
    }

    private suspend fun searchSpotifyTrack(query: String, token: String): ResolvedSource? {
        // Use field-qualified search for better precision (matches desktop spotify.axe)
        val response = spotifyApi.search(
            auth = "Bearer $token",
            query = query,
            type = "track",
            limit = 5, // Get a few results so we can filter to playable ones
        )
        // Filter to tracks that are actually playable in the user's market
        // (market=from_token is passed by default, which sets is_playable)
        val track = response.tracks?.items
            ?.firstOrNull { it.isPlayable != false }
            ?: return null
        return ResolvedSource(
            url = "spotify:track:${track.id}",
            sourceType = "spotify",
            resolver = "spotify",
            spotifyUri = "spotify:track:${track.id}",
            spotifyId = track.id,
            confidence = 0.9, // Default — overridden by scoreConfidence() in resolve()
            matchedTitle = track.name,
            matchedArtist = track.artistName,
            matchedDurationMs = track.durationMs,
        )
    }

    /**
     * Verify a Spotify track ID is actually playable in the user's market.
     * Returns a ResolvedSource if playable, null if not available.
     */
    private suspend fun verifySpotifyTrack(spotifyId: String): ResolvedSource? {
        val token = settingsStore.getSpotifyAccessToken()
        if (token.isNullOrBlank()) return null
        return try {
            val track = spotifyApi.getTrack(
                auth = "Bearer $token",
                trackId = spotifyId,
            )
            if (track.isPlayable == false) {
                Log.d(TAG, "Spotify track $spotifyId is not playable in user's market")
                return null
            }
            ResolvedSource(
                url = "spotify:track:${track.id}",
                sourceType = "spotify",
                resolver = "spotify",
                spotifyUri = "spotify:track:${track.id}",
                spotifyId = track.id,
                confidence = 0.95, // High confidence — verified ID match
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to verify Spotify track $spotifyId: ${e.message}")
            null
        }
    }

    // ── Apple Music Resolver ────────────────────────────────────────

    private suspend fun resolveAppleMusic(query: String): ResolvedSource? {
        // Tier 1: MusicKit JS (requires developer token + auth)
        if (musicKitBridge.configured.value) {
            try {
                val results = musicKitBridge.search(query, limit = 5)
                val best = results.firstOrNull()
                if (best != null) {
                    Log.d(TAG, "Apple Music (MusicKit) matched '${best.title}' by ${best.artist}")
                    return ResolvedSource(
                        url = best.appleMusicUrl ?: "applemusic:song:${best.id}",
                        sourceType = "applemusic",
                        resolver = "applemusic",
                        appleMusicId = best.id,
                        confidence = 0.9, // Default — overridden by scoreConfidence() in resolve()
                        matchedTitle = best.title,
                        matchedArtist = best.artist,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "MusicKit search failed, falling back to iTunes: ${e.message}")
            }
        }

        // Tier 2: iTunes Search API (no auth, metadata-only)
        return resolveViaiTunes(query)
    }

    private suspend fun resolveViaiTunes(query: String): ResolvedSource? =
        withContext(Dispatchers.IO) {
            try {
                val url = okhttp3.HttpUrl.Builder()
                    .scheme("https")
                    .host("itunes.apple.com")
                    .addPathSegment("search")
                    .addQueryParameter("term", query)
                    .addQueryParameter("media", "music")
                    .addQueryParameter("entity", "song")
                    .addQueryParameter("limit", "5")
                    .build()

                val request = Request.Builder().url(url).get().build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                val result = json.decodeFromString<ITunesSearchResponse>(body)
                val best = result.results.firstOrNull() ?: return@withContext null

                Log.d(TAG, "Apple Music (iTunes) matched '${best.trackName}' by ${best.artistName}")

                ResolvedSource(
                    url = best.trackViewUrl ?: "applemusic:song:${best.trackId}",
                    sourceType = "applemusic",
                    resolver = "applemusic",
                    appleMusicId = best.trackId.toString(),
                    confidence = 0.85, // Default — overridden by scoreConfidence() in resolve()
                    matchedTitle = best.trackName,
                    matchedArtist = best.artistName,
                    matchedDurationMs = best.trackTimeMillis,
                )
            } catch (e: Exception) {
                Log.w(TAG, "iTunes search failed for '$query': ${e.message}")
                null
            }
        }

    // ── Local File Resolver ──────────────────────────────────────────────

    /**
     * Search the local library for a matching track.
     * Requires both title and artist to be known for a reliable match.
     */
    private suspend fun resolveLocalFile(
        targetTitle: String?,
        targetArtist: String?,
    ): ResolvedSource? {
        if (targetTitle.isNullOrBlank() || targetArtist.isNullOrBlank()) return null
        return try {
            val track = trackDao.findLocalFile(targetTitle, targetArtist) ?: return null
            val sourceUrl = track.sourceUrl ?: return null
            Log.d(TAG, "Local file matched '${track.title}' by ${track.artist}")
            ResolvedSource(
                url = sourceUrl,
                sourceType = "local",
                resolver = "localfiles",
                confidence = 0.95, // High confidence — exact title+artist match in local library
                matchedTitle = track.title,
                matchedArtist = track.artist,
                matchedDurationMs = track.duration,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Local file resolve failed: ${e.message}")
            null
        }
    }

    // ── SoundCloud Resolver ─────────────────────────────────────────────

    /**
     * Search SoundCloud for a matching track.
     * Mirrors the desktop's soundcloud.axe resolver: searches via the
     * /tracks endpoint, filters to streamable non-blocked tracks, and
     * returns the best match with default 0.9 confidence.
     * Handles 401 by refreshing the OAuth token and retrying.
     */
    private suspend fun resolveSoundCloud(query: String): ResolvedSource? {
        val token = settingsStore.getSoundCloudToken()
        if (token.isNullOrBlank()) return null

        return try {
            val result = searchSoundCloudTrack(query, token)
            // Check for 401 (token expired) — returned as null with a log warning
            result
        } catch (e: SoundCloudAuthException) {
            // Token expired — try to refresh
            if (oAuthManager.refreshSoundCloudToken()) {
                val newToken = settingsStore.getSoundCloudToken() ?: return null
                try {
                    searchSoundCloudTrack(query, newToken)
                } catch (e2: Exception) {
                    Log.w(TAG, "SoundCloud resolve failed after refresh for '$query': ${e2.message}")
                    null
                }
            } else {
                Log.w(TAG, "SoundCloud token refresh failed for '$query'")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "SoundCloud resolve failed for '$query': ${e.message}")
            null
        }
    }

    /** Thrown when SoundCloud API returns 401 (token expired). */
    private class SoundCloudAuthException : Exception("SoundCloud token expired")

    private suspend fun searchSoundCloudTrack(query: String, token: String): ResolvedSource? =
        withContext(Dispatchers.IO) {
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("api.soundcloud.com")
                .addPathSegment("tracks")
                .addQueryParameter("q", query)
                .addQueryParameter("limit", "20")
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "OAuth $token")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.code == 401) {
                throw SoundCloudAuthException()
            }
            if (!response.isSuccessful) {
                Log.w(TAG, "SoundCloud search returned ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val tracks = try {
                json.decodeFromString<List<ScTrack>>(body)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse SoundCloud search results: ${e.message}")
                return@withContext null
            }

            // Filter to streamable, non-blocked tracks (matches desktop logic)
            val streamable = tracks.filter { it.streamable == true && it.access != "blocked" }
            val best = streamable.firstOrNull() ?: return@withContext null

            Log.d(TAG, "SoundCloud matched '${best.title}' by ${best.user?.username}")

            ResolvedSource(
                url = best.permalinkUrl ?: "$SC_API_BASE/tracks/${best.id}",
                sourceType = "soundcloud",
                resolver = "soundcloud",
                soundcloudId = best.id.toString(),
                soundcloudUrl = best.permalinkUrl,
                confidence = 0.9, // Default — overridden by scoreConfidence() in resolve()
                matchedTitle = best.title,
                matchedArtist = best.user?.username,
                matchedDurationMs = best.duration,
            )
        }

    // ── .axe Plugin Resolver ──────────────────────────────────────────

    /**
     * Resolve a track via an .axe plugin (bandcamp, youtube, etc.).
     * The plugin's resolve() function returns a JSON result that we
     * map to a [ResolvedSource].
     */
    private suspend fun resolveViaPlugin(
        resolverId: String,
        query: String,
        targetTitle: String?,
        targetArtist: String?,
    ): ResolvedSource? = try {
        val resultJson = pluginManager.resolve(
            resolverId,
            targetArtist ?: "",
            targetTitle ?: query,
            null,
        )
        Log.d(TAG, "Plugin resolve($resolverId) raw result: ${resultJson?.take(200)}")
        if (resultJson == null) {
            null
        } else {
            val result = json.decodeFromString<AxeResolveResult>(resultJson)
            val resolvedUrl = result.url ?: return@resolveViaPlugin null
            ResolvedSource(
                resolver = resolverId,
                sourceType = result.sourceType ?: resolverId,
                url = resolvedUrl,
                spotifyUri = result.spotifyUri,
                spotifyId = result.spotifyId,
                soundcloudId = result.soundcloudId,
                appleMusicId = result.appleMusicId,
                confidence = 0.9,
                matchedTitle = result.title,
                matchedArtist = result.artist,
                matchedDurationMs = result.duration,
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Plugin resolve($resolverId) failed: ${e.message}")
        null
    }
}

@kotlinx.serialization.Serializable
private data class AxeResolveResult(
    val url: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val duration: Long? = null,
    val sourceType: String? = null,
    val spotifyUri: String? = null,
    val spotifyId: String? = null,
    val soundcloudId: String? = null,
    val appleMusicId: String? = null,
)

// ── SoundCloud API Response Models ──────────────────────────────────

// ── iTunes API Response Models ──────────────────────────────────────

@Serializable
private data class ITunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ITunesTrack> = emptyList(),
)

@Serializable
private data class ITunesTrack(
    val trackId: Long,
    val trackName: String,
    val artistName: String,
    val collectionName: String? = null,
    val trackViewUrl: String? = null,
    val artworkUrl100: String? = null,
    val trackTimeMillis: Long? = null,
    val previewUrl: String? = null,
)

// ── SoundCloud API Response Models ──────────────────────────────────

@Serializable
private data class ScTrack(
    val id: Long,
    val title: String? = null,
    val user: ScUser? = null,
    val duration: Long? = null,
    @SerialName("permalink_url") val permalinkUrl: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("waveform_url") val waveformUrl: String? = null,
    val streamable: Boolean? = null,
    val access: String? = null,
    @SerialName("label_name") val labelName: String? = null,
)

@Serializable
private data class ScUser(
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class ResolverInfo(
    val id: String,
    val name: String,
    val version: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class ResolvedSource(
    val url: String,
    val sourceType: String,
    val resolver: String,
    val quality: Int? = null,
    val headers: Map<String, String>? = null,
    val spotifyUri: String? = null,
    val spotifyId: String? = null,
    val soundcloudId: String? = null,
    val soundcloudUrl: String? = null,
    val appleMusicId: String? = null,
    /** Match confidence from the resolver (0.0–1.0). Desktop defaults to 0.9 for successful resolves. */
    val confidence: Double? = null,
    /** Whether the resolver explicitly couldn't match this track. */
    val noMatch: Boolean = false,
    /** The title returned by the resolver's search result (for confidence scoring). */
    val matchedTitle: String? = null,
    /** The artist returned by the resolver's search result (for confidence scoring). */
    val matchedArtist: String? = null,
    /** Duration in ms from the resolver's result (for confidence scoring). */
    val matchedDurationMs: Long? = null,
)

// ── Confidence Scoring ──────────────────────────────────────────────

/**
 * Normalize a string for comparison: lowercase, strip everything except
 * alphanumeric characters. Matches the desktop's normalizeStr().
 */
fun normalizeStr(s: String?): String =
    s?.lowercase()?.replace(Regex("[^a-z0-9]"), "") ?: ""

/**
 * Check if two normalized strings match via containment (either direction).
 * Matches the desktop's validateResolvedTrack() logic.
 */
private fun stringsMatch(a: String, b: String): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    return a.contains(b) || b.contains(a)
}

/**
 * Calculate match confidence by comparing the resolver's result against the
 * target track. Matches the desktop's calculateConfidence():
 *
 * - Title + artist match → 0.95
 * - Title match only     → 0.85
 * - Artist match only    → 0.70
 * - No match             → 0.50
 *
 * Direct ID matches (spotifyId, appleMusicId, soundcloudId) bypass this and
 * keep their 0.95 confidence since the ID is authoritative.
 */
fun scoreConfidence(
    targetTitle: String,
    targetArtist: String,
    matchedTitle: String?,
    matchedArtist: String?,
): Double {
    val normTarget = normalizeStr(targetTitle)
    val normArtist = normalizeStr(targetArtist)
    val normMatchTitle = normalizeStr(matchedTitle)
    val normMatchArtist = normalizeStr(matchedArtist)

    val titleMatch = stringsMatch(normTarget, normMatchTitle)
    val artistMatch = stringsMatch(normArtist, normMatchArtist)

    return when {
        titleMatch && artistMatch -> 0.95
        titleMatch -> 0.85
        artistMatch -> 0.70
        else -> 0.50
    }
}
