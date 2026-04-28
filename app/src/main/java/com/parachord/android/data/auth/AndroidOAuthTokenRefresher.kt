package com.parachord.android.data.auth

import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.OAuthTokenRefresher
import kotlinx.coroutines.CancellationException

/**
 * Android implementation of [OAuthTokenRefresher].
 *
 * Delegates to the existing [OAuthManager] for OAuth-refreshable realms.
 * `OAuthManager.refreshSpotifyToken()` returns a Boolean (success/fail);
 * on success, the new access token is persisted to SettingsStore by
 * OAuthManager itself, so we read it back to wrap in the [AuthCredential].
 *
 * Only Spotify is wired in 9E.1.0. SoundCloud (also OAuth-refreshable) is
 * deferred until its native client migrates from raw OkHttp calls in a
 * later 9E phase.
 */
class AndroidOAuthTokenRefresher(
    private val oauthManager: OAuthManager,
    private val settings: SettingsStore,
) : OAuthTokenRefresher {

    override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = when (realm) {
        AuthRealm.Spotify -> {
            try {
                if (oauthManager.refreshSpotifyToken()) {
                    settings.getSpotifyAccessToken()?.let(AuthCredential::BearerToken)
                } else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        else -> null  // Other realms not OAuth-refreshable
    }
}
