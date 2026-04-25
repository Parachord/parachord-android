package com.parachord.android.sync

import android.util.Log
import com.parachord.android.data.api.AmCreatePlaylistAttributes
import com.parachord.android.data.api.AmCreatePlaylistRequest
import com.parachord.android.data.api.AmLibraryAlbum
import com.parachord.android.data.api.AmLibraryArtist
import com.parachord.android.data.api.AmLibrarySong
import com.parachord.android.data.api.AmPlaylist
import com.parachord.android.data.api.AmTrack
import com.parachord.android.data.api.AmTrackReference
import com.parachord.android.data.api.AmTracksRequest
import com.parachord.android.data.api.AmUpdatePlaylistAttributes
import com.parachord.android.data.api.AmUpdatePlaylistRequest
import com.parachord.android.data.api.AppleMusicLibraryApi
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.ArtistEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.sync.DeleteResult
import com.parachord.shared.sync.ProviderFeatures
import com.parachord.shared.sync.RemoteCreated
import com.parachord.shared.sync.SnapshotKind
import com.parachord.shared.sync.SyncProvider
import com.parachord.shared.sync.SyncedAlbum
import com.parachord.shared.sync.SyncedArtist
import com.parachord.shared.sync.SyncedPlaylist
import com.parachord.shared.sync.SyncedTrack
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.time.Instant

/**
 * Apple Music sync provider.
 *
 * Phase 4 shipped the playlist surface (fetchPlaylists, push, dedup,
 * the kill-switch degradation pattern). The collection-sync surface
 * (fetchTracks / fetchAlbums / fetchArtists + removeTracks /
 * removeAlbums) landed in the Phase 6.5+ collection-sync extension.
 *
 * Limitations:
 * - Apple has no follow/unfollow API for artists. `fetchArtists` is
 *   pull-only; `followArtists` / `unfollowArtists` inherit the no-op
 *   defaults from [SyncProvider].
 * - `saveTracks` / `saveAlbums` use Apple's documented
 *   `POST /me/library?ids[songs]=...` query-string API (NOT a JSON
 *   body). `removeTracks` / `removeAlbums` use per-item
 *   `DELETE /me/library/songs/{id}` etc. — no bulk delete.
 *
 * Endpoint degradation per desktop CLAUDE.md:
 * - PUT /library/playlists/{id}/tracks → 401 → [amPutUnsupportedForSession]
 *   flips, fall back to POST-append. Removals stay on the remote.
 * - PATCH /library/playlists/{id} → 401 → [amPatchUnsupportedForSession]
 *   flips, returns silently. Load-bearing: this runs before the track
 *   push and a throw would abort the track push too.
 * - DELETE /library/playlists/{id} → 401 → returns
 *   [DeleteResult.Unsupported]. Caller surfaces "remove manually in
 *   the Music app" UX.
 *
 * Do NOT retry-on-401 for any documented-unsupported endpoint — the
 * 401 is structural (Apple won't unblock the endpoint by handing you a
 * fresh token) and a defensive retry escalates a benign rejection into
 * a phantom auth crisis (the MusicKit bridge would walk the user
 * through a System Settings revoke for an authorization that was
 * never broken).
 *
 * Track-ID resolution via catalog search is deferred — Phase 4 push
 * paths only push tracks that already have an `appleMusicId` set;
 * tracks without one are silently skipped.
 */
class AppleMusicSyncProvider(
    private val api: AppleMusicLibraryApi,
    private val settingsStore: SettingsStore,
) : SyncProvider {

    companion object {
        const val PROVIDER_ID = "applemusic"
        private const val TAG = "AppleMusicSyncProvider"
        private const val PAGE_SIZE = 100
        /** Polite pacing — Apple Music rate-limits aggressive callers. */
        private const val INTER_REQUEST_DELAY_MS = 150L
    }

    override val id: String = PROVIDER_ID
    override val displayName: String = "Apple Music"
    override val features = ProviderFeatures(
        snapshots = SnapshotKind.DateString,
        // Apple has no follow/unfollow API for artists.
        supportsFollow = false,
        // PATCH/PUT/DELETE on library playlists all return 401 in
        // practice. Flags advertise the limitation to SyncEngine and
        // the UI layer; the provider degrades gracefully via session
        // kill-switches at runtime.
        supportsPlaylistDelete = false,
        supportsPlaylistRename = false,
        supportsTrackReplace = false,
    )

    /**
     * Once flipped on a 401/403/405 from PUT, subsequent calls skip
     * straight to POST-append without re-probing PUT. Reset on app
     * restart so we re-probe if Apple's behavior changes.
     */
    @Volatile
    internal var amPutUnsupportedForSession: Boolean = false

    /**
     * Independent kill-switch for PATCH (rename/description). Flipped
     * separately from PUT — they're distinct endpoints with their own
     * rejection behavior. Subsequent calls short-circuit and silently
     * return.
     */
    @Volatile
    internal var amPatchUnsupportedForSession: Boolean = false

    // ── Read methods ─────────────────────────────────────────────────

    override suspend fun fetchPlaylists(
        onProgress: ((current: Int, total: Int) -> Unit)?,
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
            // Total count isn't in the response; report as we go so the
            // UI sees progress.
            onProgress?.invoke(all.size, all.size)
            // `next` is the URL of the next page; `null` (or a short
            // page) means we're done.
            if (resp.next == null || resp.data.size < PAGE_SIZE) break
            offset += resp.data.size
        }
        return all
    }

    override suspend fun fetchPlaylistTracks(
        externalPlaylistId: String,
    ): List<com.parachord.shared.model.PlaylistTrack> {
        val localPlaylistId = "applemusic-$externalPlaylistId"

        // PRIMARY path — matches desktop's sync-providers/applemusic.js
        // (`/me/library/playlists/{id}/tracks?limit=100` with `data.next`
        // pagination). This is the canonical Apple-documented endpoint
        // for library-playlist tracks.
        val all = mutableListOf<com.parachord.shared.model.PlaylistTrack>()
        var offset = 0
        while (true) {
            delay(INTER_REQUEST_DELAY_MS)
            val resp = try {
                api.listPlaylistTracks(
                    playlistId = externalPlaylistId,
                    limit = PAGE_SIZE,
                    offset = offset,
                )
            } catch (e: HttpException) {
                if (e.code() == 401) throw AppleMusicReauthRequiredException()
                if (e.code() == 404) {
                    Log.w(TAG, "AM playlist $externalPlaylistId /tracks 404 at offset=$offset; " +
                        "returning ${all.size} tracks collected so far")
                    break
                }
                Log.w(TAG, "AM playlist $externalPlaylistId /tracks failed at offset=$offset", e)
                throw e
            }
            for ((index, am) in resp.data.withIndex()) {
                all.add(am.toPlaylistTrack(playlistId = localPlaylistId, position = offset + index))
            }
            if (resp.next == null || resp.data.size < PAGE_SIZE) break
            offset += resp.data.size
        }
        if (all.isNotEmpty()) return all

        // FALLBACK — primary returned empty. Desktop doesn't have this
        // fallback; we add it because the user reported AM playlists
        // showing 0 tracks in Parachord while the same playlists have
        // tracks visible in the Music app. The `?include=tracks` form
        // on the playlist GET endpoint returns tracks via
        // `relationships.tracks.data` and sometimes succeeds when the
        // dedicated `/tracks` endpoint silently returns empty (Apple
        // Music's library mirror has known inconsistencies for
        // shared / curated / smart playlists).
        delay(INTER_REQUEST_DELAY_MS)
        val viaInclude = try {
            val resp = api.getLibraryPlaylistWithTracks(externalPlaylistId)
            val data = resp.data.firstOrNull()?.relationships?.tracks?.data ?: emptyList()
            data.mapIndexed { i, am ->
                am.toPlaylistTrack(playlistId = localPlaylistId, position = i)
            }
        } catch (e: HttpException) {
            if (e.code() == 401) throw AppleMusicReauthRequiredException()
            Log.w(TAG, "AM include=tracks fallback for $externalPlaylistId failed (${e.code()})")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "AM include=tracks fallback for $externalPlaylistId threw", e)
            emptyList()
        }

        if (viaInclude.isNotEmpty()) {
            Log.d(TAG, "AM playlist $externalPlaylistId: /tracks returned 0 but " +
                "?include=tracks recovered ${viaInclude.size} tracks (fallback worked)")
            return viaInclude
        }

        Log.w(TAG, "AM playlist $externalPlaylistId returned 0 tracks via both " +
            "/tracks AND ?include=tracks — playlist may genuinely be empty or be a " +
            "Music-app-only smart-playlist source not exposed via API")
        return emptyList()
    }

    /**
     * Apple's library API doesn't expose a single-playlist endpoint
     * for change-token reads, so this implementation refetches the
     * full owned-playlist list and locates the row by ID. Fine for
     * users with O(100) playlists; if perf becomes an issue we can
     * cache the list for the duration of a sync cycle or use the
     * catalog endpoint for shared playlists.
     */
    override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? {
        delay(INTER_REQUEST_DELAY_MS)
        return try {
            // Walk pages until we find the target. Most users have <100,
            // so the first page hits.
            var offset = 0
            while (true) {
                val resp = api.listPlaylists(limit = PAGE_SIZE, offset = offset)
                val match = resp.data.firstOrNull { it.id == externalPlaylistId }
                if (match != null) return match.attributes.lastModifiedDate
                if (resp.next == null || resp.data.size < PAGE_SIZE) return null
                offset += resp.data.size
            }
            @Suppress("UNREACHABLE_CODE") null
        } catch (e: HttpException) {
            if (e.code() == 401) throw AppleMusicReauthRequiredException()
            Log.w(TAG, "getPlaylistSnapshotId failed for $externalPlaylistId", e)
            null
        }
    }

    // ── Write methods (TODO: subsequent tasks) ───────────────────────

    override suspend fun createPlaylist(name: String, description: String?): RemoteCreated {
        delay(INTER_REQUEST_DELAY_MS)
        val resp = try {
            api.createPlaylist(
                AmCreatePlaylistRequest(
                    attributes = AmCreatePlaylistAttributes(name = name, description = description),
                )
            )
        } catch (e: HttpException) {
            if (e.code() == 401) throw AppleMusicReauthRequiredException()
            throw e
        }
        val created = resp.data.firstOrNull()
            ?: throw IllegalStateException("Apple Music createPlaylist returned empty data")
        return RemoteCreated(
            externalId = created.id,
            snapshotId = created.attributes.lastModifiedDate,
        )
    }

    override suspend fun replacePlaylistTracks(
        externalPlaylistId: String,
        externalTrackIds: List<String>,
    ): String? {
        if (externalTrackIds.isEmpty()) {
            // PUT with empty list would clear; if PUT is unsupported we
            // can't clear via append either. Log + return without action.
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
                // Per desktop CLAUDE.md: refresh-and-retry on these
                // endpoints would escalate a benign endpoint rejection
                // into a phantom auth crisis (the 401 is structural,
                // not token-related).
                Log.w(TAG, "PUT replace returned ${resp.code()} for $externalPlaylistId; flipping session kill-switch, falling back to POST-append")
                amPutUnsupportedForSession = true
            } else {
                throw HttpException(resp)
            }
        }

        // POST-append fallback. Removals stay on the remote — accept this.
        delay(INTER_REQUEST_DELAY_MS)
        val resp = api.appendPlaylistTracks(externalPlaylistId, body)
        if (!resp.isSuccessful) {
            if (resp.code() == 401) throw AppleMusicReauthRequiredException()
            throw HttpException(resp)
        }
        return getPlaylistSnapshotId(externalPlaylistId)
    }

    override suspend fun updatePlaylistDetails(
        externalPlaylistId: String,
        name: String?,
        description: String?,
    ) {
        if (amPatchUnsupportedForSession) return
        if (name == null && description == null) return

        // **Load-bearing** try/catch: this method runs before the track
        // push in the create-or-link path. A throw here would abort the
        // track push too. Even if the function below never throws under
        // normal rejection, a network error or unexpected 5xx must
        // also not kill the track push. Defense-in-depth.
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
            Log.w(TAG, "PATCH details network error for $externalPlaylistId — silently skipping", e)
        }
    }

    override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult {
        delay(INTER_REQUEST_DELAY_MS)
        return try {
            val resp = api.deletePlaylist(externalPlaylistId)
            when {
                resp.isSuccessful -> DeleteResult.Success
                resp.code() in setOf(401, 403, 405) -> DeleteResult.Unsupported(resp.code())
                else -> DeleteResult.Failed(HttpException(resp))
            }
        } catch (e: Exception) {
            DeleteResult.Failed(e)
        }
    }

    // ── Collection sync: library tracks ──────────────────────────────

    override suspend fun fetchTracks(
        localCount: Int,
        latestExternalId: String?,
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): List<SyncedTrack>? {
        val all = mutableListOf<SyncedTrack>()
        // Quick-check shortcut: probe one item to compare against the
        // local state. The probe also returns meta.total which we use
        // for accurate progress reporting through pagination.
        delay(INTER_REQUEST_DELAY_MS)
        val probe = try {
            api.listLibrarySongs(limit = 1, offset = 0)
        } catch (e: HttpException) {
            if (e.code() == 401) throw AppleMusicReauthRequiredException()
            throw e
        }
        val probeId = probe.data.firstOrNull()?.id
        if (probeId == latestExternalId && localCount > 0) {
            // Nothing's changed at the head; assume rest is unchanged.
            return null
        }
        // Full pagination. Apple returns total in `meta.total` on every
        // page; first page from the probe gives us a good initial guess.
        var total = probe.meta?.total ?: 0
        var offset = 0
        while (true) {
            delay(INTER_REQUEST_DELAY_MS)
            val resp = try {
                api.listLibrarySongs(limit = PAGE_SIZE, offset = offset)
            } catch (e: HttpException) {
                if (e.code() == 401) throw AppleMusicReauthRequiredException()
                throw e
            }
            // Refresh total in case the library shifted mid-paginate.
            resp.meta?.total?.let { total = it }
            for (am in resp.data) {
                all.add(am.toSyncedTrack())
            }
            // Floor total to the running count so progress never reports
            // more than 100% if the remote shrunk during pagination.
            onProgress?.invoke(all.size, maxOf(total, all.size))
            if (resp.next == null || resp.data.size < PAGE_SIZE) break
            offset += resp.data.size
        }
        return all
    }

    /**
     * Apple's documented add-to-library endpoint takes catalog IDs
     * via query string: `POST /me/library?ids[songs]=id1,id2,...`.
     */
    override suspend fun saveTracks(externalIds: List<String>) {
        if (externalIds.isEmpty()) return
        externalIds.chunked(50).forEach { batch ->
            delay(INTER_REQUEST_DELAY_MS)
            val resp = api.addToLibrary(songIds = batch.joinToString(","))
            if (!resp.isSuccessful) {
                if (resp.code() == 401) throw AppleMusicReauthRequiredException()
                Log.w(TAG, "saveTracks returned ${resp.code()} for ${batch.size} ids")
            }
        }
    }

    /** Per-track DELETE — Apple has no bulk-delete endpoint for library songs. */
    override suspend fun removeTracks(externalIds: List<String>) {
        for (id in externalIds) {
            delay(INTER_REQUEST_DELAY_MS)
            try {
                val resp = api.deleteLibrarySong(id)
                if (!resp.isSuccessful && resp.code() != 404) {
                    Log.w(TAG, "deleteLibrarySong $id returned ${resp.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "deleteLibrarySong $id threw — continuing", e)
            }
        }
    }

    // ── Collection sync: library albums ──────────────────────────────

    override suspend fun fetchAlbums(
        localCount: Int,
        latestExternalId: String?,
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): List<SyncedAlbum>? {
        val all = mutableListOf<SyncedAlbum>()
        delay(INTER_REQUEST_DELAY_MS)
        val probe = try {
            api.listLibraryAlbums(limit = 1, offset = 0)
        } catch (e: HttpException) {
            if (e.code() == 401) throw AppleMusicReauthRequiredException()
            throw e
        }
        if (probe.data.firstOrNull()?.id == latestExternalId && localCount > 0) return null
        var total = probe.meta?.total ?: 0
        var offset = 0
        while (true) {
            delay(INTER_REQUEST_DELAY_MS)
            val resp = try {
                api.listLibraryAlbums(limit = PAGE_SIZE, offset = offset)
            } catch (e: HttpException) {
                if (e.code() == 401) throw AppleMusicReauthRequiredException()
                throw e
            }
            resp.meta?.total?.let { total = it }
            for (am in resp.data) {
                all.add(am.toSyncedAlbum())
            }
            onProgress?.invoke(all.size, maxOf(total, all.size))
            if (resp.next == null || resp.data.size < PAGE_SIZE) break
            offset += resp.data.size
        }
        return all
    }

    override suspend fun saveAlbums(externalIds: List<String>) {
        if (externalIds.isEmpty()) return
        externalIds.chunked(50).forEach { batch ->
            delay(INTER_REQUEST_DELAY_MS)
            val resp = api.addToLibrary(albumIds = batch.joinToString(","))
            if (!resp.isSuccessful) {
                if (resp.code() == 401) throw AppleMusicReauthRequiredException()
                Log.w(TAG, "saveAlbums returned ${resp.code()} for ${batch.size} ids")
            }
        }
    }

    override suspend fun removeAlbums(externalIds: List<String>) {
        for (id in externalIds) {
            delay(INTER_REQUEST_DELAY_MS)
            try {
                val resp = api.deleteLibraryAlbum(id)
                if (!resp.isSuccessful && resp.code() != 404) {
                    Log.w(TAG, "deleteLibraryAlbum $id returned ${resp.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "deleteLibraryAlbum $id threw — continuing", e)
            }
        }
    }

    // ── Collection sync: library artists (pull-only) ─────────────────

    override suspend fun fetchArtists(
        localCount: Int,
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): List<SyncedArtist>? {
        val all = mutableListOf<SyncedArtist>()
        var total = 0
        var offset = 0
        while (true) {
            delay(INTER_REQUEST_DELAY_MS)
            val resp = try {
                api.listLibraryArtists(limit = PAGE_SIZE, offset = offset)
            } catch (e: HttpException) {
                if (e.code() == 401) throw AppleMusicReauthRequiredException()
                throw e
            }
            resp.meta?.total?.let { total = it }
            for (am in resp.data) {
                all.add(am.toSyncedArtist())
            }
            onProgress?.invoke(all.size, maxOf(total, all.size))
            if (resp.next == null || resp.data.size < PAGE_SIZE) break
            offset += resp.data.size
        }
        if (all.size == localCount) return null
        return all
    }

    // followArtists / unfollowArtists inherit the SyncProvider no-op
    // defaults — Apple has no follow API.

    // ── Mappers ──────────────────────────────────────────────────────

    private fun AmPlaylist.toSyncedPlaylist(): SyncedPlaylist {
        val name = attributes.name
        val desc = attributes.description?.standard ?: attributes.description?.short
        // The PlaylistEntity carries Spotify-specific fields by historical
        // accident. Apple Music's identifier goes in the SyncedPlaylist's
        // generic `spotifyId` slot (the field name was Spotify-shaped at
        // the model's birth; renaming is a separate cleanup task).
        val playlistEntity = PlaylistEntity(
            id = "applemusic-$id",
            name = name,
            description = desc,
            artworkUrl = resolveArtworkUrl(attributes.artwork?.url),
            trackCount = 0,
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
            spotifyId = id,
            snapshotId = attributes.lastModifiedDate,
            trackCount = 0,
            // canEdit defaults to false on the response when the field
            // is omitted; treat that as not-owned (we only push to
            // owned playlists anyway).
            isOwned = attributes.canEdit,
        )
    }

    private fun AmTrack.toPlaylistTrack(
        playlistId: String,
        position: Int,
    ): PlaylistTrackEntity = PlaylistTrackEntity(
        playlistId = playlistId,
        position = position,
        trackTitle = attributes.name,
        trackArtist = attributes.artistName,
        trackAlbum = attributes.albumName,
        trackDuration = attributes.durationInMillis?.let { it / 1000 },
        trackArtworkUrl = resolveArtworkUrl(attributes.artwork?.url),
        trackSourceUrl = null,
        trackResolver = "applemusic",
        trackSpotifyUri = null,
        trackSoundcloudId = null,
        trackSpotifyId = null,
        // playParams.id is the catalog ID (preferred for cross-device
        // playback); falls back to the library ID. Either is usable.
        trackAppleMusicId = attributes.playParams?.id ?: id,
    )

    /** Library track → cross-provider [SyncedTrack]. The library row's
     * `id` is the library ID; `playParams.id` is the catalog ID. We
     * store the catalog ID in [TrackEntity.appleMusicId] so playback
     * can resolve via MusicKit. */
    private fun AmLibrarySong.toSyncedTrack(): SyncedTrack {
        val catalogId = attributes.playParams?.id ?: id
        val addedAt = attributes.dateAdded?.let { parseIso(it) } ?: 0L
        return SyncedTrack(
            entity = TrackEntity(
                id = "applemusic-$catalogId",
                title = attributes.name,
                artist = attributes.artistName,
                album = attributes.albumName,
                albumId = null,
                duration = attributes.durationInMillis,
                artworkUrl = resolveArtworkUrl(attributes.artwork?.url),
                spotifyUri = null,
                spotifyId = null,
                appleMusicId = catalogId,
                resolver = "applemusic",
                sourceType = "synced",
                addedAt = addedAt,
            ),
            spotifyId = catalogId,
            addedAt = addedAt,
        )
    }

    /** Library album → cross-provider [SyncedAlbum]. */
    private fun AmLibraryAlbum.toSyncedAlbum(): SyncedAlbum {
        val catalogId = attributes.playParams?.id ?: id
        val addedAt = attributes.dateAdded?.let { parseIso(it) } ?: 0L
        return SyncedAlbum(
            entity = AlbumEntity(
                id = "applemusic-$catalogId",
                title = attributes.name,
                artist = attributes.artistName,
                artworkUrl = resolveArtworkUrl(attributes.artwork?.url),
                trackCount = attributes.trackCount,
                addedAt = addedAt,
                spotifyId = null,
            ),
            spotifyId = catalogId,
            addedAt = addedAt,
        )
    }

    /** Library artist → cross-provider [SyncedArtist]. AM library
     * artist objects don't include images on the library endpoint —
     * the artwork comes from the catalog endpoint, which we'd need
     * one extra request per artist for. Skip for now; artist art on
     * AM-imported rows comes from the metadata enrichment cascade. */
    private fun AmLibraryArtist.toSyncedArtist(): SyncedArtist =
        SyncedArtist(
            entity = ArtistEntity(
                id = "applemusic-$id",
                name = attributes.name,
                imageUrl = null,
                spotifyId = null,
                genres = "",
            ),
            spotifyId = id,
        )

    /** Lenient ISO-8601 parse; library timestamps come in `Z` form. */
    private fun parseIso(s: String): Long =
        try { Instant.parse(s).toEpochMilli() } catch (_: Exception) { 0L }

    /** Apple Music artwork URLs come back with literal `{w}` and `{h}`
     *  placeholders (e.g. `.../{w}x{h}bb.jpg`) that the client is
     *  expected to substitute with the desired dimensions. Coil and
     *  the mosaic generator can't fetch URLs with the placeholders
     *  intact, so swap in 600x600 (matches desktop's
     *  `sync-providers/applemusic.js` which uses 500x500 — slight
     *  bump for higher-DPI Android screens). */
    private fun resolveArtworkUrl(url: String?): String? =
        url?.replace("{w}", "600")?.replace("{h}", "600")
}
