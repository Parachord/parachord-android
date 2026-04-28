package com.parachord.shared.api.transport

import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import io.ktor.client.plugins.api.createClientPlugin

/**
 * Global Ktor plugin that intercepts 401 responses from registered OAuth realms,
 * refreshes the token (single-flight per realm), and retries the original request.
 *
 * Configured per HttpClient via:
 *   install(OAuthRefreshPlugin) {
 *     tokenProvider = provider
 *     tokenRefresher = refresher
 *     refreshableHosts = mapOf("api.spotify.com" to AuthRealm.Spotify, ...)
 *   }
 *
 * Subsequent tasks in 9E.1.0 add 401-detection (Task 8), single-flight refresh
 * (Task 9), and two-strikes ReauthRequiredException (Task 10).
 *
 * @see docs/plans/2026-04-25-phase-9e-http-architecture-design.md Section 4
 */
class OAuthRefreshPluginConfig {
    var tokenProvider: AuthTokenProvider? = null
    var tokenRefresher: OAuthTokenRefresher? = null
    var refreshableHosts: Map<String, AuthRealm> = emptyMap()
}

val OAuthRefreshPlugin = createClientPlugin("OAuthRefreshPlugin", ::OAuthRefreshPluginConfig) {
    requireNotNull(pluginConfig.tokenProvider) { "OAuthRefreshPlugin requires tokenProvider" }
    requireNotNull(pluginConfig.tokenRefresher) { "OAuthRefreshPlugin requires tokenRefresher" }
    // Phase 1 (Task 7): no-op — passthrough. Tasks 8-10 add the interception logic.
}
