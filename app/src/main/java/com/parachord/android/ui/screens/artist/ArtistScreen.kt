package com.parachord.android.ui.screens.artist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.ShimmerTrackRow
import com.parachord.android.ui.components.SwipeableTabLayout
import com.parachord.android.ui.components.TrackRow
import com.parachord.android.data.metadata.AlbumSearchResult
import com.parachord.android.data.metadata.TrackSearchResult

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArtistScreen(
    onBack: () -> Unit,
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToArtist: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val artistInfo by viewModel.artistInfo.collectAsStateWithLifecycle()
    val topTracks by viewModel.topTracks.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(artistInfo?.name ?: "Artist") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            windowInsets = WindowInsets(0),
        )

        if (isLoading) {
            // Shimmer loading skeleton
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                repeat(5) { ShimmerTrackRow() }
            }
        } else {
            // Hero image (above tabs)
            val imageUrl = artistInfo?.imageUrl
            if (!imageUrl.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Artist image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(21f / 9f)
                        .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)),
                    contentScale = ContentScale.Crop,
                    loading = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(21f / 9f)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    },
                    error = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(21f / 9f)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    },
                )
            }

            // Artist name
            Text(
                text = artistInfo?.name ?: "",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Tabs + pager
            SwipeableTabLayout(
                tabs = listOf("Discography", "Biography", "Related Artists"),
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> DiscographyTab(
                        albums = albums,
                        topTracks = topTracks,
                        onNavigateToAlbum = onNavigateToAlbum,
                    )
                    1 -> BiographyTab(
                        bio = artistInfo?.bio,
                        tags = artistInfo?.tags ?: emptyList(),
                        provider = artistInfo?.provider,
                    )
                    2 -> RelatedArtistsTab(
                        similarArtists = artistInfo?.similarArtists ?: emptyList(),
                        onNavigateToArtist = onNavigateToArtist,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscographyTab(
    albums: List<AlbumSearchResult>,
    topTracks: List<TrackSearchResult>,
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (albums.isNotEmpty()) {
            item { SectionHeader("ALBUMS") }
            items(albums, key = { "album-${it.title}-${it.artist}" }) { album ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToAlbum(album.title, album.artist) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArtCard(
                        artworkUrl = album.artworkUrl,
                        size = 56.dp,
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
                        val info = buildString {
                            album.year?.let { append("$it") }
                            album.trackCount?.let {
                                if (isNotEmpty()) append(" \u2022 ")
                                append("$it tracks")
                            }
                        }
                        if (info.isNotEmpty()) {
                            Text(
                                text = info,
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

        if (topTracks.isNotEmpty()) {
            item {
                if (albums.isNotEmpty()) HorizontalDivider()
                SectionHeader("TOP TRACKS")
            }
            items(topTracks) { track ->
                TrackRow(
                    title = track.title,
                    artist = track.album ?: "",
                    artworkUrl = track.artworkUrl,
                )
            }
        }

        if (albums.isEmpty() && topTracks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No discography available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BiographyTab(
    bio: String?,
    tags: List<String>,
    provider: String?,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (!bio.isNullOrBlank()) {
            item {
                Text(
                    text = bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        if (tags.isNotEmpty()) {
            item {
                if (!bio.isNullOrBlank()) HorizontalDivider()
                SectionHeader("TAGS")
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { tag ->
                        AssistChip(
                            onClick = {},
                            label = { Text(tag) },
                        )
                    }
                }
            }
        }

        provider?.let { providers ->
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Sources: ${providers.split("+").distinct().joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        if (bio.isNullOrBlank() && tags.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No biography available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RelatedArtistsTab(
    similarArtists: List<String>,
    onNavigateToArtist: (String) -> Unit,
) {
    if (similarArtists.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No related artists",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(similarArtists) { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToArtist(name) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}
