package com.parachord.shared.di

import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.GeoLocationClient
import com.parachord.shared.api.LastFmClient
import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.api.SeatGeekClient
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.api.TicketmasterClient
import com.parachord.shared.api.createHttpClient
import kotlinx.serialization.json.Json
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Shared Koin module — provides cross-platform dependencies.
 * Used by both Android and iOS apps.
 */
val sharedModule = module {
    // JSON
    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }

    // HTTP Client (platform engine via expect/actual)
    single { createHttpClient(get(), get(), get(), get()) }

    // API Clients (Ktor, cross-platform)
    single { SpotifyClient(get()) }
    single { LastFmClient(get()) }
    single { MusicBrainzClient(get()) }
    single { TicketmasterClient(get()) }
    single { SeatGeekClient(get()) }
    single { AppleMusicClient(get()) }
    single { GeoLocationClient(get()) }
}
