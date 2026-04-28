package com.parachord.android.deeplink

import android.util.Log
import com.parachord.shared.api.AppleMusicClient
import com.parachord.android.data.api.SpotifyApi
import com.parachord.android.data.api.bestImageUrl
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore

private const val TAG = "ExternalLinkResolver"

/**
 * Resolves external Spotify / Apple Music URLs into metadata.
 *
 * Desktop equivalent:
 * - Spotify: spotify.axe -> lookupUrl() calls /v1/tracks/{id}, /v1/albums/{id}
 * - Apple Music: apple-music.axe -> lookupUrl() calls iTunes /lookup?id={id}
 */
class ExternalLinkResolver constructor(
    private val spotifyApi: SpotifyApi,
    private val appleMusicClient: AppleMusicClient,
    private val settingsStore: SettingsStore,
) {
    data class TrackResult(val track: TrackEntity)

    data class AlbumResult(
        val title: String,
        val artist: String,
        val artworkUrl: String? = null,
        val tracks: List<TrackEntity> = emptyList(),
    )

    data class PlaylistResult(
        val name: String,
        val tracks: List<TrackEntity> = emptyList(),
    )

    data class ArtistResult(val name: String)

    private suspend fun spotifyAuth(): String? {
        val token = settingsStore.getSpotifyAccessToken()
        return if (token.isNullOrBlank()) null else "Bearer $token"
    }

    // -- Spotify ---------------------------------------------------------------

    suspend fun resolveSpotifyTrack(trackId: String): TrackResult? = try {
        val auth = spotifyAuth() ?: throw IllegalStateException("No Spotify token")
        val track = spotifyApi.getTrack(auth, trackId)
        val artworkUrl = track.album?.images.bestImageUrl()
        TrackResult(
            track = TrackEntity(
                id = "spotify:$trackId",
                title = track.name ?: "Unknown",
                artist = track.artistName,
                album = track.album?.name,
                albumId = track.album?.id,
                duration = track.durationMs,
                artworkUrl = artworkUrl,
                resolver = "spotify",
                spotifyId = trackId,
                spotifyUri = "spotify:track:$trackId",
            ),
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve Spotify track $trackId", e)
        null
    }

    suspend fun resolveSpotifyAlbum(albumId: String): AlbumResult? = try {
        val auth = spotifyAuth() ?: throw IllegalStateException("No Spotify token")
        val albumTracks = spotifyApi.getAlbumTracks(auth, albumId)
        // Get album metadata from the first track's full details
        val firstTrackId = albumTracks.items.firstOrNull()?.id
        val albumMeta = if (firstTrackId != null) {
            spotifyApi.getTrack(auth, firstTrackId)
        } else null
        val artworkUrl = albumMeta?.album?.images.bestImageUrl()
        val albumName = albumMeta?.album?.name ?: "Unknown Album"
        val artistName = albumMeta?.artistName ?: albumTracks.items.firstOrNull()?.artistName ?: "Unknown"

        AlbumResult(
            title = albumName,
            artist = artistName,
            artworkUrl = artworkUrl,
            tracks = albumTracks.items.map { item ->
                TrackEntity(
                    id = "spotify:${item.id}",
                    title = item.name ?: "Unknown",
                    artist = item.artistName,
                    album = albumName,
                    albumId = albumId,
                    duration = item.durationMs,
                    artworkUrl = artworkUrl,
                    resolver = "spotify",
                    spotifyId = item.id,
                    spotifyUri = "spotify:track:${item.id}",
                )
            },
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve Spotify album $albumId", e)
        null
    }

    suspend fun resolveSpotifyPlaylist(playlistId: String): PlaylistResult? = try {
        val auth = spotifyAuth() ?: throw IllegalStateException("No Spotify token")
        val playlist = spotifyApi.getPlaylist(auth, playlistId)
        val tracks = spotifyApi.getPlaylistTracks(auth, playlistId)
        PlaylistResult(
            name = playlist.name ?: "Playlist",
            tracks = tracks.items.mapNotNull { item ->
                val track = item.track ?: return@mapNotNull null
                val artworkUrl = track.album?.images.bestImageUrl()
                TrackEntity(
                    id = "spotify:${track.id}",
                    title = track.name ?: "Unknown",
                    artist = track.artistName,
                    album = track.album?.name,
                    albumId = track.album?.id,
                    duration = track.durationMs,
                    artworkUrl = artworkUrl,
                    resolver = "spotify",
                    spotifyId = track.id,
                    spotifyUri = "spotify:track:${track.id}",
                )
            },
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve Spotify playlist $playlistId", e)
        null
    }

    suspend fun resolveSpotifyArtist(artistId: String): ArtistResult? = try {
        val auth = spotifyAuth() ?: throw IllegalStateException("No Spotify token")
        val artist = spotifyApi.getArtist(auth, artistId)
        ArtistResult(name = artist.name ?: "Unknown Artist")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve Spotify artist $artistId", e)
        null
    }

    // -- Apple Music -----------------------------------------------------------

    suspend fun resolveAppleMusicSong(songId: String): TrackResult? = try {
        val response = appleMusicClient.lookup(songId)
        val item = response.results.firstOrNull { it.wrapperType == "track" || it.kind == "song" }
            ?: throw IllegalStateException("No track found for ID $songId")
        val artworkUrl = item.artworkUrl100?.replace("100x100", "600x600")
        TrackResult(
            track = TrackEntity(
                id = "applemusic:$songId",
                title = item.trackName ?: "Unknown",
                artist = item.artistName ?: "Unknown",
                album = item.collectionName,
                duration = item.trackTimeMillis,
                artworkUrl = artworkUrl,
                resolver = "applemusic",
                appleMusicId = songId,
            ),
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve Apple Music song $songId", e)
        null
    }

    suspend fun resolveAppleMusicAlbum(albumId: String): AlbumResult? = try {
        val response = appleMusicClient.lookup(albumId, entity = "song")
        val collection = response.results.firstOrNull { it.wrapperType == "collection" }
        val songs = response.results.filter { it.wrapperType == "track" || it.kind == "song" }
        val albumName = collection?.collectionName ?: songs.firstOrNull()?.collectionName ?: "Unknown"
        val artistName = collection?.artistName ?: songs.firstOrNull()?.artistName ?: "Unknown"
        val artworkUrl = (collection?.artworkUrl100 ?: songs.firstOrNull()?.artworkUrl100)
            ?.replace("100x100", "600x600")

        AlbumResult(
            title = albumName,
            artist = artistName,
            artworkUrl = artworkUrl,
            tracks = songs
                .sortedWith(compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: 0 }))
                .map { item ->
                    TrackEntity(
                        id = "applemusic:${item.trackId}",
                        title = item.trackName ?: "Unknown",
                        artist = item.artistName ?: artistName,
                        album = albumName,
                        duration = item.trackTimeMillis,
                        artworkUrl = item.artworkUrl100?.replace("100x100", "600x600") ?: artworkUrl,
                        resolver = "applemusic",
                        appleMusicId = item.trackId?.toString(),
                    )
                },
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve Apple Music album $albumId", e)
        null
    }

    suspend fun resolveAppleMusicPlaylist(playlistId: String): PlaylistResult? {
        // iTunes API doesn't support curator playlist lookups (pl.xxx IDs).
        // Can be enhanced with MusicKit JS bridge later.
        Log.w(TAG, "Apple Music playlist lookup not supported via iTunes API: $playlistId")
        return null
    }
}
