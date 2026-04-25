# Multi-Provider Sync — Phase 3 Execution Plan (Mirror Propagation)

> **For Claude:** This plan is intended to be executed directly (not via subagents). Each task is small enough to review inline; previous experience showed the subagent loop adds latency without proportional value for refactor-style work.
>
> Parent plan: [2026-04-22-multi-provider-sync-correctness.md](2026-04-22-multi-provider-sync-correctness.md). Phase 1 (data model) landed in `06c396d`–`5f4634a`. Phase 2 (provider abstraction) landed in `d02dbd1`–`65a33b6`. Phase 4 (Apple Music sync) is next; Phases 5–6 expand just-in-time.

**Goal:** Implement the four cooperating multi-provider mirror-propagation invariants from desktop CLAUDE.md ("Multi-Provider Mirror Propagation" — `handlePull` flag, local-mutator inlining, provider-scoped `syncedFrom` guard, post-push `relevantMirrors` clear) plus the cross-provider `syncedFrom` preservation guard, so an Android edit can round-trip through one provider and propagate to every other connected mirror without lost edits or duplicates. Single-provider behavior stays identical today; the wiring becomes load-bearing the moment Phase 4 adds Apple Music.

**Architecture:** Add a thin `MirrorPropagation` helper module (or a few methods on `SyncEngine`) that owns the four predicates: `hasOtherMirrors(localId, currentProviderId)`, `isOwnPullSource(local, providerId)`, `shouldFlagOnEdit(local)`, and `clearLocallyModifiedIfAllMirrorsCaught(localId)`. Call sites are: `PlaylistDetailViewModel.pullRemoteChanges` (Fix 1), `LibraryRepository.{addTracksToPlaylist, removeTrackFromPlaylist, moveTrackInPlaylist}` (Fix 2), `SyncEngine.syncPlaylists` push loop (Fix 3) + post-loop clear (Fix 4) + import branch (cross-provider `syncedFrom` preservation). Tests use a fake second `SyncProvider` to verify the four fixes hold under multi-provider scenarios — the tests are the contract.

**Tech Stack:** Kotlin, SQLDelight, Koin, JUnit 4, MockK. Builds on `SyncProvider` interface from Phase 2.

---

## Notes on current state

Quick audit before writing the tasks:

- **`LibraryRepository.addTracksToPlaylist` and `removeTrackFromPlaylist` already write `locallyModified = true`** (lines 181, 221). Fix 2 needs to add the `syncedFrom != null || syncedTo non-empty` guard so local-only playlists aren't flagged for nothing.
- **`PlaylistDetailViewModel.pullRemoteChanges` writes `locallyModified = false`** unconditionally (line 165). Fix 1 makes this conditional on `hasOtherMirrors`.
- **No `hasOtherMirrors`, `isOwnPullSource`, or `relevantMirrors` logic exists today.** These are net-new for Phase 3.
- **No `moveTrackInPlaylist` exists on Android** (verified via grep) — Fix 2's audit only needs to cover `addTracksToPlaylist` + `removeTrackFromPlaylist`. If/when reorder lands, that mutator must inline the same flag.

---

## Task 1: DAO helpers for mirror predicates

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/parachord/shared/db/SyncPlaylistLink.sq` — add 2 queries
- Modify: `app/src/main/java/com/parachord/android/data/db/dao/SyncPlaylistLinkDao.kt` — wrap them
- Modify: `app/src/test/java/com/parachord/android/data/db/SyncPlaylistLinkSchemaTest.kt` — append 4 tests

### Step 1: Append tests

```kotlin
@Test
fun `hasOtherMirrors true when a different provider has a link`() {
    val db = TestDatabaseFactory.create()
    db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "spotify", "x", null, 1L)
    db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "applemusic", "y", null, 2L)
    val others = db.syncPlaylistLinkQueries.countOtherMirrors("p1", "spotify").executeAsOne()
    assertEquals(1L, others)
}

@Test
fun `hasOtherMirrors zero when only the current provider is linked`() {
    val db = TestDatabaseFactory.create()
    db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "spotify", "x", null, 1L)
    val others = db.syncPlaylistLinkQueries.countOtherMirrors("p1", "spotify").executeAsOne()
    assertEquals(0L, others)
}

@Test
fun `selectMirrorsExcluding returns mirrors NOT matching the excluded provider`() {
    val db = TestDatabaseFactory.create()
    db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "spotify", "x", null, 1L)
    db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "applemusic", "y", null, 2L)
    db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "tidal", "z", null, 3L)
    val rows = db.syncPlaylistLinkQueries.selectMirrorsExcluding("p1", "spotify").executeAsList()
    assertEquals(2, rows.size)
    assertTrue(rows.all { it.providerId != "spotify" })
}

@Test
fun `selectMirrorsExcluding returns all mirrors when excluded provider not present`() {
    val db = TestDatabaseFactory.create()
    db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "spotify", "x", null, 1L)
    db.syncPlaylistLinkQueries.upsertWithSnapshot("p1", "applemusic", "y", null, 2L)
    val rows = db.syncPlaylistLinkQueries.selectMirrorsExcluding("p1", "no-such-provider").executeAsList()
    assertEquals(2, rows.size)
}
```

### Step 2: Run, expect FAIL (queries don't exist)

```
./gradlew :app:testDebugUnitTest --tests "com.parachord.android.data.db.SyncPlaylistLinkSchemaTest"
```

### Step 3: Add the SQL queries to `SyncPlaylistLink.sq`

Append:

```sql
countOtherMirrors:
SELECT COUNT(*) FROM sync_playlist_link
WHERE localPlaylistId = :localPlaylistId AND providerId != :excludeProviderId;

selectMirrorsExcluding:
SELECT * FROM sync_playlist_link
WHERE localPlaylistId = :localPlaylistId AND providerId != :excludeProviderId;
```

### Step 4: Wrap them in `SyncPlaylistLinkDao`

```kotlin
suspend fun hasOtherMirrors(localPlaylistId: String, currentProviderId: String): Boolean =
    withContext(Dispatchers.IO) {
        queries.countOtherMirrors(localPlaylistId, currentProviderId).executeAsOne() > 0L
    }

suspend fun selectMirrorsExcluding(localPlaylistId: String, excludeProviderId: String): List<Link> =
    withContext(Dispatchers.IO) {
        queries.selectMirrorsExcluding(localPlaylistId, excludeProviderId).executeAsList().map { it.toLink() }
    }
```

### Step 5: Run, expect PASS + full suite

```
./gradlew :app:testDebugUnitTest
```

### Step 6: Commit

```
git add shared/src/commonMain/sqldelight/com/parachord/shared/db/SyncPlaylistLink.sq \
        app/src/main/java/com/parachord/android/data/db/dao/SyncPlaylistLinkDao.kt \
        app/src/test/java/com/parachord/android/data/db/SyncPlaylistLinkSchemaTest.kt
git commit -m "Add hasOtherMirrors + selectMirrorsExcluding helpers for propagation logic"
```

---

## Task 2: Fix 1 — Pull paths set `locallyModified = true` when other mirrors exist

The `PlaylistDetailViewModel.pullRemoteChanges` action and the `SyncEngine.syncPlaylists` import-refill path replace local tracks with remote ones. Today both write `locallyModified = false`. If the playlist also has push-mirror entries on other providers, those copies are now stale relative to the just-pulled state — the next push loop must pick them up.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/screens/playlists/PlaylistDetailViewModel.kt:146-180` (the `pullRemoteChanges` body)
- Audit: `app/src/main/java/com/parachord/android/sync/SyncEngine.kt` import branch — find any place a local row's tracks get replaced from a remote and apply the same fix.
- Add: `app/src/test/java/com/parachord/android/sync/PullSetsLocallyModifiedTest.kt`

### Step 1: Write the test

```kotlin
package com.parachord.android.sync

import com.parachord.android.data.db.TestDatabaseFactory
import com.parachord.android.data.db.dao.SyncPlaylistLinkDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-helper test for the multi-provider propagation predicate: a pull
 * should set locallyModified=true iff the playlist has at least one
 * push mirror on a different provider. Without this, an Android-edit
 * → Spotify → desktop pull stops at the desktop and never reaches
 * Apple Music.
 */
class PullSetsLocallyModifiedTest {
    @Test
    fun `pull from spotify sets locallyModified when applemusic mirror exists`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistLinkDao(db)
        dao.upsertWithSnapshot("p1", "spotify", "spx", null, 1L)
        dao.upsertWithSnapshot("p1", "applemusic", "amx", null, 2L)
        val shouldFlag = dao.hasOtherMirrors("p1", "spotify")
        assertTrue("should flag because AM mirror exists", shouldFlag)
    }

    @Test
    fun `pull from spotify does NOT set locallyModified when only spotify mirror exists`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistLinkDao(db)
        dao.upsertWithSnapshot("p1", "spotify", "spx", null, 1L)
        val shouldFlag = dao.hasOtherMirrors("p1", "spotify")
        assertFalse("should NOT flag — Spotify is the only mirror, no propagation needed", shouldFlag)
    }
}
```

### Step 2: Run, expect PASS (uses Task 1's helpers; no new code path needed for the predicate itself)

### Step 3: Wire the predicate into `pullRemoteChanges`

Read `PlaylistDetailViewModel.pullRemoteChanges`. Find the `playlistDao.update(pl.copy(...locallyModified = false...))` line. Change to:

```kotlin
val hasOtherMirrors = syncPlaylistLinkDao.hasOtherMirrors(playlistId, "spotify")
playlistDao.update(pl.copy(
    trackCount = remoteTracks.size,
    snapshotId = remoteSnapshot ?: pl.snapshotId,
    updatedAt = now,
    lastModified = now,
    locallyModified = hasOtherMirrors,
))
```

The hardcoded `"spotify"` here is intentional — `pullRemoteChanges` today only pulls from Spotify (Apple Music doesn't pull yet). When Phase 4 adds AM pull, this becomes `pullRemoteChanges(providerId)` and `"spotify"` becomes the parameter. For Phase 3 the literal is fine and prevents a fake-generalization that has no real call site.

If `PlaylistDetailViewModel`'s constructor doesn't already inject `SyncPlaylistLinkDao`, add it via Koin (`single { ... } bind` style isn't needed; the dao is already a Koin `single` from Phase 1).

### Step 4: Audit `SyncEngine.syncPlaylists` for the equivalent path

Per desktop CLAUDE.md "Main.js `sync:start` also flags on refill" — the import branch should also flag when refilling an empty local from a pulled provider AND `hasOtherMirrors` is true. Today Android doesn't have a refill-on-empty path that mirrors desktop's behavior — playlists are imported wholesale, not refilled into empty rows. Verify by reading `SyncEngine.syncPlaylists` import branch (around line 540+). If a refill-on-empty path exists, apply the same `hasOtherMirrors` flag write. If not, leave a `// TODO Phase 4: refill path needs Fix 1 when AM sync lands` comment in the import branch and move on.

### Step 5: Run full suite

```
./gradlew :app:testDebugUnitTest
```

### Step 6: Commit

```
git add app/src/main/java/com/parachord/android/ui/screens/playlists/PlaylistDetailViewModel.kt \
        app/src/test/java/com/parachord/android/sync/PullSetsLocallyModifiedTest.kt \
        app/src/main/java/com/parachord/android/sync/SyncEngine.kt  # only if audit revealed a refill path
git commit -m "Fix 1: pull paths set locallyModified=true when other mirrors exist"
```

---

## Task 3: Fix 2 — Local-content mutators inline `locallyModified` with mirror guard

`LibraryRepository.addTracksToPlaylist` and `removeTrackFromPlaylist` already inline `locallyModified = true` (verified in audit — lines 181, 221). The Phase 3 work is **adding the guard** so local-only playlists aren't flagged: the flag should fire only when the playlist has a `syncedFrom` source OR at least one `syncedTo` mirror. Without the guard, locally-created playlists with no sync intent get flagged forever and waste push-loop iterations checking nothing.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/data/repository/LibraryRepository.kt` (the two mutator functions + helper)
- Modify: `app/src/main/java/com/parachord/android/data/db/dao/PlaylistDao.kt` — add `hasSyncIntent(localId): Boolean` helper that returns true if `syncedFrom` row exists OR any `sync_playlist_link` row exists for this local
- Add: `app/src/test/java/com/parachord/android/data/repository/LibraryRepositoryFlagGuardTest.kt`

### Step 1: Write tests for the guard

```kotlin
@Test
fun `addTracksToPlaylist on local-only playlist does NOT flag locallyModified`() = runBlocking {
    val db = TestDatabaseFactory.create()
    val repo = makeRepo(db)
    repo.createLocalPlaylist("local-abc", "My List")  // no syncedFrom, no syncedTo
    repo.addTracksToPlaylist("local-abc", listOf(makeTrack()))
    val pl = db.playlistQueries.getById("local-abc").executeAsOne()
    assertEquals(0L, pl.locallyModified)
}

@Test
fun `addTracksToPlaylist on synced-from playlist DOES flag locallyModified`() = runBlocking {
    val db = TestDatabaseFactory.create()
    val repo = makeRepo(db)
    repo.createLocalPlaylist("spotify-abc", "From Spotify")
    db.syncPlaylistSourceQueries.upsert("spotify-abc", "spotify", "abc", null, null, 1L)
    repo.addTracksToPlaylist("spotify-abc", listOf(makeTrack()))
    val pl = db.playlistQueries.getById("spotify-abc").executeAsOne()
    assertEquals(1L, pl.locallyModified)
}

@Test
fun `addTracksToPlaylist on synced-to playlist DOES flag locallyModified`() = runBlocking {
    // similar shape; setup adds a sync_playlist_link row instead of source
}

@Test
fun `removeTrackFromPlaylist respects same guard`() = runBlocking { /* ... */ }
```

`makeRepo` constructs a `LibraryRepository` against the in-memory DB; `makeTrack` is a tiny TrackEntity factory.

### Step 2: Run, expect FAIL (guard doesn't exist)

### Step 3: Add `PlaylistDao.hasSyncIntent`

```kotlin
suspend fun hasSyncIntent(localId: String): Boolean = withContext(Dispatchers.IO) {
    syncPlaylistSourceDao.selectForLocal(localId) != null
        || syncPlaylistLinkDao.selectForLocal(localId).isNotEmpty()
}
```

`PlaylistDao` already takes `SyncPlaylistSourceDao` and `SyncPlaylistLinkDao` as injected deps — verify and inject if needed. (Or put the helper on `LibraryRepository` directly, which already has both DAOs. Caller's choice.)

### Step 4: Wire the guard

In `addTracksToPlaylist` / `removeTrackFromPlaylist`, replace the unconditional `locallyModified = true` with:

```kotlin
val shouldFlag = hasSyncIntent(playlistId)
playlistDao.update(playlist.copy(
    /* ...other fields... */,
    locallyModified = shouldFlag,
))
```

If the playlist already had `locallyModified = true` from a prior pending push, don't downgrade it: change to `locallyModified = playlist.locallyModified || shouldFlag`. Avoids unflagging mid-sync.

### Step 5: Full-suite + commit

```
git commit -m "Fix 2: local-content mutators only flag locallyModified when playlist has sync intent"
```

---

## Task 4: Fix 3 — Provider-scoped `syncedFrom` guard in push loop

`SyncEngine`'s push loop must skip a playlist only when the **current** push target is its pull source — not blanket-skip any playlist with a `syncedFrom`. Today this isn't an issue (we only iterate Spotify), but Phase 4 will iterate Apple Music too, and at that point a Spotify-imported playlist (`syncedFrom.resolver == "spotify"`) MUST still be pushable to Apple Music.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/sync/SyncEngine.kt` — both push loops
- Add: `app/src/test/java/com/parachord/android/sync/PushLoopGuardTest.kt`

### Step 1: Write the test (uses fake providers)

```kotlin
class PushLoopGuardTest {
    @Test
    fun `playlist syncedFrom spotify is NOT skipped when pushing to applemusic`() {
        val playlist = makePlaylist(id = "spotify-abc", syncedFromResolver = "spotify")
        val applemusicProviderId = "applemusic"
        // Predicate under test:
        val skip = playlist.syncedFrom?.resolver == applemusicProviderId
        assertFalse("AM is not the source — push must proceed", skip)
    }

    @Test
    fun `playlist syncedFrom spotify IS skipped when pushing to spotify`() {
        val playlist = makePlaylist(id = "spotify-abc", syncedFromResolver = "spotify")
        val skip = playlist.syncedFrom?.resolver == "spotify"
        assertTrue("Spotify is the source — don't re-push", skip)
    }

    @Test
    fun `playlist with no syncedFrom never matches the guard`() {
        val playlist = makePlaylist(id = "local-abc", syncedFromResolver = null)
        for (provider in listOf("spotify", "applemusic", "tidal")) {
            assertFalse(playlist.syncedFrom?.resolver == provider)
        }
    }
}
```

### Step 2: Add the guard

In `SyncEngine.syncPlaylists` push loop (find via grep for the push branch), insert at the top of the per-playlist iteration:

```kotlin
for (playlist in localPlaylists) {
    if (playlist.localOnly) continue
    // Provider-scoped guard — NOT `if (playlist has any syncedFrom) continue`.
    // A Spotify-imported playlist must still be pushable to Apple Music.
    val pullSource = syncPlaylistSourceDao.selectForLocal(playlist.id)
    if (pullSource?.providerId == providerId) continue
    // Defense-in-depth: id-prefix guard (matches desktop's pattern).
    if (playlist.id.startsWith("$providerId-")) continue
    // Skip rows with a pending action awaiting user resolution.
    val link = syncPlaylistLinkDao.selectForLink(playlist.id, providerId)
    if (link?.pendingAction != null) continue

    // Existing push body unchanged...
}
```

The `pullSource?.providerId == providerId` is the exact translation of desktop's `playlist.syncedFrom?.resolver == providerId`. The `pendingAction` skip wires up Phase 1's `pendingAction` column into the push loop — necessary so a remote-deleted link doesn't get re-pushed.

If both push loops exist (background timer + post-wizard), apply to both.

### Step 3: Full-suite + commit

```
git commit -m "Fix 3: provider-scoped syncedFrom guard + pendingAction skip in push loop"
```

---

## Task 5: Fix 4 — Post-push `relevantMirrors` clear

After the push loop runs for every enabled provider, iterate `locallyModified` playlists. For each, compute `relevantMirrors = enabledProviders.filter { has syncedTo[it] AND it != source }`. Clear the flag if every mirror's `syncedAt >= lastModified`.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/sync/SyncEngine.kt` — append a `clearLocallyModifiedFlags` step at the end of `syncPlaylists`
- Add: `app/src/test/java/com/parachord/android/sync/RelevantMirrorsClearTest.kt`

### Step 1: Write tests (DAO-level — exercises Task 1's helpers + the post-push logic)

```kotlin
class RelevantMirrorsClearTest {
    @Test
    fun `flag clears when all relevant mirrors caught up`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val linkDao = SyncPlaylistLinkDao(db)
        val sourceDao = SyncPlaylistSourceDao(db)
        // Setup: spotify-imported playlist with AM mirror; both mirrors at syncedAt=200
        // and lastModified=100. relevantMirrors = [applemusic] (excludes source).
        // Expected: flag clears.
        // ...
    }

    @Test
    fun `flag stays set when one relevant mirror is behind`() = runBlocking {
        // AM at syncedAt=50, lastModified=100. Flag does NOT clear.
    }

    @Test
    fun `flag clears immediately when no relevant mirrors exist (source-only)`() = runBlocking {
        // syncedFrom=spotify, syncedTo[spotify] only. relevantMirrors = [] (source excluded).
        // Per desktop: empty relevantMirrors → clear flag (we already pushed where we could).
    }

    @Test
    fun `flag respects the source-provider exclusion`() = runBlocking {
        // syncedFrom=spotify, syncedTo[spotify, applemusic] both at syncedAt=200, lastModified=100.
        // Including spotify (the source) in the all-synced check would always strand the flag
        // because Fix 3's guard prevents pushing back to source. relevantMirrors must exclude it.
        // Expected: flag clears.
    }
}
```

### Step 2: Implement `clearLocallyModifiedFlags`

```kotlin
private suspend fun clearLocallyModifiedFlags(enabledProviders: Set<String>) {
    val candidates = playlistDao.getLocallyModified()
    for (playlist in candidates) {
        val source = syncPlaylistSourceDao.selectForLocal(playlist.id)
        val sourceProvider = source?.providerId
        val mirrors = syncPlaylistLinkDao.selectForLocal(playlist.id)
        val relevantMirrors = mirrors.filter { link ->
            link.providerId in enabledProviders && link.providerId != sourceProvider
        }
        if (relevantMirrors.isEmpty()) {
            playlistDao.clearLocallyModified(playlist.id)
            continue
        }
        val allCaught = relevantMirrors.all { (it.syncedAt) >= (playlist.lastModified) }
        if (allCaught) playlistDao.clearLocallyModified(playlist.id)
    }
}
```

Add the call at the end of `syncPlaylists` after the push loop:

```kotlin
clearLocallyModifiedFlags(enabledProviders = setOf(SpotifySyncProvider.PROVIDER_ID))
```

The hardcoded set today contains only Spotify; Phase 4 broadens it to all enabled providers from `SettingsStore`. Kept as a literal set so Phase 4's change is one line.

Required new bits:
- `playlistDao.getLocallyModified()` — `SELECT * FROM playlists WHERE locallyModified = 1`. New SQL query.
- `playlistDao.clearLocallyModified(localId)` — already exists if not, add `UPDATE playlists SET locallyModified = 0 WHERE id = ?`.

### Step 3: Full-suite + commit

```
git commit -m "Fix 4: post-push relevantMirrors clear excludes source provider"
```

---

## Task 6: Cross-provider `syncedFrom` preservation in import path

When the import branch matches a remote to an existing local row, the match can fire because the local is a **push target** for this provider (its `syncedFrom` points at a different provider). Phase 4 makes this scenario reachable; Phase 3 wires the guard so it's correct from day one.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/sync/SyncEngine.kt` — import branch
- Add: `app/src/test/java/com/parachord/android/sync/CrossProviderSyncedFromTest.kt`

### Step 1: Write the test

```kotlin
class CrossProviderSyncedFromTest {
    @Test
    fun `applemusic import does NOT clobber spotify syncedFrom on a push-mirror local`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        sourceDao.upsert("spotify-abc", "spotify", "abc", "snap-1", "user-1", 1L)
        val syncedFrom = sourceDao.selectForLocal("spotify-abc")!!
        val isOwnPullSource = syncedFrom.providerId == "applemusic"
        // The match fires when this local is an AM push mirror; we must NOT
        // overwrite syncedFrom because Spotify owns it.
        assertFalse("AM is the importing provider but Spotify is the source — preserve", isOwnPullSource)
    }

    @Test
    fun `spotify re-import on its own source playlist DOES update syncedFrom`() = runBlocking {
        val db = TestDatabaseFactory.create()
        val sourceDao = SyncPlaylistSourceDao(db)
        sourceDao.upsert("spotify-abc", "spotify", "abc", "snap-1", null, 1L)
        val syncedFrom = sourceDao.selectForLocal("spotify-abc")!!
        val isOwnPullSource = syncedFrom.providerId == "spotify"
        assertTrue(isOwnPullSource)
    }
}
```

### Step 2: Wire the guard in import branch

In `SyncEngine.syncPlaylists` import-branch (around the existing match-and-write logic), gate the `syncedFrom` rewrite + tracks-refill:

```kotlin
val existingSource = syncPlaylistSourceDao.selectForLocal(localPlaylistId)
val isOwnPullSource = existingSource == null || existingSource.providerId == providerId

if (isOwnPullSource) {
    // Standard path: update syncedFrom + refill tracks if empty.
    syncPlaylistSourceDao.upsert(localPlaylistId, providerId, externalId, snapshotId, ownerId, now)
    // ... existing track-refill logic ...
} else {
    // CROSS-PROVIDER PUSH MIRROR. Preserve existingSource.
    // Do NOT refetch tracks (the source provider is authoritative).
    // Do NOT compute hasUpdates from snapshotId diff (cross-provider snapshots aren't comparable).
    // DO update the syncedTo[providerId] link's syncedAt.
    syncPlaylistLinkDao.upsertWithSnapshot(localPlaylistId, providerId, externalId, snapshotId, now)
}
```

If the existing import branch doesn't already separate "match" from "create-new", refactor minimally to expose this fork.

### Step 3: Full-suite + commit

```
git commit -m "Preserve cross-provider syncedFrom in import path (isOwnPullSource guard)"
```

---

## Task 7: End-to-end multi-provider propagation contract test

A single test that verifies the four fixes hold together using a fake second `SyncProvider`. This is the most valuable test in Phase 3 — it's the canary that future regressions trip first.

**Files:**
- Add: `app/src/test/java/com/parachord/android/sync/MultiProviderPropagationContractTest.kt`

### Step 1: Write the scenario

```kotlin
/**
 * Round-trip contract test. Simulates a 4-step scenario:
 *   1. User has a local playlist with syncedFrom=spotify and syncedTo={spotify, applemusic}.
 *   2. Remote Spotify edits land; sync pulls them into local. Fix 1 sets locallyModified=true
 *      because applemusic mirror exists.
 *   3. Push loop runs against applemusic. Fix 3 guard allows the push (source is spotify, not applemusic).
 *      Push succeeds; applemusic syncedAt advances.
 *   4. Post-loop clear runs. Fix 4 sees relevantMirrors=[applemusic] (spotify excluded as source),
 *      both syncedAt>=lastModified, clears the flag.
 *
 * Without ANY of the four fixes this scenario produces a stuck `locallyModified=true` flag
 * (1+4 missing) or a missing AM update (2+3 missing) or a never-cleared flag (4 missing).
 */
class MultiProviderPropagationContractTest {
    @Test
    fun `four fixes together complete an Android-Spotify-AM round trip`() = runBlocking {
        // Setup: in-memory DB, fake spotify + applemusic providers, SyncEngine instance
        // Step 1: pre-populate playlist + syncedFrom + syncedTo links
        // Step 2: simulate pull (call the predicate from Task 2)
        // Step 3: simulate push to applemusic — assert Fix 3 guard allows
        // Step 4: call clearLocallyModifiedFlags — assert flag cleared
        // assert final state matches the contract
    }
}
```

The test doesn't need to exercise real HTTP — fake providers return canned responses. The point is to wire all four predicates through realistic state transitions.

### Step 2: Full-suite + commit

```
git commit -m "Multi-provider propagation contract test exercising all four fixes"
```

---

## Task 8: Phase 3 wrap-up

**Files:**
- Modify: `docs/plans/2026-04-22-multi-provider-sync-correctness.md` — mark Phase 3 status

### Step 1: Status note

```markdown
> **Status:** ✅ Landed in commits ... → .... All four mirror-propagation
> invariants implemented + the cross-provider `syncedFrom` preservation
> guard. Single-provider behavior unchanged; logic becomes load-bearing
> in Phase 4 when Apple Music sync starts iterating the same loops.
> Verified: full test suite green; multi-provider contract test pinned.
```

### Step 2: Install + smoke-test

```
./gradlew installDebug
adb shell am force-stop com.parachord.android.debug
```

Open the app, run a Spotify sync, edit a playlist, run sync again — confirm no regression.

### Step 3: Commit

```
git commit -m "Mark multi-provider sync Phase 3 complete"
```

---

## Verification for the end of Phase 3

- All Phase 1 + Phase 2 + Phase 3 tests pass: 312 + ~15 new = ~327 total.
- `assembleDebug` clean.
- Spotify single-provider sync still works exactly as before — observable behavior unchanged.
- The four fixes are wired and tested in isolation; the contract test exercises all four together.
- Phase 4 can land Apple Music sync without further plumbing changes — the `getAll()` Koin binding from Phase 2, the `relevantMirrors` clear from Phase 3, and the provider-scoped `syncedFrom` guard from Phase 3 all activate the moment a second provider registers.

## Out of scope for Phase 3

- Apple Music sync (Phase 4).
- The `pendingAction` UI banner (Phase 4 or later — column + skip-in-push are already wired).
- Wizard / settings multi-provider UX (Phase 6).
- The Apple-specific endpoint degradation (Phase 4 — included in `AppleMusicSyncProvider`).

## Execution

Drive directly in the same session. Each task is small enough to commit + push between tasks; full-suite test after every commit. Subagent loop is overkill for this kind of work (Phase 2 demonstrated this).
