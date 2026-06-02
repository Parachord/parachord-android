# Achordion Scrobbler-Plugin Bridge Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Wire the `.axe` plugin **playback-telemetry contract** on Android so JS plugins (canonically `achordion.axe`) receive now-playing + scrobble events and can resolve MBIDs + identify the client — mirroring desktop's `scrobbleManager` contract.

**Architecture:** Three JS↔native surfaces in the headless WebView plugin runtime: (1) a `window.scrobbleManager` registry + `window.__scrobbleManagerDispatch(event, trackJson)` fanout that the native `ScrobbleManager` fires on every now-playing/scrobble; (2) a `window.__parachordClient = "android"` global so plugins can stamp `X-Parachord-Client` on their fetches (consumer = parachord-plugins#3); (3) a `resolveMbidForLove` JS→native callback so plugins can get a recording MBID for Achordion's recording-keyed submit. Native scrobbling (LB/Last.fm/Libre.fm) is unchanged and runs in parallel — this is purely additive plugin dispatch.

**Tech Stack:** Kotlin, kotlinx.serialization (JSON build), Android WebView JS bridge (`JsBridge`/`NativeBridge`), Koin DI, KMP shared module (`MbidEnrichmentService`), JUnit/Robolectric/MockK.

**This is a RE-IMPLEMENTATION, not a resume.** A stale WIP exists (branch `feature/achordion-plugin-bridge`, May 1, 48 commits behind). It is **post-KMP** (all touched files still exist at the same paths) and **additive** (`+423 / −6`), but main has since drifted on the same files (ScrobbleManager 174, bootstrap.html 131, NativeBridge 77 changed lines). **Reference the captured WIP at `docs/plans/2026-06-02-achordion-bridge-REFERENCE-wip.diff` for the exact original code**, but re-apply each piece onto current `main` and reconcile at the seams — do not cherry-pick the stale branch.

**Dependency / verification gate:** End-to-end header verification is BLOCKED on `parachord-plugins#3` (OPEN — the `achordion.axe` change to read `window.__parachordClient` and send `X-Parachord-Client`). The Android bridge + dispatch can be fully built and unit-tested independently; on-device e2e (achordion actually submitting client-stamped links) waits on that plugin shipping. Tracking issue for this Android work: `parachord-android#126`.

---

## Task 0: Fresh worktree off current main

**Files:** none (setup)

**Step 1:** From the main repo, create a clean worktree off the latest `main` (NOT the stale branch):
```bash
cd /Users/jherskowitz/Development/parachord/parachord-android
git fetch origin main
git worktree add .worktrees/achordion-bridge-v2 -b feature/achordion-bridge-v2 origin/main
```

**Step 2:** Confirm the reference diff is reachable from the worktree:
```bash
ls .worktrees/achordion-bridge-v2/docs/plans/2026-06-02-achordion-bridge-REFERENCE-wip.diff
```
Expected: file exists (it's committed on main).

**Step 3:** Per CLAUDE.md worktree rules — every gradle command in subsequent tasks MUST `cd` into `.worktrees/achordion-bridge-v2` first. Verify warning paths show `.worktrees/achordion-bridge-v2/` on each build.

---

## Task 1: `MbidEnrichmentService.getRecordingMbid` (shared)

The plugin bridge's `resolveMbidForLove` needs a public MBID-lookup that returns just the recording MBID (Achordion submit is recording-keyed). Reference diff: `MbidEnrichmentService.kt` hunk.

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/metadata/MbidEnrichmentService.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/metadata/MbidEnrichmentServiceTest.kt` (create if absent; else app-side test)

**Step 1 — Write the failing test:**
```kotlin
@Test fun `getRecordingMbid returns mapper recording mbid and caches`() = runTest {
    var calls = 0
    val svc = MbidEnrichmentService(
        mapperLookup = { _, _ -> calls++; MbidMapperResult(recordingMbid = "rec-mbid-1", artistCreditName = "A", recordingName = "T") },
        cacheRead = { null }, cacheWrite = {},
    )
    assertEquals("rec-mbid-1", svc.getRecordingMbid("Artist", "Title"))
}
```
(Match the actual `MbidEnrichmentService` constructor in current main — check its lambda params before writing.)

**Step 2 — Run, expect FAIL** (`getRecordingMbid` unresolved):
```bash
cd .worktrees/achordion-bridge-v2 && ./gradlew :shared:testDebugUnitTest --tests "*MbidEnrichmentServiceTest*"
```

**Step 3 — Implement** (from reference diff):
```kotlin
suspend fun getRecordingMbid(artistName: String, recordingName: String): String? {
    val key = cacheKey(artistName, recordingName)
    getCachedEntry(key)?.recordingMbid?.let { return it }
    val result = mapperLookup(artistName, recordingName) ?: return null
    // persist via existing cache path
    return result.recordingMbid
}
```
Reconcile with how `mapperLookup`/cache are shaped on current main (it's been public since the LB-sync work — `mapperLookup` was promoted to public).

**Step 4 — Run, expect PASS.**

**Step 5 — Commit:** `git commit -m "mbid: public getRecordingMbid for the plugin love-resolve bridge"`

---

## Task 2: JS contract in `bootstrap.html`

Add the three globals. No JS unit-test harness exists in this project, so this is verified via the Kotlin integration (Task 5/6) + on-device. Keep the block IDENTICAL to desktop semantics.

**Files:**
- Modify: `app/src/main/assets/js/bootstrap.html` (insert after the `storage` shim, ~line 93 on the stale branch — find the equivalent seam on current main)

**Step 1 — Add** (verbatim from reference diff `bootstrap.html` hunk):
- `window.__parachordClient = 'android';`
- `window.scrobbleManager = { _plugins: [], registerPlugin(plugin){…}, unregisterPlugin(id){…} }`
- `window.__scrobbleManagerDispatch = async function(eventName, trackJson) {…}` — parses JSON, applies the **<30s duration filter** (desktop parity), iterates `_plugins`, `await isEnabled()`, calls `plugin[eventName](track)`, per-plugin try/catch → `console.warn`, never breaks the loop.

**Reconciliation note:** main changed ~131 lines of bootstrap.html since the branch point. The additions are net-new blocks — place them after the existing `window.storage`/global shims and before plugin init runs. Confirm `__parachordClient` is set BEFORE the plugin-init evaluate.

**Step 2 — Verify it parses:** load via the JS bridge smoke (Task 6) — no standalone test.

**Step 3 — Commit:** `git commit -m "bridge: scrobbleManager registry + __parachordClient + dispatch entry point"`

---

## Task 3: `buildPluginTrackJson` in `ScrobbleManager`

The desktop-shaped track JSON (with the `sources` map carrying per-resolver confidence + IDs) is the payload plugins read. This is the most testable native piece — pure JSON construction.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/playback/ScrobbleManager.kt`
- Test: `app/src/test/java/com/parachord/android/playback/ScrobbleManagerPluginJsonTest.kt` (create)

**Step 1 — Failing test:** assert the JSON has `id/title/artist/album`, `duration` in **seconds** (`durationMs/1000.0`), `mbid`, `_activeResolver`, and a `sources` object keyed by resolverId with `confidence` + `spotifyId`/`appleMusicId` where present, and `noMatch:true` for non-matches. Build a `TrackEntity` + a `List<ResolvedSource>` and assert the serialized shape.

**Step 2 — Run, expect FAIL.**

**Step 3 — Implement** `private fun buildPluginTrackJson(track, cachedSources): JsonObject` (from reference diff) using `buildJsonObject { put(...); putJsonObject("sources"){…} }`. Include `buildFallbackSources(track)` for the no-cache case (single-entry sources from the track's own resolver/IDs).

**Step 4 — Run, expect PASS.**

**Step 5 — Commit:** `git commit -m "scrobble: build desktop-shaped plugin track JSON with sources map"`

---

## Task 4: `dispatchToJsPlugins` + wiring into now-playing/scrobble

**Files:**
- Modify: `app/src/main/java/com/parachord/android/playback/ScrobbleManager.kt` (constructor gains `jsBridge: JsBridge`, `trackResolverCache: TrackResolverCache`)
- Test: `app/src/test/java/com/parachord/android/playback/ScrobbleManagerDispatchTest.kt` (create)

**Step 1 — Failing test (MockK):** verify that on a now-playing event, `jsBridge.evaluate(match { it.contains("__scrobbleManagerDispatch('updateNowPlaying'") })` is called exactly once; and on scrobble, with `'scrobble'`. Verify the payload is base64-wrapped via `atob('…')` (CLAUDE.md Common Mistake #26 — base64, not template literals). Verify a `duration < 30s` track does NOT… (note: the <30s filter is JS-side per desktop; assert native still dispatches and the JS filter is the gate — OR mirror it natively if the reference diff does. Match the reference.)

**Step 2 — Run, expect FAIL.**

**Step 3 — Implement** `private fun dispatchToJsPlugins(eventName, track)`: `buildPluginTrackJson` → JSON string → base64 → `jsBridge.evaluate("window.__scrobbleManagerDispatch && window.__scrobbleManagerDispatch('$safeEvent', atob('$base64'))")`, wrapped in try/catch → `Log.w`. Call it (fire-and-forget) from the existing `updateNowPlaying` and `scrobble` paths AFTER the native dispatch, pulling `cachedSources` from `trackResolverCache`.

**Step 4 — Run, expect PASS + full ScrobbleManager suite green.**

**Step 5 — Commit:** `git commit -m "scrobble: fire-and-forget dispatch to JS scrobbleManager plugins"`

---

## Task 5: `NativeBridge.resolveMbidForLove` (JS→native MBID callback)

**Files:**
- Modify: `app/src/main/java/com/parachord/android/bridge/NativeBridge.kt` (constructor gains `mbidEnrichment: MbidEnrichmentService`)
- Test: `app/src/test/java/com/parachord/android/bridge/NativeBridgeMbidTest.kt` (create)

**Step 1 — Failing test:** given a `trackJson` with title+artist and no mbid, `resolveMbidForLove(callbackId, json)` resolves via `mbidEnrichment.getRecordingMbid(...)` and injects the result back to JS via the callback. If `mbid` already present + matches `MBID_PATTERN`, short-circuit (return it without a lookup). Use a relaxed `JsBridge` mock + verify the callback evaluate.

**Step 2 — Run, expect FAIL.**

**Step 3 — Implement** (reference diff): parse `trackJson`, honor existing valid `mbid` (`MBID_PATTERN`), else `getRecordingMbid(artist, title)`, then evaluate the JS callback with the result. Async on `scope`.

**Step 4 — Run, expect PASS.**

**Step 5 — Commit:** `git commit -m "bridge: resolveMbidForLove JS→native callback for recording-keyed submit"`

---

## Task 6: DI wiring + PluginManager registration

**Files:**
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt` — inject `JsBridge` + `TrackResolverCache` into `ScrobbleManager`; `MbidEnrichmentService` into `NativeBridge`/`JsBridge` (per reference diff `JsBridge.kt`: `NativeBridge(httpClient, scope, dataStore, mbidEnrichment)`)
- Modify: `shared/.../plugin/PluginManager.kt` (+ the `jsRuntime.evaluate(...)` registration hook from reference diff, if it injects the client/registry at load)
- Modify: `app/.../bridge/JsBridge.kt` (constructor passes `mbidEnrichment` into `NativeBridge`; register `resolveMbidForLove` as a `@JavascriptInterface` if that's how NativeBridge exposes methods — match current main's bridge-exposure pattern)

**Step 1:** Wire the Koin singletons. Watch for circular deps (`ScrobbleManager` ↔ `JsBridge` ↔ `PluginManager`) — the reference resolved these; mirror its construction order.

**Step 2 — Build:** `cd .worktrees/achordion-bridge-v2 && ./gradlew :app:compileDebugKotlin` → expect SUCCESS.

**Step 3 — Run full unit suite:** `./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest` → all green.

**Step 4 — Commit:** `git commit -m "di: wire scrobbler-plugin bridge (JsBridge, TrackResolverCache, MbidEnrichment)"`

---

## Task 7: On-device smoke (partial — full e2e gated on parachord-plugins#3)

**Files:** none

**Step 1 — Install:** `cd .worktrees/achordion-bridge-v2 && ./gradlew installDebug`; force-stop.

**Step 2 — Verify dispatch fires (independent of the plugin consumer):** play a track (>30s), `adb logcat | grep -E "scrobbleManager|__scrobbleManagerDispatch|dispatchToJsPlugins"`. Expected: the JS `console.log('[scrobbleManager] registered plugin: achordion')` at plugin load, and dispatch logs on now-playing/scrobble. No crashes, no ANR (dispatch is fire-and-forget per CLAUDE.md Apple Music polling rules — confirm it doesn't block).

**Step 3 — Confirm `__parachordClient`:** `adb logcat` for any plugin error; `window.__parachordClient` is `'android'`.

**Step 4 — Full e2e (DEFERRED):** once `parachord-plugins#3` ships the achordion change, verify a played track produces an Achordion `track-links/submit` with `X-Parachord-Client: android`. Document as the closing acceptance check; do NOT block this plan's merge on it.

**Step 5 — Update CLAUDE.md:** add a short subsection under the .axe section describing the scrobbler-plugin bridge contract (registry, dispatch, `__parachordClient`, `resolveMbidForLove`), noting the <30s filter + desktop parity + fire-and-forget rule.

**Step 6 — Commit + finish:** `git commit -m "docs: scrobbler-plugin bridge contract in CLAUDE.md"`; then use superpowers:finishing-a-development-branch.

---

## Risks / notes

- **Drift reconciliation** (main vs WIP on ScrobbleManager.kt + bootstrap.html) is the main risk. Both are largely additive — place new blocks at the current seams; the only `−6` lines in the WIP are where it touched existing code, so identify those 6 spots in the reference diff and re-apply carefully.
- **Don't block the playback path.** All JS dispatch is fire-and-forget `jsBridge.evaluate` (no `withContext` on the result) — CLAUDE.md Apple Music polling + async-IIFE rules (#26/#27) apply: base64-encode payloads, don't read the Promise return.
- **No regression to native scrobbling.** LB/Last.fm/Libre.fm native scrobblers stay exactly as-is; plugin dispatch is parallel + additive.
- **Stale worktree cleanup:** after this lands, remove `.worktrees/achordion-plugin-bridge` and delete the dead `feature/achordion-plugin-bridge` branch.
