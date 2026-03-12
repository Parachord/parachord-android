package com.parachord.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.BuildConfig
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.theme.Success

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val scrobbling by viewModel.scrobblingEnabled.collectAsStateWithLifecycle()
    val spotifyConnected by viewModel.spotifyConnected.collectAsStateWithLifecycle()
    val lastFmConnected by viewModel.lastFmConnected.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item { SectionHeader("Appearance") }
            item {
                ListItem(
                    headlineContent = { Text("Dark Mode") },
                    supportingContent = {
                        Text(
                            when (themeMode) {
                                "light" -> "Always light"
                                "dark" -> "Always dark"
                                else -> "Follow system"
                            },
                        )
                    },
                    trailingContent = {
                        ThemeModeSelector(
                            currentMode = themeMode,
                            onModeChanged = { viewModel.setThemeMode(it) },
                        )
                    },
                )
            }
            item { HorizontalDivider() }
            item { SectionHeader("Accounts") }
            item {
                ListItem(
                    headlineContent = { Text("Spotify") },
                    supportingContent = {
                        Text(if (spotifyConnected) "Connected" else "Not connected")
                    },
                    trailingContent = if (spotifyConnected) {
                        {
                            Text(
                                text = "CONNECTED",
                                style = MaterialTheme.typography.labelSmall,
                                color = Success,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Success.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    } else null,
                    modifier = Modifier.clickable {
                        if (spotifyConnected) {
                            viewModel.disconnectSpotify()
                        } else {
                            viewModel.connectSpotify(BuildConfig.SPOTIFY_CLIENT_ID)
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
                    trailingContent = if (lastFmConnected) {
                        {
                            Text(
                                text = "CONNECTED",
                                style = MaterialTheme.typography.labelSmall,
                                color = Success,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Success.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    } else null,
                    modifier = Modifier.clickable {
                        if (lastFmConnected) {
                            viewModel.disconnectLastFm()
                        } else {
                            viewModel.connectLastFm(BuildConfig.LASTFM_API_KEY)
                        }
                    },
                )
            }
            item { HorizontalDivider() }
            item { SectionHeader("Playback") }
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
            item { SectionHeader("About") }
            item {
                ListItem(
                    headlineContent = { Text("Version") },
                    supportingContent = { Text("0.1.0") },
                )
            }
        }
    }
}

@Composable
private fun ThemeModeSelector(
    currentMode: String,
    onModeChanged: (String) -> Unit,
) {
    val modes = listOf("system" to "Auto", "light" to "Light", "dark" to "Dark")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        modes.forEach { (value, label) ->
            FilterChip(
                selected = currentMode == value,
                onClick = { onModeChanged(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}
