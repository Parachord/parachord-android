package com.parachord.android.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val scrobbling by viewModel.scrobblingEnabled.collectAsStateWithLifecycle()
    val spotifyConnected by viewModel.spotifyConnected.collectAsStateWithLifecycle()
    val lastFmConnected by viewModel.lastFmConnected.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                Text(
                    text = "Accounts",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Spotify") },
                    supportingContent = {
                        Text(if (spotifyConnected) "Connected" else "Not connected")
                    },
                    modifier = Modifier.clickable {
                        if (spotifyConnected) {
                            viewModel.disconnectSpotify()
                        } else {
                            viewModel.connectSpotify("YOUR_SPOTIFY_CLIENT_ID")
                        }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Last.fm") },
                    supportingContent = {
                        Text(if (lastFmConnected) "Connected" else "Not connected")
                    },
                    modifier = Modifier.clickable {
                        if (lastFmConnected) {
                            viewModel.disconnectLastFm()
                        } else {
                            viewModel.connectLastFm("YOUR_LASTFM_API_KEY")
                        }
                    },
                )
            }
            item { HorizontalDivider() }
            item {
                Text(
                    text = "Playback",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Scrobbling") },
                    supportingContent = { Text("Send listening history to Last.fm") },
                    trailingContent = {
                        Switch(
                            checked = scrobbling,
                            onCheckedChange = { viewModel.setScrobbling(it) },
                        )
                    },
                )
            }
            item { HorizontalDivider() }
            item {
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Version") },
                    supportingContent = { Text("0.1.0") },
                )
            }
        }
    }
}
