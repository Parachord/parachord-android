package com.parachord.shared.api

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.concurrent.Volatile
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Thrown by [SpotifyClient.search] when Spotify responds with HTTP 429.
 * Callers (notably [com.parachord.android.resolver.ResolverManager.resolveSpotify])
 * catch this to set a session-level cooldown so subsequent searches
 * short-circuit while Spotify's rate-limit window expires — better not
 * to hammer Spotify into extending the throttle.
 *
 * Mirrors the [ItunesRateLimitedException] pattern from commit `16884d1`
 * for Apple Music's iTunes Search after the same KMP-migration regression:
 * Retrofit/OkHttp's interceptor-chain 429 retry was lost when Spotify cut
 * over to Ktor (commit `92ed9eb`, Phase 9E.1.8). The
 * [com.parachord.shared.sync.SpotifySyncProvider.withRetry] KDoc explicitly
 * documents this regression — this typed exception is the followup.
 *
 * @property retryAfterSeconds value of the `Retry-After` header if Spotify
 *   sent one; null otherwise. Callers may use this to size their cooldown.
 */
class SpotifyRateLimitedException(val retryAfterSeconds: Long? = null) : Exception(
    "Spotify returned HTTP 429" +
        (retryAfterSeconds?.let { " (Retry-After: ${it}s)" } ?: "")
)

/**
 * Spotify Web API client. Cross-platform (commonMain).
 *
 * Auth: per-request `Authorization: Bearer <access_token>` resolved
 * via [AuthTokenProvider.tokenFor] for [AuthRealm.Spotify]. Consumers
 * no longer pass `auth: String` per call — the client owns auth
 * resolution.
 *
 * 401 handling: requests on `api.spotify.com` flow through the global
 * [com.parachord.shared.api.transport.OAuthRefreshPlugin] (registered
 * via [installSharedPlugins]). On 401 the plugin single-flights a token
 * refresh via [com.parachord.shared.api.auth.OAuthTokenRefresher],
 * retries once with the new bearer, and throws
 * [com.parachord.shared.api.transport.ReauthRequiredException] on
 * two-strikes failure. This client does NOT need its own 401 retry
 * logic — the plugin owns that surface.
 *
 * Phase 9E.1.8 cutover (Apr 2026): consumers migrated from app-side
 * Retrofit `SpotifyApi`. Spotify is the OAuth refresh canary — first
 * production cutover to exercise [OAuthRefreshPlugin] under real
 * concurrent load.
 */
class SpotifyClient(
    private val httpClient: HttpClient,
    private val tokens: AuthTokenProvider,
) {

    companion object {
        private const val TAG = "SpotifyClient"
        private const val BASE = "https://api.spotify.com"
        /**
         * Hard cap on cooldown duration. Spotify's punishment escalation can
         * legitimately ask for 30+ minutes (we've observed `Retry-After: 1997s`
         * after sustained abuse during the regression window). Capping below
         * the real `Retry-After` is actively counterproductive — calls that
         * fire after our too-short cooldown elapses just trigger another 429
         * (Spotify's actual window hasn't closed) and re-set the cooldown,
         * so the cycle never breaks.
         *
         * 1 hour is a generous outer guardrail against a misbehaving server
         * sending `Retry-After: 86400` while still respecting Spotify's
         * realistic worst case.
         */
        private const val MAX_COOLDOWN_MS = 60L * 60L * 1000L
        /** Default cooldown when Spotify omits `Retry-After`. Spotify's published 429
         *  windows are typically 1–60s; 30s is a safe middle ground. */
        private const val DEFAULT_COOLDOWN_MS = 30_000L
        /**
         * Max concurrent Spotify calls in flight. Combined with [INTER_REQUEST_DELAY_MS]
         * caps throughput at ~13 RPS — well under Spotify's typical per-app limit
         * (~30+ RPS) and crucially keeps post-cooldown bursts from re-tripping 429.
         * AM's analogous fix (commit `16884d1`) used 4 for iTunes Search; Spotify
         * is harsher in practice (we've seen `Retry-After: 102` from a 288-track
         * resolver fan-out), so we go more conservative.
         */
        private const val MAX_CONCURRENT = 2
        /** Spacing between successive Spotify calls (per-call, after acquiring permit).
         *  Mirrors AM's `INTER_REQUEST_DELAY_MS` from commit `16884d1`. */
        private const val INTER_REQUEST_DELAY_MS = 150L
    }

    /**
     * Bounds simultaneous Spotify HTTP calls. The cooldown alone (see
     * [rateLimitedUntilMs]) prevents storms WHILE a 429 is active, but
     * once the cooldown expires, the resolver pipeline's queued tasks
     * (one per track in a hosted-XSPF fan-out) burst against Spotify
     * simultaneously and re-trip 429 within milliseconds — restarting
     * the cooldown indefinitely. The semaphore breaks the cycle by
     * letting at most [MAX_CONCURRENT] calls run at a time, so calls
     * trickle out at a sustainable rate after each cooldown.
     */
    private val concurrencyLimiter = Semaphore(MAX_CONCURRENT)

    /**
     * Epoch-ms timestamp at which Spotify calls may resume. Spotify's rate
     * limit is per-account/per-app, shared across ALL endpoints — once one
     * call returns 429, every subsequent call to any endpoint also 429s
     * until the window expires. So this cooldown is global to the client,
     * not per-method: a 429 from `search` sets it, and `getCurrentUser` /
     * `getPlaylistTracks` / etc. all check and short-circuit on it.
     *
     * `@Volatile` so writes from one coroutine are seen by readers on other
     * dispatchers without a memory-model surprise.
     */
    @Volatile
    private var rateLimitedUntilMs: Long = 0L

    /**
     * Throws [SpotifyRateLimitedException] without making a network call if
     * we're currently inside a cooldown window. Call at the top of any
     * 429-aware method — keeps a depleted bucket from being made worse by
     * additional in-flight requests during the throttle window.
     */
    private fun checkCooldown() {
        val now = currentTimeMillis()
        if (now < rateLimitedUntilMs) {
            throw SpotifyRateLimitedException(
                retryAfterSeconds = ((rateLimitedUntilMs - now + 999L) / 1000L).coerceAtLeast(1L),
            )
        }
    }

    /**
     * Promote a 429 [HttpResponse] into a typed exception, sizing the
     * cooldown from the `Retry-After` header (or 30s default), capped at
     * [MAX_COOLDOWN_MS]. Logs ONLY on the first 429 of each cooldown cycle —
     * a 288-track playlist generates hundreds of cooldown short-circuits
     * after the first 429, and logging each one would drown out the signal.
     */
    private fun handleRateLimited(response: HttpResponse): Nothing {
        val retryAfterSec = response.headers["Retry-After"]?.toLongOrNull()
        val backoffMs = ((retryAfterSec ?: (DEFAULT_COOLDOWN_MS / 1000L)) * 1000L).coerceAtMost(MAX_COOLDOWN_MS)
        val wasAlreadyLimited = currentTimeMillis() < rateLimitedUntilMs
        rateLimitedUntilMs = currentTimeMillis() + backoffMs
        if (!wasAlreadyLimited) {
            Log.w(TAG, "Spotify rate-limited (HTTP 429). Backing off ${backoffMs / 1000}s; subsequent calls in this window will short-circuit.")
        }
        throw SpotifyRateLimitedException(retryAfterSec)
    }

    /**
     * Apply the Spotify bearer token from [AuthTokenProvider]. If no
     * token is currently stored, the call falls through with no
     * Authorization header set — the server will return 401, and
     * `OAuthRefreshPlugin` will attempt a refresh-and-retry.
     */
    private suspend fun applyAuth(builder: HttpRequestBuilder) {
        val token = (tokens.tokenFor(AuthRealm.Spotify) as? AuthCredential.BearerToken)?.accessToken
        if (token != null) builder.header(HttpHeaders.Authorization, "Bearer $token")
    }

    // ── Search + Lookup ──────────────────────────────────────────────

    /**
     * Search the Spotify catalog. **Reads response status before body
     * deserialization** so a 429 surfaces as a typed
     * [SpotifyRateLimitedException] instead of a
     * `NoTransformationFoundException` from Ktor's body parser trying to
     * deserialize an empty 429 body. This lets [ResolverManager.resolveSpotify]
     * back off cleanly instead of treating every rate-limited request as
     * a generic failure and continuing to slam Spotify.
     *
     * The KMP cutover (commit 92ed9eb) lost Retrofit/OkHttp's interceptor-
     * chain 429 retry; opening a 288-track hosted XSPF then fans out enough
     * concurrent searches to trigger the storm — symptoms include missing
     * Spotify resolver badges, missing track-search-cascade artwork, and
     * a non-responsive "Pull from Spotify" banner whose `getPlaylistTracks`
     * call inherits the depleted bucket.
     */
    suspend fun search(query: String, type: String, limit: Int = 20, market: String = "from_token"): SpSearchResponse {
        checkCooldown()
        return concurrencyLimiter.withPermit {
            // Re-check cooldown inside the permit — the wait to acquire could
            // have spanned a freshly-set cooldown from a sibling call.
            checkCooldown()
            delay(INTER_REQUEST_DELAY_MS)
            val response: HttpResponse = httpClient.get("$BASE/v1/search") {
                applyAuth(this)
                parameter("q", query); parameter("type", type); parameter("limit", limit); parameter("market", market)
            }
            if (response.status.value == 429) handleRateLimited(response)
            // Non-429 non-success: return an empty search response rather than
            // letting body parsing throw. Mirrors [AppleMusicClient.search].
            if (!response.status.isSuccess()) {
                SpSearchResponse()
            } else {
                response.body()
            }
        }
    }

    suspend fun getTrack(trackId: String, market: String = "from_token"): SpTrack =
        httpClient.get("$BASE/v1/tracks/$trackId") {
            applyAuth(this); parameter("market", market)
        }.body()

    suspend fun getArtist(artistId: String): SpArtist =
        httpClient.get("$BASE/v1/artists/$artistId") { applyAuth(this) }.body()

    suspend fun getArtistTopTracks(artistId: String, market: String = "US"): SpTopTracksResponse =
        httpClient.get("$BASE/v1/artists/$artistId/top-tracks") {
            applyAuth(this); parameter("market", market)
        }.body()

    suspend fun getArtistAlbums(artistId: String, includeGroups: String = "album,single,compilation", limit: Int = 50): SpPaginated<SpAlbum> =
        httpClient.get("$BASE/v1/artists/$artistId/albums") {
            applyAuth(this); parameter("include_groups", includeGroups); parameter("limit", limit)
        }.body()

    suspend fun getAlbumTracks(albumId: String, limit: Int = 50): SpPaginated<SpSimpleTrack> =
        httpClient.get("$BASE/v1/albums/$albumId/tracks") {
            applyAuth(this); parameter("limit", limit)
        }.body()

    // ── Playback Control (Spotify Connect) ───────────────────────────

    suspend fun getDevices(): SpDevicesResponse =
        httpClient.get("$BASE/v1/me/player/devices") { applyAuth(this) }.body()

    suspend fun transferPlayback(body: SpTransferRequest): HttpResponse =
        httpClient.put("$BASE/v1/me/player") {
            applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun startPlayback(body: SpPlaybackRequest, deviceId: String? = null): HttpResponse =
        httpClient.put("$BASE/v1/me/player/play") {
            applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
            if (deviceId != null) parameter("device_id", deviceId)
        }

    suspend fun resumePlayback(): HttpResponse =
        httpClient.put("$BASE/v1/me/player/play") { applyAuth(this) }

    suspend fun pausePlayback(): HttpResponse =
        httpClient.put("$BASE/v1/me/player/pause") { applyAuth(this) }

    suspend fun seekPlayback(positionMs: Long): HttpResponse =
        httpClient.put("$BASE/v1/me/player/seek") {
            applyAuth(this); parameter("position_ms", positionMs)
        }

    suspend fun setVolume(volumePercent: Int, deviceId: String? = null): HttpResponse =
        httpClient.put("$BASE/v1/me/player/volume") {
            applyAuth(this)
            parameter("volume_percent", volumePercent.coerceIn(0, 100))
            if (deviceId != null) parameter("device_id", deviceId)
        }

    suspend fun getPlaybackState(): HttpResponse =
        httpClient.get("$BASE/v1/me/player") { applyAuth(this) }

    // ── Library (Sync Read) ──────────────────────────────────────────

    suspend fun getLikedTracks(limit: Int = 50, offset: Int = 0, market: String = "from_token"): SpSavedTracksResponse =
        httpClient.get("$BASE/v1/me/tracks") {
            applyAuth(this); parameter("limit", limit); parameter("offset", offset); parameter("market", market)
        }.body()

    suspend fun getSavedAlbums(limit: Int = 50, offset: Int = 0, market: String = "from_token"): SpSavedAlbumsResponse =
        httpClient.get("$BASE/v1/me/albums") {
            applyAuth(this); parameter("limit", limit); parameter("offset", offset); parameter("market", market)
        }.body()

    suspend fun getFollowedArtists(limit: Int = 50, after: String? = null): SpFollowedArtistsResponse =
        httpClient.get("$BASE/v1/me/following") {
            applyAuth(this); parameter("type", "artist"); parameter("limit", limit)
            if (after != null) parameter("after", after)
        }.body()

    suspend fun getUserPlaylists(limit: Int = 50, offset: Int = 0): SpPaginatedPlaylists =
        httpClient.get("$BASE/v1/me/playlists") {
            applyAuth(this); parameter("limit", limit); parameter("offset", offset)
        }.body()

    /**
     * 429-aware: shares the global cooldown from [search]. Used by the
     * Pull-from-Spotify banner via [com.parachord.shared.sync.SpotifySyncProvider.fetchPlaylistTracks].
     * Without cooldown gating, Pull would 429 directly while a search storm
     * was depleting the user's Spotify rate-limit bucket.
     */
    suspend fun getPlaylistTracks(playlistId: String, limit: Int = 100, offset: Int = 0, market: String = "from_token"): SpPlaylistTracksResponse {
        checkCooldown()
        return concurrencyLimiter.withPermit {
            checkCooldown()
            delay(INTER_REQUEST_DELAY_MS)
            val response: HttpResponse = httpClient.get("$BASE/v1/playlists/$playlistId/tracks") {
                applyAuth(this); parameter("limit", limit); parameter("offset", offset); parameter("market", market)
            }
            if (response.status.value == 429) handleRateLimited(response)
            response.body()
        }
    }

    suspend fun getPlaylist(playlistId: String, fields: String? = null): SpPlaylistFull =
        httpClient.get("$BASE/v1/playlists/$playlistId") {
            applyAuth(this); if (fields != null) parameter("fields", fields)
        }.body()

    /**
     * 429-aware: shares the global cooldown. Called early in
     * [com.parachord.shared.sync.SpotifySyncProvider.getMarket] (which the
     * Pull banner uses for market-aware playlist fetches), so a 429 here
     * blocks the entire Pull flow. Promoting it to a typed exception means
     * the catch in `pullRemoteChanges` can react cleanly instead of
     * surfacing `NoTransformationFoundException`.
     */
    suspend fun getCurrentUser(): SpUser {
        checkCooldown()
        return concurrencyLimiter.withPermit {
            checkCooldown()
            delay(INTER_REQUEST_DELAY_MS)
            val response: HttpResponse = httpClient.get("$BASE/v1/me") { applyAuth(this) }
            if (response.status.value == 429) handleRateLimited(response)
            response.body()
        }
    }

    // ── Library Write ────────────────────────────────────────────────

    suspend fun saveTracks(body: SpIdsRequest): HttpResponse =
        httpClient.put("$BASE/v1/me/tracks") {
            applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun removeTracks(body: SpIdsRequest): HttpResponse =
        httpClient.delete("$BASE/v1/me/tracks") {
            applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun saveAlbums(body: SpIdsRequest): HttpResponse =
        httpClient.put("$BASE/v1/me/albums") {
            applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun removeAlbums(body: SpIdsRequest): HttpResponse =
        httpClient.delete("$BASE/v1/me/albums") {
            applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun followArtists(body: SpIdsRequest): HttpResponse =
        httpClient.put("$BASE/v1/me/following") {
            applyAuth(this); parameter("type", "artist")
            contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun unfollowArtists(body: SpIdsRequest): HttpResponse =
        httpClient.delete("$BASE/v1/me/following") {
            applyAuth(this); parameter("type", "artist")
            contentType(ContentType.Application.Json); setBody(body)
        }

    // ── Playlist Write ───────────────────────────────────────────────

    suspend fun createPlaylist(userId: String, body: SpCreatePlaylistRequest): SpPlaylistFull =
        httpClient.post("$BASE/v1/users/$userId/playlists") {
            applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
        }.body()

    suspend fun replacePlaylistTracks(playlistId: String, body: SpUrisRequest): HttpResponse =
        httpClient.put("$BASE/v1/playlists/$playlistId/tracks") {
            applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun addPlaylistTracks(playlistId: String, body: SpUrisRequest): HttpResponse =
        httpClient.post("$BASE/v1/playlists/$playlistId/tracks") {
            applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun updatePlaylistDetails(playlistId: String, body: SpUpdatePlaylistRequest): HttpResponse =
        httpClient.put("$BASE/v1/playlists/$playlistId") {
            applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun unfollowPlaylist(playlistId: String): HttpResponse =
        httpClient.delete("$BASE/v1/playlists/$playlistId/followers") { applyAuth(this) }
}

// ── Response Models ──────────────────────────────────────────────────

@Serializable
data class SpTopTracksResponse(val tracks: List<SpTrack> = emptyList())

@Serializable
data class SpSearchResponse(
    val tracks: SpPaginated<SpTrack>? = null,
    val albums: SpPaginated<SpAlbum>? = null,
    val artists: SpPaginated<SpArtist>? = null,
)

@Serializable
data class SpPaginated<T>(val items: List<T> = emptyList(), val total: Int = 0)

@Serializable
data class SpTrack(
    val id: String? = null,
    val name: String? = null,
    val artists: List<SpArtistRef> = emptyList(),
    val album: SpAlbumRef? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    @SerialName("is_playable") val isPlayable: Boolean? = null,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name.orEmpty() }
}

@Serializable
data class SpArtistRef(val id: String? = null, val name: String? = null)

@Serializable
data class SpAlbumRef(
    val id: String? = null, val name: String? = null, val images: List<SpImage>? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("total_tracks") val totalTracks: Int? = null,
)

@Serializable
data class SpAlbum(
    val id: String? = null, val name: String? = null, val artists: List<SpArtistRef> = emptyList(),
    val images: List<SpImage>? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("total_tracks") val totalTracks: Int? = null,
    @SerialName("album_type") val albumType: String? = null,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name.orEmpty() }
    val year: Int? get() = releaseDate?.take(4)?.toIntOrNull()
}

@Serializable
data class SpSimpleTrack(
    val id: String? = null, val name: String? = null, val artists: List<SpArtistRef> = emptyList(),
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name.orEmpty() }
}

@Serializable
data class SpArtist(val id: String? = null, val name: String? = null, val genres: List<String> = emptyList(), val images: List<SpImage>? = null)

@Serializable
data class SpImage(val url: String? = null, val height: Int? = null, val width: Int? = null)

fun List<SpImage>?.bestImageUrl(): String? =
    this?.filter { it.url != null }?.sortedBy { it.width ?: 0 }?.firstOrNull { (it.width ?: 0) >= 300 }?.url
        ?: this?.firstOrNull { it.url != null }?.url

@Serializable data class SpDevicesResponse(val devices: List<SpDevice> = emptyList())
@Serializable data class SpDevice(val id: String, val name: String, @SerialName("is_active") val isActive: Boolean = false, @SerialName("is_restricted") val isRestricted: Boolean = false, val type: String = "", @SerialName("volume_percent") val volumePercent: Int? = null)
@Serializable data class SpTransferRequest(@SerialName("device_ids") val deviceIds: List<String>, val play: Boolean = false)
@Serializable data class SpPlaybackRequest(val uris: List<String>? = null, @SerialName("context_uri") val contextUri: String? = null)
@Serializable data class SpPlaybackState(@SerialName("is_playing") val isPlaying: Boolean = false, @SerialName("progress_ms") val progressMs: Long? = null, val item: SpTrack? = null, val device: SpDevice? = null)
@Serializable data class SpSavedTrack(@SerialName("added_at") val addedAt: String? = null, val track: SpTrack? = null)
@Serializable data class SpSavedTracksResponse(val items: List<SpSavedTrack> = emptyList(), val total: Int = 0, val offset: Int = 0, val limit: Int = 50, val next: String? = null)
@Serializable data class SpSavedAlbum(@SerialName("added_at") val addedAt: String? = null, val album: SpAlbum? = null)
@Serializable data class SpSavedAlbumsResponse(val items: List<SpSavedAlbum> = emptyList(), val total: Int = 0, val offset: Int = 0, val limit: Int = 50, val next: String? = null)
@Serializable data class SpFollowedArtistsResponse(val artists: SpCursorPaginated)
@Serializable data class SpCursorPaginated(val items: List<SpArtist> = emptyList(), val total: Int = 0, val cursors: SpCursors? = null, val next: String? = null)
@Serializable data class SpCursors(val after: String? = null)
@Serializable data class SpPlaylistSimple(val id: String? = null, val name: String? = null, val description: String? = null, val images: List<SpImage>? = null, val owner: SpUser? = null, @SerialName("snapshot_id") val snapshotId: String? = null, val tracks: SpPlaylistTracksRef? = null)
@Serializable data class SpPlaylistTracksRef(val total: Int = 0)
@Serializable data class SpPaginatedPlaylists(val items: List<SpPlaylistSimple> = emptyList(), val total: Int = 0, val offset: Int = 0, val limit: Int = 50, val next: String? = null)
@Serializable data class SpPlaylistTrackItem(@SerialName("added_at") val addedAt: String? = null, val track: SpTrack? = null)
@Serializable data class SpPlaylistTracksResponse(val items: List<SpPlaylistTrackItem> = emptyList(), val total: Int = 0, val offset: Int = 0, val limit: Int = 100, val next: String? = null)
@Serializable data class SpPlaylistFull(val id: String? = null, val name: String? = null, val description: String? = null, val images: List<SpImage>? = null, val owner: SpUser? = null, @SerialName("snapshot_id") val snapshotId: String? = null, val tracks: SpPlaylistTracksResponse? = null)
@Serializable data class SpUser(val id: String, @SerialName("display_name") val displayName: String? = null, val country: String? = null)
@Serializable data class SpIdsRequest(val ids: List<String>)
@Serializable data class SpUrisRequest(val uris: List<String>)
@Serializable data class SpCreatePlaylistRequest(val name: String, val description: String? = null, val public: Boolean = false)
@Serializable data class SpUpdatePlaylistRequest(val name: String? = null, val description: String? = null)
@Serializable data class SpSnapshotResponse(@SerialName("snapshot_id") val snapshotId: String)
