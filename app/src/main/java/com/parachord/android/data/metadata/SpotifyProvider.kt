package com.parachord.android.data.metadata

import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.api.SpotifyApi
import com.parachord.android.data.api.bestImageUrl
import com.parachord.android.data.store.SettingsStore
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spotify metadata provider.
 * Best album art and preview URLs. Only available when user is authenticated.
 * Lowest priority since it requires OAuth and has rate limits.
 */
@Singleton
class SpotifyProvider @Inject constructor(
    private val api: SpotifyApi,
    private val settingsStore: SettingsStore,
    private val oAuthManager: OAuthManager,
) : MetadataProvider {

    override val name = "spotify"
    override val priority = 20

    override suspend fun isAvailable(): Boolean =
        settingsStore.getSpotifyAccessToken() != null

    override suspend fun searchTracks(query: String, limit: Int): List<TrackSearchResult> =
        withAuth { auth ->
            val response = api.search(auth = auth, query = query, type = "track", limit = limit)
            response.tracks?.items?.map { t ->
                TrackSearchResult(
                    title = t.name,
                    artist = t.artistName,
                    album = t.album?.name,
                    duration = t.durationMs,
                    artworkUrl = t.album?.images?.bestImageUrl(),
                    previewUrl = t.previewUrl,
                    provider = name,
                )
            } ?: emptyList()
        } ?: emptyList()

    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumSearchResult> =
        withAuth { auth ->
            val response = api.search(auth = auth, query = query, type = "album", limit = limit)
            response.albums?.items?.map { a ->
                AlbumSearchResult(
                    title = a.name,
                    artist = a.artistName,
                    artworkUrl = a.images.bestImageUrl(),
                    year = a.year,
                    trackCount = a.totalTracks,
                    provider = name,
                )
            } ?: emptyList()
        } ?: emptyList()

    override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> =
        withAuth { auth ->
            val response = api.search(auth = auth, query = query, type = "artist", limit = limit)
            response.artists?.items?.map { a ->
                ArtistInfo(
                    name = a.name,
                    imageUrl = a.images.bestImageUrl(),
                    tags = a.genres,
                    provider = name,
                )
            } ?: emptyList()
        } ?: emptyList()

    override suspend fun getArtistInfo(artistName: String): ArtistInfo? =
        withAuth { auth ->
            // Search to find artist ID, then fetch full artist for reliable images
            val response = api.search(auth = auth, query = artistName, type = "artist", limit = 1)
            val searchArtist = response.artists?.items?.firstOrNull() ?: return@withAuth null
            val fullArtist = api.getArtist(auth = auth, artistId = searchArtist.id)
            ArtistInfo(
                name = fullArtist.name,
                imageUrl = fullArtist.images.bestImageUrl(),
                tags = fullArtist.genres,
                provider = name,
            )
        }

    override suspend fun getArtistTopTracks(artistName: String, limit: Int): List<TrackSearchResult> =
        withAuth { auth ->
            val response = api.search(auth = auth, query = artistName, type = "artist", limit = 1)
            val artistId = response.artists?.items?.firstOrNull()?.id ?: return@withAuth emptyList()
            val topTracks = api.getArtistTopTracks(auth = auth, artistId = artistId)
            topTracks.tracks.take(limit).map { t ->
                TrackSearchResult(
                    title = t.name,
                    artist = t.artistName,
                    album = t.album?.name,
                    duration = t.durationMs,
                    artworkUrl = t.album?.images?.bestImageUrl(),
                    previewUrl = t.previewUrl,
                    spotifyId = t.id,
                    provider = name,
                )
            }
        } ?: emptyList()

    override suspend fun getArtistAlbums(artistName: String, limit: Int): List<AlbumSearchResult> =
        withAuth { auth ->
            val response = api.search(auth = auth, query = artistName, type = "artist", limit = 1)
            val artistId = response.artists?.items?.firstOrNull()?.id ?: return@withAuth emptyList()
            val albums = api.getArtistAlbums(auth = auth, artistId = artistId, limit = limit)
            albums.items.map { a ->
                AlbumSearchResult(
                    title = a.name,
                    artist = a.artistName,
                    artworkUrl = a.images.bestImageUrl(),
                    year = a.year,
                    trackCount = a.totalTracks,
                    spotifyId = a.id,
                    releaseType = a.albumType,
                    provider = name,
                )
            }
        } ?: emptyList()

    override suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? =
        withAuth { auth ->
            val response = api.search(
                auth = auth,
                query = "album:$albumTitle artist:$artistName",
                type = "album",
                limit = 1,
            )
            val album = response.albums?.items?.firstOrNull() ?: return@withAuth null
            val tracksResponse = api.getAlbumTracks(auth = auth, albumId = album.id)
            AlbumDetail(
                title = album.name,
                artist = album.artistName,
                artworkUrl = album.images.bestImageUrl(),
                year = album.year,
                tracks = tracksResponse.items.map { t ->
                    TrackSearchResult(
                        title = t.name,
                        artist = t.artistName,
                        album = album.name,
                        duration = t.durationMs,
                        artworkUrl = album.images.bestImageUrl(),
                        previewUrl = t.previewUrl,
                        spotifyId = t.id,
                        provider = name,
                    )
                },
                provider = name,
            )
        }

    /**
     * Execute a block with a valid Spotify access token.
     * On HTTP 401, refreshes the token and retries once.
     */
    private suspend fun <T> withAuth(block: suspend (auth: String) -> T): T? {
        val token = settingsStore.getSpotifyAccessToken() ?: return null
        return try {
            block("Bearer $token")
        } catch (e: HttpException) {
            if (e.code() == 401 && oAuthManager.refreshSpotifyToken()) {
                val newToken = settingsStore.getSpotifyAccessToken() ?: return null
                block("Bearer $newToken")
            } else null
        }
    }
}
