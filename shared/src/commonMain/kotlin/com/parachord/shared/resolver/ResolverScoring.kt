package com.parachord.shared.resolver

/**
 * Ports the desktop app's resolver priority + confidence scoring logic.
 *
 * Source selection uses three stages:
 * 1. Minimum confidence filter — discard sources below [MIN_CONFIDENCE_THRESHOLD]
 *    (filters out "no match" results where neither title nor artist matched)
 * 2. Resolver priority — user-configured ordering (lower index = higher priority)
 * 3. Confidence score — match quality tiebreaker within the same priority tier
 *
 * A Spotify result at 70% confidence beats a SoundCloud result at 95%
 * when the user ranks Spotify higher. But a Spotify result at 50%
 * confidence (no match) is filtered out, allowing the correct local
 * file or lower-priority result to win.
 */
class ResolverScoring constructor(
    private val getResolverOrder: suspend () -> List<String>,
    private val getActiveResolvers: suspend () -> List<String>,
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

        /**
         * Minimum confidence threshold for a source to be considered in selection.
         * Sources below this are filtered out before priority sorting.
         *
         * scoreConfidence() returns 0.50 for "no match" (neither title nor artist
         * matched). Without this threshold, a wrong-song Spotify result at 0.50
         * would beat a correct local file at 0.95 due to priority ordering.
         *
         * The desktop app handles this via noMatch:true sentinel filtering;
         * we use an equivalent confidence floor.
         */
        const val MIN_CONFIDENCE_THRESHOLD = 0.60
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
        if (sources.size == 1) {
            val only = sources.first()
            return if ((only.confidence ?: 0.0) >= MIN_CONFIDENCE_THRESHOLD) only else null
        }

        val resolverOrder = getResolverOrder()
        val activeResolvers = getActiveResolvers()

        return sources
            .filter { activeResolvers.isEmpty() || it.resolver in activeResolvers }
            .filter { (it.confidence ?: 0.0) >= MIN_CONFIDENCE_THRESHOLD }
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
