# Multi-Provider Sync — Phase 1 Execution Plan (Data Model)

> **For Claude:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task, committing after each task passes.
>
> Parent plan: [docs/plans/2026-04-22-multi-provider-sync-correctness.md](2026-04-22-multi-provider-sync-correctness.md). Phases 2–6 will be expanded just-in-time once Phase 1 lands.

**Goal:** Extend the Android playlist schema to represent multi-provider `syncedFrom` / `syncedTo` state relationally, mirroring desktop's data model without changing any current behavior. After this phase, the schema is ready to support Apple Music sync (Phase 4) and multi-provider propagation logic (Phase 3) but no provider code has actually changed yet.

**Architecture:** Extend existing `sync_playlist_link` (which already represents `syncedTo[providerId]`) with `snapshotId` + `pendingAction` columns. Create new `sync_playlist_source` table to represent `syncedFrom`. Add `localOnly` column on `playlists`. Keep legacy `playlists.spotifyId` / `playlists.snapshotId` for read compatibility but stop writing them directly — expose them via a DAO helper that joins against the link table (Decision 5).

**Tech Stack:** SQLDelight, SQLite (Android + JVM), JUnit 4, MockK. Tests use an in-memory SQLite-JDBC driver under `testImplementation` so DAO logic can be exercised without an Android device.

---

## Task 1: Add SQLite-JDBC test driver

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts:175-183` (testImplementation block)

**Step 1: Add the version-catalog entries**

In `libs.versions.toml`, under `[libraries]`:

```toml
sqldelight-sqlite-driver = { group = "app.cash.sqldelight", name = "sqlite-driver", version.ref = "sqldelight" }
sqlite-jdbc = { group = "org.xerial", name = "sqlite-jdbc", version = "3.45.3.0" }
```

No version entry needed for `sqlite-jdbc` if you add the hardcoded version inline; matches existing pattern for `androidx-webkit`.

**Step 2: Reference them in `app/build.gradle.kts`**

Add to the `testImplementation(...)` block (right after `testImplementation(libs.turbine)`):

```kotlin
testImplementation(libs.sqldelight.sqlite.driver)
testImplementation(libs.sqlite.jdbc)
```

**Step 3: Sanity-check the classpath compiles**

Run: `./gradlew :app:testDebugUnitTest --tests "com.parachord.android.sync.SyncEngineTest" -i`
Expected: existing test suite passes. If the dependency declarations are malformed, Gradle fails at resolve time before running any tests.

**Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "Add SQLite-JDBC driver for DAO-level tests"
```

---

## Task 2: Shared test fixture — in-memory database factory

**Files:**
- Create: `app/src/test/java/com/parachord/android/data/db/TestDatabaseFactory.kt`

**Step 1: Write the factory + first smoke test**

```kotlin
package com.parachord.android.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.db.ParachordDb

/**
 * Creates a fresh in-memory SQLDelight database for each test. Mirrors the
 * CREATE TABLE statements that [AndroidModule] runs at DB bind time —
 * SQLDelight's auto-generated schema is sufficient for the core tables,
 * but any idempotent ALTER TABLE migrations must also run here so tests
 * see the same schema existing installs get.
 */
object TestDatabaseFactory {
    fun create(): ParachordDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ParachordDb.Schema.create(driver)
        return ParachordDb(driver)
    }
}
```

**Step 2: Write a smoke test that proves the fixture works**

```kotlin
// File: app/src/test/java/com/parachord/android/data/db/TestDatabaseFactoryTest.kt
package com.parachord.android.data.db

import org.junit.Assert.assertTrue
import org.junit.Test

class TestDatabaseFactoryTest {
    @Test
    fun `creates usable in-memory db`() {
        val db = TestDatabaseFactory.create()
        val count = db.playlistQueries.getAll().executeAsList().size
        assertTrue("expected fresh DB to have 0 playlists, got $count", count == 0)
    }
}
```

**Step 3: Run the smoke test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.parachord.android.data.db.TestDatabaseFactoryTest" -i`
Expected: PASS.

**Step 4: Commit**

```bash
git add app/src/test/java/com/parachord/android/data/db/TestDatabaseFactory.kt app/src/test/java/com/parachord/android/data/db/TestDatabaseFactoryTest.kt
git commit -m "Add TestDatabaseFactory for DAO-level tests"
```

---

## Task 3: Add `snapshotId` column to `sync_playlist_link`

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/parachord/shared/db/SyncPlaylistLink.sq`
- Create: `app/src/test/java/com/parachord/android/data/db/SyncPlaylistLinkSchemaTest.kt`
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt` (ALTER TABLE bootstrap for existing installs)

**Step 1: Write the failing test**

```kotlin
// File: app/src/test/java/com/parachord/android/data/db/SyncPlaylistLinkSchemaTest.kt
package com.parachord.android.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlaylistLinkSchemaTest {
    @Test
    fun `snapshotId persists across upsert`() {
        val db = TestDatabaseFactory.create()
        db.syncPlaylistLinkQueries.upsertWithSnapshot(
            localPlaylistId = "local-abc",
            providerId = "spotify",
            externalId = "spot-xyz",
            snapshotId = "snap-1",
            syncedAt = 1000L,
        )
        val row = db.syncPlaylistLinkQueries.selectForLink("local-abc", "spotify").executeAsOne()
        assertEquals("snap-1", row.snapshotId)
    }
}
```

**Step 2: Run — expect FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests "com.parachord.android.data.db.SyncPlaylistLinkSchemaTest" -i`
Expected: **FAIL**. The `upsertWithSnapshot` query doesn't exist yet; compilation will fail inside the generated query class because `snapshotId` isn't a column.

**Step 3: Extend the `.sq` schema**

Update `SyncPlaylistLink.sq`:

```sql
CREATE TABLE IF NOT EXISTS sync_playlist_link (
    localPlaylistId TEXT NOT NULL,
    providerId      TEXT NOT NULL,
    externalId      TEXT NOT NULL,
    snapshotId      TEXT,
    syncedAt        INTEGER NOT NULL,
    PRIMARY KEY (localPlaylistId, providerId)
);

-- existing queries unchanged...

upsertWithSnapshot:
INSERT OR REPLACE INTO sync_playlist_link (localPlaylistId, providerId, externalId, snapshotId, syncedAt)
VALUES (?, ?, ?, ?, ?);
```

Leave the existing `upsert:` query in place so existing call sites still compile; the new one is an explicit snapshot-aware variant.

**Step 4: Add the runtime migration for existing installs**

In `AndroidModule.kt`, in the block that already runs idempotent `ALTER TABLE` statements (search for `ALTER TABLE playlists ADD COLUMN sourceUrl`), add:

```kotlin
runCatching {
    driver.execute(null, "ALTER TABLE sync_playlist_link ADD COLUMN snapshotId TEXT", 0)
}
```

Same try/catch-swallow pattern as the other ALTERs — duplicate-column on second launch is expected and harmless.

**Step 5: Run — expect PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "com.parachord.android.data.db.SyncPlaylistLinkSchemaTest" -i`
Expected: **PASS**.

**Step 6: Run the full suite to make sure nothing else regressed**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all existing tests still pass.

**Step 7: Commit**

```bash
git add shared/src/commonMain/sqldelight/com/parachord/shared/db/SyncPlaylistLink.sq \
        app/src/test/java/com/parachord/android/data/db/SyncPlaylistLinkSchemaTest.kt \
        app/src/main/java/com/parachord/android/di/AndroidModule.kt
git commit -m "Add snapshotId column to sync_playlist_link"
```

---

## Task 4: Add `pendingAction` column to `sync_playlist_link`

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/parachord/shared/db/SyncPlaylistLink.sq`
- Modify: `app/src/test/java/com/parachord/android/data/db/SyncPlaylistLinkSchemaTest.kt`
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt`

**Step 1: Add the failing test**

Append to `SyncPlaylistLinkSchemaTest.kt`:

```kotlin
@Test
fun `pendingAction round-trips`() {
    val db = TestDatabaseFactory.create()
    db.syncPlaylistLinkQueries.upsertWithSnapshot(
        localPlaylistId = "local-abc",
        providerId = "spotify",
        externalId = "spot-xyz",
        snapshotId = null,
        syncedAt = 1000L,
    )
    db.syncPlaylistLinkQueries.setPendingAction("local-abc", "spotify", "remote-deleted")
    val row = db.syncPlaylistLinkQueries.selectForLink("local-abc", "spotify").executeAsOne()
    assertEquals("remote-deleted", row.pendingAction)
}

@Test
fun `selectPendingForProvider returns only rows with non-null pendingAction`() {
    val db = TestDatabaseFactory.create()
    db.syncPlaylistLinkQueries.upsertWithSnapshot("a", "spotify", "x", null, 1L)
    db.syncPlaylistLinkQueries.upsertWithSnapshot("b", "spotify", "y", null, 2L)
    db.syncPlaylistLinkQueries.setPendingAction("b", "spotify", "remote-deleted")
    val pending = db.syncPlaylistLinkQueries.selectPendingForProvider("spotify").executeAsList()
    assertEquals(1, pending.size)
    assertEquals("b", pending.first().localPlaylistId)
}
```

**Step 2: Run — expect FAIL** (queries don't exist)

**Step 3: Extend `.sq` schema**

```sql
CREATE TABLE IF NOT EXISTS sync_playlist_link (
    localPlaylistId TEXT NOT NULL,
    providerId      TEXT NOT NULL,
    externalId      TEXT NOT NULL,
    snapshotId      TEXT,
    pendingAction   TEXT,
    syncedAt        INTEGER NOT NULL,
    PRIMARY KEY (localPlaylistId, providerId)
);

-- new queries:
setPendingAction:
UPDATE sync_playlist_link SET pendingAction = ? WHERE localPlaylistId = ? AND providerId = ?;

clearPendingAction:
UPDATE sync_playlist_link SET pendingAction = NULL WHERE localPlaylistId = ? AND providerId = ?;

selectPendingForProvider:
SELECT * FROM sync_playlist_link WHERE providerId = ? AND pendingAction IS NOT NULL;
```

**Step 4: Add the runtime migration in `AndroidModule.kt`:**

```kotlin
runCatching {
    driver.execute(null, "ALTER TABLE sync_playlist_link ADD COLUMN pendingAction TEXT", 0)
}
```

**Step 5: Run — expect PASS**. Then **Step 6:** full suite passes. **Step 7:** commit.

```bash
git commit -m "Add pendingAction column to sync_playlist_link with query helpers"
```

---

## Task 5: Add `localOnly` column to `playlists`

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/parachord/shared/db/Playlist.sq`
- Create: `app/src/test/java/com/parachord/android/data/db/PlaylistLocalOnlyTest.kt`
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt`

**Step 1: Failing test**

```kotlin
class PlaylistLocalOnlyTest {
    @Test
    fun `localOnly defaults to 0`() {
        val db = TestDatabaseFactory.create()
        db.playlistQueries.insert(
            id = "local-abc", name = "My List", description = null, artworkUrl = null,
            trackCount = 0, createdAt = 1L, updatedAt = 1L, spotifyId = null,
            snapshotId = null, lastModified = 1L, locallyModified = 0L,
            ownerName = null, sourceUrl = null, sourceContentHash = null,
            // localOnly intentionally omitted to test default
        )
        val row = db.playlistQueries.getById("local-abc").executeAsOne()
        assertEquals(0L, row.localOnly)
    }

    @Test
    fun `setLocalOnly flips the flag`() {
        val db = TestDatabaseFactory.create()
        db.playlistQueries.insert(/* ... same as above ... */)
        db.playlistQueries.setLocalOnly("local-abc", 1L)
        val row = db.playlistQueries.getById("local-abc").executeAsOne()
        assertEquals(1L, row.localOnly)
    }
}
```

**Step 2: Run — FAIL** (column doesn't exist; the `insert` query signature also needs to change).

**Step 3: Extend `Playlist.sq`**

Add `localOnly INTEGER NOT NULL DEFAULT 0` to the CREATE TABLE. Update the `insert:` query to include `localOnly` as the last parameter with a default. Add a `setLocalOnly:` query:

```sql
setLocalOnly:
UPDATE playlists SET localOnly = ? WHERE id = ?;
```

**Step 4: Runtime migration in `AndroidModule.kt`:**

```kotlin
runCatching {
    driver.execute(null, "ALTER TABLE playlists ADD COLUMN localOnly INTEGER NOT NULL DEFAULT 0", 0)
}
```

**Step 5: Existing `insert` call sites need updating.** Add `localOnly = 0` to each of them so the compile passes. Grep for `playlistQueries.insert(` and `playlistDao.insert(` to find every site. Don't change any behavior — just pass 0.

**Step 6: Run — PASS**. **Step 7: full suite passes**. **Step 8: commit**.

```bash
git commit -m "Add localOnly column to playlists table"
```

---

## Task 6: Create `sync_playlist_source` table

**Files:**
- Create: `shared/src/commonMain/sqldelight/com/parachord/shared/db/SyncPlaylistSource.sq`
- Create: `app/src/test/java/com/parachord/android/data/db/SyncPlaylistSourceSchemaTest.kt`
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt`

**Step 1: Failing test**

```kotlin
class SyncPlaylistSourceSchemaTest {
    @Test
    fun `upsert and read syncedFrom`() {
        val db = TestDatabaseFactory.create()
        db.syncPlaylistSourceQueries.upsert(
            localPlaylistId = "spotify-abc",
            providerId = "spotify",
            externalId = "abc",
            snapshotId = "snap-1",
            ownerId = "user-1",
            syncedAt = 1000L,
        )
        val row = db.syncPlaylistSourceQueries.selectForLocal("spotify-abc").executeAsOne()
        assertEquals("spotify", row.providerId)
        assertEquals("abc", row.externalId)
        assertEquals("user-1", row.ownerId)
    }

    @Test
    fun `primary key is localPlaylistId alone — one source per playlist`() {
        val db = TestDatabaseFactory.create()
        db.syncPlaylistSourceQueries.upsert("p1", "spotify", "a", null, null, 1L)
        db.syncPlaylistSourceQueries.upsert("p1", "applemusic", "b", null, null, 2L)
        val row = db.syncPlaylistSourceQueries.selectForLocal("p1").executeAsOne()
        assertEquals("applemusic", row.providerId)  // second upsert wins
    }
}
```

**Step 2: Run — FAIL**.

**Step 3: Create the new `.sq` file**

```sql
-- File: SyncPlaylistSource.sq
-- Represents the desktop `syncedFrom: { resolver, externalId, snapshotId, ownerId }`
-- shape. A playlist has at most one source (the provider it was imported from),
-- so localPlaylistId is the sole primary key. Distinct from sync_playlist_link
-- which is keyed (localPlaylistId, providerId) — many push targets, one pull source.

CREATE TABLE IF NOT EXISTS sync_playlist_source (
    localPlaylistId TEXT NOT NULL PRIMARY KEY,
    providerId      TEXT NOT NULL,
    externalId      TEXT NOT NULL,
    snapshotId      TEXT,
    ownerId         TEXT,
    syncedAt        INTEGER NOT NULL
);

selectAll:
SELECT * FROM sync_playlist_source;

selectForLocal:
SELECT * FROM sync_playlist_source WHERE localPlaylistId = ?;

selectByExternalId:
SELECT * FROM sync_playlist_source WHERE providerId = ? AND externalId = ?;

upsert:
INSERT OR REPLACE INTO sync_playlist_source
    (localPlaylistId, providerId, externalId, snapshotId, ownerId, syncedAt)
VALUES (?, ?, ?, ?, ?, ?);

deleteForLocal:
DELETE FROM sync_playlist_source WHERE localPlaylistId = ?;

deleteForProvider:
DELETE FROM sync_playlist_source WHERE providerId = ?;
```

**Step 4: Add runtime bootstrap in `AndroidModule.kt`**

The existing block that recreates `sync_playlist_link` via raw DDL on every bind should gain a sibling for `sync_playlist_source`:

```kotlin
runCatching {
    driver.execute(null, """
        CREATE TABLE IF NOT EXISTS sync_playlist_source (
            localPlaylistId TEXT NOT NULL PRIMARY KEY,
            providerId      TEXT NOT NULL,
            externalId      TEXT NOT NULL,
            snapshotId      TEXT,
            ownerId         TEXT,
            syncedAt        INTEGER NOT NULL
        )
    """.trimIndent(), 0)
}
```

**Step 5: Run — PASS**. **Step 6: full suite**. **Step 7: commit**.

```bash
git commit -m "Create sync_playlist_source table for syncedFrom representation"
```

---

## Task 7: DAO wrapper for `sync_playlist_source`

**Files:**
- Create: `app/src/main/java/com/parachord/android/data/db/dao/SyncPlaylistSourceDao.kt`
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt` (register the DAO with Koin)
- Create: `app/src/test/java/com/parachord/android/data/db/dao/SyncPlaylistSourceDaoTest.kt`

**Step 1: Failing test**

```kotlin
class SyncPlaylistSourceDaoTest {
    @Test
    fun `upsert stores and retrieves`() {
        val db = TestDatabaseFactory.create()
        val dao = SyncPlaylistSourceDao(db)
        dao.upsert(
            localPlaylistId = "local-abc",
            providerId = "spotify",
            externalId = "xyz",
            snapshotId = null,
            ownerId = "me",
            syncedAt = 1L,
        )
        val source = dao.selectForLocal("local-abc")
        assertNotNull(source)
        assertEquals("spotify", source!!.providerId)
    }

    @Test
    fun `deleteForLocal removes the row`() { /* similar shape */ }
    @Test
    fun `deleteForProvider nukes all rows for the provider`() { /* similar shape */ }
}
```

**Step 2: Run — FAIL** (DAO doesn't exist).

**Step 3: Implement the DAO**

Mirror the existing `SyncPlaylistLinkDao.kt` style. Expose `suspend fun` wrappers around the generated queries. Include a `data class Source` model for row returns if the generated type is inconvenient.

**Step 4: Register with Koin** in `AndroidModule.kt` next to `SyncPlaylistLinkDao`:

```kotlin
single { SyncPlaylistSourceDao(get()) }
```

**Step 5: Run — PASS**. **Step 6: full suite**. **Step 7: commit**.

```bash
git commit -m "Add SyncPlaylistSourceDao wrapper + Koin registration"
```

---

## Task 8: Update `SyncPlaylistLinkDao` to expose `snapshotId` + `pendingAction`

**Files:**
- Modify: `app/src/main/java/com/parachord/android/data/db/dao/SyncPlaylistLinkDao.kt`
- Create or modify: `app/src/test/java/com/parachord/android/data/db/dao/SyncPlaylistLinkDaoTest.kt`

**Step 1: Failing test**

```kotlin
@Test
fun `upsertWithSnapshot stores and retrieves snapshotId`() {
    val db = TestDatabaseFactory.create()
    val dao = SyncPlaylistLinkDao(db)
    dao.upsertWithSnapshot("local-abc", "spotify", "xyz", "snap-1", 1L)
    assertEquals("snap-1", dao.selectForLink("local-abc", "spotify")?.snapshotId)
}

@Test
fun `setPendingAction + clearPendingAction round-trip`() {
    val db = TestDatabaseFactory.create()
    val dao = SyncPlaylistLinkDao(db)
    dao.upsertWithSnapshot("a", "spotify", "x", null, 1L)
    dao.setPendingAction("a", "spotify", "remote-deleted")
    assertEquals("remote-deleted", dao.selectForLink("a", "spotify")?.pendingAction)
    dao.clearPendingAction("a", "spotify")
    assertNull(dao.selectForLink("a", "spotify")?.pendingAction)
}

@Test
fun `selectPendingForProvider returns only rows with pendingAction set`() { /* ... */ }
```

**Step 2: Run — FAIL**.

**Step 3: Extend `SyncPlaylistLinkDao`**

Add the three methods (`upsertWithSnapshot`, `setPendingAction`, `clearPendingAction`, `selectPendingForProvider`). The existing `upsert` stays for backward compatibility; new sync paths should call `upsertWithSnapshot`.

If the existing `Link` data class doesn't expose `snapshotId` / `pendingAction`, extend it with those nullable fields.

**Step 4: Run — PASS**. **Step 5: full suite**. **Step 6: commit**.

```bash
git commit -m "Expose snapshotId + pendingAction via SyncPlaylistLinkDao"
```

---

## Task 9: Backfill migration for `sync_playlist_source`

**Files:**
- Modify: `app/src/main/java/com/parachord/android/sync/SyncEngine.kt`
- Create: `app/src/test/java/com/parachord/android/sync/MigrateSourceFromPlaylistsTest.kt`

Mirrors the existing `migrateLinksFromPlaylists` but populates `sync_playlist_source` from playlists whose `id` starts with `spotify-` (meaning they were Spotify-imported — `syncedFrom.resolver = "spotify"`). Idempotent. Runs at the top of every sync cycle.

**Step 1: Failing test**

```kotlin
class MigrateSourceFromPlaylistsTest {
    @Test
    fun `backfills syncedFrom for spotify-imported playlists`() {
        val db = TestDatabaseFactory.create()
        db.playlistQueries.insert(
            id = "spotify-abc", name = "From Spotify", /* ... */
            spotifyId = "abc", snapshotId = "snap-1", /* ... */,
            localOnly = 0,
        )
        val sourceDao = SyncPlaylistSourceDao(db)
        val linkDao = SyncPlaylistLinkDao(db)
        // call the migration logic under test — extract into a testable helper
        SyncEngine.migrateSourceFromPlaylists(db, sourceDao)
        assertEquals("spotify", sourceDao.selectForLocal("spotify-abc")?.providerId)
        assertEquals("abc", sourceDao.selectForLocal("spotify-abc")?.externalId)
        assertEquals("snap-1", sourceDao.selectForLocal("spotify-abc")?.snapshotId)
    }

    @Test
    fun `is idempotent — second call no-ops`() {
        // call twice; result is identical; no exceptions
    }

    @Test
    fun `ignores playlists without id prefix`() {
        // a local-* playlist with a spotifyId (push mirror) should NOT
        // get a syncedFrom row — Spotify is its push target, not source.
    }
}
```

**Step 2: Run — FAIL**.

**Step 3: Implement `migrateSourceFromPlaylists`**

Either as a top-level `object` function or a companion-object helper on `SyncEngine`. It iterates all playlists, filters by `id.startsWith("spotify-")`, and upserts a `sync_playlist_source` row. If `spotifyId` is missing (should never happen, but defensive) skip with a log warning.

**Step 4: Wire it in** — `SyncEngine.syncPlaylists()` should call it alongside the existing `migrateLinksFromPlaylists()`.

**Step 5: Run — PASS**. **Step 6: full suite**. **Step 7: commit**.

```bash
git commit -m "Add migrateSourceFromPlaylists for syncedFrom backfill"
```

---

## Task 10: Auto-derive `spotifyId` / `snapshotId` via DAO join (Decision 5)

**Files:**
- Modify: `app/src/main/java/com/parachord/android/data/db/dao/PlaylistDao.kt`
- Modify: `shared/src/commonMain/sqldelight/com/parachord/shared/db/Playlist.sq` (add a join query)
- Create: `app/src/test/java/com/parachord/android/data/db/dao/PlaylistDaoJoinTest.kt`

**Step 1: Failing test**

```kotlin
class PlaylistDaoJoinTest {
    @Test
    fun `getByIdWithSpotifyLink joins sync_playlist_link`() {
        val db = TestDatabaseFactory.create()
        val dao = PlaylistDao(db)
        dao.insert(/* spotifyId = null, snapshotId = null */)
        db.syncPlaylistLinkQueries.upsertWithSnapshot("local-abc", "spotify", "xyz", "snap-1", 1L)
        val view = dao.getByIdWithSpotifyLink("local-abc")
        assertEquals("xyz", view.spotifyId)
        assertEquals("snap-1", view.snapshotId)
    }
}
```

**Step 2: Run — FAIL**.

**Step 3: Add the SQLDelight join query to `Playlist.sq`:**

```sql
getByIdWithSpotifyLink:
SELECT p.*, l.externalId AS linkSpotifyId, l.snapshotId AS linkSnapshotId
FROM playlists p
LEFT JOIN sync_playlist_link l
  ON l.localPlaylistId = p.id AND l.providerId = 'spotify'
WHERE p.id = ?;
```

**Step 4: DAO wrapper returns a view model**

```kotlin
data class PlaylistWithLink(
    val entity: PlaylistEntity,
    val linkSpotifyId: String?,
    val linkSnapshotId: String?,
) {
    /** Resolved Spotify ID: link-table wins, falls back to legacy scalar. */
    val spotifyId: String? get() = linkSpotifyId ?: entity.spotifyId
    val snapshotId: String? get() = linkSnapshotId ?: entity.snapshotId
}
```

Existing call sites of `playlistDao.getById(id).spotifyId` continue to work (entity accessor falls back). New read paths can use `getByIdWithSpotifyLink` to get the link-table-sourced value.

**Step 5: Run — PASS**. **Step 6: full suite**. **Step 7: commit**.

```bash
git commit -m "Expose spotifyId/snapshotId via sync_playlist_link join"
```

---

## Task 11: Integration smoke test — full Phase 1 state round-trip

**Files:**
- Create: `app/src/test/java/com/parachord/android/sync/Phase1SchemaIntegrationTest.kt`

One test that exercises the whole Phase 1 schema in a realistic scenario:

```kotlin
@Test
fun `full sync state survives round-trip through every new field`() {
    val db = TestDatabaseFactory.create()
    val playlistDao = PlaylistDao(db)
    val linkDao = SyncPlaylistLinkDao(db)
    val sourceDao = SyncPlaylistSourceDao(db)

    // A Spotify-imported playlist mirrored to Apple Music with a pending action.
    playlistDao.insert(/* id = "spotify-abc", localOnly = 0, ... */)
    sourceDao.upsert("spotify-abc", "spotify", "abc", "snap-1", "user-1", 1000L)
    linkDao.upsertWithSnapshot("spotify-abc", "spotify", "abc", "snap-1", 1000L)
    linkDao.upsertWithSnapshot("spotify-abc", "applemusic", "am-xyz", "2026-04-20T12:00:00Z", 1000L)
    linkDao.setPendingAction("spotify-abc", "applemusic", "remote-deleted")

    // Read everything back:
    val pl = playlistDao.getByIdWithSpotifyLink("spotify-abc")
    assertEquals("abc", pl.spotifyId)
    assertEquals("snap-1", pl.snapshotId)
    assertEquals(0L, pl.entity.localOnly)

    val source = sourceDao.selectForLocal("spotify-abc")
    assertEquals("spotify", source!!.providerId)

    val amLink = linkDao.selectForLink("spotify-abc", "applemusic")
    assertEquals("remote-deleted", amLink!!.pendingAction)
    assertEquals("am-xyz", amLink.externalId)

    val pending = linkDao.selectPendingForProvider("applemusic")
    assertEquals(1, pending.size)
}
```

**Step 1: write test. Step 2: run — PASS (all previous tasks should make this work out of the box). Step 3: commit**.

```bash
git commit -m "Phase 1 integration smoke test"
```

---

## Task 12: Phase 1 wrap-up — doc + install to device

**Files:**
- Modify: `docs/plans/2026-04-22-multi-provider-sync-correctness.md` (update Phase 1 status)

**Step 1: Mark Phase 1 complete in the parent plan.**

Add a status note at the top of the Phase 1 section:

> **Status:** ✅ Landed in commits … through …. Schema ready; no provider code changed yet. Phase 2 expansion needed before propagation logic work can begin.

**Step 2: Install to device, smoke test, force-stop**

```bash
./gradlew installDebug
/Users/jherskowitz/Library/Android/sdk/platform-tools/adb shell am force-stop com.parachord.android.debug
```

Launch the app; verify:
- A normal Spotify sync still works (no regressions).
- A hosted XSPF playlist still polls + pushes.
- No crash on startup (DB bind runs the new ALTER TABLE / CREATE TABLE).

**Step 3: Commit the status update.**

```bash
git commit -m "Mark multi-provider sync Phase 1 complete"
```

---

## Verification for the end of Phase 1

- `./gradlew :app:testDebugUnitTest` is green.
- `./gradlew :app:assembleDebug` succeeds.
- Fresh install on device: Parachord opens, plays music, syncs a Spotify playlist without error. DB inspector confirms `sync_playlist_link.snapshotId`, `sync_playlist_link.pendingAction`, `sync_playlist_source` (all columns / tables), and `playlists.localOnly` exist.
- Existing users upgrading: force-stop, relaunch. No crash from the ALTER TABLE migrations (try/catch swallows duplicate-column on second launch).
- No user-facing behavior changed. The columns are populated by `migrateSourceFromPlaylists` backfill on first sync; nothing reads them yet. Phase 2 will start using them.

---

## Execution choice

**1. Subagent-Driven (this session)** — dispatch fresh subagent per task, review between tasks.

**2. Parallel Session (separate)** — open new session with `superpowers:executing-plans` in a worktree.

Recommend **option 2** for this phase — it's 12 tasks of mechanical schema work that benefits from uninterrupted focus, and the subagent overhead per task isn't worth it.
