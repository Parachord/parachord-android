package com.parachord.shared.api

import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Platform-specific HTTP client factory.
 *
 * Android: uses OkHttp engine (familiar, proven).
 * iOS: uses Darwin engine (URLSession-based, native performance).
 *
 * Plugin install order (matters for Ktor middleware layering):
 *  1. ContentNegotiation — first, so subsequent plugins can read/write JSON bodies
 *  2. Logging — early, sees behavior post-content-negotiation; sanitizes Authorization
 *  3. DefaultRequest — sets User-Agent + baseline headers; before auth so it applies on retries
 *  4. HttpTimeout — last, wraps everything in 60s/15s/30s budget
 *
 * Subsequent task (9E.1.0 Tasks 7-11) inserts `OAuthRefreshPlugin` between
 * DefaultRequest and HttpTimeout.
 */
expect fun createHttpClient(json: Json, appConfig: AppConfig): HttpClient

internal fun HttpClientConfig<*>.installSharedPlugins(json: Json, appConfig: AppConfig) {
    install(ContentNegotiation) { json(json) }
    install(Logging) {
        level = if (appConfig.isDebug) LogLevel.HEADERS else LogLevel.INFO
        sanitizeHeader { it == HttpHeaders.Authorization }
    }
    defaultRequest {
        header(HttpHeaders.UserAgent, appConfig.userAgent)
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000     // AI endpoints take 30–60s (CLAUDE.md "AI generation needs long timeouts")
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
    expectSuccess = false
}
