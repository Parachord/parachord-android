package com.parachord.android.data.auth

import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.config.AppConfig

/**
 * Android implementation of [AuthTokenProvider].
 *
 * Reads from [SettingsStore] (which wraps SecureTokenStore /
 * EncryptedSharedPreferences for secure tokens; DataStore for non-secure
 * config). Static credentials (Apple Music dev token, Last.fm shared secret)
 * come from [AppConfig] populated from BuildConfig at startup.
 *
 * After Phase 9B (SettingsStore -> multiplatform-settings), this implementation
 * moves to commonMain with platform-specific Keychain/SharedPreferences
 * actuals.
 */
class AndroidAuthTokenProvider(
    private val settings: SettingsStore,
    private val appConfig: AppConfig,
) : AuthTokenProvider {

    override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = when (realm) {
        AuthRealm.Spotify ->
            settings.getSpotifyAccessToken()?.let(AuthCredential::BearerToken)

        AuthRealm.AppleMusicLibrary ->
            settings.getAppleMusicUserToken()?.let { mut ->
                AuthCredential.BearerWithMUT(devToken = appConfig.appleMusicDeveloperToken, mut = mut)
            }

        AuthRealm.ListenBrainz ->
            settings.getListenBrainzToken()?.let {
                AuthCredential.TokenPrefixed(prefix = "Token", token = it)
            }

        AuthRealm.LastFm ->
            // Always returns a LastFmSigned with the shared secret; sessionKey is
            // null until the user completes the Last.fm auth flow.
            AuthCredential.LastFmSigned(
                sharedSecret = appConfig.lastFmSharedSecret,
                sessionKey = settings.getLastFmSessionKey(),
            )

        AuthRealm.Ticketmaster ->
            settings.getTicketmasterApiKey()?.let {
                AuthCredential.ApiKeyParam(paramName = "apikey", value = it)
            }

        AuthRealm.SeatGeek ->
            settings.getSeatGeekClientId()?.let {
                AuthCredential.ApiKeyParam(paramName = "client_id", value = it)
            }

        AuthRealm.Discogs ->
            settings.getDiscogsToken()?.let {
                AuthCredential.TokenPrefixed(prefix = "Discogs token=", token = it)
            }
    }

    override suspend fun invalidate(realm: AuthRealm) {
        // Best-effort clear via existing SettingsStore methods. Where no dedicated
        // clear method exists, fall back to setting an empty value (which the
        // typed getter treats as "not authed" for downstream consumers).
        when (realm) {
            AuthRealm.Spotify -> settings.clearSpotifyTokens()
            AuthRealm.AppleMusicLibrary -> settings.setAppleMusicUserToken("")
            AuthRealm.ListenBrainz -> settings.clearListenBrainzToken()
            AuthRealm.LastFm -> settings.setLastFmSession("")
            AuthRealm.Ticketmaster -> settings.clearTicketmasterApiKey()
            AuthRealm.SeatGeek -> settings.clearSeatGeekClientId()
            AuthRealm.Discogs -> settings.setDiscogsToken("")
        }
    }
}
