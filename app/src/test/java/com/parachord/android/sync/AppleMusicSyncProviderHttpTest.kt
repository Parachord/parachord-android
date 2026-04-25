package com.parachord.android.sync

import com.parachord.android.data.api.AppleMusicLibraryApi
import com.parachord.shared.sync.DeleteResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Integration tests for [AppleMusicSyncProvider] against a real
 * Retrofit + OkHttp stack pointed at a MockWebServer.
 *
 * Coverage targets the four documented Apple Music degradation
 * patterns from desktop CLAUDE.md:
 *
 * 1. PUT 401/403/405 → flip [amPutUnsupportedForSession], degrade to
 *    POST-append, NO retry on PUT (per "do NOT retry-on-401" rule).
 * 2. PATCH 401/403/405 → flip [amPatchUnsupportedForSession], no
 *    throw (load-bearing — runs before track push).
 * 3. DELETE 401/403/405 → return [DeleteResult.Unsupported] (no throw).
 * 4. SHOULD-WORK endpoints (list, create) on 401 → raise
 *    [AppleMusicReauthRequiredException] without retry.
 *
 * Pagination + happy-path responses are also exercised.
 *
 * Note: [AppleMusicSyncProvider.replacePlaylistTracks] calls
 * [AppleMusicSyncProvider.getPlaylistSnapshotId] after a successful
 * write to fetch the new snapshot. Tests that exercise PUT/POST
 * either stub that secondary call by enqueueing a list response, or
 * inspect the call sequence without asserting on the snapshot return
 * value.
 */
class AppleMusicSyncProviderHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: AppleMusicSyncProvider

    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AppleMusicLibraryApi::class.java)
        // settingsStore is only consulted by the auth interceptor (which
        // we're not exercising here — Retrofit goes straight to the
        // mock without an interceptor in the test). Relaxed mock keeps
        // the constructor happy.
        provider = AppleMusicSyncProvider(api, mockk(relaxed = true))
    }

    @After fun teardown() {
        server.shutdown()
    }

    // ── Reads + reauth ───────────────────────────────────────────────

    @Test fun `fetchPlaylists parses single page`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"data":[{"id":"p.abc","type":"library-playlists","attributes":{"name":"Mix","canEdit":true,"lastModifiedDate":"2026-04-24T12:00:00Z"}}]}"""
        ))
        val result = provider.fetchPlaylists(null)
        assertEquals(1, result.size)
        assertEquals("p.abc", result.first().spotifyId)
        assertEquals("2026-04-24T12:00:00Z", result.first().snapshotId)
        assertTrue(result.first().isOwned)
    }

    @Test fun `fetchPlaylists pages until short page`() = runBlocking {
        // Build a 100-item page (PAGE_SIZE) so the loop continues.
        val full = (1..100).joinToString(",") { """{"id":"p.$it","type":"library-playlists","attributes":{"name":"P$it","canEdit":true}}""" }
        server.enqueue(MockResponse().setBody("""{"data":[$full],"next":"/page2"}"""))
        // Second page has 3 items — short, stops the loop.
        val short = (101..103).joinToString(",") { """{"id":"p.$it","type":"library-playlists","attributes":{"name":"P$it","canEdit":true}}""" }
        server.enqueue(MockResponse().setBody("""{"data":[$short]}"""))
        val result = provider.fetchPlaylists(null)
        assertEquals(103, result.size)
    }

    @Test fun `fetchPlaylists raises AppleMusicReauthRequiredException on 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        try {
            provider.fetchPlaylists(null)
            fail("expected AppleMusicReauthRequiredException")
        } catch (_: AppleMusicReauthRequiredException) { /* expected */ }
    }

    @Test fun `fetchPlaylistTracks raises reauth on 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        try {
            provider.fetchPlaylistTracks("p.abc")
            fail("expected AppleMusicReauthRequiredException")
        } catch (_: AppleMusicReauthRequiredException) { /* expected */ }
    }

    @Test fun `createPlaylist returns RemoteCreated with snapshot`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"data":[{"id":"p.new","type":"library-playlists","attributes":{"name":"New","canEdit":true,"lastModifiedDate":"2026-04-24T13:00:00Z"}}]}"""
        ))
        val result = provider.createPlaylist("New", null)
        assertEquals("p.new", result.externalId)
        assertEquals("2026-04-24T13:00:00Z", result.snapshotId)
    }

    @Test fun `createPlaylist raises reauth on 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        try {
            provider.createPlaylist("New", null)
            fail("expected AppleMusicReauthRequiredException")
        } catch (_: AppleMusicReauthRequiredException) { /* expected */ }
    }

    // ── Writes + kill-switches ───────────────────────────────────────

    @Test fun `replacePlaylistTracks PUT 204 returns snapshot`() = runBlocking {
        // PUT succeeds with 204; subsequent getPlaylistSnapshotId hits
        // listPlaylists with the new lastModifiedDate.
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setBody(
            """{"data":[{"id":"p.abc","type":"library-playlists","attributes":{"name":"Mix","lastModifiedDate":"2026-04-24T15:00:00Z"}}]}"""
        ))
        val snap = provider.replacePlaylistTracks("p.abc", listOf("song-1", "song-2"))
        assertEquals("2026-04-24T15:00:00Z", snap)
        assertTrue("PUT must NOT have flipped the kill-switch on success", !provider.amPutUnsupportedForSession)

        // Verify the request order
        val req1 = server.takeRequest()
        assertEquals("PUT", req1.method)
        val req2 = server.takeRequest()
        assertEquals("GET", req2.method)
    }

    @Test fun `replacePlaylistTracks PUT 401 flips kill-switch and degrades to POST without PUT retry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))   // PUT
        server.enqueue(MockResponse().setResponseCode(204))   // POST append
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"p.abc","type":"library-playlists","attributes":{"name":"Mix","lastModifiedDate":"2026-04-24T15:00:00Z"}}]}"""))  // snapshot refetch
        val snap = provider.replacePlaylistTracks("p.abc", listOf("song-1"))
        assertEquals("2026-04-24T15:00:00Z", snap)
        assertTrue("PUT 401 must flip kill-switch", provider.amPutUnsupportedForSession)

        // Critical: exactly one PUT was attempted. No retry.
        assertEquals("PUT", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test fun `replacePlaylistTracks after kill-switch goes straight to POST without PUT probe`() = runBlocking {
        // Pre-flip the kill-switch to simulate a prior PUT 401 in the same session.
        provider.amPutUnsupportedForSession = true

        server.enqueue(MockResponse().setResponseCode(204))   // POST append
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"p.abc","type":"library-playlists","attributes":{"name":"Mix","lastModifiedDate":"2026-04-24T15:00:00Z"}}]}"""))  // snapshot
        provider.replacePlaylistTracks("p.abc", listOf("song-1"))

        // First call must be POST, NOT PUT.
        val req = server.takeRequest()
        assertEquals("POST", req.method)
    }

    @Test fun `replacePlaylistTracks empty list returns existing snapshot without writes`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"p.abc","type":"library-playlists","attributes":{"name":"Mix","lastModifiedDate":"2026-04-24T15:00:00Z"}}]}"""))
        val snap = provider.replacePlaylistTracks("p.abc", emptyList())
        assertEquals("2026-04-24T15:00:00Z", snap)
        // Only one request fired (the snapshot lookup).
        assertEquals(1, server.requestCount)
    }

    @Test fun `updatePlaylistDetails PATCH 401 flips kill-switch without throwing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        provider.updatePlaylistDetails("p.abc", name = "Renamed", description = null)
        assertTrue("PATCH 401 must flip kill-switch", provider.amPatchUnsupportedForSession)
        // No exception thrown — load-bearing.
    }

    @Test fun `updatePlaylistDetails after kill-switch short-circuits without HTTP call`() = runBlocking {
        provider.amPatchUnsupportedForSession = true
        provider.updatePlaylistDetails("p.abc", name = "Renamed", description = null)
        assertEquals("kill-switch must short-circuit before HTTP", 0, server.requestCount)
    }

    @Test fun `updatePlaylistDetails 204 happy path does NOT flip kill-switch`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        provider.updatePlaylistDetails("p.abc", name = "Renamed", description = null)
        assertTrue("204 must NOT flip kill-switch", !provider.amPatchUnsupportedForSession)
    }

    @Test fun `updatePlaylistDetails network error swallowed silently (load-bearing)`() = runBlocking {
        // DISCONNECT_AT_START guarantees the IOException fires at the
        // call site (PATCH has no response body, so a body-disconnect
        // policy wouldn't trigger).
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        // Must NOT throw — would abort the track push otherwise.
        provider.updatePlaylistDetails("p.abc", name = "Renamed", description = null)
    }

    @Test fun `deletePlaylist 204 returns Success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = provider.deletePlaylist("p.abc")
        assertEquals(DeleteResult.Success, result)
    }

    @Test fun `deletePlaylist 401 returns Unsupported(401)`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = provider.deletePlaylist("p.abc")
        assertTrue(result is DeleteResult.Unsupported)
        assertEquals(401, (result as DeleteResult.Unsupported).status)
    }

    @Test fun `deletePlaylist 405 returns Unsupported(405)`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405))
        val result = provider.deletePlaylist("p.abc")
        assertTrue(result is DeleteResult.Unsupported)
        assertEquals(405, (result as DeleteResult.Unsupported).status)
    }

    @Test fun `deletePlaylist network error returns Failed`() = runBlocking {
        // DISCONNECT_AT_START closes the socket before sending any
        // response — guarantees an IOException at the call site.
        // (DISCONNECT_DURING_RESPONSE_BODY would not trigger here
        // because DELETE has no response body to disconnect during.)
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        val result = provider.deletePlaylist("p.abc")
        assertTrue("network error must yield Failed, not throw", result is DeleteResult.Failed)
    }

    @Test fun `deletePlaylist 500 returns Failed (not Unsupported)`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = provider.deletePlaylist("p.abc")
        assertTrue("5xx must NOT be treated as Unsupported", result is DeleteResult.Failed)
    }
}
