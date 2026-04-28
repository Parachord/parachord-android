package com.parachord.shared.api.transport

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthRefreshPluginTest {

    private val provider = StubProvider()
    private val refresher = StubRefresher(provider)

    @Test
    fun status200_passesThroughUnchanged() = runBlocking {
        val client = HttpClient(MockEngine { respond("hello", HttpStatusCode.OK) }) {
            install(OAuthRefreshPlugin) {
                tokenProvider = provider
                tokenRefresher = refresher
                refreshableHosts = mapOf("api.spotify.com" to AuthRealm.Spotify)
            }
        }
        val response = client.get("https://api.spotify.com/v1/me")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("hello", response.bodyAsText())
        assertEquals(0, refresher.refreshCalls)
    }

    @Test
    fun status401_fromUnregisteredHost_passesThroughUnchanged() = runBlocking {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) }) {
            install(OAuthRefreshPlugin) {
                tokenProvider = provider
                tokenRefresher = refresher
                refreshableHosts = mapOf("api.spotify.com" to AuthRealm.Spotify)
            }
        }
        val response = client.get("https://api.lastfm.com/foo")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(0, refresher.refreshCalls)
    }
}

internal class StubProvider(
    private val tokens: MutableMap<AuthRealm, AuthCredential> = mutableMapOf(),
) : AuthTokenProvider {
    override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = tokens[realm]
    override suspend fun invalidate(realm: AuthRealm) { tokens.remove(realm) }
    fun setToken(realm: AuthRealm, token: String) { tokens[realm] = AuthCredential.BearerToken(token) }
    fun setBearer(realm: AuthRealm, token: AuthCredential.BearerToken) { tokens[realm] = token }
}

internal class StubRefresher(
    private val provider: StubProvider,
) : OAuthTokenRefresher {
    var refreshCalls = 0
    var nextResult: AuthCredential.BearerToken? = AuthCredential.BearerToken("refreshed")
    override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? {
        refreshCalls++
        nextResult?.let { provider.setBearer(realm, it) }
        return nextResult
    }
}
