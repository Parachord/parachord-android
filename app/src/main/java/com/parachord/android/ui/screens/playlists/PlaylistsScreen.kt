package com.parachord.android.ui.screens.playlists

import androidx.compose.foundation.background
import com.parachord.android.ui.components.hapticCombinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.parachord.android.data.db.entity.PlaylistEntity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ContextMenuItem
import com.parachord.android.ui.components.HostedBadge
import com.parachord.android.ui.components.ModalBg
import com.parachord.android.ui.components.ModalBgDarker
import com.parachord.android.ui.components.ModalDivider
import com.parachord.android.ui.components.ModalTextActive
import com.parachord.android.ui.components.ModalTextPrimary
import com.parachord.android.ui.screens.library.CollectionFilterBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToPlaylist: (playlistId: String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PlaylistsViewModel = koinViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val sharePlaylistById = com.parachord.android.share.rememberSharePlaylistById()
    val sortedPlaylists by viewModel.sortedPlaylists.collectAsStateWithLifecycle()
    val currentSort by viewModel.sort.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Scroll to top when sort or search changes
    LaunchedEffect(currentSort, searchQuery) {
        listState.scrollToItem(0)
    }

    // Phase 6.5 — Decision D8: surface a toast when a sync-aware
    // delete returns Unsupported (Apple Music's 401 on DELETE).
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                val count = playlists.size
                Text(
                    text = if (count > 0) "PLAYLISTS ($count)" else "PLAYLISTS",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.2.em,
                    ),
                )
            },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Filled.Menu, contentDescription = "Menu")
                }
            },
            windowInsets = WindowInsets(0),
        )

        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(
                        text = "No playlists yet \u2014 tap + to create one",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            CollectionFilterBar(
                sortLabel = currentSort.label,
                sortOptions = PlaylistSort.entries.map { sort ->
                    sort.label to {
                        viewModel.setSort(sort)
                        scope.launch { listState.scrollToItem(0) }
                    }
                },
                selectedSortLabel = currentSort.label,
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                onClearSearch = { viewModel.setSearchQuery("") },
            )

            var pendingDeletePlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }

            pendingDeletePlaylist?.let { playlist ->
                AlertDialog(
                    onDismissRequest = { pendingDeletePlaylist = null },
                    containerColor = ModalBg,
                    titleContentColor = ModalTextActive,
                    textContentColor = ModalTextPrimary,
                    title = { Text("Delete Playlist") },
                    text = { Text("Are you sure you want to delete \"${playlist.name}\"?") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.deletePlaylist(playlist)
                            pendingDeletePlaylist = null
                        }) {
                            Text("Delete", color = Color(0xFFEF4444))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDeletePlaylist = null }) {
                            Text("Cancel", color = ModalTextPrimary)
                        }
                    },
                )
            }

            if (sortedPlaylists.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No playlists match your search",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(sortedPlaylists, key = { it.id }) { playlist ->
                        // Trigger mosaic generation for playlists without artwork
                        if (playlist.artworkUrl.isNullOrBlank()) {
                            LaunchedEffect(playlist.id) {
                                viewModel.enrichPlaylistArtIfNeeded(playlist.id)
                            }
                        }

                        var showMenu by remember { mutableStateOf(false) }

                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    pendingDeletePlaylist = playlist
                                }
                                false // always snap back; dialog handles the delete
                            },
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = "Delete",
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                }
                            },
                            enableDismissFromStartToEnd = false,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .hapticCombinedClickable(
                                        onClick = { onNavigateToPlaylist(playlist.id) },
                                        onLongClick = { showMenu = true },
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AlbumArtCard(
                                    artworkUrl = playlist.artworkUrl,
                                    size = 44.dp,
                                    cornerRadius = 4.dp,
                                    elevation = 1.dp,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        val subtitle = buildString {
                                            playlist.ownerName?.let { append("$it · ") }
                                            append("${playlist.trackCount} tracks")
                                        }
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (playlist.sourceUrl != null) {
                                            HostedBadge()
                                        }
                                        if (playlist.spotifyId != null) {
                                            Text(
                                                text = "Spotify",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = SpotifyGreen,
                                                modifier = Modifier
                                                    .background(
                                                        color = SpotifyGreen.copy(alpha = 0.12f),
                                                        shape = RoundedCornerShape(4.dp),
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (showMenu) {
                            PlaylistContextMenu(
                                playlistName = playlist.name,
                                artworkUrl = playlist.artworkUrl,
                                trackCount = playlist.trackCount,
                                onDismiss = { showMenu = false },
                                onPlayPlaylist = {
                                    showMenu = false
                                    viewModel.playPlaylist(playlist.id, playlist.name)
                                },
                                onDeletePlaylist = {
                                    showMenu = false
                                    pendingDeletePlaylist = playlist
                                },
                                onShare = { sharePlaylistById(playlist.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Playlist context menu ────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistContextMenu(
    playlistName: String,
    artworkUrl: String?,
    trackCount: Int,
    onDismiss: () -> Unit,
    onPlayPlaylist: () -> Unit,
    onDeletePlaylist: () -> Unit,
    onShare: (() -> Unit)? = null,
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
                icon = Icons.Filled.PlayArrow,
                label = "Play Playlist",
                onClick = onPlayPlaylist,
            )

            if (onShare != null) {
                HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))
                ContextMenuItem(
                    icon = Icons.Filled.Share,
                    label = "Share",
                    onClick = { onShare(); onDismiss() },
                )
            }

            HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))

            ContextMenuItem(
                icon = Icons.Filled.Delete,
                label = "Delete Playlist",
                onClick = onDeletePlaylist,
            )
        }
    }
}

private val SpotifyGreen = Color(0xFF1DB954)
