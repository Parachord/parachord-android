package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Immutable snapshot of the queue state, exposed to UI via StateFlow.
 */
data class QueueSnapshot(
    val upNext: List<TrackEntity> = emptyList(),
    val playbackContext: PlaybackContext? = null,
    val shuffleEnabled: Boolean = false,
)

/**
 * Manages the playback queue, mirroring the desktop app's queue logic.
 *
 * Key design: the current track is NOT in the queue. The queue (`upNext`)
 * contains only upcoming tracks. A separate `playHistory` stack enables
 * back-navigation.
 *
 * All mutations emit a new [QueueSnapshot] via [snapshot].
 */
@Singleton
class QueueManager @Inject constructor() {

    private val upNext = mutableListOf<TrackEntity>()
    private val playHistory = mutableListOf<TrackEntity>()
    private var originalOrder: List<TrackEntity>? = null
    private var _playbackContext: PlaybackContext? = null
    private var _shuffleEnabled = false

    private val _snapshot = MutableStateFlow(QueueSnapshot())
    val snapshot: StateFlow<QueueSnapshot> = _snapshot.asStateFlow()

    /**
     * Set a new queue from a collection of tracks.
     *
     * Splits the track at [startIndex] out as the return value (to be played),
     * and puts everything after it into [upNext]. If shuffle is enabled,
     * shuffles upNext and saves original order.
     *
     * @return The track at [startIndex] to play immediately.
     */
    fun setQueue(
        tracks: List<TrackEntity>,
        startIndex: Int,
        context: PlaybackContext? = null,
        shuffle: Boolean = false,
    ): TrackEntity? {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return null

        val trackToPlay = tracks[startIndex]
        val remaining = tracks.subList(startIndex + 1, tracks.size).toMutableList()

        playHistory.clear()
        _playbackContext = context
        _shuffleEnabled = shuffle

        if (shuffle && remaining.size > 1) {
            originalOrder = remaining.toList()
            remaining.shuffle()
        } else {
            originalOrder = null
        }

        upNext.clear()
        upNext.addAll(remaining)
        emitSnapshot()
        return trackToPlay
    }

    /** Append tracks to the end of the queue. */
    fun addToQueue(tracks: List<TrackEntity>) {
        upNext.addAll(tracks)
        emitSnapshot()
    }

    /** Insert tracks at the front of the queue (play next). */
    fun insertNext(tracks: List<TrackEntity>) {
        upNext.addAll(0, tracks)
        emitSnapshot()
    }

    /**
     * Advance to the next track.
     * Pushes [currentTrack] onto play history and pops the first item from upNext.
     *
     * @return The next track, or null if queue is empty.
     */
    fun skipNext(currentTrack: TrackEntity?): TrackEntity? {
        if (upNext.isEmpty()) return null
        currentTrack?.let { playHistory.add(it) }
        val next = upNext.removeAt(0)
        emitSnapshot()
        return next
    }

    /**
     * Go back to the previous track.
     * Pops from play history and pushes [currentTrack] to the front of upNext.
     *
     * @return The previous track, or null if history is empty.
     */
    fun skipPrevious(currentTrack: TrackEntity?): TrackEntity? {
        if (playHistory.isEmpty()) return null
        currentTrack?.let { upNext.add(0, it) }
        val previous = playHistory.removeAt(playHistory.lastIndex)
        emitSnapshot()
        return previous
    }

    /**
     * User tapped a track in the queue UI.
     * Pushes [currentTrack] to history, removes everything before [index] in upNext,
     * pops the item at [index] and returns it.
     */
    fun playFromQueue(index: Int, currentTrack: TrackEntity?): TrackEntity? {
        if (index !in upNext.indices) return null
        currentTrack?.let { playHistory.add(it) }
        val track = upNext[index]
        // Keep only items after the clicked one
        val remaining = upNext.subList(index + 1, upNext.size).toList()
        upNext.clear()
        upNext.addAll(remaining)
        emitSnapshot()
        return track
    }

    /** Drag-to-reorder: move a track from one position to another. */
    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex !in upNext.indices || toIndex !in upNext.indices) return
        val item = upNext.removeAt(fromIndex)
        upNext.add(toIndex, item)
        emitSnapshot()
    }

    /** Remove a single track from the queue. */
    fun removeFromQueue(index: Int) {
        if (index !in upNext.indices) return
        upNext.removeAt(index)
        emitSnapshot()
    }

    /** Clear the queue. Resets shuffle state and context, but preserves play history. */
    fun clearQueue() {
        upNext.clear()
        originalOrder = null
        _playbackContext = null
        emitSnapshot()
    }

    /**
     * Toggle shuffle mode.
     * ON: save current upNext order, shuffle it (Fisher-Yates).
     * OFF: restore original order, removing items that were already played.
     */
    fun toggleShuffle(): Boolean {
        _shuffleEnabled = !_shuffleEnabled
        if (_shuffleEnabled) {
            if (upNext.size > 1) {
                originalOrder = upNext.toList()
                upNext.shuffle()
            }
        } else {
            val original = originalOrder
            if (original != null) {
                // Restore original order, keeping only tracks still in upNext
                val currentIds = upNext.map { it.id }.toSet()
                val restored = original.filter { it.id in currentIds }
                upNext.clear()
                upNext.addAll(restored)
                originalOrder = null
            }
        }
        emitSnapshot()
        return _shuffleEnabled
    }

    val shuffleEnabled: Boolean get() = _shuffleEnabled
    val playbackContext: PlaybackContext? get() = _playbackContext

    /** Set the playback context without changing the queue contents. */
    fun setContext(context: PlaybackContext?) {
        _playbackContext = context
        emitSnapshot()
    }

    /** Read-only access to play history for persistence. */
    val history: List<TrackEntity> get() = playHistory.toList()

    /** Read-only access to original order for persistence. */
    val savedOriginalOrder: List<TrackEntity>? get() = originalOrder?.toList()

    /**
     * Restore full queue state from persistence.
     * Called once on startup when persist-queue setting is enabled.
     */
    fun restoreState(
        restoredUpNext: List<TrackEntity>,
        restoredHistory: List<TrackEntity>,
        restoredOriginalOrder: List<TrackEntity>?,
        context: PlaybackContext?,
        shuffle: Boolean,
    ) {
        upNext.clear()
        upNext.addAll(restoredUpNext)
        playHistory.clear()
        playHistory.addAll(restoredHistory)
        originalOrder = restoredOriginalOrder
        _playbackContext = context
        _shuffleEnabled = shuffle
        emitSnapshot()
    }

    private fun emitSnapshot() {
        _snapshot.value = QueueSnapshot(
            upNext = upNext.toList(),
            playbackContext = _playbackContext,
            shuffleEnabled = _shuffleEnabled,
        )
    }
}
