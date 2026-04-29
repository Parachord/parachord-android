@file:Suppress("unused")
package com.parachord.android.ai

/**
 * Source-compat typealiases. The real implementation moved to
 * `com.parachord.shared.ai.AiRecommendationService`. The 3 concrete provider
 * classes (`ChatGptProvider` / `ClaudeProvider` / `GeminiProvider`) are
 * assembled into a `Map<String, AiProviderEntry>` in `AndroidModule`. The
 * `Context+File` disk cache is wired in via `cacheRead`/`cacheWrite` suspend
 * lambdas pointing at `<filesDir>/ai_suggestions_cache.json`.
 */
typealias AiRecommendationService = com.parachord.shared.ai.AiRecommendationService
typealias AiAlbumSuggestion = com.parachord.shared.ai.AiAlbumSuggestion
typealias AiArtistSuggestion = com.parachord.shared.ai.AiArtistSuggestion
typealias AiRecommendations = com.parachord.shared.ai.AiRecommendations
typealias AiProviderEntry = com.parachord.shared.ai.AiProviderEntry
