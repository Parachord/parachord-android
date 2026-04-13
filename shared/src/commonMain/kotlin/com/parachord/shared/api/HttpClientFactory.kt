package com.parachord.shared.api

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

/**
 * Platform-specific HTTP client factory.
 *
 * Android: uses OkHttp engine (familiar, proven, integrates with existing interceptors).
 * iOS: uses Darwin engine (URLSession-based, native performance).
 */
expect fun createHttpClient(json: Json): HttpClient
