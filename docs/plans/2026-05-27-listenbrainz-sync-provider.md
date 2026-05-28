# ListenBrainzSyncProvider Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a third `SyncProvider` implementation that two-way-syncs playlists between Parachord (Android) and ListenBrainz. Closes #156. Unblocks #145.

**Architecture:** Mirrors the existing `SpotifySyncProvider` / `AppleMusicSyncProvider` shape — provider-agnostic `SyncEngine` dispatches on `ProviderFeatures`, never on `id`. New `trackRecordingMbid` column on `playlist_tracks` carries the LB-side track identity. AM-style session kill-switch handles 401s gracefully. Three-layer dedup (link table → name match → create) gives multi-client convergence (Desktop ↔ LB ↔ Android) for free.

**Tech Stack:** Kotlin + SQLDelight + Ktor (LB client) + Koin DI. Tests: JUnit + Robolectric + mockk.

**Design reference:** `docs/plans/2026-05-27-listenbrainz-sync-provider-design.md`.

---

## Phase 1 — Schema + Model

### Task 1: Add `trackRecordingMbid` column to PlaylistTrack.sq

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/parachord/shared/db/PlaylistTrack.sq`

**Step 1: Update CREATE TABLE + INSERT + backfill query**

Add `trackRecordingMbid TEXT` column after `trackAppleMusicId`:

```sql
CREATE TABLE IF NOT EXISTS playlist_tracks (
    playlistId TEXT NOT NULL,
    position INTEGER NOT NULL,
    -- ... existing columns ...
    trackAppleMusicId TEXT,
    trackRecordingMbid TEXT,   -- NEW
    addedAt INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (playlistId, position),
    FOREIGN KEY (playlistId) REFERENCES playlists(id) ON DELETE CASCADE
);
```

Update `insert:` query to include the new column (14 → 15 placeholders):

```sql
insert:
INSERT OR REPLACE INTO playlist_tracks (playlistId, position, trackTitle, trackArtist, trackAlbum, trackDuration, trackArtworkUrl, trackSourceUrl, trackResolver, trackSpotifyUri, trackSoundcloudId, trackSpotifyId, trackAppleMusicId, trackRecordingMbid, addedAt)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
```

Extend `backfillResolverIds:` to include the MBID:

```sql
backfillResolverIds:
UPDATE playlist_tracks
SET trackSpotifyId       = COALESCE(trackSpotifyId, ?),
    trackSpotifyUri      = COALESCE(trackSpotifyUri, ?),
    trackAppleMusicId    = COALESCE(trackAppleMusicId, ?),
    trackSoundcloudId    = COALESCE(trackSoundcloudId, ?),
    trackRecordingMbid   = COALESCE(trackRecordingMbid, ?)
WHERE playlistId = ? AND position = ?;
```

**Step 2: Verify SQLDelight regenerates**

```bash
./gradlew :shared:generateCommonMainParachordDbInterface
```

Expected: BUILD SUCCESSFUL. The generated Kotlin code at `shared/build/generated/sqldelight/code/parachord/commonMain/com/parachord/shared/db/Playlist_tracks.kt` should now have a `trackRecordingMbid: String?` field.

**Step 3: Commit**

```bash
git add shared/src/commonMain/sqldelight/com/parachord/shared/db/PlaylistTrack.sq
git commit -m "schema: add trackRecordingMbid column to playlist_tracks"
```

---

### Task 2: Add `trackRecordingMbid` to the PlaylistTrack model

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/model/PlaylistTrack.kt`

**Step 1: Add field to data class**

After `trackAppleMusicId`, add `val trackRecordingMbid: String? = null,`. Default null so all existing callsites stay compiled.

Also update `availableResolvers` if there's a "musicbrainz" / "listenbrainz" inclusion needed — likely no change, since `availableResolvers` is for content resolvers, not metadata providers.

**Step 2: Compile**

```bash
./gradlew :shared:compileDebugKotlinAndroid :shared:compileKotlinIosArm64
```

Expected: BUILD SUCCESSFUL. iOS target included to catch any KMP-stdlib issue early.

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/model/PlaylistTrack.kt
git commit -m "model: add trackRecordingMbid field to PlaylistTrack"
```

---

### Task 3: Update PlaylistTrackDao to read/write the new column

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/db/dao/PlaylistTrackDao.kt`

**Step 1: Find existing constructors / mappers**

Grep for the SQL-row → `PlaylistTrack` mapper and the `insert` callsite:

```bash
grep -n "trackAppleMusicId" shared/src/commonMain/kotlin/com/parachord/shared/db/dao/PlaylistTrackDao.kt
```

**Step 2: Add `trackRecordingMbid` to the row mapper and insert callsite**

In `Playlist_tracks.toPlaylistTrack()` (around line 23), add the field. In every `db.playlistTrackQueries.insert(...)` call site, pass `pt.trackRecordingMbid`. In `backfillResolverIds` if it's wrapped in Kotlin, add the parameter.

**Step 3: Compile + verify generated DAO code matches**

```bash
./gradlew :shared:compileDebugKotlinAndroid
```

Expected: BUILD SUCCESSFUL.

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/db/dao/PlaylistTrackDao.kt
git commit -m "dao: read/write trackRecordingMbid column"
```

---

### Task 4: Add ALTER TABLE migration in AndroidModule

**Files:**
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt`

**Step 1: Find the existing in-bind ALTER TABLE block**

Grep for the playlists sourceUrl migration or sync_playlist_link CREATE in the database bind path:

```bash
grep -n "ALTER TABLE\|CREATE TABLE IF NOT EXISTS" app/src/main/java/com/parachord/android/di/AndroidModule.kt | head -10
```

**Step 2: Add ALTER TABLE statement wrapped in try/catch**

In the same block, after the existing migrations:

```kotlin
// Migration: add trackRecordingMbid to playlist_tracks (V13).
// Wrapped in try/catch — duplicate-column on second launch is the only
// expected failure, and it's safe to swallow. New installs get the
// column from PlaylistTrack.sq's CREATE TABLE.
try {
    driver.execute(
        identifier = null,
        sql = "ALTER TABLE playlist_tracks ADD COLUMN trackRecordingMbid TEXT",
        parameters = 0,
    )
} catch (_: Exception) {
    // Column already exists — migration already ran.
}
```

**Step 3: Compile + install + verify on device**

```bash
./gradlew installDebug
adb shell am force-stop com.parachord.android.debug
```

Then launch Parachord on the device — no crash on startup, no log of "no such column: trackRecordingMbid".

**Step 4: Commit**

```bash
git add app/src/main/java/com/parachord/android/di/AndroidModule.kt
git commit -m "db: ALTER TABLE playlist_tracks ADD COLUMN trackRecordingMbid"
```

---

## Phase 2 — ListenBrainz client mutations

### Task 5: Add `ListenBrainzUnauthorizedException`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/sync/ListenBrainzUnauthorizedException.kt`

**Step 1: Write the file**

```kotlin
package com.parachord.shared.sync

/**
 * Thrown by [com.parachord.shared.api.ListenBrainzClient] mutation
 * endpoints when LB returns 401. [ListenBrainzSyncProvider] catches
 * this and trips its session-scoped auth-failed kill-switch, mirroring
 * [AppleMusicReauthRequiredException]'s contract.
 */
class ListenBrainzUnauthorizedException(message: String = "ListenBrainz returned 401 — token rejected") : Exception(message)
```

**Step 2: Compile**

```bash
./gradlew :shared:compileDebugKotlinAndroid
```

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/sync/ListenBrainzUnauthorizedException.kt
git commit -m "sync: add ListenBrainzUnauthorizedException"
```

---

### Task 6: Add `createPlaylist` + `editPlaylist` + `deletePlaylist` to ListenBrainzClient

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/api/ListenBrainzClient.kt`

**Step 1: Write the failing test first**

Create `app/src/test/java/com/parachord/shared/api/ListenBrainzClientMutationTest.kt`. Use Ktor's `MockEngine` to assert request shape:

```kotlin
@Test fun `createPlaylist sends correct body + auth header`() = runTest {
    val engine = MockEngine { request ->
        assertEquals("https://api.listenbrainz.org/1/playlist/create", request.url.toString())
        assertEquals("Token test-token", request.headers["Authorization"])
        // ... assert body JSON shape ...
        respond("""{"playlist_mbid":"12345678-1234-1234-1234-123456789012"}""", HttpStatusCode.OK)
    }
    val client = ListenBrainzClient(makeHttpClient(engine))
    val mbid = client.createPlaylist("Test", description = "Desc", isPublic = true, token = "test-token")
    assertEquals("12345678-1234-1234-1234-123456789012", mbid)
}

@Test fun `createPlaylist throws ListenBrainzUnauthorizedException on 401`() = runTest {
    val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
    val client = ListenBrainzClient(makeHttpClient(engine))
    assertFailsWith<ListenBrainzUnauthorizedException> {
        client.createPlaylist("Test", token = "bad")
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.parachord.shared.api.ListenBrainzClientMutationTest.*"` — expect FAIL (methods don't exist).

**Step 2: Implement the three mutation methods**

In `ListenBrainzClient.kt`, after `mbidMapperLookup`:

```kotlin
/** POST /1/playlist/create — returns the assigned playlist MBID. */
suspend fun createPlaylist(
    name: String,
    description: String? = null,
    isPublic: Boolean = true,
    token: String,
): String = executeWithRetry {
    val response = http.post("$BASE/1/playlist/create") {
        header(HttpHeaders.Authorization, "Token $token")
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("playlist", buildJsonObject {
                put("title", name)
                description?.let { put("annotation", it) }
                put("extension", buildJsonObject {
                    put("https://musicbrainz.org/doc/jspf#playlist", buildJsonObject {
                        put("public", isPublic)
                    })
                })
            })
        }.toString())
    }
    if (response.status == HttpStatusCode.Unauthorized) {
        throw ListenBrainzUnauthorizedException()
    }
    val body = response.body<JsonObject>()
    body["playlist_mbid"]?.jsonPrimitive?.content
        ?: throw IllegalStateException("LB create-playlist response missing playlist_mbid")
}

/** POST /1/playlist/edit/{mbid}. */
suspend fun editPlaylist(playlistMbid: String, name: String?, description: String?, token: String) {
    // ...
}

/** DELETE /1/playlist/{mbid}. */
suspend fun deletePlaylist(playlistMbid: String, token: String) {
    // ...
}
```

(Exact body shape per https://listenbrainz.readthedocs.io/en/latest/users/api/playlist.html)

**Step 3: Run tests, expect PASS**

```bash
./gradlew :app:testDebugUnitTest --tests "com.parachord.shared.api.ListenBrainzClientMutationTest.*"
```

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/api/ListenBrainzClient.kt app/src/test/java/com/parachord/shared/api/ListenBrainzClientMutationTest.kt
git commit -m "client: add LB playlist create/edit/delete mutations"
```

---

### Task 7: Add `addPlaylistItems` + `deletePlaylistItems` + `getPlaylistLastModified`

**Files:**
- Modify: `shared/.../api/ListenBrainzClient.kt`
- Modify: `app/src/test/java/com/parachord/shared/api/ListenBrainzClientMutationTest.kt`

**Step 1: Write failing tests for the three methods**

Same pattern as Task 6 — MockEngine asserting request URL + body + auth header + 401 handling.

For `addPlaylistItems`:
- URL: `POST /1/playlist/{mbid}/item/add`
- Body: `{"playlist": {"track": [{"identifier": "https://musicbrainz.org/recording/<mbid1>"}, ...]}}`

For `deletePlaylistItems`:
- URL: `POST /1/playlist/{mbid}/item/delete`
- Body: `{"index": 0, "count": N}`

For `getPlaylistLastModified`:
- URL: `GET /1/playlist/{mbid}` (existing endpoint, but extract `last_modified_at`)
- Returns: ISO string or null

Run tests → FAIL.

**Step 2: Implement**

**Step 3: Run tests → PASS**

**Step 4: Commit**

```bash
git commit -m "client: add LB playlist item add/delete + last-modified getter"
```

---

### Task 8: Add `getUserOwnedPlaylists` for pull

**Files:**
- Modify: `shared/.../api/ListenBrainzClient.kt`
- Modify: test file

**Step 1: Test**

```kotlin
@Test fun `getUserOwnedPlaylists parses owned playlists list`() = runTest {
    val engine = MockEngine { request ->
        assertTrue(request.url.toString().endsWith("/1/user/testuser/playlists"))
        respond(LB_PLAYLISTS_FIXTURE, HttpStatusCode.OK)
    }
    val client = ListenBrainzClient(makeHttpClient(engine))
    val result = client.getUserOwnedPlaylists("testuser")
    assertEquals(2, result.size)
    assertEquals("My Playlist", result[0].title)
    // ...
}
```

**Step 2: Implement** (similar shape to existing `getCreatedForPlaylists`)

**Step 3: Tests pass**

**Step 4: Commit**

```bash
git commit -m "client: add LB getUserOwnedPlaylists for sync pull"
```

---

## Phase 3 — ListenBrainzSyncProvider

### Task 9: Stub the provider + features struct + `SyncProviderShapeTest` extension

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/sync/ListenBrainzSyncProvider.kt`
- Modify: `app/src/test/java/com/parachord/shared/sync/SyncProviderShapeTest.kt`

**Step 1: Write the stub**

```kotlin
class ListenBrainzSyncProvider(
    private val client: ListenBrainzClient,
    private val settingsStore: SettingsStore,
    private val mbidEnrichmentService: MbidEnrichmentService,
) : SyncProvider {
    companion object { const val PROVIDER_ID = "listenbrainz" }

    override val id = PROVIDER_ID
    override val displayName = "ListenBrainz"
    override val features = ProviderFeatures(
        snapshots = SnapshotKind.DateString,
        supportsFollow = false,
        supportsPlaylistDelete = true,
        supportsPlaylistRename = true,
        supportsTrackReplace = true,
    )

    @Volatile private var authFailedForSession = false

    override suspend fun fetchPlaylists(onProgress: ((Int, Int) -> Unit)?) = TODO()
    override suspend fun fetchPlaylistTracks(externalPlaylistId: String) = TODO()
    override suspend fun getPlaylistSnapshotId(externalPlaylistId: String) = TODO()
    override suspend fun createPlaylist(name: String, description: String?) = TODO()
    override suspend fun replacePlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>) = TODO()
    override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) = TODO()
    override suspend fun deletePlaylist(externalPlaylistId: String) = TODO()
    override suspend fun searchForTrackId(title: String, artist: String, album: String?) = TODO()
}
```

**Step 2: Add features struct test**

In `SyncProviderShapeTest.kt`, add a test that pins the `features` struct:

```kotlin
@Test fun `LB features struct matches design`() {
    val provider = ListenBrainzSyncProvider(mockk(), mockk(), mockk())
    assertEquals(SnapshotKind.DateString, provider.features.snapshots)
    assertFalse(provider.features.supportsFollow)
    assertTrue(provider.features.supportsPlaylistDelete)
    assertTrue(provider.features.supportsPlaylistRename)
    assertTrue(provider.features.supportsTrackReplace)
}
```

**Step 3: Compile + test**

```bash
./gradlew :app:testDebugUnitTest --tests "com.parachord.shared.sync.SyncProviderShapeTest.*"
```

Expected: PASS.

**Step 4: Commit**

```bash
git commit -m "sync: stub ListenBrainzSyncProvider + features struct"
```

---

### Task 10: Implement `fetchPlaylists` (auth gate + kill-switch)

**Files:**
- Modify: `shared/.../sync/ListenBrainzSyncProvider.kt`
- Create: `app/src/test/java/com/parachord/android/sync/ListenBrainzSyncProviderTest.kt`

**Step 1: Failing test**

```kotlin
@Test fun `fetchPlaylists returns empty when token unset`() = runTest {
    val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns null }
    val provider = ListenBrainzSyncProvider(mockk(), settings, mockk())
    assertTrue(provider.fetchPlaylists().isEmpty())
}

@Test fun `fetchPlaylists swallows 401 and trips kill-switch`() = runTest {
    val client: ListenBrainzClient = mockk {
        coEvery { getUserOwnedPlaylists(any()) } throws ListenBrainzUnauthorizedException()
    }
    val settings: SettingsStore = mockk {
        coEvery { getListenBrainzToken() } returns "valid-but-server-rejected"
        coEvery { getListenBrainzUsername() } returns "test-user"
    }
    val provider = ListenBrainzSyncProvider(client, settings, mockk())
    assertTrue(provider.fetchPlaylists().isEmpty())
    // After kill-switch trips, second call short-circuits (no client call)
    assertTrue(provider.fetchPlaylists().isEmpty())
    coVerify(exactly = 1) { client.getUserOwnedPlaylists(any()) }
}
```

**Step 2: Implement** — check kill-switch, check token, call client, catch `ListenBrainzUnauthorizedException` to trip kill-switch and return empty.

**Step 3: Tests pass**

**Step 4: Commit**

```bash
git commit -m "sync: ListenBrainzSyncProvider fetchPlaylists with kill-switch"
```

---

### Task 11: Implement `fetchPlaylistTracks` + `getPlaylistSnapshotId`

**Files:**
- Modify: provider + test

**Step 1: Tests** (similar pattern)

**Step 2: Implement** — `fetchPlaylistTracks` delegates to existing `client.getPlaylistTracksRich`, maps to `PlaylistTrack`. `getPlaylistSnapshotId` calls new `client.getPlaylistLastModified`.

**Step 3: Tests pass**

**Step 4: Commit**

```bash
git commit -m "sync: LB fetchPlaylistTracks + getPlaylistSnapshotId"
```

---

### Task 12: Implement `createPlaylist` + `replacePlaylistTracks` + `updatePlaylistDetails`

**Files:**
- Modify: provider + test

**Step 1: Tests**

```kotlin
@Test fun `createPlaylist returns RemoteCreated with server-assigned MBID`() = runTest {
    val client: ListenBrainzClient = mockk {
        coEvery { createPlaylist(any(), any(), any(), any()) } returns "new-mbid-uuid"
    }
    val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
    val provider = ListenBrainzSyncProvider(client, settings, mockk())
    val result = provider.createPlaylist("Test", "Desc")
    assertEquals("new-mbid-uuid", result.externalId)
    assertNull(result.snapshotId) // re-fetched separately
}

@Test fun `replacePlaylistTracks does delete-all + add-all`() = runTest {
    val client: ListenBrainzClient = mockk(relaxed = true) {
        coEvery { getPlaylistTracksRich("mbid") } returns List(3) { mockk() }
        coEvery { getPlaylistLastModified("mbid") } returns "2026-05-27T12:00:00Z"
    }
    val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
    val provider = ListenBrainzSyncProvider(client, settings, mockk())
    val newSnap = provider.replacePlaylistTracks("mbid", listOf("a", "b", "c", "d"))
    coVerify { client.deletePlaylistItems("mbid", 0, 3, "tok") }
    coVerify { client.addPlaylistItems("mbid", listOf("a", "b", "c", "d"), "tok") }
    assertEquals("2026-05-27T12:00:00Z", newSnap)
}

@Test fun `replacePlaylistTracks skips API calls when both empty`() = runTest {
    val client: ListenBrainzClient = mockk(relaxed = true) {
        coEvery { getPlaylistTracksRich("mbid") } returns emptyList()
    }
    val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
    val provider = ListenBrainzSyncProvider(client, settings, mockk())
    provider.replacePlaylistTracks("mbid", emptyList())
    coVerify(exactly = 0) { client.deletePlaylistItems(any(), any(), any(), any()) }
    coVerify(exactly = 0) { client.addPlaylistItems(any(), any(), any()) }
}
```

**Step 2: Implement**

**Step 3: Tests pass**

**Step 4: Commit**

```bash
git commit -m "sync: LB create/replace/updateDetails"
```

---

### Task 13: Implement `deletePlaylist` + `searchForTrackId`

**Files:**
- Modify: provider + test

**Step 1: Tests**

```kotlin
@Test fun `deletePlaylist 401 returns Unsupported (not thrown)`() = runTest {
    val client: ListenBrainzClient = mockk {
        coEvery { deletePlaylist(any(), any()) } throws ListenBrainzUnauthorizedException()
    }
    val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
    val provider = ListenBrainzSyncProvider(client, settings, mockk())
    val result = provider.deletePlaylist("mbid")
    assertTrue(result is DeleteResult.Unsupported)
}

@Test fun `searchForTrackId delegates to mapper`() = runTest {
    val enrichment: MbidEnrichmentService = mockk {
        coEvery { mapperLookup("Title", "Artist") } returns mockk { every { recordingMbid } returns "mbid-result" }
    }
    val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
    val provider = ListenBrainzSyncProvider(mockk(), settings, enrichment)
    assertEquals("mbid-result", provider.searchForTrackId("Title", "Artist"))
}

@Test fun `searchForTrackId returns null when mapper has no match`() = runTest {
    val enrichment: MbidEnrichmentService = mockk {
        coEvery { mapperLookup(any(), any()) } returns null
    }
    val provider = ListenBrainzSyncProvider(mockk(), mockk(relaxed = true), enrichment)
    assertNull(provider.searchForTrackId("Title", "Artist"))
}
```

**Step 2: Implement**

**Step 3: Tests pass**

**Step 4: Commit**

```bash
git commit -m "sync: LB deletePlaylist + searchForTrackId"
```

---

## Phase 4 — SyncEngine wiring

### Task 14: Add LB branches to `extractExternalTrackIds` + `missingProviderId` + `applyResolvedId`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncEngine.kt`
- Modify: `app/src/test/java/com/parachord/android/sync/SyncEngineTest.kt` (or new test file)

**Step 1: Failing test**

```kotlin
@Test fun `extractExternalTrackIds for LB returns trackRecordingMbid values`() {
    // Use reflection or test-only public exposure to call the helper.
    // ...
}
```

**Step 2: Add LB branches** to each of the three helpers per the design.

**Step 3: Tests pass**

**Step 4: Commit**

```bash
git commit -m "sync: SyncEngine recognizes LB in track-ID helpers"
```

---

### Task 15: Extend `isPushCandidate` for LB + add to `pushPlaylistsForProvider` dispatch

**Files:**
- Modify: `SyncEngine.kt`
- Modify: test file

**Step 1: Failing test** in `PushCandidateTest.kt`:

```kotlin
@Test fun `LB push candidate includes spotify-prefixed playlists`() {
    val playlist = Playlist(id = "spotify-abc", ...)
    assertTrue(SyncEngine.isPushCandidate(playlist, "listenbrainz"))
}
```

**Step 2: Implement** — `isPushCandidate(playlist, "listenbrainz") = startsWith("local-") || sourceUrl != null || startsWith("spotify-") || startsWith("applemusic-")`.

**Step 3: Tests pass**

**Step 4: Commit**

```bash
git commit -m "sync: LB push candidate filter includes provider mirrors"
```

---

### Task 16: Extend `healImportedSyncedFromMismatch` for `listenbrainz-*` prefix

**Files:**
- Modify: `SyncEngine.kt`
- Modify: `app/src/test/java/com/parachord/android/sync/HealImportedSyncedFromTest.kt`

**Step 1: Failing test** — case where a `listenbrainz-mbid-xyz` playlist row has `sync_playlist_source.providerId = "spotify"` (corrupt state); the heal should restore `providerId = "listenbrainz"`.

**Step 2: Implement** — add `"listenbrainz-"` to the prefix list. The implied-provider lookup (`prefix → provider`) extends to LB.

**Step 3: Tests pass**

**Step 4: Commit**

```bash
git commit -m "sync: healImportedSyncedFromMismatch handles listenbrainz-* prefix"
```

---

## Phase 5 — DI + Settings + UI

### Task 17: Koin binding for ListenBrainzSyncProvider + inject into SyncEngine providers list

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/di/SharedModule.kt` (or wherever `single<SyncEngine>` lives)

**Step 1: Add binding**

```kotlin
single {
    ListenBrainzSyncProvider(client = get(), settingsStore = get(), mbidEnrichmentService = get())
}

single<SyncEngine> {
    SyncEngine(
        providers = listOf(
            get<SpotifySyncProvider>(),
            get<AppleMusicSyncProvider>(),
            get<ListenBrainzSyncProvider>(),
        ),
        // ...
    )
}
```

**Step 2: Compile + run app**

```bash
./gradlew :app:compileDebugKotlin installDebug
adb shell am force-stop com.parachord.android.debug
```

Launch the app — no Koin missing-binding crash on startup.

**Step 3: Commit**

```bash
git commit -m "di: register ListenBrainzSyncProvider + add to SyncEngine providers"
```

---

### Task 18: SettingsStore — `listenbrainz` in enabled-providers + `getListenBrainzUsername`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/settings/SettingsStore.kt`

**Step 1: Tests** (extend existing SettingsStore tests if present, or create minimal ones)

**Step 2: Implement**
- `getEnabledSyncProviders()` allows `"listenbrainz"` as a valid value
- Add `getListenBrainzUsername(): String?` reading from existing KvStore key (probably already populated by LB scrobbler auth flow)

**Step 3: Tests pass**

**Step 4: Commit**

```bash
git commit -m "settings: listenbrainz in enabled-sync-providers + getListenBrainzUsername"
```

---

### Task 19: Settings UI toggle for LB sync

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/screens/settings/SettingsScreen.kt`

**Step 1: Add toggle row**

In the General settings section, parallel to the "Apple Music Sync" toggle:

```kotlin
val lbConnected by viewModel.listenBrainzConnected.collectAsStateWithLifecycle()
val lbSyncEnabled by viewModel.listenBrainzSyncEnabled.collectAsStateWithLifecycle()
if (lbConnected) {
    SettingsToggleRow(
        title = "ListenBrainz Sync",
        subtitle = "Pushes Parachord-curated playlists to your ListenBrainz profile. Loved tracks already sync separately via scrobblers.",
        checked = lbSyncEnabled,
        onCheckedChange = { viewModel.setListenBrainzSyncEnabled(it) },
    )
}
```

**Step 2: ViewModel additions** — `listenBrainzSyncEnabled: StateFlow<Boolean>` reading/writing `getEnabledSyncProviders()`.

**Step 3: Compile + install + verify**

```bash
./gradlew installDebug
```

Toggle visible only when LB is authorized. Toggling reflects in `getEnabledSyncProviders()`.

**Step 4: Commit**

```bash
git commit -m "ui: ListenBrainz Sync toggle in Settings"
```

---

## Phase 6 — Final verification

### Task 20: Full test suite

**Step 1: Run all tests**

```bash
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests passing including the new LB suite + existing Spotify/AM conformance tests.

**Step 2: If failures, debug and fix.** Don't move on.

**Step 3: Commit any fixes**

---

### Task 21: Install on device + smoke test

**Step 1: Install + force-stop**

```bash
./gradlew installDebug
adb shell am force-stop com.parachord.android.debug
```

**Step 2: Launch app, verify**

- Settings → General → "ListenBrainz Sync" toggle visible (assuming LB is connected)
- Toggle it on
- Trigger a sync (Settings → Sync now, OR wait for the next scheduled sync)
- adb logcat | grep "LB\|ListenBrainz" — verify sync ran, no exceptions

**Step 3: Smoke test the convergence path** (manual, may need to coordinate with desktop):

1. Create a local playlist with 3 tracks on Android
2. Trigger sync — check on LB website (listenbrainz.org/user/<name>/playlists) that the playlist appeared
3. On desktop with LB sync enabled, trigger sync — verify the playlist appears locally
4. Add a track on desktop, sync, verify it appears on Android after the next Android sync
5. Delete on Android, sync, verify it's gone from LB and desktop's next sync removes it locally

**Step 4: Open PR**

```bash
git push -u origin feat/listenbrainz-sync-provider-156
gh pr create --title "feat: ListenBrainzSyncProvider — two-way playlist sync (closes #156)" --body ...
```

---

## Design / spec references
- `docs/plans/2026-05-27-listenbrainz-sync-provider-design.md` — full design
- CLAUDE.md § "Multi-Provider Sync — Apple Music + Spotify (Phases 1–6.5 + Collection)" — convergence model
- `shared/.../sync/SyncProvider.kt` — interface contract
- `shared/.../sync/AppleMusicSyncProvider.kt` — reference impl for kill-switch + best-effort patterns
- LB playlist API: https://listenbrainz.readthedocs.io/en/latest/users/api/playlist.html
