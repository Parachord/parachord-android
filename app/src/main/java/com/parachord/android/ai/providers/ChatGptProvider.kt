@file:Suppress("unused")
package com.parachord.android.ai.providers

/**
 * Source-compat typealias. The real implementation moved to
 * `com.parachord.shared.ai.providers.ChatGptProvider` and now uses the
 * shared Ktor HttpClient (was OkHttp). The shared Ktor client already
 * has a 60s requestTimeoutMillis configured in HttpClientFactory.
 */
typealias ChatGptProvider = com.parachord.shared.ai.providers.ChatGptProvider
