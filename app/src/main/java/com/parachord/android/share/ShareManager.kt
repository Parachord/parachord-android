package com.parachord.android.share

import android.net.Uri
import android.util.Log
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.PlaylistTrackDao
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.db.entity.TrackEntity
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Builds shareable URLs for tracks, albums, playlists, and artists.
 *
 * Primary path: POST to the desktop Smart Links backend (`links.parachord.app`)
 * to mint a rich landing page — same infra desktop uses. The recipient gets
 * Open Graph previews + per-service "Play on Spotify/SoundCloud/Bandcamp"
 * buttons on every major platform that unfurls links.
 *
 * Fallback path: a `https://parachord.com/go?uri=parachord://...` redirect
 * (matches desktop's chat-share format) so the link always opens in Parachord
 * even if the smart-link API is down or we don't have enough metadata to
 * make a useful smart link (e.g. artists, which aren't smart-link-shaped).
 *
 * Network calls are bounded by [SMART_LINK_TIMEOUT_MS] so a slow API can't
 * hold up the share sheet — we silently fall back to the deeplink wrapper.
 */
class ShareManager constructor(
    private val smartLinkApi: SmartLinkApi,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
) {
    companion object {
        private const val TAG = "ShareManager"
        private const val SMART_LINK_TIMEOUT_MS = 4_000L
        private const val GO_REDIRECT = "https://parachord.com/go?uri="
    }

    data class ShareResult(
        val url: String,
        val subject: String,
        /** True when we got a smart-link URL; false when we fell back to the deeplink. */
        val isSmartLink: Boolean,
    )

    suspend fun shareTrack(track: TrackEntity): ShareResult {
        val subject = "${track.artist} – ${track.title}"
        val urls = buildMap<String, String> {
            track.spotifyId?.let { put("spotify", "https://open.spotify.com/track/$it") }
            track.appleMusicId?.let { put("applemusic", "https://music.apple.com/song/$it") }
            track.soundcloudId?.let { put("soundcloud", "https://soundcloud.com/$it") }
        }
        val smart = if (urls.isNotEmpty()) {
            tryCreateSmartLink(
                SmartLinkCreateRequest(
                    title = track.title,
                    artist = track.artist,
                    albumArt = track.artworkUrl,
                    type = "track",
                    urls = urls,
                )
            )
        } else null
        val url = smart ?: deepLinkWrapper("play", "artist=${enc(track.artist)}&title=${enc(track.title)}")
        return ShareResult(url, subject, smart != null)
    }

    suspend fun shareAlbum(
        title: String,
        artist: String,
        artworkUrl: String?,
        tracks: List<PlaylistTrackEntity>,
        spotifyAlbumId: String? = null,
    ): ShareResult {
        val subject = "$artist – $title"
        val albumUrls = buildMap<String, String> {
            spotifyAlbumId?.let { put("spotify", "https://open.spotify.com/album/$it") }
        }
        val smartTracks = tracks.map { it.toSmartLinkTrack() }
        val smart = if (smartTracks.isNotEmpty() || albumUrls.isNotEmpty()) {
            tryCreateSmartLink(
                SmartLinkCreateRequest(
                    title = title,
                    artist = artist,
                    albumArt = artworkUrl,
                    type = "album",
                    urls = albumUrls.takeIf { it.isNotEmpty() },
                    tracks = smartTracks.ifEmpty { null },
                )
            )
        } else null
        val url = smart ?: deepLinkWrapper(
            host = "album",
            // Path segments: album/{artist}/{title}; both URL-encoded.
            pathOrQuery = "/${enc(artist)}/${enc(title)}",
            isPath = true,
        )
        return ShareResult(url, subject, smart != null)
    }

    /**
     * Convenience for callsites that have a [playlistId] but no in-memory
     * track list — fetches both from the local DB before sharing. Returns
     * null when the playlist row doesn't exist anymore.
     */
    suspend fun sharePlaylist(playlistId: String): ShareResult? {
        val playlist = playlistDao.getById(playlistId) ?: return null
        val tracks = playlistTrackDao.getByPlaylistIdSync(playlistId)
        return sharePlaylist(playlist, tracks)
    }

    suspend fun sharePlaylist(playlist: PlaylistEntity, tracks: List<PlaylistTrackEntity>): ShareResult {
        val subject = playlist.name
        // If the playlist has been pushed to Spotify, include that URL on the
        // smart-link so recipients can open it directly in Spotify too.
        val playlistUrls = buildMap<String, String> {
            playlist.spotifyId?.let { put("spotify", "https://open.spotify.com/playlist/$it") }
        }
        val smart = tryCreateSmartLink(
            SmartLinkCreateRequest(
                title = playlist.name,
                creator = playlist.ownerName,
                albumArt = playlist.artworkUrl,
                type = "playlist",
                urls = playlistUrls.takeIf { it.isNotEmpty() },
                tracks = tracks.map { it.toSmartLinkTrack() }.ifEmpty { null },
            )
        )
        // Always fall back to the Parachord deeplink wrapper rather than to
        // a raw Spotify URL — even synced playlists should keep Parachord
        // branding on the share. Recipients with Spotify can still get
        // there via the smart-link page (which renders a Spotify button)
        // when the API is reachable.
        val url = smart ?: deepLinkWrapper("playlist", "/${enc(playlist.id)}", isPath = true)
        return ShareResult(url, subject, smart != null)
    }

    /**
     * Artists don't have a smart-link shape on desktop yet (per
     * `TODO-album-smart-links.md` in Parachord/parachord — even albums are
     * partial), so always fall back to the deeplink wrapper. Recipients with
     * Parachord installed jump straight into the Artist screen; everyone else
     * lands on the redirect page that explains what Parachord is.
     */
    fun shareArtist(name: String, imageUrl: String? = null): ShareResult {
        val subject = name
        val url = deepLinkWrapper("artist", "/${enc(name)}", isPath = true)
        return ShareResult(url, subject, isSmartLink = false)
    }

    private suspend fun tryCreateSmartLink(request: SmartLinkCreateRequest): String? {
        return try {
            withTimeout(SMART_LINK_TIMEOUT_MS) {
                smartLinkApi.create(request).url
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Smart link create timed out; falling back to deeplink for '${request.title}'")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Smart link create failed (${e.message}); falling back to deeplink for '${request.title}'")
            null
        }
    }

    private fun deepLinkWrapper(host: String, pathOrQuery: String, isPath: Boolean = false): String {
        val deeplink = if (isPath) "parachord://$host$pathOrQuery"
        else "parachord://$host?$pathOrQuery"
        return GO_REDIRECT + Uri.encode(deeplink)
    }

    private fun enc(value: String): String = Uri.encode(value)

    private fun PlaylistTrackEntity.toSmartLinkTrack(): SmartLinkTrack = SmartLinkTrack(
        title = trackTitle,
        artist = trackArtist,
        duration = trackDuration,
        urls = buildMap {
            trackSpotifyId?.let { put("spotify", "https://open.spotify.com/track/$it") }
            trackAppleMusicId?.let { put("applemusic", "https://music.apple.com/song/$it") }
            trackSoundcloudId?.let { put("soundcloud", "https://soundcloud.com/$it") }
        },
        albumArt = trackArtworkUrl,
    )
}
