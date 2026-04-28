package com.parachord.shared.api.auth

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AuthInterfacesTest {
    @Test
    fun authTokenProvider_returnsCredentialByRealm() = runBlocking {
        val provider = FakeAuthTokenProvider(
            credentials = mapOf(
                AuthRealm.Spotify to AuthCredential.BearerToken("spotify-token"),
                AuthRealm.LastFm to AuthCredential.LastFmSigned("secret", "sk-key"),
            )
        )
        assertEquals(AuthCredential.BearerToken("spotify-token"), provider.tokenFor(AuthRealm.Spotify))
        assertEquals(AuthCredential.LastFmSigned("secret", "sk-key"), provider.tokenFor(AuthRealm.LastFm))
        assertNull(provider.tokenFor(AuthRealm.Discogs))
    }

    @Test
    fun authTokenProvider_invalidateRemovesCredential() = runBlocking {
        val provider = FakeAuthTokenProvider(
            credentials = mutableMapOf(AuthRealm.Spotify to AuthCredential.BearerToken("token"))
        )
        provider.invalidate(AuthRealm.Spotify)
        assertNull(provider.tokenFor(AuthRealm.Spotify))
    }

    @Test
    fun oauthRefresher_returnsNewTokenOnSuccess() = runBlocking {
        val refresher = FakeOAuthTokenRefresher(
            results = mapOf(AuthRealm.Spotify to AuthCredential.BearerToken("new-token"))
        )
        assertEquals(AuthCredential.BearerToken("new-token"), refresher.refresh(AuthRealm.Spotify))
    }

    @Test
    fun oauthRefresher_returnsNullOnRefreshFailure() = runBlocking {
        val refresher = FakeOAuthTokenRefresher(results = emptyMap())
        assertNull(refresher.refresh(AuthRealm.Spotify))
    }

    @Test
    fun reauthRequired_carriesRealm() {
        val ex = assertFailsWith<ReauthRequiredException> {
            throw ReauthRequiredException(AuthRealm.Spotify)
        }
        assertEquals(AuthRealm.Spotify, ex.realm)
    }
}

private class FakeAuthTokenProvider(
    credentials: Map<AuthRealm, AuthCredential>,
) : AuthTokenProvider {
    private val credentials: MutableMap<AuthRealm, AuthCredential> = credentials.toMutableMap()
    override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = credentials[realm]
    override suspend fun invalidate(realm: AuthRealm) { credentials.remove(realm) }
}

private class FakeOAuthTokenRefresher(
    private val results: Map<AuthRealm, AuthCredential.BearerToken?>,
) : OAuthTokenRefresher {
    override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = results[realm]
}
