# iOS Playback Loop Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (or executing-plans) to implement this plan task-by-task.

**Goal:** Tapping a track in a production iOS screen resolves through the shared
`.axe` pipeline and plays through the right engine (AVPlayer / MusicKit /
Spotify Connect), with the mini player + Now Playing reflecting it.

**Architecture:** Shared `ResolverScoring.selectRanked` ranks `.axe` resolve
results; an `iosMain` `IosResolverCoordinator` fans out `PluginManager` resolves
(awaiting via unique per-call result keys — JSC returns `[object Promise]` from a
bare async IIFE), caches them via `IosTrackResolverCache`, and a Swift
`PlaybackRouter` dispatches the first engine-available source. `QueuePlaybackCoordinator`
gains cache→resolve→route at its existing `playTrack` seam.

**Tech Stack:** Kotlin Multiplatform (`commonMain`/`iosMain`), JavaScriptCore,
SwiftUI `@Observable`, AVFoundation, MusicKit, kotlinx.serialization, kotlin.test.

**Design reference:** `docs/plans/2026-06-05-ios-playback-loop-design.md`

**Governing CLAUDE.md rules (the spec):** Resolver Pipeline Rules
(`resolveInBackground` on every tracklist screen; pass `targetTitle`+`targetArtist`;
active resolvers only; `selectBest` not `.first`); two-layer on-the-fly resolution;
Resolver Badge Display (filter `noMatch`/<0.60, dim ≤0.80, sort priority-then-confidence,
carry `confidence` through the chain); `MIN_CONFIDENCE_THRESHOLD = 0.60`.

**Build/verify note (worktree-free, on branch `ios-playback-loop`):**
- Shared unit tests: `./gradlew :shared:iosSimulatorArm64Test` (or
  `:shared:testDebugUnitTest` for JVM-runnable commonTest).
- iOS app build + simulator verify:
  `cd iosApp && xcodebuild -project Parachord.xcodeproj -scheme Parachord -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' -configuration Debug build`
  then `xcrun simctl install/launch` + screenshot, per the Phase 4.10 workflow.
- Per-iter: `@agents.md` (iosApp/AGENTS.md) — pbxproj needs 4 entries per new
  Swift file; use the Edit tool for the PBXBuildFile line; don't name a
  Swift-facing Kotlin property `description`.

---

## Task 1: `ResolverScoring.selectRanked` (shared)

Extract the floor-filter + priority-then-confidence sort into a list-returning
function; `selectBest` becomes its head. The router needs the full ranking for
availability-based fallback.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/resolver/ResolverScoring.kt:55-90`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/resolver/ResolverScoringTest.kt` (create)

**Step 1: Write the failing test**

```kotlin
package com.parachord.shared.resolver

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolverScoringTest {
    private fun scoring(order: List<String>, active: List<String> = emptyList()) =
        ResolverScoring(getResolverOrder = { order }, getActiveResolvers = { active })

    private fun src(resolver: String, conf: Double, title: String = "t", artist: String = "a") =
        ResolvedSource(url = "u://$resolver", sourceType = "stream", resolver = resolver,
            confidence = conf, matchedTitle = title, matchedArtist = artist)

    @Test fun selectRanked_filters_below_floor() = runTest {
        val s = scoring(listOf("spotify", "soundcloud"))
        val ranked = s.selectRanked(listOf(src("spotify", 0.50), src("soundcloud", 0.95)))
        assertEquals(1, ranked.size)
        assertEquals("soundcloud", ranked.first().resolver)
    }

    @Test fun selectRanked_sorts_priority_then_confidence() = runTest {
        val s = scoring(listOf("spotify", "soundcloud"))
        // soundcloud higher confidence but lower priority than spotify
        val ranked = s.selectRanked(listOf(src("soundcloud", 0.99), src("spotify", 0.70)))
        assertEquals(listOf("spotify", "soundcloud"), ranked.map { it.resolver })
    }

    @Test fun selectRanked_respects_active_filter() = runTest {
        val s = scoring(listOf("spotify", "soundcloud"), active = listOf("soundcloud"))
        val ranked = s.selectRanked(listOf(src("spotify", 0.95), src("soundcloud", 0.95)))
        assertEquals(listOf("soundcloud"), ranked.map { it.resolver })
    }

    @Test fun selectBest_is_head_of_selectRanked() = runTest {
        val s = scoring(listOf("spotify", "soundcloud"))
        val sources = listOf(src("soundcloud", 0.99), src("spotify", 0.70))
        assertEquals(s.selectRanked(sources).firstOrNull(), s.selectBest(sources))
    }

    @Test fun selectRanked_empty_for_no_sources() = runTest {
        assertTrue(scoring(listOf("spotify")).selectRanked(emptyList()).isEmpty())
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --tests "*ResolverScoringTest*"`
Expected: FAIL — `selectRanked` unresolved reference.

**Step 3: Implement `selectRanked`, refactor `selectBest`**

Replace the body of `selectBest` (lines ~55-90) with:

```kotlin
suspend fun selectRanked(
    sources: List<ResolvedSource>,
    preferredResolver: String? = null,
): List<ResolvedSource> {
    if (sources.isEmpty()) return emptyList()

    val resolverOrder = getResolverOrder()
    val activeResolvers = getActiveResolvers()

    return sources
        .filter { activeResolvers.isEmpty() || it.resolver in activeResolvers }
        .filter { (it.confidence ?: 0.0) >= MIN_CONFIDENCE_THRESHOLD }
        .map { source ->
            ScoredSource(
                source = source,
                priority = resolverOrder.indexOf(source.resolver)
                    .let { if (it == -1) resolverOrder.size else it },
                confidence = source.confidence ?: 0.0,
            )
        }
        .sortedWith(
            compareBy<ScoredSource> { scored ->
                if (preferredResolver != null && scored.source.resolver == preferredResolver) -1
                else scored.priority
            }.thenByDescending { it.confidence }
        )
        .map { it.source }
}

suspend fun selectBest(
    sources: List<ResolvedSource>,
    preferredResolver: String? = null,
): ResolvedSource? = selectRanked(sources, preferredResolver).firstOrNull()
```

(Removes the old single-source fast path — `selectRanked` handles size-1 via the
same filters, preserving the floor+active behavior. Keep the `ScoredSource`
private data class.)

**Step 4: Run tests** — `./gradlew :shared:testDebugUnitTest --tests "*ResolverScoringTest*"` → PASS.

**Step 5: Commit**
```bash
git add shared/src/commonMain/.../ResolverScoring.kt shared/src/commonTest/.../ResolverScoringTest.kt
git commit -m "shared: ResolverScoring.selectRanked (selectBest = ranked head)"
```

---

## Task 2: `IosResolverCoordinator` — awaiting fan-out resolve (iosMain)

iOS's `.axe`-only `ResolverManager`-equivalent. Resolves a track across
stream-capable active resolvers concurrently through the single JSC context using
**unique per-call result keys** (a shared `__lastPluginResult` global would clobber
across concurrent resolves), parses each JSON → `ResolvedSource`, ranks via
`selectRanked`.

**Files:**
- Create: `shared/src/iosMain/kotlin/com/parachord/shared/ios/IosResolverCoordinator.kt`
- Modify: `shared/src/iosMain/kotlin/com/parachord/shared/ios/IosContainer.kt`
  (wire `ResolverScoring`, expose `resolveSources`), and the
  `IosJsRuntime` may stay unchanged (coordinator builds scripts + polls via
  `jsRuntime.evaluate`).

**Design notes (no commonTest possible — JSC is iosMain; verify on simulator):**
- `STREAMING_RESOLVERS = listOf("spotify","applemusic","soundcloud","localfiles")`
  (CLAUDE.md `stream: true` set). Query `= STREAMING_RESOLVERS ∩ loaded plugin ids`.
  Active filtering happens inside `selectRanked` (don't double-filter, but DO skip
  non-active resolvers before resolving to avoid wasted JSC work:
  `active = settingsStore.getActiveResolvers(); if (active.isNotEmpty()) filter`).
- **Unique key resolve primitive** (concurrent-safe):

```kotlin
private suspend fun resolveOne(resolverId: String, artist: String, title: String, album: String?): ResolvedSource? {
    val key = "res_${callCounter.incrementAndGet()}"
    val a = artist.jsEsc(); val t = title.jsEsc()
    val alb = if (album != null) "'${album.jsEsc()}'" else "null"
    jsRuntime.evaluate("""
        (function(){
          window.__resolveResults = window.__resolveResults || {};
          window.__resolveResults['$key'] = 'pending';
          (async () => {
            try {
              var r = window.__resolverLoader.getResolver('$resolverId');
              if (!r || !r.resolve) { window.__resolveResults['$key'] = 'null'; return; }
              var result = await r.resolve('$a', '$t', $alb, r.config || {});
              window.__resolveResults['$key'] = result ? JSON.stringify(result) : 'null';
            } catch (e) {
              window.__resolveResults['$key'] = 'error:' + ((e && e.message) ? e.message : String(e));
            }
          })();
        })();
    """.trimIndent())
    repeat(50) {
        delay(100)
        val r = jsRuntime.evaluate("window.__resolveResults['$key']")
        if (r != null && r != "pending") {
            // free the slot so the map doesn't grow unbounded
            jsRuntime.evaluate("delete window.__resolveResults['$key']; null")
            if (r == "null" || r.startsWith("error:")) return null
            return runCatching { json.decodeFromString<ResolvedSource>(r) }.getOrNull()
                ?.copy(resolver = resolverId)   // ensure resolver id is set for scoring/routing
        }
    }
    return null
}
```

- `resolveSources(artist, title, album): List<ResolvedSource>` — `coroutineScope { ids.map { async { resolveOne(...) } }.awaitAll() }.filterNotNull()`,
  then `scoring.selectRanked(sources)`. The concurrent `async` calls interleave
  on the JSC run loop because each `delay` yields and each fetch callback fires
  independently (unique keys keep them isolated).
- `ResolverScoring` in `IosContainer`:
  `ResolverScoring(getResolverOrder = { settingsStore.getResolverOrder() }, getActiveResolvers = { settingsStore.getActiveResolvers() })`.
- Expose on `IosContainer`:
  `suspend fun resolveSources(artist: String, title: String, album: String?): List<ResolvedSource> = resolverCoordinator.resolveSources(artist, title, album)`.
  `ResolvedSource` bridges to Swift directly (it's `@Serializable` data class).
- Reuse the existing `jsEsc`/escape helper pattern from `pluginResolveAsync`;
  remove the now-superseded single-`__lastPluginResult` `pluginResolveAsync` OR
  keep it for the Dev card (leave it — Dev card still references it; out of scope
  to delete).

**Verify (simulator):** Temporarily add a Dev-card button calling
`resolveSources("Spoon","The Underdog", null)` and print the ranked resolver list
+ confidences. Confirm concurrent resolves return distinct results (no clobber).
Then **commit**:
```bash
git commit -m "ios: IosResolverCoordinator — concurrent .axe resolve + selectRanked"
```

---

## Task 3: `IosTrackResolverCache` — throttled background pre-resolution (Swift)

Two-layer rule: pre-resolve the visible list, cache results for badges + instant
tap-to-play. Throttle because all resolves share one JSC context.

**Files:**
- Create: `iosApp/Parachord/IosTrackResolverCache.swift` (+ 4 pbxproj entries — see `@agents.md`)

**Shape:**

```swift
import Shared

@MainActor @Observable
final class IosTrackResolverCache {
    static let shared = IosTrackResolverCache()
    private let container = IosContainer.companion.shared

    // key = normalized "artisttitlealbum"
    private(set) var cache: [String: [ResolvedSource]] = [:]
    private var inFlight: Set<String> = []

    func key(artist: String, title: String, album: String?) -> String {
        "\(artist.lowercased())\u{1}\(title.lowercased())\u{1}\((album ?? "").lowercased())"
    }

    func cached(artist: String, title: String, album: String?) -> [ResolvedSource]? {
        cache[key(artist: artist, title: title, album: album)]
    }

    /// Resolve a list in the background, capped concurrency, newest-first skip
    /// of already-cached/in-flight entries. Updates `cache` reactively so badge
    /// rows re-render as each track resolves.
    func resolveInBackground(_ tracks: [(artist: String, title: String, album: String?)]) {
        Task {
            // small concurrency cap — one JSC context
            let cap = 3
            await withTaskGroup(of: Void.self) { group in
                var running = 0
                for t in tracks {
                    let k = key(artist: t.artist, title: t.title, album: t.album)
                    if cache[k] != nil || inFlight.contains(k) { continue }
                    inFlight.insert(k)
                    if running >= cap { await group.next(); running -= 1 }
                    running += 1
                    group.addTask { @MainActor in
                        let ranked = (try? await self.container.resolveSources(
                            artist: t.artist, title: t.title, album: t.album)) ?? []
                        self.cache[k] = ranked
                        self.inFlight.remove(k)
                    }
                }
            }
        }
    }
}
```

**Verify:** unit-test the `key` normalization inline (or in the simulator).
Build green. **Commit:** `git commit -m "ios: IosTrackResolverCache — throttled background pre-resolution"`.

---

## Task 4: `PlaybackRouter` (Swift)

Walk an already-ranked `[ResolvedSource]`; play the first engine-available
source. Availability checks are cheap+sync. Failures/unavailable → next source.
No confidence re-ranking.

**Files:**
- Create: `iosApp/Parachord/PlaybackRouter.swift` (+ pbxproj)

**Shape:**

```swift
import Shared

enum PlaybackEngineKind { case avPlayer, musicKit, spotify }
enum RouteResult { case played(PlaybackEngineKind), noPlayableSource }

@MainActor
struct PlaybackRouter {
    let avPlayer: IosAVPlayer
    let musicKit: MusicKitEngine        // existing Dev-tab play(appleMusicId:) extracted
    let spotify: SpotifyConnectEngine   // existing Phase 4.8 shim extracted
    let spotifyTokenPresent: () -> Bool

    func play(ranked: [ResolvedSource], title: String, artist: String) async -> RouteResult {
        for s in ranked {
            switch s.resolver {
            case "soundcloud", "localfiles", "direct":
                avPlayer.load(url: s.url, title: title, artist: artist)
                return .played(.avPlayer)
            case "applemusic":
                guard MusicAuthorization.currentStatus == .authorized,
                      let id = s.appleMusicId else { continue }
                if await musicKit.play(appleMusicId: id) { return .played(.musicKit) }
            case "spotify":
                guard spotifyTokenPresent(), let uri = s.spotifyUri else { continue }
                if await spotify.play(uri: uri) { return .played(.spotify) }
            default:
                continue
            }
        }
        return .noPlayableSource
    }
}
```

- Extract the existing Dev-tab MusicKit `play(appleMusicId:)` (ContentView ~363)
  into a small `MusicKitEngine` returning `Bool` (success). Same for the Phase 4.8
  Spotify Connect play into `SpotifyConnectEngine`. Keep their Dev cards working
  by having the cards call the extracted engines.

**Verify (simulator):** with a hand-built ranked list, confirm AVPlayer path
plays and an unavailable-engine source falls through. **Commit.**

---

## Task 5: Evolve `QueuePlaybackCoordinator` — cache→resolve→route + unified state

**Files:**
- Modify: `iosApp/Parachord/ContentView.swift` (`QueuePlaybackCoordinator` ~720-834,
  `playTrack` ~798)

**Changes:**
- Add deps: `router: PlaybackRouter`, `resolverCache: IosTrackResolverCache`,
  `container = IosContainer.companion.shared`.
- Add unified state: `var activeEngine: PlaybackEngineKind = .avPlayer`.
  (`currentTrack/isPlaying/currentTime/duration` already exist via `player`; expose
  `isPlaying`/`currentTime`/`duration` as computed passthroughs that read `player`
  for AVPlayer and the engine for MusicKit/Spotify — for v1, position for external
  engines is best-effort/zero.)
- Rewrite `playTrack`:

```swift
private func playTrack(_ track: Track) {
    currentTrack = track
    Task { @MainActor in
        // 1. cache hit?
        var ranked = resolverCache.cached(artist: track.artist, title: track.title, album: track.album)
        // 2. already-resolved single source on the Track (e.g. Dev sample)?
        if ranked == nil, let url = track.sourceUrl {
            ranked = [ResolvedSource(url: url, sourceType: "direct",
                resolver: track.resolver ?? "direct", confidence: 0.95)]
        }
        // 3. on-the-fly fallback
        if ranked == nil || ranked!.isEmpty {
            ranked = (try? await container.resolveSources(
                artist: track.artist, title: track.title, album: track.album)) ?? []
        }
        let result = await router.play(ranked: ranked!, title: track.title, artist: track.artist)
        switch result {
        case .played(let kind):
            activeEngine = kind
            if kind == .avPlayer { startAVPlaybackWhenReady() }   // existing 40-poll ready loop
        case .noPlayableSource:
            // resolved-but-not-playable — skip ahead, surface a note
            skipNext()
        }
    }
}
```

- Transport: `togglePlayPause()/skipNext()/skipPrevious()` dispatch by
  `activeEngine` (AVPlayer uses existing; MusicKit/Spotify call their engines).
- `onTrackEnded` (AVPlayer) → `skipNext()` unchanged. External-engine
  auto-advance is a documented follow-up (manual Next works).

**Verify (simulator):** tap a track in the Dev queue (direct URLs still play via
the new path). **Commit.**

---

## Task 6: Wire `PlaylistDetailView` — pre-resolve, badges, tap-to-play

**Files:**
- Modify: `iosApp/Parachord/PlaylistDetailView.swift`
- Create: `iosApp/Parachord/ResolverBadgeRow.swift` (+ pbxproj)

**Changes:**
- `PlaylistDetailViewModel.load()` (after tracks load): call
  `IosTrackResolverCache.shared.resolveInBackground(tracks.map { ($0.artist, $0.title, $0.album) })`.
- Convert `IosPlaylistTrack` → shared `Track` in the VM:
  `Track(title:, artist:, album:, ...)` (metadata-only; resolver IDs nil).
- Row becomes a `Button { coordinator.setQueue(trackEntities, startIndex: index) }`.
  The coordinator is injected from `RootView` (shared instance).
- `ResolverBadgeRow(sources: [ResolvedSource])` per CLAUDE.md badge rules:
  - filter `source.noMatch == false && (source.confidence ?? 0) >= 0.60`
  - sort by resolver priority (from `container.settingsStore` order) then
    confidence desc
  - each icon `.opacity((source.confidence ?? 0) <= 0.80 ? 0.6 : 1.0)`
  - read badges from `IosTrackResolverCache.shared.cache[key]` so they appear as
    background resolution completes (observe the cache).

**Verify (simulator):** open a weekly playlist → badges appear as tracks resolve;
tap a track → it plays (or shows resolved-but-not-playable for AM/Spotify-only
matches without auth); next/prev advance through the list. **Commit.**

---

## Task 7: Unified now-playing in MiniPlayer / NowPlayingView + RootView wiring

**Files:**
- Modify: `iosApp/Parachord/RootView.swift` (`AppPlayback` → inject the evolved
  coordinator + router + cache; pass coordinator into Discover→PlaylistDetail),
  `NowPlayingView.swift`, `ContentView.swift` (MiniPlayer).

**Changes:**
- `AppPlayback` constructs: `IosAVPlayer` → `MusicKitEngine`/`SpotifyConnectEngine`
  → `PlaybackRouter` → `QueuePlaybackCoordinator(player:router:resolverCache:)`.
- `MiniPlayer` + `NowPlayingView` read `coordinator.currentTrack`,
  `coordinator.isPlaying`, transport via coordinator methods (engine-agnostic).
- Discover/PlaylistDetail get the coordinator via SwiftUI environment or explicit
  init param.

**Verify (simulator):** play from a playlist → mini player shows the track on every
tab; Now Playing transport works; AVPlayer track auto-advances. **Commit.**

---

## Task 8: End-to-end verification + final review

- Full build + install + launch on iPhone 17 Pro simulator.
- Walk the loop: Discover → weekly playlist → badges → tap → audio → next/prev.
- Confirm a SoundCloud/direct match plays via AVPlayer; an Apple-Music-only match
  (no subscription) reports resolved-but-not-playable and falls through.
- `./gradlew :shared:testDebugUnitTest --tests "*ResolverScoringTest*"` green.
- Update `iosApp/AGENTS.md` with any new gotchas discovered (unique-key JSC
  resolve, throttling).
- REQUIRED: superpowers:requesting-code-review, then
  superpowers:finishing-a-development-branch.
- **Commit** as `ios: Phase 5.5 — playback loop (resolve→score→route, 3 engines)`.

---

## Out of scope (follow-ups, documented not dropped)

- External-engine (MusicKit/Spotify) auto-advance via state polling.
- Resolver badges on Search / Now Playing screens.
- Pre-resolve next track ~30s before current ends (AM polling optimization).
- iOS local-files resolver (no MediaStore equivalent wired).
