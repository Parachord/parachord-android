package com.parachord.shared.api.auth

/**
 * Request-time authentication credential lookup. Cheap; no network.
 *
 * Implementations read from secure storage (SecureTokenStore on Android,
 * Keychain on iOS) and non-secure prefs (SettingsStore / multiplatform-settings).
 *
 * @see OAuthTokenRefresher for response-time token refresh on 401.
 */
interface AuthTokenProvider {
    /** Returns the cached credential for [realm], or null if not authenticated. */
    suspend fun tokenFor(realm: AuthRealm): AuthCredential?

    /** Marks the [realm]'s credential as invalid (e.g., after a confirmed reauth requirement). */
    suspend fun invalidate(realm: AuthRealm)
}
