package com.parachord.android.playlist

import android.util.Log
import com.parachord.android.auth.OAuthManager
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.api.bestImageUrl
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.handlers.MusicKitWebBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

private const val TAG = "PlaylistImportManager"

data class ImportResult(
    val playlistId: String,
    val playlistName: String,
    val trackCount: Int,
)

class PlaylistImportManager constructor(
    private val spotifyClient: SpotifyClient,
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
     * Pre-flight gate for Spotify import. Per Phase 9E.1.8 the Ktor `SpotifyClient`
     * resolves the Bearer token from `AuthTokenProvider` per-request, and the global
     * `OAuthRefreshPlugin` handles 401-driven refresh + retry on `api.spotify.com`.
     *
     * `ReauthRequiredException` from the plugin maps to "reconnect Spotify". Other
     * exceptions surface as a generic Spotify error so the import UI can show
     * something user-actionable.
     */
    private suspend fun <T> withSpotifyAuth(block: suspend () -> T): T {
        if (settingsStore.getSpotifyAccessToken() == null) {
            throw IllegalStateException("Spotify not connected. Connect Spotify in Settings to import playlists.")
        }
        return try {
            block()
        } catch (e: com.parachord.shared.api.auth.ReauthRequiredException) {
            throw IllegalStateException("Spotify session expired. Reconnect Spotify in Settings.")
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Spotify import failed: ${e.message ?: e::class.simpleName}", e)
        }
    }

    private suspend fun importSpotifyPlaylist(url: String): ImportResult {
        val playlistId = extractSpotifyPlaylistId(url)
            ?: throw IllegalArgumentException("Could not extract Spotify playlist ID from URL")

        val playlist = withSpotifyAuth { spotifyClient.getPlaylist(playlistId) }
        val playlistName = playlist.name ?: "Spotify Playlist"
        val artworkUrl = playlist.images?.firstOrNull()?.url

        val allTracks = mutableListOf<TrackEntity>()
        var offset = 0
        var hasMore = true
        while (hasMore) {
            val page = withSpotifyAuth {
                spotifyClient.getPlaylistTracks(playlistId, limit = 100, offset = offset)
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
        // security: H10 — require HTTPS and block private-network SSRF targets
        validateXspfUrl(url)
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
            sourceUrl = url,
            sourceContentHash = sha256Hex(content),
        )
    }


    private suspend fun savePlaylist(
        name: String,
        source: String,
        tracks: List<TrackEntity>,
        artworkUrl: String?,
        sourceUrl: String? = null,
        sourceContentHash: String? = null,
    ): ImportResult {
        val playlistId = "${source}-${UUID.randomUUID()}"
        val playlist = PlaylistEntity(
            id = playlistId,
            name = name,
            artworkUrl = artworkUrl ?: tracks.firstOrNull()?.artworkUrl,
            trackCount = tracks.size,
            sourceUrl = sourceUrl,
            sourceContentHash = sourceContentHash,
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
