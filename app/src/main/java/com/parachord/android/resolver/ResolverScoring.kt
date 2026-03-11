package com.parachord.android.resolver

import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ports the desktop app's resolver priority + confidence scoring logic.
 *
 * Source selection uses a two-tier sort:
 * 1. Resolver priority — user-configured ordering (lower index = higher priority)
 * 2. Confidence score — match quality tiebreaker within the same priority tier
 *
 * A Spotify result at 50% confidence beats a SoundCloud result at 95%
 * when the user ranks Spotify higher. But if two resolvers share the same
 * priority level, the higher-confidence match wins.
 */
@Singleton
class ResolverScoring @Inject constructor(
    private val settingsStore: SettingsStore,
) {
    companion object {
        /**
         * Canonical priority order from the desktop app.
         * Used as the default when the user hasn't customized their order,
         * and as the basis for insertInCanonicalOrder().
         */
        val CANONICAL_RESOLVER_ORDER = listOf(
            "spotify", "applemusic", "bandcamp", "soundcloud", "localfiles", "youtube"
        )
    }

    /**
     * Select the best source from a list of resolved sources,
     * applying the desktop app's priority-first, confidence-second scoring.
     */
    suspend fun selectBest(
        sources: List<ResolvedSource>,
        preferredResolver: String? = null,
    ): ResolvedSource? {
        if (sources.isEmpty()) return null
        if (sources.size == 1) return sources.first()

        val resolverOrder = settingsStore.getResolverOrder()
        val activeResolvers = settingsStore.getActiveResolvers()

        return sources
            .filter { activeResolvers.isEmpty() || it.resolver in activeResolvers }
            .map { source ->
                ScoredSource(
                    source = source,
                    priority = resolverOrder.indexOf(source.resolver).let {
                        if (it == -1) resolverOrder.size else it
                    },
                    confidence = source.confidence ?: 0.0,
                )
            }
            .sortedWith(compareBy<ScoredSource> { scored ->
                // Preferred resolver always wins
                if (preferredResolver != null && scored.source.resolver == preferredResolver) -1
                else scored.priority
            }.thenByDescending { it.confidence })
            .firstOrNull()
            ?.source
    }

    /**
     * Insert a resolver ID into an order list at its canonical position.
     * Mirrors the desktop's insertInCanonicalOrder() function.
     */
    fun insertInCanonicalOrder(order: List<String>, newId: String): List<String> {
        if (newId in order) return order

        val canonicalIndex = CANONICAL_RESOLVER_ORDER.indexOf(newId)
        if (canonicalIndex == -1) {
            return order + newId
        }

        val result = order.toMutableList()
        var insertAt = result.size
        for (i in result.indices) {
            val existingIndex = CANONICAL_RESOLVER_ORDER.indexOf(result[i])
            if (existingIndex != -1 && existingIndex > canonicalIndex) {
                insertAt = i
                break
            }
        }
        result.add(insertAt, newId)
        return result
    }
}

private data class ScoredSource(
    val source: ResolvedSource,
    val priority: Int,
    val confidence: Double,
)
