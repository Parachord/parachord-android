package com.parachord.android.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.parachord.android.data.db.entity.SearchHistoryEntity
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.ShimmerTrackRow
import com.parachord.android.ui.components.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onOpenDrawer: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val localTracks by viewModel.localTrackResults.collectAsStateWithLifecycle()
    val localAlbums by viewModel.localAlbumResults.collectAsStateWithLifecycle()
    val remoteTracks by viewModel.remoteTrackResults.collectAsStateWithLifecycle()
    val remoteAlbums by viewModel.remoteAlbumResults.collectAsStateWithLifecycle()
    val artists by viewModel.artistResults.collectAsStateWithLifecycle()
    val isSearchingRemote by viewModel.isSearchingRemote.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    var active by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "SEARCH",
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
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = viewModel::onQueryChange,
                    onSearch = { active = false },
                    expanded = active,
                    onExpandedChange = { active = it },
                    placeholder = { Text("Search tracks, albums, artists...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                )
            },
            expanded = active,
            onExpandedChange = { active = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (!active) 16.dp else 0.dp),
        ) {
            // Show search history when query is empty
            if (query.isBlank() && searchHistory.isNotEmpty()) {
                SearchHistoryContent(
                    history = searchHistory,
                    onEntryClick = { entry ->
                        viewModel.onQueryChange(entry.query)
                    },
                    onDeleteEntry = { entry ->
                        viewModel.deleteHistoryEntry(entry)
                    },
                    onClearAll = {
                        viewModel.clearHistory()
                    },
                )
                return@SearchBar
            }

            // Shimmer loading when searching remote
            if (isSearchingRemote && localTracks.isEmpty() && artists.isEmpty()) {
                repeat(4) { ShimmerTrackRow() }
            }

            LazyColumn {
                // ---- Local Library Results ----
                if (localTracks.isNotEmpty()) {
                    item { SectionHeader("Library") }
                    items(localTracks.take(5), key = { "local-track-${it.id}" }) { track ->
                        TrackRow(
                            title = track.title,
                            artist = track.artist,
                            artworkUrl = track.artworkUrl,
                            resolver = track.resolver,
                            duration = track.duration,
                            onClick = {
                                viewModel.saveHistoryEntry(
                                    resultType = "track",
                                    resultName = track.title,
                                    resultArtist = track.artist,
                                    artworkUrl = track.artworkUrl,
                                )
                                viewModel.playLocalTrack(track)
                            },
                        )
                    }
                }

                // ---- Artists ----
                if (artists.isNotEmpty()) {
                    item {
                        if (localTracks.isNotEmpty()) HorizontalDivider()
                        SectionHeader("Artists")
                    }
                    items(artists, key = { "artist-${it.name}" }) { artist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.saveHistoryEntry(
                                        resultType = "artist",
                                        resultName = artist.name,
                                        artworkUrl = artist.imageUrl,
                                    )
                                    onNavigateToArtist(artist.name)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (artist.imageUrl != null) {
                                AsyncImage(
                                    model = artist.imageUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = artist.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                artist.tags.takeIf { it.isNotEmpty() }?.let { tags ->
                                    Text(
                                        text = tags.joinToString(", "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- Remote Tracks ----
                if (remoteTracks.isNotEmpty()) {
                    item {
                        if (localTracks.isNotEmpty() || artists.isNotEmpty()) HorizontalDivider()
                        SectionHeader("Tracks")
                    }
                    items(remoteTracks, key = { "remote-track-${it.title}-${it.artist}" }) { track ->
                        TrackRow(
                            title = track.title,
                            artist = track.artist,
                            artworkUrl = track.artworkUrl,
                            onClick = {
                                viewModel.saveHistoryEntry(
                                    resultType = "track",
                                    resultName = track.title,
                                    resultArtist = track.artist,
                                    artworkUrl = track.artworkUrl,
                                )
                            },
                        )
                    }
                }

                // ---- Remote Albums ----
                if (remoteAlbums.isNotEmpty()) {
                    item {
                        HorizontalDivider()
                        SectionHeader("Albums")
                    }
                    items(remoteAlbums, key = { "remote-album-${it.title}-${it.artist}" }) { album ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.saveHistoryEntry(
                                        resultType = "album",
                                        resultName = album.title,
                                        resultArtist = album.artist,
                                        artworkUrl = album.artworkUrl,
                                    )
                                    onNavigateToAlbum(album.title, album.artist)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AlbumArtCard(
                                artworkUrl = album.artworkUrl,
                                size = 48.dp,
                                cornerRadius = 4.dp,
                                elevation = 1.dp,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = album.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = buildString {
                                        append(album.artist)
                                        album.year?.let { append(" \u2022 $it") }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // ---- No results ----
                val hasAnyResults = localTracks.isNotEmpty() || localAlbums.isNotEmpty() ||
                    remoteTracks.isNotEmpty() || remoteAlbums.isNotEmpty() || artists.isNotEmpty()
                if (query.isNotBlank() && !hasAnyResults && !isSearchingRemote) {
                    item {
                        Text(
                            text = "No results for \"$query\"",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHistoryContent(
    history: List<SearchHistoryEntity>,
    onEntryClick: (SearchHistoryEntity) -> Unit,
    onDeleteEntry: (SearchHistoryEntity) -> Unit,
    onClearAll: () -> Unit,
) {
    LazyColumn {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Recent",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onClearAll) {
                    Text(
                        text = "Clear all",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        items(history, key = { "history-${it.id}" }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEntryClick(entry) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.query,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    entry.resultName?.let { name ->
                        Text(
                            text = buildString {
                                append(name)
                                entry.resultArtist?.let { append(" \u2022 $it") }
                                entry.resultType?.let { type ->
                                    append(" \u2022 ${type.replaceFirstChar { it.uppercase() }}")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(
                    onClick = { onDeleteEntry(entry) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
