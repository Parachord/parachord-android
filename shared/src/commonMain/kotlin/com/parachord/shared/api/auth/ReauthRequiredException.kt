package com.parachord.shared.api.auth

/**
 * Thrown by OAuthRefreshPlugin when a 401-refresh-401 sequence indicates
 * the user must re-authenticate. Call sites that care can catch and prompt
 * re-login; otherwise it propagates as a fatal error with a clear realm.
 */
class ReauthRequiredException(val realm: AuthRealm) : Exception("Re-authentication required for $realm")
