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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
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
                    supportingContent = { Text("Not connected") },
                    modifier = Modifier.clickable { /* TODO: OAuth flow */ },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Last.fm") },
                    supportingContent = { Text("Not connected") },
                    modifier = Modifier.clickable { /* TODO: OAuth flow */ },
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
                var scrobbling by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text("Scrobbling") },
                    supportingContent = { Text("Send listening history to Last.fm") },
                    trailingContent = {
                        Switch(
                            checked = scrobbling,
                            onCheckedChange = { scrobbling = it },
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
