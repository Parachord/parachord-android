package com.parachord.android.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigateToArtist: (String) -> Unit = {},
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
    var active by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
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
            if (isSearchingRemote) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn {
                // ---- Local Library Results ----
                if (localTracks.isNotEmpty()) {
                    item { SectionHeader("Library") }
                    items(localTracks.take(5), key = { "local-track-${it.id}" }) { track ->
                        ListItem(
                            headlineContent = {
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            modifier = Modifier.clickable { viewModel.playLocalTrack(track) },
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
                        ListItem(
                            headlineContent = {
                                Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = artist.tags.takeIf { it.isNotEmpty() }?.let { tags ->
                                { Text(tags.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            },
                            leadingContent = {
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
                            },
                            modifier = Modifier.clickable { onNavigateToArtist(artist.name) },
                        )
                    }
                }

                // ---- Remote Tracks ----
                if (remoteTracks.isNotEmpty()) {
                    item {
                        if (localTracks.isNotEmpty() || artists.isNotEmpty()) HorizontalDivider()
                        SectionHeader("Tracks")
                    }
                    items(remoteTracks, key = { "remote-track-${it.title}-${it.artist}" }) { track ->
                        ListItem(
                            headlineContent = {
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = track.artworkUrl?.let { url ->
                                {
                                    AsyncImage(
                                        model = url,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .height(48.dp)
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(4.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
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
                        ListItem(
                            headlineContent = {
                                Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                val info = buildString {
                                    append(album.artist)
                                    album.year?.let { append(" • $it") }
                                }
                                Text(info, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = album.artworkUrl?.let { url ->
                                {
                                    AsyncImage(
                                        model = url,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .height(48.dp)
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(4.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                            },
                        )
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
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
