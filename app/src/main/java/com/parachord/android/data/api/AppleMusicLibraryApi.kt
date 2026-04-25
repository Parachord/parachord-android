package com.parachord.android.data.api

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the Apple Music library + storefront endpoints
 * used by [com.parachord.android.sync.AppleMusicSyncProvider].
 *
 * Auth headers (`Authorization: Bearer {dev-token}` and
 * `Music-User-Token: {mut}`) are added by [AppleMusicAuthInterceptor];
 * the methods themselves don't take auth parameters.
 *
 * PATCH / PUT / DELETE endpoints on `/me/library/playlists` return 401
 * for most user tokens — Apple's documented policy. The provider checks
 * `Response<Unit>.code()` and degrades to alternate paths instead of
 * throwing. Per desktop CLAUDE.md: do NOT retry-on-401 for these
 * endpoints; the rejection is structural, not token-related.
 */
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
     * Full-replace via PUT — public API often returns 401. Caller checks
     * the status code; on 401/403/405 it flips a session kill-switch and
     * degrades to [appendPlaylistTracks] (POST). Returns Response<Unit>
     * so caller can inspect status without an exception being thrown for
     * 4xx responses.
     */
    @PUT("v1/me/library/playlists/{id}/tracks")
    suspend fun replacePlaylistTracks(
        @Path("id") playlistId: String,
        @Body body: AmTracksRequest,
    ): Response<Unit>

    /** Append — the only reliable write path. Returns 204 on success. */
    @POST("v1/me/library/playlists/{id}/tracks")
    suspend fun appendPlaylistTracks(
        @Path("id") playlistId: String,
        @Body body: AmTracksRequest,
    ): Response<Unit>

    /**
     * Rename via PATCH — returns 401 on most tokens. Caller flips a
     * separate kill-switch (do NOT throw — this method runs before the
     * track push and a throw would abort it).
     */
    @PATCH("v1/me/library/playlists/{id}")
    suspend fun updatePlaylistDetails(
        @Path("id") playlistId: String,
        @Body body: AmUpdatePlaylistRequest,
    ): Response<Unit>

    /**
     * Delete — returns 401 in practice. Caller returns
     * [com.parachord.shared.sync.DeleteResult.Unsupported] so the UI
     * can surface "remove manually in the Music app" rather than
     * silently failing.
     */
    @DELETE("v1/me/library/playlists/{id}")
    suspend fun deletePlaylist(
        @Path("id") playlistId: String,
    ): Response<Unit>

    // ── Library tracks (collection sync) ─────────────────────────────

    @GET("v1/me/library/songs")
    suspend fun listLibrarySongs(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): AmLibrarySongListResponse

    /**
     * Add catalog songs to the user's library by ID. Apple's
     * documented add-to-library endpoint takes a query-string
     * `ids[songs]` parameter (NOT a JSON body), comma-separated.
     */
    @POST("v1/me/library")
    suspend fun addToLibrary(
        @Query("ids[songs]") songIds: String? = null,
        @Query("ids[albums]") albumIds: String? = null,
    ): Response<Unit>

    /** Remove a single library song. */
    @DELETE("v1/me/library/songs/{id}")
    suspend fun deleteLibrarySong(
        @Path("id") songId: String,
    ): Response<Unit>

    // ── Library albums (collection sync) ─────────────────────────────

    @GET("v1/me/library/albums")
    suspend fun listLibraryAlbums(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): AmLibraryAlbumListResponse

    @DELETE("v1/me/library/albums/{id}")
    suspend fun deleteLibraryAlbum(
        @Path("id") albumId: String,
    ): Response<Unit>

    // ── Library artists (read-only — Apple has no follow API) ────────

    @GET("v1/me/library/artists")
    suspend fun listLibraryArtists(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): AmLibraryArtistListResponse

    // ── Storefront detection ─────────────────────────────────────────

    @GET("v1/me/storefront")
    suspend fun getStorefront(): AmStorefrontResponse
}

// ── Library JSON models ──────────────────────────────────────────────

@Serializable
data class AmLibrarySongListResponse(
    val data: List<AmLibrarySong> = emptyList(),
    val next: String? = null,
    val meta: AmListMeta? = null,
)

/** Apple Music wraps total counts in a `meta` envelope on paginated
 *  list endpoints. Used by [AppleMusicSyncProvider] to emit accurate
 *  `(current, total)` progress instead of `(current, current)`. */
@Serializable
data class AmListMeta(
    val total: Int? = null,
)

@Serializable
data class AmLibrarySong(
    val id: String,
    val type: String,             // "library-songs"
    val attributes: AmLibrarySongAttributes,
)

@Serializable
data class AmLibrarySongAttributes(
    val name: String,
    val artistName: String,
    val albumName: String? = null,
    val durationInMillis: Long? = null,
    val artwork: AmArtwork? = null,
    val playParams: AmPlayParams? = null,
    /** ISO timestamp this track was added to the library. */
    val dateAdded: String? = null,
)

@Serializable
data class AmLibraryAlbumListResponse(
    val data: List<AmLibraryAlbum> = emptyList(),
    val next: String? = null,
    val meta: AmListMeta? = null,
)

@Serializable
data class AmLibraryAlbum(
    val id: String,
    val type: String,             // "library-albums"
    val attributes: AmLibraryAlbumAttributes,
)

@Serializable
data class AmLibraryAlbumAttributes(
    val name: String,
    val artistName: String,
    val artwork: AmArtwork? = null,
    val playParams: AmPlayParams? = null,
    val dateAdded: String? = null,
    val trackCount: Int = 0,
)

@Serializable
data class AmLibraryArtistListResponse(
    val data: List<AmLibraryArtist> = emptyList(),
    val next: String? = null,
    val meta: AmListMeta? = null,
)

@Serializable
data class AmLibraryArtist(
    val id: String,
    val type: String,             // "library-artists"
    val attributes: AmLibraryArtistAttributes,
)

@Serializable
data class AmLibraryArtistAttributes(
    val name: String,
)

// ── JSON models ──────────────────────────────────────────────────────

@Serializable
data class AmPlaylistListResponse(
    val data: List<AmPlaylist> = emptyList(),
    val next: String? = null,
)

@Serializable
data class AmPlaylist(
    val id: String,
    val type: String,
    val attributes: AmPlaylistAttributes,
)

@Serializable
data class AmPlaylistAttributes(
    val name: String,
    val description: AmDescription? = null,
    val canEdit: Boolean = false,
    val dateAdded: String? = null,
    val lastModifiedDate: String? = null,
    val playParams: AmPlayParams? = null,
    val artwork: AmArtwork? = null,
)

@Serializable
data class AmDescription(
    val standard: String? = null,
    val short: String? = null,
)

@Serializable
data class AmPlayParams(
    val id: String,
    val kind: String,
)

@Serializable
data class AmArtwork(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class AmPlaylistTrackListResponse(
    val data: List<AmTrack> = emptyList(),
    val next: String? = null,
)

@Serializable
data class AmTrack(
    val id: String,
    val type: String,
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
data class AmTracksRelationship(
    val data: List<AmTrackReference>,
)

@Serializable
data class AmTrackReference(
    val id: String,
    val type: String = "songs",
)

@Serializable
data class AmTracksRequest(
    val data: List<AmTrackReference>,
)

@Serializable
data class AmUpdatePlaylistRequest(
    val attributes: AmUpdatePlaylistAttributes,
)

@Serializable
data class AmUpdatePlaylistAttributes(
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class AmStorefrontResponse(
    val data: List<AmStorefront> = emptyList(),
)

@Serializable
data class AmStorefront(
    val id: String,
)
