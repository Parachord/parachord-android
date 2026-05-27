package com.parachord.shared.sync

import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.metadata.MbidEnrichmentService
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.settings.SettingsStore
import kotlin.concurrent.Volatile

/**
 * Two-way playlist sync between Parachord and ListenBrainz.
 *
 * Mirrors [AppleMusicSyncProvider]'s shape — session-scoped
 * `authFailedForSession` kill-switch on 401, graceful no-op handling
 * for the not-yet-supported surface (tracks / albums / artists fall
 * back to inherited defaults).
 *
 * V1 scope: playlists only (push + pull). Loved tracks stay on
 * [com.parachord.android.playback.LovesPushService]. Library surface
 * (saved tracks/albums, followed artists) inherits the no-op
 * defaults from [SyncProvider].
 *
 * See `docs/plans/2026-05-27-listenbrainz-sync-provider-design.md`.
 */
class ListenBrainzSyncProvider(
    private val client: ListenBrainzClient,
    private val settingsStore: SettingsStore,
    private val mbidEnrichmentService: MbidEnrichmentService,
) : SyncProvider {
    companion object {
        const val PROVIDER_ID = "listenbrainz"
    }

    override val id: String = PROVIDER_ID
    override val displayName: String = "ListenBrainz"

    override val features: ProviderFeatures = ProviderFeatures(
        snapshots = SnapshotKind.DateString,         // LB returns last_modified_at ISO string
        supportsFollow = false,                       // V2
        supportsPlaylistDelete = true,                // POST /1/playlist/{mbid}/delete
        supportsPlaylistRename = true,                // POST /1/playlist/edit/{mbid}
        supportsTrackReplace = true,                  // delete-all + add-all
    )

    /**
     * Session-scoped kill-switch. Tripped by a 401 from any mutation
     * endpoint; remaining LB pushes in the session short-circuit until
     * the user re-authenticates. Mirrors the AM pattern.
     */
    @Volatile
    private var authFailedForSession: Boolean = false

    // ── Playlist surface — all TODO for now, implemented in Tasks 10-13 ─

    override suspend fun fetchPlaylists(
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): List<SyncedPlaylist> = TODO("Task 10")

    override suspend fun fetchPlaylistTracks(
        externalPlaylistId: String,
    ): List<PlaylistTrack> = TODO("Task 11")

    override suspend fun getPlaylistSnapshotId(
        externalPlaylistId: String,
    ): String? = TODO("Task 11")

    override suspend fun createPlaylist(
        name: String,
        description: String?,
    ): RemoteCreated = TODO("Task 12")

    override suspend fun replacePlaylistTracks(
        externalPlaylistId: String,
        externalTrackIds: List<String>,
    ): String? = TODO("Task 12")

    override suspend fun updatePlaylistDetails(
        externalPlaylistId: String,
        name: String?,
        description: String?,
    ): Unit = TODO("Task 12")

    override suspend fun deletePlaylist(
        externalPlaylistId: String,
    ): DeleteResult = TODO("Task 13")

    override suspend fun searchForTrackId(
        title: String,
        artist: String,
        album: String?,
    ): String? = TODO("Task 13")

    // Library surface (saveTracks, saveAlbums, fetchArtists, etc.) intentionally
    // inherits the no-op defaults from SyncProvider.
}
