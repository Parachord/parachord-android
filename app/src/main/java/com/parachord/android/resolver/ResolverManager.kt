package com.parachord.android.resolver

import android.util.Log
import com.parachord.android.data.api.SpotifyApi
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
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
) {
    companion object {
        private const val TAG = "ResolverManager"
    }

    private val _resolvers = MutableStateFlow(
        listOf(
            ResolverInfo(id = "spotify", name = "Spotify", enabled = true),
        )
    )
    val resolvers: StateFlow<List<ResolverInfo>> = _resolvers.asStateFlow()

    /** Resolve a track query through all available native resolvers in parallel. */
    suspend fun resolve(query: String): List<ResolvedSource> = coroutineScope {
        val results = listOf(
            async { resolveSpotify(query) },
        ).mapNotNull { it.await() }

        Log.d(TAG, "Resolved '$query' → ${results.size} sources: ${results.map { it.resolver }}")
        results
    }

    /**
     * Resolve using a pre-existing spotifyId (from metadata providers).
     * This avoids a redundant search when we already have the Spotify track ID.
     */
    suspend fun resolveWithHints(
        query: String,
        spotifyId: String? = null,
    ): List<ResolvedSource> = coroutineScope {
        val results = mutableListOf<ResolvedSource>()

        // If we have a Spotify ID from metadata, use it directly
        if (spotifyId != null) {
            results.add(
                ResolvedSource(
                    url = "spotify:track:$spotifyId",
                    sourceType = "spotify",
                    resolver = "spotify",
                    spotifyUri = "spotify:track:$spotifyId",
                    spotifyId = spotifyId,
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
        val token = settingsStore.getSpotifyAccessTokenFlow().firstOrNull()
        if (token.isNullOrBlank()) return null

        return try {
            val response = spotifyApi.search(
                auth = "Bearer $token",
                query = query,
                type = "track",
                limit = 1,
            )
            val track = response.tracks?.items?.firstOrNull() ?: return null
            ResolvedSource(
                url = "spotify:track:${track.id}",
                sourceType = "spotify",
                resolver = "spotify",
                spotifyUri = "spotify:track:${track.id}",
                spotifyId = track.id,
                confidence = 0.9,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Spotify resolve failed for '$query': ${e.message}")
            null
        }
    }
}

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
    /** Match confidence from the resolver (0.0–1.0). Desktop defaults to 0.9 for successful resolves. */
    val confidence: Double? = null,
    /** Whether the resolver explicitly couldn't match this track. */
    val noMatch: Boolean = false,
)
