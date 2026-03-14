package com.parachord.android.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.Alignment
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.SwipeableTabLayout
import com.parachord.android.ui.components.TrackRow
import com.parachord.android.ui.screens.friends.FriendsViewModel
import com.parachord.android.ui.screens.sync.SyncSetupSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToFriend: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
    friendsViewModel: FriendsViewModel = hiltViewModel(),
) {
    val sortedArtistNames by viewModel.sortedArtistNames.collectAsStateWithLifecycle()
    val sortedAlbums by viewModel.sortedAlbums.collectAsStateWithLifecycle()
    val sortedTracks by viewModel.sortedTracks.collectAsStateWithLifecycle()
    val syncedArtists by viewModel.artists.collectAsStateWithLifecycle()
    val rawTracks by viewModel.tracks.collectAsStateWithLifecycle()
    val rawAlbums by viewModel.albums.collectAsStateWithLifecycle()
    val friends by friendsViewModel.friends.collectAsState()

    // Unfiltered counts for tab labels
    val artistCount = remember(rawTracks, syncedArtists) {
        (rawTracks.map { it.artist } + syncedArtists.map { it.name }).distinct().size
    }

    val artistSort by viewModel.artistSort.collectAsStateWithLifecycle()
    val albumSort by viewModel.albumSort.collectAsStateWithLifecycle()
    val trackSort by viewModel.trackSort.collectAsStateWithLifecycle()
    val friendSort by viewModel.friendSort.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    var showSyncSheet by remember { mutableStateOf(false) }

    if (showSyncSheet) {
        SyncSetupSheet(onDismiss = { showSyncSheet = false })
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "COLLECTION",
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
            actions = {
                IconButton(onClick = { showSyncSheet = true }) {
                    Icon(Icons.Default.Sync, contentDescription = "Sync")
                }
            },
            windowInsets = WindowInsets(0),
        )
        SwipeableTabLayout(
            tabs = listOf("Artists", "Albums", "Songs", "Friends"),
            counts = listOf(artistCount, rawAlbums.size, rawTracks.size, friends.size),
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> {
                    // Build a lookup for artist image URLs from synced ArtistEntity items
                    val artistImageMap = remember(syncedArtists) {
                        syncedArtists.associate { it.name to it.imageUrl }
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        CollectionFilterBar(
                            sortLabel = artistSort.label,
                            sortOptions = ArtistSort.entries.map { sort ->
                                sort.label to { viewModel.setArtistSort(sort) }
                            },
                            selectedSortLabel = artistSort.label,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { viewModel.setSearchQuery(it) },
                            onClearSearch = { viewModel.setSearchQuery("") },
                        )
                        if (sortedArtistNames.isEmpty()) {
                            EmptyState("No artists yet", Icons.Default.Person, onSyncClick = { showSyncSheet = true })
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(sortedArtistNames, key = { it }) { artist ->
                                    val imageUrl = artistImageMap[artist]

                                    // Trigger lazy image enrichment for artists in the DB with no image
                                    if (artist in artistImageMap && imageUrl == null) {
                                        LaunchedEffect(artist) {
                                            viewModel.enrichArtistImageIfNeeded(artist)
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onNavigateToArtist(artist) }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AlbumArtCard(
                                            artworkUrl = imageUrl,
                                            size = 40.dp,
                                            cornerRadius = 20.dp,
                                            placeholderName = artist,
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = artist,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        CollectionFilterBar(
                            sortLabel = albumSort.label,
                            sortOptions = AlbumSort.entries.map { sort ->
                                sort.label to { viewModel.setAlbumSort(sort) }
                            },
                            selectedSortLabel = albumSort.label,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { viewModel.setSearchQuery(it) },
                            onClearSearch = { viewModel.setSearchQuery("") },
                        )
                        if (sortedAlbums.isEmpty()) {
                            EmptyState("No albums yet", Icons.Default.MusicNote, onSyncClick = { showSyncSheet = true })
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(sortedAlbums, key = { it.id }) { album ->
                                    // Trigger lazy artwork enrichment for albums with no artwork
                                    if (album.artworkUrl == null) {
                                        LaunchedEffect(album.id) {
                                            viewModel.enrichAlbumArtIfNeeded(album.title, album.artist)
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onNavigateToAlbum(album.title, album.artist) }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        AlbumArtCard(
                                            artworkUrl = album.artworkUrl,
                                            size = 48.dp,
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
                                            Text(
                                                text = album.artist,
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
                    }
                }
                2 -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        CollectionFilterBar(
                            sortLabel = trackSort.label,
                            sortOptions = TrackSort.entries.map { sort ->
                                sort.label to { viewModel.setTrackSort(sort) }
                            },
                            selectedSortLabel = trackSort.label,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { viewModel.setSearchQuery(it) },
                            onClearSearch = { viewModel.setSearchQuery("") },
                        )
                        if (sortedTracks.isEmpty()) {
                            EmptyState("No songs yet", Icons.Default.MusicNote, onSyncClick = { showSyncSheet = true })
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(sortedTracks, key = { it.id }) { track ->
                                    TrackRow(
                                        title = track.title,
                                        artist = track.artist,
                                        artworkUrl = track.artworkUrl,
                                        resolver = track.resolver,
                                        duration = track.duration,
                                        onClick = { viewModel.playTrack(track) },
                                    )
                                }
                            }
                        }
                    }
                }
                3 -> {
                    FriendsTab(
                        friends = friends,
                        friendSort = friendSort,
                        searchQuery = searchQuery,
                        onSortChange = { viewModel.setFriendSort(it) },
                        onSearchQueryChange = { viewModel.setSearchQuery(it) },
                        onClearSearch = { viewModel.setSearchQuery("") },
                        onNavigateToFriend = onNavigateToFriend,
                        onRemoveFriend = { friendsViewModel.removeFriend(it) },
                    )
                }
            }
        }
    }
}

private val OnAirGreen = Color(0xFF22C55E)
private val LastFmRed = Color(0xFFD51007)
private val ListenBrainzOrange = Color(0xFFE8702A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendsTab(
    friends: List<FriendEntity>,
    friendSort: FriendSort,
    searchQuery: String,
    onSortChange: (FriendSort) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onNavigateToFriend: (String) -> Unit,
    onRemoveFriend: (String) -> Unit,
) {
    // Apply filter and sort inline
    val sortedFriends = remember(friends, friendSort, searchQuery) {
        val filtered = if (searchQuery.isBlank()) friends else {
            friends.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
        }
        when (friendSort) {
            FriendSort.ALPHA_ASC -> filtered.sortedBy { it.displayName.lowercase() }
            FriendSort.ALPHA_DESC -> filtered.sortedByDescending { it.displayName.lowercase() }
            FriendSort.RECENT -> filtered.sortedByDescending { it.addedAt }
            FriendSort.ON_AIR -> filtered.sortedByDescending { it.isOnAir }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CollectionFilterBar(
            sortLabel = friendSort.label,
            sortOptions = FriendSort.entries.map { sort ->
                sort.label to { onSortChange(sort) }
            },
            selectedSortLabel = friendSort.label,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            onClearSearch = onClearSearch,
        )
        if (sortedFriends.isEmpty()) {
            EmptyState("No friends yet", Icons.Default.People)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(sortedFriends, key = { it.id }) { friend ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                onRemoveFriend(friend.id)
                                true
                            } else {
                                false
                            }
                        },
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.error)
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onError,
                                )
                            }
                        },
                        enableDismissFromStartToEnd = false,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { onNavigateToFriend(friend.id) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Avatar with on-air indicator
                            Box {
                                AlbumArtCard(
                                    artworkUrl = friend.avatarUrl,
                                    size = 48.dp,
                                    cornerRadius = 24.dp,
                                    placeholderName = friend.displayName,
                                )
                                if (friend.isOnAir) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .size(14.dp)
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
                                        style = MaterialTheme.typography.bodyLarge,
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
                                if (friend.isOnAir && friend.cachedTrackName != null) {
                                    Text(
                                        text = "\u25B6 ${friend.cachedTrackArtist ?: ""} \u2014 ${friend.cachedTrackName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnAirGreen,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } else if (friend.cachedTrackName != null) {
                                    Text(
                                        text = "${friend.cachedTrackArtist ?: ""} \u2014 ${friend.cachedTrackName}",
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
            }
        }
    }
}

@Composable
private fun EmptyState(
    message: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onSyncClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onSyncClick != null) {
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onSyncClick) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sync Spotify")
                }
            }
        }
    }
}
