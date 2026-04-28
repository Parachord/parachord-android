package com.parachord.shared.api

import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

actual fun createHttpClient(
    json: Json,
    appConfig: AppConfig,
    authProvider: AuthTokenProvider,
    tokenRefresher: OAuthTokenRefresher,
): HttpClient = HttpClient(OkHttp) {
    installSharedPlugins(json, appConfig, authProvider, tokenRefresher)
    engine {
        config {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(30, TimeUnit.SECONDS)
        }
    }
}
