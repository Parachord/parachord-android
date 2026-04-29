@file:Suppress("unused")
package com.parachord.android.ai.providers

/**
 * Source-compat typealias. The real implementation moved to
 * `com.parachord.shared.ai.providers.GeminiProvider` and now uses the
 * shared Ktor HttpClient (was OkHttp).
 */
typealias GeminiProvider = com.parachord.shared.ai.providers.GeminiProvider
