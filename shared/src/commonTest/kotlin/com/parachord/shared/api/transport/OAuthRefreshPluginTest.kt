package com.parachord.shared.api.transport

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.api.auth.ReauthRequiredException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun status401_fromRegisteredHost_triggersRefreshAndRetry() = runBlocking {
        var requestCount = 0
        val mock = MockEngine { request ->
            requestCount++
            when (requestCount) {
                1 -> respond("", HttpStatusCode.Unauthorized)
                2 -> {
                    // Verify the retry has the new bearer.
                    val authHeader = request.headers["Authorization"]
                    assertEquals("Bearer refreshed", authHeader)
                    respond("ok", HttpStatusCode.OK)
                }
                else -> error("unexpected request $requestCount")
            }
        }
        provider.setToken(AuthRealm.Spotify, "stale")
        refresher.nextResult = AuthCredential.BearerToken("refreshed")

        val client = HttpClient(mock) {
            install(OAuthRefreshPlugin) {
                tokenProvider = provider
                tokenRefresher = refresher
                refreshableHosts = mapOf("api.spotify.com" to AuthRealm.Spotify)
            }
        }
        val response = client.get("https://api.spotify.com/v1/me") {
            headers { append("Authorization", "Bearer stale") }
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, requestCount)
        assertEquals(1, refresher.refreshCalls)
    }

    @Test
    fun concurrent401s_singleFlight_oneRefreshCallForFiveConcurrentRequests() = runBlocking {
        val mock = MockEngine { request ->
            val auth = request.headers["Authorization"] ?: ""
            if (auth.contains("stale")) respond("", HttpStatusCode.Unauthorized)
            else respond("ok", HttpStatusCode.OK)
        }
        provider.setToken(AuthRealm.Spotify, "stale")
        refresher.nextResult = AuthCredential.BearerToken("refreshed")

        val client = HttpClient(mock) {
            install(OAuthRefreshPlugin) {
                tokenProvider = provider
                tokenRefresher = refresher
                refreshableHosts = mapOf("api.spotify.com" to AuthRealm.Spotify)
            }
        }

        // Fire 5 concurrent requests — all will 401 first, then need refresh
        val results = (1..5).map {
            async {
                client.get("https://api.spotify.com/v1/me/$it") {
                    headers { append("Authorization", "Bearer stale") }
                }.status
            }
        }.awaitAll()

        assertEquals(5, results.size)
        assertEquals(true, results.all { it == HttpStatusCode.OK })
        assertEquals(1, refresher.refreshCalls, "expected single-flight: only one refresh call")
    }

    @Test
    fun two401InARow_throwsReauthRequired() = runBlocking {
        val mock = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        provider.setToken(AuthRealm.Spotify, "stale")
        refresher.nextResult = AuthCredential.BearerToken("also-bad")

        val client = HttpClient(mock) {
            install(OAuthRefreshPlugin) {
                tokenProvider = provider
                tokenRefresher = refresher
                refreshableHosts = mapOf("api.spotify.com" to AuthRealm.Spotify)
            }
        }

        val ex = assertFailsWith<ReauthRequiredException> {
            client.get("https://api.spotify.com/v1/me") {
                headers { append("Authorization", "Bearer stale") }
            }
        }
        assertEquals(AuthRealm.Spotify, ex.realm)
    }

    @Test
    fun refreshReturnsNull_throwsReauthRequired() = runBlocking {
        val mock = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        provider.setToken(AuthRealm.Spotify, "stale")
        refresher.nextResult = null  // refresh failed

        val client = HttpClient(mock) {
            install(OAuthRefreshPlugin) {
                tokenProvider = provider
                tokenRefresher = refresher
                refreshableHosts = mapOf("api.spotify.com" to AuthRealm.Spotify)
            }
        }

        val ex = assertFailsWith<ReauthRequiredException> {
            client.get("https://api.spotify.com/v1/me") {
                headers { append("Authorization", "Bearer stale") }
            }
        }
        assertEquals(AuthRealm.Spotify, ex.realm)
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
