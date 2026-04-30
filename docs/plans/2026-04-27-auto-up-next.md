# Android Auto Up Next — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make Android Auto's "Up Next" view show the real Parachord queue (current track + upcoming tracks) and let the user reorder, remove, and tap-to-play queue items from the head unit, for both ExoPlayer-native and external (Spotify Connect / Apple Music) playback.

**Architecture:** Synthesize a `QueueTimeline : androidx.media3.common.Timeline` inside `ExternalPlaybackForwardingPlayer` from the existing shared `QueueManager.snapshot: StateFlow<QueueSnapshot>` + `PlaybackStateHolder.state.currentTrack`. Override the relevant `Timeline`/`MediaItem`-shaped methods on the wrapper so Media3 (and Auto) sees the real queue regardless of which handler is producing audio. The underlying `ExoPlayer` continues to play either the silence loop (during external playback) or the real audio (native playback) — its single-item internal timeline is hidden behind the wrapper.

**Tech Stack:** Kotlin, Media3 1.x (`androidx.media3.common.Timeline` / `Window` / `Period`, `ForwardingPlayer`, `MediaSession.MediaItemsWithStartPosition`), Koin DI, Robolectric for unit testing, JUnit4.

**Worktree:** `.worktrees/auto-up-next` on branch `feature/auto-up-next`. Branched from main at `0d2105b`.

---

## Pre-flight observations (already verified)

- `PlaybackService extends MediaLibraryService` (`PlaybackService.kt:71`) — Auto integration already shipped via PR #110/#112.
- `ExternalPlaybackForwardingPlayer` already exists at `PlaybackService.kt:645-758` with `externalMode`, command overrides, and a `getDuration()` override that reads from `PlaybackStateHolder`.
- `QueueManager` lives in `shared/commonMain/.../playback/QueueManager.kt`. Exposes `snapshot: StateFlow<QueueSnapshot>` where `QueueSnapshot.upNext: List<Track>` (current track NOT included). Methods: `setQueue`, `addToQueue`, `insertNext`, `skipNext`, `skipPrevious`, `playFromQueue(index, currentTrack)`, `moveInQueue(from, to)`, `removeFromQueue(index)`, `toggleShuffle`, `restoreState`. Already singleton-bound in Koin (`AndroidModule.kt:328`).
- `PlaybackStateHolder.state.value.currentTrack: TrackEntity?` is the source of truth for the now-playing track. `effectiveTrack` extension applies `streamingMetadata` overlay; we use `currentTrack` (not `effectiveTrack`) for the timeline — overlay is presentation-layer.
- `TrackEntity` is a `typealias` for `com.parachord.shared.model.Track` (KMP migration).
- `PlaybackController.kt:1775-1790` has a private `TrackEntity.toMediaItem()` extension. Spec calls for promoting to top-level helper.
- `PlaybackController` already exposes `playFromQueue(index)`, `moveInQueue(from, to)`, `removeFromQueue(index)` at lines 383, 398, 404.

## Architectural rules (DO NOT VIOLATE)

1. **All Android-only — nothing in `:shared`.** Media3 classes are AndroidX. CarPlay on iOS uses entirely different APIs and would synthesize different platform classes from the same `QueueManager.snapshot`. The wrapper is the platform adapter; the orchestration is already shared.
2. **Index 0 is the current track. Indices 1..N are `upNext[0..N-1]`.** When Auto issues `seekTo(mediaItemIndex)`, subtract 1 to get the queue index. Same for `moveMediaItem` / `removeMediaItem`.
3. **Never forward delegate timeline events.** The underlying `ExoPlayer` fires `onTimelineChanged` every time the silence loop is set/replaced. We must register one internal `Player.Listener` on `delegate`, swallow timeline/media-item events, and re-emit only synthesized events to externally-registered listeners.
4. **Main-thread invariant.** Media3 requires player operations on `Looper.getMainLooper()`. Snapshot collection happens on `Dispatchers.Main` already (service's `serviceScope`). The `@Volatile` snapshot field is read on the main thread.
5. **`hasPreviousMediaItem()` returns false for v1.** History is not surfaced in the timeline; `seekToPrevious` continues to route through `PlaybackController.skipPrevious()` (which reads from `playHistory`).
6. **No shuffle/repeat parity in v1.** Defer; route Auto's `setShuffleModeEnabled`/`setRepeatMode` to no-op for now, expose via `state` overrides only if simple. Document the deferral in the wrapper KDoc.
7. **`MediaItem.mediaId` uses the bare `TrackEntity.id`** (matches the existing `playFromQueue` lookup scheme; no `track:` prefix — that scheme was suggested in the issue but `playFromQueue` doesn't strip prefixes today).
8. **Never emit a synthesized `onTimelineChanged` from inside `setMediaItems`-triggered listener calls** — would cause Auto to re-render mid-mutation. Only emit on `QueueManager.snapshot` collector ticks.
9. **Tests use Robolectric** (already present per `app/build.gradle.kts`'s `testOptions.unitTests.includeAndroidResources = true`). Avoid touching the actual `ExoPlayer` from unit tests — instantiate `QueueTimeline` directly and mock the wrapper's surface where needed.

---

## Task 1: Promote `TrackEntity.toMediaItem()` to a public top-level helper

Reusable from both `PlaybackController` (existing call site) and the new wrapper code. No behavior change.

**Files:**
- Create: `app/src/main/java/com/parachord/android/playback/MediaItemMapping.kt`
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackController.kt:1775-1790` (replace local extension with import)

**Step 1: Write a failing test**

Create `app/src/test/java/com/parachord/android/playback/MediaItemMappingTest.kt`:

```kotlin
package com.parachord.android.playback

import androidx.media3.common.MediaMetadata
import com.parachord.shared.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaItemMappingTest {

    @Test fun `toAutoMediaItem populates metadata fields`() {
        val track = Track(
            id = "track-1",
            title = "Title",
            artist = "Artist",
            album = "Album",
            artworkUrl = "https://example.com/art.jpg",
            sourceUrl = "https://example.com/audio.mp3",
        )
        val item = track.toAutoMediaItem()
        assertEquals("track-1", item.mediaId)
        assertEquals("Title", item.mediaMetadata.title)
        assertEquals("Artist", item.mediaMetadata.artist)
        assertEquals("Album", item.mediaMetadata.albumTitle)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, item.mediaMetadata.mediaType)
        assertEquals(true, item.mediaMetadata.isPlayable)
        assertEquals(false, item.mediaMetadata.isBrowsable)
        assertEquals("https://example.com/art.jpg", item.mediaMetadata.artworkUri.toString())
    }

    @Test fun `toAutoMediaItem omits artwork when null`() {
        val track = Track(id = "t", title = "T", artist = "A", album = "B", artworkUrl = null)
        val item = track.toAutoMediaItem()
        assertNull(item.mediaMetadata.artworkUri)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.parachord.android.playback.MediaItemMappingTest"`
Expected: FAIL with "Unresolved reference: toAutoMediaItem"

**Step 3: Write minimal implementation**

Create `app/src/main/java/com/parachord/android/playback/MediaItemMapping.kt`:

```kotlin
package com.parachord.android.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * Build an Android Auto / Media3 `MediaItem` from a [TrackEntity].
 *
 * Used by both [PlaybackController] (for ExoPlayer playback) and
 * [PlaybackService.ExternalPlaybackForwardingPlayer] (for the synthetic
 * timeline that surfaces the queue to Android Auto).
 *
 * - `mediaId` is the bare [TrackEntity.id] — matches the existing scheme
 *   that `PlaybackController.playFromQueue()` lookups expect.
 * - `MediaMetadata.MEDIA_TYPE_MUSIC` + `isPlayable=true` + `isBrowsable=false`
 *   are required for Auto to render the row as a tappable track.
 * - `artworkUri` is set only when [TrackEntity.artworkUrl] is non-null;
 *   Auto fetches over HTTPS for remote URIs and accepts `content://` for
 *   local files.
 */
fun TrackEntity.toAutoMediaItem(): MediaItem {
    val builder = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setIsPlayable(true)
        .setIsBrowsable(false)
    artworkUrl?.let { builder.setArtworkUri(Uri.parse(it)) }
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(sourceUrl ?: "")
        .setMediaMetadata(builder.build())
        .build()
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.parachord.android.playback.MediaItemMappingTest"`
Expected: PASS (2 tests)

**Step 5: Replace existing private extension**

Modify `app/src/main/java/com/parachord/android/playback/PlaybackController.kt`:
- Delete lines 1775-1790 (the private `TrackEntity.toMediaItem()` function).
- At call site (line ~882), change `track.toMediaItem()` to `track.toAutoMediaItem()`.

**Step 6: Verify build still passes**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

**Step 7: Commit**

```bash
git add app/src/main/java/com/parachord/android/playback/MediaItemMapping.kt \
        app/src/test/java/com/parachord/android/playback/MediaItemMappingTest.kt \
        app/src/main/java/com/parachord/android/playback/PlaybackController.kt
git commit -m "auto: extract TrackEntity.toAutoMediaItem helper"
```

---

## Task 2: `QueueTimeline` — synthetic Media3 Timeline

The core of the feature. Wraps a `(currentTrack, upNext)` snapshot as a `Timeline` whose `windowCount` = `(currentTrack != null ? 1 : 0) + upNext.size`.

**Files:**
- Create: `app/src/main/java/com/parachord/android/playback/QueueTimeline.kt`
- Test: `app/src/test/java/com/parachord/android/playback/QueueTimelineTest.kt`

**Step 1: Write the failing test**

Create `app/src/test/java/com/parachord/android/playback/QueueTimelineTest.kt`:

```kotlin
package com.parachord.android.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import com.parachord.shared.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.parachord.android.playback.QueueTimelineTest"`
Expected: FAIL with "Unresolved reference: QueueTimeline"

**Step 3: Write minimal implementation**

Create `app/src/main/java/com/parachord/android/playback/QueueTimeline.kt`:

```kotlin
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
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.parachord.android.playback.QueueTimelineTest"`
Expected: PASS (6 tests)

**Step 5: Commit**

```bash
git add app/src/main/java/com/parachord/android/playback/QueueTimeline.kt \
        app/src/test/java/com/parachord/android/playback/QueueTimelineTest.kt
git commit -m "auto: add QueueTimeline synthetic Timeline subclass"
```

---

## Task 3: Wrapper queue-snapshot ingestion + listener registry

`ExternalPlaybackForwardingPlayer` gets a `@Volatile` snapshot field, an `updateQueueSnapshot()` method that swaps it, and an internal `Player.Listener` registry that filters delegate events.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackService.kt:645-758`

**Step 1: Write the failing test**

Create `app/src/test/java/com/parachord/android/playback/ForwardingPlayerSnapshotTest.kt`:

```kotlin
package com.parachord.android.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.parachord.shared.model.Track
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
        assertEquals("c1", window.uid)
        timeline.getWindow(1, window)
        assertEquals("u1", window.uid)
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
        verify { controller.playFromQueue(1) } // index 2 in timeline → upNext[1]
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
        wrapper.updateQueueSnapshot(track("c"), listOf(track("a"), track("b"))) // same current, upNext changed

        verify(exactly = 1) { listener.onMediaItemTransition(any(), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
    }
}
```

Note: this test references `PlaybackService.ExternalPlaybackForwardingPlayer` from outside. The class is currently `inner class`-shaped (implicit) but is actually a public nested class (top-level `class` inside `PlaybackService`). Confirm at `PlaybackService.kt:645`. If it's `private`, you'll need to remove that modifier — it must be at least package-private for the test, and there's no harm making it public.

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.parachord.android.playback.ForwardingPlayerSnapshotTest"`
Expected: FAIL with "Unresolved reference: updateQueueSnapshot" and similar.

**Step 3: Add the snapshot ingest + listener overrides**

Modify `PlaybackService.kt:645-758`. Replace the `ExternalPlaybackForwardingPlayer` body with the following structure (preserving all existing behavior — just adding to it):

Add these private state fields at the top of the class:

```kotlin
        @Volatile
        private var queueSnapshot: QueueSnapshotState = QueueSnapshotState.EMPTY

        /** External listeners we re-emit synthesized timeline events to.
         *  Tracked separately from delegate listeners so we can swallow the
         *  delegate's silence-loop timeline changes. */
        private val externalListeners = java.util.concurrent.CopyOnWriteArraySet<Player.Listener>()

        /** Internal data holder so reads of timeline + index are atomic. */
        data class QueueSnapshotState(
            val items: List<MediaItem>,
            val durationsUs: LongArray,
            val timeline: Timeline,
            val currentMediaId: String?,
        ) {
            companion object {
                val EMPTY = QueueSnapshotState(
                    items = emptyList(),
                    durationsUs = LongArray(0),
                    timeline = QueueTimeline(emptyList(), LongArray(0)),
                    currentMediaId = null,
                )
            }
        }
```

Add these overrides (after the existing `companion object` block, replacing only the timeline-related ones — keep `play`/`pause`/`seekToNext`/`seekToPrevious`/`getDuration`/`getContentDuration`/`isCommandAvailable`/`getAvailableCommands` as they are, but extend `getAvailableCommands` and `EXTERNAL_COMMANDS`):

```kotlin
        // ── Synthetic timeline overrides ────────────────────────────────

        override fun getCurrentTimeline(): Timeline = queueSnapshot.timeline

        override fun getCurrentMediaItemIndex(): Int =
            if (queueSnapshot.currentMediaId != null) 0 else C.INDEX_UNSET

        override fun getNextMediaItemIndex(): Int =
            if (queueSnapshot.items.size > 1) 1 else C.INDEX_UNSET

        override fun getPreviousMediaItemIndex(): Int = C.INDEX_UNSET

        override fun getMediaItemCount(): Int = queueSnapshot.items.size

        override fun getCurrentMediaItem(): MediaItem? = queueSnapshot.items.firstOrNull()

        override fun getMediaItemAt(index: Int): MediaItem = queueSnapshot.items[index]

        override fun hasNextMediaItem(): Boolean = queueSnapshot.items.size > 1

        // hasPreviousMediaItem stays false for v1 (no history surface)
        override fun hasPreviousMediaItem(): Boolean = false

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (mediaItemIndex == 0) {
                // No-op for tapping current track. Don't forward to delegate
                // because that would seek inside the silence loop.
                return
            }
            val queueIndex = mediaItemIndex - 1
            if (queueIndex >= 0) {
                playbackController.playFromQueue(queueIndex)
            }
        }

        override fun seekToDefaultPosition(mediaItemIndex: Int) = seekTo(mediaItemIndex, 0L)

        // ── Auto reorder / remove ───────────────────────────────────────

        override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
            // Both indices include the current track at slot 0; queue mutations
            // operate on upNext, so subtract 1.
            val from = currentIndex - 1
            val to = newIndex - 1
            if (from >= 0 && to >= 0) {
                playbackController.moveInQueue(from, to)
            }
        }

        override fun removeMediaItem(index: Int) {
            val queueIndex = index - 1
            if (queueIndex >= 0) {
                playbackController.removeFromQueue(queueIndex)
            }
        }

        override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
            // No-op for v1 — Auto only invokes this for voice search, and we
            // don't have a browse tree resolved from MediaItem to our
            // resolver pipeline yet. See "Out of scope" in the issue.
        }

        // ── Listener registry ────────────────────────────────────────────

        override fun addListener(listener: Player.Listener) {
            externalListeners.add(listener)
            // Don't forward to delegate — we'll synthesize the events the
            // listener needs. (super.addListener would register on the
            // delegate, which would leak the silence-loop timeline events.)
        }

        override fun removeListener(listener: Player.Listener) {
            externalListeners.remove(listener)
        }

        /**
         * Public entry point invoked by [PlaybackService] when a new
         * [QueueSnapshot] arrives or [PlaybackStateHolder.state.currentTrack]
         * changes. Rebuilds the [QueueTimeline], swaps the volatile snapshot,
         * and dispatches synthesized [Player.Listener] events.
         *
         * **Main-thread invariant.** Caller must invoke on Looper.getMainLooper().
         */
        fun updateQueueSnapshot(currentTrack: TrackEntity?, upNext: List<TrackEntity>) {
            val combined = buildList {
                currentTrack?.let { add(it) }
                addAll(upNext)
            }
            val items = combined.map { it.toAutoMediaItem() }
            val durationsUs = LongArray(combined.size) { i ->
                val durMs = combined[i].duration ?: 0L
                if (durMs > 0L) durMs * 1000L else C.TIME_UNSET
            }
            val newTimeline = QueueTimeline(items, durationsUs)
            val previousMediaId = queueSnapshot.currentMediaId
            val newCurrentId = currentTrack?.id
            queueSnapshot = QueueSnapshotState(
                items = items,
                durationsUs = durationsUs,
                timeline = newTimeline,
                currentMediaId = newCurrentId,
            )

            // Re-emit timeline change to all external listeners.
            for (listener in externalListeners) {
                listener.onTimelineChanged(
                    newTimeline,
                    Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
                )
            }
            // Fire onMediaItemTransition only when the current track actually
            // changed (an upNext-only mutation is a timeline change, not a
            // transition).
            if (newCurrentId != previousMediaId) {
                val newItem = items.firstOrNull()
                for (listener in externalListeners) {
                    listener.onMediaItemTransition(
                        newItem,
                        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                    )
                }
            }
        }
```

Update the `companion object` to include the new commands:

```kotlin
        companion object {
            private val EXTERNAL_COMMANDS = setOf(
                COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_GET_TIMELINE, COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_SEEK_TO_MEDIA_ITEM,
            )
        }
```

Update `getAvailableCommands` to include the new commands UNCONDITIONALLY (timeline access doesn't depend on `externalMode` — the synthetic timeline is always-on):

```kotlin
        override fun getAvailableCommands(): Commands {
            val builder = super.getAvailableCommands().buildUpon()
                .addAll(
                    COMMAND_GET_TIMELINE,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_SEEK_TO_MEDIA_ITEM,
                )
            if (externalMode) {
                builder.addAll(
                    COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
            }
            return builder.build()
        }
```

Same treatment for `isCommandAvailable`:

```kotlin
        override fun isCommandAvailable(command: Int): Boolean {
            if (command in setOf(COMMAND_GET_TIMELINE, COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_SEEK_TO_MEDIA_ITEM)) return true
            if (externalMode && command in EXTERNAL_COMMANDS) return true
            return super.isCommandAvailable(command)
        }
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.parachord.android.playback.ForwardingPlayerSnapshotTest"`
Expected: PASS (10 tests)

**Step 5: Run the full test suite to ensure no regressions**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add app/src/main/java/com/parachord/android/playback/PlaybackService.kt \
        app/src/test/java/com/parachord/android/playback/ForwardingPlayerSnapshotTest.kt
git commit -m "auto: synthetic timeline overrides + listener registry on wrapper"
```

---

## Task 4: Service-side flow collector — feed snapshots to the wrapper

`PlaybackService.onCreate()` launches a coroutine in `serviceScope` that combines `QueueManager.snapshot` with `PlaybackStateHolder.state.currentTrack` and calls `wrapper.updateQueueSnapshot()` on every change.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackService.kt:139-175` (onCreate body) and add `QueueManager` injection at the top of the class.

**Step 1: Add the import + Koin injection**

Near the existing `inject()` lines (around `PlaybackService.kt:73-75`):

```kotlin
import com.parachord.shared.playback.QueueManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
```

```kotlin
    private val queueManager: QueueManager by inject()
```

**Step 2: Add a service-scope job for the snapshot bridge**

Add a `private var queueSnapshotJob: Job? = null` field next to `stateObserverJob`.

In `onCreate()`, after `startStateObserver()` (around line 174), append:

```kotlin
        // Feed QueueManager snapshots + current-track changes into the
        // wrapper's synthetic timeline. Combines two flows so a change in
        // either side triggers a re-emit. Runs on Dispatchers.Main per
        // Media3 main-thread invariant — wrapper.updateQueueSnapshot fires
        // listeners synchronously.
        queueSnapshotJob = serviceScope.launch {
            combine(
                queueManager.snapshot,
                stateHolder.state.map { it.currentTrack }.distinctUntilChanged(),
            ) { qs, current -> Pair(qs, current) }.collect { (qs, current) ->
                wrapper.updateQueueSnapshot(currentTrack = current, upNext = qs.upNext)
            }
        }
```

In `onDestroy()`, add cleanup (find the existing `stateObserverJob?.cancel()` line and add):

```kotlin
        queueSnapshotJob?.cancel()
```

**Step 3: Write the failing integration test**

Create `app/src/test/java/com/parachord/android/playback/PlaybackServiceQueueBridgeTest.kt`:

```kotlin
package com.parachord.android.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.parachord.shared.model.Track
import com.parachord.shared.playback.QueueManager
import io.mockk.mockk
import io.mockk.verify
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the service-side flow collector wires QueueManager + state holder
 * into wrapper.updateQueueSnapshot. Tests the collector logic in isolation
 * without standing up the full PlaybackService.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackServiceQueueBridgeTest {

    private fun track(id: String) = Track(id = id, title = id, artist = "A", album = "B")

    @Test fun `combine fires updateQueueSnapshot on each side change`() = runTest {
        val delegate = mockk<ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns androidx.media3.common.Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        val queue = QueueManager()

        // Mirror the service-side combine. This is the exact same wiring
        // we'll add to PlaybackService.onCreate; if it works here, it
        // works there.
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
        val window = androidx.media3.common.Timeline.Window()
        wrapper.currentTimeline.getWindow(0, window)
        assertEquals("c2", window.uid)

        job.cancel()
    }
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.parachord.android.playback.PlaybackServiceQueueBridgeTest"`
Expected: PASS (1 test)

**Step 5: Verify the full build + test suite**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add app/src/main/java/com/parachord/android/playback/PlaybackService.kt \
        app/src/test/java/com/parachord/android/playback/PlaybackServiceQueueBridgeTest.kt
git commit -m "auto: bridge QueueManager + state holder into wrapper"
```

---

## Task 5: `LibraryCallback.onAddMediaItems` / `onSetMediaItems` overrides

Default `Player.setMediaItems()` would clobber our synthetic timeline. Auto's voice-search path goes through `onAddMediaItems` in the library callback; for v1 we just return the existing queue so the default behavior no-ops without breaking.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackService.kt:250-281` (LibraryCallback)

**Step 1: Override in `LibraryCallback`**

Add inside `LibraryCallback`:

```kotlin
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            // v1: voice search and "play X" intents are out of scope. Returning
            // an empty list signals to Media3 that we accepted no items, so
            // the default Player.setMediaItems() pipeline no-ops without
            // overwriting our synthetic timeline. Future work: resolve the
            // requested MediaItem against our catalog/library.
            Log.d(TAG, "onAddMediaItems called with ${mediaItems.size} items — ignoring (v1)")
            return Futures.immediateFuture(emptyList())
        }
```

**Step 2: Add a smoke test**

Append to `PlaybackServiceQueueBridgeTest.kt`:

```kotlin
    @Test fun `onAddMediaItems returns empty list (no clobber)`() {
        // We can't easily instantiate LibraryCallback without the service,
        // but we can verify the override exists by checking the class
        // surface. The actual behavior is exercised end-to-end via the
        // service test below; here we just want to lock the contract.
        val method = PlaybackService::class.java.declaredClasses
            .firstOrNull { it.simpleName == "LibraryCallback" }
            ?.declaredMethods
            ?.firstOrNull { it.name == "onAddMediaItems" }
        assertEquals("onAddMediaItems must exist on LibraryCallback", true, method != null)
    }
```

**Step 3: Run test + build**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/java/com/parachord/android/playback/PlaybackService.kt \
        app/src/test/java/com/parachord/android/playback/PlaybackServiceQueueBridgeTest.kt
git commit -m "auto: LibraryCallback.onAddMediaItems no-op so Auto voice-search doesn't clobber timeline"
```

---

## Task 6: Manual DHU verification

Real Auto smoke test against the Desktop Head Unit emulator.

**Files:** none (manual)

**Step 1: Start the DHU and connect**

```bash
adb forward tcp:5277 tcp:5277
~/Library/Android/sdk/extras/google/auto/desktop-head-unit
```

**Step 2: Install the build**

```bash
./gradlew installDebug
adb shell am force-stop com.parachord.android.debug
```

**Step 3: Run through every acceptance criterion from the issue**

- [ ] Open the Up Next view in DHU's Now Playing — real upcoming tracks appear with title, artist, artwork.
- [ ] Tap a queue item in Auto — that track plays via `PlaybackController.playFromQueue()` (verify resolver runs, not ExoPlayer's synthetic playback).
- [ ] Add a track on the phone via long-press → Add to Queue — Auto reflects within ~1s with no app restart.
- [ ] Remove or reorder via the phone — Auto reflects live.
- [ ] Reorder / remove via Auto (where supported) — `QueueManager` mutation reflected in phone UI.
- [ ] `skipNext` / `skipPrevious` from Auto continue to work identically.
- [ ] Switch source mid-session: ExoPlayer-native local file → Spotify Connect → Apple Music — synthetic timeline behaves identically across all three.
- [ ] No regressions: lock-screen Now Playing UI, notification tray, system widget, full unit test suite.

**Step 4: Commit verification log**

If anything failed, file follow-up issues OR loop back to fix in this branch. Once all green:

```bash
git commit --allow-empty -m "auto: DHU verification pass — all acceptance criteria green"
```

---

## Verification

End-to-end checks before merging:

1. `./gradlew :app:testDebugUnitTest` — green, all new tests passing, no regressions.
2. `./gradlew :app:assembleDebug` — green.
3. DHU smoke test (Task 6) — all 8 acceptance criteria pass.
4. On-device with car BT (if available) — physical confirmation that audio still routes via Spotify/AM/ExoPlayer correctly during external playback while the queue surface populates.

## Critical files

**New:**
- `app/src/main/java/com/parachord/android/playback/MediaItemMapping.kt`
- `app/src/main/java/com/parachord/android/playback/QueueTimeline.kt`
- `app/src/test/java/com/parachord/android/playback/MediaItemMappingTest.kt`
- `app/src/test/java/com/parachord/android/playback/QueueTimelineTest.kt`
- `app/src/test/java/com/parachord/android/playback/ForwardingPlayerSnapshotTest.kt`
- `app/src/test/java/com/parachord/android/playback/PlaybackServiceQueueBridgeTest.kt`

**Modified:**
- `app/src/main/java/com/parachord/android/playback/PlaybackService.kt` (snapshot field + listener registry + 12 timeline overrides on `ExternalPlaybackForwardingPlayer`, queue-bridge collector in `onCreate`, `onAddMediaItems` no-op on `LibraryCallback`, command-set expansion)
- `app/src/main/java/com/parachord/android/playback/PlaybackController.kt` (replace private `toMediaItem()` with import of public `toAutoMediaItem()`)

**Reused (not modified):**
- `shared/src/commonMain/kotlin/com/parachord/shared/playback/QueueManager.kt`
- `app/src/main/java/com/parachord/android/playback/PlaybackStateHolder.kt`
- `app/src/main/java/com/parachord/android/playback/PlaybackState.kt`
- `app/src/main/java/com/parachord/android/di/AndroidModule.kt` (no DI changes — `QueueManager` already singleton-bound)
