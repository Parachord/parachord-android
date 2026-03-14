package com.parachord.android.ui.screens.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ResolverIconSquare
import com.parachord.android.ui.theme.PlayerSurface
import com.parachord.android.ui.theme.PlayerTextPrimary
import com.parachord.android.ui.theme.PlayerTextSecondary
import com.parachord.android.ui.theme.PurpleDark

/**
 * Queue sheet content displayed below the now-playing controls.
 * Shows upcoming tracks with drag-to-reorder and swipe-to-dismiss.
 */
@Composable
fun QueueSheet(
    upNext: List<TrackEntity>,
    playbackContext: PlaybackContext?,
    onPlayFromQueue: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onClearQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PlayerSurface),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "UP NEXT",
                        style = MaterialTheme.typography.titleSmall,
                        color = PlayerTextPrimary,
                    )
                    if (upNext.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${upNext.size} track${if (upNext.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = PlayerTextSecondary,
                        )
                    }
                }
                if (playbackContext != null) {
                    val contextLabel = if (playbackContext.type == "listen-along") {
                        "Listening along with ${playbackContext.name}"
                    } else {
                        "Playing from: ${playbackContext.name}"
                    }
                    val contextColor = if (playbackContext.type == "listen-along") {
                        Color(0xFF34D399) // Green for listen-along, matching desktop
                    } else {
                        PurpleDark
                    }
                    Text(
                        text = contextLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = contextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (upNext.isNotEmpty()) {
                TextButton(onClick = onClearQueue) {
                    Text(
                        text = "Clear",
                        color = PlayerTextSecondary,
                    )
                }
            }
        }

        if (upNext.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Queue is empty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlayerTextSecondary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(
                    items = upNext,
                    key = { index, track -> "${track.id}-$index" },
                ) { index, track ->
                    QueueTrackRow(
                        track = track,
                        onTap = { onPlayFromQueue(index) },
                        onRemove = { onRemoveFromQueue(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueTrackRow(
    track: TrackEntity,
    onTap: () -> Unit,
    onRemove: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            onRemove()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Red.copy(alpha = 0.3f))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = Color.White,
                )
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PlayerSurface)
                .clickable(onClick = onTap)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag handle
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "Reorder",
                tint = PlayerTextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Album art
            AlbumArtCard(
                artworkUrl = track.artworkUrl,
                size = 40.dp,
                cornerRadius = 4.dp,
                elevation = 0.dp,
                placeholderName = track.artist.ifBlank { track.title },
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Title and artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PlayerTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = PlayerTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Resolver badge
            if (track.resolver != null) {
                Spacer(modifier = Modifier.width(8.dp))
                ResolverIconSquare(resolver = track.resolver!!, size = 20.dp)
            }
        }
    }
}
