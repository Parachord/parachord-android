package com.parachord.android.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline

/**
 * Synthetic [Timeline] that exposes the Parachord queue (current track +
 * upNext) to Media3, including Android Auto's "Up Next" view.
 *
 * **Index convention:** index 0 is the current track. Indices 1..N are
 * `QueueSnapshot.upNext[0..N-1]`. Callers that translate Auto's
 * `mediaItemIndex` into a `QueueManager` queue index must subtract 1.
 *
 * **Why we need this:** the underlying [androidx.media3.exoplayer.ExoPlayer]
 * always holds a single-item timeline (either the silence loop during
 * external playback, or the currently-playing native track during ExoPlayer
 * playback). Without a synthetic timeline, Auto's queue view is empty.
 *
 * The wrapper layer ([com.parachord.android.playback.PlaybackService.ExternalPlaybackForwardingPlayer])
 * builds an instance per snapshot tick and reports it via
 * `getCurrentTimeline()`, hiding the delegate's single-item timeline from
 * external listeners.
 *
 * **Window/period contract:** every window is non-seekable, non-dynamic,
 * uid = `mediaItem.mediaId` (the [TrackEntity.id]). One period per window;
 * period uid mirrors the window uid so [getIndexOfPeriod] is a simple
 * lookup.
 */
class QueueTimeline(
    private val items: List<MediaItem>,
    private val durationsUs: LongArray,
) : Timeline() {

    init {
        require(items.size == durationsUs.size) {
            "items (${items.size}) and durationsUs (${durationsUs.size}) must be the same length"
        }
    }

    override fun getWindowCount(): Int = items.size

    override fun getPeriodCount(): Int = items.size

    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long,
    ): Window {
        val item = items[windowIndex]
        val durationUs = durationsUs[windowIndex]
        return window.set(
            /* uid = */ item.mediaId,
            /* mediaItem = */ item,
            /* manifest = */ null,
            /* presentationStartTimeMs = */ C.TIME_UNSET,
            /* windowStartTimeMs = */ C.TIME_UNSET,
            /* elapsedRealtimeEpochOffsetMs = */ C.TIME_UNSET,
            /* isSeekable = */ false,
            /* isDynamic = */ false,
            /* liveConfiguration = */ null,
            /* defaultPositionUs = */ 0L,
            /* durationUs = */ durationUs,
            /* firstPeriodIndex = */ windowIndex,
            /* lastPeriodIndex = */ windowIndex,
            /* positionInFirstPeriodUs = */ 0L,
        )
    }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        val mediaId = items[periodIndex].mediaId
        return period.set(
            /* id = */ if (setIds) mediaId else null,
            /* uid = */ if (setIds) mediaId else null,
            /* windowIndex = */ periodIndex,
            /* durationUs = */ durationsUs[periodIndex],
            /* positionInWindowUs = */ 0L,
        )
    }

    override fun getIndexOfPeriod(uid: Any): Int {
        val s = uid as? String ?: return C.INDEX_UNSET
        val idx = items.indexOfFirst { it.mediaId == s }
        return if (idx == -1) C.INDEX_UNSET else idx
    }

    override fun getUidOfPeriod(periodIndex: Int): Any = items[periodIndex].mediaId
}
