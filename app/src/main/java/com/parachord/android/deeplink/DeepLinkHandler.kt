package com.parachord.android.deeplink

import android.net.Uri
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DeepLinkHandler"

/**
 * All possible actions from a deep link or external URL.
 */
sealed class DeepLinkAction {
    // ── Playback ──
    data class Play(val artist: String, val title: String) : DeepLinkAction()
    data class Control(val action: String) : DeepLinkAction()
    data class QueueAdd(val artist: String, val title: String, val album: String?) : DeepLinkAction()
    data object QueueClear : DeepLinkAction()
    data class Shuffle(val enabled: Boolean) : DeepLinkAction()
    data class Volume(val level: Int) : DeepLinkAction()

    // ── Navigation ──
    data object NavigateHome : DeepLinkAction()
    data class NavigateArtist(val name: String, val tab: String? = null) : DeepLinkAction()
    data class NavigateAlbum(val artist: String, val title: String) : DeepLinkAction()
    data class NavigateLibrary(val tab: String? = null) : DeepLinkAction()
    data class NavigateHistory(val tab: String? = null, val period: String? = null) : DeepLinkAction()
    data class NavigateFriend(val id: String, val tab: String? = null) : DeepLinkAction()
    data class NavigateRecommendations(val tab: String? = null) : DeepLinkAction()
    data object NavigateCharts : DeepLinkAction()
    data object NavigateCriticalDarlings : DeepLinkAction()
    data object NavigatePlaylists : DeepLinkAction()
    data class NavigatePlaylist(val id: String) : DeepLinkAction()
    data class NavigateSettings(val tab: String? = null) : DeepLinkAction()
    data class NavigateSearch(val query: String?, val source: String? = null) : DeepLinkAction()
    data class NavigateChat(val prompt: String? = null) : DeepLinkAction()

    // ── Import ──
    data class ImportPlaylist(val url: String) : DeepLinkAction()

    // ── External URL lookups ──
    data class SpotifyTrack(val trackId: String) : DeepLinkAction()
    data class SpotifyAlbum(val albumId: String) : DeepLinkAction()
    data class SpotifyPlaylist(val playlistId: String) : DeepLinkAction()
    data class SpotifyArtist(val artistId: String) : DeepLinkAction()
    data class AppleMusicSong(val songId: String) : DeepLinkAction()
    data class AppleMusicAlbum(val albumId: String) : DeepLinkAction()
    data class AppleMusicPlaylist(val playlistId: String) : DeepLinkAction()

    // ── Auth (pass-through) ──
    data class OAuthCallback(val uri: Uri) : DeepLinkAction()

    // ── Unknown ──
    data class Unknown(val uri: Uri) : DeepLinkAction()
}

/**
 * Parses incoming URIs into [DeepLinkAction]s.
 *
 * Supports:
 * - parachord:// protocol URLs (play, control, queue, shuffle, volume, navigation, import)
 * - Spotify web URLs (open.spotify.com/track/, /album/, /playlist/, /artist/)
 * - Spotify URIs (spotify:track:, spotify:album:, etc.)
 * - Apple Music URLs (music.apple.com/.../album/, /song/, /playlist/)
 *
 * Desktop equivalent: protocol URL handler in app.js:9648-10122,
 * external URL parsing in resolver-loader.js:217-413
 */
@Singleton
class DeepLinkHandler @Inject constructor() {

    fun parse(uri: Uri): DeepLinkAction {
        Log.d(TAG, "Parsing URI: $uri")

        return when (uri.scheme) {
            "parachord" -> parseParachord(uri)
            "spotify" -> parseSpotifyUri(uri)
            "https", "http" -> parseHttpUrl(uri)
            else -> DeepLinkAction.Unknown(uri)
        }
    }

    private fun parseParachord(uri: Uri): DeepLinkAction {
        val host = uri.host ?: return DeepLinkAction.Unknown(uri)
        val pathSegments = uri.pathSegments

        return when (host) {
            "auth" -> DeepLinkAction.OAuthCallback(uri)

            "play" -> {
                val artist = uri.getQueryParameter("artist") ?: return DeepLinkAction.Unknown(uri)
                val title = uri.getQueryParameter("title") ?: return DeepLinkAction.Unknown(uri)
                DeepLinkAction.Play(artist, title)
            }

            "control" -> {
                val action = pathSegments.firstOrNull() ?: return DeepLinkAction.Unknown(uri)
                DeepLinkAction.Control(action)
            }

            "queue" -> {
                when (pathSegments.firstOrNull()) {
                    "add" -> {
                        val artist = uri.getQueryParameter("artist") ?: return DeepLinkAction.Unknown(uri)
                        val title = uri.getQueryParameter("title") ?: return DeepLinkAction.Unknown(uri)
                        val album = uri.getQueryParameter("album")
                        DeepLinkAction.QueueAdd(artist, title, album)
                    }
                    "clear" -> DeepLinkAction.QueueClear
                    else -> DeepLinkAction.Unknown(uri)
                }
            }

            "shuffle" -> {
                when (pathSegments.firstOrNull()) {
                    "on" -> DeepLinkAction.Shuffle(enabled = true)
                    "off" -> DeepLinkAction.Shuffle(enabled = false)
                    else -> DeepLinkAction.Unknown(uri)
                }
            }

            "volume" -> {
                val level = pathSegments.firstOrNull()?.toIntOrNull()
                    ?: return DeepLinkAction.Unknown(uri)
                DeepLinkAction.Volume(level.coerceIn(0, 100))
            }

            "home" -> DeepLinkAction.NavigateHome
            "artist" -> {
                val name = pathSegments.firstOrNull() ?: return DeepLinkAction.Unknown(uri)
                val tab = pathSegments.getOrNull(1)
                DeepLinkAction.NavigateArtist(name, tab)
            }
            "album" -> {
                val artist = pathSegments.getOrNull(0) ?: return DeepLinkAction.Unknown(uri)
                val title = pathSegments.getOrNull(1) ?: return DeepLinkAction.Unknown(uri)
                DeepLinkAction.NavigateAlbum(artist, title)
            }
            "library" -> {
                val tab = pathSegments.firstOrNull()
                DeepLinkAction.NavigateLibrary(tab)
            }
            "history" -> {
                val tab = pathSegments.firstOrNull()
                val period = uri.getQueryParameter("period")
                DeepLinkAction.NavigateHistory(tab, period)
            }
            "friend" -> {
                val id = pathSegments.firstOrNull() ?: return DeepLinkAction.Unknown(uri)
                val tab = pathSegments.getOrNull(1)
                DeepLinkAction.NavigateFriend(id, tab)
            }
            "recommendations" -> {
                val tab = pathSegments.firstOrNull()
                DeepLinkAction.NavigateRecommendations(tab)
            }
            "charts" -> DeepLinkAction.NavigateCharts
            "critics-picks" -> DeepLinkAction.NavigateCriticalDarlings
            "playlists" -> DeepLinkAction.NavigatePlaylists
            "playlist" -> {
                val id = pathSegments.firstOrNull() ?: return DeepLinkAction.Unknown(uri)
                DeepLinkAction.NavigatePlaylist(id)
            }
            "settings" -> {
                val tab = pathSegments.firstOrNull()
                DeepLinkAction.NavigateSettings(tab)
            }
            "search" -> {
                val query = uri.getQueryParameter("q")
                val source = uri.getQueryParameter("source")
                DeepLinkAction.NavigateSearch(query, source)
            }
            "chat" -> {
                val prompt = uri.getQueryParameter("prompt")
                DeepLinkAction.NavigateChat(prompt)
            }
            "import" -> {
                val url = uri.getQueryParameter("url") ?: return DeepLinkAction.Unknown(uri)
                DeepLinkAction.ImportPlaylist(url)
            }

            else -> DeepLinkAction.Unknown(uri)
        }
    }

    private fun parseSpotifyUri(uri: Uri): DeepLinkAction {
        // Handle Branch.io referrer format used by Spotify's link sharing:
        // spotify://open?_branch_referrer=<gzip+base64 encoded data>
        // The referrer contains $full_url=https://open.spotify.com/artist/xxx
        if (uri.host == "open" && uri.getQueryParameter("_branch_referrer") != null) {
            val extracted = extractSpotifyUrlFromBranchReferrer(
                uri.getQueryParameter("_branch_referrer")!!
            )
            if (extracted != null) {
                Log.d(TAG, "Extracted Spotify URL from Branch referrer: $extracted")
                return parseSpotifyUrl(Uri.parse(extracted))
            }
        }

        // Standard format: spotify:track:6rqhFgbbKwnb9MLmUQDhG6
        val ssp = uri.schemeSpecificPart ?: return DeepLinkAction.Unknown(uri)
        val parts = ssp.split(":")
        if (parts.size < 2) return DeepLinkAction.Unknown(uri)

        return when (parts[0]) {
            "track" -> DeepLinkAction.SpotifyTrack(parts[1])
            "album" -> DeepLinkAction.SpotifyAlbum(parts[1])
            "playlist" -> DeepLinkAction.SpotifyPlaylist(parts[1])
            "artist" -> DeepLinkAction.SpotifyArtist(parts[1])
            else -> DeepLinkAction.Unknown(uri)
        }
    }

    /**
     * Spotify's link sharing wraps real URLs in a Branch.io referrer:
     * gzip-compressed, base64-encoded data containing $full_url parameter.
     * Decode it to extract the actual open.spotify.com URL.
     */
    private fun extractSpotifyUrlFromBranchReferrer(referrer: String): String? = try {
        val compressed = android.util.Base64.decode(referrer, android.util.Base64.DEFAULT)
        val decompressed = java.util.zip.GZIPInputStream(compressed.inputStream())
            .bufferedReader().readText()
        // Parse the decompressed URL and extract $full_url parameter
        val refUri = Uri.parse(decompressed)
        val fullUrl = refUri.getQueryParameter("\$full_url")
            ?: refUri.getQueryParameter("\$fallback_url")
        // Strip tracking params to get clean Spotify URL
        if (fullUrl != null) {
            val spotifyUri = Uri.parse(fullUrl)
            "${spotifyUri.scheme}://${spotifyUri.host}${spotifyUri.path}"
        } else null
    } catch (e: Exception) {
        Log.w(TAG, "Failed to decode Branch referrer: ${e.message}")
        null
    }

    private fun parseHttpUrl(uri: Uri): DeepLinkAction {
        return when (uri.host) {
            "open.spotify.com" -> parseSpotifyUrl(uri)
            "music.apple.com" -> parseAppleMusicUrl(uri)
            else -> DeepLinkAction.Unknown(uri)
        }
    }

    private fun parseSpotifyUrl(uri: Uri): DeepLinkAction {
        val segments = uri.pathSegments
        if (segments.size < 2) return DeepLinkAction.Unknown(uri)

        // Handle /intl-xx/ prefix (e.g., /intl-en/track/xxx)
        val (type, id) = if (segments[0].startsWith("intl-") && segments.size >= 3) {
            segments[1] to segments[2]
        } else {
            segments[0] to segments[1]
        }

        return when (type) {
            "track" -> DeepLinkAction.SpotifyTrack(id)
            "album" -> DeepLinkAction.SpotifyAlbum(id)
            "playlist" -> DeepLinkAction.SpotifyPlaylist(id)
            "artist" -> DeepLinkAction.SpotifyArtist(id)
            else -> DeepLinkAction.Unknown(uri)
        }
    }

    private fun parseAppleMusicUrl(uri: Uri): DeepLinkAction {
        val segments = uri.pathSegments
        if (segments.size < 3) return DeepLinkAction.Unknown(uri)

        // Skip country code (first segment)
        val type = segments[1]
        val id = segments.lastOrNull() ?: return DeepLinkAction.Unknown(uri)

        val songId = uri.getQueryParameter("i")

        return when (type) {
            "album" -> {
                if (songId != null) {
                    DeepLinkAction.AppleMusicSong(songId)
                } else {
                    DeepLinkAction.AppleMusicAlbum(id)
                }
            }
            "song" -> DeepLinkAction.AppleMusicSong(id)
            "playlist" -> DeepLinkAction.AppleMusicPlaylist(id)
            "artist" -> {
                val artistName = if (segments.size >= 4) segments[2] else id
                DeepLinkAction.NavigateArtist(artistName.replace("-", " "))
            }
            else -> DeepLinkAction.Unknown(uri)
        }
    }
}
