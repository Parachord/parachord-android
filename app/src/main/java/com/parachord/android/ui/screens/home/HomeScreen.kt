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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
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
    onNavigateToRecommendations: () -> Unit = {},
    onNavigateToCriticalDarlings: () -> Unit = {},
    onNavigateToPopOfTheTops: () -> Unit = {},
    onNavigateToFreshDrops: () -> Unit = {},
    onNavigateToCollection: (tab: Int) -> Unit = {},
    onNavigateToPlaylists: () -> Unit = {},
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
    val forYouPreview by viewModel.forYouPreview.collectAsStateWithLifecycle()
    val criticalDarlingsPreview by viewModel.criticalDarlingsPreview.collectAsStateWithLifecycle()
    val freshDropsPreview by viewModel.freshDropsPreview.collectAsStateWithLifecycle()

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
                        forYouPreview = forYouPreview,
                        criticalDarlingsPreview = criticalDarlingsPreview,
                        freshDropsPreview = freshDropsPreview,
                        onNavigateToRecommendations = onNavigateToRecommendations,
                        onNavigateToCriticalDarlings = onNavigateToCriticalDarlings,
                        onNavigateToPopOfTheTops = onNavigateToPopOfTheTops,
                        onNavigateToFreshDrops = onNavigateToFreshDrops,
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
                    StatsRow(
                        stats = stats,
                        onSongsClick = { onNavigateToCollection(2) },
                        onAlbumsClick = { onNavigateToCollection(1) },
                        onArtistsClick = { onNavigateToCollection(0) },
                        onPlaylistsClick = onNavigateToPlaylists,
                        onFriendsClick = { onNavigateToCollection(3) },
                    )
                }

                // ── Recent Loves ─────────────────────────────────────────
                if (recentTracks.isNotEmpty()) {
                    item { SectionHeader("Recent Loves") }
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
    forYouPreview: DiscoverPreview?,
    criticalDarlingsPreview: DiscoverPreview?,
    freshDropsPreview: DiscoverPreview?,
    onNavigateToRecommendations: () -> Unit,
    onNavigateToCriticalDarlings: () -> Unit,
    onNavigateToPopOfTheTops: () -> Unit,
    onNavigateToFreshDrops: () -> Unit,
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
                icon = Icons.Filled.Star,
                gradient = listOf(
                    Color(0xFF6366F1), // indigo
                    Color(0xFF8B5CF6), // purple
                    Color(0xFFEC4899), // pink
                ),
                preview = forYouPreview,
                fallbackSubtitle = "Personalized picks",
                onClick = onNavigateToRecommendations,
                modifier = Modifier.weight(1f),
            )
            DiscoverCard(
                title = "Critical Darlings",
                icon = Icons.Filled.Favorite,
                gradient = listOf(
                    Color(0xFFF59E0B), // amber
                    Color(0xFFF97316), // orange
                    Color(0xFFEF4444), // red
                ),
                preview = criticalDarlingsPreview,
                fallbackSubtitle = "Staff picks",
                onClick = onNavigateToCriticalDarlings,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DiscoverCard(
                title = "Pop of the Tops",
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                gradient = listOf(
                    Color(0xFFF97316), // orange
                    Color(0xFFEC4899), // pink
                    Color(0xFF8B5CF6), // purple
                ),
                preview = null,
                fallbackSubtitle = "Coming soon",
                onClick = onNavigateToPopOfTheTops,
                modifier = Modifier.weight(1f),
            )
            DiscoverCard(
                title = "Fresh Drops",
                icon = Icons.Filled.Explore,
                gradient = listOf(
                    Color(0xFF10B981), // emerald
                    Color(0xFF14B8A6), // teal
                    Color(0xFF06B6D4), // cyan
                ),
                preview = freshDropsPreview,
                fallbackSubtitle = "New releases",
                onClick = onNavigateToFreshDrops,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DiscoverCard(
    title: String,
    icon: ImageVector,
    gradient: List<Color>,
    preview: DiscoverPreview?,
    fallbackSubtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(gradient))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Header: icon + title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                )
            }
            // Preview content or fallback
            if (preview != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (preview.artworkUrl != null) {
                        AlbumArtCard(
                            artworkUrl = preview.artworkUrl,
                            size = 36.dp,
                            cornerRadius = 4.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preview.label,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            letterSpacing = 0.5.sp,
                        )
                        Text(
                            text = preview.title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = preview.subtitle,
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Text(
                    text = fallbackSubtitle,
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
private val LastFmRed = Color(0xFFD51007)
private val ListenBrainzOrange = Color(0xFFE8702A)
private val HomeMiniPlaybarBg = Color(0xF2262626)

/** Hexagonal clip path matching the desktop's friend avatar treatment. */
private val HomeHexagonShape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, 0f)
            lineTo(w, h * 0.25f)
            lineTo(w, h * 0.75f)
            lineTo(w * 0.5f, h)
            lineTo(0f, h * 0.75f)
            lineTo(0f, h * 0.25f)
            close()
        }
        return Outline.Generic(path)
    }
}

@Composable
private fun FriendActivityRow(
    friend: FriendEntity,
    onClick: () -> Unit,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Hexagonal avatar with on-air indicator
        Box(modifier = Modifier.padding(top = 2.dp)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(HomeHexagonShape),
                contentAlignment = Alignment.Center,
            ) {
                if (!friend.avatarUrl.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model = friend.avatarUrl,
                        contentDescription = friend.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp),
                        loading = { HomeHexFallback(friend.displayName) },
                        error = { HomeHexFallback(friend.displayName) },
                    )
                } else {
                    HomeHexFallback(friend.displayName)
                }
            }
            if (friend.isOnAir) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 1.dp, y = 1.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(surfaceColor)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(OnAirGreen),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = friend.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(6.dp))
                // Service badge
                val (badgeText, badgeColor) = when (friend.service) {
                    "lastfm" -> "Last.fm" to LastFmRed
                    "listenbrainz" -> "LB" to ListenBrainzOrange
                    else -> friend.service to MaterialTheme.colorScheme.outline
                }
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    modifier = Modifier
                        .background(
                            color = badgeColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }

            // On-air: mini playbar pill. Offline: plain muted text.
            if (friend.isOnAir && friend.cachedTrackName != null) {
                Spacer(modifier = Modifier.height(4.dp))
                HomeMiniPlaybar(
                    trackName = friend.cachedTrackName!!,
                    artistName = friend.cachedTrackArtist,
                    artworkUrl = friend.cachedTrackArtworkUrl,
                )
            } else if (friend.cachedTrackName != null) {
                Text(
                    text = buildString {
                        append(friend.cachedTrackName)
                        friend.cachedTrackArtist?.let { append("  ·  $it") }
                        if (friend.cachedTrackTimestamp > 0) {
                            append("  ·  ")
                            append(formatTimeAgo(friend.cachedTrackTimestamp))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

    }
}

@Composable
private fun HomeMiniPlaybar(
    trackName: String,
    artistName: String?,
    artworkUrl: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(HomeMiniPlaybarBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (!artworkUrl.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(24.dp),
                    loading = { HomeMiniArtFallback() },
                    error = { HomeMiniArtFallback() },
                )
            } else {
                HomeMiniArtFallback()
            }
        }
        Text(
            text = buildString {
                append(trackName)
                artistName?.let { append("  ·  $it") }
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 24.sp,
            color = Color(0xFFD1D5DB),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(OnAirGreen),
        )
    }
}

@Composable
private fun HomeMiniArtFallback() {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Color(0xFF4B5563)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = Color(0xFF9CA3AF),
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun HomeHexFallback(name: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(
                brush = Brush.linearGradient(
                    listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

// ── Collection Stats Row ─────────────────────────────────────────

@Composable
private fun StatsRow(
    stats: CollectionStats,
    onSongsClick: () -> Unit = {},
    onAlbumsClick: () -> Unit = {},
    onArtistsClick: () -> Unit = {},
    onPlaylistsClick: () -> Unit = {},
    onFriendsClick: () -> Unit = {},
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF3F4F6)

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
                onClick = onSongsClick,
            )
        }
        item {
            StatCard(
                icon = Icons.Filled.Album,
                count = stats.albums,
                label = "Albums",
                bg = cardBg,
                onClick = onAlbumsClick,
            )
        }
        item {
            StatCard(
                icon = Icons.Filled.Person,
                count = stats.artists,
                label = "Artists",
                bg = cardBg,
                onClick = onArtistsClick,
            )
        }
        item {
            StatCard(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                count = stats.playlists,
                label = "Playlists",
                bg = cardBg,
                onClick = onPlaylistsClick,
            )
        }
        item {
            StatCard(
                icon = Icons.Filled.People,
                count = stats.friends,
                label = "Friends",
                bg = cardBg,
                onClick = onFriendsClick,
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
    onClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .width(90.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
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
