package com.parachord.android.deeplink

import com.parachord.android.playback.PlaybackController
import com.parachord.shared.deeplink.DeepLinkAction
import com.parachord.shared.deeplink.ProtocolPlayTeardown
import com.parachord.shared.deeplink.RadioMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlayRadioDispatcher] (Mode B — artist seed). Mode C
 * (pool-based) lives in [PlayRadioModeCTest].
 *
 * Mode B has two sub-paths since the LB Radio fix (#121):
 *  - **Title-bearing** (`?artist=X&title=Y`) → Last.fm `track.getsimilar`
 *    via [PlaybackController.startSpinoffWithSeed]. Returns
 *    [PlayRadioResult.StartedModeB].
 *  - **Artist-only** (`?artist=X`, no title) → ListenBrainz LB Radio
 *    via the synthesized Mode C pool path. Returns
 *    [PlayRadioResult.StartedModeC].
 */
class PlayRadioModeBTest {

    private fun build(
        pc: PlaybackController = mockk(relaxed = true),
        td: ProtocolPlayTeardown = mockk<ProtocolPlayTeardown>().also {
            coEvery { it.prepareForNewPlayback() } just runs
        },
        // Title-bearing Mode B never invokes the handler — pass a relaxed
        // mock so a stray call surfaces as a verification failure, not a
        // NPE. Artist-only Mode B tests build their own strict mock.
        handler: ProtocolPlayHandler = mockk(relaxed = true),
    ): PlayRadioDispatcher = PlayRadioDispatcher(pc, td, handler)

    // ── Title-bearing path (Last.fm track.getsimilar) ──────────────────

    @Test
    fun modeB_titleBearing_callsTeardownBeforeStartSpinoffWithSeed() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForNewPlayback() } just runs

        val dispatcher = build(pc, td)
        val result = dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.ArtistSeed("Slowdive", "Sugar For The Pill"),
            )
        )

        assertTrue(result is PlayRadioResult.StartedModeB)
        coVerifyOrder {
            td.prepareForNewPlayback()
            pc.startSpinoffWithSeed("Slowdive", "Sugar For The Pill", any(), any())
        }
    }

    @Test
    fun modeB_titleBearing_displayName_prefersExplicitName() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val dispatcher = build(pc = pc)

        val result = dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.ArtistSeed("Slowdive", "Sugar For The Pill"),
                name = "My Custom Station",
            )
        )

        assertEquals("My Custom Station", (result as PlayRadioResult.StartedModeB).displayName)
        coVerify(exactly = 1) {
            pc.startSpinoffWithSeed("Slowdive", "Sugar For The Pill", "My Custom Station", any())
        }
    }

    @Test
    fun modeB_titleBearing_displayName_artistTitleFallbackWhenNoExplicitName() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val dispatcher = build(pc = pc)

        val result = dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.ArtistSeed("Slowdive", "Sugar For The Pill"),
                name = null,
            )
        )

        assertEquals(
            "Radio: Slowdive – Sugar For The Pill",
            (result as PlayRadioResult.StartedModeB).displayName,
        )
        coVerify(exactly = 1) {
            pc.startSpinoffWithSeed(
                "Slowdive",
                "Sugar For The Pill",
                "Radio: Slowdive – Sugar For The Pill",
                any(),
            )
        }
    }

    @Test
    fun modeB_titleBearing_passesKickStartFirstTrackTrue() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForNewPlayback() } just runs

        val dispatcher = build(pc, td)
        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.ArtistSeed("Slowdive", "Sugar For The Pill"),
            )
        )

        // Mode B is "start fresh radio now" — the pool's first track must
        // begin playing without waiting for an existing track to finish.
        coVerify(exactly = 1) {
            pc.startSpinoffWithSeed(
                seedArtist = "Slowdive",
                seedTitle = "Sugar For The Pill",
                displayName = "Radio: Slowdive – Sugar For The Pill",
                kickStartFirstTrack = true,
            )
        }
    }

    @Test
    fun modeB_titleBearing_doesNotCallProtocolPlayHandler() = runTest {
        // Title-bearing Mode B is fully handled by the dispatcher +
        // PlaybackController — never delegates to the protocol play
        // handler. (The handler's `handle(PlayRadio)` requires PoolBased
        // and would throw on ArtistSeed.)
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForNewPlayback() } just runs
        val handler = mockk<ProtocolPlayHandler>()  // strict — any call fails

        val dispatcher = PlayRadioDispatcher(pc, td, handler)
        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.ArtistSeed("Slowdive", "Sugar For The Pill"),
            )
        )
        // Strict mock — no verification block needed; any invocation throws.
    }

    // ── Artist-only path (ListenBrainz LB Radio via Mode C pool) ───────

    @Test
    fun modeB_artistOnly_synthesizesLbRadioUrlAsBothInitialAndRefill() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>(relaxed = true)
        coEvery { td.prepareForNewPlayback() } just runs
        val handler = mockk<ProtocolPlayHandler>()
        coEvery { handler.handle(any<DeepLinkAction.PlayRadio>()) } returns
            ProtocolPlayResult.Started("Radio: Slowdive", 10)
        coEvery { handler.resolveTrackList(any()) } returns emptyList()

        val dispatcher = PlayRadioDispatcher(pc, td, handler)
        val result = dispatcher.dispatch(
            DeepLinkAction.PlayRadio(mode = RadioMode.ArtistSeed("Slowdive", null))
        )

        assertTrue(result is PlayRadioResult.StartedModeC)

        // Capture the synthetic action handed to the handler. Verify both
        // input.url and refillUrl point at the same LB Radio URL with the
        // expected prompt + mode.
        val slot = slot<DeepLinkAction.PlayRadio>()
        coVerify(exactly = 1) { handler.handle(capture(slot)) }
        val synthetic = slot.captured
        assertEquals(RadioMode.PoolBased, synthetic.mode)
        assertNotNull(synthetic.input?.url)
        assertEquals(synthetic.input?.url, synthetic.refillUrl)
        val url = synthetic.input!!.url!!
        assertTrue(
            "URL should hit api.listenbrainz.org: $url",
            url.startsWith("https://api.listenbrainz.org/1/explore/lb-radio?"),
        )
        assertTrue(
            "URL should encode prompt=artist:(Slowdive): $url",
            url.contains("prompt=artist%3A%28Slowdive%29") ||
                url.contains("prompt=artist%3A(Slowdive)") ||
                url.contains("prompt=artist:%28Slowdive%29") ||
                url.contains("prompt=artist:(Slowdive)"),
        )
        assertTrue("URL should set mode=easy: $url", url.contains("mode=easy"))
    }

    @Test
    fun modeB_artistOnly_doesNotCallStartSpinoffWithSeed() = runTest {
        // Artist-only routes through ProtocolPlayHandler.handle() — must
        // NOT touch the Last.fm spinoff path.
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>(relaxed = true)
        coEvery { td.prepareForNewPlayback() } just runs
        val handler = mockk<ProtocolPlayHandler>()
        coEvery { handler.handle(any<DeepLinkAction.PlayRadio>()) } returns
            ProtocolPlayResult.Started("Radio: Slowdive", 10)

        val dispatcher = PlayRadioDispatcher(pc, td, handler)
        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(mode = RadioMode.ArtistSeed("Slowdive", null))
        )

        coVerify(exactly = 0) { pc.startSpinoffWithSeed(any(), any(), any(), any()) }
    }

    @Test
    fun modeB_artistOnly_artistWithSpaces_urlEncodedCorrectly() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>(relaxed = true)
        coEvery { td.prepareForNewPlayback() } just runs
        val handler = mockk<ProtocolPlayHandler>()
        coEvery { handler.handle(any<DeepLinkAction.PlayRadio>()) } returns
            ProtocolPlayResult.Started("Radio: Tame Impala", 10)

        val dispatcher = PlayRadioDispatcher(pc, td, handler)
        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(mode = RadioMode.ArtistSeed("Tame Impala", null))
        )

        val slot = slot<DeepLinkAction.PlayRadio>()
        coVerify(exactly = 1) { handler.handle(capture(slot)) }
        val url = slot.captured.input!!.url!!
        // URLEncoder turns spaces into '+' for query-string values.
        assertTrue(
            "space should be encoded in $url",
            url.contains("%20Impala") || url.contains("+Impala"),
        )
    }

    @Test
    fun modeB_artistOnly_displayName_prefersExplicitName() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>(relaxed = true)
        coEvery { td.prepareForNewPlayback() } just runs
        val handler = mockk<ProtocolPlayHandler>()
        coEvery { handler.handle(any<DeepLinkAction.PlayRadio>()) } returns
            ProtocolPlayResult.Started("My Custom Station", 10)

        val dispatcher = PlayRadioDispatcher(pc, td, handler)
        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.ArtistSeed("Slowdive", null),
                name = "My Custom Station",
            )
        )

        val slot = slot<DeepLinkAction.PlayRadio>()
        coVerify(exactly = 1) { handler.handle(capture(slot)) }
        assertEquals("My Custom Station", slot.captured.name)
    }

    @Test
    fun modeB_artistOnly_displayName_fallbackWhenNoExplicitName() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>(relaxed = true)
        coEvery { td.prepareForNewPlayback() } just runs
        val handler = mockk<ProtocolPlayHandler>()
        coEvery { handler.handle(any<DeepLinkAction.PlayRadio>()) } returns
            ProtocolPlayResult.Started("Radio: Slowdive", 10)

        val dispatcher = PlayRadioDispatcher(pc, td, handler)
        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(mode = RadioMode.ArtistSeed("Slowdive", null), name = null)
        )

        val slot = slot<DeepLinkAction.PlayRadio>()
        coVerify(exactly = 1) { handler.handle(capture(slot)) }
        assertEquals("Radio: Slowdive", slot.captured.name)
    }
}
