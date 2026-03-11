package com.parachord.android.data.scanner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.LibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ScanProgress(
    val isScanning: Boolean = false,
    val tracksFound: Int = 0,
    val albumsFound: Int = 0,
)

@Singleton
class MediaScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: LibraryRepository,
) {
    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    /**
     * Scans the device's MediaStore for audio files and inserts them into Room.
     * Returns the number of tracks found.
     */
    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        _progress.value = ScanProgress(isScanning = true)

        val tracks = mutableListOf<TrackEntity>()
        val albumMap = mutableMapOf<String, AlbumEntity>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown"
                val artist = cursor.getString(artistCol) ?: "Unknown"
                val album = cursor.getString(albumCol)
                val albumId = cursor.getLong(albumIdCol)
                val duration = cursor.getLong(durationCol)
                val filePath = cursor.getString(dataCol)

                val contentUri = ContentUris.withAppendedId(collection, mediaId)

                // Build album artwork URI
                val artworkUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId,
                ).toString()

                val albumIdStr = "local-album-$albumId"

                tracks.add(
                    TrackEntity(
                        id = "local-$mediaId",
                        title = title,
                        artist = artist,
                        album = album,
                        albumId = albumIdStr,
                        duration = duration,
                        artworkUrl = artworkUri,
                        sourceType = "local",
                        sourceUrl = contentUri.toString(),
                    )
                )

                if (album != null && albumIdStr !in albumMap) {
                    albumMap[albumIdStr] = AlbumEntity(
                        id = albumIdStr,
                        title = album,
                        artist = artist,
                        artworkUrl = artworkUri,
                    )
                }

                _progress.value = ScanProgress(
                    isScanning = true,
                    tracksFound = tracks.size,
                    albumsFound = albumMap.size,
                )
            }
        }

        // Batch insert into Room
        if (tracks.isNotEmpty()) {
            repository.addTracks(tracks)
        }
        if (albumMap.isNotEmpty()) {
            repository.addAlbums(albumMap.values.toList())
        }

        _progress.value = ScanProgress(
            isScanning = false,
            tracksFound = tracks.size,
            albumsFound = albumMap.size,
        )

        tracks.size
    }
}
