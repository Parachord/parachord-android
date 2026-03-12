package com.parachord.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.BuildConfig
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.SwipeableTabLayout
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

        SwipeableTabLayout(
            tabs = listOf("Plug-Ins", "General", "About"),
        ) { page ->
            when (page) {
                0 -> PlugInsTab()
                1 -> GeneralTab(
                    themeMode = themeMode,
                    onThemeModeChanged = { viewModel.setThemeMode(it) },
                    scrobbling = scrobbling,
                    onScrobblingChanged = { viewModel.setScrobbling(it) },
                    spotifyConnected = spotifyConnected,
                    onSpotifyToggle = {
                        if (spotifyConnected) viewModel.disconnectSpotify()
                        else viewModel.connectSpotify(BuildConfig.SPOTIFY_CLIENT_ID)
                    },
                    lastFmConnected = lastFmConnected,
                    onLastFmToggle = {
                        if (lastFmConnected) viewModel.disconnectLastFm()
                        else viewModel.connectLastFm(BuildConfig.LASTFM_API_KEY)
                    },
                )
                2 -> AboutTab()
            }
        }
    }
}

@Composable
private fun PlugInsTab() {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { SectionHeader("Installed Resolvers") }
        item {
            // Placeholder for plug-in list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No plug-ins installed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Resolvers extend Parachord with new music sources",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun GeneralTab(
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    scrobbling: Boolean,
    onScrobblingChanged: (Boolean) -> Unit,
    spotifyConnected: Boolean,
    onSpotifyToggle: () -> Unit,
    lastFmConnected: Boolean,
    onLastFmToggle: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                        onModeChanged = onThemeModeChanged,
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
                modifier = Modifier.clickable { onSpotifyToggle() },
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
                modifier = Modifier.clickable { onLastFmToggle() },
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
                        onCheckedChange = onScrobblingChanged,
                    )
                },
            )
        }
    }
}

@Composable
private fun AboutTab() {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Parachord",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Version 0.1.0",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "A universal music player that connects all your music sources in one place.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
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
