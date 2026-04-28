package com.parachord.android.data.auth

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.config.AppConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidAuthTokenProviderTest {

    private fun makeAppConfig() = AppConfig(
        lastFmSharedSecret = "shared-secret",
        appleMusicDeveloperToken = "dev-jwt",
    )

    @Test
    fun spotifyRealm_returnsBearerTokenFromStore() = runBlocking {
        val store = mockk<com.parachord.android.data.store.SettingsStore>()
        coEvery { store.getSpotifyAccessToken() } returns "spotify-token"
        val provider = AndroidAuthTokenProvider(store, makeAppConfig())

        val cred = provider.tokenFor(AuthRealm.Spotify)
        assertEquals(AuthCredential.BearerToken("spotify-token"), cred)
    }

    @Test
    fun spotifyRealm_returnsNullWhenNotAuthed() = runBlocking {
        val store = mockk<com.parachord.android.data.store.SettingsStore>()
        coEvery { store.getSpotifyAccessToken() } returns null
        val provider = AndroidAuthTokenProvider(store, makeAppConfig())
        assertNull(provider.tokenFor(AuthRealm.Spotify))
    }

    @Test
    fun appleMusicLibrary_returnsBearerWithMUT_whenBothPresent() = runBlocking {
        val store = mockk<com.parachord.android.data.store.SettingsStore>()
        coEvery { store.getAppleMusicUserToken() } returns "mut-token"
        val provider = AndroidAuthTokenProvider(store, makeAppConfig())

        val cred = provider.tokenFor(AuthRealm.AppleMusicLibrary)
        assertEquals(AuthCredential.BearerWithMUT(devToken = "dev-jwt", mut = "mut-token"), cred)
    }

    @Test
    fun appleMusicLibrary_returnsNullWhenMutMissing() = runBlocking {
        val store = mockk<com.parachord.android.data.store.SettingsStore>()
        coEvery { store.getAppleMusicUserToken() } returns null
        val provider = AndroidAuthTokenProvider(store, makeAppConfig())
        assertNull(provider.tokenFor(AuthRealm.AppleMusicLibrary))
    }

    @Test
    fun lastFm_returnsLastFmSignedWithSecretAndSession() = runBlocking {
        val store = mockk<com.parachord.android.data.store.SettingsStore>()
        coEvery { store.getLastFmSessionKey() } returns "sk-key"
        val provider = AndroidAuthTokenProvider(store, makeAppConfig())

        val cred = provider.tokenFor(AuthRealm.LastFm) as? AuthCredential.LastFmSigned
        assertEquals("shared-secret", cred?.sharedSecret)
        assertEquals("sk-key", cred?.sessionKey)
    }

    @Test
    fun listenBrainz_returnsTokenPrefixedWithToken() = runBlocking {
        val store = mockk<com.parachord.android.data.store.SettingsStore>()
        coEvery { store.getListenBrainzToken() } returns "lb-user-token"
        val provider = AndroidAuthTokenProvider(store, makeAppConfig())

        val cred = provider.tokenFor(AuthRealm.ListenBrainz) as? AuthCredential.TokenPrefixed
        assertEquals("Token", cred?.prefix)
        assertEquals("lb-user-token", cred?.token)
    }

    @Test
    fun ticketmaster_returnsApiKeyParam() = runBlocking {
        val store = mockk<com.parachord.android.data.store.SettingsStore>()
        coEvery { store.getTicketmasterApiKey() } returns "tm-key"
        val provider = AndroidAuthTokenProvider(store, makeAppConfig())

        val cred = provider.tokenFor(AuthRealm.Ticketmaster) as? AuthCredential.ApiKeyParam
        assertEquals("apikey", cred?.paramName)
        assertEquals("tm-key", cred?.value)
    }

    @Test
    fun seatGeek_returnsApiKeyParam() = runBlocking {
        val store = mockk<com.parachord.android.data.store.SettingsStore>()
        coEvery { store.getSeatGeekClientId() } returns "sg-id"
        val provider = AndroidAuthTokenProvider(store, makeAppConfig())

        val cred = provider.tokenFor(AuthRealm.SeatGeek) as? AuthCredential.ApiKeyParam
        assertEquals("client_id", cred?.paramName)
        assertEquals("sg-id", cred?.value)
    }

    @Test
    fun discogs_returnsTokenPrefixed() = runBlocking {
        val store = mockk<com.parachord.android.data.store.SettingsStore>()
        coEvery { store.getDiscogsToken() } returns "discogs-tok"
        val provider = AndroidAuthTokenProvider(store, makeAppConfig())

        val cred = provider.tokenFor(AuthRealm.Discogs) as? AuthCredential.TokenPrefixed
        assertEquals("Discogs token=", cred?.prefix)
        assertEquals("discogs-tok", cred?.token)
    }
}
