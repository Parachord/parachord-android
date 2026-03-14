package com.parachord.android.ui.screens.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ParachordCard
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.ShimmerTrackRow
import com.parachord.android.ui.components.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToNowPlaying: () -> Unit,
    onOpenDrawer: () -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onNavigateToPlaylist: (playlistId: String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToFriend: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val recentTracks by viewModel.recentTracks.collectAsStateWithLifecycle()
    val hasLibrary by viewModel.hasLibrary.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val recentAlbums by viewModel.recentAlbums.collectAsStateWithLifecycle()
    val recentPlaylists by viewModel.recentPlaylists.collectAsStateWithLifecycle()
    val friendActivity by viewModel.friendActivity.collectAsStateWithLifecycle()
    val stats by viewModel.collectionStats.collectAsStateWithLifecycle()

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.scanLocalMusic()
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "PARACHORD",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.2.em,
                    ),
                )
            },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Filled.Menu, contentDescription = "Menu")
                }
            },
            windowInsets = WindowInsets(0),
        )

        if (!hasLibrary) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                ParachordCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Welcome to Parachord",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your music, unified.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    if (scanProgress.isScanning) {
                        repeat(3) {
                            ShimmerTrackRow()
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Found ${scanProgress.tracksFound} tracks, ${scanProgress.albumsFound} albums...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Button(
                            onClick = { permissionLauncher.launch(audioPermission) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text("Scan Local Music")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Or connect your accounts in Settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                // ── Recently Added Albums ────────────────────────────────
                if (recentAlbums.isNotEmpty()) {
                    item { SectionHeader("Recently Added") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(recentAlbums, key = { it.id }) { album ->
                                RecentAlbumCard(
                                    album = album,
                                    onClick = { onNavigateToAlbum(album.title, album.artist) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // ── Your Playlists ───────────────────────────────────────
                if (recentPlaylists.isNotEmpty()) {
                    item { SectionHeader("Your Playlists") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(recentPlaylists, key = { it.id }) { playlist ->
                                PlaylistCard(
                                    playlist = playlist,
                                    onClick = { onNavigateToPlaylist(playlist.id) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // ── Discover ─────────────────────────────────────────────
                item { SectionHeader("Discover") }
                item {
                    DiscoverGrid(
                        onNavigateToRecommendations = { /* TODO */ },
                        onNavigateToCharts = { /* TODO */ },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // ── Friend Activity ──────────────────────────────────────
                if (friendActivity.isNotEmpty()) {
                    item { SectionHeader("Friend Activity") }
                    item {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            friendActivity.forEach { friend ->
                                FriendActivityRow(
                                    friend = friend,
                                    onClick = { onNavigateToFriend(friend.id) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // ── Collection Stats ─────────────────────────────────────
                item { SectionHeader("Your Collection") }
                item {
                    StatsRow(stats = stats)
                }

                // ── Recent Tracks ────────────────────────────────────────
                if (recentTracks.isNotEmpty()) {
                    item { SectionHeader("Recent Tracks") }
                    items(recentTracks.take(10), key = { it.id }) { track ->
                        TrackRow(
                            title = track.title,
                            artist = track.artist,
                            artworkUrl = track.artworkUrl,
                            resolver = track.resolver,
                            duration = track.duration,
                            onClick = {
                                viewModel.playTrack(track)
                                onNavigateToNowPlaying()
                            },
                        )
                    }
                }
            }
        }
    }
}

// ── Recently Added Album Card ────────────────────────────────────

@Composable
private fun RecentAlbumCard(
    album: AlbumEntity,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
    ) {
        AlbumArtCard(
            artworkUrl = album.artworkUrl,
            size = 140.dp,
            cornerRadius = 8.dp,
            elevation = 2.dp,
            placeholderName = album.artist,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = album.artist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Playlist Card ────────────────────────────────────────────────

@Composable
private fun PlaylistCard(
    playlist: PlaylistEntity,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .clickable(onClick = onClick),
    ) {
        AlbumArtCard(
            artworkUrl = playlist.artworkUrl,
            size = 130.dp,
            cornerRadius = 8.dp,
            elevation = 2.dp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${playlist.trackCount} tracks",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// ── Discover Grid ────────────────────────────────────────────────

@Composable
private fun DiscoverGrid(
    onNavigateToRecommendations: () -> Unit,
    onNavigateToCharts: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DiscoverCard(
                title = "For You",
                subtitle = "Personalized picks",
                icon = Icons.Filled.Star,
                gradient = listOf(
                    Color(0xFF6366F1), // indigo
                    Color(0xFF8B5CF6), // purple
                    Color(0xFFEC4899), // pink
                ),
                onClick = onNavigateToRecommendations,
                modifier = Modifier.weight(1f),
            )
            DiscoverCard(
                title = "Critical Darlings",
                subtitle = "Staff picks",
                icon = Icons.Filled.Favorite,
                gradient = listOf(
                    Color(0xFFF59E0B), // amber
                    Color(0xFFF97316), // orange
                    Color(0xFFEF4444), // red
                ),
                onClick = { /* TODO */ },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DiscoverCard(
                title = "Pop of the Tops",
                subtitle = "What's trending",
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                gradient = listOf(
                    Color(0xFFF97316), // orange
                    Color(0xFFEC4899), // pink
                    Color(0xFF8B5CF6), // purple
                ),
                onClick = onNavigateToCharts,
                modifier = Modifier.weight(1f),
            )
            DiscoverCard(
                title = "Fresh Drops",
                subtitle = "New releases",
                icon = Icons.Filled.Explore,
                gradient = listOf(
                    Color(0xFF10B981), // emerald
                    Color(0xFF14B8A6), // teal
                    Color(0xFF06B6D4), // cyan
                ),
                onClick = { /* TODO */ },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DiscoverCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(gradient))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(22.dp),
            )
            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1,
                )
            }
        }
    }
}

// ── Friend Activity Row ──────────────────────────────────────────

private val OnAirGreen = Color(0xFF22C55E)

@Composable
private fun FriendActivityRow(
    friend: FriendEntity,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar with on-air indicator
        Box {
            AlbumArtCard(
                artworkUrl = friend.avatarUrl,
                size = 40.dp,
                cornerRadius = 20.dp,
                placeholderName = friend.displayName,
            )
            if (friend.isOnAir) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(OnAirGreen),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = friend.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            friend.cachedTrackName?.let { track ->
                Text(
                    text = buildString {
                        append(track)
                        friend.cachedTrackArtist?.let { append("  ·  $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (friend.isOnAir) OnAirGreen
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (friend.isOnAir) {
            Text(
                text = "LIVE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = OnAirGreen,
                modifier = Modifier
                    .background(
                        color = OnAirGreen.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        } else if (friend.cachedTrackTimestamp > 0) {
            Text(
                text = formatTimeAgo(friend.cachedTrackTimestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

// ── Collection Stats Row ─────────────────────────────────────────

@Composable
private fun StatsRow(stats: CollectionStats) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF3F4F6)
    val cardBorder = if (isDark) Color(0xFF2A2A2A) else Color(0xFFE5E7EB)

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            StatCard(
                icon = Icons.Filled.MusicNote,
                count = stats.tracks,
                label = "Songs",
                bg = cardBg,
                border = cardBorder,
            )
        }
        item {
            StatCard(
                icon = Icons.Filled.Album,
                count = stats.albums,
                label = "Albums",
                bg = cardBg,
                border = cardBorder,
            )
        }
        item {
            StatCard(
                icon = Icons.Filled.Person,
                count = stats.artists,
                label = "Artists",
                bg = cardBg,
                border = cardBorder,
            )
        }
        item {
            StatCard(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                count = stats.playlists,
                label = "Playlists",
                bg = cardBg,
                border = cardBorder,
            )
        }
        item {
            StatCard(
                icon = Icons.Filled.People,
                count = stats.friends,
                label = "Friends",
                bg = cardBg,
                border = cardBorder,
            )
        }
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    count: Int,
    label: String,
    bg: Color,
    border: Color,
) {
    Column(
        modifier = Modifier
            .width(90.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "$count",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Utility ──────────────────────────────────────────────────────

private fun formatTimeAgo(timestampSeconds: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - timestampSeconds
    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86400 -> "${diff / 3600}h"
        diff < 604800 -> "${diff / 86400}d"
        else -> "${diff / 604800}w"
    }
}
