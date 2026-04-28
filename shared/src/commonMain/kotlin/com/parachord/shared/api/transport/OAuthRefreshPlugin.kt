package com.parachord.shared.api.transport

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.api.auth.ReauthRequiredException
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Bearer-only swap on retry — works for Spotify and SoundCloud (the only
 * OAuth-refreshable realms, both Bearer).
 *
 * Single-flight per realm: N concurrent 401s for the same realm only
 * trigger ONE refresh request; followers reuse the freshly cached token.
 *
 * Two-strikes escalation: if the retry after a successful refresh ALSO
 * returns 401, throw ReauthRequiredException so the call site can prompt
 * re-login. Same exception is thrown when the refresher returns null
 * (refresh token revoked / network error / etc.).
 *
 * @see docs/plans/2026-04-25-phase-9e-http-architecture-design.md Section 4
 */
class OAuthRefreshPluginConfig {
    var tokenProvider: AuthTokenProvider? = null
    var tokenRefresher: OAuthTokenRefresher? = null
    var refreshableHosts: Map<String, AuthRealm> = emptyMap()
}

val OAuthRefreshPlugin = createClientPlugin("OAuthRefreshPlugin", ::OAuthRefreshPluginConfig) {
    val provider = requireNotNull(pluginConfig.tokenProvider) { "tokenProvider required" }
    val refresher = requireNotNull(pluginConfig.tokenRefresher) { "tokenRefresher required" }
    val refreshableHosts = pluginConfig.refreshableHosts

    val mutexes = mutableMapOf<AuthRealm, Mutex>()
    val mutexesGuard = Mutex()

    suspend fun mutexFor(realm: AuthRealm): Mutex = mutexesGuard.withLock {
        mutexes.getOrPut(realm) { Mutex() }
    }

    suspend fun singleFlightRefresh(
        realm: AuthRealm,
        originalToken: String?,
    ): AuthCredential.BearerToken? {
        return mutexFor(realm).withLock {
            // Thundering-herd check: did a previous holder of this mutex refresh?
            val current = provider.tokenFor(realm) as? AuthCredential.BearerToken
            if (current != null && current.accessToken != originalToken) {
                return@withLock current
            }
            refresher.refresh(realm)
        }
    }

    client.plugin(HttpSend).intercept { request ->
        val firstAttempt = execute(request)
        if (firstAttempt.response.status != HttpStatusCode.Unauthorized) return@intercept firstAttempt

        val realm = refreshableHosts[request.url.host] ?: return@intercept firstAttempt
        val originalAuth = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")

        val newToken = singleFlightRefresh(realm, originalAuth)
            ?: throw ReauthRequiredException(realm)

        request.headers.remove(HttpHeaders.Authorization)
        request.headers.append(HttpHeaders.Authorization, "Bearer ${newToken.accessToken}")

        val secondAttempt = execute(request)
        if (secondAttempt.response.status == HttpStatusCode.Unauthorized) {
            throw ReauthRequiredException(realm)
        }
        secondAttempt
    }
}
