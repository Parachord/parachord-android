package com.parachord.android.ui.screens.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Tracks", "Albums", "Playlists")

    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Library") },
        )
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                )
            }
        }

        when (selectedTab) {
            0 -> {
                if (tracks.isEmpty()) {
                    EmptyState("No tracks yet")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(tracks, key = { it.id }) { track ->
                            ListItem(
                                headlineContent = {
                                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                            )
                        }
                    }
                }
            }
            1 -> {
                if (albums.isEmpty()) {
                    EmptyState("No albums yet")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(albums, key = { it.id }) { album ->
                            ListItem(
                                headlineContent = {
                                    Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                            )
                        }
                    }
                }
            }
            2 -> {
                if (playlists.isEmpty()) {
                    EmptyState("No playlists yet")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(playlists, key = { it.id }) { playlist ->
                            ListItem(
                                headlineContent = {
                                    Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = playlist.description?.let { desc ->
                                    { Text(desc, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
