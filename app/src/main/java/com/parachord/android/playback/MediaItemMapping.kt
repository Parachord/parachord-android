package com.parachord.android.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.parachord.android.data.db.entity.TrackEntity

/**
 * Build an Android Auto / Media3 `MediaItem` from a [TrackEntity].
 *
 * Used by both [PlaybackController] (for ExoPlayer playback) and
 * [PlaybackService.ExternalPlaybackForwardingPlayer] (for the synthetic
 * timeline that surfaces the queue to Android Auto).
 *
 * - `mediaId` is the bare [TrackEntity.id] — matches the existing scheme
 *   that `PlaybackController.playFromQueue()` lookups expect.
 * - `MediaMetadata.MEDIA_TYPE_MUSIC` + `isPlayable=true` + `isBrowsable=false`
 *   are required for Auto to render the row as a tappable track.
 * - `artworkUri` is set only when [TrackEntity.artworkUrl] is non-null;
 *   Auto fetches over HTTPS for remote URIs and accepts `content://` for
 *   local files.
 */
fun TrackEntity.toAutoMediaItem(): MediaItem {
    val builder = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setIsPlayable(true)
        .setIsBrowsable(false)
    artworkUrl?.let { builder.setArtworkUri(Uri.parse(it)) }
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(sourceUrl ?: "")
        .setMediaMetadata(builder.build())
        .build()
}
