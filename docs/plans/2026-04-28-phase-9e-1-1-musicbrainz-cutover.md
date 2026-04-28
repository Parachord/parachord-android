# Phase 9E.1.1 — MusicBrainz Ktor Cutover

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Migrate the first native API consumer from Retrofit (`MusicBrainzApi`) to the Ktor client (`MusicBrainzClient`) that already exists in `:shared`. This is the canary cutover for Phase 9E — validates the migration pattern that 9E.1.2 through 9E.1.8 will replicate.

**Architecture:** Per `docs/plans/2026-04-25-phase-9e-http-architecture-design.md`. Atomic per-API cutover: parity-check Ktor client, write MockEngine tests, migrate consumer call sites, delete Retrofit interface + Koin binding. No transitional dual-implementation in main.

**Tech Stack:** Ktor 3.1.1, Koin DI, kotlinx-serialization, MockEngine for tests.

**Worktree:** `.worktrees/9e-1-1-musicbrainz-client` on branch `feature/9e-1-1-musicbrainz-client`. Branched from main at `2da326c` (Phase 9E.1.0 complete + plan archival).

---

## Pre-flight observations

Already inspected to inform the plan:

- `shared/src/commonMain/kotlin/com/parachord/shared/api/MusicBrainzClient.kt` (231 lines) — Ktor client with 6 methods: `searchRecordings`, `searchReleases`, `searchArtists`, `getRelease`, `browseReleaseGroups`, `getArtist`. Includes 20 `data class` models (`Mb*`).
- `app/src/main/java/com/parachord/android/data/api/MusicBrainzApi.kt` (227 lines) — Retrofit interface with the same 6 methods + same 20 models.
- Model names are byte-identical between the two files; only the package differs (`com.parachord.android.data.api.*` vs `com.parachord.shared.api.*`).
- Koin binding for the Ktor client already exists in `SharedModule.kt`: `single { MusicBrainzClient(get()) }`.
- 5 consumer files import the Retrofit `MusicBrainzApi` and/or its models: `MusicBrainzProvider.kt`, `WikipediaProvider.kt`, `CriticalDarlingsRepository.kt`, `FreshDropsRepository.kt`, `AndroidModule.kt`.
- The Retrofit Koin binding lives at `AndroidModule.kt:269-276`.

---

## Task 1: Add MockEngine tests for `MusicBrainzClient`

**Files:**
- Create: `shared/src/commonTest/kotlin/com/parachord/shared/api/MusicBrainzClientTest.kt`

The Ktor client predates 9E.1.0 and has no tests. Add a MockEngine-based test for each of its 6 methods, asserting the URL/path/query parameters and that JSON deserializes to the right `Mb*` shape. This validates parity with the Retrofit interface and protects against drift during cutover.

**TDD steps:**

1. Write the test file with one test per method (6 tests minimum). Stage canned JSON responses via MockEngine; assert request URL + query params and response model fields.
2. Run: `./gradlew :shared:testDebugUnitTest --tests "com.parachord.shared.api.MusicBrainzClientTest" -q`. Expected: all tests fail compilation initially because the test file doesn't exist; once written, they should PASS against the existing client without code changes (this is a parity-confirmation test, not a failing-then-passing TDD cycle).
3. Commit: `Add MockEngine tests for MusicBrainzClient (6 methods)`.

---

## Task 2: Migrate consumer call sites from `MusicBrainzApi` to `MusicBrainzClient`

**Files (4 consumers):**
- Modify: `app/src/main/java/com/parachord/android/data/metadata/MusicBrainzProvider.kt`
- Modify: `app/src/main/java/com/parachord/android/data/metadata/WikipediaProvider.kt`
- Modify: `app/src/main/java/com/parachord/android/data/repository/CriticalDarlingsRepository.kt`
- Modify: `app/src/main/java/com/parachord/android/data/repository/FreshDropsRepository.kt`

**For each consumer:**

1. Constructor parameter type: `musicBrainzApi: MusicBrainzApi` → `musicBrainzClient: MusicBrainzClient`. Rename the field accordingly.
2. Call sites: `musicBrainzApi.<method>(...)` → `musicBrainzClient.<method>(...)`. Method signatures match exactly.
3. Imports:
   - Replace `import com.parachord.android.data.api.MusicBrainzApi` with `import com.parachord.shared.api.MusicBrainzClient`.
   - Replace `import com.parachord.android.data.api.Mb*` with `import com.parachord.shared.api.Mb*` for any model type referenced directly (some consumers may not need this if they only use the client's return values without naming the types).
4. Verify no Retrofit-specific behavior is being relied on (e.g., `Response<T>` wrapper, Retrofit interceptors). If found, surface — Ktor returns the body directly, status checking happens via `expectSuccess = false` patterns.

After each file, run `./gradlew :app:assembleDebug -q` to confirm it still compiles. Commit each consumer migration as its own commit (small commits, easy to revert):

- `MusicBrainzProvider: migrate from MusicBrainzApi (Retrofit) to MusicBrainzClient (Ktor)`
- `WikipediaProvider: migrate from MusicBrainzApi to MusicBrainzClient`
- `CriticalDarlingsRepository: migrate from MusicBrainzApi to MusicBrainzClient`
- `FreshDropsRepository: migrate from MusicBrainzApi to MusicBrainzClient`

---

## Task 3: Delete `MusicBrainzApi` Retrofit interface and its Koin binding

**Files:**
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt` (delete lines 269-276 — the `single<MusicBrainzApi> { Retrofit.Builder()... }` binding)
- Delete: `app/src/main/java/com/parachord/android/data/api/MusicBrainzApi.kt`
- Modify: `AndroidModule.kt` import of `MusicBrainzApi` (line 25) — remove

**Verification:**

1. `grep -rn "MusicBrainzApi" app/src shared/src` — should return zero matches after the deletion.
2. `./gradlew :shared:testDebugUnitTest -q` — all tests pass (29 prior + 6 new MusicBrainz tests = 35).
3. `./gradlew :app:assembleDebug -q` — BUILD SUCCESSFUL.

Commit: `Delete MusicBrainzApi Retrofit interface and its Koin binding`.

---

## Task 4: Smoke test on device

Build + install + launch + verify MusicBrainz-driven flows still work.

```bash
./gradlew :app:installDebug
adb shell am force-stop com.parachord.android.debug
adb shell monkey -p com.parachord.android.debug -c android.intent.category.LAUNCHER 1
sleep 4
adb logcat -d -t 1000 | grep -iE "musicbrainz|mbid"
```

Expected: requests to `musicbrainz.org/ws/2/...` succeed (200s in Logcat). MBID enrichment + metadata cascade still work — search a track, open an artist page, watch artwork enrich.

---

## Final commit

```bash
git commit --allow-empty -m "Phase 9E.1.1 complete — MusicBrainz on Ktor

First Ktor client cutover. MockEngine tests added for all 6 methods.
4 consumers migrated (MusicBrainzProvider, WikipediaProvider,
CriticalDarlingsRepository, FreshDropsRepository). Retrofit interface
and its Koin binding deleted.

Validates the migration pattern for 9E.1.2-9E.1.8 to follow:
parity-check Ktor client → MockEngine tests → consumer migration
(one repository at a time) → delete Retrofit interface + binding.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Verification gates

1. **End of Task 1:** 6 MockEngine tests pass — confirms Ktor client parity with the Retrofit interface.
2. **End of Task 3:** `grep -rn "MusicBrainzApi"` returns zero. `./gradlew :app:assembleDebug` passes. 35 shared tests pass.
3. **End of Task 4:** App launches, MusicBrainz API calls succeed in Logcat. (Bake is short here because MusicBrainz has no auth, no caching, no edge-case behavior to surface — the most boring API in the migration is the right canary.)

---

## What unblocks after this phase

Phase 9E.1.2 (GeoLocation) — same pattern, even simpler (no auth, single consumer). After 9E.1.1 + 9E.1.2 land, the migration template is firmly established and 9E.1.3-9E.1.8 follow mechanically.
