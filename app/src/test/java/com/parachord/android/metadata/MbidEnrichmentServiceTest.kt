package com.parachord.android.metadata

import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.api.MbidMapperResult
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.metadata.MbidEnrichmentService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit coverage for [MbidEnrichmentService.getRecordingMbid] — the public
 * MBID-resolve entry point the Achordion scrobbler-plugin bridge relies on.
 *
 * The service is exercised with a mocked [ListenBrainzClient] (so we can count
 * mapper calls), a relaxed-mock [TrackDao] (never touched by this path — no
 * trackId context), and in-memory cache lambdas instead of disk I/O.
 */
class MbidEnrichmentServiceTest {

    private fun mapperResult(recordingMbid: String?) = MbidMapperResult(
        artistMbid = "11111111-1111-1111-1111-111111111111",
        artistCreditName = "Slowdive",
        recordingName = "Sugar For The Pill",
        recordingMbid = recordingMbid,
        releaseName = "Slowdive",
        releaseMbid = "22222222-2222-2222-2222-222222222222",
    )

    private fun buildService(
        client: ListenBrainzClient,
    ): MbidEnrichmentService {
        var cache: String? = null
        return MbidEnrichmentService(
            listenBrainzClient = client,
            trackDao = mockk<TrackDao>(relaxed = true),
            cacheRead = { cache },
            cacheWrite = { cache = it },
        )
    }

    @Test
    fun getRecordingMbid_returnsMappersRecordingMbid() = runTest {
        val expected = "33333333-3333-3333-3333-333333333333"
        val client = mockk<ListenBrainzClient>()
        coEvery { client.mbidMapperLookup("Slowdive", "Sugar For The Pill") } returns
            mapperResult(expected)

        val service = buildService(client)
        val result = service.getRecordingMbid("Slowdive", "Sugar For The Pill")

        assertEquals(expected, result)
    }

    @Test
    fun getRecordingMbid_returnsNull_whenMapperHasNoMatch() = runTest {
        val client = mockk<ListenBrainzClient>()
        coEvery { client.mbidMapperLookup(any(), any()) } returns null

        val service = buildService(client)
        assertNull(service.getRecordingMbid("Unknown", "Nothing"))
    }

    @Test
    fun getRecordingMbid_cacheHit_avoidsSecondMapperLookup() = runTest {
        val expected = "44444444-4444-4444-4444-444444444444"
        val client = mockk<ListenBrainzClient>()
        coEvery { client.mbidMapperLookup("Slowdive", "Sugar For The Pill") } returns
            mapperResult(expected)

        val service = buildService(client)

        // First call: misses the cache, hits the mapper, caches the result.
        val first = service.getRecordingMbid("Slowdive", "Sugar For The Pill")
        // Second call (same artist/title): must be served from the in-memory
        // cache without a second network round-trip.
        val second = service.getRecordingMbid("Slowdive", "Sugar For The Pill")

        assertEquals(expected, first)
        assertEquals(expected, second)
        coVerify(exactly = 1) { client.mbidMapperLookup("Slowdive", "Sugar For The Pill") }
    }
}
