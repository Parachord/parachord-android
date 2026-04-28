package com.parachord.shared.api.transport

import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.api.auth.ReauthRequiredException
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

/**
 * Global Ktor plugin that intercepts 401 responses from registered OAuth realms,
 * refreshes the token, and retries the original request.
 *
 * Configured per HttpClient via:
 *   install(OAuthRefreshPlugin) {
 *     tokenProvider = provider
 *     tokenRefresher = refresher
 *     refreshableHosts = mapOf("api.spotify.com" to AuthRealm.Spotify, ...)
 *   }
 *
 * Bearer-only swap on retry — works for Spotify and SoundCloud (the only
 * OAuth-refreshable realms, both Bearer).
 *
 * Single-flight semantics + two-strikes escalation arrive in subsequent tasks.
 *
 * @see docs/plans/2026-04-25-phase-9e-http-architecture-design.md Section 4
 */
class OAuthRefreshPluginConfig {
    var tokenProvider: AuthTokenProvider? = null
    var tokenRefresher: OAuthTokenRefresher? = null
    var refreshableHosts: Map<String, AuthRealm> = emptyMap()
}

val OAuthRefreshPlugin = createClientPlugin("OAuthRefreshPlugin", ::OAuthRefreshPluginConfig) {
    @Suppress("UNUSED_VARIABLE")
    val provider = requireNotNull(pluginConfig.tokenProvider) { "tokenProvider required" }
    val refresher = requireNotNull(pluginConfig.tokenRefresher) { "tokenRefresher required" }
    val refreshableHosts = pluginConfig.refreshableHosts

    client.plugin(HttpSend).intercept { request ->
        val firstAttempt = execute(request)
        if (firstAttempt.response.status != HttpStatusCode.Unauthorized) return@intercept firstAttempt

        val realm = refreshableHosts[request.url.host] ?: return@intercept firstAttempt

        val newToken = refresher.refresh(realm) ?: throw ReauthRequiredException(realm)

        // Replace Authorization header on the retry. Bearer-only swap — works for
        // Spotify and SoundCloud (the only OAuth-refreshable realms, both Bearer).
        request.headers.remove(HttpHeaders.Authorization)
        request.headers.append(HttpHeaders.Authorization, "Bearer ${newToken.accessToken}")

        execute(request)
    }
}
