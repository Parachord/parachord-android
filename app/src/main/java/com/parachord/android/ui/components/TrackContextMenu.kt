package com.parachord.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Context data for a track long-press action.
 * Contains all info needed to build the context menu and dispatch actions.
 */
data class TrackContextInfo(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val duration: Long? = null,
    /** Whether this track is already in the user's collection. */
    val isInCollection: Boolean = false,
    /** If this track is in a playlist, the playlist ID (enables "Remove from Playlist"). */
    val playlistId: String? = null,
    /** Position in playlist (for removal). */
    val playlistPosition: Int? = null,
)

/**
 * Modal bottom sheet context menu for tracks, matching the desktop app's
 * right-click menu functionality.
 *
 * Menu items (matching desktop):
 * - Play Next
 * - Add to Queue
 * - Add to Playlist...
 * - Go to Artist
 * - Go to Album (if album available)
 * - Add to Collection / Remove from Collection
 * - Remove from Playlist (if in a playlist context)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackContextMenu(
    track: TrackContextInfo,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onGoToArtist: () -> Unit,
    onGoToAlbum: (() -> Unit)? = null,
    onToggleCollection: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            // Track header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtCard(
                    artworkUrl = track.artworkUrl,
                    size = 48.dp,
                    cornerRadius = 4.dp,
                    elevation = 1.dp,
                    placeholderName = track.artist.ifBlank { track.title },
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Menu items
            ContextMenuItem(
                icon = Icons.Filled.SkipNext,
                label = "Play Next",
                onClick = { onPlayNext(); onDismiss() },
            )
            ContextMenuItem(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                label = "Add to Queue",
                onClick = { onAddToQueue(); onDismiss() },
            )
            ContextMenuItem(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                label = "Add to Playlist\u2026",
                onClick = { onAddToPlaylist() },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            ContextMenuItem(
                icon = Icons.Filled.Person,
                label = "Go to Artist",
                onClick = { onGoToArtist(); onDismiss() },
            )
            if (onGoToAlbum != null) {
                ContextMenuItem(
                    icon = Icons.Filled.Album,
                    label = "Go to Album",
                    onClick = { onGoToAlbum(); onDismiss() },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            if (track.isInCollection) {
                ContextMenuItem(
                    icon = Icons.Filled.LibraryAddCheck,
                    label = "Remove from Collection",
                    onClick = { onToggleCollection(); onDismiss() },
                )
            } else {
                ContextMenuItem(
                    icon = Icons.Filled.LibraryAdd,
                    label = "Add to Collection",
                    onClick = { onToggleCollection(); onDismiss() },
                )
            }

            if (onRemoveFromPlaylist != null) {
                ContextMenuItem(
                    icon = Icons.Filled.PlaylistRemove,
                    label = "Remove from Playlist",
                    onClick = { onRemoveFromPlaylist(); onDismiss() },
                )
            }
        }
    }
}

/**
 * A single row in a context menu bottom sheet.
 */
@Composable
fun ContextMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
