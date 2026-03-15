package com.parachord.android.ui.screens.playlists

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ContextMenuItem
import com.parachord.android.ui.components.ModalBg
import com.parachord.android.ui.components.ModalBgDarker
import com.parachord.android.ui.components.ModalDivider
import com.parachord.android.ui.components.ModalTextActive
import com.parachord.android.ui.components.ModalTextPrimary
import com.parachord.android.ui.components.TrackContextInfo
import com.parachord.android.ui.components.TrackContextMenuHost
import com.parachord.android.ui.components.TrackRow
import com.parachord.android.ui.components.rememberTrackContextMenuState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val allPlaylists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val nowPlayingTitle by viewModel.nowPlayingTitle.collectAsStateWithLifecycle()
    val resolverOrder by viewModel.resolverOrder.collectAsStateWithLifecycle()
    val contextMenuState = rememberTrackContextMenuState()
    var showPlaylistMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Context menu host with Remove from Playlist support
    TrackContextMenuHost(
        state = contextMenuState,
        playlists = allPlaylists,
        onPlayNext = { viewModel.playNext(it) },
        onAddToQueue = { viewModel.addToQueue(it) },
        onAddToPlaylist = { targetPlaylist, track -> viewModel.addToPlaylist(targetPlaylist, track) },
        onNavigateToArtist = onNavigateToArtist,
        onNavigateToAlbum = onNavigateToAlbum,
        onToggleCollection = { track, isInCollection ->
            if (!isInCollection) viewModel.addToCollection(track)
        },
        onRemoveFromPlaylist = { _, position -> viewModel.removeFromPlaylist(position) },
    )

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = playlist?.name?.uppercase() ?: "PLAYLIST",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.2.em,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            windowInsets = WindowInsets(0),
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Header with artwork + play all button
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!playlist?.artworkUrl.isNullOrBlank()) {
                        AlbumArtCard(
                            artworkUrl = playlist?.artworkUrl,
                            size = 160.dp,
                            cornerRadius = 8.dp,
                            elevation = 4.dp,
                        )
                    } else {
                        PlaylistMosaic(
                            trackArtworkUrls = tracks.mapNotNull { it.trackArtworkUrl },
                            size = 160.dp,
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = playlist?.name ?: "",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Author and source line
                    val metaParts = buildList {
                        playlist?.ownerName?.let { add("by $it") }
                        if (playlist?.spotifyId != null) add("Spotify")
                        add("${tracks.size} tracks")
                    }
                    Text(
                        text = metaParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (tracks.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { viewModel.playAll() },
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Play All")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = { showPlaylistMenu = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "More options",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Track list
            itemsIndexed(tracks, key = { index, _ -> index }) { index, track ->
                TrackRow(
                    title = track.trackTitle,
                    artist = track.trackArtist,
                    artworkUrl = track.trackArtworkUrl,
                    resolvers = track.availableResolvers(resolverOrder),
                    duration = track.trackDuration,
                    trackNumber = index + 1,
                    isPlaying = nowPlayingTitle == track.trackTitle,
                    onClick = { viewModel.playTrack(index) },
                    onLongClick = {
                        val entity = viewModel.trackEntityAt(index)
                        if (entity != null) {
                            contextMenuState.show(
                                TrackContextInfo(
                                    title = track.trackTitle,
                                    artist = track.trackArtist,
                                    album = track.trackAlbum,
                                    artworkUrl = track.trackArtworkUrl,
                                    duration = track.trackDuration,
                                    playlistId = track.playlistId,
                                    playlistPosition = track.position,
                                ),
                                entity,
                            )
                        }
                    },
                )
            }
        }

        if (showPlaylistMenu) {
            val pl = playlist
            if (pl != null) {
                PlaylistOptionsSheet(
                    playlistName = pl.name,
                    artworkUrl = pl.artworkUrl,
                    trackCount = tracks.size,
                    onDismiss = { showPlaylistMenu = false },
                    onQueuePlaylist = {
                        showPlaylistMenu = false
                        viewModel.queueAll()
                    },
                    onDeletePlaylist = {
                        showPlaylistMenu = false
                        showDeleteConfirm = true
                    },
                )
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                containerColor = ModalBg,
                titleContentColor = ModalTextActive,
                textContentColor = ModalTextPrimary,
                title = { Text("Delete Playlist") },
                text = { Text("Are you sure you want to delete \"${playlist?.name}\"?") },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        viewModel.deletePlaylist()
                        onBack()
                    }) {
                        Text("Delete", color = Color(0xFFEF4444))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel", color = ModalTextPrimary)
                    }
                },
            )
        }
    }
}

@Composable
private fun PlaylistMosaic(
    trackArtworkUrls: List<String>,
    size: Dp,
) {
    val uniqueUrls = trackArtworkUrls.distinct().take(4)
    val halfSize = size / 2

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        if (uniqueUrls.size >= 4) {
            Column {
                Row {
                    AsyncImage(model = uniqueUrls[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(halfSize))
                    AsyncImage(model = uniqueUrls[1], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(halfSize))
                }
                Row {
                    AsyncImage(model = uniqueUrls[2], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(halfSize))
                    AsyncImage(model = uniqueUrls[3], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(halfSize))
                }
            }
        } else if (uniqueUrls.isNotEmpty()) {
            // Less than 4 unique artworks — just show the first one full size
            AsyncImage(model = uniqueUrls[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            // No artwork at all — gray placeholder
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF2D2D2D)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = Color(0xFF6B7280),
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistOptionsSheet(
    playlistName: String,
    artworkUrl: String?,
    trackCount: Int,
    onDismiss: () -> Unit,
    onQueuePlaylist: () -> Unit,
    onDeletePlaylist: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ModalBg,
        scrimColor = Color.Black.copy(alpha = 0.4f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(
                        color = Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .background(Brush.verticalGradient(listOf(ModalBg, ModalBgDarker)))
                .padding(bottom = 32.dp),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtCard(
                    artworkUrl = artworkUrl,
                    size = 48.dp,
                    cornerRadius = 4.dp,
                    elevation = 1.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlistName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = ModalTextActive,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "$trackCount tracks",
                        fontSize = 13.sp,
                        color = ModalTextPrimary,
                    )
                }
            }

            HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 8.dp))

            ContextMenuItem(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                label = "Queue Playlist",
                onClick = onQueuePlaylist,
            )

            HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))

            ContextMenuItem(
                icon = Icons.Filled.Delete,
                label = "Delete Playlist",
                onClick = onDeletePlaylist,
            )
        }
    }
}
