package com.parachord.shared.ios

import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.resolver.ResolvedSource
import com.parachord.shared.resolver.ResolverCoordinator
import com.parachord.shared.resolver.ResolverRuntime
import com.parachord.shared.resolver.ResolverScoring
import com.parachord.shared.resolver.scoreConfidence
import kotlinx.coroutines.CancellationException

/**
 * iOS's resolver entry point for Swift. Post-#210 this is a thin delegate over
 * the shared [ResolverCoordinator] (fan-out + native Spotify branch + native
 * Apple Music + `.axe` execution + re-score + rank) — the platform-specific
 * async lives in [IosResolverRuntime].
 *
 * The public API ([resolveSources] / [resolveSingle]) is unchanged — Swift
 * callers depend on it. [resolveSources] delegates straight to
 * [ResolverCoordinator.resolveRanked]; [resolveSingle] (additive
 * re-resolution of ONE resolver, #1) keeps a thin local implementation routed
 * through the [runtime] + the injected [resolveSpotify] lambda. The shared
 * `resolveSingle` is a LATER task (Task 6).
 */
class IosResolverCoordinator(
    private val coordinator: ResolverCoordinator,
    private val runtime: ResolverRuntime,
    private val pluginManager: PluginManager,
    private val settingsStore: com.parachord.shared.settings.SettingsStore,
    private val appleMusicDeveloperToken: String,
    /**
     * Resolves Spotify for a query; returns null when Spotify is off / has no
     * token / errored. The EXACT same gate+search the coordinator's Spotify
     * branch uses (wired once in [IosContainer], reused here for [resolveSingle]).
     */
    private val resolveSpotify: suspend (query: String) -> ResolvedSource?,
) {
    /**
     * Resolve a track across stream-capable active resolvers and return the
     * ranked, floor-filtered sources (best first). Empty when nothing matched
     * above the confidence floor. Delegates to the shared coordinator's ranked
     * path — behavior identical to the pre-#210 inline fan-out.
     */
    suspend fun resolveSources(artist: String, title: String, album: String?): List<ResolvedSource> {
        pluginManager.ensureInitialized()
        return coordinator.resolveRanked(
            query = "$artist $title",
            targetTitle = title,
            targetArtist = artist,
            album = album,
        )
    }

    /**
     * Resolve ONE specific resolver for a track (additive re-resolution, #1).
     * When the user enables a resolver AFTER a track was already cached, we
     * resolve just the newly-enabled resolver and merge it into the existing
     * (still-good) sources — rather than re-resolving everything. Re-scored +
     * 0.60-floor-filtered like [resolveSources]; null on miss / below floor.
     */
    suspend fun resolveSingle(resolverId: String, artist: String, title: String, album: String?): ResolvedSource? {
        pluginManager.ensureInitialized()
        val query = "$artist $title"
        val raw: ResolvedSource? = when (resolverId) {
            "spotify" -> {
                if (settingsStore.getSpotifyAccessToken() == null) return null
                try {
                    resolveSpotify(query)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            in runtime.nativeResolverIds -> {
                if (resolverId == "applemusic" && appleMusicDeveloperToken.isBlank()) return null
                runtime.resolveNative(resolverId, query, title, artist, album)
            }
            else -> {
                val loaded = pluginManager.plugins.value.map { it.id }.toSet()
                if (resolverId !in loaded) return null
                runtime.resolveAxe(resolverId, query, title, artist, album)
            }
        }
        val source = raw ?: return null
        val scored = if (source.matchedTitle != null || source.matchedArtist != null) {
            source.copy(confidence = scoreConfidence(title, artist, source.matchedTitle, source.matchedArtist))
        } else {
            source
        }
        return if (!scored.noMatch && (scored.confidence ?: 0.0) >= ResolverScoring.MIN_CONFIDENCE_THRESHOLD) scored else null
    }
}
