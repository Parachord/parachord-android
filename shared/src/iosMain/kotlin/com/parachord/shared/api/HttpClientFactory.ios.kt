package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

actual fun createHttpClient(json: Json): HttpClient = HttpClient(Darwin) {
    install(ContentNegotiation) { json(json) }
    install(Logging) {
        level = LogLevel.HEADERS
        logger = object : io.ktor.client.plugins.logging.Logger {
            override fun log(message: String) {
                println("KtorHttp: $message")
            }
        }
    }
    engine {
        configureRequest {
            setAllowsCellularAccess(true)
        }
    }
}
