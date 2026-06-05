# iOS Playback Loop — Design

**Date:** 2026-06-05
**Status:** Validated, ready for implementation planning
**Goal:** Close the playback loop on iOS — tapping a track in a production
screen resolves through the shared `.axe` pipeline and plays through the
right engine, with the mini player + Now Playing reflecting it.

---

## Governing rules (from CLAUDE.md — design is built against these)

This design lives entirely inside areas CLAUDE.md already governs. The rules
below are the spec; the components exist to satisfy them.

- **Resolver Pipeline Rules.** Every screen that shows a list of playable
  tracks calls `resolveInBackground(tracks)` when tracks load. Always pass
  `targetTitle` + `targetArtist` to `resolve()`. Only call active resolvers
  (`getActiveResolvers()`; empty list ⇒ all implemented). Select with
  `ResolverScoring.selectBest`, never `sources.first`.
- **On-the-fly Track Resolution (two-layer).** (1) Background pre-resolution
  populates a cache so badges appear and playback starts instantly; (2)
  on-the-fly fallback resolves at play time only when a track wasn't
  pre-resolved.
- **Resolver Badge Display.** Filter `noMatch`/`<0.60` from display; dim
  `≤0.80` icons to 0.6 alpha; sort by priority then confidence; carry
  `confidence` through the whole chain to the icon.
- **Confidence floor.** `MIN_CONFIDENCE_THRESHOLD = 0.60`. `scoreConfidence`
  returns 0.95 only when BOTH title and artist substring-match; single-axis
  matches collapse to 0.50 and are filtered.
- **Playback routing.** Each resolver type routes to a specific mechanism
  (Spotify → Connect Web API, SoundCloud/direct/local → AVPlayer, Apple Music
  → MusicKit). Mirrors Android's `PlaybackRouter`.

iOS divergence from Android, called out explicitly: iOS has **no native
resolvers** — all resolution is `.axe`-only via the shared `PluginManager`
through one JavaScriptCore context. Background pre-resolution is therefore
**throttled** (concurrency cap) where Android can lean on native resolvers +
a separate bridge.

---

## Components

1. **`IosResolverCoordinator`** *(Kotlin, `iosMain`)* — iOS's
   `ResolverManager`-equivalent, `.axe`-only.
   `resolveSources(artist, title, album) → List<ResolvedSource>`: fans out
   `PluginManager.resolve(...)` across stream-capable active resolvers in
   parallel, parses each JSON result, then filters + ranks via shared scoring.
   Logic stays in Kotlin (project principle). Active-resolver filtering via a
   `getActiveResolvers` lambda wired from `SettingsStore` in `IosContainer`.

2. **`ResolverScoring.selectRanked`** *(shared, small addition)* — returns the
   floor-filtered, priority-then-confidence-sorted list. `selectBest` becomes
   `selectRanked(...).firstOrNull()`. The router needs the full ranking for
   availability-based fallback. Android benefits too (no behavior change to
   `selectBest`).

3. **`IosTrackResolverCache`** *(Swift or `iosMain`)* — keyed by track identity
   (artist+title+album, normalized). `resolveInBackground(tracks)` resolves a
   list via `IosResolverCoordinator`, **throttled** (small concurrency cap +
   the existing limiter pattern), caches `ResolvedSource` per track. Read by
   both the badge UI and `coordinator.playTrack`.

4. **`PlaybackRouter`** *(Swift)* — walks an already-ranked
   `[ResolvedSource]`, dispatches the first source whose engine is available:
   stream/soundcloud/local URL → `IosAVPlayer`; `appleMusicId` → MusicKit;
   `spotifyUri` → Spotify Connect. Engine-unavailable or transient failure →
   continue to next source. Never re-ranks by confidence.

5. **`QueuePlaybackCoordinator`** *(Swift, evolved)* — `playTrack` gains
   cache-read → on-the-fly resolve → route at the existing seam
   (`ContentView.swift` ~line 800). Owns the three engines; exposes unified
   engine-agnostic now-playing state. `MiniPlayer` + `NowPlayingView` observe
   it.

6. **UI wiring** — `PlaylistDetailView` is the reference screen: `.task` calls
   `resolveInBackground`; rows render resolver badges and become tappable →
   `coordinator.setQueue(tracks, startIndex)`.

---

## Data flow

**Tap → first sound**

1. `PlaylistDetailView` converts `[IosPlaylistTrack]` → `[Track]`
   (metadata-only) and calls `coordinator.setQueue(tracks, startIndex)`.
2. `QueueManager.setQueue` (shared) returns the play track, stores the rest as
   up-next. Coordinator calls `playTrack`.
3. `playTrack` reads `IosTrackResolverCache` first. Hit ⇒ use cached ranked
   sources. Miss ⇒ `await IosResolverCoordinator.resolveSources(...)`.
4. `resolveSources` fans out `PluginManager.resolve` across stream-capable
   active resolvers, parses, runs `selectRanked` → ranked list above floor.
5. `PlaybackRouter.play(ranked)` walks the list, plays the first
   engine-available source, sets `activeEngine`.
6. Coordinator publishes unified now-playing state; mini player + Now Playing
   react. Resolved source is written back onto the in-queue `Track`.

**Lazy advance (track ends or Next)**

- AVPlayer `onTrackEnded` → `coordinator.skipNext()` → `QueueManager.skipNext`
  returns next metadata track → `playTrack` (cache → resolve → route).
- Prev (>3s in) restarts current; else resolves the previous track.
- Background pre-resolution means advances are usually cache hits.

---

## Router pseudocode

```
PlaybackRouter.play(ranked: [ResolvedSource]) -> RouteResult
  for source in ranked {              // floor-filtered + priority-sorted already
    switch source.resolver {
      case soundcloud, localfiles, direct:
        avPlayer.load(source.url); return .played(.avPlayer)
      case applemusic:
        guard MusicAuthorization == .authorized else { continue }
        guard let id = source.appleMusicId else { continue }
        do { try musicKit.play(id); return .played(.musicKit) }
        catch { continue }
      case spotify:
        guard spotifyTokenPresent else { continue }
        guard let uri = source.spotifyUri else { continue }
        do { try spotify.play(uri); return .played(.spotify) }
        catch { continue }
    }
  }
  return .noPlayableSource            // resolved-but-not-playable; surface clearly
```

Fallback is **engine-availability only**, never confidence re-ranking. A 0.95
Apple Music source that can't play (no subscription) falls to the next *ranked*
source — it is not demoted below a lower-confidence one.

---

## Unified now-playing state

`QueuePlaybackCoordinator` exposes engine-agnostic `@Observable` state:
`currentTrack`, `isPlaying`, `currentTime`, `duration`, `activeEngine`.
AVPlayer feeds it via existing observers; MusicKit/Spotify feed title/artist
immediately and position best-effort. Transport controls
(`togglePlayPause` / `skipNext` / `skipPrevious`) dispatch to `activeEngine`.
`MiniPlayer` + `NowPlayingView` re-point at this unified state, rendering
identically regardless of engine.

---

## Badges (PlaylistDetailView)

Each row renders a resolver badge row, confidence-aware per the rules:
priority-then-confidence sort, `noMatch`/`<0.60` filtered out, `≤0.80` dimmed
to 0.6 alpha. `confidence` flows `ResolvedSource` → `IosTrackResolverCache` →
row → icon. The leftmost (highest-priority, then highest-confidence) badge is
the source that will actually play.

---

## Explicit v1 scope (YAGNI — documented, not silently dropped)

- **External-engine auto-advance** (MusicKit/Spotify emitting "ended" to us) →
  follow-up. AVPlayer auto-advances; external-engine tracks advance on manual
  Next for v1.
- **Badges on Search / Now Playing** screens → follow-up. PlaylistDetail is the
  reference wiring first, matching Android's incremental screen rollout.
- **Pre-resolve next track ~30s early** (Android's AM polling optimization) →
  follow-up. Background pre-resolution already covers the common case.
- Only shared change is `selectRanked`; everything else is `iosMain` / Swift.

---

## Files

- Shared: `shared/.../resolver/ResolverScoring.kt` (`selectRanked`).
- `iosMain`: `IosResolverCoordinator` (new), `IosContainer` (wire
  `getActiveResolvers`, expose `resolveSources`), optionally
  `IosTrackResolverCache`.
- Swift: `PlaybackRouter` (new), `QueuePlaybackCoordinator` (evolve
  `playTrack` + unified state + engines), `IosTrackResolverCache` (if Swift),
  `PlaylistDetailView` (badges + tap), `MiniPlayer` / `NowPlayingView`
  (observe unified state), resolver badge view.
