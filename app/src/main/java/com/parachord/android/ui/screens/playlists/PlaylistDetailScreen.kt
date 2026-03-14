package com.parachord.android.ui.screens.playlists

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.ui.components.AlbumArtCard
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
    val contextMenuState = rememberTrackContextMenuState()

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
                    AlbumArtCard(
                        artworkUrl = playlist?.artworkUrl,
                        size = 180.dp,
                        cornerRadius = 8.dp,
                        elevation = 4.dp,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = playlist?.name ?: "",
                        style = MaterialTheme.typography.headlineSmall.copy(
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
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (tracks.isNotEmpty()) {
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
                    resolver = track.trackResolver,
                    duration = track.trackDuration,
                    trackNumber = index + 1,
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
    }
}
