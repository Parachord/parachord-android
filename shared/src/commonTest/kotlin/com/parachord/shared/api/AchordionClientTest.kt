package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AchordionClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun buildClient(engine: MockEngine, token: String = "test-token"): AchordionClient {
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return AchordionClient(httpClient, token)
    }

    // ── fetchEntityLink ─────────────────────────────────────────────

    @Test
    fun fetchEntityLink_returnsCanonicalUrl_whenApiReturns200() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"url":"https://achordion.xyz/recording/slowdive-sugar","name":"Sugar For The Pill"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = buildClient(engine)
        val result = client.fetchEntityLink(EntityType.Track, "abc-mbid")
        assertEquals("https://achordion.xyz/recording/slowdive-sugar", result?.url)
        assertEquals("Sugar For The Pill", result?.name)
    }

    @Test
    fun fetchEntityLink_returnsNull_on404() = runTest {
        val engine = MockEngine { _ ->
            respond(content = """{"error":"Not Found"}""", status = HttpStatusCode.NotFound)
        }
        val client = buildClient(engine)
        assertNull(client.fetchEntityLink(EntityType.Track, "abc-mbid"))
    }

    @Test
    fun fetchEntityLink_returnsNull_onMalformedResponse() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"unexpected":"shape"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = buildClient(engine)
        // SerializationException on missing required `url` field → swallowed → null.
        assertNull(client.fetchEntityLink(EntityType.Track, "abc-mbid"))
    }

    @Test
    fun fetchEntityLink_sendsBearerTokenInAuthHeader() = runTest {
        var seenAuth: String? = null
        val engine = MockEngine { req ->
            seenAuth = req.headers["Authorization"]
            respond(
                """{"url":"https://achordion.xyz/recording/x"}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }
        val client = buildClient(engine, token = "secret-bearer")
        client.fetchEntityLink(EntityType.Track, "abc-mbid")
        assertEquals("Bearer secret-bearer", seenAuth)
    }

    @Test
    fun fetchEntityLink_encodesMbidAndTypeInQueryString() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { req ->
            seenUrl = req.url.toString()
            respond(
                """{"url":"https://achordion.xyz/release-group/x"}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }
        val client = buildClient(engine)
        client.fetchEntityLink(EntityType.ReleaseGroup, "554b417d-8885-41e6-86c7-ae935e62d571")
        val url = checkNotNull(seenUrl)
        assertEquals(true, url.startsWith("https://achordion.xyz/api/entity-link?"))
        assertEquals(true, url.contains("type=release-group"))
        assertEquals(true, url.contains("mbid=554b417d-8885-41e6-86c7-ae935e62d571"))
    }

    @Test
    fun fetchEntityLink_includeNamesAddsQueryParam() = runTest {
        var seenUrl: String? = null
        val engine = MockEngine { req ->
            seenUrl = req.url.toString()
            respond(
                """{"url":"https://achordion.xyz/x"}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }
        val client = buildClient(engine)
        client.fetchEntityLink(EntityType.Track, "abc", includeNames = true)
        assertEquals(true, seenUrl?.contains("include=names"))
    }

    @Test
    fun fetchEntityLink_emptyToken_returnsNull_doesNotHitNetwork() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls += 1
            respond("""{"url":"x"}""", HttpStatusCode.OK)
        }
        val client = buildClient(engine, token = "")
        assertNull(client.fetchEntityLink(EntityType.Track, "abc"))
        assertEquals(0, calls)
    }

    @Test
    fun fetchEntityLink_after401_subsequentCallsShortCircuit() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls += 1
            respond(content = "", status = HttpStatusCode.Unauthorized)
        }
        val client = buildClient(engine)
        assertNull(client.fetchEntityLink(EntityType.Track, "first"))
        assertNull(client.fetchEntityLink(EntityType.Track, "second"))
        assertNull(client.fetchEntityLink(EntityType.Track, "third"))
        assertEquals(1, calls)   // only the first call hits the network
    }
}
