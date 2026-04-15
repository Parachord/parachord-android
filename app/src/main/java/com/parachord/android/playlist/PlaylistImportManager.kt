package com.parachord.android.playlist

import android.util.Log
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.api.SpotifyApi
import com.parachord.android.data.api.bestImageUrl
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.handlers.MusicKitWebBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.util.UUID

private const val TAG = "PlaylistImportManager"

data class ImportResult(
    val playlistId: String,
    val playlistName: String,
    val trackCount: Int,
)

class PlaylistImportManager constructor(
    private val spotifyApi: SpotifyApi,
    private val musicKitBridge: MusicKitWebBridge,
    private val libraryRepository: LibraryRepository,
    private val settingsStore: SettingsStore,
    private val httpClient: OkHttpClient,
    private val oAuthManager: OAuthManager,
) {
    suspend fun importFromUrl(url: String): ImportResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Importing playlist from URL: $url")
        when {
            isSpotifyPlaylistUrl(url) -> importSpotifyPlaylist(url)
            isAppleMusicPlaylistUrl(url) -> importAppleMusicPlaylist(url)
            else -> importXspfFromUrl(url)
        }
    }

    suspend fun importFromXspfContent(content: String, filename: String? = null): ImportResult =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Importing playlist from XSPF content (filename=$filename)")
            val parsed = XspfParser.parse(content)
            savePlaylist(
                name = parsed.title,
                source = "imported-xspf",
                tracks = parsed.tracks,
                artworkUrl = null,
            )
        }

    private fun isSpotifyPlaylistUrl(url: String): Boolean =
        url.contains("open.spotify.com/") && url.contains("/playlist/") ||
            url.startsWith("spotify:playlist:")

    private fun isAppleMusicPlaylistUrl(url: String): Boolean =
        url.contains("music.apple.com") && url.contains("/playlist/")

    /**
     * Execute a Spotify API call with automatic token refresh on 401.
     * Mirrors SpotifyProvider.withAuth() and ResolverManager.resolveSpotify().
     */
    private suspend fun <T> withSpotifyAuth(block: suspend (auth: String) -> T): T {
        val token = settingsStore.getSpotifyAccessToken()
            ?: throw IllegalStateException("Spotify not connected. Connect Spotify in Settings to import playlists.")
        return try {
            block("Bearer $token")
        } catch (e: HttpException) {
            if (e.code() == 401 && oAuthManager.refreshSpotifyToken()) {
                val newToken = settingsStore.getSpotifyAccessToken()
                    ?: throw IllegalStateException("Spotify session expired. Reconnect Spotify in Settings.")
                block("Bearer $newToken")
            } else {
                when (e.code()) {
                    401 -> throw IllegalStateException("Spotify session expired. Reconnect Spotify in Settings.")
                    403 -> throw IllegalStateException("Spotify access denied. Reconnect Spotify in Settings to grant updated permissions.")
                    404 -> throw IllegalArgumentException("Playlist not found. It may be private or deleted.")
                    else -> throw IllegalStateException("Spotify error: HTTP ${e.code()}")
                }
            }
        }
    }

    private suspend fun importSpotifyPlaylist(url: String): ImportResult {
        val playlistId = extractSpotifyPlaylistId(url)
            ?: throw IllegalArgumentException("Could not extract Spotify playlist ID from URL")

        val playlist = withSpotifyAuth { auth -> spotifyApi.getPlaylist(auth, playlistId) }
        val playlistName = playlist.name ?: "Spotify Playlist"
        val artworkUrl = playlist.images?.firstOrNull()?.url

        val allTracks = mutableListOf<TrackEntity>()
        var offset = 0
        var hasMore = true
        while (hasMore) {
            val page = withSpotifyAuth { auth ->
                spotifyApi.getPlaylistTracks(auth, playlistId, limit = 100, offset = offset)
            }
            page.items.forEach { item ->
                val track = item.track ?: return@forEach
                allTracks.add(
                    TrackEntity(
                        id = "spotify:${track.id}",
                        title = track.name ?: "Unknown",
                        artist = track.artistName,
                        album = track.album?.name,
                        albumId = track.album?.id,
                        duration = track.durationMs,
                        artworkUrl = track.album?.images.bestImageUrl(),
                        resolver = "spotify",
                        spotifyId = track.id,
                        spotifyUri = "spotify:track:${track.id}",
                    )
                )
            }
            offset += page.items.size
            hasMore = page.next != null && page.items.isNotEmpty()
        }

        return savePlaylist(
            name = playlistName,
            source = "spotify-import",
            tracks = allTracks,
            artworkUrl = artworkUrl,
        )
    }

    private fun extractSpotifyPlaylistId(url: String): String? {
        if (url.startsWith("spotify:playlist:")) {
            return url.removePrefix("spotify:playlist:")
        }
        val regex = Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?playlist/([a-zA-Z0-9]+)")
        return regex.find(url)?.groupValues?.get(1)
    }

    private suspend fun importAppleMusicPlaylist(url: String): ImportResult {
        val playlistId = extractAppleMusicPlaylistId(url)
            ?: throw IllegalArgumentException("Could not extract Apple Music playlist ID from URL")
        if (!musicKitBridge.configured.value) {
            throw IllegalStateException("Apple Music not configured. Set up Apple Music in Settings to import playlists.")
        }
        val result = musicKitBridge.getPlaylist(playlistId)
            ?: throw IllegalStateException("Could not load Apple Music playlist. It may be private or unavailable in your region.")
        val tracks = result.tracks.map { t ->
            TrackEntity(
                id = "applemusic:${t.id}",
                title = t.title,
                artist = t.artist,
                album = t.album,
                duration = t.duration,
                artworkUrl = t.artworkUrl,
                resolver = "applemusic",
                appleMusicId = t.id,
            )
        }
        return savePlaylist(
            name = result.name,
            source = "applemusic-import",
            tracks = tracks,
            artworkUrl = tracks.firstOrNull()?.artworkUrl,
        )
    }

    private fun extractAppleMusicPlaylistId(url: String): String? {
        val regex = Regex("music\\.apple\\.com/[a-z]{2}/playlist/[^/]+/(pl\\.[a-zA-Z0-9]+)")
        return regex.find(url)?.groupValues?.get(1)
    }

    private suspend fun importXspfFromUrl(url: String): ImportResult {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to fetch playlist: HTTP ${response.code}")
        }
        val content = response.body?.string()
            ?: throw IllegalStateException("Empty response from URL")
        if (!content.contains("<playlist") || !content.contains("</playlist>")) {
            throw IllegalArgumentException("URL does not contain a valid XSPF playlist")
        }
        val parsed = XspfParser.parse(content)
        return savePlaylist(
            name = parsed.title,
            source = "hosted-xspf",
            tracks = parsed.tracks,
            artworkUrl = null,
        )
    }

    private suspend fun savePlaylist(
        name: String,
        source: String,
        tracks: List<TrackEntity>,
        artworkUrl: String?,
    ): ImportResult {
        val playlistId = "${source}-${UUID.randomUUID()}"
        val playlist = PlaylistEntity(
            id = playlistId,
            name = name,
            artworkUrl = artworkUrl ?: tracks.firstOrNull()?.artworkUrl,
            trackCount = tracks.size,
        )
        libraryRepository.createPlaylistWithTracks(playlist, tracks)
        Log.d(TAG, "Imported playlist '$name' with ${tracks.size} tracks (id=$playlistId)")
        return ImportResult(
            playlistId = playlistId,
            playlistName = name,
            trackCount = tracks.size,
        )
    }
}
