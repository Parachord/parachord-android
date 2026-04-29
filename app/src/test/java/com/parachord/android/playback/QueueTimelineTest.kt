package com.parachord.android.playback

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Timeline
import com.parachord.shared.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QueueTimelineTest {

    private fun track(id: String, title: String = id, durationMs: Long? = 30_000L) =
        Track(id = id, title = title, artist = "A", album = "B", duration = durationMs)

    @Test fun `empty timeline reports zero windows and periods`() {
        val tl = QueueTimeline(items = emptyList(), durationsUs = LongArray(0))
        assertEquals(0, tl.windowCount)
        assertEquals(0, tl.periodCount)
    }

    @Test fun `single-item timeline reports one window`() {
        val item = track("t1").toAutoMediaItem()
        val tl = QueueTimeline(items = listOf(item), durationsUs = longArrayOf(30_000_000L))
        assertEquals(1, tl.windowCount)
        assertEquals(1, tl.periodCount)
        val window = Timeline.Window()
        tl.getWindow(0, window)
        assertEquals(item, window.mediaItem)
        assertEquals("t1", window.uid)
        assertEquals(30_000_000L, window.durationUs)
        assertTrue(window.isPlaceholder.not())
    }

    @Test fun `three-item timeline reports correct windows in order`() {
        val items = listOf("t1", "t2", "t3").map { track(it).toAutoMediaItem() }
        val tl = QueueTimeline(items, longArrayOf(C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET))
        assertEquals(3, tl.windowCount)
        val window = Timeline.Window()
        for (i in 0..2) {
            tl.getWindow(i, window)
            assertEquals("t${i + 1}", window.uid)
        }
    }

    @Test fun `getIndexOfPeriod resolves uid back to index`() {
        val items = listOf("a", "b", "c").map { track(it).toAutoMediaItem() }
        val tl = QueueTimeline(items, LongArray(3) { C.TIME_UNSET })
        assertEquals(0, tl.getIndexOfPeriod("a"))
        assertEquals(1, tl.getIndexOfPeriod("b"))
        assertEquals(2, tl.getIndexOfPeriod("c"))
        assertEquals(C.INDEX_UNSET, tl.getIndexOfPeriod("missing"))
    }

    @Test fun `getUidOfPeriod returns mediaId`() {
        val item = track("the-uid").toAutoMediaItem()
        val tl = QueueTimeline(listOf(item), longArrayOf(C.TIME_UNSET))
        assertEquals("the-uid", tl.getUidOfPeriod(0))
    }

    @Test fun `period delegates duration to window`() {
        val item = track("t1").toAutoMediaItem()
        val tl = QueueTimeline(listOf(item), longArrayOf(60_000_000L))
        val period = Timeline.Period()
        tl.getPeriod(0, period)
        assertEquals(60_000_000L, period.durationUs)
    }
}
