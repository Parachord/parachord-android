package com.parachord.android.resolver

import android.util.Log
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.api.SpotifyApi
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.handlers.MusicKitWebBridge
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
) {
    companion object {
        private const val TAG = "ResolverManager"
        private const val SC_API_BASE = "https://api.soundcloud.com"
        /** Only check token freshness once per 5 minutes to avoid unnecessary API calls. */
        private const val TOKEN_CHECK_INTERVAL_MS = 5 * 60 * 1000L
    }

    /** Timestamp of last proactive token freshness check (epochMs). */
    @Volatile private var lastTokenCheck = 0L

    private val _resolvers = MutableStateFlow(
        listOf(
            ResolverInfo(id = "spotify", name = "Spotify", enabled = true),
            ResolverInfo(id = "applemusic", name = "Apple Music", enabled = true),
            ResolverInfo(id = "soundcloud", name = "SoundCloud", enabled = true),
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
     */
    suspend fun resolve(query: String): List<ResolvedSource> = coroutineScope {
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
        }

        val results = tasks.mapNotNull { it.await() }
        Log.d(TAG, "Resolved '$query' → ${results.size} sources: ${results.map { it.resolver }}")
        results
    }

    /**
     * Resolve using pre-existing IDs (from metadata providers).
     * Verifies that ID-based sources are actually playable before trusting them.
     * Falls back to search-based resolution for all enabled resolvers.
     */
    suspend fun resolveWithHints(
        query: String,
        spotifyId: String? = null,
        soundcloudId: String? = null,
        appleMusicId: String? = null,
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
        val others = resolve(query).filter { it.resolver !in results.map { r -> r.resolver } }
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
            confidence = 0.9,
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
                        confidence = 0.9,
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
                    confidence = 0.85,
                )
            } catch (e: Exception) {
                Log.w(TAG, "iTunes search failed for '$query': ${e.message}")
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
                confidence = 0.9, // Desktop default for SoundCloud matches
            )
        }
}

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
)
