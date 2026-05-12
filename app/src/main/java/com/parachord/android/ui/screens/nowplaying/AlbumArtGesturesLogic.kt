package com.parachord.android.ui.screens.nowplaying

import kotlin.math.abs
import kotlin.math.sign

/**
 * Swipe-release outcome for the album-art horizontal-drag gesture.
 *
 * The decision is pure: given the current drag offset and release
 * velocity, return whether to commit to the next track, the previous
 * track, or spring back to centered.
 */
sealed class SwipeOutcome {
    /** Commit to the next track (finger moved left, offsetX < 0). */
    object Next : SwipeOutcome()
    /** Commit to the previous track (finger moved right, offsetX > 0). */
    object Previous : SwipeOutcome()
    /** Drag didn't cross the threshold — animate back to centered. */
    object SnapBack : SwipeOutcome()
}

/**
 * Decide whether a horizontal drag should commit to next / previous /
 * snap-back.
 *
 * Rules:
 *  - If neither the drag distance NOR the release velocity crosses its
 *    threshold, snap back.
 *  - Otherwise, the SIGN of the dominant signal (whichever crossed)
 *    decides direction:
 *      - Velocity dominates when it crossed its threshold.
 *      - Otherwise the offset's sign decides.
 *    Convention: leftward motion (negative offset / negative velocity)
 *    → Next; rightward → Previous.
 *  - Velocity overrides offset when they disagree — handles "fast
 *    flick to throw back" past-threshold drags.
 *
 * @param offsetX horizontal drag distance in pixels. Negative = finger
 *   moved left.
 * @param velocity release velocity in px/s. Negative = leftward.
 * @param commitThreshold minimum |offsetX| (px) for an offset-based
 *   commit. Typically 30% of screen width.
 * @param velocityThreshold minimum |velocity| (px/s) for a
 *   velocity-based commit. Defaults to 600 px/s.
 */
fun decideSwipeCommit(
    offsetX: Float,
    velocity: Float,
    commitThreshold: Float,
    velocityThreshold: Float = 600f,
): SwipeOutcome {
    val absOffset = abs(offsetX)
    val absVelocity = abs(velocity)
    val offsetCrossed = absOffset >= commitThreshold
    val velocityCrossed = absVelocity >= velocityThreshold
    if (!offsetCrossed && !velocityCrossed) return SwipeOutcome.SnapBack
    // Velocity dominates when it crossed; otherwise offset's sign decides.
    val sign = if (velocityCrossed) (-velocity).sign else (-offsetX).sign
    return if (sign > 0) SwipeOutcome.Next else SwipeOutcome.Previous
}
