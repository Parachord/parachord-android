package com.parachord.android.ui.screens.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.BuildConfig
import com.parachord.android.ui.components.ResolverIconSquare
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.SwipeableTabLayout
import com.parachord.android.ui.theme.Success
import kotlin.math.roundToInt

// ── Plugin Data Model ──────────────────────────────────────────────

private data class PluginInfo(
    val id: String,
    val name: String,
    val resolverId: String, // maps to ResolverIconPaths/ResolverIconColors
    val bgColor: Color,
    val category: PluginCategory,
    val capabilities: List<String>,
    val description: String,
)

private enum class PluginCategory(val label: String) {
    RESOLVER("Content Resolvers"),
    META_SERVICE("Meta Services"),
}

private val builtInPlugins = listOf(
    PluginInfo(
        id = "spotify",
        name = "Spotify",
        resolverId = "spotify",
        bgColor = Color(0xFF1DB954),
        category = PluginCategory.RESOLVER,
        capabilities = listOf("Resolve", "Search", "Stream"),
        description = "Stream music from Spotify via Connect",
    ),
    PluginInfo(
        id = "local-files",
        name = "Local Files",
        resolverId = "localfiles",
        bgColor = Color(0xFF6B7280),
        category = PluginCategory.RESOLVER,
        capabilities = listOf("Resolve", "Browse", "Stream"),
        description = "Play music stored on your device",
    ),
    PluginInfo(
        id = "lastfm",
        name = "Last.fm",
        resolverId = "lastfm",
        bgColor = Color(0xFFD51007),
        category = PluginCategory.META_SERVICE,
        capabilities = listOf("Metadata", "Scrobble", "Recommendations"),
        description = "Scrobbling, artist info, and recommendations",
    ),
)

// ── Settings Screen ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val scrobbling by viewModel.scrobblingEnabled.collectAsStateWithLifecycle()
    val spotifyConnected by viewModel.spotifyConnected.collectAsStateWithLifecycle()
    val lastFmConnected by viewModel.lastFmConnected.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "SETTINGS",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.2.em,
                    ),
                )
            },
            windowInsets = WindowInsets(0),
        )

        SwipeableTabLayout(
            tabs = listOf("Plug-Ins", "General", "About"),
        ) { page ->
            when (page) {
                0 -> PlugInsTab(
                    spotifyConnected = spotifyConnected,
                    lastFmConnected = lastFmConnected,
                    onSpotifyToggle = {
                        if (spotifyConnected) viewModel.disconnectSpotify()
                        else viewModel.connectSpotify(BuildConfig.SPOTIFY_CLIENT_ID)
                    },
                    onLastFmToggle = {
                        if (lastFmConnected) viewModel.disconnectLastFm()
                        else viewModel.connectLastFm(BuildConfig.LASTFM_API_KEY)
                    },
                    scrobbling = scrobbling,
                    onScrobblingChanged = { viewModel.setScrobbling(it) },
                )
                1 -> GeneralTab(
                    themeMode = themeMode,
                    onThemeModeChanged = { viewModel.setThemeMode(it) },
                )
                2 -> AboutTab()
            }
        }
    }
}

// ── Plug-Ins Tab ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlugInsTab(
    spotifyConnected: Boolean,
    lastFmConnected: Boolean,
    onSpotifyToggle: () -> Unit,
    onLastFmToggle: () -> Unit,
    scrobbling: Boolean,
    onScrobblingChanged: (Boolean) -> Unit,
) {
    var selectedPlugin by remember { mutableStateOf<PluginInfo?>(null) }

    // Resolver order state (drag-to-reorder)
    val resolverPlugins = builtInPlugins.filter { it.category == PluginCategory.RESOLVER }
    val resolverOrder = remember { mutableStateListOf(*resolverPlugins.map { it.id }.toTypedArray()) }
    val metaServices = builtInPlugins.filter { it.category == PluginCategory.META_SERVICE }

    fun isConnected(pluginId: String): Boolean = when (pluginId) {
        "spotify" -> spotifyConnected
        "lastfm" -> lastFmConnected
        "local-files" -> true // always available
        else -> false
    }

    fun findPlugin(id: String): PluginInfo? = builtInPlugins.find { it.id == id }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Content Resolvers section
        item { SectionHeader("Content Resolvers") }
        item {
            Text(
                text = "Drag to reorder playback priority",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        item {
            DraggableResolverRow(
                resolverOrder = resolverOrder,
                findPlugin = ::findPlugin,
                isConnected = ::isConnected,
                onReorder = { from, to ->
                    val item = resolverOrder.removeAt(from)
                    resolverOrder.add(to, item)
                },
                onPluginClick = { id -> findPlugin(id)?.let { selectedPlugin = it } },
            )
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }

        // Meta Services section
        item { SectionHeader("Meta Services") }
        item {
            Text(
                text = "Services for recommendations, metadata, and AI features",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                metaServices.forEach { plugin ->
                    PluginTile(
                        plugin = plugin,
                        isConnected = isConnected(plugin.id),
                        priorityNumber = null,
                        onClick = { selectedPlugin = plugin },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Fill remaining space
                repeat(3 - metaServices.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }

    // Config bottom sheet
    selectedPlugin?.let { plugin ->
        PluginConfigSheet(
            plugin = plugin,
            isConnected = isConnected(plugin.id),
            onDismiss = { selectedPlugin = null },
            onToggleConnection = {
                when (plugin.id) {
                    "spotify" -> onSpotifyToggle()
                    "lastfm" -> onLastFmToggle()
                }
            },
            scrobbling = scrobbling,
            onScrobblingChanged = onScrobblingChanged,
        )
    }
}

// ── Draggable Resolver Row ────────────────────────────────────────

@Composable
private fun DraggableResolverRow(
    resolverOrder: List<String>,
    findPlugin: (String) -> PluginInfo?,
    isConnected: (String) -> Boolean,
    onReorder: (from: Int, to: Int) -> Unit,
    onPluginClick: (String) -> Unit,
) {
    val density = LocalDensity.current
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableStateOf(0f) }

    // Calculate tile width based on available space
    // Each tile is ~1/3 of the row width with spacing
    val tileWidthDp = 100.dp // approximate, will use weight in practice

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        resolverOrder.forEachIndexed { index, pluginId ->
            val plugin = findPlugin(pluginId) ?: return@forEachIndexed
            val isDragging = draggingIndex == index
            val elevation by animateDpAsState(
                targetValue = if (isDragging) 8.dp else 0.dp,
                label = "dragElevation",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .zIndex(if (isDragging) 1f else 0f)
                    .then(
                        if (isDragging) {
                            Modifier.offset { IntOffset(dragOffsetX.roundToInt(), 0) }
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (isDragging) {
                            Modifier.shadow(elevation, RoundedCornerShape(16.dp))
                        } else {
                            Modifier
                        },
                    )
                    .pointerInput(index, resolverOrder.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffsetX = 0f
                            },
                            onDragEnd = {
                                // Calculate target index based on drag offset
                                val tileWidth = size.width.toFloat() + with(density) { 12.dp.toPx() }
                                val indexShift = (dragOffsetX / tileWidth).roundToInt()
                                val targetIndex = (index + indexShift).coerceIn(0, resolverOrder.size - 1)
                                if (targetIndex != index) {
                                    onReorder(index, targetIndex)
                                }
                                draggingIndex = -1
                                dragOffsetX = 0f
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                dragOffsetX = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetX += dragAmount.x
                            },
                        )
                    },
            ) {
                PluginTile(
                    plugin = plugin,
                    isConnected = isConnected(pluginId),
                    priorityNumber = index + 1,
                    onClick = { onPluginClick(pluginId) },
                )
            }
        }
        // Fill remaining space if fewer than 3
        repeat(3 - resolverOrder.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

// ── Plugin Tile ────────────────────────────────────────────────────

@Composable
private fun PluginTile(
    plugin: PluginInfo,
    isConnected: Boolean,
    priorityNumber: Int? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(plugin.bgColor)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // Service logo SVG icon (white, centered)
            ResolverIconSquare(
                resolver = plugin.resolverId,
                size = 56.dp,
                showBackground = false,
            )

            // Connected status badge (top-right)
            if (isConnected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Success),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Enabled",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            // Priority number badge (top-left, resolvers only)
            if (priorityNumber != null && isConnected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$priorityNumber",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF111827),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = plugin.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Plugin Config Bottom Sheet ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PluginConfigSheet(
    plugin: PluginInfo,
    isConnected: Boolean,
    onDismiss: () -> Unit,
    onToggleConnection: () -> Unit,
    scrobbling: Boolean,
    onScrobblingChanged: (Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            // Header with colored background and service logo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(plugin.bgColor)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ResolverIconSquare(
                        resolver = plugin.resolverId,
                        size = 56.dp,
                        showBackground = false,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Text(
                        text = plugin.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Capabilities
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                plugin.capabilities.forEach { cap ->
                    Text(
                        text = cap,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isConnected) Success.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (isConnected) "ENABLED" else "DISABLED",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isConnected) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Plugin-specific config
            when (plugin.id) {
                "spotify" -> SpotifyConfig(isConnected, onToggleConnection)
                "local-files" -> LocalFilesConfig()
                "lastfm" -> LastFmConfig(isConnected, onToggleConnection, scrobbling, onScrobblingChanged)
            }
        }
    }
}

// ── Spotify Config ─────────────────────────────────────────────────

@Composable
private fun SpotifyConfig(isConnected: Boolean, onToggle: () -> Unit) {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Connection",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (isConnected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Spotify Premium connected",
                style = MaterialTheme.typography.bodySmall,
                color = Success,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onToggle) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }
    } else {
        Text(
            text = "Connect your Spotify Premium account to stream music.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onToggle) {
            Text("Connect Spotify", color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ── Local Files Config ─────────────────────────────────────────────

@Composable
private fun LocalFilesConfig() {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Library",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Device storage",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Local files are automatically scanned from your device's music directories.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
}

// ── Last.fm Config ─────────────────────────────────────────────────

@Composable
private fun LastFmConfig(
    isConnected: Boolean,
    onToggle: () -> Unit,
    scrobbling: Boolean,
    onScrobblingChanged: (Boolean) -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Connection",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (isConnected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Last.fm connected",
                style = MaterialTheme.typography.bodySmall,
                color = Success,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onToggle) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }
    } else {
        Text(
            text = "Connect your Last.fm account for scrobbling and recommendations.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onToggle) {
            Text("Connect Last.fm", color = MaterialTheme.colorScheme.primary)
        }
    }

    // Scrobbling toggle
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Scrobbling",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Send listening history to Last.fm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = scrobbling,
            onCheckedChange = onScrobblingChanged,
            enabled = isConnected,
        )
    }
}

// ── General Tab ────────────────────────────────────────────────────

@Composable
private fun GeneralTab(
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { SectionHeader("Appearance") }
        item {
            ListItem(
                headlineContent = { Text("Dark Mode") },
                supportingContent = {
                    Text(
                        when (themeMode) {
                            "light" -> "Always light"
                            "dark" -> "Always dark"
                            else -> "Follow system"
                        },
                    )
                },
                trailingContent = {
                    ThemeModeSelector(
                        currentMode = themeMode,
                        onModeChanged = onThemeModeChanged,
                    )
                },
            )
        }
    }
}

// ── About Tab ──────────────────────────────────────────────────────

@Composable
private fun AboutTab() {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Parachord",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Version 0.1.0",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "A universal music player that connects all your music sources in one place.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}

// ── Theme Mode Selector ────────────────────────────────────────────

@Composable
private fun ThemeModeSelector(
    currentMode: String,
    onModeChanged: (String) -> Unit,
) {
    val modes = listOf("system" to "Auto", "light" to "Light", "dark" to "Dark")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        modes.forEach { (value, label) ->
            FilterChip(
                selected = currentMode == value,
                onClick = { onModeChanged(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}
