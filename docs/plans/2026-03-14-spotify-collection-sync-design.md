# Spotify Collection Bidirectional Sync — Design

## Overview

Port the desktop app's Collection bidirectional sync with Spotify to Android. Syncs liked songs, saved albums, followed artists, and playlists between the local Collection and Spotify. Supports push-back (removing from Collection removes from Spotify) and background auto-sync.

## Data Model

### New table: `sync_sources`

```
SyncSourceEntity(
    itemId: String,        // FK to tracks/albums/artists/playlists
    itemType: String,      // "track" | "album" | "artist" | "playlist"
    providerId: String,    // "spotify" | "manual"
    externalId: String?,   // Spotify ID
    addedAt: Long,         // When added on the provider
    syncedAt: Long,        // When last synced
    PK = (itemId, itemType, providerId)
)
```

### New table: `artists`

```
ArtistEntity(
    id: String,
    name: String,
    imageUrl: String?,
    spotifyId: String?,
    genres: String?,        // Comma-separated
    addedAt: Long
)
```

### Column additions

- `TrackEntity` — add `spotifyId: String?`
- `AlbumEntity` — add `spotifyId: String?`
- `PlaylistEntity` — add `spotifyId: String?`, `snapshotId: String?`, `lastModified: Long`, `locallyModified: Boolean`

### Migration 6 → 7

- CREATE TABLE sync_sources, artists
- ALTER TABLE tracks ADD COLUMN spotifyId
- ALTER TABLE albums ADD COLUMN spotifyId
- ALTER TABLE playlists ADD COLUMN spotifyId, snapshotId, lastModified, locallyModified

### Multi-source deletion rule

An item is only deleted from Collection when ALL its sync source entries are removed. Removing from one provider only removes that provider's sync source row.

## Spotify API Endpoints

### Library reads

| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/me/tracks?limit=50&offset=` | Liked songs (paginated) |
| GET | `/me/albums?limit=50&offset=` | Saved albums (paginated) |
| GET | `/me/following?type=artist&limit=50&after=` | Followed artists (cursor-based) |
| GET | `/me/playlists?limit=50&offset=` | User playlists (paginated) |
| GET | `/playlists/{id}/tracks?limit=100&offset=` | Playlist tracks |
| GET | `/playlists/{id}?fields=snapshot_id` | Playlist snapshot |
| GET | `/me` | Current user profile |

### Library writes

| Method | Endpoint | Purpose |
|--------|----------|---------|
| PUT | `/me/tracks` | Save tracks (batch ≤50) |
| DELETE | `/me/tracks` | Remove tracks (batch ≤50) |
| PUT | `/me/albums` | Save albums (batch ≤50) |
| DELETE | `/me/albums` | Remove albums (batch ≤50) |
| PUT | `/me/following?type=artist&ids=` | Follow artists (batch ≤50) |
| DELETE | `/me/following?type=artist&ids=` | Unfollow artists (batch ≤50) |

### Playlist writes

| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/users/{id}/playlists` | Create playlist |
| PUT | `/playlists/{id}/tracks` | Replace playlist tracks |
| POST | `/playlists/{id}/tracks` | Append tracks |
| PUT | `/playlists/{id}` | Update name/description |
| DELETE | `/playlists/{id}/followers` | Unfollow playlist |

## Sync Engine

### Diff calculation

`calculateDiff(remoteItems, localItems, providerId)`:
- `toAdd` — in remote, not in local
- `toRemove` — in local with provider source, not in remote (delete sync source; delete item only if no other sources)
- `toUpdate` — in both but missing this provider's sync source
- `unchanged` — already synced, update syncedAt

### Mass removal safeguard

If removals > 25% of synced items AND > 50 items: skip all removals, treat as unchanged. Prevents data loss from incomplete API pagination.

### Per-type sync flow

1. Count local items from this provider
2. Quick cache check (count + latest item match → skip full fetch)
3. Full paginated fetch if changed
4. Calculate diff → apply to Room DB
5. Return stats (added, removed, updated, unchanged)

## Playlist Sync (Bidirectional)

### Sync states

- `push` — local modified, remote unchanged
- `pull` — remote changed (snapshot_id differs), local unchanged
- `conflict` — both modified → timestamp-based resolution (newer wins)

### Push flow

1. Filter tracks to those with spotifyUri
2. Batch 100-track chunks: first = PUT (replace), rest = POST (append)
3. Push metadata changes
4. Update snapshotId, clear locallyModified

### Pull flow

1. Fetch tracks from Spotify
2. Replace PlaylistTrackEntity rows
3. Update snapshotId, lastModified

### Local playlists → Spotify

New playlists without spotifyId are pushed to Spotify:
1. POST `/users/{id}/playlists` to create
2. Push tracks with spotifyUri
3. Store returned ID + snapshot on PlaylistEntity + sync source

## Bidirectional Item Removal

When user removes a synced item from Collection:
1. Check SyncSourceEntity for Spotify source
2. If present → call Spotify remove API
3. Delete sync source entry
4. Delete item only if no sync sources remain

## Sync Settings (SettingsStore)

```
sync_enabled: Boolean
sync_provider: String
sync_tracks: Boolean
sync_albums: Boolean
sync_artists: Boolean
sync_playlists: Boolean
sync_selected_playlist_ids: Set<String>
sync_last_completed_at: Long
sync_push_local_playlists: Boolean
```

### Stop syncing

Prompt: "Remove synced items or keep them?"
- Remove: delete items with only this provider source
- Keep: remove sync source entries, leave items

## UI

### Bottom sheet setup flow (4 steps)

1. **Options** — checkboxes: Liked Songs, Saved Albums, Followed Artists, Playlists
2. **Playlist selection** — tabs (All/Created/Following), checkboxes, select all
3. **Progress** — spinner, phase label, counter, progress bar
4. **Complete** — per-type stats, Done button

### Entry points

- Empty Collection → "Sync Spotify" button
- Collection toolbar → sync icon
- Settings → Spotify Sync section

### Settings sync section

- Enable/disable toggle
- Last synced timestamp
- Sync Now button
- Change sync settings → re-opens bottom sheet step 1
- Stop syncing → confirmation with keep/remove choice

### Status indicators

- Spinning sync icon in Collection toolbar during sync
- Toast notifications for background sync results

## Background Sync

### In-app timer (SyncScheduler)

- 15-minute interval via CoroutineScope
- Checks lastSyncAt before triggering
- Runs only when sync enabled + valid token
- Toast on completion
- Cancels on disable or background

### WorkManager (LibrarySyncWorker)

- Hourly periodic work
- Constraint: network required
- Refreshes token, runs full sync, updates lastSyncAt
- Exponential backoff, max 3 retries
- ExistingPeriodicWorkPolicy.KEEP

### Coordination

- Both check lastSyncAt to avoid redundant syncs
- Mutex in SyncEngine prevents concurrent runs
- Shared sync_last_completed_at preference
