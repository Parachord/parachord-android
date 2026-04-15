package com.parachord.shared.config

/**
 * Platform-agnostic access to API keys and build configuration.
 *
 * On Android these come from BuildConfig (generated from local.properties).
 * On iOS these come from Info.plist or a similar configuration mechanism.
 *
 * Populated at startup via DI. Not expect/actual because BuildConfig
 * is generated in the :app module, which :shared can't reference.
 */
data class AppConfig(
    val lastFmApiKey: String = "",
    val lastFmSharedSecret: String = "",
    val spotifyClientId: String = "",
    val soundCloudClientId: String = "",
    val soundCloudClientSecret: String = "",
    val appleMusicDeveloperToken: String = "",
    val ticketmasterApiKey: String = "",
    val seatGeekClientId: String = "",
)
