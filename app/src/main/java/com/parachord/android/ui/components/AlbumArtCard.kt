package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

/**
 * Reusable album art image composable with rounded corners, shadow,
 * and a placeholder when no artwork URL is available.
 */
@Composable
fun AlbumArtCard(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    cornerRadius: Dp = 8.dp,
    elevation: Dp = 2.dp,
    contentDescription: String? = "Album artwork",
) {
    val shape = RoundedCornerShape(cornerRadius)
    val commonModifier = modifier
        .size(size)
        .shadow(elevation, shape)
        .clip(shape)

    if (!artworkUrl.isNullOrBlank()) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(artworkUrl)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            modifier = commonModifier,
            contentScale = ContentScale.Crop,
            loading = {
                ArtPlaceholder(Modifier.size(size), size)
            },
            error = {
                ArtPlaceholder(Modifier.size(size), size)
            },
        )
    } else {
        ArtPlaceholder(commonModifier, size)
    }
}

/**
 * Variant that fills available width with a 1:1 aspect ratio.
 * Used for large album art displays (NowPlaying, Album header).
 */
@Composable
fun AlbumArtCardFill(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    elevation: Dp = 4.dp,
    contentDescription: String? = "Album artwork",
) {
    val shape = RoundedCornerShape(cornerRadius)
    val commonModifier = modifier
        .aspectRatio(1f)
        .shadow(elevation, shape)
        .clip(shape)

    if (!artworkUrl.isNullOrBlank()) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(artworkUrl)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            modifier = commonModifier,
            contentScale = ContentScale.Crop,
            loading = {
                ArtPlaceholder(Modifier.aspectRatio(1f), 48.dp)
            },
            error = {
                ArtPlaceholder(Modifier.aspectRatio(1f), 48.dp)
            },
        )
    } else {
        ArtPlaceholder(commonModifier, 48.dp)
    }
}

@Composable
private fun ArtPlaceholder(modifier: Modifier, iconSize: Dp) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize * 0.4f),
        )
    }
}
