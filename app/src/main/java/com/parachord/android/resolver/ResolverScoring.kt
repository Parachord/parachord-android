package com.parachord.android.resolver

import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.resolver.ResolvedSource

/**
 * Android wrapper for the shared [com.parachord.shared.resolver.ResolverScoring].
 * Bridges the SettingsStore dependency to the shared module's function-based API.
 */
class ResolverScoring constructor(
    private val settingsStore: SettingsStore,
) {
    private val shared = com.parachord.shared.resolver.ResolverScoring(
        getResolverOrder = { settingsStore.getResolverOrder() },
        getActiveResolvers = { settingsStore.getActiveResolvers() },
    )

    companion object {
        val CANONICAL_RESOLVER_ORDER = com.parachord.shared.resolver.ResolverScoring.CANONICAL_RESOLVER_ORDER
        const val MIN_CONFIDENCE_THRESHOLD = com.parachord.shared.resolver.ResolverScoring.MIN_CONFIDENCE_THRESHOLD
    }

    suspend fun selectBest(
        sources: List<ResolvedSource>,
        preferredResolver: String? = null,
    ): ResolvedSource? = shared.selectBest(sources, preferredResolver)

    fun insertInCanonicalOrder(order: List<String>, newId: String): List<String> =
        shared.insertInCanonicalOrder(order, newId)
}
