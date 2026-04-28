package com.parachord.shared.api

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HttpClientFactoryTest {
    private val appConfig = AppConfig(
        userAgent = "Parachord/0.5.0-test (Android; https://parachord.app)",
        isDebug = true,
    )
    private val json = Json { ignoreUnknownKeys = true }

    private val stubProvider = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = null
        override suspend fun invalidate(realm: AuthRealm) {}
    }
    private val stubRefresher = object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
    }

    @Test
    fun userAgent_setOnEveryRequest() = runBlocking {
        var capturedUA: String? = null
        val mock = MockEngine { request ->
            capturedUA = request.headers[HttpHeaders.UserAgent]
            respond("ok", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = HttpClient(mock) {
            installSharedPlugins(json, appConfig, stubProvider, stubRefresher)
        }
        client.get("https://example.com/test")
        assertEquals("Parachord/0.5.0-test (Android; https://parachord.app)", capturedUA)
    }

    @Test
    fun httpTimeout_pluginInstalled() {
        val client = HttpClient(MockEngine { respond("ok") }) {
            installSharedPlugins(json, appConfig, stubProvider, stubRefresher)
        }
        assertNotNull(client.pluginOrNull(HttpTimeout))
    }

    @Test
    fun status404_doesNotThrow_expectSuccessIsFalse() = runBlocking {
        // expectSuccess=false means call sites pattern-match status, not catch exceptions.
        val mock = MockEngine { respond("not found", HttpStatusCode.NotFound) }
        val client = HttpClient(mock) {
            installSharedPlugins(json, appConfig, stubProvider, stubRefresher)
        }
        // If expectSuccess were true, this would throw ClientRequestException.
        val response = client.get("https://example.com/missing")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
