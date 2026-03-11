package com.parachord.android.ui.screens.album

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
fun AlbumScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlbumViewModel = hiltViewModel(),
) {
    val albumDetail by viewModel.albumDetail.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isResolving by viewModel.isResolving.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(albumDetail?.title ?: "Album") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        if (isResolving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            val detail = albumDetail
            if (detail == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Album not found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // Album header
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            detail.artworkUrl?.let { url ->
                                AsyncImage(
                                    model = url,
                                    contentDescription = "Album artwork",
                                    modifier = Modifier
                                        .size(160.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                            }
                            Column {
                                Text(
                                    text = detail.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = detail.artist,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                detail.year?.let { year ->
                                    Text(
                                        text = "$year",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                FilledTonalButton(
                                    onClick = { viewModel.playAll() },
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Play All")
                                }
                            }
                        }
                    }

                    item { HorizontalDivider() }

                    // Tracklist
                    itemsIndexed(
                        detail.tracks,
                        key = { idx, t -> "track-$idx-${t.title}" },
                    ) { index, track ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    track.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                val info = buildString {
                                    append(track.artist)
                                    track.duration?.let {
                                        append(" \u2022 ${formatDuration(it)}")
                                    }
                                }
                                Text(info, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.width(28.dp),
                                )
                            },
                            modifier = Modifier.clickable { viewModel.playTrack(index) },
                        )
                    }

                    // Provider attribution
                    detail.provider.takeIf { it.isNotBlank() }?.let { providers ->
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Source: ${providers.split("+").distinct().joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:%02d".format(seconds)
}
