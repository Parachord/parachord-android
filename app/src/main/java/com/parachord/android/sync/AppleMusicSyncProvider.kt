package com.parachord.android.sync

import android.util.Log
import com.parachord.android.data.api.AmCreatePlaylistAttributes
import com.parachord.android.data.api.AmCreatePlaylistRequest
import com.parachord.android.data.api.AmPlaylist
import com.parachord.android.data.api.AmTrack
import com.parachord.android.data.api.AmTrackReference
import com.parachord.android.data.api.AmTracksRequest
import com.parachord.android.data.api.AmUpdatePlaylistAttributes
import com.parachord.android.data.api.AmUpdatePlaylistRequest
import com.parachord.android.data.api.AppleMusicLibraryApi
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.sync.DeleteResult
import com.parachord.shared.sync.ProviderFeatures
import com.parachord.shared.sync.RemoteCreated
import com.parachord.shared.sync.SnapshotKind
import com.parachord.shared.sync.SyncProvider
import com.parachord.shared.sync.SyncedPlaylist
import kotlinx.coroutines.delay
import retrofit2.HttpException

/**
 * Apple Music sync provider — playlist surface only (Decision D1).
 *
 * Library tracks/albums/artists pull + push are deferred; the
 * SyncProvider interface deliberately doesn't include those methods
 * yet so AM doesn't have to no-op-implement them.
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
        val all = mutableListOf<com.parachord.shared.model.PlaylistTrack>()
        var offset = 0
        val localPlaylistId = "applemusic-$externalPlaylistId"
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
                throw e
            }
            for ((index, am) in resp.data.withIndex()) {
                all.add(am.toPlaylistTrack(playlistId = localPlaylistId, position = offset + index))
            }
            if (resp.next == null || resp.data.size < PAGE_SIZE) break
            offset += resp.data.size
        }
        return all
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
            artworkUrl = attributes.artwork?.url,
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
        trackArtworkUrl = attributes.artwork?.url,
        trackSourceUrl = null,
        trackResolver = "applemusic",
        trackSpotifyUri = null,
        trackSoundcloudId = null,
        trackSpotifyId = null,
        // playParams.id is the catalog ID (preferred for cross-device
        // playback); falls back to the library ID. Either is usable.
        trackAppleMusicId = attributes.playParams?.id ?: id,
    )
}
