package com.parachord.shared.deeplink

/**
 * State teardown contract for protocol-driven playback transitions.
 *
 * Every `parachord://play/...` command that *replaces* the current
 * context (album / playlist / radio) must call
 * [prepareForNewPlayback] **before** building and submitting the new
 * queue. Otherwise the cleanup that `PlaybackController.handlePlay`
 * runs internally executes AFTER the new context is set, undoing the
 * caller's work.
 *
 * **Order matters** (per desktop CLAUDE.md § "Android Parity Requirements"):
 *
 * 1. **Exit spinoff** (`PlaybackController.exitSpinoff()`).
 *    Spinoff queues are derived state; if we leave them attached, the
 *    new context inherits the old spinoff parent and refill behavior.
 * 2. **Exit listen-along** (`MainViewModel.stopListenAlong()`).
 *    The listen-along ticker keeps mirroring the followed user's track
 *    changes; if we don't stop it before queue replacement, the next
 *    poll tick reverts our newly-set track to whatever the followed
 *    user is on.
 * 3. **Clear the queue** (`QueueManager.clearQueue()`).
 *    Wipes the prior context's tracks so the new ones aren't appended.
 *
 * Concrete implementation lives in `app/.../deeplink/AndroidProtocolPlayTeardown.kt`
 * (wired via Koin); we only define the contract here so future iOS work
 * has something to implement against.
 */
interface ProtocolPlayTeardown {
    /**
     * Run all three teardown steps **in order** (spinoff → listen-along
     * → queue clear). Idempotent: safe to call when no spinoff,
     * listen-along, or queue is active. Suspending so callers can wait
     * for the queue clear to settle before submitting new tracks.
     */
    suspend fun prepareForNewPlayback()
}
