package com.parachord.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Custom track list item replacing generic Material ListItem.
 *
 * Shows album art (48dp), title/artist, optional track number,
 * optional resolver badge, and optional duration.
 */
@Composable
fun TrackRow(
    title: String,
    artist: String,
    artworkUrl: String? = null,
    resolver: String? = null,
    duration: Long? = null,
    trackNumber: Int? = null,
    isPlaying: Boolean = false,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Optional track number (for album tracklists)
        if (trackNumber != null) {
            Text(
                text = "$trackNumber",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
        }

        // Album art
        AlbumArtCard(
            artworkUrl = artworkUrl,
            size = 48.dp,
            cornerRadius = 4.dp,
            elevation = 1.dp,
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Title and artist
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isPlaying) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Duration
        if (duration != null && duration > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatTrackDuration(duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Resolver badge
        if (resolver != null) {
            Spacer(modifier = Modifier.width(8.dp))
            ResolverBadge(resolver = resolver)
        }
    }
}

private fun formatTrackDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
