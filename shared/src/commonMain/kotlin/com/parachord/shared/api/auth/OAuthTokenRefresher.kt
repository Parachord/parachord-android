package com.parachord.shared.api.auth

/**
 * Response-time OAuth refresh. Invoked only when a 401 confirms a cached
 * token is dead. Performs a network refresh against the provider's
 * /api/token endpoint, persists the new token via SecureTokenStore, and
 * returns the new credential.
 *
 * Returns null if refresh fails (refresh token revoked / network error
 * persisting the new token / etc.) — caller should escalate to
 * [ReauthRequiredException].
 */
interface OAuthTokenRefresher {
    suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken?
}
