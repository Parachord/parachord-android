package com.parachord.android.ui.screens.artist

import com.parachord.android.resolver.trackKey
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import coil.compose.AsyncImage
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.data.db.entity.TrackEntity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.AlbumArtCardFill
import com.parachord.android.ui.components.FaceAwareImage
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.ShimmerTrackRow
import com.parachord.android.ui.components.shimmerBrush
import com.parachord.android.ui.components.SwipeableTabLayout
import com.parachord.android.ui.components.TrackContextInfo
import com.parachord.android.ui.components.TrackContextMenuHost
import com.parachord.android.ui.components.TrackRow
import com.parachord.android.ui.components.rememberTrackContextMenuState
import com.parachord.android.data.metadata.AlbumSearchResult
import com.parachord.android.data.metadata.SimilarArtist
import com.parachord.android.data.metadata.TrackSearchResult
import com.parachord.android.data.repository.ConcertEvent
import com.parachord.android.data.repository.Resource

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
    val trackResolvers by viewModel.trackResolvers.collectAsStateWithLifecycle()
    val trackResolverConfidences by viewModel.trackResolverConfidences.collectAsStateWithLifecycle()
    val isSaved by viewModel.isSaved.collectAsStateWithLifecycle()
    val tourDates by viewModel.tourDates.collectAsStateWithLifecycle()
    val isOnTour by viewModel.isOnTour.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val contextMenuState = rememberTrackContextMenuState()

    // Context menu host
    TrackContextMenuHost(
        state = contextMenuState,
        playlists = playlists,
        onPlayNext = { viewModel.playNext(it) },
        onAddToQueue = { viewModel.addToQueue(it) },
        onAddToPlaylist = { playlist, track -> viewModel.addToPlaylist(playlist, track) },
        onNavigateToArtist = onNavigateToArtist,
        onNavigateToAlbum = onNavigateToAlbum,
        onToggleCollection = { track, isInCollection ->
            if (!isInCollection) viewModel.addToCollection(track)
        },
    )

    val density = LocalDensity.current

    // Track the max (expanded) height of the hero image in pixels
    var imageMaxHeightPx by remember { mutableFloatStateOf(0f) }
    // Current collapse offset: 0 = fully expanded, imageMaxHeightPx = fully collapsed
    var collapseOffset by remember { mutableFloatStateOf(0f) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (imageMaxHeightPx <= 0f) return Offset.Zero
                val delta = available.y
                if (delta < 0f) {
                    // Scrolling up — collapse the image first
                    val oldOffset = collapseOffset
                    collapseOffset = (collapseOffset - delta).coerceIn(0f, imageMaxHeightPx)
                    val consumed = oldOffset - collapseOffset
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (imageMaxHeightPx <= 0f) return Offset.Zero
                val delta = available.y
                if (delta > 0f) {
                    // Scrolling down and child didn't consume it — expand the image
                    val oldOffset = collapseOffset
                    collapseOffset = (collapseOffset - delta).coerceIn(0f, imageMaxHeightPx)
                    val consumed = oldOffset - collapseOffset
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (isLoading && artistInfo == null) {
                    Box(
                        modifier = Modifier
                            .width(140.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerBrush()),
                    )
                } else {
                    Text(
                        text = (artistInfo?.name ?: "").uppercase(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Light,
                            letterSpacing = 0.2.em,
                        ),
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { viewModel.toggleSaved() }) {
                    Icon(
                        imageVector = if (isSaved) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = if (isSaved) "Remove from collection" else "Save to collection",
                        tint = if (isSaved) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            windowInsets = WindowInsets(0),
        )

        if (isLoading) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                repeat(5) { ShimmerTrackRow() }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection),
            ) {
                // Hero image — collapsible
                val imageUrl = artistInfo?.imageUrl
                val hasImage = !imageUrl.isNullOrBlank()
                if (hasImage) {
                    val visibleHeightDp = with(density) {
                        (imageMaxHeightPx - collapseOffset).coerceAtLeast(0f).toDp()
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(visibleHeightDp)
                            .clipToBounds(),
                    ) {
                        FaceAwareImage(
                            imageUrl = imageUrl!!,
                            contentDescription = "Artist image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .onSizeChanged { size ->
                                    if (size.height > 0 && imageMaxHeightPx == 0f) {
                                        imageMaxHeightPx = size.height.toFloat()
                                    }
                                },
                            loading = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            },
                            error = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            },
                        )
                    }
                }

                // Tabs + pager — show "On Tour" tab when artist has upcoming dates
                // Desktop puts "On Tour" last: Music | Biography | Related Artists | On Tour
                // Appending at end avoids shifting existing tab indices when it appears
                val tabs = remember(isOnTour) {
                    buildList {
                        add("Discography")
                        add("Biography")
                        add("Related Artists")
                        if (isOnTour) add("On Tour")
                    }
                }

                SwipeableTabLayout(
                    tabs = tabs,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val tabName = tabs.getOrNull(page) ?: return@SwipeableTabLayout
                    when (tabName) {
                        "Discography" -> DiscographyTab(
                            albums = albums,
                            topTracks = topTracks,
                            onNavigateToAlbum = onNavigateToAlbum,
                            onPlayTrack = viewModel::playTrack,
                            trackResolvers = trackResolvers,
                            trackResolverConfidences = trackResolverConfidences,
                            onTrackLongClick = { track ->
                                val entity = viewModel.trackSearchResultToEntity(track)
                                contextMenuState.show(
                                    TrackContextInfo(
                                        title = track.title,
                                        artist = track.artist,
                                        album = track.album,
                                        artworkUrl = track.artworkUrl,
                                        duration = track.duration,
                                    ),
                                    entity,
                                )
                            },
                        )
                        "On Tour" -> OnTourTab(tourDates = tourDates)
                        "Biography" -> BiographyTab(
                            bio = artistInfo?.bio,
                            bioSource = artistInfo?.bioSource,
                            bioUrl = artistInfo?.bioUrl,
                            tags = artistInfo?.tags ?: emptyList(),
                            provider = artistInfo?.provider,
                        )
                        "Related Artists" -> RelatedArtistsTab(
                            similarArtists = artistInfo?.similarArtists ?: emptyList(),
                            onNavigateToArtist = onNavigateToArtist,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Release type filters for the discography tab.
 * "all" shows everything; other values match AlbumSearchResult.releaseType.
 */
private data class ReleaseFilter(val key: String, val label: String)

private val RELEASE_FILTERS = listOf(
    ReleaseFilter("all", "All"),
    ReleaseFilter("album", "Albums"),
    ReleaseFilter("single", "Singles"),
    ReleaseFilter("ep", "EPs"),
    ReleaseFilter("live", "Live"),
    ReleaseFilter("compilation", "Compilations"),
)

private fun releaseTypeBadgeColor(type: String?): Color = when (type?.lowercase()) {
    "album" -> Color(0xFF6366F1)        // indigo
    "ep" -> Color(0xFFA855F7)           // purple
    "single" -> Color(0xFFEC4899)       // pink
    "live" -> Color(0xFFF59E0B)         // amber
    "compilation" -> Color(0xFF14B8A6)  // teal
    else -> Color(0xFF9CA3AF)           // gray
}

@Composable
private fun DiscographyTab(
    albums: List<AlbumSearchResult>,
    topTracks: List<TrackSearchResult>,
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit,
    onPlayTrack: (TrackSearchResult) -> Unit = {},
    trackResolvers: Map<String, List<String>> = emptyMap(),
    trackResolverConfidences: Map<String, Map<String, Float>> = emptyMap(),
    onTrackLongClick: (TrackSearchResult) -> Unit = {},
) {
    var selectedFilter by remember { mutableStateOf("all") }

    // Calculate counts per type
    val typeCounts = remember(albums) {
        albums.groupBy { it.releaseType?.lowercase() ?: "album" }
            .mapValues { it.value.size }
    }

    val availableFilters = remember(albums, typeCounts) {
        val types = albums.mapNotNull { it.releaseType?.lowercase() }.toSet()
        RELEASE_FILTERS.filter { it.key == "all" || it.key in types }
    }

    val filteredAlbums = remember(albums, selectedFilter) {
        if (selectedFilter == "all") albums
        else albums.filter { it.releaseType?.lowercase() == selectedFilter }
    }

    val cardBg = MaterialTheme.colorScheme.surfaceVariant

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Filter chips row (only show if we have more than one filter available)
        if (availableFilters.size > 1) {
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    lazyItems(availableFilters, key = { it.key }) { filter ->
                        val chipColor = releaseTypeBadgeColor(
                            if (filter.key == "all") null else filter.key,
                        )
                        val count = if (filter.key == "all") null else typeCounts[filter.key]
                        val chipLabel = if (count != null) "${filter.label} ($count)" else filter.label
                        FilterChip(
                            selected = selectedFilter == filter.key,
                            onClick = { selectedFilter = filter.key },
                            label = { Text(chipLabel) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = chipColor.copy(alpha = 0.20f),
                                selectedLabelColor = chipColor,
                            ),
                        )
                    }
                }
            }
        }

        if (filteredAlbums.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    filteredAlbums.chunked(2).forEach { rowAlbums ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowAlbums.forEach { album ->
                                Box(modifier = Modifier.weight(1f)) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(cardBg)
                                            .clickable { onNavigateToAlbum(album.title, album.artist) }
                                            .padding(10.dp),
                                    ) {
                                        AlbumArtCardFill(
                                            artworkUrl = album.artworkUrl,
                                            modifier = Modifier.fillMaxWidth(),
                                            cornerRadius = 6.dp,
                                            elevation = 1.dp,
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = album.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            album.releaseType?.let { type ->
                                                val badgeColor = releaseTypeBadgeColor(type)
                                                Text(
                                                    text = type.uppercase(),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    letterSpacing = 0.5.sp,
                                                    color = badgeColor,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(badgeColor.copy(alpha = 0.18f))
                                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                                )
                                            }
                                            album.year?.let { year ->
                                                Text(
                                                    text = "$year",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            // Fill empty space if odd number
                            if (rowAlbums.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        if (topTracks.isNotEmpty() && selectedFilter == "all") {
            item {
                if (filteredAlbums.isNotEmpty()) HorizontalDivider()
                SectionHeader("TOP TRACKS")
            }
            items(topTracks) { track ->
                TrackRow(
                    title = track.title,
                    artist = track.album ?: "",
                    artworkUrl = track.artworkUrl,
                    resolvers = trackResolvers[trackKey(track.title, track.artist)]?.ifEmpty { null },
                    resolverConfidences = trackResolverConfidences[trackKey(track.title, track.artist)],
                    onClick = { onPlayTrack(track) },
                    onLongClick = { onTrackLongClick(track) },
                )
            }
        }

        if (filteredAlbums.isEmpty() && (topTracks.isEmpty() || selectedFilter != "all")) {
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
    bioSource: String?,
    bioUrl: String?,
    tags: List<String>,
    provider: String?,
) {
    val uriHandler = LocalUriHandler.current

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

            // Bio source attribution with optional "Read more" link
            if (bioSource != null) {
                item {
                    val sourceName = when (bioSource) {
                        "wikipedia" -> "Wikipedia"
                        "discogs" -> "Discogs"
                        "lastfm" -> "Last.fm"
                        else -> bioSource
                    }
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "From $sourceName",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        if (bioUrl != null) {
                            Text(
                                text = " · ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                text = "Read more",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { uriHandler.openUri(bioUrl) },
                            )
                        }
                    }
                }
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
    similarArtists: List<SimilarArtist>,
    onNavigateToArtist: (String) -> Unit,
) {
    if (similarArtists.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No related artists",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                count = similarArtists.size,
                key = { idx -> "similar-$idx-${similarArtists[idx].name}" },
            ) { idx ->
                val artist = similarArtists[idx]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToArtist(artist.name) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (artist.imageUrl != null) {
                            AsyncImage(
                                model = artist.imageUrl,
                                contentDescription = artist.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(
                                text = artist.name.take(1).uppercase(),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun OnTourTab(
    tourDates: Resource<List<ConcertEvent>>,
) {
    val uriHandler = LocalUriHandler.current

    when (tourDates) {
        is Resource.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Color(0xFF7C3AED),
                )
            }
        }
        is Resource.Error -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tourDates.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is Resource.Success -> {
            if (tourDates.data.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No upcoming tour dates",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp),
                ) {
                    items(tourDates.data, key = { it.id }) { event ->
                        TourDateRow(
                            event = event,
                            onTicketClick = {
                                event.ticketUrl?.let { uriHandler.openUri(it) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TourDateRow(
    event: ConcertEvent,
    onTicketClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Date column
        Column(
            modifier = Modifier.width(56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val dateParts = event.date?.split("-")
            val month = dateParts?.getOrNull(1)?.toIntOrNull()?.let {
                java.time.Month.of(it).name.take(3)
            } ?: ""
            val day = dateParts?.getOrNull(2) ?: ""
            Text(
                text = month,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF10C9B4), // Concert teal
                letterSpacing = 0.5.sp,
            )
            Text(
                text = day,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (event.venueName != null) {
                Text(
                    text = event.venueName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (event.locationString.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = event.locationString,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    if (event.displayTime.isNotBlank()) {
                        Text(
                            text = " · ${event.displayTime}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }

        // Ticket link
        if (event.ticketUrl != null) {
            IconButton(
                onClick = onTicketClick,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.OpenInNew,
                    contentDescription = "Tickets",
                    modifier = Modifier.size(18.dp),
                    tint = Color(0xFF10C9B4), // Concert teal
                )
            }
        }
    }
}
