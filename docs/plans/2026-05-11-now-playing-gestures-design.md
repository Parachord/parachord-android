# Now Playing gestures — design

**Goal:** Add two touch gestures to the album-art region of the Now Playing screen — double-tap to love (Instagram-classic, always-add) and horizontal swipe to skip to the previous/next track.

**Architecture:** A new `AlbumArtWithGestures` composable in `ui/screens/nowplaying/AlbumArtGestures.kt` wraps the existing album-art image, layers a heart-pop overlay, and exposes three callbacks (`onDoubleTapLove`, `onSwipeNext`, `onSwipePrevious`) wired into the existing `NowPlayingViewModel.addToCollection` + `PlaybackController.skipNext/skipPrevious` paths.

**Tech stack:** Jetpack Compose `pointerInput` + `detectTapGestures` + `detectHorizontalDragGestures`. `Animatable<Float>` for scale/alpha/offset. `LibraryRepository.isInCollection(track)` Flow for the loved-state read.

## Decisions

| # | Question | Choice |
|---|---|---|
| 1 | Love semantics | **Instagram-strict (always-add).** Double-tapping an already-loved track does nothing. Removal goes through the existing context menu. |
| 2 | Swipe direction | **Finger left → next.** Matches Spotify / Apple Music / Instagram Reels. |
| 3 | Gesture target area | **Album art only.** Slider, play/pause, queue button untouched. |
| 4 | Heart animation | **Instagram-classic overlay.** Large white heart, fades in + scale-pops from 0.5 → 1.5 → 1.0, fades out over ~600ms total. At the touch point, not centered. Haptic tick. Only fires on a *new* love. |
| 5 | Swipe mechanics | **Threshold-based with finger-follow and spring-back.** Album art drifts with finger; commits on ≥30% drag OR fast release velocity; otherwise springs back. |

## Sections

### 1. Architecture + gesture wiring

#### New file

`app/src/main/java/com/parachord/android/ui/screens/nowplaying/AlbumArtGestures.kt`

#### Public API

```kotlin
@Composable
fun AlbumArtWithGestures(
    artworkUrl: String?,
    isLoved: Boolean,
    onDoubleTapLove: () -> Unit,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    modifier: Modifier = Modifier,
)
```

#### Callsite

`NowPlayingScreen.kt` replaces its existing album-art `AsyncImage` (or equivalent) with `AlbumArtWithGestures`. Wires:

- `onDoubleTapLove` → `viewModel.addToCollection(currentTrack)` (existing path; idempotent — repeats just no-op so the gesture handler doesn't need to dedupe).
- `onSwipeNext` → `playbackController.skipNext()` (user-initiated; same as the existing next button).
- `onSwipePrevious` → `playbackController.skipPrevious()`.
- `isLoved` → derived from `LibraryRepository.isInCollection(track)` as a `StateFlow<Boolean>` collected with `collectAsStateWithLifecycle()`. Used only to gate the heart-pop animation (Instagram-strict: animation only on the first add).

#### Gesture coexistence

Single `Modifier.pointerInput(track.id) { … }` block (keyed on track ID so the gesture detector resets when the track changes mid-gesture — defensive against stale state). Two separate detectors inside:

```kotlin
launch { detectTapGestures(onDoubleTap = { offset -> handleDoubleTap(offset) }) }
launch {
    detectHorizontalDragGestures(
        onDragStart = { … },
        onHorizontalDrag = { _, dx -> … },
        onDragEnd = { … },
        onDragCancel = { … },
    )
}
```

Compose's gesture arbitration treats the two detectors as siblings — a confirmed horizontal drag won't fire `onDoubleTap`, and vice versa. Vertical drag isn't intercepted so a future parent scroll-container won't be blocked.

### 2. Double-tap to love + heart-pop animation

#### Flow on double-tap

1. Compose calls `onDoubleTap(offset: Offset)` with the touch position in composable-local pixels.
2. Read current `isLoved` synchronously.
3. **If NOT loved:** invoke `onDoubleTapLove()`. Set `poppedAt = offset`. Fire `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)`.
4. **If already loved:** no-op. No animation. No haptic. (Instagram-strict.)

The loved-state check is done *at gesture time*, not via `LaunchedEffect(isLoved)`. This anchors the animation to the explicit gesture rather than the repository state change — so a love from the context menu doesn't accidentally trigger an overlay.

#### Heart-pop animation

State:

```kotlin
var poppedAt: Offset? by remember { mutableStateOf(null) }
val scale = remember { Animatable(0.5f) }
val alpha = remember { Animatable(0f) }
```

Render (inside the album-art `Box`):

```kotlin
poppedAt?.let { pos ->
    Icon(
        imageVector = Icons.Filled.Favorite,
        contentDescription = null,   // decorative; the love itself is announced via the context-menu state
        tint = Color.White,
        modifier = Modifier
            .size(96.dp)
            .offset { IntOffset(pos.x.toInt() - 48.dp.toPx().toInt(), pos.y.toInt() - 48.dp.toPx().toInt()) }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            }
    )
}

LaunchedEffect(poppedAt) {
    if (poppedAt == null) return@LaunchedEffect
    // Reset
    scale.snapTo(0.5f)
    alpha.snapTo(0f)
    // Animate
    coroutineScope {
        launch {
            scale.animateTo(1.5f, tween(150))
            scale.animateTo(1.0f, tween(100))
        }
        launch {
            alpha.animateTo(1f, tween(100))
            delay(200)
            alpha.animateTo(0f, tween(300))
        }
    }
    poppedAt = null   // remove from composition after the animation completes
}
```

Total duration ~600ms. Restarts cleanly on rapid repeat double-taps because `LaunchedEffect(poppedAt)` re-runs when `poppedAt` changes.

#### Drop shadow / readability

The icon needs to read against any artwork. Two options, decide at implementation:
1. `Modifier.shadow(8.dp, CircleShape)` on a containing `Box` — soft shadow under the heart.
2. White heart with a 1.dp dark outline via `Icon(painter = …)` from a vector asset that has the outline baked in.

Option 1 is simpler and matches Material 3 elevation tokens. Default to that unless the visual looks washed out on light album art.

### 3. Swipe to skip — drag + commit + risks

#### Drag state

```kotlin
val offsetX = remember { Animatable(0f) }
var dragInProgress by remember { mutableStateOf(false) }
val velocityTracker = remember { VelocityTracker() }
val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
```

`offsetX` drives the album art's horizontal translation. `velocityTracker` accumulates per-frame deltas to compute release velocity.

#### Drag handlers

- **`onDragStart(start: Offset)`**:
  - Reject if `start.x < EDGE_GUARD_PX` or `start.x > width - EDGE_GUARD_PX` (16.dp default) → leave system back-gesture intact.
  - `dragInProgress = true`. Cancel any in-flight `offsetX.animateTo`.
- **`onHorizontalDrag(change, dx)`**:
  - `offsetX.snapTo(offsetX.value + dx)`.
  - `velocityTracker.addPosition(change.uptimeMillis, change.position)`.
- **`onDragEnd`**:
  - `dragInProgress = false`.
  - Compute `velocity = velocityTracker.calculateVelocity().x`.
  - Call `decideSwipeCommit(offsetX.value, velocity, COMMIT_THRESHOLD)` → returns `SwipeOutcome.Next | SwipeOutcome.Previous | SwipeOutcome.SnapBack`.
  - On `Next`/`Previous`: `offsetX.animateTo(±screenWidthPx, tween(200))`, then call the callback; the next track's compose pass will reset `offsetX` to 0 with the new artwork.
  - On `SnapBack`: `offsetX.animateTo(0f, spring())`.
- **`onDragCancel`**: same as `SnapBack`.

#### Decision function (pure, testable)

```kotlin
// AlbumArtGesturesLogic.kt — public for testing
sealed class SwipeOutcome {
    object Next : SwipeOutcome()
    object Previous : SwipeOutcome()
    object SnapBack : SwipeOutcome()
}

fun decideSwipeCommit(
    offsetX: Float,
    velocity: Float,
    commitThreshold: Float,
    velocityThreshold: Float = 600f,
): SwipeOutcome {
    val absOffset = abs(offsetX)
    val absVelocity = abs(velocity)
    if (absOffset >= commitThreshold || absVelocity >= velocityThreshold) {
        // Use the sign of whichever wins. Velocity overrides offset when both
        // disagree (rare — fast flick partway).
        val sign = if (absVelocity >= velocityThreshold) velocity.unaryMinus().sign else (-offsetX).sign
        return if (sign > 0) SwipeOutcome.Next else SwipeOutcome.Previous
    }
    return SwipeOutcome.SnapBack
}
```

Velocity-overrides-offset when they disagree handles the rare "fast flick partway" case (Spotify allows this).

#### Constants

```kotlin
private val SWIPE_COMMIT_FRACTION = 0.30f      // 30% of screen width
private val SWIPE_VELOCITY_THRESHOLD = 600f    // px/s
private val EDGE_GUARD_DP = 16.dp              // back-gesture reserve
```

`COMMIT_THRESHOLD_PX = screenWidthPx * SWIPE_COMMIT_FRACTION` computed once per composition.

#### Visual during drag

```kotlin
Modifier
    .offset { IntOffset(offsetX.value.toInt(), 0) }
    .graphicsLayer { alpha = 1f - (abs(offsetX.value) / screenWidthPx) * 0.3f }
```

Slight fade as the art drifts so the user sees it "leaving". Caps at 70% opacity (when fully off-screen would be 70% per the formula — adequate signal without losing the artwork).

#### End-of-queue edge case

`playbackController.skipNext()` and `.skipPrevious()` are both no-ops at queue boundaries. The gesture still produces a slide-off animation regardless — when the callback fires and nothing changes, the same artwork renders again with `offsetX = 0` because the track ID hasn't changed (so `pointerInput(track.id)` doesn't reset; the animation just lands on the same value). Net effect: a "swipe-and-rebound" feel, which correctly communicates "tried, nothing happened."

#### Testing strategy

**`AlbumArtGesturesLogicTest` (pure Kotlin, `:app/src/test`):**

- `belowThresholdAndSlowVelocity_snapsBack`
- `aboveThresholdAndSlowVelocity_commitsInOffsetDirection`
- `belowThresholdButFastNegativeVelocity_commitsNext`
- `belowThresholdButFastPositiveVelocity_commitsPrevious`
- `velocityDisagreesWithOffset_velocityWins`
- `zeroDrag_zeroVelocity_snapsBack`

8 cases or so. Pure math, deterministic.

**Manual on-device matrix** (documented in the implementation plan):

- Double-tap on un-loved track → heart pops, track is loved.
- Double-tap on already-loved track → silent, no animation.
- Slow drag <30% → snaps back.
- Slow drag ≥30% → commits to next/previous.
- Fast flick <30% → commits (velocity override).
- Drag starting from screen edge → ignored (back gesture works).
- Rapid alternating gestures (tap, drag, tap, drag) → no stuck states.

#### Risks

1. **System back-gesture conflict** at screen edges. Mitigated by `EDGE_GUARD_DP`. If users still complain, widen to `24.dp` or detect ongoing back-gesture via system insets.
2. **Coil image swap latency**. New track's artwork may not be cached. Render a translucent placeholder (Compose `Box` with `MaterialTheme.colorScheme.surface` at 30% alpha) so the post-swipe layout doesn't show a hole.
3. **Tap-during-drag arbitration**. `detectTapGestures` and `detectHorizontalDragGestures` in the same `pointerInput` can race. If users report misfires, gate `onDoubleTap` on `!dragInProgress` and on "no drag movement in the last 200ms".
4. **Talkback / accessibility**. Gesture-only affordances are inaccessible to screen-reader users. The existing context menu (long-press) already exposes love + skip. Document this as the a11y path. Future work could add a `semantics { onLongClick(label = "Love track") }` action if needed.

## Out of scope

- Vertical swipe (no behavior defined; ignore).
- Pinch-to-zoom on album art.
- Long-press for a separate action (preserves Compose's existing combined-clickable contract for parent containers).
- Animated heart-pop on context-menu love (only the double-tap gesture triggers the overlay).
- Auto-scroll the queue UI on swipe (skip changes the current track; queue UI updates via its own state observers).
- Multi-tap (triple-tap, four-tap) — undefined behavior.

## Cross-refs

- `app/src/main/java/com/parachord/android/playback/PlaybackController.kt` — `skipNext()` / `skipPrevious()` entry points.
- `app/src/main/java/com/parachord/android/data/repository/LibraryRepository.kt` — `addToCollection(track)` + `isInCollection(track): Flow<Boolean>`.
- `app/src/main/java/com/parachord/android/ui/screens/nowplaying/NowPlayingViewModel.kt:145` — existing `addToCollection` VM hook.
- `app/src/main/java/com/parachord/android/ui/screens/nowplaying/NowPlayingScreen.kt` — callsite to update.
