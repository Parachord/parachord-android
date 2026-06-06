# iOS MusicKit Continuous Playback — Design

**Date:** 2026-06-06
**Status:** Validated, ready for implementation planning
**Goal:** Apple Music playlists play through continuously on iOS (auto-advance),
with a live Now Playing scrubber and lock-screen next/prev that drive the queue.

---

## Problem

The playback loop plays single Apple Music tracks on-device, but they don't
auto-advance: only `IosAVPlayer` fires an `onTrackEnded` callback (wired to
`skipNext`). `IosMusicKitPlayer` (wrapping `ApplicationMusicPlayer.shared`) has
no equivalent, so an AM playlist **stops after each song**. The coordinator's
`currentTime`/`duration` also return 0 for the MusicKit engine (dead scrubber),
and lock-screen next/prev only do skip±15s, not queue advance.

This mirrors Android's "Apple Music State Polling & Auto-Advance"
(CLAUDE.md) — adapted to native MusicKit instead of the WebView bridge.

---

## Components

### 1. `IosMusicKitPlayer` — state-polling loop + `onTrackEnded`

`ApplicationMusicPlayer.shared` exposes `.state.playbackStatus` and
`.playbackTime`; the playing `Song` carries `.duration`. A `Task` loop at
**500ms** on the main actor (MusicKit state is main-actor-bound) each tick:

1. **Publishes** `isPlaying` / `currentTime` / `duration` `@Observable` props
   (feeds the scrubber — currently 0 for MusicKit).
2. **Detects track end** two ways (like Android):
   - **Primary:** `playbackStatus` transitions `playing → stopped` (single-song
     queue, no repeat, stops at end).
   - **Safety net:** `currentTime > 0 && duration - currentTime < 1.0`.
   Either fires a new `var onTrackEnded: (() -> Void)?` (parallel to
   `IosAVPlayer.onTrackEnded`).

**Guards (load-bearing — from CLAUDE.md's Android AM rules):**
- `trackEndHandled` flag (reset in `play()`) → `onTrackEnded` fires **once** per
  track, not repeatedly while status stays `.stopped`.
- Require we've observed `.playing` before honoring a `.stopped` — so swapping
  the queue in `play()` (which briefly reports `.stopped`) doesn't false-fire.
- **Never block the loop** on the async catalog request; the loop only reads
  state. `play()` is fire-and-forget from the loop's view.
- The loop starts on first `play()`, runs continuously (cheap), and no-ops
  end-detection when the active engine isn't MusicKit.

### 2. `QueuePlaybackCoordinator` — queue-level transport + lock-screen owner

- **Auto-advance:** `init` wires `musicKit.onTrackEnded = { self?.skipNext() }`,
  parallel to the existing `player.onTrackEnded`. `skipNext` unchanged — it
  already resolves + routes the next track (calling `musicKit.play` for AM,
  which resets that track's end-guard).
- **Unified now-playing state:** the `currentTime`/`duration`/`isPlaying`
  computed props (already switch on `activeEngine`) return the polled MusicKit
  values instead of 0 → scrubber works for AM.
- **Lock screen (`MPNowPlayingInfoCenter`):** the coordinator publishes
  now-playing info (title/artist/artwork/duration/elapsed/rate) for the active
  track on every state change, engine-agnostic. `ApplicationMusicPlayer` also
  writes the center natively for AM; the coordinator's per-tick write
  **complements** it so our elapsed/duration drive a consistent scrubber. This
  overlap is the one piece flagged for on-device verification.
- **Lock-screen next/prev (`MPRemoteCommandCenter`):** register
  `nextTrackCommand → skipNext` and `previousTrackCommand → skipPrevious` (the
  "real next/previous" the AVPlayer skip±15s comment anticipated). Engine-
  agnostic — they drive the queue, not a player. `play`/`pause`/`toggle` route
  to `coordinator.togglePlayPause` (already dispatches by engine).
- **Avoid double-registration:** `IosAVPlayer` self-registers play/pause/skip on
  the shared command center in `init`; the coordinator-driven player suppresses
  its own registration (a flag), leaving the Dev tab's standalone player intact.

---

## Data flow

```
ApplicationMusicPlayer.shared
   │  (poll 500ms, main actor)
   ▼
IosMusicKitPlayer loop
   ├─ publish isPlaying/currentTime/duration ── coordinator props ── scrubber
   └─ end detected (status→stopped | near-end) ─ onTrackEnded ─┐
                                                               ▼
                                              QueuePlaybackCoordinator.skipNext
                                                 ├─ no next → stop engine, clear now-playing
                                                 └─ next → resolve → route
                                                        ├─ applemusic → musicKit.play (resets end-guard)
                                                        └─ stream/direct → IosAVPlayer (its own onTrackEnded)
Lock screen:
  MPRemoteCommandCenter.next/prev ── coordinator.skipNext/skipPrevious (engine-agnostic)
  MPNowPlayingInfoCenter ── coordinator per-tick (title/art/elapsed/duration/rate)
```

---

## Edge cases

- **Queue exhaustion:** `skipNext` with no next → `ApplicationMusicPlayer.pause()`
  + clear `currentTrack`; loop sees `.stopped` with no pending track → no
  re-fire (guard); lock screen clears.
- **Cross-engine advance** (AM → AVPlayer next): `trackEndHandled` + `activeEngine`
  flip means the MusicKit loop doesn't fire again for the finished AM track.
- **Manual skip vs natural end:** tapping Next calls `skipNext` directly; the
  abandoned track's guard is moot because `play()` resets it for the new track.
  No double-advance.
- **Pause/resume:** pause → `.paused` (not `.stopped`) → no false end.
- **Backgrounded:** `UIBackgroundModes: audio` set; `ApplicationMusicPlayer`
  keeps playing. If iOS throttles the main-actor loop, the `.stopped` transition
  still fires on resume (acceptable — matches Android's Doze caveat).

---

## Verification

- Build green on simulator.
- No clean unit test (ApplicationMusicPlayer + timing); small, review-driven
  poll/guard logic.
- **On-device is the real test** (simulator can't play AM): playlist advances
  track-to-track unattended; scrubber moves; lock screen shows the track and
  next/prev advance the queue. Validation hand-off, like the playback loop.

---

## Scope / YAGNI (out, documented not dropped)

- **Spotify auto-advance** — out (Spotify playback still stubbed); the
  `onTrackEnded` pattern generalizes to it when its engine lands.
- **Gapless / pre-buffer next AM track** — out; the resolver cache already
  pre-resolves the next track's ID, and MusicKit handles its own buffering.
- **No `preload()` equivalent** — CLAUDE.md warns it disrupts the active stream
  on Android; native MusicKit doesn't need it.

---

## Files

- `iosApp/Parachord/ContentView.swift` — `IosMusicKitPlayer` (poll loop +
  `onTrackEnded` + published state), `QueuePlaybackCoordinator` (wire
  `musicKit.onTrackEnded`, MusicKit branch of now-playing props, coordinator-
  level `MPRemoteCommandCenter` next/prev + `MPNowPlayingInfoCenter`),
  `IosAVPlayer` (suppress-own-remote-commands flag).
