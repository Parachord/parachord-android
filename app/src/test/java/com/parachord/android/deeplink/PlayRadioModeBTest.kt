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
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Unit tests for [PlayRadioDispatcher] (Mode B — artist seed). Mode C
 * (pool-based) lands in Task 4; only the stub-toast path is exercised
 * here.
 */
class PlayRadioModeBTest {

    private fun build(
        pc: PlaybackController = mockk(relaxed = true),
        td: ProtocolPlayTeardown = mockk<ProtocolPlayTeardown>().also {
            coEvery { it.prepareForNewPlayback() } just runs
        },
        toast: suspend (String) -> Unit = { /* no-op */ },
    ): PlayRadioDispatcher = PlayRadioDispatcher(pc, td, toast)

    @Test
    fun modeB_callsTeardownBeforeStartSpinoffWithSeed() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForNewPlayback() } just runs

        val dispatcher = build(pc, td)
        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.ArtistSeed("Slowdive", null),
            )
        )

        coVerifyOrder {
            td.prepareForNewPlayback()
            pc.startSpinoffWithSeed("Slowdive", null, any())
        }
    }

    @Test
    fun modeB_displayName_prefersExplicitNameOverArtistTitleFallback() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val dispatcher = build(pc = pc)

        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.ArtistSeed("Slowdive", "Sugar For The Pill"),
                name = "My Custom Station",
            )
        )

        coVerify(exactly = 1) {
            pc.startSpinoffWithSeed("Slowdive", "Sugar For The Pill", "My Custom Station")
        }
    }

    @Test
    fun modeB_displayName_artistTitleFallbackWhenNoExplicitName() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val dispatcher = build(pc = pc)

        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.ArtistSeed("Slowdive", "Sugar For The Pill"),
                name = null,
            )
        )

        coVerify(exactly = 1) {
            pc.startSpinoffWithSeed(
                "Slowdive",
                "Sugar For The Pill",
                "Radio: Slowdive – Sugar For The Pill",
            )
        }
    }

    @Test
    fun modeB_displayName_artistOnlyFallback() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val dispatcher = build(pc = pc)

        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.ArtistSeed("Slowdive", null),
                name = null,
            )
        )

        coVerify(exactly = 1) {
            pc.startSpinoffWithSeed("Slowdive", null, "Radio: Slowdive")
        }
    }

    @Test
    fun modeB_emitsAcknowledgmentToastBeforeTeardown() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForNewPlayback() } just runs
        val toasts = mutableListOf<String>()
        val dispatcher = PlayRadioDispatcher(pc, td) { toasts += it }

        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(mode = RadioMode.ArtistSeed("Slowdive", null))
        )

        // Acknowledgment toast fires before teardown / startSpinoffWithSeed.
        assert(toasts.firstOrNull() == "Building radio…") {
            "expected 'Building radio…' first, got $toasts"
        }
    }

    @Test
    fun modeC_isStubbed_doesNotInvokeTeardownOrPlayback() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>(relaxed = true)
        val toasts = mutableListOf<String>()
        val dispatcher = PlayRadioDispatcher(pc, td) { toasts += it }

        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(mode = RadioMode.PoolBased)
        )

        coVerify(exactly = 0) { td.prepareForNewPlayback() }
        coVerify(exactly = 0) { pc.startSpinoffWithSeed(any(), any(), any()) }
        assert(toasts.contains("Mode C coming next commit"))
    }
}
