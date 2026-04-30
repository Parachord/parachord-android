package com.parachord.android.playback

import android.app.Application
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.parachord.shared.model.Track
import com.parachord.shared.playback.QueueManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the service-side flow collector wires QueueManager + state holder
 * into wrapper.updateQueueSnapshot. Tests the collector logic in isolation
 * without standing up the full PlaybackService.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlaybackServiceQueueBridgeTest {

    private fun track(id: String) = Track(id = id, title = id, artist = "A", album = "B")

    @Test fun `combine fires updateQueueSnapshot on each side change`() = runTest {
        val delegate = mockk<ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        val queue = QueueManager()

        // Mirror the service-side combine. This is the exact same wiring
        // we add to PlaybackService.onCreate; if it works here, it works
        // there.
        val job = launch {
            combine(
                queue.snapshot,
                stateHolder.state.map { it.currentTrack }.distinctUntilChanged(),
            ) { qs, current -> Pair(qs, current) }.collect { (qs, current) ->
                wrapper.updateQueueSnapshot(currentTrack = current, upNext = qs.upNext)
            }
        }
        advanceUntilIdle()

        // Initial state: no current track, no upNext. Timeline is empty.
        assertEquals(0, wrapper.currentTimeline.windowCount)

        // Set a current track.
        stateHolder.update { copy(currentTrack = track("c1")) }
        advanceUntilIdle()
        assertEquals(1, wrapper.currentTimeline.windowCount)

        // Add upNext.
        queue.addToQueue(listOf(track("u1"), track("u2")))
        advanceUntilIdle()
        assertEquals(3, wrapper.currentTimeline.windowCount)

        // Skip current.
        stateHolder.update { copy(currentTrack = track("c2")) }
        advanceUntilIdle()
        val window = Timeline.Window()
        wrapper.currentTimeline.getWindow(0, window)
        // QueueTimeline uses positional uids in the form "<mediaId>#<index>".
        assertEquals("c2#0", window.uid)

        job.cancel()
    }

    @Test fun `onAddMediaItems exists on LibraryCallback`() {
        // We can't easily instantiate LibraryCallback without the service,
        // but we can verify the override exists by reflecting the class
        // surface. The actual behavior is exercised end-to-end in DHU
        // testing (Task 6); here we just lock the contract so future
        // refactoring doesn't accidentally drop the override.
        val method = PlaybackService::class.java.declaredClasses
            .firstOrNull { it.simpleName == "LibraryCallback" }
            ?.declaredMethods
            ?.firstOrNull { it.name == "onAddMediaItems" }
        assertNotNull("onAddMediaItems must exist on LibraryCallback", method)
    }
}
