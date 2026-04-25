# Multi-Provider Sync — Phase 4 Execution Plan (Apple Music Sync Provider)

> **For Claude:** Direct-execution plan. Drive in this session; commit + push between tasks.
>
> Parent plan: [2026-04-22-multi-provider-sync-correctness.md](2026-04-22-multi-provider-sync-correctness.md). Phases 1–3 landed in `06c396d` → `67b9f96`. Phase 5 (concurrency edge cases) and Phase 6 (settings/wizard UI) follow.

**Goal:** Add `AppleMusicSyncProvider` so the Phase 3 multi-provider propagation logic activates with a real second provider — pushing locally-edited playlists to Apple Music, pulling AM library playlists into Parachord, surviving Apple's 401-on-PATCH/PUT/DELETE degradation without throwing.

**Architecture:** New Retrofit interface `AppleMusicLibraryApi` for `api.music.apple.com/v1/me/library/*`. New `AppleMusicSyncProvider` implementing `SyncProvider` with two session kill-switches (`amPutUnsupportedForSession`, `amPatchUnsupportedForSession`) tracked independently. Auth interceptor reads the developer token from `BuildConfig.APPLE_MUSIC_DEVELOPER_TOKEN` and the user token from `SettingsStore.getAppleMusicUserToken()`. SyncEngine push loop iterates the registered `List<SyncProvider>`; the existing four propagation fixes from Phase 3 activate immediately. No UI work (that's Phase 6); user enables AM sync by setting an `enabled_providers` flag in DataStore that Phase 6 will surface.

**Tech Stack:** Retrofit + OkHttp, Kotlin coroutines, Koin DI, JUnit 4, MockK, OkHttp `MockWebServer` for Retrofit tests.

## Scope decisions (from D1)

- **In:** Playlist sync — pull, push, create, delete, rename, track replace.
- **Out (deferred):** Library tracks/albums/artists pull + push (the `fetchTracks` / `saveTracks` / `fetchAlbums` / `saveAlbums` / `followArtists` interface methods stay in P4 scope as **default no-op overrides** so the interface compiles, but they're stubbed for AM until a follow-up phase). Catalog-search-based track-ID resolution is also deferred — Phase 4 pushes only tracks that already have an `appleMusicId` set; tracks without one are silently skipped during push (logged once per playlist).
- **No UI:** Settings toggle is Phase 6. Enabling AM sync in Phase 4 is "set the storefront and the user token in DataStore via existing settings or adb"; the wiring downstream activates automatically.

---

## Task 1: Extend `SyncProvider` interface with playlist method members

`SyncProvider` is property-only today. Phase 4 adds the playlist surface that both providers must implement, plus default no-op stubs for library methods (so AM doesn't have to implement them yet).

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncProvider.kt`
- Modify: `app/src/test/java/com/parachord/shared/sync/SyncProviderShapeTest.kt` (assert new method shapes compile)

### Step 1: Append new members to the interface

```kotlin
interface SyncProvider {
    val id: String
    val displayName: String
    val features: ProviderFeatures

    // ── Playlist surface (Phase 4) ───────────────────────────────────

    /**
     * Fetch the user's owned + followed playlists from the provider.
     * Returns provider-shaped `SyncedPlaylist` rows; SyncEngine merges
     * into local state via three-layer dedup.
     */
    suspend fun fetchPlaylists(
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): List<com.parachord.shared.sync.SyncedPlaylist>

    /**
     * Fetch every track in [externalPlaylistId]. Returns
     * PlaylistTrackEntity rows ready to insert into Parachord's
     * playlist_tracks table. Provider-specific track IDs are populated
     * (`trackSpotifyId` for Spotify, `trackAppleMusicId` for AM, etc.).
     */
    suspend fun fetchPlaylistTracks(
        externalPlaylistId: String,
    ): List<com.parachord.android.data.db.entity.PlaylistTrackEntity>

    /**
     * Returns the provider's snapshot/change-token for [externalPlaylistId].
     * Spotify returns its opaque `snapshot_id`; Apple returns the
     * `lastModifiedDate` ISO string. SyncEngine string-compares against
     * the stored snapshot; mismatch ⇒ pull.
     */
    suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String?

    /**
     * Create a new remote playlist with [name] (and optional description).
     * Returns the newly-created external ID + initial snapshot.
     */
    suspend fun createPlaylist(
        name: String,
        description: String? = null,
    ): RemoteCreated

    /**
     * Full-replace [externalPlaylistId]'s tracklist with the provided
     * external IDs. Returns the new snapshot token. Providers without
     * reliable replace (Apple Music when PUT 401's) degrade to append-
     * only after first failure (kill-switch); removals stay on remote.
     */
    suspend fun replacePlaylistTracks(
        externalPlaylistId: String,
        externalTrackIds: List<String>,
    ): String?

    /**
     * Update playlist metadata (rename, description). Best-effort.
     * Apple Music returns 401 here — providers must NOT throw on
     * documented-unsupported responses (kill-switch + return without
     * raising). Load-bearing because this runs before the track push
     * in `pushPlaylist`; a throw here aborts the track push too.
     */
    suspend fun updatePlaylistDetails(
        externalPlaylistId: String,
        name: String?,
        description: String?,
    )

    /**
     * Delete [externalPlaylistId] from the provider. Apple Music
     * returns 401; providers must return [DeleteResult.Unsupported]
     * (NOT throw) so callers can surface "remove manually in the
     * Music app" UX.
     */
    suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult

    // ── Library surface (DEFERRED to a later phase) ──────────────────
    // Default no-op implementations so providers that don't ship
    // library sync (Apple Music in P4) don't have to implement them.

    suspend fun fetchTracks(
        localCount: Int,
        latestExternalId: String?,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): List<Any>? = null

    suspend fun saveTracks(externalIds: List<String>) { /* no-op */ }
    suspend fun removeTracks(externalIds: List<String>) { /* no-op */ }

    suspend fun fetchAlbums(
        localCount: Int,
        latestExternalId: String?,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): List<Any>? = null

    suspend fun saveAlbums(externalIds: List<String>) { /* no-op */ }
    suspend fun removeAlbums(externalIds: List<String>) { /* no-op */ }

    suspend fun fetchArtists(
        localCount: Int,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): List<Any>? = null

    suspend fun followArtists(externalIds: List<String>) { /* no-op */ }
    suspend fun unfollowArtists(externalIds: List<String>) { /* no-op */ }
}
```

The `Any` return types on the deferred methods are intentional — until library sync lands they're not called from anywhere. When a future phase wires them up, the return types tighten to `SyncedTrack` / `SyncedAlbum` / `SyncedArtist`.

### Step 2: Append shape tests

```kotlin
@Test
fun `interface declares playlist surface`() {
    // Compile-only check: anonymous SyncProvider must implement
    // every playlist method to be instantiable.
    val p = object : SyncProvider {
        override val id = "test"
        override val displayName = "Test"
        override val features = ProviderFeatures(snapshots = SnapshotKind.None)
        override suspend fun fetchPlaylists(onProgress: ((Int, Int) -> Unit)?): List<SyncedPlaylist> = emptyList()
        override suspend fun fetchPlaylistTracks(externalPlaylistId: String) = emptyList<com.parachord.android.data.db.entity.PlaylistTrackEntity>()
        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = null
        override suspend fun createPlaylist(name: String, description: String?) = RemoteCreated("x", null)
        override suspend fun replacePlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>): String? = null
        override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {}
        override suspend fun deletePlaylist(externalPlaylistId: String) = DeleteResult.Success
    }
    // Library methods inherit no-op defaults — instantiation succeeds without overriding them.
    assertEquals("test", p.id)
}
```

### Step 3: Run tests + commit

```
./gradlew :app:testDebugUnitTest --tests "com.parachord.shared.sync.SyncProviderShapeTest"
./gradlew :app:testDebugUnitTest
```

Expected: existing SpotifySyncProviderConformanceTest will FAIL because Spotify doesn't implement the new methods yet. **That's expected**; Task 2 fixes it. **Don't commit until Task 2 lands** — keep the working tree dirty across both tasks since they're a single coupled change.

---

## Task 2: Align `SpotifySyncProvider` with the new interface

Spotify already has every method body needed; just needs `override` keywords + signature renames.

**Files:**
- Modify: `app/src/main/java/com/parachord/android/sync/SpotifySyncProvider.kt`

### Step 1: Add overrides + rename `createPlaylistOnSpotify` → `createPlaylist`

Audit the existing methods:
- `fetchPlaylists` already exists and returns `List<SyncedPlaylist>` — add `override`.
- `fetchPlaylistTracks(spotifyPlaylistId)` already exists — add `override`, rename param to `externalPlaylistId`.
- `getPlaylistSnapshotId(spotifyPlaylistId)` already exists — add `override`, rename param.
- `createPlaylistOnSpotify(name, description?)` returns `SpPlaylistFull` — rename to `createPlaylist`, change return to `RemoteCreated(externalId = result.id, snapshotId = result.snapshotId)`.
- `replacePlaylistTracks(spotifyPlaylistId, spotifyUris)` returns `String?` — add `override`, rename params to `externalPlaylistId, externalTrackIds`.
- `updatePlaylistDetails(spotifyPlaylistId, name, description)` exists — add `override`.
- `deletePlaylist(spotifyPlaylistId)` returns `Unit` — add `override`, change return to `DeleteResult`. Spotify always returns `DeleteResult.Success` from a successful DELETE; on HTTP error return `DeleteResult.Failed(exception)`.

Update existing call sites in `SyncEngine.kt`:
- `spotifyProvider.createPlaylistOnSpotify(...)` → `spotifyProvider.createPlaylist(...)` and use `.externalId` / `.snapshotId` instead of `.id` / `.snapshotId`.
- `spotifyProvider.deletePlaylist(...)` returns `DeleteResult` now — handle the result (log if `.Failed`, log differently if `.Unsupported`).

### Step 2: Run conformance + full suite

```
./gradlew :app:testDebugUnitTest --tests "com.parachord.android.sync.SpotifySyncProviderConformanceTest"
./gradlew :app:testDebugUnitTest
```

Conformance test must pass (provider satisfies the interface); full suite must stay green.

### Step 3: Commit (covers both Task 1 + Task 2 — they're coupled)

```bash
git add shared/src/commonMain/kotlin/com/parachord/shared/sync/SyncProvider.kt \
        app/src/test/java/com/parachord/shared/sync/SyncProviderShapeTest.kt \
        app/src/main/java/com/parachord/android/sync/SpotifySyncProvider.kt \
        app/src/main/java/com/parachord/android/sync/SyncEngine.kt
git commit -m "Add playlist method members to SyncProvider; align SpotifySyncProvider"
```

---

## Task 3: Add MockWebServer test dependency

**Files:**
- Modify: `gradle/libs.versions.toml` — new entry
- Modify: `app/build.gradle.kts` — add to testImplementation

Used by Tasks 6+ to mock Retrofit responses for Apple Music.

### Step 1: Catalog entry

```toml
[libraries]
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
```

### Step 2: testImplementation

```kotlin
testImplementation(libs.okhttp.mockwebserver)
```

### Step 3: Verify + commit

```
./gradlew :app:testDebugUnitTest
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "Add MockWebServer for Retrofit unit tests"
```

---

## Task 4: `AppleMusicLibraryApi` Retrofit interface

**Files:**
- Create: `app/src/main/java/com/parachord/android/data/api/AppleMusicLibraryApi.kt`

Retrofit interface for the 7 endpoints needed for playlist sync. Auth headers (`Authorization: Bearer {dev-token}` and `Music-User-Token: {mut}`) are added by an interceptor; methods don't take auth params.

### Step 1: Define the interface

```kotlin
package com.parachord.android.data.api

import retrofit2.Response
import retrofit2.http.*

interface AppleMusicLibraryApi {

    // ── Library playlists ────────────────────────────────────────────

    @GET("v1/me/library/playlists")
    suspend fun listPlaylists(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): AmPlaylistListResponse

    @GET("v1/me/library/playlists/{id}/tracks")
    suspend fun listPlaylistTracks(
        @Path("id") playlistId: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): AmPlaylistTrackListResponse

    @POST("v1/me/library/playlists")
    suspend fun createPlaylist(
        @Body body: AmCreatePlaylistRequest,
    ): AmPlaylistListResponse

    /**
     * Full-replace via PUT — public API often returns 401.
     * Caller handles by flipping the kill-switch and degrading to POST.
     * Returns Response<Unit> so caller can inspect the status code
     * without an exception being thrown for 4xx responses.
     */
    @PUT("v1/me/library/playlists/{id}/tracks")
    suspend fun replacePlaylistTracks(
        @Path("id") playlistId: String,
        @Body body: AmTracksRequest,
    ): Response<Unit>

    /** Append — the only reliable write path. Returns 204. */
    @POST("v1/me/library/playlists/{id}/tracks")
    suspend fun appendPlaylistTracks(
        @Path("id") playlistId: String,
        @Body body: AmTracksRequest,
    ): Response<Unit>

    /**
     * Rename via PATCH — returns 401 on most tokens. Caller handles
     * by flipping the kill-switch (do NOT throw).
     */
    @PATCH("v1/me/library/playlists/{id}")
    suspend fun updatePlaylistDetails(
        @Path("id") playlistId: String,
        @Body body: AmUpdatePlaylistRequest,
    ): Response<Unit>

    /** Delete — returns 401 in practice. Caller returns Unsupported. */
    @DELETE("v1/me/library/playlists/{id}")
    suspend fun deletePlaylist(
        @Path("id") playlistId: String,
    ): Response<Unit>

    // ── Storefront detection ─────────────────────────────────────────

    @GET("v1/me/storefront")
    suspend fun getStorefront(): AmStorefrontResponse
}
```

### Step 2: Define the JSON shapes

```kotlin
import kotlinx.serialization.Serializable

@Serializable
data class AmPlaylistListResponse(
    val data: List<AmPlaylist>,
    val next: String? = null,
)

@Serializable
data class AmPlaylist(
    val id: String,
    val type: String,            // "library-playlists"
    val attributes: AmPlaylistAttributes,
)

@Serializable
data class AmPlaylistAttributes(
    val name: String,
    val description: AmDescription? = null,
    val canEdit: Boolean = false,
    val dateAdded: String? = null,
    val lastModifiedDate: String? = null,  // The "snapshot" for SnapshotKind.DateString
    val playParams: AmPlayParams? = null,
    val artwork: AmArtwork? = null,
)

@Serializable
data class AmDescription(val standard: String? = null, val short: String? = null)

@Serializable
data class AmPlayParams(val id: String, val kind: String)

@Serializable
data class AmArtwork(val url: String, val width: Int? = null, val height: Int? = null)

@Serializable
data class AmPlaylistTrackListResponse(
    val data: List<AmTrack>,
    val next: String? = null,
)

@Serializable
data class AmTrack(
    val id: String,            // Library track ID
    val type: String,          // "library-songs" or "songs"
    val attributes: AmTrackAttributes,
)

@Serializable
data class AmTrackAttributes(
    val name: String,
    val artistName: String,
    val albumName: String? = null,
    val durationInMillis: Long? = null,
    val artwork: AmArtwork? = null,
    val playParams: AmPlayParams? = null,
)

@Serializable
data class AmCreatePlaylistRequest(
    val attributes: AmCreatePlaylistAttributes,
    val relationships: AmCreatePlaylistRelationships? = null,
)

@Serializable
data class AmCreatePlaylistAttributes(
    val name: String,
    val description: String? = null,
)

@Serializable
data class AmCreatePlaylistRelationships(
    val tracks: AmTracksRelationship,
)

@Serializable
data class AmTracksRelationship(val data: List<AmTrackReference>)

@Serializable
data class AmTrackReference(val id: String, val type: String = "songs")

@Serializable
data class AmTracksRequest(val data: List<AmTrackReference>)

@Serializable
data class AmUpdatePlaylistRequest(val attributes: AmUpdatePlaylistAttributes)

@Serializable
data class AmUpdatePlaylistAttributes(
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class AmStorefrontResponse(val data: List<AmStorefront>)

@Serializable
data class AmStorefront(val id: String)
```

### Step 3: Compile-check + commit

```
./gradlew :app:assembleDebug
git add app/src/main/java/com/parachord/android/data/api/AppleMusicLibraryApi.kt
git commit -m "Add AppleMusicLibraryApi Retrofit interface + JSON models"
```

---

## Task 5: Apple Music auth interceptor + Retrofit client wiring

**Files:**
- Create: `app/src/main/java/com/parachord/android/data/api/AppleMusicAuthInterceptor.kt`
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt` — add Retrofit client + `AppleMusicLibraryApi` provisioning

### Step 1: Auth interceptor

```kotlin
package com.parachord.android.data.api

import com.parachord.android.BuildConfig
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds Apple's required auth headers on every request:
 * - `Authorization: Bearer {dev-token}` — the JWT signed with the
 *   developer team's private key (built into the APK via BuildConfig).
 * - `Music-User-Token: {mut}` — the per-user token obtained through
 *   MusicKitWebBridge.authorize() and persisted in SettingsStore.
 *
 * If the MUT is missing, requests still send the dev-token and Apple
 * returns 401 — the caller handles that by raising
 * AppleMusicReauthRequiredException upstream.
 */
class AppleMusicAuthInterceptor(
    private val settingsStore: SettingsStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val devToken = BuildConfig.APPLE_MUSIC_DEVELOPER_TOKEN
        val mut = runBlocking { settingsStore.getAppleMusicUserToken() }
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer $devToken")
            .apply { if (!mut.isNullOrBlank()) addHeader("Music-User-Token", mut) }
            .build()
        return chain.proceed(request)
    }
}
```

### Step 2: Koin wiring — separate OkHttp client + Retrofit

Apple Music uses a separate OkHttp client (own interceptor + 150ms inter-request pacing) but shares the JSON converter. Add to `AndroidModule`:

```kotlin
// Apple Music — separate OkHttpClient with its own auth interceptor.
single(named("amOkHttp")) {
    OkHttpClient.Builder()
        .addInterceptor(AppleMusicAuthInterceptor(get()))
        // Inherit shared User-Agent + logging interceptors from the global client
        // (or copy them; check existing pattern).
        .build()
}

single<AppleMusicLibraryApi> {
    val json = Json { ignoreUnknownKeys = true }
    Retrofit.Builder()
        .baseUrl("https://api.music.apple.com/")
        .client(get(named("amOkHttp")))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(AppleMusicLibraryApi::class.java)
}
```

### Step 3: Compile + commit

```
./gradlew :app:assembleDebug
git add app/src/main/java/com/parachord/android/data/api/AppleMusicAuthInterceptor.kt \
        app/src/main/java/com/parachord/android/di/AndroidModule.kt
git commit -m "AppleMusicAuthInterceptor + Retrofit client wiring"
```

---

## Task 6: `AppleMusicReauthRequiredException` + 401-handling helper

**Files:**
- Create: `app/src/main/java/com/parachord/android/sync/AppleMusicReauthRequiredException.kt`

### Step 1: Exception

```kotlin
package com.parachord.android.sync

/**
 * Thrown when an Apple Music API call returns 401 on an endpoint
 * that's NOT in the documented-unsupported list. Documented-
 * unsupported endpoints (PATCH/PUT/DELETE on library resources)
 * return their respective sentinel results instead — they don't
 * raise this.
 *
 * Per desktop CLAUDE.md "Do NOT retry-on-401 for any of the
 * documented-unsupported endpoints": defensively retrying with a
 * fresh token here would walk the user through a System Settings
 * revoke flow for an authorization that was never broken (since
 * the 401 is structural, not token-related). Go straight to this
 * exception on the first 401 from a SHOULD-WORK endpoint
 * (listPlaylists, listPlaylistTracks, getStorefront).
 */
class AppleMusicReauthRequiredException(
    message: String = "Apple Music user token rejected; user must re-authorize",
) : Exception(message)
```

### Step 2: Commit

```
git add app/src/main/java/com/parachord/android/sync/AppleMusicReauthRequiredException.kt
git commit -m "Add AppleMusicReauthRequiredException for unrecoverable 401s"
```

---

## Task 7: `AppleMusicSyncProvider` skeleton + read methods

**Files:**
- Create: `app/src/main/java/com/parachord/android/sync/AppleMusicSyncProvider.kt`
- Create: `app/src/test/java/com/parachord/android/sync/AppleMusicSyncProviderConformanceTest.kt`
- Create: `app/src/test/java/com/parachord/android/sync/AppleMusicSyncProviderReadTest.kt` (uses MockWebServer)

### Step 1: Skeleton + conformance test

```kotlin
package com.parachord.android.sync

import android.util.Log
import com.parachord.android.data.api.AppleMusicLibraryApi
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.model.Playlist
import com.parachord.shared.sync.*

class AppleMusicSyncProvider(
    private val api: AppleMusicLibraryApi,
    private val settingsStore: SettingsStore,
) : SyncProvider {

    companion object {
        const val PROVIDER_ID = "applemusic"
        private const val TAG = "AppleMusicSyncProvider"
        private const val PAGE_SIZE = 100
        private const val INTER_REQUEST_DELAY_MS = 150L
    }

    override val id = PROVIDER_ID
    override val displayName = "Apple Music"
    override val features = ProviderFeatures(
        snapshots = SnapshotKind.DateString,
        // Apple has no follow API.
        supportsFollow = false,
        // PATCH/PUT/DELETE all return 401 in practice. We degrade
        // gracefully via session kill-switches; the flag here advertises
        // the limitation to SyncEngine and the UI layer.
        supportsPlaylistDelete = false,
        supportsPlaylistRename = false,
        supportsTrackReplace = false,
    )

    // Session kill-switches — independent flags for independent endpoints.
    @Volatile private var amPutUnsupportedForSession = false
    @Volatile private var amPatchUnsupportedForSession = false

    // Method bodies in subsequent tasks. Stub everything to satisfy the
    // interface so this commit compiles.

    override suspend fun fetchPlaylists(
        onProgress: ((Int, Int) -> Unit)?,
    ): List<SyncedPlaylist> = TODO("Task 7 step 3")

    override suspend fun fetchPlaylistTracks(
        externalPlaylistId: String,
    ): List<PlaylistTrackEntity> = TODO("Task 7 step 4")

    override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? =
        TODO("Task 7 step 5")

    override suspend fun createPlaylist(name: String, description: String?): RemoteCreated =
        TODO("Task 8")

    override suspend fun replacePlaylistTracks(
        externalPlaylistId: String,
        externalTrackIds: List<String>,
    ): String? = TODO("Task 9")

    override suspend fun updatePlaylistDetails(
        externalPlaylistId: String,
        name: String?,
        description: String?,
    ) = TODO("Task 10")

    override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult =
        TODO("Task 11")
}
```

Conformance test (mirrors Spotify's):

```kotlin
class AppleMusicSyncProviderConformanceTest {
    private val provider: SyncProvider = AppleMusicSyncProvider(
        api = mockk(relaxed = true),
        settingsStore = mockk(relaxed = true),
    )

    @Test fun `id is applemusic`() = assertEquals("applemusic", provider.id)
    @Test fun `displayName is Apple Music`() = assertEquals("Apple Music", provider.displayName)
    @Test fun `features declare AM degradation`() {
        val expected = ProviderFeatures(
            snapshots = SnapshotKind.DateString,
            supportsFollow = false,
            supportsPlaylistDelete = false,
            supportsPlaylistRename = false,
            supportsTrackReplace = false,
        )
        assertEquals(expected, provider.features)
    }
}
```

### Step 2: Commit skeleton + conformance test

The TODO bodies will throw `NotImplementedError` if called, but the conformance test only checks `features`/`id`/`displayName` (no method invocation). Tests pass.

```
./gradlew :app:testDebugUnitTest --tests "com.parachord.android.sync.AppleMusicSyncProviderConformanceTest"
git add app/src/main/java/com/parachord/android/sync/AppleMusicSyncProvider.kt \
        app/src/test/java/com/parachord/android/sync/AppleMusicSyncProviderConformanceTest.kt
git commit -m "AppleMusicSyncProvider skeleton + conformance test"
```

### Step 3: Implement `fetchPlaylists` (with pagination + 150ms pacing)

```kotlin
override suspend fun fetchPlaylists(
    onProgress: ((Int, Int) -> Unit)?,
): List<SyncedPlaylist> {
    val all = mutableListOf<SyncedPlaylist>()
    var offset = 0
    while (true) {
        delay(INTER_REQUEST_DELAY_MS)
        val resp = try {
            api.listPlaylists(limit = PAGE_SIZE, offset = offset)
        } catch (e: HttpException) {
            if (e.code() == 401) throw AppleMusicReauthRequiredException()
            throw e
        }
        for (am in resp.data) {
            all.add(am.toSyncedPlaylist())
        }
        onProgress?.invoke(all.size, all.size)  // total unknown until done
        if (resp.next == null || resp.data.size < PAGE_SIZE) break
        offset += resp.data.size
    }
    return all
}

private fun AmPlaylist.toSyncedPlaylist(): SyncedPlaylist {
    val name = attributes.name
    val desc = attributes.description?.standard ?: attributes.description?.short
    val playlistEntity = PlaylistEntity(
        id = "applemusic-$id",
        name = name,
        description = desc,
        artworkUrl = attributes.artwork?.url,
        trackCount = 0,  // populated when tracks are fetched
        createdAt = 0L,
        updatedAt = System.currentTimeMillis(),
        spotifyId = null,
        snapshotId = attributes.lastModifiedDate,
        lastModified = 0L,
        locallyModified = false,
        ownerName = null,
        sourceUrl = null,
        sourceContentHash = null,
        localOnly = false,
    )
    return SyncedPlaylist(
        entity = playlistEntity,
        spotifyId = id,  // field name is generic — holds AM library playlist ID here
        snapshotId = attributes.lastModifiedDate,
        trackCount = 0,
        isOwned = attributes.canEdit,
    )
}
```

Note: `SyncedPlaylist` was named for Spotify originally (`spotifyId` field). Either rename to `externalId` (cross-cutting refactor) or just put the AM ID in that field with a code comment. **Defer the rename** — it's a separate cleanup task. For now, the field's semantic is "the provider's external ID" regardless of the name.

### Step 4: Implement `fetchPlaylistTracks`

```kotlin
override suspend fun fetchPlaylistTracks(externalPlaylistId: String): List<PlaylistTrackEntity> {
    val all = mutableListOf<PlaylistTrackEntity>()
    var offset = 0
    while (true) {
        delay(INTER_REQUEST_DELAY_MS)
        val resp = try {
            api.listPlaylistTracks(playlistId = externalPlaylistId, limit = PAGE_SIZE, offset = offset)
        } catch (e: HttpException) {
            if (e.code() == 401) throw AppleMusicReauthRequiredException()
            throw e
        }
        for ((index, am) in resp.data.withIndex()) {
            all.add(am.toPlaylistTrack(playlistId = "applemusic-$externalPlaylistId", position = offset + index))
        }
        if (resp.next == null || resp.data.size < PAGE_SIZE) break
        offset += resp.data.size
    }
    return all
}

private fun AmTrack.toPlaylistTrack(playlistId: String, position: Int): PlaylistTrackEntity =
    PlaylistTrackEntity(
        playlistId = playlistId,
        position = position,
        trackTitle = attributes.name,
        trackArtist = attributes.artistName,
        trackAlbum = attributes.albumName,
        trackDuration = attributes.durationInMillis?.let { it / 1000 }?.toInt(),
        trackArtworkUrl = attributes.artwork?.url,
        trackSourceUrl = null,
        trackResolver = "applemusic",
        trackSpotifyUri = null,
        trackSoundcloudId = null,
        trackSpotifyId = null,
        trackAppleMusicId = attributes.playParams?.id ?: id,
    )
```

### Step 5: Implement `getPlaylistSnapshotId`

```kotlin
override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? {
    delay(INTER_REQUEST_DELAY_MS)
    val resp = try {
        api.listPlaylists(limit = 1, offset = 0)  // sub-optimal but no per-playlist GET
    } catch (e: HttpException) {
        if (e.code() == 401) throw AppleMusicReauthRequiredException()
        throw e
    }
    // Apple's API doesn't have a single-playlist endpoint for library
    // playlists. The lastModifiedDate comes from the list response.
    // For Phase 4 we just refetch the full list; if perf becomes an
    // issue we can cache or use the catalog endpoint instead.
    return resp.data.firstOrNull { it.id == externalPlaylistId }?.attributes?.lastModifiedDate
}
```

(This is admittedly inefficient for many playlists; acceptable for v1.)

### Step 6: Read-path tests with MockWebServer

```kotlin
class AppleMusicSyncProviderReadTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: AppleMusicSyncProvider

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(/* json converter */)
            .build()
            .create(AppleMusicLibraryApi::class.java)
        provider = AppleMusicSyncProvider(api, mockk(relaxed = true))
    }
    @After fun teardown() { server.shutdown() }

    @Test fun `fetchPlaylists parses single page`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"p.abc","type":"library-playlists","attributes":{"name":"Mix","canEdit":true,"lastModifiedDate":"2026-04-24T12:00:00Z"}}]}"""))
        val result = provider.fetchPlaylists(null)
        assertEquals(1, result.size)
        assertEquals("p.abc", result.first().spotifyId)
        assertEquals("2026-04-24T12:00:00Z", result.first().snapshotId)
    }

    @Test fun `fetchPlaylists pages until next is null`() = runBlocking {
        // Two-page response
        server.enqueue(MockResponse().setBody("""{"data":[<100 items>],"next":"..."}"""))
        server.enqueue(MockResponse().setBody("""{"data":[<3 items>]}"""))
        val result = provider.fetchPlaylists(null)
        assertEquals(103, result.size)
    }

    @Test fun `fetchPlaylists raises AppleMusicReauthRequiredException on 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            provider.fetchPlaylists(null)
            fail()
        } catch (_: AppleMusicReauthRequiredException) {
            // expected
        }
    }
}
```

### Step 7: Run + commit

```
./gradlew :app:testDebugUnitTest
git add app/src/main/java/com/parachord/android/sync/AppleMusicSyncProvider.kt \
        app/src/test/java/com/parachord/android/sync/AppleMusicSyncProviderReadTest.kt
git commit -m "AppleMusicSyncProvider read path: fetchPlaylists, fetchPlaylistTracks, getPlaylistSnapshotId"
```

---

## Task 8: `createPlaylist` (with optional initial tracks)

```kotlin
override suspend fun createPlaylist(name: String, description: String?): RemoteCreated {
    delay(INTER_REQUEST_DELAY_MS)
    val resp = try {
        api.createPlaylist(AmCreatePlaylistRequest(
            attributes = AmCreatePlaylistAttributes(name = name, description = description),
        ))
    } catch (e: HttpException) {
        if (e.code() == 401) throw AppleMusicReauthRequiredException()
        throw e
    }
    val created = resp.data.firstOrNull()
        ?: throw IllegalStateException("AM create returned empty data")
    return RemoteCreated(
        externalId = created.id,
        snapshotId = created.attributes.lastModifiedDate,
    )
}
```

Test with MockWebServer: 200 happy path + 401 raises reauth. Commit.

---

## Task 9: `replacePlaylistTracks` with PUT-degrades-to-POST kill-switch

```kotlin
override suspend fun replacePlaylistTracks(
    externalPlaylistId: String,
    externalTrackIds: List<String>,
): String? {
    if (externalTrackIds.isEmpty()) {
        // PUT with empty list would clear; if PUT is unsupported we
        // can't clear. Log + return without action.
        Log.w(TAG, "Empty track list for $externalPlaylistId; not pushing (PUT may be unsupported)")
        return getPlaylistSnapshotId(externalPlaylistId)
    }
    val body = AmTracksRequest(externalTrackIds.map { AmTrackReference(it, "songs") })

    if (!amPutUnsupportedForSession) {
        delay(INTER_REQUEST_DELAY_MS)
        val resp = api.replacePlaylistTracks(externalPlaylistId, body)
        if (resp.isSuccessful) {
            return getPlaylistSnapshotId(externalPlaylistId)
        }
        if (resp.code() in setOf(401, 403, 405)) {
            // Documented-unsupported. Flip kill-switch; do NOT retry.
            // Per desktop CLAUDE.md: refresh-and-retry on these endpoints
            // would escalate a benign endpoint rejection into a phantom
            // auth crisis (since the 401 is structural, not token-related).
            Log.w(TAG, "PUT replace returned ${resp.code()} for $externalPlaylistId; flipping session kill-switch, falling back to POST-append")
            amPutUnsupportedForSession = true
        } else {
            throw retrofit2.HttpException(resp)
        }
    }

    // POST-append fallback. Removals stay on the remote — accept this.
    delay(INTER_REQUEST_DELAY_MS)
    val resp = api.appendPlaylistTracks(externalPlaylistId, body)
    if (!resp.isSuccessful) throw retrofit2.HttpException(resp)
    return getPlaylistSnapshotId(externalPlaylistId)
}
```

Tests:
- PUT 204 success → returns snapshot
- PUT 401 → kill-switch flips, POST 204 → returns snapshot, NO retry on PUT
- PUT 401 + POST 401 → throws reauth
- Empty list → returns existing snapshot, no calls
- After PUT 401 in same session, next call goes straight to POST without trying PUT first

Commit.

---

## Task 10: `updatePlaylistDetails` with PATCH kill-switch (load-bearing — never throws)

```kotlin
override suspend fun updatePlaylistDetails(
    externalPlaylistId: String,
    name: String?,
    description: String?,
) {
    if (amPatchUnsupportedForSession) return
    if (name == null && description == null) return

    try {
        delay(INTER_REQUEST_DELAY_MS)
        val resp = api.updatePlaylistDetails(
            externalPlaylistId,
            AmUpdatePlaylistRequest(AmUpdatePlaylistAttributes(name = name, description = description)),
        )
        if (resp.isSuccessful) return
        if (resp.code() in setOf(401, 403, 405)) {
            Log.w(TAG, "PATCH details returned ${resp.code()} for $externalPlaylistId; flipping session kill-switch, future calls skip silently")
            amPatchUnsupportedForSession = true
            return
        }
        Log.w(TAG, "PATCH details returned ${resp.code()} for $externalPlaylistId; not retrying")
    } catch (e: Exception) {
        // **Load-bearing**: a throw here aborts the track push too
        // (this method runs first in pushPlaylist). Defense-in-depth
        // try/catch even though the function above never throws.
        Log.w(TAG, "PATCH details network error for $externalPlaylistId — silently skipping", e)
    }
}
```

Tests cover: 204 happy path, 401 flips kill-switch (no throw), subsequent call short-circuits, network error swallowed silently. Commit.

---

## Task 11: `deletePlaylist` returns `DeleteResult`

```kotlin
override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult {
    delay(INTER_REQUEST_DELAY_MS)
    return try {
        val resp = api.deletePlaylist(externalPlaylistId)
        when {
            resp.isSuccessful -> DeleteResult.Success
            resp.code() in setOf(401, 403, 405) -> DeleteResult.Unsupported(resp.code())
            else -> DeleteResult.Failed(retrofit2.HttpException(resp))
        }
    } catch (e: Exception) {
        DeleteResult.Failed(e)
    }
}
```

Test: 204 → Success; 401 → Unsupported(401); network error → Failed. Commit.

---

## Task 12: Koin registration as second `SyncProvider`

**Files:**
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt`

Wire `AppleMusicSyncProvider` as a Koin singleton, bound as `SyncProvider` so `getAll<SyncProvider>()` includes it:

```kotlin
singleOf(::AppleMusicSyncProvider) bind com.parachord.shared.sync.SyncProvider::class
```

Place next to the Spotify registration. The existing `single<List<SyncProvider>> { getAll() }` from Phase 2 picks it up automatically.

Verify: `./gradlew :app:assembleDebug` clean. Commit.

---

## Task 13: SyncEngine push loop iterates the registered providers

Today the push loop hardcodes Spotify (filter is `it.spotifyId == null && (id.startsWith("local-") || sourceUrl != null)`). Generalize:

For each registered provider in `providers`, iterate the candidate playlists and apply the existing dedup + push logic. The candidate filter changes per provider:
- Spotify: `playlist has no link to spotify yet AND (id.startsWith("local-") OR sourceUrl != null)`
- Apple Music: `playlist has no link to applemusic yet AND (id.startsWith("local-") OR sourceUrl != null OR id.startsWith("spotify-"))` — Spotify-imported playlists ARE valid push targets for AM (Phase 3's provider-scoped guard handles the `syncedFrom` case)

Implement: extract the per-provider push body into `pushPlaylistsForProvider(provider, settings, ...)`, iterate `enabledProviders`, call once per provider.

For Phase 4, "enabled" is hardcoded to `listOf(spotifyProvider)` UNLESS a feature flag is set — Phase 6 turns this into a real settings query. Add a `SettingsStore.getEnabledSyncProviders(): Set<String>` returning `setOf("spotify")` by default + an override for testing.

Tests: extend the multi-provider contract test to verify both providers get iterated when both are enabled. Commit.

---

## Task 14: Phase 4 wrap-up + plan status

- Mark Phase 4 status in parent plan
- Install + smoke test (Spotify sync still works; if AM sync is enabled via DataStore override + MUT present, AM playlists pull/push)
- Commit + push

---

## Verification for the end of Phase 4

- All Phase 1+2+3+4 tests green (~327 + ~25 new for Phase 4 = ~350 total).
- `assembleDebug` clean.
- Spotify single-provider sync still works exactly as before — observable behavior unchanged when AM is not enabled.
- With AM enabled (via DataStore override): playlists pull from AM library, locally-edited playlists push to AM, the Phase 3 propagation logic activates end-to-end.
- Documented-unsupported endpoints (PATCH/PUT/DELETE) return their respective sentinels without throwing.
- No retry-on-401 for documented-unsupported endpoints (checked in tests).

## Out of scope for Phase 4

- Library tracks/albums/artists pull + push (deferred — interface methods are no-op stubs).
- Catalog-search-based track ID resolution (deferred — push only tracks with existing `appleMusicId`).
- Storefront detection from `/me/storefront` API (use stored value or default "us"; full detection is a follow-up).
- Settings UI toggle (Phase 6).
- Sync wizard (Phase 6).
- AM-deletion-unsupported toast/banner UI (Phase 6).

## Execution

Drive directly in this session. ~14 tasks; estimated ~60–90 min wall time at the direct-execution pace. Each task has a clear test or compile boundary; commit + push between tasks.
