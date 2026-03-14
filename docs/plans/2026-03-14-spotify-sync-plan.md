# Spotify Collection Bidirectional Sync — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Port the desktop app's Collection bidirectional sync with Spotify to Android — syncing liked songs, saved albums, followed artists, and playlists between the local Room database and Spotify's library APIs.

**Architecture:** Provider-agnostic sync engine (`SyncEngine`) orchestrates diffs between remote and local data, delegating API calls to `SpotifySyncProvider`. A separate `SyncSourceEntity` table tracks provenance per item per provider, enabling multi-source deletion rules. Background sync uses both an in-app coroutine timer (15 min) and WorkManager (hourly). UI uses a 4-step bottom sheet for setup and a Settings section for ongoing management.

**Tech Stack:** Kotlin, Room (migration 6→7), Retrofit/OkHttp, Jetpack Compose + Material 3, Hilt DI, Jetpack DataStore, WorkManager, Coroutines/Flow

**Important:** The current Spotify OAuth scope (`user-read-playback-state user-modify-playback-state user-library-read`) is missing scopes needed for sync. We must add: `user-library-modify`, `user-follow-read`, `user-follow-modify`, `playlist-read-private`, `playlist-modify-public`, `playlist-modify-private`. This means existing users will need to re-authenticate.

---

## Task 1: Data Layer — New Entities, Updated Entities, Migration

**Files:**
- Create: `app/src/main/java/com/parachord/android/data/db/entity/SyncSourceEntity.kt`
- Create: `app/src/main/java/com/parachord/android/data/db/entity/ArtistEntity.kt`
- Create: `app/src/main/java/com/parachord/android/data/db/dao/SyncSourceDao.kt`
- Create: `app/src/main/java/com/parachord/android/data/db/dao/ArtistDao.kt`
- Modify: `app/src/main/java/com/parachord/android/data/db/entity/TrackEntity.kt`
- Modify: `app/src/main/java/com/parachord/android/data/db/entity/AlbumEntity.kt`
- Modify: `app/src/main/java/com/parachord/android/data/db/entity/PlaylistEntity.kt`
- Modify: `app/src/main/java/com/parachord/android/data/db/ParachordDatabase.kt`
- Modify: `app/src/main/java/com/parachord/android/app/DatabaseModule.kt`

**Step 1: Create SyncSourceEntity**

```kotlin
// app/src/main/java/com/parachord/android/data/db/entity/SyncSourceEntity.kt
package com.parachord.android.data.db.entity

import androidx.room.Entity

/**
 * Tracks which sync provider owns each Collection item.
 * An item can have multiple sync sources (e.g., synced from Spotify AND manually added).
 * The item is only deleted from Collection when ALL its sync source entries are removed.
 */
@Entity(
    tableName = "sync_sources",
    primaryKeys = ["itemId", "itemType", "providerId"],
)
data class SyncSourceEntity(
    /** ID of the item in our DB (TrackEntity.id, AlbumEntity.id, etc.) */
    val itemId: String,
    /** Type discriminator: "track", "album", "artist", "playlist" */
    val itemType: String,
    /** Sync provider: "spotify", "manual" */
    val providerId: String,
    /** The item's ID on the external provider (e.g., Spotify track ID) */
    val externalId: String? = null,
    /** When the user added this item on the provider (epoch ms) */
    val addedAt: Long = 0L,
    /** When we last synced this item (epoch ms) */
    val syncedAt: Long = System.currentTimeMillis(),
)
```

**Step 2: Create ArtistEntity**

```kotlin
// app/src/main/java/com/parachord/android/data/db/entity/ArtistEntity.kt
package com.parachord.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val imageUrl: String? = null,
    val spotifyId: String? = null,
    val genres: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)
```

**Step 3: Create SyncSourceDao**

```kotlin
// app/src/main/java/com/parachord/android/data/db/dao/SyncSourceDao.kt
package com.parachord.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.parachord.android.data.db.entity.SyncSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncSourceDao {
    @Query("SELECT * FROM sync_sources WHERE itemId = :itemId AND itemType = :itemType")
    suspend fun getByItem(itemId: String, itemType: String): List<SyncSourceEntity>

    @Query("SELECT * FROM sync_sources WHERE providerId = :providerId AND itemType = :itemType")
    suspend fun getByProvider(providerId: String, itemType: String): List<SyncSourceEntity>

    @Query("SELECT * FROM sync_sources WHERE providerId = :providerId")
    suspend fun getAllByProvider(providerId: String): List<SyncSourceEntity>

    @Query("SELECT COUNT(*) FROM sync_sources WHERE providerId = :providerId AND itemType = :itemType")
    suspend fun countByProvider(providerId: String, itemType: String): Int

    @Query("SELECT * FROM sync_sources WHERE itemId = :itemId AND itemType = :itemType AND providerId = :providerId")
    suspend fun get(itemId: String, itemType: String, providerId: String): SyncSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(syncSource: SyncSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(syncSources: List<SyncSourceEntity>)

    @Delete
    suspend fun delete(syncSource: SyncSourceEntity)

    @Query("DELETE FROM sync_sources WHERE itemId = :itemId AND itemType = :itemType AND providerId = :providerId")
    suspend fun deleteByKey(itemId: String, itemType: String, providerId: String)

    @Query("DELETE FROM sync_sources WHERE itemId = :itemId AND itemType = :itemType")
    suspend fun deleteAllForItem(itemId: String, itemType: String)

    @Query("DELETE FROM sync_sources WHERE providerId = :providerId")
    suspend fun deleteAllForProvider(providerId: String)

    @Query("SELECT * FROM sync_sources WHERE providerId = :providerId AND itemType = :itemType ORDER BY addedAt DESC LIMIT 1")
    suspend fun getMostRecentByProvider(providerId: String, itemType: String): SyncSourceEntity?
}
```

**Step 4: Create ArtistDao**

```kotlin
// app/src/main/java/com/parachord/android/data/db/dao/ArtistDao.kt
package com.parachord.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.parachord.android.data.db.entity.ArtistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists ORDER BY name ASC")
    fun getAll(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun getById(id: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE spotifyId = :spotifyId")
    suspend fun getBySpotifyId(spotifyId: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE name LIKE '%' || :query || '%'")
    fun search(query: String): Flow<List<ArtistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(artist: ArtistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(artists: List<ArtistEntity>)

    @Delete
    suspend fun delete(artist: ArtistEntity)

    @Query("DELETE FROM artists WHERE id = :id")
    suspend fun deleteById(id: String)
}
```

**Step 5: Add spotifyId to TrackEntity**

In `TrackEntity.kt`, add after line 23 (`val soundcloudId: String? = null,`):
```kotlin
    /** Raw Spotify track ID for library API calls (e.g., "6rqhFgbbKwnb9MLmUQDhG6"). */
    val spotifyId: String? = null,
```

**Step 6: Add spotifyId to AlbumEntity**

In `AlbumEntity.kt`, add after line 14 (`val addedAt: Long = System.currentTimeMillis(),`):
```kotlin
    val spotifyId: String? = null,
```

**Step 7: Add sync fields to PlaylistEntity**

In `PlaylistEntity.kt`, add after line 14 (`val updatedAt: Long = System.currentTimeMillis(),`):
```kotlin
    val spotifyId: String? = null,
    val snapshotId: String? = null,
    val lastModified: Long = System.currentTimeMillis(),
    val locallyModified: Boolean = false,
```

**Step 8: Update ParachordDatabase**

Add imports for new entities and DAOs. Add to entities array: `SyncSourceEntity::class, ArtistEntity::class`. Bump version to 7. Add abstract DAO methods. Add MIGRATION_6_7:

```kotlin
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // New sync_sources table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `sync_sources` (
                `itemId` TEXT NOT NULL,
                `itemType` TEXT NOT NULL,
                `providerId` TEXT NOT NULL,
                `externalId` TEXT,
                `addedAt` INTEGER NOT NULL DEFAULT 0,
                `syncedAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`itemId`, `itemType`, `providerId`)
            )
        """.trimIndent())

        // New artists table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `artists` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `imageUrl` TEXT,
                `spotifyId` TEXT,
                `genres` TEXT,
                `addedAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())

        // Add spotifyId to tracks
        db.execSQL("ALTER TABLE `tracks` ADD COLUMN `spotifyId` TEXT")

        // Add spotifyId to albums
        db.execSQL("ALTER TABLE `albums` ADD COLUMN `spotifyId` TEXT")

        // Add sync columns to playlists
        db.execSQL("ALTER TABLE `playlists` ADD COLUMN `spotifyId` TEXT")
        db.execSQL("ALTER TABLE `playlists` ADD COLUMN `snapshotId` TEXT")
        db.execSQL("ALTER TABLE `playlists` ADD COLUMN `lastModified` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `playlists` ADD COLUMN `locallyModified` INTEGER NOT NULL DEFAULT 0")

        // Backfill: create 'manual' sync sources for existing collection items
        db.execSQL("""
            INSERT OR IGNORE INTO sync_sources (itemId, itemType, providerId, addedAt, syncedAt)
            SELECT id, 'track', 'manual', addedAt, addedAt FROM tracks
        """.trimIndent())
        db.execSQL("""
            INSERT OR IGNORE INTO sync_sources (itemId, itemType, providerId, addedAt, syncedAt)
            SELECT id, 'album', 'manual', addedAt, addedAt FROM albums
        """.trimIndent())
    }
}
```

Add `MIGRATION_6_7` to `.addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)`.

**Step 9: Update DatabaseModule**

Add two new `@Provides` methods:
```kotlin
@Provides
fun provideSyncSourceDao(database: ParachordDatabase): SyncSourceDao = database.syncSourceDao()

@Provides
fun provideArtistDao(database: ParachordDatabase): ArtistDao = database.artistDao()
```

**Step 10: Build and verify**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 11: Commit**

```
feat: add sync data layer — SyncSourceEntity, ArtistEntity, migration 6→7
```

---

## Task 2: Spotify API — Library Endpoints

**Files:**
- Modify: `app/src/main/java/com/parachord/android/data/api/SpotifyApi.kt`

**Step 1: Add library read endpoints**

Add after the playback control section (after line 105):

```kotlin
// --- Library (Sync) ---

@GET("v1/me/tracks")
suspend fun getLikedTracks(
    @Header("Authorization") auth: String,
    @Query("limit") limit: Int = 50,
    @Query("offset") offset: Int = 0,
): SpSavedTracksResponse

@GET("v1/me/albums")
suspend fun getSavedAlbums(
    @Header("Authorization") auth: String,
    @Query("limit") limit: Int = 50,
    @Query("offset") offset: Int = 0,
): SpSavedAlbumsResponse

@GET("v1/me/following")
suspend fun getFollowedArtists(
    @Header("Authorization") auth: String,
    @Query("type") type: String = "artist",
    @Query("limit") limit: Int = 50,
    @Query("after") after: String? = null,
): SpFollowedArtistsResponse

@GET("v1/me/playlists")
suspend fun getUserPlaylists(
    @Header("Authorization") auth: String,
    @Query("limit") limit: Int = 50,
    @Query("offset") offset: Int = 0,
): SpPaginatedPlaylists

@GET("v1/playlists/{id}/tracks")
suspend fun getPlaylistTracks(
    @Header("Authorization") auth: String,
    @Path("id") playlistId: String,
    @Query("limit") limit: Int = 100,
    @Query("offset") offset: Int = 0,
): SpPlaylistTracksResponse

@GET("v1/playlists/{id}")
suspend fun getPlaylist(
    @Header("Authorization") auth: String,
    @Path("id") playlistId: String,
    @Query("fields") fields: String? = null,
): SpPlaylistFull

@GET("v1/me")
suspend fun getCurrentUser(
    @Header("Authorization") auth: String,
): SpUser
```

**Step 2: Add library write endpoints**

```kotlin
// --- Library Write (Sync push-back) ---

@PUT("v1/me/tracks")
suspend fun saveTracks(
    @Header("Authorization") auth: String,
    @Body body: SpIdsRequest,
): Response<Unit>

@HTTP(method = "DELETE", path = "v1/me/tracks", hasBody = true)
suspend fun removeTracks(
    @Header("Authorization") auth: String,
    @Body body: SpIdsRequest,
): Response<Unit>

@PUT("v1/me/albums")
suspend fun saveAlbums(
    @Header("Authorization") auth: String,
    @Body body: SpIdsRequest,
): Response<Unit>

@HTTP(method = "DELETE", path = "v1/me/albums", hasBody = true)
suspend fun removeAlbums(
    @Header("Authorization") auth: String,
    @Body body: SpIdsRequest,
): Response<Unit>

@PUT("v1/me/following")
suspend fun followArtists(
    @Header("Authorization") auth: String,
    @Query("type") type: String = "artist",
    @Body body: SpIdsRequest,
): Response<Unit>

@HTTP(method = "DELETE", path = "v1/me/following", hasBody = true)
suspend fun unfollowArtists(
    @Header("Authorization") auth: String,
    @Query("type") type: String = "artist",
    @Body body: SpIdsRequest,
): Response<Unit>

// --- Playlist Write ---

@POST("v1/users/{userId}/playlists")
suspend fun createPlaylist(
    @Header("Authorization") auth: String,
    @Path("userId") userId: String,
    @Body body: SpCreatePlaylistRequest,
): SpPlaylistFull

@PUT("v1/playlists/{id}/tracks")
suspend fun replacePlaylistTracks(
    @Header("Authorization") auth: String,
    @Path("id") playlistId: String,
    @Body body: SpUrisRequest,
): Response<SpSnapshotResponse>

@POST("v1/playlists/{id}/tracks")
suspend fun addPlaylistTracks(
    @Header("Authorization") auth: String,
    @Path("id") playlistId: String,
    @Body body: SpUrisRequest,
): Response<SpSnapshotResponse>

@PUT("v1/playlists/{id}")
suspend fun updatePlaylistDetails(
    @Header("Authorization") auth: String,
    @Path("id") playlistId: String,
    @Body body: SpUpdatePlaylistRequest,
): Response<Unit>

@HTTP(method = "DELETE", path = "v1/playlists/{id}/followers")
suspend fun unfollowPlaylist(
    @Header("Authorization") auth: String,
    @Path("id") playlistId: String,
): Response<Unit>
```

**Step 3: Add response models**

Add to the response models section:

```kotlin
// --- Library Sync response models ---

@Serializable
data class SpSavedTrack(
    @SerialName("added_at") val addedAt: String? = null,
    val track: SpTrack,
)

@Serializable
data class SpSavedTracksResponse(
    val items: List<SpSavedTrack> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 50,
    val next: String? = null,
)

@Serializable
data class SpSavedAlbum(
    @SerialName("added_at") val addedAt: String? = null,
    val album: SpAlbum,
)

@Serializable
data class SpSavedAlbumsResponse(
    val items: List<SpSavedAlbum> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 50,
    val next: String? = null,
)

@Serializable
data class SpFollowedArtistsResponse(
    val artists: SpCursorPaginated,
)

@Serializable
data class SpCursorPaginated(
    val items: List<SpArtist> = emptyList(),
    val total: Int = 0,
    val cursors: SpCursors? = null,
    val next: String? = null,
)

@Serializable
data class SpCursors(
    val after: String? = null,
)

@Serializable
data class SpPlaylistSimple(
    val id: String,
    val name: String,
    val description: String? = null,
    val images: List<SpImage> = emptyList(),
    val owner: SpUser? = null,
    @SerialName("snapshot_id") val snapshotId: String? = null,
    val tracks: SpPlaylistTracksRef? = null,
)

@Serializable
data class SpPlaylistTracksRef(
    val total: Int = 0,
)

@Serializable
data class SpPaginatedPlaylists(
    val items: List<SpPlaylistSimple> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 50,
    val next: String? = null,
)

@Serializable
data class SpPlaylistTrackItem(
    @SerialName("added_at") val addedAt: String? = null,
    val track: SpTrack? = null,
)

@Serializable
data class SpPlaylistTracksResponse(
    val items: List<SpPlaylistTrackItem> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 100,
    val next: String? = null,
)

@Serializable
data class SpPlaylistFull(
    val id: String,
    val name: String,
    val description: String? = null,
    val images: List<SpImage> = emptyList(),
    val owner: SpUser? = null,
    @SerialName("snapshot_id") val snapshotId: String? = null,
    val tracks: SpPlaylistTracksResponse? = null,
)

@Serializable
data class SpUser(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
)

// --- Library Write request models ---

@Serializable
data class SpIdsRequest(
    val ids: List<String>,
)

@Serializable
data class SpUrisRequest(
    val uris: List<String>,
)

@Serializable
data class SpCreatePlaylistRequest(
    val name: String,
    val description: String? = null,
    val public: Boolean = false,
)

@Serializable
data class SpUpdatePlaylistRequest(
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class SpSnapshotResponse(
    @SerialName("snapshot_id") val snapshotId: String,
)
```

**Step 4: Add `@HTTP` import**

Add to imports: `import retrofit2.http.HTTP` and `import retrofit2.http.POST`

**Step 5: Build and verify**

Run: `./gradlew assembleDebug`

**Step 6: Commit**

```
feat: add Spotify library & playlist sync API endpoints
```

---

## Task 3: OAuth Scope Update

**Files:**
- Modify: `app/src/main/java/com/parachord/android/auth/OAuthManager.kt`

**Step 1: Update Spotify OAuth scopes**

In `OAuthManager.kt` line 118, replace the scope string:

Old:
```kotlin
.appendQueryParameter("scope", "user-read-playback-state user-modify-playback-state user-library-read")
```

New:
```kotlin
.appendQueryParameter("scope", listOf(
    "user-read-playback-state",
    "user-modify-playback-state",
    "user-library-read",
    "user-library-modify",
    "user-follow-read",
    "user-follow-modify",
    "playlist-read-private",
    "playlist-modify-public",
    "playlist-modify-private",
).joinToString(" "))
```

**Step 2: Build and verify**

Run: `./gradlew assembleDebug`

**Step 3: Commit**

```
feat: add Spotify OAuth scopes for library sync
```

---

## Task 4: Sync Settings in SettingsStore

**Files:**
- Modify: `app/src/main/java/com/parachord/android/data/store/SettingsStore.kt`

**Step 1: Add sync preference keys**

Add to the companion object after line 49 (`val GEMINI_MODEL`):

```kotlin
val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
val SYNC_PROVIDER = stringPreferencesKey("sync_provider")
val SYNC_TRACKS = booleanPreferencesKey("sync_tracks")
val SYNC_ALBUMS = booleanPreferencesKey("sync_albums")
val SYNC_ARTISTS = booleanPreferencesKey("sync_artists")
val SYNC_PLAYLISTS = booleanPreferencesKey("sync_playlists")
val SYNC_SELECTED_PLAYLIST_IDS = stringPreferencesKey("sync_selected_playlist_ids")
val SYNC_LAST_COMPLETED_AT = stringPreferencesKey("sync_last_completed_at")
val SYNC_PUSH_LOCAL_PLAYLISTS = booleanPreferencesKey("sync_push_local_playlists")
```

**Step 2: Add sync settings accessors**

Add after the AI Providers section (after line 431):

```kotlin
// --- Sync Settings ---

data class SyncSettings(
    val enabled: Boolean = false,
    val provider: String = "spotify",
    val syncTracks: Boolean = true,
    val syncAlbums: Boolean = true,
    val syncArtists: Boolean = true,
    val syncPlaylists: Boolean = true,
    val selectedPlaylistIds: Set<String> = emptySet(),
    val pushLocalPlaylists: Boolean = true,
)

val syncEnabledFlow: Flow<Boolean> = dataStore.data.map { it[SYNC_ENABLED] ?: false }

val lastSyncAtFlow: Flow<Long> = dataStore.data.map {
    it[SYNC_LAST_COMPLETED_AT]?.toLongOrNull() ?: 0L
}

fun getSyncSettingsFlow(): Flow<SyncSettings> = dataStore.data.map { prefs ->
    SyncSettings(
        enabled = prefs[SYNC_ENABLED] ?: false,
        provider = prefs[SYNC_PROVIDER] ?: "spotify",
        syncTracks = prefs[SYNC_TRACKS] ?: true,
        syncAlbums = prefs[SYNC_ALBUMS] ?: true,
        syncArtists = prefs[SYNC_ARTISTS] ?: true,
        syncPlaylists = prefs[SYNC_PLAYLISTS] ?: true,
        selectedPlaylistIds = (prefs[SYNC_SELECTED_PLAYLIST_IDS] ?: "")
            .split(",").filter { it.isNotBlank() }.toSet(),
        pushLocalPlaylists = prefs[SYNC_PUSH_LOCAL_PLAYLISTS] ?: true,
    )
}

suspend fun getSyncSettings(): SyncSettings {
    val prefs = dataStore.data.first()
    return SyncSettings(
        enabled = prefs[SYNC_ENABLED] ?: false,
        provider = prefs[SYNC_PROVIDER] ?: "spotify",
        syncTracks = prefs[SYNC_TRACKS] ?: true,
        syncAlbums = prefs[SYNC_ALBUMS] ?: true,
        syncArtists = prefs[SYNC_ARTISTS] ?: true,
        syncPlaylists = prefs[SYNC_PLAYLISTS] ?: true,
        selectedPlaylistIds = (prefs[SYNC_SELECTED_PLAYLIST_IDS] ?: "")
            .split(",").filter { it.isNotBlank() }.toSet(),
        pushLocalPlaylists = prefs[SYNC_PUSH_LOCAL_PLAYLISTS] ?: true,
    )
}

suspend fun saveSyncSettings(settings: SyncSettings) {
    dataStore.edit { prefs ->
        prefs[SYNC_ENABLED] = settings.enabled
        prefs[SYNC_PROVIDER] = settings.provider
        prefs[SYNC_TRACKS] = settings.syncTracks
        prefs[SYNC_ALBUMS] = settings.syncAlbums
        prefs[SYNC_ARTISTS] = settings.syncArtists
        prefs[SYNC_PLAYLISTS] = settings.syncPlaylists
        prefs[SYNC_SELECTED_PLAYLIST_IDS] = settings.selectedPlaylistIds.joinToString(",")
        prefs[SYNC_PUSH_LOCAL_PLAYLISTS] = settings.pushLocalPlaylists
    }
}

suspend fun setSyncEnabled(enabled: Boolean) {
    dataStore.edit { it[SYNC_ENABLED] = enabled }
}

suspend fun setLastSyncAt(timestamp: Long) {
    dataStore.edit { it[SYNC_LAST_COMPLETED_AT] = timestamp.toString() }
}

suspend fun clearSyncSettings() {
    dataStore.edit { prefs ->
        prefs.remove(SYNC_ENABLED)
        prefs.remove(SYNC_PROVIDER)
        prefs.remove(SYNC_TRACKS)
        prefs.remove(SYNC_ALBUMS)
        prefs.remove(SYNC_ARTISTS)
        prefs.remove(SYNC_PLAYLISTS)
        prefs.remove(SYNC_SELECTED_PLAYLIST_IDS)
        prefs.remove(SYNC_LAST_COMPLETED_AT)
        prefs.remove(SYNC_PUSH_LOCAL_PLAYLISTS)
    }
}
```

**Step 3: Build and verify**

Run: `./gradlew assembleDebug`

**Step 4: Commit**

```
feat: add sync settings to SettingsStore
```

---

## Task 5: SpotifySyncProvider

**Files:**
- Create: `app/src/main/java/com/parachord/android/sync/SpotifySyncProvider.kt`

**Step 1: Create the provider**

This class wraps all Spotify library API calls with pagination, batching, retry logic, and progress reporting. It converts Spotify API models to our local entity models.

```kotlin
// app/src/main/java/com/parachord/android/sync/SpotifySyncProvider.kt
package com.parachord.android.sync

import android.util.Log
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.api.*
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.ArtistEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spotify-specific sync provider. Handles all Spotify library API calls
 * with pagination, batching, retry, and progress reporting.
 * Matches the desktop's sync-providers/spotify.js.
 */
@Singleton
class SpotifySyncProvider @Inject constructor(
    private val spotifyApi: SpotifyApi,
    private val settingsStore: SettingsStore,
    private val oAuthManager: OAuthManager,
) {
    companion object {
        private const val TAG = "SpotifySyncProvider"
        const val PROVIDER_ID = "spotify"
        private const val BATCH_SIZE = 50
        private const val PLAYLIST_TRACK_BATCH_SIZE = 100
        private const val MAX_RETRIES = 5
    }

    data class SyncedTrack(
        val entity: TrackEntity,
        val spotifyId: String,
        val addedAt: Long,
    )

    data class SyncedAlbum(
        val entity: AlbumEntity,
        val spotifyId: String,
        val addedAt: Long,
    )

    data class SyncedArtist(
        val entity: ArtistEntity,
        val spotifyId: String,
    )

    data class SyncedPlaylist(
        val entity: PlaylistEntity,
        val spotifyId: String,
        val snapshotId: String?,
        val trackCount: Int,
        val isOwned: Boolean,
    )

    private suspend fun auth(): String = "Bearer ${settingsStore.getSpotifyAccessToken() ?: ""}"

    /**
     * Execute a Spotify API call with automatic token refresh on 401,
     * rate-limit handling on 429, and exponential backoff on server errors.
     */
    private suspend fun <T> withRetry(block: suspend (auth: String) -> T): T {
        var retries = 0
        while (true) {
            try {
                return block(auth())
            } catch (e: retrofit2.HttpException) {
                when (e.code()) {
                    401 -> {
                        if (retries > 0) throw e
                        Log.d(TAG, "Token expired, refreshing...")
                        if (!oAuthManager.refreshSpotifyToken()) throw e
                        retries++
                    }
                    429 -> {
                        val retryAfter = e.response()?.headers()?.get("Retry-After")?.toLongOrNull() ?: 1
                        if (retries >= MAX_RETRIES) throw e
                        Log.d(TAG, "Rate limited, waiting ${retryAfter}s")
                        delay(retryAfter * 1000)
                        retries++
                    }
                    in 500..599 -> {
                        if (retries >= MAX_RETRIES) throw e
                        val backoff = (1L shl retries) * 1000
                        Log.d(TAG, "Server error ${e.code()}, backoff ${backoff}ms")
                        delay(backoff)
                        retries++
                    }
                    else -> throw e
                }
            }
        }
    }

    private fun parseIsoTimestamp(iso: String?): Long {
        if (iso == null) return System.currentTimeMillis()
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    // ── Fetch operations ─────────────────────────────────────────

    /**
     * Fetch all liked tracks. Returns null if nothing changed (cache check).
     * @param localCount count of locally synced tracks from this provider
     * @param latestExternalId most recently added track's Spotify ID (for cache check)
     */
    suspend fun fetchTracks(
        localCount: Int = 0,
        latestExternalId: String? = null,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SyncedTrack>? {
        // Quick cache check: fetch 1 item to compare count + latest
        val probe = withRetry { spotifyApi.getLikedTracks(it, limit = 1) }
        if (probe.total == localCount && probe.items.firstOrNull()?.track?.id == latestExternalId) {
            Log.d(TAG, "Tracks unchanged (count=$localCount), skipping full fetch")
            return null
        }

        val all = mutableListOf<SyncedTrack>()
        var offset = 0
        val total = probe.total
        onProgress(0, total)

        while (offset < total) {
            val page = withRetry { spotifyApi.getLikedTracks(it, limit = BATCH_SIZE, offset = offset) }
            page.items.forEach { saved ->
                val track = saved.track
                all.add(SyncedTrack(
                    entity = TrackEntity(
                        id = "spotify-${track.id}",
                        title = track.name,
                        artist = track.artistName,
                        album = track.album?.name,
                        albumId = track.album?.id?.let { "spotify-$it" },
                        duration = track.durationMs,
                        artworkUrl = track.album?.images?.bestImageUrl(),
                        spotifyUri = "spotify:track:${track.id}",
                        spotifyId = track.id,
                        resolver = "spotify",
                        sourceType = "synced",
                    ),
                    spotifyId = track.id,
                    addedAt = parseIsoTimestamp(saved.addedAt),
                ))
            }
            offset += BATCH_SIZE
            onProgress(all.size, total)
        }

        return all
    }

    suspend fun fetchAlbums(
        localCount: Int = 0,
        latestExternalId: String? = null,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SyncedAlbum>? {
        val probe = withRetry { spotifyApi.getSavedAlbums(it, limit = 1) }
        if (probe.total == localCount && probe.items.firstOrNull()?.album?.id == latestExternalId) {
            return null
        }

        val all = mutableListOf<SyncedAlbum>()
        var offset = 0
        val total = probe.total

        while (offset < total) {
            val page = withRetry { spotifyApi.getSavedAlbums(it, limit = BATCH_SIZE, offset = offset) }
            page.items.forEach { saved ->
                val album = saved.album
                all.add(SyncedAlbum(
                    entity = AlbumEntity(
                        id = "spotify-${album.id}",
                        title = album.name,
                        artist = album.artistName,
                        artworkUrl = album.images.bestImageUrl(),
                        year = album.year,
                        trackCount = album.totalTracks,
                        spotifyId = album.id,
                    ),
                    spotifyId = album.id,
                    addedAt = parseIsoTimestamp(saved.addedAt),
                ))
            }
            offset += BATCH_SIZE
            onProgress(all.size, total)
        }

        return all
    }

    suspend fun fetchArtists(
        localCount: Int = 0,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SyncedArtist>? {
        // Artists use cursor-based pagination, no quick cache check available
        val all = mutableListOf<SyncedArtist>()
        var after: String? = null
        var total = 0

        do {
            val response = withRetry { spotifyApi.getFollowedArtists(it, after = after) }
            total = response.artists.total
            response.artists.items.forEach { artist ->
                all.add(SyncedArtist(
                    entity = ArtistEntity(
                        id = "spotify-${artist.id}",
                        name = artist.name,
                        imageUrl = artist.images.bestImageUrl(),
                        spotifyId = artist.id,
                        genres = artist.genres.joinToString(","),
                    ),
                    spotifyId = artist.id,
                ))
            }
            after = response.artists.cursors?.after
            onProgress(all.size, total)
        } while (after != null)

        if (all.size == localCount) return null
        return all
    }

    suspend fun fetchPlaylists(
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SyncedPlaylist> {
        val currentUser = withRetry { spotifyApi.getCurrentUser(it) }
        val all = mutableListOf<SyncedPlaylist>()
        val seen = mutableSetOf<String>()
        var offset = 0

        val probe = withRetry { spotifyApi.getUserPlaylists(it, limit = 1) }
        val total = probe.total

        while (offset < total) {
            val page = withRetry { spotifyApi.getUserPlaylists(it, limit = BATCH_SIZE, offset = offset) }
            page.items.forEach { playlist ->
                if (seen.add(playlist.id)) { // Deduplicate
                    all.add(SyncedPlaylist(
                        entity = PlaylistEntity(
                            id = "spotify-${playlist.id}",
                            name = playlist.name,
                            description = playlist.description,
                            artworkUrl = playlist.images.bestImageUrl(),
                            trackCount = playlist.tracks?.total ?: 0,
                            spotifyId = playlist.id,
                            snapshotId = playlist.snapshotId,
                        ),
                        spotifyId = playlist.id,
                        snapshotId = playlist.snapshotId,
                        trackCount = playlist.tracks?.total ?: 0,
                        isOwned = playlist.owner?.id == currentUser.id,
                    ))
                }
            }
            offset += BATCH_SIZE
            onProgress(all.size, total)
        }

        return all
    }

    suspend fun fetchPlaylistTracks(
        spotifyPlaylistId: String,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): List<PlaylistTrackEntity> {
        val all = mutableListOf<PlaylistTrackEntity>()
        var offset = 0
        val localPlaylistId = "spotify-$spotifyPlaylistId"

        val probe = withRetry { spotifyApi.getPlaylistTracks(it, spotifyPlaylistId, limit = 1) }
        val total = probe.total

        while (offset < total) {
            val page = withRetry {
                spotifyApi.getPlaylistTracks(it, spotifyPlaylistId, limit = PLAYLIST_TRACK_BATCH_SIZE, offset = offset)
            }
            page.items.forEach { item ->
                val track = item.track ?: return@forEach // Skip null (deleted) tracks
                all.add(PlaylistTrackEntity(
                    playlistId = localPlaylistId,
                    position = all.size,
                    trackTitle = track.name,
                    trackArtist = track.artistName,
                    trackAlbum = track.album?.name,
                    trackDuration = track.durationMs,
                    trackArtworkUrl = track.album?.images?.bestImageUrl(),
                    trackResolver = "spotify",
                    trackSpotifyUri = "spotify:track:${track.id}",
                    addedAt = parseIsoTimestamp(item.addedAt),
                ))
            }
            offset += PLAYLIST_TRACK_BATCH_SIZE
            onProgress(all.size, total)
        }

        return all
    }

    suspend fun getPlaylistSnapshotId(spotifyPlaylistId: String): String? {
        return try {
            val playlist = withRetry {
                spotifyApi.getPlaylist(it, spotifyPlaylistId, fields = "snapshot_id")
            }
            playlist.snapshotId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get snapshot for $spotifyPlaylistId", e)
            null
        }
    }

    // ── Write operations ─────────────────────────────────────────

    suspend fun saveTracks(spotifyIds: List<String>) {
        spotifyIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyApi.saveTracks(it, SpIdsRequest(batch)) }
        }
    }

    suspend fun removeTracks(spotifyIds: List<String>) {
        spotifyIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyApi.removeTracks(it, SpIdsRequest(batch)) }
        }
    }

    suspend fun saveAlbums(spotifyIds: List<String>) {
        spotifyIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyApi.saveAlbums(it, SpIdsRequest(batch)) }
        }
    }

    suspend fun removeAlbums(spotifyIds: List<String>) {
        spotifyIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyApi.removeAlbums(it, SpIdsRequest(batch)) }
        }
    }

    suspend fun followArtists(spotifyIds: List<String>) {
        spotifyIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyApi.followArtists(it, body = SpIdsRequest(batch)) }
        }
    }

    suspend fun unfollowArtists(spotifyIds: List<String>) {
        spotifyIds.chunked(BATCH_SIZE).forEach { batch ->
            withRetry { spotifyApi.unfollowArtists(it, body = SpIdsRequest(batch)) }
        }
    }

    /** Create a new playlist on Spotify. Returns the Spotify playlist ID. */
    suspend fun createPlaylistOnSpotify(name: String, description: String? = null): SpPlaylistFull {
        val user = withRetry { spotifyApi.getCurrentUser(it) }
        return withRetry {
            spotifyApi.createPlaylist(it, user.id, SpCreatePlaylistRequest(name, description))
        }
    }

    /** Replace all tracks in a Spotify playlist. Returns snapshot ID. */
    suspend fun replacePlaylistTracks(spotifyPlaylistId: String, spotifyUris: List<String>): String? {
        if (spotifyUris.isEmpty()) {
            // Clear the playlist
            withRetry { spotifyApi.replacePlaylistTracks(it, spotifyPlaylistId, SpUrisRequest(emptyList())) }
            return null
        }

        val chunks = spotifyUris.chunked(PLAYLIST_TRACK_BATCH_SIZE)
        var snapshotId: String? = null

        chunks.forEachIndexed { index, chunk ->
            val response = if (index == 0) {
                withRetry { spotifyApi.replacePlaylistTracks(it, spotifyPlaylistId, SpUrisRequest(chunk)) }
            } else {
                withRetry { spotifyApi.addPlaylistTracks(it, spotifyPlaylistId, SpUrisRequest(chunk)) }
            }
            if (response.isSuccessful) {
                snapshotId = response.body()?.snapshotId
            }
        }

        return snapshotId
    }

    suspend fun updatePlaylistDetails(spotifyPlaylistId: String, name: String?, description: String?) {
        withRetry {
            spotifyApi.updatePlaylistDetails(it, spotifyPlaylistId, SpUpdatePlaylistRequest(name, description))
        }
    }

    suspend fun deletePlaylist(spotifyPlaylistId: String) {
        withRetry { spotifyApi.unfollowPlaylist(it, spotifyPlaylistId) }
    }
}
```

**Step 2: Build and verify**

Run: `./gradlew assembleDebug`

**Step 3: Commit**

```
feat: add SpotifySyncProvider with paginated fetch, batch writes, retry logic
```

---

## Task 6: SyncEngine

**Files:**
- Create: `app/src/main/java/com/parachord/android/sync/SyncEngine.kt`

**Step 1: Create the sync engine**

```kotlin
// app/src/main/java/com/parachord/android/sync/SyncEngine.kt
package com.parachord.android.sync

import android.util.Log
import com.parachord.android.data.db.dao.*
import com.parachord.android.data.db.entity.*
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncEngine @Inject constructor(
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val syncSourceDao: SyncSourceDao,
    private val settingsStore: SettingsStore,
    private val spotifyProvider: SpotifySyncProvider,
) {
    companion object {
        private const val TAG = "SyncEngine"
        private const val MASS_REMOVAL_THRESHOLD_PERCENT = 0.25
        private const val MASS_REMOVAL_THRESHOLD_COUNT = 50
    }

    /** Prevents concurrent syncs from in-app timer and WorkManager. */
    private val syncMutex = Mutex()

    data class TypeSyncResult(
        val added: Int = 0,
        val removed: Int = 0,
        val updated: Int = 0,
        val unchanged: Int = 0,
    )

    data class FullSyncResult(
        val tracks: TypeSyncResult = TypeSyncResult(),
        val albums: TypeSyncResult = TypeSyncResult(),
        val artists: TypeSyncResult = TypeSyncResult(),
        val playlists: TypeSyncResult = TypeSyncResult(),
        val success: Boolean = true,
        val error: String? = null,
    )

    enum class SyncPhase {
        TRACKS, ALBUMS, ARTISTS, PLAYLISTS, COMPLETE
    }

    data class SyncProgress(
        val phase: SyncPhase,
        val current: Int = 0,
        val total: Int = 0,
        val message: String = "",
    )

    /**
     * Run a full sync. This is the main entry point.
     * Acquires syncMutex to prevent concurrent runs.
     */
    suspend fun syncAll(
        onProgress: (SyncProgress) -> Unit = {},
    ): FullSyncResult = syncMutex.withLock {
        val settings = settingsStore.getSyncSettings()
        if (!settings.enabled) {
            return FullSyncResult(success = false, error = "Sync not enabled")
        }

        try {
            var trackResult = TypeSyncResult()
            var albumResult = TypeSyncResult()
            var artistResult = TypeSyncResult()
            var playlistResult = TypeSyncResult()

            if (settings.syncTracks) {
                onProgress(SyncProgress(SyncPhase.TRACKS, message = "Syncing liked songs..."))
                trackResult = syncTracks(onProgress)
            }

            if (settings.syncAlbums) {
                onProgress(SyncProgress(SyncPhase.ALBUMS, message = "Syncing saved albums..."))
                albumResult = syncAlbums(onProgress)
            }

            if (settings.syncArtists) {
                onProgress(SyncProgress(SyncPhase.ARTISTS, message = "Syncing followed artists..."))
                artistResult = syncArtists(onProgress)
            }

            if (settings.syncPlaylists) {
                onProgress(SyncProgress(SyncPhase.PLAYLISTS, message = "Syncing playlists..."))
                playlistResult = syncPlaylists(settings, onProgress)
            }

            settingsStore.setLastSyncAt(System.currentTimeMillis())

            val result = FullSyncResult(
                tracks = trackResult,
                albums = albumResult,
                artists = artistResult,
                playlists = playlistResult,
            )
            onProgress(SyncProgress(SyncPhase.COMPLETE, message = "Sync complete"))
            Log.d(TAG, "Sync complete: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            FullSyncResult(success = false, error = e.message)
        }
    }

    // ── Track sync ───────────────────────────────────────────────

    private suspend fun syncTracks(
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = SpotifySyncProvider.PROVIDER_ID
        val localSources = syncSourceDao.getByProvider(providerId, "track")
        val localCount = localSources.size
        val latest = syncSourceDao.getMostRecentByProvider(providerId, "track")

        val remote = spotifyProvider.fetchTracks(
            localCount = localCount,
            latestExternalId = latest?.externalId,
            onProgress = { current, total ->
                onProgress(SyncProgress(SyncPhase.TRACKS, current, total, "Syncing liked songs..."))
            },
        ) ?: return TypeSyncResult(unchanged = localCount) // Nothing changed

        return applyTrackDiff(remote, localSources, providerId)
    }

    private suspend fun applyTrackDiff(
        remote: List<SpotifySyncProvider.SyncedTrack>,
        localSources: List<SyncSourceEntity>,
        providerId: String,
    ): TypeSyncResult {
        val remoteByExternalId = remote.associateBy { it.spotifyId }
        val localByExternalId = localSources.associateBy { it.externalId }

        val toAdd = remote.filter { it.spotifyId !in localByExternalId }
        var toRemove = localSources.filter { it.externalId !in remoteByExternalId }
        val toUpdate = remote.filter { synced ->
            synced.spotifyId in localByExternalId
        }

        // Mass removal safeguard
        if (toRemove.size > localSources.size * MASS_REMOVAL_THRESHOLD_PERCENT
            && toRemove.size > MASS_REMOVAL_THRESHOLD_COUNT
        ) {
            Log.w(TAG, "⚠️ Mass removal safeguard: would remove ${toRemove.size}/${localSources.size} tracks, skipping removals")
            toRemove = emptyList()
        }

        // Apply adds
        toAdd.forEach { synced ->
            trackDao.insert(synced.entity)
            syncSourceDao.insert(SyncSourceEntity(
                itemId = synced.entity.id,
                itemType = "track",
                providerId = providerId,
                externalId = synced.spotifyId,
                addedAt = synced.addedAt,
                syncedAt = System.currentTimeMillis(),
            ))
        }

        // Apply removals
        toRemove.forEach { source ->
            syncSourceDao.deleteByKey(source.itemId, "track", providerId)
            // Only delete the item if no other sync sources remain
            val remaining = syncSourceDao.getByItem(source.itemId, "track")
            if (remaining.isEmpty()) {
                trackDao.getById(source.itemId)?.let { trackDao.delete(it) }
            }
        }

        // Update syncedAt for unchanged items
        toUpdate.forEach { synced ->
            val existing = localByExternalId[synced.spotifyId] ?: return@forEach
            syncSourceDao.insert(existing.copy(syncedAt = System.currentTimeMillis()))
        }

        return TypeSyncResult(
            added = toAdd.size,
            removed = toRemove.size,
            updated = 0,
            unchanged = toUpdate.size,
        )
    }

    // ── Album sync ───────────────────────────────────────────────

    private suspend fun syncAlbums(
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = SpotifySyncProvider.PROVIDER_ID
        val localSources = syncSourceDao.getByProvider(providerId, "album")
        val localCount = localSources.size
        val latest = syncSourceDao.getMostRecentByProvider(providerId, "album")

        val remote = spotifyProvider.fetchAlbums(
            localCount = localCount,
            latestExternalId = latest?.externalId,
            onProgress = { current, total ->
                onProgress(SyncProgress(SyncPhase.ALBUMS, current, total, "Syncing saved albums..."))
            },
        ) ?: return TypeSyncResult(unchanged = localCount)

        return applyAlbumDiff(remote, localSources, providerId)
    }

    private suspend fun applyAlbumDiff(
        remote: List<SpotifySyncProvider.SyncedAlbum>,
        localSources: List<SyncSourceEntity>,
        providerId: String,
    ): TypeSyncResult {
        val remoteByExternalId = remote.associateBy { it.spotifyId }
        val localByExternalId = localSources.associateBy { it.externalId }

        val toAdd = remote.filter { it.spotifyId !in localByExternalId }
        var toRemove = localSources.filter { it.externalId !in remoteByExternalId }
        val unchanged = remote.filter { it.spotifyId in localByExternalId }

        if (toRemove.size > localSources.size * MASS_REMOVAL_THRESHOLD_PERCENT
            && toRemove.size > MASS_REMOVAL_THRESHOLD_COUNT
        ) {
            Log.w(TAG, "⚠️ Mass removal safeguard: albums, skipping removals")
            toRemove = emptyList()
        }

        toAdd.forEach { synced ->
            albumDao.insert(synced.entity)
            syncSourceDao.insert(SyncSourceEntity(
                itemId = synced.entity.id,
                itemType = "album",
                providerId = providerId,
                externalId = synced.spotifyId,
                addedAt = synced.addedAt,
                syncedAt = System.currentTimeMillis(),
            ))
        }

        toRemove.forEach { source ->
            syncSourceDao.deleteByKey(source.itemId, "album", providerId)
            val remaining = syncSourceDao.getByItem(source.itemId, "album")
            if (remaining.isEmpty()) {
                albumDao.getById(source.itemId)?.let { albumDao.delete(it) }
            }
        }

        unchanged.forEach { synced ->
            val existing = localByExternalId[synced.spotifyId] ?: return@forEach
            syncSourceDao.insert(existing.copy(syncedAt = System.currentTimeMillis()))
        }

        return TypeSyncResult(added = toAdd.size, removed = toRemove.size, unchanged = unchanged.size)
    }

    // ── Artist sync ──────────────────────────────────────────────

    private suspend fun syncArtists(
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = SpotifySyncProvider.PROVIDER_ID
        val localSources = syncSourceDao.getByProvider(providerId, "artist")
        val localCount = localSources.size

        val remote = spotifyProvider.fetchArtists(
            localCount = localCount,
            onProgress = { current, total ->
                onProgress(SyncProgress(SyncPhase.ARTISTS, current, total, "Syncing followed artists..."))
            },
        ) ?: return TypeSyncResult(unchanged = localCount)

        val remoteByExternalId = remote.associateBy { it.spotifyId }
        val localByExternalId = localSources.associateBy { it.externalId }

        val toAdd = remote.filter { it.spotifyId !in localByExternalId }
        var toRemove = localSources.filter { it.externalId !in remoteByExternalId }
        val unchanged = remote.filter { it.spotifyId in localByExternalId }

        if (toRemove.size > localSources.size * MASS_REMOVAL_THRESHOLD_PERCENT
            && toRemove.size > MASS_REMOVAL_THRESHOLD_COUNT
        ) {
            Log.w(TAG, "⚠️ Mass removal safeguard: artists, skipping removals")
            toRemove = emptyList()
        }

        toAdd.forEach { synced ->
            artistDao.insert(synced.entity)
            syncSourceDao.insert(SyncSourceEntity(
                itemId = synced.entity.id,
                itemType = "artist",
                providerId = providerId,
                externalId = synced.spotifyId,
                syncedAt = System.currentTimeMillis(),
            ))
        }

        toRemove.forEach { source ->
            syncSourceDao.deleteByKey(source.itemId, "artist", providerId)
            val remaining = syncSourceDao.getByItem(source.itemId, "artist")
            if (remaining.isEmpty()) {
                artistDao.deleteById(source.itemId)
            }
        }

        unchanged.forEach { synced ->
            val existing = localByExternalId[synced.spotifyId] ?: return@forEach
            syncSourceDao.insert(existing.copy(syncedAt = System.currentTimeMillis()))
        }

        return TypeSyncResult(added = toAdd.size, removed = toRemove.size, unchanged = unchanged.size)
    }

    // ── Playlist sync ────────────────────────────────────────────

    private suspend fun syncPlaylists(
        settings: SettingsStore.SyncSettings,
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = SpotifySyncProvider.PROVIDER_ID
        var added = 0
        var removed = 0
        var updated = 0
        var unchanged = 0

        // 1. Fetch remote playlists
        val remotePlaylists = spotifyProvider.fetchPlaylists { current, total ->
            onProgress(SyncProgress(SyncPhase.PLAYLISTS, current, total, "Fetching playlists..."))
        }

        // Filter to selected playlists (if user chose specific ones)
        val selectedRemote = if (settings.selectedPlaylistIds.isEmpty()) {
            remotePlaylists
        } else {
            remotePlaylists.filter { it.spotifyId in settings.selectedPlaylistIds }
        }

        val localSources = syncSourceDao.getByProvider(providerId, "playlist")
        val localByExternalId = localSources.associateBy { it.externalId }

        // 2. Pull remote playlists → local
        for (remote in selectedRemote) {
            val existingSource = localByExternalId[remote.spotifyId]

            if (existingSource == null) {
                // New playlist from Spotify
                playlistDao.insert(remote.entity)
                // Fetch and store tracks
                val tracks = spotifyProvider.fetchPlaylistTracks(remote.spotifyId)
                playlistTrackDao.deleteByPlaylistId(remote.entity.id)
                playlistTrackDao.insertAll(tracks)
                syncSourceDao.insert(SyncSourceEntity(
                    itemId = remote.entity.id,
                    itemType = "playlist",
                    providerId = providerId,
                    externalId = remote.spotifyId,
                    syncedAt = System.currentTimeMillis(),
                ))
                added++
            } else {
                // Existing synced playlist — check for changes
                val localPlaylist = playlistDao.getById(existingSource.itemId)
                if (localPlaylist == null) {
                    unchanged++
                    continue
                }

                val remoteSnapshotId = remote.snapshotId
                val localSnapshotId = localPlaylist.snapshotId
                val localModified = localPlaylist.locallyModified

                when {
                    localModified && remoteSnapshotId != localSnapshotId -> {
                        // Conflict — newer wins
                        val localIsNewer = localPlaylist.lastModified > existingSource.syncedAt
                        if (localIsNewer) {
                            pushPlaylist(localPlaylist)
                        } else {
                            pullPlaylist(localPlaylist, remote)
                        }
                        updated++
                    }
                    localModified -> {
                        // Push local changes
                        pushPlaylist(localPlaylist)
                        updated++
                    }
                    remoteSnapshotId != localSnapshotId -> {
                        // Pull remote changes
                        pullPlaylist(localPlaylist, remote)
                        updated++
                    }
                    else -> {
                        // No changes
                        syncSourceDao.insert(existingSource.copy(syncedAt = System.currentTimeMillis()))
                        unchanged++
                    }
                }
            }
        }

        // 3. Push local-only playlists to Spotify
        if (settings.pushLocalPlaylists) {
            val allPlaylists = playlistDao.getAllSync()
            val localOnly = allPlaylists.filter { it.spotifyId == null }

            for (playlist in localOnly) {
                try {
                    val created = spotifyProvider.createPlaylistOnSpotify(
                        playlist.name, playlist.description
                    )
                    // Push tracks
                    val tracks = playlistTrackDao.getByPlaylistIdSync(playlist.id)
                    val uris = tracks.mapNotNull { it.trackSpotifyUri }
                    if (uris.isNotEmpty()) {
                        spotifyProvider.replacePlaylistTracks(created.id, uris)
                    }
                    // Update local playlist with Spotify ID
                    playlistDao.insert(playlist.copy(
                        spotifyId = created.id,
                        snapshotId = created.snapshotId,
                        locallyModified = false,
                    ))
                    syncSourceDao.insert(SyncSourceEntity(
                        itemId = playlist.id,
                        itemType = "playlist",
                        providerId = providerId,
                        externalId = created.id,
                        syncedAt = System.currentTimeMillis(),
                    ))
                    added++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to push playlist ${playlist.name} to Spotify", e)
                }
            }
        }

        // 4. Handle remote removals (playlists that were synced but no longer on Spotify)
        val remoteIds = selectedRemote.map { it.spotifyId }.toSet()
        val removedSources = localSources.filter { it.externalId != null && it.externalId !in remoteIds }
        removedSources.forEach { source ->
            syncSourceDao.deleteByKey(source.itemId, "playlist", providerId)
            val remaining = syncSourceDao.getByItem(source.itemId, "playlist")
            if (remaining.isEmpty()) {
                playlistDao.getById(source.itemId)?.let { playlistDao.delete(it) }
                playlistTrackDao.deleteByPlaylistId(source.itemId)
            }
            removed++
        }

        return TypeSyncResult(added = added, removed = removed, updated = updated, unchanged = unchanged)
    }

    private suspend fun pushPlaylist(playlist: PlaylistEntity) {
        val spotifyId = playlist.spotifyId ?: return
        val tracks = playlistTrackDao.getByPlaylistIdSync(playlist.id)
        val uris = tracks.mapNotNull { it.trackSpotifyUri }
        val snapshotId = spotifyProvider.replacePlaylistTracks(spotifyId, uris)

        playlistDao.insert(playlist.copy(
            snapshotId = snapshotId ?: playlist.snapshotId,
            locallyModified = false,
        ))

        val source = syncSourceDao.get(playlist.id, "playlist", SpotifySyncProvider.PROVIDER_ID)
        if (source != null) {
            syncSourceDao.insert(source.copy(syncedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun pullPlaylist(
        localPlaylist: PlaylistEntity,
        remote: SpotifySyncProvider.SyncedPlaylist,
    ) {
        val tracks = spotifyProvider.fetchPlaylistTracks(remote.spotifyId)
        playlistTrackDao.deleteByPlaylistId(localPlaylist.id)
        playlistTrackDao.insertAll(tracks)

        playlistDao.insert(localPlaylist.copy(
            name = remote.entity.name,
            description = remote.entity.description,
            artworkUrl = remote.entity.artworkUrl,
            trackCount = tracks.size,
            snapshotId = remote.snapshotId,
            lastModified = System.currentTimeMillis(),
            locallyModified = false,
        ))

        val source = syncSourceDao.get(localPlaylist.id, "playlist", SpotifySyncProvider.PROVIDER_ID)
        if (source != null) {
            syncSourceDao.insert(source.copy(syncedAt = System.currentTimeMillis()))
        }
    }

    // ── Bidirectional removal ────────────────────────────────────

    /**
     * Called when user removes a track from Collection.
     * If the track was synced from Spotify, also removes it from Spotify liked songs.
     */
    suspend fun onTrackRemoved(track: TrackEntity) {
        val sources = syncSourceDao.getByItem(track.id, "track")
        val spotifySource = sources.find { it.providerId == SpotifySyncProvider.PROVIDER_ID }
        if (spotifySource?.externalId != null) {
            try {
                spotifyProvider.removeTracks(listOf(spotifySource.externalId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove track from Spotify", e)
            }
        }
        syncSourceDao.deleteAllForItem(track.id, "track")
    }

    suspend fun onAlbumRemoved(album: AlbumEntity) {
        val sources = syncSourceDao.getByItem(album.id, "album")
        val spotifySource = sources.find { it.providerId == SpotifySyncProvider.PROVIDER_ID }
        if (spotifySource?.externalId != null) {
            try {
                spotifyProvider.removeAlbums(listOf(spotifySource.externalId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove album from Spotify", e)
            }
        }
        syncSourceDao.deleteAllForItem(album.id, "album")
    }

    suspend fun onArtistRemoved(artist: ArtistEntity) {
        val sources = syncSourceDao.getByItem(artist.id, "artist")
        val spotifySource = sources.find { it.providerId == SpotifySyncProvider.PROVIDER_ID }
        if (spotifySource?.externalId != null) {
            try {
                spotifyProvider.unfollowArtists(listOf(spotifySource.externalId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unfollow artist on Spotify", e)
            }
        }
        syncSourceDao.deleteAllForItem(artist.id, "artist")
    }

    // ── Stop syncing ─────────────────────────────────────────────

    /**
     * Stop syncing and optionally remove all items that only have this provider
     * as their source.
     */
    suspend fun stopSyncing(removeItems: Boolean) {
        val providerId = SpotifySyncProvider.PROVIDER_ID

        if (removeItems) {
            // Delete items that ONLY have this provider as sync source
            val allSources = syncSourceDao.getAllByProvider(providerId)
            for (source in allSources) {
                val otherSources = syncSourceDao.getByItem(source.itemId, source.itemType)
                    .filter { it.providerId != providerId }
                if (otherSources.isEmpty()) {
                    // No other sources — delete the item
                    when (source.itemType) {
                        "track" -> trackDao.getById(source.itemId)?.let { trackDao.delete(it) }
                        "album" -> albumDao.getById(source.itemId)?.let { albumDao.delete(it) }
                        "artist" -> artistDao.deleteById(source.itemId)
                        "playlist" -> {
                            playlistDao.getById(source.itemId)?.let { playlistDao.delete(it) }
                            playlistTrackDao.deleteByPlaylistId(source.itemId)
                        }
                    }
                }
            }
        }

        syncSourceDao.deleteAllForProvider(providerId)
        settingsStore.clearSyncSettings()
    }
}
```

**Step 2: Add missing DAO methods**

Add to `AlbumDao.kt`:
```kotlin
@Query("SELECT * FROM albums WHERE id = :id")
suspend fun getById(id: String): AlbumEntity?
```

Add to `PlaylistDao.kt`:
```kotlin
@Query("SELECT * FROM playlists WHERE id = :id")
suspend fun getById(id: String): PlaylistEntity?

@Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
suspend fun getAllSync(): List<PlaylistEntity>
```

Add to `PlaylistTrackDao.kt`:
```kotlin
@Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
suspend fun getByPlaylistIdSync(playlistId: String): List<PlaylistTrackEntity>
```

**Step 3: Build and verify**

Run: `./gradlew assembleDebug`

**Step 4: Commit**

```
feat: add SyncEngine with diff calculation, mass removal safeguard, bidirectional sync
```

---

## Task 7: SyncScheduler + LibrarySyncWorker

**Files:**
- Create: `app/src/main/java/com/parachord/android/sync/SyncScheduler.kt`
- Modify: `app/src/main/java/com/parachord/android/sync/LibrarySyncWorker.kt`

**Step 1: Create SyncScheduler (in-app timer)**

```kotlin
// app/src/main/java/com/parachord/android/sync/SyncScheduler.kt
package com.parachord.android.sync

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.work.*
import com.parachord.android.data.store.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages sync scheduling: in-app 15-minute timer + WorkManager hourly background sync.
 * Coordinates both to avoid redundant syncs.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncEngine: SyncEngine,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SyncScheduler"
        private const val IN_APP_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        private const val MIN_SYNC_GAP_MS = 10 * 60 * 1000L // Don't re-sync within 10 minutes
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timerJob: Job? = null

    /** Start the in-app periodic sync timer. Call from Application.onCreate or Activity. */
    fun startInAppTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(IN_APP_INTERVAL_MS)
                runBackgroundSync()
            }
        }
        Log.d(TAG, "In-app sync timer started (${IN_APP_INTERVAL_MS / 60000}min interval)")
    }

    fun stopInAppTimer() {
        timerJob?.cancel()
        timerJob = null
        Log.d(TAG, "In-app sync timer stopped")
    }

    private suspend fun runBackgroundSync() {
        val settings = settingsStore.getSyncSettings()
        if (!settings.enabled) return

        // Check if we synced recently
        val lastSync = settingsStore.lastSyncAtFlow.first()
        if (System.currentTimeMillis() - lastSync < MIN_SYNC_GAP_MS) {
            Log.d(TAG, "Skipping background sync — last sync was recent")
            return
        }

        try {
            val result = syncEngine.syncAll()
            if (result.success) {
                val added = result.tracks.added + result.albums.added +
                    result.artists.added + result.playlists.added
                val removed = result.tracks.removed + result.albums.removed +
                    result.artists.removed + result.playlists.removed
                if (added > 0 || removed > 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Synced: +$added added, -$removed removed",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background sync failed", e)
        }
    }

    /** Enqueue the WorkManager periodic sync. Call when sync is enabled. */
    fun enableWorkManagerSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<LibrarySyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            LibrarySyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        Log.d(TAG, "WorkManager periodic sync enabled (hourly)")
    }

    /** Cancel the WorkManager periodic sync. Call when sync is disabled. */
    fun disableWorkManagerSync() {
        WorkManager.getInstance(context).cancelUniqueWork(LibrarySyncWorker.WORK_NAME)
        Log.d(TAG, "WorkManager periodic sync disabled")
    }
}
```

**Step 2: Implement LibrarySyncWorker**

Replace the contents of `LibrarySyncWorker.kt`:

```kotlin
package com.parachord.android.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.store.SettingsStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * WorkManager worker that periodically syncs the user's library.
 * Runs hourly when sync is enabled with network connectivity.
 */
@HiltWorker
class LibrarySyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncEngine: SyncEngine,
    private val settingsStore: SettingsStore,
    private val oAuthManager: OAuthManager,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "LibrarySyncWorker"
        const val WORK_NAME = "library_sync"
        private const val MIN_SYNC_GAP_MS = 10 * 60 * 1000L
    }

    override suspend fun doWork(): Result {
        val settings = settingsStore.getSyncSettings()
        if (!settings.enabled) {
            Log.d(TAG, "Sync not enabled, skipping")
            return Result.success()
        }

        // Check if we synced recently (in-app timer may have run)
        val lastSync = settingsStore.lastSyncAtFlow.first()
        if (System.currentTimeMillis() - lastSync < MIN_SYNC_GAP_MS) {
            Log.d(TAG, "Skipping — last sync was recent")
            return Result.success()
        }

        return try {
            // Refresh token before syncing
            oAuthManager.refreshSpotifyToken()

            val result = syncEngine.syncAll()
            if (result.success) {
                Log.d(TAG, "Background sync complete: $result")
                Result.success()
            } else {
                Log.w(TAG, "Sync reported failure: ${result.error}")
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync worker failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
```

**Step 3: Build and verify**

Run: `./gradlew assembleDebug`

**Step 4: Commit**

```
feat: add SyncScheduler (in-app timer + WorkManager) for background sync
```

---

## Task 8: LibraryRepository — Bidirectional Removal Integration

**Files:**
- Modify: `app/src/main/java/com/parachord/android/data/repository/LibraryRepository.kt`

**Step 1: Add SyncEngine dependency and update delete methods**

Add `SyncEngine` to the constructor and update delete methods to call sync engine for push-back:

```kotlin
@Singleton
class LibraryRepository @Inject constructor(
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val artistDao: ArtistDao,
    private val syncEngine: SyncEngine,
) {
    // ... existing methods ...

    // ── Artists ──────────────────────────────────────────────
    fun getAllArtists(): Flow<List<ArtistEntity>> = artistDao.getAll()
    suspend fun addArtist(artist: ArtistEntity) = artistDao.insert(artist)
    suspend fun addArtists(artists: List<ArtistEntity>) = artistDao.insertAll(artists)

    /**
     * Delete a track from Collection with bidirectional sync.
     * If the track was synced from Spotify, also removes it from Spotify liked songs.
     */
    suspend fun deleteTrackWithSync(track: TrackEntity) {
        syncEngine.onTrackRemoved(track)
        trackDao.delete(track)
    }

    suspend fun deleteAlbumWithSync(album: AlbumEntity) {
        syncEngine.onAlbumRemoved(album)
        albumDao.delete(album)
    }

    suspend fun deleteArtistWithSync(artist: ArtistEntity) {
        syncEngine.onArtistRemoved(artist)
        artistDao.delete(artist)
    }

    // Keep old deleteTrack/deleteAlbum for non-sync usage
    suspend fun deleteTrack(track: TrackEntity) = trackDao.delete(track)
    suspend fun deleteAlbum(album: AlbumEntity) = albumDao.delete(album)
    suspend fun deletePlaylist(playlist: PlaylistEntity) = playlistDao.delete(playlist)
}
```

**Note:** Be careful about circular dependency. `SyncEngine` depends on DAOs. `LibraryRepository` depends on DAOs + SyncEngine. This is fine since SyncEngine does NOT depend on LibraryRepository.

**Step 2: Build and verify**

Run: `./gradlew assembleDebug`

**Step 3: Commit**

```
feat: add bidirectional removal to LibraryRepository
```

---

## Task 9: Sync Setup Bottom Sheet UI

**Files:**
- Create: `app/src/main/java/com/parachord/android/ui/screens/sync/SyncSetupSheet.kt`
- Create: `app/src/main/java/com/parachord/android/ui/screens/sync/SyncViewModel.kt`

**Step 1: Create SyncViewModel**

```kotlin
// app/src/main/java/com/parachord/android/ui/screens/sync/SyncViewModel.kt
package com.parachord.android.ui.screens.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.sync.SpotifySyncProvider
import com.parachord.android.sync.SyncEngine
import com.parachord.android.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncEngine: SyncEngine,
    private val syncScheduler: SyncScheduler,
    private val settingsStore: SettingsStore,
    private val spotifyProvider: SpotifySyncProvider,
) : ViewModel() {

    // ── Setup step tracking ─────────────────────────────────
    enum class SetupStep { OPTIONS, PLAYLISTS, SYNCING, COMPLETE }

    private val _currentStep = MutableStateFlow(SetupStep.OPTIONS)
    val currentStep: StateFlow<SetupStep> = _currentStep

    // ── Sync option toggles ─────────────────────────────────
    private val _syncTracks = MutableStateFlow(true)
    val syncTracks: StateFlow<Boolean> = _syncTracks

    private val _syncAlbums = MutableStateFlow(true)
    val syncAlbums: StateFlow<Boolean> = _syncAlbums

    private val _syncArtists = MutableStateFlow(true)
    val syncArtists: StateFlow<Boolean> = _syncArtists

    private val _syncPlaylists = MutableStateFlow(true)
    val syncPlaylists: StateFlow<Boolean> = _syncPlaylists

    fun setSyncTracks(v: Boolean) { _syncTracks.value = v }
    fun setSyncAlbums(v: Boolean) { _syncAlbums.value = v }
    fun setSyncArtists(v: Boolean) { _syncArtists.value = v }
    fun setSyncPlaylists(v: Boolean) { _syncPlaylists.value = v }

    // ── Playlist selection ──────────────────────────────────
    private val _availablePlaylists = MutableStateFlow<List<SpotifySyncProvider.SyncedPlaylist>>(emptyList())
    val availablePlaylists: StateFlow<List<SpotifySyncProvider.SyncedPlaylist>> = _availablePlaylists

    private val _selectedPlaylistIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedPlaylistIds: StateFlow<Set<String>> = _selectedPlaylistIds

    private val _playlistFilter = MutableStateFlow("all") // "all", "owned", "following"
    val playlistFilter: StateFlow<String> = _playlistFilter

    fun setPlaylistFilter(filter: String) { _playlistFilter.value = filter }
    fun togglePlaylistSelection(spotifyId: String) {
        _selectedPlaylistIds.value = _selectedPlaylistIds.value.let {
            if (spotifyId in it) it - spotifyId else it + spotifyId
        }
    }
    fun selectAllPlaylists(spotifyIds: List<String>) {
        _selectedPlaylistIds.value = _selectedPlaylistIds.value + spotifyIds
    }
    fun deselectAllPlaylists(spotifyIds: List<String>) {
        _selectedPlaylistIds.value = _selectedPlaylistIds.value - spotifyIds.toSet()
    }

    // ── Sync progress ───────────────────────────────────────
    private val _syncProgress = MutableStateFlow(SyncEngine.SyncProgress(SyncEngine.SyncPhase.TRACKS))
    val syncProgress: StateFlow<SyncEngine.SyncProgress> = _syncProgress

    private val _syncResult = MutableStateFlow<SyncEngine.FullSyncResult?>(null)
    val syncResult: StateFlow<SyncEngine.FullSyncResult?> = _syncResult

    // ── Sync settings state ─────────────────────────────────
    val syncEnabled = settingsStore.syncEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val lastSyncAt = settingsStore.lastSyncAtFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    // ── Actions ─────────────────────────────────────────────

    fun proceedFromOptions() {
        if (_syncPlaylists.value) {
            // Need to fetch playlists for selection
            viewModelScope.launch {
                try {
                    val playlists = spotifyProvider.fetchPlaylists()
                    _availablePlaylists.value = playlists
                    _selectedPlaylistIds.value = playlists.map { it.spotifyId }.toSet()
                    _currentStep.value = SetupStep.PLAYLISTS
                } catch (e: Exception) {
                    // If playlist fetch fails, skip to sync
                    startSync()
                }
            }
        } else {
            startSync()
        }
    }

    fun startSync() {
        _currentStep.value = SetupStep.SYNCING
        viewModelScope.launch {
            // Save settings first
            settingsStore.saveSyncSettings(SettingsStore.SyncSettings(
                enabled = true,
                provider = "spotify",
                syncTracks = _syncTracks.value,
                syncAlbums = _syncAlbums.value,
                syncArtists = _syncArtists.value,
                syncPlaylists = _syncPlaylists.value,
                selectedPlaylistIds = _selectedPlaylistIds.value,
                pushLocalPlaylists = true,
            ))

            // Start schedulers
            syncScheduler.startInAppTimer()
            syncScheduler.enableWorkManagerSync()

            // Run initial sync
            val result = syncEngine.syncAll { progress ->
                _syncProgress.value = progress
            }
            _syncResult.value = result
            _currentStep.value = SetupStep.COMPLETE
        }
    }

    /** Trigger an immediate manual sync (from Settings "Sync Now" button). */
    fun syncNow() {
        _currentStep.value = SetupStep.SYNCING
        viewModelScope.launch {
            val result = syncEngine.syncAll { progress ->
                _syncProgress.value = progress
            }
            _syncResult.value = result
            _currentStep.value = SetupStep.COMPLETE
        }
    }

    fun stopSyncing(removeItems: Boolean) {
        viewModelScope.launch {
            syncEngine.stopSyncing(removeItems)
            syncScheduler.stopInAppTimer()
            syncScheduler.disableWorkManagerSync()
        }
    }

    fun resetSetup() {
        _currentStep.value = SetupStep.OPTIONS
        _syncResult.value = null
    }
}
```

**Step 2: Create SyncSetupSheet**

```kotlin
// app/src/main/java/com/parachord/android/ui/screens/sync/SyncSetupSheet.kt
package com.parachord.android.ui.screens.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.sync.SyncEngine
import com.parachord.android.ui.components.AlbumArtCard

private val SpotifyGreen = Color(0xFF1DB954)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSetupSheet(
    onDismiss: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val currentStep by viewModel.currentStep.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            if (currentStep != SyncSetupSheet.SetupStep.SYNCING) onDismiss()
        },
        sheetState = sheetState,
    ) {
        when (currentStep) {
            SyncViewModel.SetupStep.OPTIONS -> OptionsStep(viewModel)
            SyncViewModel.SetupStep.PLAYLISTS -> PlaylistSelectionStep(viewModel)
            SyncViewModel.SetupStep.SYNCING -> SyncingStep(viewModel)
            SyncViewModel.SetupStep.COMPLETE -> CompleteStep(viewModel, onDismiss)
        }
    }
}

@Composable
private fun OptionsStep(viewModel: SyncViewModel) {
    val syncTracks by viewModel.syncTracks.collectAsStateWithLifecycle()
    val syncAlbums by viewModel.syncAlbums.collectAsStateWithLifecycle()
    val syncArtists by viewModel.syncArtists.collectAsStateWithLifecycle()
    val syncPlaylists by viewModel.syncPlaylists.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Sync,
                contentDescription = null,
                tint = SpotifyGreen,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Sync with Spotify",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose what to sync from your Spotify library",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        SyncOptionRow("Liked Songs", Icons.Default.Favorite, syncTracks) { viewModel.setSyncTracks(it) }
        SyncOptionRow("Saved Albums", Icons.Default.Album, syncAlbums) { viewModel.setSyncAlbums(it) }
        SyncOptionRow("Followed Artists", Icons.Default.Person, syncArtists) { viewModel.setSyncArtists(it) }
        SyncOptionRow("Playlists", Icons.Default.QueueMusic, syncPlaylists) { viewModel.setSyncPlaylists(it) }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { viewModel.proceedFromOptions() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
            enabled = syncTracks || syncAlbums || syncArtists || syncPlaylists,
        ) {
            Text(if (syncPlaylists) "Next" else "Start Sync")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SyncOptionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PlaylistSelectionStep(viewModel: SyncViewModel) {
    val playlists by viewModel.availablePlaylists.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedPlaylistIds.collectAsStateWithLifecycle()
    val filter by viewModel.playlistFilter.collectAsStateWithLifecycle()

    val filteredPlaylists = remember(playlists, filter) {
        when (filter) {
            "owned" -> playlists.filter { it.isOwned }
            "following" -> playlists.filter { !it.isOwned }
            else -> playlists
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            "Select Playlists",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(16.dp))

        // Filter tabs
        Row(modifier = Modifier.fillMaxWidth()) {
            FilterChip(selected = filter == "all", onClick = { viewModel.setPlaylistFilter("all") },
                label = { Text("All") }, modifier = Modifier.padding(end = 8.dp))
            FilterChip(selected = filter == "owned", onClick = { viewModel.setPlaylistFilter("owned") },
                label = { Text("Created by Me") }, modifier = Modifier.padding(end = 8.dp))
            FilterChip(selected = filter == "following", onClick = { viewModel.setPlaylistFilter("following") },
                label = { Text("Following") })
        }

        // Select/Deselect All
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            TextButton(onClick = {
                viewModel.selectAllPlaylists(filteredPlaylists.map { it.spotifyId })
            }) { Text("Select All") }
            TextButton(onClick = {
                viewModel.deselectAllPlaylists(filteredPlaylists.map { it.spotifyId })
            }) { Text("Deselect All") }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 400.dp),
        ) {
            items(filteredPlaylists, key = { it.spotifyId }) { playlist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.togglePlaylistSelection(playlist.spotifyId) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = playlist.spotifyId in selectedIds,
                        onCheckedChange = { viewModel.togglePlaylistSelection(playlist.spotifyId) },
                    )
                    Spacer(Modifier.width(8.dp))
                    AlbumArtCard(
                        artworkUrl = playlist.entity.artworkUrl,
                        size = 40.dp,
                        cornerRadius = 4.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            playlist.entity.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${playlist.trackCount} tracks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { viewModel.startSync() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
            enabled = selectedIds.isNotEmpty(),
        ) {
            Text("Start Sync")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SyncingStep(viewModel: SyncViewModel) {
    val progress by viewModel.syncProgress.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = SpotifyGreen,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            progress.message,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        if (progress.total > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${progress.current} of ${progress.total}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress.current.toFloat() / progress.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
                color = SpotifyGreen,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CompleteStep(viewModel: SyncViewModel, onDismiss: () -> Unit) {
    val result by viewModel.syncResult.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (result?.success == true) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = if (result?.success == true) SpotifyGreen else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (result?.success == true) "Sync Complete!" else "Sync Failed",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        result?.let { r ->
            if (r.success) {
                Spacer(Modifier.height(20.dp))
                SyncStatRow("Tracks", r.tracks)
                SyncStatRow("Albums", r.albums)
                SyncStatRow("Artists", r.artists)
                SyncStatRow("Playlists", r.playlists)
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    r.error ?: "Unknown error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                viewModel.resetSetup()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Done")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SyncStatRow(label: String, stats: SyncEngine.TypeSyncResult) {
    if (stats.added == 0 && stats.removed == 0 && stats.unchanged == 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(80.dp),
        )
        if (stats.added > 0) {
            Text(
                "+${stats.added}",
                style = MaterialTheme.typography.bodyMedium,
                color = SpotifyGreen,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        if (stats.removed > 0) {
            Text(
                "-${stats.removed}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        if (stats.unchanged > 0) {
            Text(
                "${stats.unchanged} unchanged",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

**Step 3: Build and verify**

Run: `./gradlew assembleDebug`

**Step 4: Commit**

```
feat: add sync setup bottom sheet with 4-step flow
```

---

## Task 10: Collection Screen Integration

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/screens/library/LibraryScreen.kt`
- Modify: `app/src/main/java/com/parachord/android/ui/screens/library/LibraryViewModel.kt`

**Step 1: Add sync button to Collection toolbar and empty state**

In `CollectionScreen`, add a sync icon button to the TopAppBar actions, and add a "Sync Spotify" button to the empty states. Add a state to control showing the SyncSetupSheet.

Add to `LibraryViewModel`:
```kotlin
val artists: StateFlow<List<ArtistEntity>> = repository.getAllArtists()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

In `CollectionScreen`, add:
- `var showSyncSheet by remember { mutableStateOf(false) }` state
- Sync icon button in TopAppBar actions
- "Sync Spotify" button in empty states
- `if (showSyncSheet) SyncSetupSheet(onDismiss = { showSyncSheet = false })`
- Use `viewModel.artists` for the Artists tab instead of deriving from tracks

**Step 2: Build and verify**

Run: `./gradlew assembleDebug`

**Step 3: Commit**

```
feat: add sync button to Collection screen and empty states
```

---

## Task 11: Settings Sync Section

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/screens/settings/SettingsScreen.kt`

**Step 1: Add a "Spotify Sync" section to Settings**

Add a new section in the Settings screen that shows:
- Sync enable/disable toggle
- Last synced timestamp (formatted as relative time)
- "Sync Now" button
- "Change sync settings" button (opens SyncSetupSheet)
- "Stop syncing" button with confirmation dialog (keep/remove items)

This section should use `SyncViewModel` and only appear when Spotify is connected.

**Step 2: Build and verify**

Run: `./gradlew assembleDebug`

**Step 3: Commit**

```
feat: add Spotify Sync section to Settings screen
```

---

## Task 12: Final Integration and Build Verification

**Step 1: Full build**

Run: `./gradlew assembleDebug`

Verify no compilation errors across all modified files.

**Step 2: Test the sync flow manually**

1. Open app → Settings → ensure Spotify is connected
2. Re-authenticate Spotify (new scopes required)
3. Go to Collection → tap sync icon → verify 4-step bottom sheet
4. Complete sync → verify tracks/albums/artists appear in Collection
5. Go to Settings → Spotify Sync → verify status, Sync Now works
6. Remove a synced track → verify it's removed from Spotify liked songs

**Step 3: Final commit**

```
feat: complete Spotify Collection bidirectional sync

Ports the desktop app's sync system to Android:
- Syncs liked songs, saved albums, followed artists, playlists
- Bidirectional: removing from Collection removes from Spotify
- Local playlists pushed to Spotify
- Background sync: 15-min in-app timer + hourly WorkManager
- 4-step setup bottom sheet + Settings sync section
- Mass removal safeguard matching desktop
```
