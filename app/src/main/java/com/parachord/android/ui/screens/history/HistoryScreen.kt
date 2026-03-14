package com.parachord.android.ui.screens.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.navigation.compose.hiltViewModel
import com.parachord.android.data.repository.HistoryAlbum
import com.parachord.android.data.repository.HistoryArtist
import com.parachord.android.data.repository.HistoryTrack
import com.parachord.android.data.repository.RecentTrack
import com.parachord.android.data.repository.Resource
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ShimmerTrackRow
import com.parachord.android.ui.components.SwipeableTabLayout
import com.parachord.android.ui.components.ResolverIconRow
import com.parachord.android.ui.components.TrackRow

private val historyTabs = listOf("Top Songs", "Top Albums", "Top Artists", "Recently Played")

private data class PeriodOption(val key: String, val label: String)

private val periodOptions = listOf(
    PeriodOption("7day", "7 Days"),
    PeriodOption("1month", "Month"),
    PeriodOption("3month", "3 Months"),
    PeriodOption("6month", "6 Months"),
    PeriodOption("12month", "Year"),
    PeriodOption("overall", "All Time"),
)

private data class SortOption(val key: String, val label: String)

private val sortOptions = listOf(
    SortOption("recent", "Recent"),
    SortOption("artist", "By Artist"),
    SortOption("title", "By Title"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToArtist: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val selectedPeriod by viewModel.selectedPeriod.collectAsState()
    val topTracks by viewModel.topTracks.collectAsState()
    val topAlbums by viewModel.topAlbums.collectAsState()
    val topArtists by viewModel.topArtists.collectAsState()
    val filteredRecentTracks by viewModel.filteredRecentTracks.collectAsState()
    val recentSort by viewModel.recentSort.collectAsState()
    val recentSearch by viewModel.recentSearch.collectAsState()
    val trackResolvers by viewModel.trackResolvers.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "HISTORY",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.2.em,
                    ),
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            windowInsets = WindowInsets(0),
        )
        SwipeableTabLayout(tabs = historyTabs) { page ->
            when (page) {
                0 -> TopSongsTab(
                    tracks = topTracks,
                    selectedPeriod = selectedPeriod,
                    onPeriodChanged = viewModel::setPeriod,
                    onPlayTrack = viewModel::playTopTrack,
                    trackResolvers = trackResolvers,
                )
                1 -> TopAlbumsTab(
                    albums = topAlbums,
                    selectedPeriod = selectedPeriod,
                    onPeriodChanged = viewModel::setPeriod,
                    onAlbumClick = onNavigateToAlbum,
                )
                2 -> TopArtistsTab(
                    artists = topArtists,
                    selectedPeriod = selectedPeriod,
                    onPeriodChanged = viewModel::setPeriod,
                    onArtistClick = onNavigateToArtist,
                )
                3 -> RecentlyPlayedTab(
                    recentTracks = filteredRecentTracks,
                    sort = recentSort,
                    search = recentSearch,
                    onSortChanged = viewModel::setRecentSort,
                    onSearchChanged = viewModel::setRecentSearch,
                    onPlayTrack = viewModel::playRecentTrack,
                    trackResolvers = trackResolvers,
                )
            }
        }
    }
}

// ---------- Period Filter ----------

@Composable
private fun PeriodFilter(
    selectedPeriod: String,
    onPeriodChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(periodOptions, key = { it.key }) { option ->
            FilterChip(
                selected = selectedPeriod == option.key,
                onClick = { onPeriodChanged(option.key) },
                label = { Text(option.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

// ---------- Top Songs Tab ----------

@Composable
private fun TopSongsTab(
    tracks: Resource<List<HistoryTrack>>,
    selectedPeriod: String,
    onPeriodChanged: (String) -> Unit,
    onPlayTrack: (Int) -> Unit,
    trackResolvers: Map<String, List<String>> = emptyMap(),
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PeriodFilter(selectedPeriod = selectedPeriod, onPeriodChanged = onPeriodChanged)
        when (tracks) {
            is Resource.Loading -> {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(10) { ShimmerTrackRow(modifier = Modifier.padding(horizontal = 16.dp)) }
                }
            }
            is Resource.Error -> ErrorState(message = tracks.message, onRetry = { onPeriodChanged(selectedPeriod) })
            is Resource.Success -> {
                if (tracks.data.isEmpty()) {
                    EmptyState("No listening data yet")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(tracks.data.size) { index ->
                            val track = tracks.data[index]
                            TrackRow(
                                title = track.title,
                                artist = "${track.artist}  •  ${formatPlayCount(track.playCount)}",
                                artworkUrl = track.artworkUrl,
                                trackNumber = track.rank,
                                resolvers = trackResolvers["${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"]?.ifEmpty { null },
                                onClick = { onPlayTrack(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- Top Albums Tab ----------

@Composable
private fun TopAlbumsTab(
    albums: Resource<List<HistoryAlbum>>,
    selectedPeriod: String,
    onPeriodChanged: (String) -> Unit,
    onAlbumClick: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PeriodFilter(selectedPeriod = selectedPeriod, onPeriodChanged = onPeriodChanged)
        when (albums) {
            is Resource.Loading -> {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(10) { ShimmerTrackRow(modifier = Modifier.padding(horizontal = 16.dp)) }
                }
            }
            is Resource.Error -> ErrorState(message = albums.message, onRetry = { onPeriodChanged(selectedPeriod) })
            is Resource.Success -> {
                if (albums.data.isEmpty()) {
                    EmptyState("No album data yet")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(albums.data, key = { "${it.rank}-${it.name}-${it.artist}" }) { album ->
                            AlbumRow(
                                album = album,
                                onClick = { onAlbumClick(album.name, album.artist) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(album: HistoryAlbum, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Rank number
        Text(
            text = "${album.rank}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )

        // Album art
        AlbumArtCard(
            artworkUrl = album.artworkUrl,
            size = 44.dp,
            cornerRadius = 4.dp,
            elevation = 1.dp,
            placeholderName = album.name,
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Album info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = album.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Play count
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatPlayCount(album.playCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Top Artists Tab ----------

@Composable
private fun TopArtistsTab(
    artists: Resource<List<HistoryArtist>>,
    selectedPeriod: String,
    onPeriodChanged: (String) -> Unit,
    onArtistClick: (String) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PeriodFilter(selectedPeriod = selectedPeriod, onPeriodChanged = onPeriodChanged)
        when (artists) {
            is Resource.Loading -> {
                // Simple loading placeholder
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Loading...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is Resource.Error -> ErrorState(message = artists.message, onRetry = { onPeriodChanged(selectedPeriod) })
            is Resource.Success -> {
                if (artists.data.isEmpty()) {
                    EmptyState("No artist data yet")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(artists.data, key = { "${it.rank}-${it.name}" }) { artist ->
                            ArtistGridItem(
                                artist = artist,
                                onClick = { onArtistClick(artist.name) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistGridItem(artist: HistoryArtist, onClick: () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        AlbumArtCard(
            artworkUrl = artist.imageUrl,
            size = 96.dp,
            cornerRadius = 48.dp, // circular
            placeholderName = artist.name,
            modifier = Modifier.clip(CircleShape),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = formatPlayCount(artist.playCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------- Recently Played Tab ----------

@Composable
private fun RecentlyPlayedTab(
    recentTracks: Resource<List<RecentTrack>>,
    sort: String,
    search: String,
    onSortChanged: (String) -> Unit,
    onSearchChanged: (String) -> Unit,
    onPlayTrack: (Int) -> Unit,
    trackResolvers: Map<String, List<String>> = emptyMap(),
) {
    Column(modifier = Modifier.fillMaxSize()) {
        when (recentTracks) {
            is Resource.Loading -> {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(10) { ShimmerTrackRow(modifier = Modifier.padding(horizontal = 16.dp)) }
                }
            }
            is Resource.Error -> ErrorState(message = recentTracks.message, onRetry = {})
            is Resource.Success -> {
                if (recentTracks.data.isEmpty() && search.isBlank()) {
                    EmptyState("No recent listening history")
                } else {
                    // Sort chips row
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sortOptions, key = { it.key }) { option ->
                            FilterChip(
                                selected = sort == option.key,
                                onClick = { onSortChanged(option.key) },
                                label = { Text(option.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }

                    // Search field
                    OutlinedTextField(
                        value = search,
                        onValueChange = onSearchChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        placeholder = { Text("Search recent tracks...") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (recentTracks.data.isEmpty()) {
                        EmptyState("No matching tracks")
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(recentTracks.data.size) { index ->
                                RecentTrackRow(
                                    track = recentTracks.data[index],
                                    resolvers = trackResolvers["${recentTracks.data[index].title.lowercase().trim()}|${recentTracks.data[index].artist.lowercase().trim()}"]?.ifEmpty { null },
                                    onClick = { onPlayTrack(index) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentTrackRow(track: RecentTrack, resolvers: List<String>? = null, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArtCard(
            artworkUrl = track.artworkUrl,
            size = 44.dp,
            cornerRadius = 4.dp,
            elevation = 1.dp,
            placeholderName = track.title,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (track.nowPlaying) "▶ ${track.title}" else track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (track.nowPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Row {
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (track.source.isNotBlank()) {
                    Text(
                        text = "  •  ${track.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        // Time ago
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (track.nowPlaying) "Now" else formatTimeAgo(track.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = if (track.nowPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Resolver badges (to the right of the time)
        if (!resolvers.isNullOrEmpty()) {
            Spacer(modifier = Modifier.width(6.dp))
            ResolverIconRow(resolvers = resolvers, size = 20.dp)
        }
    }
}

// ---------- Shared States ----------

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) {
                Text("Try Again")
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Helpers ----------

private fun formatPlayCount(count: Int): String = when {
    count >= 1_000_000 -> "${"%.1f".format(count / 1_000_000.0)}M plays"
    count >= 1_000 -> "${"%.1f".format(count / 1_000.0)}K plays"
    count == 1 -> "1 play"
    else -> "$count plays"
}

private fun formatTimeAgo(timestampSeconds: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - timestampSeconds
    return when {
        diff < 60 -> "Just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        diff < 172800 -> "Yesterday"
        diff < 604800 -> "${diff / 86400}d ago"
        else -> "${diff / 604800}w ago"
    }
}
