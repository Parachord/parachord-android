package com.parachord.android.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.request.ImageRequest

/**
 * Reusable album art image composable with rounded corners, shadow,
 * and a gradient placeholder when no artwork URL is available.
 *
 * When [placeholderName] is provided, uses a deterministic gradient
 * with initials (matching the desktop app's behavior). Otherwise falls
 * back to a simple music note placeholder.
 */
@Composable
fun AlbumArtCard(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    cornerRadius: Dp = 8.dp,
    elevation: Dp = 2.dp,
    placeholderName: String? = null,
    contentDescription: String? = "Album artwork",
) {
    val shape = RoundedCornerShape(cornerRadius)
    val commonModifier = modifier
        .size(size)
        .shadow(elevation, shape)
        .clip(shape)

    // Animate between different artwork URLs (track switches) with a smooth crossfade
    Crossfade(
        targetState = artworkUrl,
        animationSpec = tween(durationMillis = 300),
        modifier = commonModifier,
        label = "album-art-crossfade",
    ) { url ->
        if (!url.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(300)
                    .build(),
                contentDescription = contentDescription,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
                loading = {
                    if (placeholderName != null) {
                        GradientPlaceholder(
                            name = placeholderName,
                            modifier = Modifier.size(size),
                            fontSize = (size.value * 0.35f).sp,
                        )
                    } else {
                        ArtPlaceholderSimple(Modifier.size(size), size)
                    }
                },
                error = {
                    if (placeholderName != null) {
                        GradientPlaceholder(
                            name = placeholderName,
                            modifier = Modifier.size(size),
                            fontSize = (size.value * 0.35f).sp,
                        )
                    } else {
                        ArtPlaceholderSimple(Modifier.size(size), size)
                    }
                },
            )
        } else {
            if (placeholderName != null) {
                GradientPlaceholder(
                    name = placeholderName,
                    modifier = Modifier.size(size),
                    fontSize = (size.value * 0.35f).sp,
                )
            } else {
                ArtPlaceholderSimple(Modifier.size(size), size)
            }
        }
    }
}

/**
 * Variant that fills available width with a 1:1 aspect ratio.
 * Used for large album art displays (NowPlaying, Album header).
 *
 * Defers URL changes until the new image is decoded — the displayed URL
 * only updates on Coil's onSuccess callback, so the old artwork stays
 * visible with zero flicker. Uses Compose Crossfade for smooth transitions.
 */
@Composable
fun AlbumArtCardFill(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    elevation: Dp = 4.dp,
    placeholderName: String? = null,
    contentDescription: String? = "Album artwork",
) {
    val shape = RoundedCornerShape(cornerRadius)
    val commonModifier = modifier
        .aspectRatio(1f)
        .shadow(elevation, shape)
        .clip(shape)

    // Only update the displayed URL when the image is actually decoded.
    // This prevents any flash/flicker when the URL changes — the old
    // image stays visible until the new one is ready.
    var displayedUrl by remember { mutableStateOf(artworkUrl) }
    val context = LocalContext.current

    // Prefetch the new URL; update displayedUrl only on success
    if (artworkUrl != displayedUrl && !artworkUrl.isNullOrBlank()) {
        androidx.compose.runtime.LaunchedEffect(artworkUrl) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .build()
                context.imageLoader.execute(request)
                // Image is now in memory cache — safe to switch
                displayedUrl = artworkUrl
            } catch (_: Exception) {
                // Failed to load — keep showing current image
            }
        }
    } else if (artworkUrl.isNullOrBlank()) {
        displayedUrl = null
    }

    Crossfade(
        targetState = displayedUrl,
        animationSpec = tween(durationMillis = 400),
        modifier = commonModifier,
        label = "album-art-fill-crossfade",
    ) { url ->
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(url)
                    .build(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            if (placeholderName != null) {
                GradientPlaceholder(
                    name = placeholderName,
                    modifier = Modifier.fillMaxSize(),
                    fontSize = 32.sp,
                )
            } else {
                ArtPlaceholderSimple(Modifier.fillMaxSize(), 48.dp)
            }
        }
    }
}

/**
 * Simple music note placeholder (fallback when no name is available).
 */
@Composable
private fun ArtPlaceholderSimple(modifier: Modifier, iconSize: Dp) {
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
