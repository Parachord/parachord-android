package com.parachord.android.playback

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.parachord.shared.model.Track
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ForwardingPlayerSnapshotTest {

    private fun track(id: String) = Track(id = id, title = id, artist = "A", album = "B")

    @Test fun `getCurrentTimeline reflects last updateQueueSnapshot call`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        wrapper.updateQueueSnapshot(
            currentTrack = track("c1"),
            upNext = listOf(track("u1"), track("u2")),
        )
        val timeline = wrapper.currentTimeline
        assertEquals(3, timeline.windowCount)
        val window = Timeline.Window()
        timeline.getWindow(0, window)
        assertEquals("c1#0", window.uid)
        timeline.getWindow(1, window)
        assertEquals("u1#1", window.uid)
    }

    @Test fun `getCurrentMediaItemIndex is always 0 when current track present`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        wrapper.updateQueueSnapshot(track("current"), listOf(track("a"), track("b")))
        assertEquals(0, wrapper.currentMediaItemIndex)
    }

    @Test fun `hasNextMediaItem reflects upNext non-empty`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        wrapper.updateQueueSnapshot(track("c"), upNext = emptyList())
        assertEquals(false, wrapper.hasNextMediaItem())

        wrapper.updateQueueSnapshot(track("c"), upNext = listOf(track("u1")))
        assertEquals(true, wrapper.hasNextMediaItem())
    }

    @Test fun `hasPreviousMediaItem returns false in v1`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1")))
        assertEquals(false, wrapper.hasPreviousMediaItem())
    }

    @Test fun `seekTo with index larger than 0 routes to playFromQueue with offset`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1"), track("u2")))

        wrapper.seekTo(2, 0L)
        verify { controller.playFromQueue(1) }
    }

    @Test fun `seekTo with index 0 does not invoke playFromQueue`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1")))

        wrapper.seekTo(0, 5_000L)
        verify(exactly = 0) { controller.playFromQueue(any()) }
    }

    @Test fun `moveMediaItem subtracts 1 from indices when routing to controller`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1"), track("u2"), track("u3")))

        wrapper.moveMediaItem(/* fromIndex = */ 3, /* newIndex = */ 1)
        verify { controller.moveInQueue(2, 0) }
    }

    @Test fun `removeMediaItem subtracts 1 when routing to controller`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1"), track("u2")))

        wrapper.removeMediaItem(/* index = */ 2)
        verify { controller.removeFromQueue(1) }
    }

    @Test fun `updateQueueSnapshot fires onTimelineChanged on registered external listeners`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        val timelineSlot = slot<Timeline>()
        val reasonSlot = slot<Int>()
        val listener = mockk<Player.Listener>(relaxed = true) {
            every { onTimelineChanged(capture(timelineSlot), capture(reasonSlot)) } returns Unit
        }
        wrapper.addListener(listener)

        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1")))

        verify { listener.onTimelineChanged(any(), Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
        assertEquals(2, timelineSlot.captured.windowCount)
    }

    @Test fun `current track change fires onMediaItemTransition`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        val itemSlot = slot<MediaItem?>()
        val listener = mockk<Player.Listener>(relaxed = true) {
            every { onMediaItemTransition(captureNullable(itemSlot), any()) } returns Unit
        }
        wrapper.addListener(listener)

        wrapper.updateQueueSnapshot(track("first"), emptyList())
        wrapper.updateQueueSnapshot(track("second"), emptyList())

        verify(exactly = 2) { listener.onMediaItemTransition(any(), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
    }

    @Test fun `same current track does not re-fire onMediaItemTransition`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        val listener = mockk<Player.Listener>(relaxed = true)
        wrapper.addListener(listener)

        wrapper.updateQueueSnapshot(track("c"), listOf(track("a")))
        wrapper.updateQueueSnapshot(track("c"), listOf(track("a"), track("b")))

        verify(exactly = 1) { listener.onMediaItemTransition(any(), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
    }
}
