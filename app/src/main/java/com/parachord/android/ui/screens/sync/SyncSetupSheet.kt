package com.parachord.android.ui.screens.sync

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.sync.SyncEngine
import com.parachord.android.ui.components.AlbumArtCard

private val SpotifyGreen = Color(0xFF1DB954)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSetupSheet(
    onDismiss: () -> Unit,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val currentStep by viewModel.currentStep.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            if (currentStep != SyncViewModel.SetupStep.SYNCING) onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        },
    ) {
        when (currentStep) {
            SyncViewModel.SetupStep.OPTIONS -> OptionsStep(viewModel)
            SyncViewModel.SetupStep.PLAYLISTS -> PlaylistSelectionStep(viewModel)
            SyncViewModel.SetupStep.SYNCING -> SyncingStep(viewModel)
            SyncViewModel.SetupStep.COMPLETE -> CompleteStep(viewModel, onDismiss)
        }
    }
}

@Composable
private fun OptionsStep(viewModel: SyncViewModel) {
    val syncTracks by viewModel.syncTracks.collectAsStateWithLifecycle()
    val syncAlbums by viewModel.syncAlbums.collectAsStateWithLifecycle()
    val syncArtists by viewModel.syncArtists.collectAsStateWithLifecycle()
    val syncPlaylists by viewModel.syncPlaylists.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Sync,
                contentDescription = null,
                tint = SpotifyGreen,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Sync with Spotify",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose what to sync from your Spotify library",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        SyncOptionRow("Liked Songs", Icons.Default.Favorite, syncTracks) { viewModel.setSyncTracks(it) }
        SyncOptionRow("Saved Albums", Icons.Default.Album, syncAlbums) { viewModel.setSyncAlbums(it) }
        SyncOptionRow("Followed Artists", Icons.Default.Person, syncArtists) { viewModel.setSyncArtists(it) }
        @Suppress("DEPRECATION")
        SyncOptionRow("Playlists", Icons.Default.QueueMusic, syncPlaylists) { viewModel.setSyncPlaylists(it) }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { viewModel.proceedFromOptions() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
            enabled = syncTracks || syncAlbums || syncArtists || syncPlaylists,
        ) {
            Text(if (syncPlaylists) "Next" else "Start Sync")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SyncOptionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PlaylistSelectionStep(viewModel: SyncViewModel) {
    val playlists by viewModel.availablePlaylists.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedPlaylistIds.collectAsStateWithLifecycle()
    val filter by viewModel.playlistFilter.collectAsStateWithLifecycle()
    val isLoading by viewModel.playlistsLoading.collectAsStateWithLifecycle()
    val error by viewModel.playlistsError.collectAsStateWithLifecycle()

    val filteredPlaylists = remember(playlists, filter) {
        when (filter) {
            "owned" -> playlists.filter { it.isOwned }
            "following" -> playlists.filter { !it.isOwned }
            else -> playlists
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            "Select Playlists",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            FilterChip(selected = filter == "all", onClick = { viewModel.setPlaylistFilter("all") },
                label = { Text("All") }, modifier = Modifier.padding(end = 8.dp))
            FilterChip(selected = filter == "owned", onClick = { viewModel.setPlaylistFilter("owned") },
                label = { Text("Created by Me") }, modifier = Modifier.padding(end = 8.dp))
            FilterChip(selected = filter == "following", onClick = { viewModel.setPlaylistFilter("following") },
                label = { Text("Following") })
        }

        if (!isLoading && error == null) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                TextButton(onClick = {
                    viewModel.selectAllPlaylists(filteredPlaylists.map { it.spotifyId })
                }) { Text("Select All") }
                TextButton(onClick = {
                    viewModel.deselectAllPlaylists(filteredPlaylists.map { it.spotifyId })
                }) { Text("Deselect All") }
            }
        } else {
            Spacer(Modifier.height(8.dp))
        }

        when {
            error != null -> {
                // Error state
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp)
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Failed to load playlists",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { viewModel.proceedFromOptions() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry")
                    }
                }
            }
            isLoading -> {
                // Skeleton loading state — 6 shimmer rows matching desktop
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 400.dp),
                ) {
                    items(6) {
                        PlaylistSkeletonRow()
                    }
                }
            }
            else -> {
                // Loaded state — real playlist rows
                if (filteredPlaylists.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (filter) {
                                "owned" -> "No playlists created by you"
                                "following" -> "No playlists you're following"
                                else -> "No playlists found"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 400.dp),
                    ) {
                        items(filteredPlaylists, key = { it.spotifyId }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.togglePlaylistSelection(playlist.spotifyId) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = playlist.spotifyId in selectedIds,
                                    onCheckedChange = { viewModel.togglePlaylistSelection(playlist.spotifyId) },
                                )
                                Spacer(Modifier.width(8.dp))
                                AlbumArtCard(
                                    artworkUrl = playlist.entity.artworkUrl,
                                    size = 40.dp,
                                    cornerRadius = 4.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        playlist.entity.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        buildString {
                                            append("${playlist.trackCount} tracks")
                                            if (!playlist.isOwned) append(" · Following")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { viewModel.goBackToOptions() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Back")
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { viewModel.startSync() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                enabled = selectedIds.isNotEmpty() && !isLoading,
            ) {
                Text("Start Sync")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Animated shimmer skeleton row matching a playlist row layout. */
@Composable
private fun PlaylistSkeletonRow() {
    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by shimmerTransition.animateFloat(
        initialValue = -300f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )
    val baseColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val highlightColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(shimmerOffset, 0f),
        end = Offset(shimmerOffset + 300f, 0f),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Checkbox placeholder
        Box(
            modifier = Modifier
                .size(48.dp)
                .padding(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmerBrush),
        )
        Spacer(Modifier.width(8.dp))
        // Image placeholder
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmerBrush),
        )
        Spacer(Modifier.width(12.dp))
        // Text placeholders
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerBrush),
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerBrush),
            )
        }
    }
}

@Composable
private fun SyncingStep(viewModel: SyncViewModel) {
    val progress by viewModel.syncProgress.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = SpotifyGreen,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            progress.message,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        if (progress.total > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${progress.current} of ${progress.total}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress.current.toFloat() / progress.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
                color = SpotifyGreen,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CompleteStep(viewModel: SyncViewModel, onDismiss: () -> Unit) {
    val result by viewModel.syncResult.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (result?.success == true) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = if (result?.success == true) SpotifyGreen else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (result?.success == true) "Sync Complete!" else "Sync Failed",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        result?.let { r ->
            if (r.success) {
                Spacer(Modifier.height(20.dp))
                SyncStatRow("Tracks", r.tracks)
                SyncStatRow("Albums", r.albums)
                SyncStatRow("Artists", r.artists)
                SyncStatRow("Playlists", r.playlists)
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    r.error ?: "Unknown error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                viewModel.resetSetup()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Done")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SyncStatRow(label: String, stats: SyncEngine.TypeSyncResult) {
    if (stats.added == 0 && stats.removed == 0 && stats.unchanged == 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(80.dp),
        )
        if (stats.added > 0) {
            Text(
                "+${stats.added}",
                style = MaterialTheme.typography.bodyMedium,
                color = SpotifyGreen,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        if (stats.removed > 0) {
            Text(
                "-${stats.removed}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        if (stats.unchanged > 0) {
            Text(
                "${stats.unchanged} unchanged",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
