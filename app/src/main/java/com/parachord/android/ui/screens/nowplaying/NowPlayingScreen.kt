package com.parachord.android.ui.screens.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.ui.components.AlbumArtCardFill
import com.parachord.android.ui.components.ResolverBadge
import com.parachord.android.ui.theme.PlayerSurface
import com.parachord.android.ui.theme.PlayerTextPrimary
import com.parachord.android.ui.theme.PlayerTextSecondary
import com.parachord.android.ui.theme.PurpleDark
import com.parachord.android.ui.theme.PurpleLight
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val track = playbackState.currentTrack

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PlayerSurface),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Always-dark top app bar
        TopAppBar(
            title = {
                Text(
                    text = "Now Playing",
                    color = PlayerTextSecondary,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = PlayerTextPrimary,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
            ),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Large album artwork with shadow
        AlbumArtCardFill(
            artworkUrl = track?.artworkUrl,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(horizontal = 8.dp),
            cornerRadius = 12.dp,
            elevation = 8.dp,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Track info
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = track?.title ?: "No track playing",
                style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                color = PlayerTextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = track?.artist ?: "",
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = PlayerTextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Resolver badge
            if (track?.resolver != null) {
                Spacer(modifier = Modifier.height(8.dp))
                ResolverBadge(resolver = track.resolver!!)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Seek bar with custom colors
        val duration = playbackState.duration.coerceAtLeast(1L)
        val position = playbackState.position

        Column(modifier = Modifier.padding(horizontal = 32.dp)) {
            Slider(
                value = position.toFloat(),
                onValueChange = { viewModel.seekTo(it.toLong()) },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = PurpleDark,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatDuration(position),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = PlayerTextSecondary,
                )
                Text(
                    text = formatDuration(duration),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = PlayerTextSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Playback controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Skip Previous
            IconButton(
                onClick = { viewModel.skipPrevious() },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = PlayerTextPrimary,
                ),
            ) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(36.dp),
                )
            }

            // Play/Pause — large purple circle
            IconButton(
                onClick = { viewModel.togglePlayPause() },
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(PurpleLight),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.White,
                ),
            ) {
                Icon(
                    imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(36.dp),
                )
            }

            // Skip Next
            IconButton(
                onClick = { viewModel.skipNext() },
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = PlayerTextPrimary,
                ),
            ) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
