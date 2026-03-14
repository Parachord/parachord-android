package com.parachord.android.ui.screens.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.ui.components.AlbumArtCardFill
import com.parachord.android.ui.components.ResolverIconSquare
import com.parachord.android.ui.components.TrackContextInfo
import com.parachord.android.ui.components.TrackContextMenuHost
import com.parachord.android.ui.components.rememberTrackContextMenuState
import com.parachord.android.ui.icons.ParachordIcons
import com.parachord.android.ui.theme.PlayerSurface
import com.parachord.android.ui.theme.PlayerTextPrimary
import com.parachord.android.ui.theme.PlayerTextSecondary
import com.parachord.android.ui.theme.PurpleDark
import kotlinx.coroutines.launch
import java.util.Locale

private val InactiveControlColor = Color(0xFF4B5563)
private val ActiveControlColor = Color(0xFFC084FC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onNavigateToArtist: (artistName: String) -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val track = playbackState.currentTrack
    val upNext = playbackState.upNext
    val scope = rememberCoroutineScope()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val contextMenuState = rememberTrackContextMenuState()

    // Context menu host
    TrackContextMenuHost(
        state = contextMenuState,
        playlists = playlists,
        onPlayNext = { viewModel.playNext(it) },
        onAddToQueue = { viewModel.addToQueue(it) },
        onAddToPlaylist = { playlist, t -> viewModel.addToPlaylist(playlist, t) },
        onNavigateToArtist = onNavigateToArtist,
        onNavigateToAlbum = onNavigateToAlbum,
        onToggleCollection = { t, isInCollection ->
            if (!isInCollection) viewModel.addToCollection(t)
        },
    )

    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = sheetState,
    )

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 28.dp, // Small peek so swipe-up gesture is detected
        sheetContainerColor = PlayerSurface,
        sheetContentColor = PlayerTextPrimary,
        sheetDragHandle = {
            // Subtle drag handle indicator for swipe-up affordance
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(PlayerTextSecondary.copy(alpha = 0.4f)),
                )
            }
        },
        containerColor = PlayerSurface,
        modifier = modifier,
        sheetContent = {
            QueueSheet(
                upNext = upNext,
                playbackContext = playbackState.playbackContext,
                onPlayFromQueue = { viewModel.playFromQueue(it) },
                onMoveInQueue = { from, to -> viewModel.moveInQueue(from, to) },
                onRemoveFromQueue = { viewModel.removeFromQueue(it) },
                onClearQueue = { viewModel.clearQueue() },
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PlayerSurface)
                .pointerInput(Unit) {
                    var totalDrag = 0f
                    detectVerticalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onVerticalDrag = { _, dragAmount ->
                            totalDrag += dragAmount
                        },
                        onDragEnd = {
                            // Negative = swipe up → expand queue
                            if (totalDrag < -80f) {
                                scope.launch { sheetState.expand() }
                            }
                            // Positive = swipe down → collapse to mini-player
                            else if (totalDrag > 80f) {
                                onBack()
                            }
                        },
                    )
                },
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Always-dark top app bar — tap anywhere to collapse
                TopAppBar(
                    title = {
                        Text(
                            text = "NOW PLAYING",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Light,
                                letterSpacing = 0.2.em,
                            ),
                            color = PlayerTextSecondary,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Close",
                                tint = PlayerTextPrimary,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                    windowInsets = WindowInsets(0),
                    modifier = Modifier.clickable { onBack() },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Large album artwork with shadow — tap to open album page
                AlbumArtCardFill(
                    artworkUrl = track?.artworkUrl,
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(horizontal = 8.dp)
                        .then(
                            if (track?.album != null && track.artist.isNotBlank()) {
                                Modifier.clickable {
                                    onNavigateToAlbum(track.album, track.artist)
                                }
                            } else Modifier
                        ),
                    cornerRadius = 12.dp,
                    elevation = 8.dp,
                    placeholderName = track?.artist ?: track?.title,
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Track info
                Column(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = track?.title ?: "No track playing",
                        style = MaterialTheme.typography.headlineMedium,
                        color = PlayerTextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = track?.artist ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = PlayerTextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (track?.artist != null) {
                            Modifier.clickable { onNavigateToArtist(track.artist) }
                        } else Modifier,
                    )

                    // Resolver icon
                    if (track?.resolver != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ResolverIconSquare(resolver = track.resolver!!, size = 24.dp)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Seek bar with custom colors
                val duration = playbackState.duration.coerceAtLeast(1L)
                val position = playbackState.position

                Column(modifier = Modifier.padding(horizontal = 32.dp)) {
                    Slider(
                        value = position.toFloat(),
                        onValueChange = { viewModel.seekTo(it.toLong()) },
                        valueRange = 0f..duration.toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = PurpleDark,
                            inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                        ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatDuration(position),
                            style = MaterialTheme.typography.bodySmall,
                            color = PlayerTextSecondary,
                        )
                        Text(
                            text = formatDuration(duration),
                            style = MaterialTheme.typography.bodySmall,
                            color = PlayerTextSecondary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Playback controls with shuffle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Shuffle
                    IconButton(
                        onClick = { viewModel.toggleShuffle() },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = if (playbackState.shuffleEnabled) ActiveControlColor else InactiveControlColor,
                        ),
                    ) {
                        Icon(
                            imageVector = ParachordIcons.Shuffle,
                            contentDescription = "Shuffle",
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Skip Previous
                    IconButton(
                        onClick = { viewModel.skipPrevious() },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = PlayerTextPrimary,
                        ),
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    // Play/Pause — large purple circle
                    IconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    // Skip Next
                    IconButton(
                        onClick = { viewModel.skipNext() },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = PlayerTextPrimary,
                        ),
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Context menu (•••) button — opens track actions
                    IconButton(
                        onClick = {
                            if (track != null) {
                                contextMenuState.show(
                                    TrackContextInfo(
                                        title = track.title,
                                        artist = track.artist,
                                        album = track.album,
                                        artworkUrl = track.artworkUrl,
                                        duration = track.duration,
                                    ),
                                    track,
                                )
                            }
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = InactiveControlColor,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = "More actions",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Bottom row: Queue (left) and Spinoff (right)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Queue button with badge
                    IconButton(
                        onClick = {
                            scope.launch {
                                if (sheetState.currentValue == SheetValue.Expanded) {
                                    sheetState.partialExpand()
                                } else {
                                    sheetState.expand()
                                }
                            }
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = if (upNext.isNotEmpty()) ActiveControlColor else PlayerTextSecondary,
                        ),
                    ) {
                        Box {
                            Icon(
                                imageVector = ParachordIcons.Queue,
                                contentDescription = "Queue",
                                modifier = Modifier.size(22.dp),
                            )
                            if (upNext.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(16.dp)
                                        .background(PurpleDark, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "${upNext.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontSize = 9.sp,
                                    )
                                }
                            }
                        }
                    }

                    // Spinoff button
                    IconButton(
                        onClick = { /* TODO: toggle spinoff mode */ },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = InactiveControlColor,
                        ),
                    ) {
                        Icon(
                            imageVector = ParachordIcons.Spinoff,
                            contentDescription = "Spinoff",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
