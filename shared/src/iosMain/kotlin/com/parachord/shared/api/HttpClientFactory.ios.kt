package com.parachord.shared.api

import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.serialization.json.Json

actual fun createHttpClient(json: Json, appConfig: AppConfig): HttpClient = HttpClient(Darwin) {
    installSharedPlugins(json, appConfig)
    engine {
        configureRequest {
            setAllowsCellularAccess(true)
        }
    }
}
