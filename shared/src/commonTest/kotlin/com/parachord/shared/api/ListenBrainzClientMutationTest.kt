package com.parachord.shared.api

import com.parachord.shared.sync.ListenBrainzUnauthorizedException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MockEngine tests for [ListenBrainzClient] playlist mutation endpoints
 * added in Phase 2 of the ListenBrainz sync provider work (issue #156,
 * Task 6).
 *
 * Endpoints under test:
 *  - POST /1/playlist/create
 *  - POST /1/playlist/edit/{mbid}
 *  - POST /1/playlist/{mbid}/delete
 *
 * Each method must:
 *  - send `Authorization: Token <token>` header
 *  - throw [ListenBrainzUnauthorizedException] on HTTP 401
 *  - shape the body per the LB JSPF spec (create/edit only)
 */
class ListenBrainzClientMutationTest {

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    private fun client(engine: MockEngine) = ListenBrainzClient(
        HttpClient(engine) {
            install(ContentNegotiation) { json() }
        },
    )

    private suspend fun bodyJson(request: HttpRequestData): JsonObject {
        val text = request.body.toByteArray().decodeToString()
        return Json.parseToJsonElement(text).jsonObject
    }

    // ── createPlaylist ───────────────────────────────────────────────────────

    @Test
    fun createPlaylist_sendsCorrectRequestAndParsesMbid() = runTest {
        var seenUrl: String? = null
        var seenAuth: String? = null
        var seenMethod: HttpMethod? = null
        var seenBody: JsonObject? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            seenAuth = request.headers["Authorization"]
            seenMethod = request.method
            seenBody = bodyJson(request)
            respond(
                """{"playlist_mbid":"12345678-1234-1234-1234-123456789012","status":"ok"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }

        val mbid = client(engine).createPlaylist(
            name = "Test Playlist",
            description = "A test",
            isPublic = true,
            token = "tok",
        )

        assertEquals("12345678-1234-1234-1234-123456789012", mbid)
        assertEquals("https://api.listenbrainz.org/1/playlist/create", seenUrl)
        assertEquals(HttpMethod.Post, seenMethod)
        assertEquals("Token tok", seenAuth)

        val playlist = seenBody!!["playlist"]!!.jsonObject
        assertEquals("Test Playlist", playlist["title"]!!.jsonPrimitive.content)
        assertEquals("A test", playlist["annotation"]!!.jsonPrimitive.content)
        val ext = playlist["extension"]!!.jsonObject
        val jspfExt = ext["https://musicbrainz.org/doc/jspf#playlist"]!!.jsonObject
        assertTrue(jspfExt["public"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun createPlaylist_omitsDescriptionWhenNull() = runTest {
        var seenBody: JsonObject? = null
        val engine = MockEngine { request ->
            seenBody = bodyJson(request)
            respond(
                """{"playlist_mbid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","status":"ok"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        client(engine).createPlaylist(name = "No-Desc", description = null, token = "tok")
        val playlist = seenBody!!["playlist"]!!.jsonObject
        assertEquals("No-Desc", playlist["title"]!!.jsonPrimitive.content)
        assertFalse(playlist.containsKey("annotation"))
    }

    @Test
    fun createPlaylist_privateFlag() = runTest {
        var seenBody: JsonObject? = null
        val engine = MockEngine { request ->
            seenBody = bodyJson(request)
            respond(
                """{"playlist_mbid":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","status":"ok"}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        client(engine).createPlaylist(name = "Priv", isPublic = false, token = "tok")
        val ext = seenBody!!["playlist"]!!.jsonObject["extension"]!!.jsonObject
        val jspfExt = ext["https://musicbrainz.org/doc/jspf#playlist"]!!.jsonObject
        assertFalse(jspfExt["public"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun createPlaylist_throwsUnauthorizedOn401() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        assertFailsWith<ListenBrainzUnauthorizedException> {
            client(engine).createPlaylist("Test", token = "bad-token")
        }
    }

    // ── editPlaylist ─────────────────────────────────────────────────────────

    @Test
    fun editPlaylist_sendsBodyWithOnlyNonNullFields() = runTest {
        var seenUrl: String? = null
        var seenAuth: String? = null
        var seenBody: JsonObject? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            seenAuth = request.headers["Authorization"]
            seenBody = bodyJson(request)
            respond("""{"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }

        client(engine).editPlaylist(
            playlistMbid = "abcd1234-1111-2222-3333-444455556666",
            name = "Renamed",
            description = null,
            token = "tok",
        )

        assertEquals(
            "https://api.listenbrainz.org/1/playlist/edit/abcd1234-1111-2222-3333-444455556666",
            seenUrl,
        )
        assertEquals("Token tok", seenAuth)
        val playlist = seenBody!!["playlist"]!!.jsonObject
        assertEquals("Renamed", playlist["title"]!!.jsonPrimitive.content)
        assertFalse(playlist.containsKey("annotation"))
    }

    @Test
    fun editPlaylist_includesDescriptionWhenProvided() = runTest {
        var seenBody: JsonObject? = null
        val engine = MockEngine { request ->
            seenBody = bodyJson(request)
            respond("""{"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }
        client(engine).editPlaylist(
            playlistMbid = "abcd1234-1111-2222-3333-444455556666",
            name = null,
            description = "New annotation",
            token = "tok",
        )
        val playlist = seenBody!!["playlist"]!!.jsonObject
        assertFalse(playlist.containsKey("title"))
        assertEquals("New annotation", playlist["annotation"]!!.jsonPrimitive.content)
    }

    @Test
    fun editPlaylist_throwsUnauthorizedOn401() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        assertFailsWith<ListenBrainzUnauthorizedException> {
            client(engine).editPlaylist(
                playlistMbid = "abcd1234-1111-2222-3333-444455556666",
                name = "x",
                description = null,
                token = "bad",
            )
        }
    }

    // ── deletePlaylist ───────────────────────────────────────────────────────

    @Test
    fun deletePlaylist_postsToDeleteEndpoint() = runTest {
        var seenUrl: String? = null
        var seenAuth: String? = null
        var seenMethod: HttpMethod? = null
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            seenAuth = request.headers["Authorization"]
            seenMethod = request.method
            respond("""{"status":"ok"}""", HttpStatusCode.OK, jsonHeaders)
        }

        client(engine).deletePlaylist(
            playlistMbid = "abcd1234-1111-2222-3333-444455556666",
            token = "tok",
        )

        assertEquals(
            "https://api.listenbrainz.org/1/playlist/abcd1234-1111-2222-3333-444455556666/delete",
            seenUrl,
        )
        assertEquals(HttpMethod.Post, seenMethod)
        assertEquals("Token tok", seenAuth)
    }

    @Test
    fun deletePlaylist_throwsUnauthorizedOn401() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        assertFailsWith<ListenBrainzUnauthorizedException> {
            client(engine).deletePlaylist(
                playlistMbid = "abcd1234-1111-2222-3333-444455556666",
                token = "bad",
            )
        }
    }
}
