# iOS Playback — Architecture Design

> **Status:** DESIGN. Brainstormed and validated section-by-section. Awaiting expansion into a TDD-shaped execution plan via `superpowers:writing-plans` once Phase 9A foundation work lands.
>
> **For Claude:** This is the architectural spec for porting Parachord playback to iOS as part of the KMP migration. The implementation plan should reference this doc as its source of truth and follow the phasing in the final section.

**Goal:** Ship iOS playback as part of the existing KMP shared-module strategy, without duplicating the orchestration logic that took ~30 documented bug fixes to get right on Android. iOS is the second platform target; the architecture must hold for a third (e.g. desktop-Compose-Multiplatform) without further refactoring.

**MVP scope:** Local files + Apple Music. Both flow through Apple-native APIs (`AVPlayer` and native `MusicKit` framework respectively), share a single `AVAudioSession`, and feed the same `MPNowPlayingInfoCenter`. Spotify Connect and SoundCloud are deferred to a follow-up workstream.

**Tech stack (iOS):** Swift, SwiftUI (or Compose Multiplatform — punted), `AVFoundation` (`AVPlayer`, `AVAudioSession`), native `MusicKit` framework (`ApplicationMusicPlayer.shared`), `MediaPlayer.framework` (`MPNowPlayingInfoCenter`, `MPRemoteCommandCenter`). The `:shared` KMP module compiled as an XCFramework consumed via Swift interop.

---

## Architectural principle: orchestration moves shared, platform writes only the adapter

Phase 7 of the KMP migration plan defined a `PlaybackEngine` interface in `commonMain` but punted: Android's actual implementation lives entirely in `:app`. For iOS we cannot afford that same punt — duplicating the orchestration layer (queue advance, source selection, cross-engine transitions, pre-resolution timing) means duplicating the bug surface. CLAUDE.md's "Common Mistakes" list documents 19 items just for the Android playback layer; we are not relitigating those on iOS.

The principle: **the orchestrator is shared Kotlin; only the platform adapter is per-platform.** This unblocks Phase 9A (TrackEntity → Track) and 9C (QueueManager → shared) as part of the iOS bootstrap, which is fine — those phases were already on the roadmap.

### What lives where

**`shared/commonMain` owns (orchestration + decision logic):**
- `QueueManager` + `QueuePersistence` — pure logic, Phase 9C.
- `PlaybackOrchestrator` — extracted from `PlaybackController`. Owns: which track plays next, when to pre-resolve, cross-engine transition choreography, repeat/shuffle, pending actions, position-based advance triggers.
- Decision-only `PlaybackRouter` — given a `ResolvedSource`, return the right `PlaybackEngine`. Imperative bits stay in `:app`.
- Source selection via existing `ResolverScoring` + `ResolverManager` (already shared).

**`shared/commonMain` defines (interfaces — `expect/actual` or plain Kotlin interfaces):**
- `PlaybackEngine` — what a per-source player must do.
- `NowPlayingPresenter` — what a lock-screen / Android Auto / CarPlay surface must accept.
- `AudioSession` — activation, interruption events, route changes.
- `RemoteCommandSource` — incoming play/pause/skip from lock screen, Bluetooth, CarPlay, Android Auto.

**iOS platform adapter (`iosApp/`, Swift) implements:**
- `AVPlayerEngine` — local files, SoundCloud streams, direct streams.
- `MusicKitEngine` — `ApplicationMusicPlayer.shared` wrapper.
- `IOSAudioSession` — `AVAudioSession.sharedInstance()` lifecycle.
- `IOSNowPlayingPresenter` — `MPNowPlayingInfoCenter` + queue mirroring for CarPlay.
- `IOSRemoteCommandSource` — `MPRemoteCommandCenter` registration.
- `IOSPlaybackHost` — top-level wiring; replaces `PlaybackService` (no foreground service needed).

**Android adapter (`:app`, refactored) implements the same interfaces:**
- `ExoPlayerEngine`, `MusicKitWebEngine`, `SpotifyConnectEngine`, `SoundCloudEngine`.
- `AndroidAudioSession`, `AndroidNowPlayingPresenter` (MediaSession-backed). `MiniPlayerWidgetProvider` becomes a *second* `NowPlayingPresenter` registration.
- `PlaybackService` shrinks to a thin foreground-service shell.

**Critical design property:** the orchestrator never imports a concrete engine. It holds `Map<ResolverType, PlaybackEngine>` and dispatches by resolver. Adding a new resolver = registering a new engine. Cross-handler transitions (the source of CLAUDE.md mistakes #12 and #28) become a single shared code path.

---

## `PlaybackEngine` — interface shape

```kotlin
interface PlaybackEngine {
    val id: ResolverType                       // "applemusic", "spotify", "localfiles", ...
    val state: StateFlow<EngineState>          // Loading | Playing | Paused | Ended | Error
    val position: StateFlow<PositionSnapshot>  // (positionMs, durationMs, timestamp)
    val requiresLocalAudioSession: Boolean     // false for external engines (Spotify Connect, Android MusicKit-WebView)

    suspend fun load(source: ResolvedSource): Result<Unit>
    suspend fun play(): Result<Unit>
    suspend fun pause(): Result<Unit>
    suspend fun seek(positionMs: Long): Result<Unit>
    suspend fun stop(): Result<Unit>           // release decoders, drop session
    fun setVolume(linearGain: Float)           // 0..1, post-resolver-offset
}
```

### Shape decisions and rationale

1. **`state` and `position` are separate `StateFlow`s.** Conflating them is the source of CLAUDE.md mistake #17 ("stale poll data"). Position is fast-moving and unreliable; state is event-driven and authoritative. The orchestrator trusts state; treats position as advisory.

2. **No `playUri(uri)` convenience method.** Forces callers through `load() → play()`. This makes cross-engine transition explicit (we always observe `Loading → Playing`, can detect failures) and gives a single seam for resolver-pipeline integration.

3. **`load(ResolvedSource)`, not `load(Track)`.** The orchestrator does resolver work *before* calling the engine. The engine never thinks about resolution.

4. **`Result<Unit>` for suspend ops.** Engines don't throw across the boundary. Errors flow back as `Result.failure(EngineError)`. The orchestrator decides retry vs. fall-through vs. surface.

5. **`stop()` is explicit and required.** CLAUDE.md mistake #13 was calling `ctrl.stop()` mid-transition. Putting `stop()` in the interface forces every implementation to define what stop means; orchestrator only calls it on cross-engine transitions, never same-engine.

6. **No event callbacks (`onTrackEnded`).** End-of-track is a state transition (`state.value == Ended`). Orchestrator subscribes once and reacts; no risk of double-fire (CLAUDE.md mistake #10).

7. **No queue inside the engine.** Even though `AVQueuePlayer` and `ApplicationMusicPlayer` both have native queues, we don't expose them. Queue is shared logic. Engines are single-track. We sacrifice native gapless from `AVQueuePlayer` for cross-platform consistency; recoverable later via an optional `prepare(nextSource)` hook.

### Side benefit of (7): synthetic queue is the queue

Android Auto integration is currently "minimal Now Playing remote control" (commit `d488d10`) and doesn't expose the queue. With engines single-track, `QueueManager.currentQueue` *is* the canonical queue, and `AndroidNowPlayingPresenter` translates it to `MediaSession.setQueue(...)` — Android Auto's "Up Next" works through the same shared code path that drives CarPlay's `CPListTemplate`. External-playback Spotify Connect (where ExoPlayer is idle and has no engine queue at all) gets queue exposure for free. This is queue exposure that wasn't possible with native engine queues.

The cost — losing native gapless — is *audio quality between tracks*, not a UX-surface concern. Recoverable post-MVP via an optional `PlaybackEngine.prepare(nextSource)` hook called by the orchestrator ~5s before end-of-track.

---

## Cross-engine transitions

Four transition cases at MVP (`AVPlayerEngine` + `MusicKitEngine` only):

| Outgoing → Incoming | Action |
|---|---|
| None → AVPlayer (first play) | `audioSession.activate()` → `engine.load()` → `engine.play()` |
| None → MusicKit (first play) | `audioSession.activate()` → `engine.load()` → `engine.play()` |
| AVPlayer → AVPlayer (same engine) | `engine.load(new)` → `engine.play()`. **No `stop()`.** |
| MusicKit → MusicKit (same engine) | `engine.load(new)` → `engine.play()`. **No `stop()`.** |
| AVPlayer → MusicKit (cross-engine) | `oldEngine.stop()` → `newEngine.load()` → `newEngine.play()` |
| MusicKit → AVPlayer (cross-engine) | `oldEngine.stop()` → `newEngine.load()` → `newEngine.play()` |

### Orchestrator transition algorithm

```kotlin
private val transitionMutex = Mutex()
private var inflightTransition: Job? = null

suspend fun transitionTo(source: ResolvedSource) {
    inflightTransition?.cancelAndJoin()  // Coalesce rapid skips
    inflightTransition = scope.launch {
        transitionMutex.withLock {
            val target = engines[source.resolverType] ?: error("no engine for ${source.resolverType}")
            val current = activeEngine

            if (current != null && current !== target) current.stop()
            // Same-engine: no stop. Avoids CLAUDE.md mistake #13 class.

            target.load(source).onFailure { return@withLock fallThrough(source) }
            target.play().onFailure { return@withLock fallThrough(source) }
            activeEngine = target
            presenter.onTrackChanged(source.track, queue.snapshot())
        }
    }
}
```

### Five invariants this enforces

1. **One active engine at a time.** Cross-engine always stops outgoing before loading incoming. Same-engine never stops.
2. **Single-flight transitions.** A second skip during an in-progress transition cancels the first cleanly via `cancelAndJoin`. Avoids CLAUDE.md mistake #29 ("CancellationException swallowed in catch").
3. **Audio session activation is sticky.** Activated on first play of a session; deactivated only on explicit user stop or app-backgrounded-with-no-playback. Each transition does not toggle the session — that would flicker AirPods routing on iOS.
4. **Failed load falls through to next-best source.** Same `ResolvedSource` list from `ResolverScoring`, skip the failed entry, try the next one. After all sources exhaust, call `advance()` to skip the track.
5. **Presenter update fires once per transition.** `onTrackChanged()` runs after the new engine confirms `Playing`. Lock-screen / Android Auto / CarPlay see a single clean update, not flicker.

### Pre-resolution hook

The orchestrator subscribes to the active engine's `position` flow and, when `durationMs - positionMs < 30_000`, fires a fire-and-forget `resolverManager.resolve(nextQueueTrack)` to populate `TrackResolverCache`. When end-of-track fires, the resolved sources are already cached. **Engines never know about pre-resolution.** This replaces Android's 30s pre-resolve dance for AM (CLAUDE.md "Pre-resolving next track") with a single shared code path.

### Edge case: all fallbacks failed

A 10s overall transition timeout in the mutex block. On timeout: deactivate session, surface error, advance queue.

### iOS-specific simplifications this design unlocks

- No equivalent of CLAUDE.md mistake #12 (`stopExternalPlayback` race) — `oldEngine.stop()` is awaited before new load.
- No mistake #13 (ExoPlayer demotion) — same-engine path skips `stop()`.
- No mistake #28 (audio focus competition) — single `IOSAudioSession` owner, no Chromium AudioFocusDelegate.
- AM→AVPlayer transition is one `MusicKit.stop()` + one `AVPlayer.replaceCurrentItem(...)`, both synchronous-ish on the audio thread. No 30ms race window.

---

## Now Playing + remote-command integration

Two interfaces in `commonMain`:

```kotlin
interface NowPlayingPresenter {
    fun onTrackChanged(track: Track, queue: QueueSnapshot)
    fun onStateChanged(state: PlaybackState)        // Playing | Paused | Buffering | Stopped
    fun onPositionTick(positionMs: Long, durationMs: Long)
    fun onArtworkResolved(artworkUrl: String?)      // late-binding from ImageEnrichmentService
    fun release()
}

interface RemoteCommandSource {
    val commands: SharedFlow<RemoteCommand>
    // Play, Pause, Toggle, SkipNext, SkipPrev, SeekTo(ms),
    // PlayQueueItem(id), ToggleLove, Stop
}
```

### Orchestrator wiring

```kotlin
init {
    scope.launch {
        remoteCommandSource.commands.collect { cmd ->
            when (cmd) {
                Play -> activeEngine?.play()
                Pause -> activeEngine?.pause()
                Toggle -> if (state == Playing) pause() else play()
                SkipNext -> advance()
                SkipPrev -> back()
                is SeekTo -> activeEngine?.seek(cmd.positionMs)
                is PlayQueueItem -> playFromQueue(cmd.id)
                ToggleLove -> libraryRepository.toggleLove(currentTrack)
                Stop -> stopAndDeactivateSession()
            }
        }
    }
}
```

### Position tick rate is platform-decided

`IOSNowPlayingPresenter` gets ticks from `AVPlayer.addPeriodicTimeObserver(forInterval: CMTime(seconds: 1))` for AVPlayer-driven playback, and from MusicKit's `playbackTime` for MusicKit-driven. The orchestrator subscribes to the *active engine's* `position` flow and forwards to the presenter — same single source per transition, no cross-engine flicker.

### Three design properties

1. **Multiple presenters supported, not exclusive.** Orchestrator holds `List<NowPlayingPresenter>` and broadcasts to all. iOS registers the lock-screen presenter; Android registers MediaSession + WidgetUpdater + (future) CarPlay. Adding a new surface is registration, not orchestrator changes.
2. **Artwork is late-binding, not blocking.** `onTrackChanged` fires immediately with `artworkUrl = null` if not resolved; `onArtworkResolved` updates once `ImageEnrichmentService` returns. Lock screen shows track text instantly, art fades in.
3. **`ToggleLove` is a remote command.** AirPods double-tap, Apple Watch favorite, Android Auto thumbs-up — all hit the same shared `LibraryRepository.toggleLove()`. No platform-specific love handling.

### iOS gotcha to bake into startup order

`MPRemoteCommandCenter` commands *must* be registered before the audio session activates the first time, or the lock screen shows no controls. `IOSRemoteCommandSource.init()` runs during `IOSPlaybackHost` startup, before the first `transitionTo()`.

---

## Audio session lifecycle (interruptions, route changes, focus)

Shared interface:

```kotlin
interface AudioSession {
    val state: StateFlow<SessionState>     // Inactive | Active | Interrupted
    val events: SharedFlow<SessionEvent>

    suspend fun activate()
    suspend fun deactivate()
}

sealed class SessionEvent {
    object InterruptionBegan : SessionEvent()           // call, Siri, alarm
    data class InterruptionEnded(val shouldResume: Boolean) : SessionEvent()
    object RouteBecameNoisy : SessionEvent()            // headphones yanked
    data class RouteChanged(val output: AudioRoute) : SessionEvent()  // AirPods connected, etc.
}
```

### Behavior contract (one place, both platforms)

| Event | Orchestrator action |
|---|---|
| `InterruptionBegan` | `activeEngine?.pause()`. Do **not** deactivate session. iOS expects to hand control back. |
| `InterruptionEnded(shouldResume=true)` | `activeEngine?.play()` — only if we *were* playing pre-interruption. |
| `InterruptionEnded(shouldResume=false)` | No-op. User stayed in Siri / went to call screen. |
| `RouteBecameNoisy` | `activeEngine?.pause()`. Universal user expectation: pull cans, music stops. |
| `RouteChanged` | Metadata only. Don't pause; AirPods just routed. |

### Cross-platform asymmetry

On Android, *external engines* (Spotify Connect, MusicKit-WebView) don't go through *our* process's audio focus — Spotify's process owns focus, our requesting it competes (CLAUDE.md mistake #28). On iOS this distinction collapses because MusicKit-native and AVPlayer both flow through *our* process's `AVAudioSession`. We model this with `PlaybackEngine.requiresLocalAudioSession: Boolean` — iOS engines all return `true`; Android's `SpotifyConnectEngine` and `MusicKitWebEngine` return `false` so the orchestrator skips session activation when those are active.

### iOS impl note

`Info.plist` gets `UIBackgroundModes: [audio]`. Without this, the session deactivates when the app backgrounds and `AVPlayer` stops within a couple of seconds. Bake into `IOSPlaybackHost` smoke-test.

### MVP simplifications

- No `mixWithOthers` / `duckOthers` options. Default `.playback` category interrupts other audio (matches user expectation for a music app). If we later want a "mix with podcast" mode, it's an `AudioSession.activate(options:)` parameter.
- No CarPlay-specific session config at MVP. CarPlay piggybacks on `.playback`.

---

## Resolver integration + queue advance

Three resolution moments in the orchestrator's life:

| Moment | Trigger | What runs |
|---|---|---|
| **Enqueue** | `addToQueue(tracks)` / `insertNext(tracks)` | `TrackResolverCache.resolveInBackground(tracks)` — fire-and-forget. Populates badges in UI, primes cache. |
| **Pre-resolve** | Position observer: `duration - position < 30_000` | `TrackResolverCache.resolveInBackground([nextTrack])` — fire-and-forget. |
| **On-demand** | `transitionTo(track)` finds no cached sources | `ResolverManager.resolve(track)` — awaits. Brief audible gap acceptable for ephemeral tracks. |

### The advance loop

```kotlin
private suspend fun advance() {
    val nextTrack = queue.nextOrNull() ?: return run { stopAndDeactivateSession() }

    val sources = trackResolverCache.getOrResolve(nextTrack) ?: emptyList()
    val active = settingsStore.getActiveResolvers()
    val best = resolverScoring.selectBest(sources, active)

    if (best == null) {
        queue.markUnplayable(nextTrack)
        return advance()
    }

    transitionTo(best)
}
```

### Five behavior rules

1. **The 0.60 confidence floor is enforced by `ResolverScoring.selectBest`** — already shared. If it returns null, the track is unplayable for the user's current settings; advance past it.
2. **Active-resolver filtering happens once, in `selectBest`.** Orchestrator doesn't re-filter.
3. **Fall-through within a track:** if `transitionTo(best)` fails to load, retry with `sources.minus(best).let { selectBest(it, active) }`. Three internal retries max before declaring the track unplayable and calling `advance()`.
4. **Pre-resolution and on-demand share the same cache.** `TrackResolverCache` is the single source of truth, deduplicates in-flight requests.
5. **Skip-track-on-failure has a hard limit.** If `advance()` recurses 5 times in a row, break out, surface "nothing in queue is playable" to UI. Avoids silent infinite-loop.

### Volume offset application

```kotlin
val gain = userVolumeLinear * 10.0.pow(resolverOffsetDb(source.resolverType) / 20.0)
activeEngine.setVolume(gain.toFloat().coerceIn(0f, 1f))
```

Offsets stay in shared (`ResolverVolumeOffsets`). Orchestrator computes; engine just sets a 0..1 gain. Note: MusicKit on iOS *does* respect `setVolume` (unlike WebView MusicKit on Android — CLAUDE.md "MusicKit v3 `music.volume` has no effect on Android WebView"). iOS Apple Music volume normalization actually works, fixing one of Android's known limitations.

### Hard prerequisite

Phase 9A (TrackEntity → Track) is the gating item. None of the above compiles in `commonMain` until the queue's element type is `Track`, not Android-only `TrackEntity`. Migration order: 9A first, then `QueueManager` to shared (Phase 9C), then orchestrator on top.

### MVP simplifications

- No "smart shuffle" — `QueueManager.nextOrNull()` follows current shuffle/repeat modes.
- No "play history" capture for retroactive scrobble correction — orchestrator emits scrobble events on track-confirmed-playing, `ScrobbleManager` (already shared) consumes.

### Refactor consequence

Existing `PlaybackController.playTrackInternal` and `resolveOnTheFly` paths on Android collapse into one orchestrator path. Behavior-preserving for Android — same calls, same cache, same rules — but a real refactor. Net effect: ~600 lines removed from `PlaybackController.kt`, replaced by ~200 lines in shared `PlaybackOrchestrator.kt`.

---

## Phasing, sequencing, and verification gates

| # | Phase | What ships | Effort | User impact |
|---|---|---|---|---|
| **0a** | **Phase 9A: TrackEntity → Track** | Shared `Track` model is the universal element type across queue, playback, scrobble, repos | 3–5 days | None — behavior-preserving Android refactor |
| **0b** | **Phase 9C: QueueManager → shared** | Pure queue logic in `commonMain`, JVM-runnable tests | 0.5 day | None |
| **A** | **Shared orchestration extraction** | `PlaybackEngine` / `NowPlayingPresenter` / `RemoteCommandSource` / `AudioSession` interfaces in commonMain. `PlaybackOrchestrator` extracted from `PlaybackController`. Android handlers refactored to implement the new interfaces. MediaSession wrapped as `AndroidNowPlayingPresenter`, widget becomes a 2nd presenter, focus+noisy wrapped as `AndroidAudioSession` | 5–7 days | None — Android still ships, identical behavior |
| **B** | **iOS project bootstrap** | `iosApp/` Xcode project. `:shared` produces XCFramework consumable from Swift. `IosJsRuntime` via JavaScriptCore replaces the stub (unblocks 17 .axe plugins as a side effect). Smoke-test: Swift calls `ResolverScoring.selectBest` and gets the right answer | 2–3 days | None — internal milestone |
| **C** | **iOS playback adapter (no audio yet)** | `IOSAudioSession`, `AVPlayerEngine`, `IOSNowPlayingPresenter`, `IOSRemoteCommandSource`, `IOSPlaybackHost`. `Info.plist` background audio mode. Minimal SwiftUI shell screen | 4–5 days | None — no UI for end users |
| **D** | **First audible note: local files** | Tap a local file, hear it play. Lock screen shows metadata + transport controls. Background audio survives screen-off. Pause-on-noisy-route works. Siri interruption auto-resumes. Scrubber works | 2–3 days | **TestFlight build #1** — internal only |
| **E** | **MusicKit integration** | `MusicAuthorization.request()` flow. `MusicKitEngine` via `ApplicationMusicPlayer.shared`. Cross-engine transitions verified (Local→AM→Local). Pre-resolution timing validated. End-of-track / queue advance through the resolver pipeline. Volume offsets actually work (unlike Android's WebView MusicKit) | 3–4 days | **TestFlight build #2** — feature-complete iOS MVP |
| **F** | **Hardening + Android regression** | "All fallbacks failed" 10s timeout path tested. 5-track skip limit tested. Rapid-skip cancellation. Interruption resumption matrix (call, Siri, alarm). Android Auto queue exposure verified (the synthetic-queue win). Full Android regression sweep — the Phase A refactor must not have broken anything | 3–5 days | Android users get the Android Auto queue improvement; iOS goes to wider TestFlight |

**Total realistic effort: ~22–32 focused days.**

### Three explicit verification gates

1. **End of Phase A.** Android must pass full regression on real device: library, search, playback (all 4 sources), background audio, lock-screen, widget, Android Auto, queue persistence, scrobble. If anything regresses, Phase A doesn't merge. This is the riskiest phase because it touches working production code.
2. **End of Phase D.** First iOS TestFlight. Verify the ten checkpoints from CLAUDE.md "External Playback Background Survival" *don't apply* on iOS (no foreground service to demote, no silent-WAV needed, no WebView lifecycle, no `LAYER_TYPE_HARDWARE`) — and confirm iOS-specific equivalents work (background mode, audio session interruption, route change).
3. **End of Phase E.** First iOS feature-complete TestFlight. Verify cross-engine transitions don't hit the iOS analogs of Android mistakes #12 / #13 / #28. These should *not exist* on iOS by design — verification confirms the architecture actually delivers that promise.

### Deliberately deferred (not MVP)

- **Spotify Connect on iOS** — Web API, no `SPTAppRemote`. Matches Android's April 2026 stance. CLAUDE.md's iOS port table predates that decision and should be updated when this lands.
- **SoundCloud on iOS** — mostly free since SoundCloud streams are AVPlayer-with-HTTPS-URL. Could fold into Phase C if cheap, otherwise post-MVP.
- **iOS UI beyond the minimal Now Playing screen** — Library, Search, Discover etc. depend on the Compose-Multiplatform-vs-SwiftUI decision punted in the bootstrap conversation.
- **Coil 3 upgrade** (KMP Phase 8) — for iOS artwork loading, can use SwiftUI `AsyncImage` standalone at MVP.
- **Android Spotify-Connect / WebView-MusicKit refactor through the new `PlaybackEngine` interface** — Phase A only refactors *one* representative engine path; the rest stay on the existing `PlaybackHandler` interface temporarily, with a parallel implementation. Tightening to a single shared interface is post-MVP cleanup.
- **Native gapless via `prepare(nextSource)`** — recoverable post-MVP if perceived as a gap.

### Risk callout

Phase A is the single highest-risk merge in this plan. It rewrites the orchestration layer of a working music player while preserving exact behavior. **Recommendation:** do Phase A on a worktree (`superpowers:using-git-worktrees`), smoke-test against an internal Android build for at least 48 hours under real-world use (commute, gym, screen-off) before merging to main. Bake-time matters more than test coverage for playback bugs — many of CLAUDE.md's mistakes were only caught after hours of real listening.

---

## Cross-platform sync parallel (out of scope here, noted for context)

Today's sync split mirrors today's playback split: ~261 lines of contract in `shared/commonMain/sync/` (`SyncProvider` interface + models), ~3,435 lines of orchestration in `app/.../sync/` (`SyncEngine.kt` alone is 2,134 lines). Same architectural debt, same "extract orchestration to shared" remediation. The iOS sync workstream is **a parallel design effort to this one**, with its own Phase A:

- Move `SyncEngine`, `SpotifySyncProvider`, `AppleMusicSyncProvider` orchestration to `shared/commonMain`.
- Hard prereq: Phase 9D (Room → SQLDelight actual) — `SyncEngine` reads DAOs constantly.
- Soft prereq: Phase 9E (Retrofit → Ktor actual) — providers' API calls must work cross-platform.
- iOS-specific: `LibrarySyncWorker` (Android WorkManager) becomes `IOSSyncScheduler` (`BGTaskScheduler` + `BGAppRefreshTaskRequest`).

The Phase 9D foundation work is a shared dependency between iOS playback (this doc) and iOS sync (separate workstream). Doing 9A → 9D before either workstream's Phase A unblocks both simultaneously.

---

## UX principle: platform-idiomatic surfaces, shared business logic

Independent of which UI framework we pick for iOS (Compose Multiplatform vs. SwiftUI vs. punted-until-Phase-D), the UX strategy is fixed: **iOS gets iOS conventions, Android gets Android conventions, the shared layer never dictates UI styling.**

The shared layer ends at the ViewModel boundary. `Track`, `Album`, `ResolvedSource`, `QueueSnapshot`, `PlaybackState`, every repository, every API client, every resolver — all shared. Above that line, each platform renders idiomatically.

### What this means concretely

**Branding stays unified** (cross-platform brand identity):
- Purple accent (`#7c3aed` light / `#a78bfa` dark) on both platforms.
- Hosted-XSPF chip is `🌐 Hosted` literal text + blue pill on both — the desktop-Android-iOS brand recognition rule from CLAUDE.md applies.
- Resolver-specific colors (Spotify green, YouTube red, Bandcamp cyan) match across platforms.
- Smart-link share URLs land on `go.parachord.com` on both.

**UX conventions diverge by platform**:

| Pattern | Android | iOS |
|---|---|---|
| Navigation back gesture | System back button + predictive back | Swipe-back-to-pop |
| Bottom sheets | `ModalBottomSheet` (Material 3) | `.presentationDetents` (iOS-native) |
| Long-press menus | `ModalBottomSheet` context menus (existing `AlbumContextMenu` / `ArtistContextMenu` / `TrackContextMenu`) | `UIContextMenu` with native haptics |
| Pull-to-refresh | Material indicator | iOS-style indicator |
| Search bar | Top app bar with embedded search | Searchable navigation bar |
| Iconography | Material Icons | SF Symbols |
| Typography | Roboto / Material type scale | System font / Dynamic Type |
| Switches / toggles | Material `Switch` | iOS `Toggle` style |

**Platform-exclusive features stay platform-exclusive** (different on purpose):

| Feature | Android | iOS |
|---|---|---|
| Home-screen widget | `MiniPlayerWidgetProvider` (existing) | WidgetKit widget |
| Lock-screen rich state | MediaSession + notification | Live Activity / Dynamic Island |
| Car integration | Android Auto media browser | CarPlay (`CPListTemplate`, `CPNowPlayingTemplate`) |
| Background work scheduling | WorkManager | `BGTaskScheduler` (`BGAppRefreshTaskRequest`) |
| Companion device | (none today) | Apple Watch app (post-MVP) |
| Voice integration | Google Assistant via MediaSession | Siri / Shortcuts app |

### Implication for the UI framework decision

Even Compose Multiplatform — the most aggressive UI-sharing option — explicitly endorses "share 80%, override 20%" rather than 100% identical UI. The decision between Compose Multiplatform and SwiftUI is therefore not *whether* per-platform tweaks happen, but *how much overhead the framework adds when they're needed*.

Compose Multiplatform requires writing custom composables for any iOS convention that Material 3 doesn't have out of the box (swipe-back, bottom-sheet detents, native search bars, SF Symbols integration). SwiftUI gets these for free by construction — at the cost of duplicating the screen layout itself.

**Recommendation deferred to Phase D verification.** Build the minimal Now Playing screen in SwiftUI for speed during Phase D. After it works, compare: how much SwiftUI code did the screen need? How much would Compose Multiplatform have saved? Decide based on data, not speculation. The Phase D Now Playing screen is small enough to rewrite in either direction if we change our minds.

### What stays in shared regardless of UI framework

- All ViewModel logic and `StateFlow` exposure (from Phase 5 of KMP migration, already shared).
- All repositories, API clients, resolver pipeline, scrobblers, AI services.
- `PlaybackOrchestrator` and the four interfaces it consumes.
- Track / Album / Artist / Playlist / Queue models.
- Settings keys, default values, preference logic (Phase 9B).
- Plugin loading, marketplace sync, .axe execution.

The platform-specific UI layer never reaches into shared business logic except through ViewModels. This is the enforcement boundary.

---

## Open follow-ups before execution

1. **iOS UI framework decision.** Compose Multiplatform vs. SwiftUI for screens beyond Now Playing. Punted in bootstrap conversation; decision can wait until after Phase D ships.
2. **`prepare()` hook for native gapless.** Specified as optional post-MVP. Add to `PlaybackEngine` interface when we're ready to recover gapless.
3. **CarPlay queue UI scope.** Phase F verifies queue exposure to `MPNowPlayingInfoCenter`; full `CPListTemplate` queue browsing UI is a separate workstream.
4. **`ToggleLove` UX consistency.** AirPods double-tap is configurable in iOS Settings (default is play/pause); we should verify the iOS Apple Music app's behavior and match it.
