package com.parachord.shared.api

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * MockEngine-based parity tests for the existing Phase 2-era MusicBrainzClient.
 *
 * These validate URL construction, query parameters, and JSON deserialization
 * for all 6 endpoints before the consumer cutover from Retrofit's MusicBrainzApi.
 * All tests use installSharedPlugins so they run against the production plugin
 * stack (User-Agent, HttpTimeout, OAuthRefreshPlugin). Stub auth is inert —
 * MusicBrainz is unauthenticated.
 */
class MusicBrainzClientTest {

    private val appConfig = AppConfig(
        userAgent = "Parachord/0.5.0-test (Android; https://parachord.app)",
        isDebug = false,
    )
    private val json = Json { ignoreUnknownKeys = true }

    private val stubAuthProvider = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = null
        override suspend fun invalidate(realm: AuthRealm) {}
    }
    private val stubTokenRefresher = object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
    }

    private fun buildClient(mock: MockEngine): MusicBrainzClient {
        val httpClient = HttpClient(mock) {
            installSharedPlugins(json, appConfig, stubAuthProvider, stubTokenRefresher)
        }
        return MusicBrainzClient(httpClient)
    }

    @Test
    fun searchRecordings_buildsCorrectRequest_andDeserializesResponse() = runBlocking {
        val mock = MockEngine { request ->
            assertEquals(
                "https://musicbrainz.org/ws/2/recording/",
                request.url.toString().substringBefore("?"),
            )
            assertEquals("turnstile", request.url.parameters["query"])
            assertEquals("20", request.url.parameters["limit"])
            assertEquals("json", request.url.parameters["fmt"])
            respond(
                content = """
                    {
                      "recordings": [
                        {
                          "id": "abc-123",
                          "title": "Holiday",
                          "length": 180000,
                          "artist-credit": [
                            { "name": "Turnstile", "artist": { "id": "art-1", "name": "Turnstile" } }
                          ],
                          "releases": [ { "id": "rel-1", "title": "Glow On" } ]
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = buildClient(mock)

        val response = client.searchRecordings("turnstile", limit = 20)
        assertEquals(1, response.recordings.size)
        val rec = response.recordings.first()
        assertEquals("abc-123", rec.id)
        assertEquals("Holiday", rec.title)
        assertEquals(180000L, rec.length)
        assertEquals("Turnstile", rec.artistName)
        assertEquals("Glow On", rec.albumTitle)
    }

    @Test
    fun searchReleases_buildsCorrectRequest_andDeserializesResponse() = runBlocking {
        val mock = MockEngine { request ->
            assertEquals(
                "https://musicbrainz.org/ws/2/release/",
                request.url.toString().substringBefore("?"),
            )
            assertEquals("glow on", request.url.parameters["query"])
            assertEquals("10", request.url.parameters["limit"])
            assertEquals("json", request.url.parameters["fmt"])
            respond(
                content = """
                    {
                      "releases": [
                        {
                          "id": "rel-1",
                          "title": "Glow On",
                          "artist-credit": [ { "name": "Turnstile" } ],
                          "date": "2021-08-27",
                          "track-count": 15,
                          "release-group": { "primary-type": "Album", "secondary-types": [] }
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = buildClient(mock)

        val response = client.searchReleases("glow on", limit = 10)
        assertEquals(1, response.releases.size)
        val rel = response.releases.first()
        assertEquals("rel-1", rel.id)
        assertEquals("Glow On", rel.title)
        assertEquals("Turnstile", rel.artistName)
        assertEquals(2021, rel.year)
        assertEquals(15, rel.trackCount)
        assertEquals("Album", rel.releaseGroup?.primaryType)
    }

    @Test
    fun searchArtists_buildsCorrectRequest_andDeserializesResponse() = runBlocking {
        val mock = MockEngine { request ->
            assertEquals(
                "https://musicbrainz.org/ws/2/artist/",
                request.url.toString().substringBefore("?"),
            )
            assertEquals("turnstile", request.url.parameters["query"])
            assertEquals("5", request.url.parameters["limit"])
            assertEquals("json", request.url.parameters["fmt"])
            respond(
                content = """
                    {
                      "artists": [
                        {
                          "id": "art-1",
                          "name": "Turnstile",
                          "disambiguation": "Baltimore hardcore band",
                          "tags": [ { "name": "hardcore", "count": 3 } ]
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = buildClient(mock)

        val response = client.searchArtists("turnstile", limit = 5)
        assertEquals(1, response.artists.size)
        val artist = response.artists.first()
        assertEquals("art-1", artist.id)
        assertEquals("Turnstile", artist.name)
        assertEquals("Baltimore hardcore band", artist.disambiguation)
        assertEquals(1, artist.tags.size)
        assertEquals("hardcore", artist.tags.first().name)
        assertEquals(3, artist.tags.first().count)
    }

    @Test
    fun getRelease_buildsPathWithIdAndIncParam_andDeserializesTracks() = runBlocking {
        val mock = MockEngine { request ->
            assertEquals(
                "https://musicbrainz.org/ws/2/release/rel-1",
                request.url.toString().substringBefore("?"),
            )
            assertEquals("recordings+artist-credits", request.url.parameters["inc"])
            assertEquals("json", request.url.parameters["fmt"])
            respond(
                content = """
                    {
                      "id": "rel-1",
                      "title": "Glow On",
                      "artist-credit": [ { "name": "Turnstile" } ],
                      "date": "2021-08-27",
                      "media": [
                        {
                          "position": 1,
                          "format": "Digital Media",
                          "tracks": [
                            {
                              "id": "trk-1",
                              "number": "1",
                              "title": "MYSTERY",
                              "length": 90000,
                              "position": 1,
                              "artist-credit": [ { "name": "Turnstile" } ]
                            }
                          ]
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = buildClient(mock)

        val release = client.getRelease("rel-1")
        assertEquals("rel-1", release.id)
        assertEquals("Glow On", release.title)
        assertEquals("Turnstile", release.artistName)
        assertEquals(2021, release.year)
        assertEquals(1, release.media.size)
        val track = release.media.first().tracks.first()
        assertEquals("trk-1", track.id)
        assertEquals("MYSTERY", track.title)
        assertEquals("Turnstile", track.artistName)
    }

    @Test
    fun browseReleaseGroups_buildsCorrectRequest_andDeserializesGroups() = runBlocking {
        val mock = MockEngine { request ->
            assertEquals(
                "https://musicbrainz.org/ws/2/release-group",
                request.url.toString().substringBefore("?"),
            )
            assertEquals("art-1", request.url.parameters["artist"])
            assertEquals("100", request.url.parameters["limit"])
            assertEquals("0", request.url.parameters["offset"])
            assertEquals("json", request.url.parameters["fmt"])
            respond(
                content = """
                    {
                      "release-group-count": 2,
                      "release-group-offset": 0,
                      "release-groups": [
                        {
                          "id": "rg-1",
                          "title": "Glow On",
                          "primary-type": "Album",
                          "secondary-types": [],
                          "first-release-date": "2021-08-27",
                          "artist-credit": [ { "name": "Turnstile" } ]
                        },
                        {
                          "id": "rg-2",
                          "title": "Time & Space",
                          "primary-type": "Album",
                          "secondary-types": [],
                          "first-release-date": "2018-02-23",
                          "artist-credit": [ { "name": "Turnstile" } ]
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = buildClient(mock)

        val response = client.browseReleaseGroups("art-1")
        assertEquals(2, response.releaseGroupCount)
        assertEquals(0, response.releaseGroupOffset)
        assertEquals(2, response.releaseGroups.size)
        val first = response.releaseGroups.first()
        assertEquals("rg-1", first.id)
        assertEquals("Glow On", first.title)
        assertEquals("Album", first.primaryType)
        assertEquals(2021, first.year)
        assertEquals("Turnstile", first.artistName)
    }

    @Test
    fun getArtist_buildsPathWithIdAndIncRels_andDeserializesRelations() = runBlocking {
        val mock = MockEngine { request ->
            assertEquals(
                "https://musicbrainz.org/ws/2/artist/art-1",
                request.url.toString().substringBefore("?"),
            )
            assertEquals("url-rels", request.url.parameters["inc"])
            assertEquals("json", request.url.parameters["fmt"])
            respond(
                content = """
                    {
                      "id": "art-1",
                      "name": "Turnstile",
                      "relations": [
                        {
                          "type": "wikidata",
                          "url": { "resource": "https://www.wikidata.org/wiki/Q123" }
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = buildClient(mock)

        val artist = client.getArtist("art-1")
        assertEquals("art-1", artist.id)
        assertEquals("Turnstile", artist.name)
        assertEquals(1, artist.relations.size)
        val rel = artist.relations.first()
        assertEquals("wikidata", rel.type)
        assertNotNull(rel.url)
        assertEquals("https://www.wikidata.org/wiki/Q123", rel.url?.resource)
    }

    @Test
    fun getRelease_acceptsCustomIncParam() = runBlocking {
        // Validates non-default `inc` value flows through to the query string.
        var capturedInc: String? = null
        val mock = MockEngine { request ->
            capturedInc = request.url.parameters["inc"]
            respond(
                content = """{"id":"rel-1","title":"x","media":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = buildClient(mock)

        client.getRelease("rel-1", inc = "artists+labels+recordings")
        assertEquals("artists+labels+recordings", capturedInc)
    }
}
