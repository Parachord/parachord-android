package com.parachord.android.playback

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Timeline
import com.parachord.shared.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals("t1#0", window.uid)
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
            assertEquals("t${i + 1}#$i", window.uid)
        }
    }

    @Test fun `getIndexOfPeriod resolves uid back to index`() {
        val items = listOf("a", "b", "c").map { track(it).toAutoMediaItem() }
        val tl = QueueTimeline(items, LongArray(3) { C.TIME_UNSET })
        assertEquals(0, tl.getIndexOfPeriod("a#0"))
        assertEquals(1, tl.getIndexOfPeriod("b#1"))
        assertEquals(2, tl.getIndexOfPeriod("c#2"))
        assertEquals(C.INDEX_UNSET, tl.getIndexOfPeriod("missing#0"))
        assertEquals(C.INDEX_UNSET, tl.getIndexOfPeriod("a#5"))
        assertEquals(C.INDEX_UNSET, tl.getIndexOfPeriod("nohash"))
    }

    @Test fun `getUidOfPeriod returns positional uid`() {
        val item = track("the-uid").toAutoMediaItem()
        val tl = QueueTimeline(listOf(item), longArrayOf(C.TIME_UNSET))
        assertEquals("the-uid#0", tl.getUidOfPeriod(0))
    }

    @Test fun `duplicate mediaIds at different positions resolve distinct indices`() {
        val itemA = track("dup").toAutoMediaItem()
        val itemB = track("dup").toAutoMediaItem()
        val tl = QueueTimeline(listOf(itemA, itemB), longArrayOf(C.TIME_UNSET, C.TIME_UNSET))
        assertEquals(0, tl.getIndexOfPeriod("dup#0"))
        assertEquals(1, tl.getIndexOfPeriod("dup#1"))
    }

    @Test fun `period delegates duration to window`() {
        val item = track("t1").toAutoMediaItem()
        val tl = QueueTimeline(listOf(item), longArrayOf(60_000_000L))
        val period = Timeline.Period()
        tl.getPeriod(0, period)
        assertEquals(60_000_000L, period.durationUs)
    }

    @Test fun `getPeriod with setIds=true populates id and uid`() {
        val item = track("the-id").toAutoMediaItem()
        val tl = QueueTimeline(listOf(item), longArrayOf(C.TIME_UNSET))
        val period = Timeline.Period()
        tl.getPeriod(0, period, setIds = true)
        assertEquals("the-id#0", period.id)
        assertEquals("the-id#0", period.uid)
        assertNotNull(period.id)
        assertNotNull(period.uid)
    }

    @Test fun `getPeriod with setIds=false leaves id and uid null`() {
        val item = track("the-id").toAutoMediaItem()
        val tl = QueueTimeline(listOf(item), longArrayOf(C.TIME_UNSET))
        val period = Timeline.Period()
        tl.getPeriod(0, period, setIds = false)
        assertNull(period.id)
        assertNull(period.uid)
    }
}
