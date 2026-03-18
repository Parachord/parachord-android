package com.parachord.android.ui.screens.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.components.rememberDragHaptics
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.BuildConfig
import com.parachord.android.ui.components.ResolverIconSquare
import com.parachord.android.ui.components.ModalBg
import com.parachord.android.ui.components.ModalBgDarker
import com.parachord.android.ui.components.ModalTextActive
import com.parachord.android.ui.components.ModalTextPrimary
import com.parachord.android.ui.components.ModalScrim
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.SwipeableTabLayout
import com.parachord.android.ui.screens.sync.SyncSetupSheet
import com.parachord.android.ui.screens.sync.SyncViewModel
import com.parachord.android.ui.theme.ParachordTheme
import com.parachord.android.ui.theme.Success
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
        id = "soundcloud",
        name = "SoundCloud",
        resolverId = "soundcloud",
        bgColor = Color(0xFFFF5500),
        category = PluginCategory.RESOLVER,
        capabilities = listOf("Resolve", "Search", "Stream"),
        description = "Search and stream tracks from SoundCloud",
    ),
    PluginInfo(
        id = "applemusic",
        name = "Apple Music",
        resolverId = "applemusic",
        bgColor = Color(0xFFFC3C44),
        category = PluginCategory.RESOLVER,
        capabilities = listOf("Resolve", "Search", "Stream"),
        description = "Search and stream from Apple Music via MusicKit",
    ),
    PluginInfo(
        id = "local-files",
        name = "Local Files",
        resolverId = "localfiles",
        bgColor = Color(0xFFA855F7), // Match ResolverIconColors.localfiles
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
    PluginInfo(
        id = "listenbrainz",
        name = "ListenBrainz",
        resolverId = "listenbrainz",
        bgColor = Color(0xFF353070),
        category = PluginCategory.META_SERVICE,
        capabilities = listOf("Scrobble", "Stats"),
        description = "Open-source listening history and statistics",
    ),
    PluginInfo(
        id = "librefm",
        name = "Libre.fm",
        resolverId = "librefm",
        bgColor = Color(0xFF2E7D32),
        category = PluginCategory.META_SERVICE,
        capabilities = listOf("Scrobble"),
        description = "Free and open music scrobbling service",
    ),
    PluginInfo(
        id = "discogs",
        name = "Discogs",
        resolverId = "discogs",
        bgColor = Color(0xFF333333),
        category = PluginCategory.META_SERVICE,
        capabilities = listOf("Metadata", "Bios", "Images"),
        description = "Artist bios and images from the Discogs community database",
    ),
    PluginInfo(
        id = "wikipedia",
        name = "Wikipedia",
        resolverId = "wikipedia",
        bgColor = Color(0xFF636466),
        category = PluginCategory.META_SERVICE,
        capabilities = listOf("Metadata", "Bios", "Images"),
        description = "Encyclopedia-style artist bios and images via Wikidata",
    ),
    PluginInfo(
        id = "chatgpt",
        name = "ChatGPT",
        resolverId = "chatgpt",
        bgColor = Color(0xFF10A37F),
        category = PluginCategory.META_SERVICE,
        capabilities = listOf("AI DJ", "Chat"),
        description = "Generate playlists and chat with AI DJ using ChatGPT.",
    ),
    PluginInfo(
        id = "claude",
        name = "Claude",
        resolverId = "claude",
        bgColor = Color(0xFFD97757),
        category = PluginCategory.META_SERVICE,
        capabilities = listOf("AI DJ", "Chat"),
        description = "Anthropic's Claude — thoughtful and capable AI assistant.",
    ),
    PluginInfo(
        id = "gemini",
        name = "Google Gemini",
        resolverId = "gemini",
        bgColor = Color(0xFF4285F4),
        category = PluginCategory.META_SERVICE,
        capabilities = listOf("AI DJ", "Chat"),
        description = "Generate playlists and chat with AI DJ using Google Gemini.",
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
    val hasPreferredSpotifyDevice by viewModel.hasPreferredSpotifyDevice.collectAsStateWithLifecycle()
    val lastFmConnected by viewModel.lastFmConnected.collectAsStateWithLifecycle()
    val listenBrainzConnected by viewModel.listenBrainzConnected.collectAsStateWithLifecycle()
    val libreFmConnected by viewModel.libreFmConnected.collectAsStateWithLifecycle()
    val soundCloudConnected by viewModel.soundCloudConnected.collectAsStateWithLifecycle()
    val soundCloudCredentialsSaved by viewModel.soundCloudCredentialsSaved.collectAsStateWithLifecycle()
    val appleMusicDeveloperToken by viewModel.appleMusicDeveloperToken.collectAsStateWithLifecycle()
    val appleMusicConfigured by viewModel.appleMusicConfigured.collectAsStateWithLifecycle()
    val appleMusicAuthorized by viewModel.appleMusicAuthorized.collectAsStateWithLifecycle()
    val appleMusicConnecting by viewModel.appleMusicConnecting.collectAsStateWithLifecycle()
    val persistQueue by viewModel.persistQueue.collectAsStateWithLifecycle()
    val libreFmAuthError by viewModel.libreFmAuthError.collectAsStateWithLifecycle()
    val listenBrainzAuthError by viewModel.listenBrainzAuthError.collectAsStateWithLifecycle()
    val disabledMetaProviders by viewModel.disabledMetaProviders.collectAsStateWithLifecycle()
    val resolverOrder by viewModel.resolverOrder.collectAsStateWithLifecycle()
    val chatGptConnected by viewModel.chatGptConnected.collectAsStateWithLifecycle()
    val claudeConnected by viewModel.claudeConnected.collectAsStateWithLifecycle()
    val geminiConnected by viewModel.geminiConnected.collectAsStateWithLifecycle()

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
                    hasPreferredSpotifyDevice = hasPreferredSpotifyDevice,
                    onClearPreferredSpotifyDevice = { viewModel.clearPreferredSpotifyDevice() },
                    lastFmConnected = lastFmConnected,
                    listenBrainzConnected = listenBrainzConnected,
                    libreFmConnected = libreFmConnected,
                    soundCloudConnected = soundCloudConnected,
                    resolverOrder = resolverOrder,
                    onResolverOrderChanged = { viewModel.setResolverOrder(it) },
                    onSpotifyToggle = {
                        if (spotifyConnected) viewModel.disconnectSpotify()
                        else viewModel.connectSpotify(BuildConfig.SPOTIFY_CLIENT_ID)
                    },
                    onLastFmToggle = {
                        if (lastFmConnected) viewModel.disconnectLastFm()
                        else viewModel.connectLastFm(BuildConfig.LASTFM_API_KEY)
                    },
                    soundCloudCredentialsSaved = soundCloudCredentialsSaved,
                    onSoundCloudSaveCredentials = { id, secret -> viewModel.saveSoundCloudCredentials(id, secret) },
                    onSoundCloudConnect = { viewModel.connectSoundCloud() },
                    onSoundCloudDisconnect = { viewModel.disconnectSoundCloud() },
                    onSoundCloudClearCredentials = { viewModel.clearSoundCloudCredentials() },
                    appleMusicHasToken = appleMusicDeveloperToken != null,
                    appleMusicConfigured = appleMusicConfigured,
                    appleMusicAuthorized = appleMusicAuthorized,
                    appleMusicConnecting = appleMusicConnecting,
                    onAppleMusicSaveToken = { viewModel.setAppleMusicDeveloperToken(it) },
                    onAppleMusicSaveStorefront = { viewModel.setAppleMusicStorefront(it) },
                    onAppleMusicAuthorize = { viewModel.connectAppleMusic() },
                    onAppleMusicDisconnect = { viewModel.disconnectAppleMusic() },
                    onListenBrainzTokenSubmit = { viewModel.setListenBrainzToken(it) },
                    onListenBrainzDisconnect = { viewModel.disconnectListenBrainz() },
                    listenBrainzAuthError = listenBrainzAuthError,
                    onLibreFmConnect = { user, pass -> viewModel.connectLibreFm(user, pass) },
                    onLibreFmDisconnect = { viewModel.disconnectLibreFm() },
                    libreFmAuthError = libreFmAuthError,
                    scrobbling = scrobbling,
                    onScrobblingChanged = { viewModel.setScrobbling(it) },
                    disabledMetaProviders = disabledMetaProviders,
                    onMetaProviderToggle = { name, enabled ->
                        viewModel.setMetaProviderEnabled(name, enabled)
                    },
                    chatGptConnected = chatGptConnected,
                    claudeConnected = claudeConnected,
                    geminiConnected = geminiConnected,
                    onSaveAiConfig = { providerId, apiKey, model ->
                        viewModel.saveAiProviderConfig(providerId, apiKey, model)
                    },
                    onSaveAiModel = { providerId, model ->
                        viewModel.saveAiModel(providerId, model)
                    },
                    getAiModel = { viewModel.getAiModel(it) },
                    onClearAiProvider = { viewModel.clearAiProvider(it) },
                )
                1 -> GeneralTab(
                    themeMode = themeMode,
                    onThemeModeChanged = { viewModel.setThemeMode(it) },
                    persistQueue = persistQueue,
                    onPersistQueueChanged = { viewModel.setPersistQueue(it) },
                    spotifyConnected = spotifyConnected,
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
    hasPreferredSpotifyDevice: Boolean = false,
    onClearPreferredSpotifyDevice: () -> Unit = {},
    lastFmConnected: Boolean,
    listenBrainzConnected: Boolean,
    libreFmConnected: Boolean,
    soundCloudConnected: Boolean,
    onSpotifyToggle: () -> Unit,
    onLastFmToggle: () -> Unit,
    resolverOrder: List<String> = emptyList(),
    onResolverOrderChanged: (List<String>) -> Unit = {},
    soundCloudCredentialsSaved: Boolean = false,
    onSoundCloudSaveCredentials: (String, String) -> Unit = { _, _ -> },
    onSoundCloudConnect: () -> Unit = {},
    onSoundCloudDisconnect: () -> Unit = {},
    onSoundCloudClearCredentials: () -> Unit = {},
    appleMusicHasToken: Boolean = false,
    appleMusicConfigured: Boolean = false,
    appleMusicAuthorized: Boolean = false,
    appleMusicConnecting: Boolean = false,
    onAppleMusicSaveToken: (String) -> Unit = {},
    onAppleMusicSaveStorefront: (String) -> Unit = {},
    onAppleMusicAuthorize: () -> Unit = {},
    onAppleMusicDisconnect: () -> Unit = {},
    onListenBrainzTokenSubmit: (String) -> Unit,
    onListenBrainzDisconnect: () -> Unit,
    listenBrainzAuthError: String?,
    onLibreFmConnect: (String, String) -> Unit,
    onLibreFmDisconnect: () -> Unit,
    libreFmAuthError: String?,
    scrobbling: Boolean,
    onScrobblingChanged: (Boolean) -> Unit,
    disabledMetaProviders: Set<String> = emptySet(),
    onMetaProviderToggle: (String, Boolean) -> Unit = { _, _ -> },
    chatGptConnected: Boolean = false,
    claudeConnected: Boolean = false,
    geminiConnected: Boolean = false,
    onSaveAiConfig: (String, String, String) -> Unit = { _, _, _ -> },
    onSaveAiModel: (String, String) -> Unit = { _, _ -> },
    getAiModel: (String) -> StateFlow<String> = { MutableStateFlow("") },
    onClearAiProvider: (String) -> Unit = {},
) {
    var selectedPlugin by remember { mutableStateOf<PluginInfo?>(null) }

    val resolverPlugins = builtInPlugins.filter { it.category == PluginCategory.RESOLVER }
    val metaServices = builtInPlugins.filter { it.category == PluginCategory.META_SERVICE }

    // Lookup can be by id ("local-files") or resolverId ("localfiles")
    fun findPlugin(key: String): PluginInfo? =
        builtInPlugins.find { it.id == key || it.resolverId == key }

    fun isConnected(key: String): Boolean {
        val plugin = findPlugin(key)
        val id = plugin?.id ?: key
        return when (id) {
            "spotify" -> spotifyConnected
            "soundcloud" -> soundCloudConnected
            "applemusic" -> appleMusicConfigured && appleMusicAuthorized
            "lastfm" -> lastFmConnected
            "listenbrainz" -> listenBrainzConnected
            "librefm" -> libreFmConnected
            "local-files" -> true // always available
            // Discogs and Wikipedia are always available (no auth) — enabled by default
            "discogs" -> id !in disabledMetaProviders
            "wikipedia" -> id !in disabledMetaProviders
            "chatgpt" -> chatGptConnected
            "claude" -> claudeConnected
            "gemini" -> geminiConnected
            else -> false
        }
    }

    // Split resolvers into enabled (connected) and disabled (not connected).
    // Enabled resolvers are shown in drag-to-reorder with priority numbers.
    // Disabled resolvers are shown grayed out below.
    // resolverOrder uses resolverId values (e.g. "localfiles", "spotify")
    val enabledResolverIds = resolverOrder.filter { id ->
        val plugin = findPlugin(id)
        plugin != null && plugin.category == PluginCategory.RESOLVER && isConnected(id)
    }
    val disabledResolverPlugins = resolverPlugins.filter { !isConnected(it.resolverId) }

    // Mutable state for drag-to-reorder (synced from persisted order)
    val orderedEnabled = remember(enabledResolverIds) {
        mutableStateListOf(*enabledResolverIds.toTypedArray())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Content Resolvers section — enabled (draggable)
        item { SectionHeader("Content Resolvers") }
        if (orderedEnabled.isNotEmpty()) {
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
                    resolverOrder = orderedEnabled,
                    findPlugin = ::findPlugin,
                    isConnected = ::isConnected,
                    onReorder = { from, to ->
                        val item = orderedEnabled.removeAt(from)
                        orderedEnabled.add(to, item)
                        // Persist: enabled resolvers in new order + disabled resolvers appended
                        val newOrder = orderedEnabled.toList() +
                            resolverOrder.filter { it !in orderedEnabled }
                        onResolverOrderChanged(newOrder)
                    },
                    onPluginClick = { id -> findPlugin(id)?.let { selectedPlugin = it } },
                )
            }
        }

        // Disabled resolvers — grayed out, not draggable
        if (disabledResolverPlugins.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Not connected",
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
                    disabledResolverPlugins.forEach { plugin ->
                        PluginTile(
                            plugin = plugin,
                            isConnected = false,
                            priorityNumber = null,
                            onClick = { selectedPlugin = plugin },
                            modifier = Modifier.weight(1f),
                            grayed = true,
                        )
                    }
                    // Fill remaining space
                    repeat(3 - disabledResolverPlugins.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
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
        // Meta Services tiles: 3 per row
        val rows = metaServices.chunked(3)
        rows.forEach { rowPlugins ->
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = if (rowPlugins !== rows.last()) 12.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowPlugins.forEach { plugin ->
                        val connected = isConnected(plugin.id)
                        PluginTile(
                            plugin = plugin,
                            isConnected = connected,
                            priorityNumber = null,
                            onClick = { selectedPlugin = plugin },
                            modifier = Modifier.weight(1f),
                            grayed = !connected,
                        )
                    }
                    // Fill remaining space in last row
                    repeat(3 - rowPlugins.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
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
                    "soundcloud" -> if (soundCloudConnected) onSoundCloudDisconnect() else onSoundCloudConnect()
                    "applemusic" -> if (appleMusicConfigured && appleMusicAuthorized) onAppleMusicDisconnect() else onAppleMusicAuthorize()
                    "lastfm" -> onLastFmToggle()
                    "discogs" -> onMetaProviderToggle("discogs", !isConnected("discogs"))
                    "wikipedia" -> onMetaProviderToggle("wikipedia", !isConnected("wikipedia"))
                }
            },
            scrobbling = scrobbling,
            onScrobblingChanged = onScrobblingChanged,
            soundCloudCredentialsSaved = soundCloudCredentialsSaved,
            onSoundCloudSaveCredentials = onSoundCloudSaveCredentials,
            onSoundCloudConnect = onSoundCloudConnect,
            onSoundCloudDisconnect = onSoundCloudDisconnect,
            onSoundCloudClearCredentials = onSoundCloudClearCredentials,
            appleMusicHasToken = appleMusicHasToken,
            appleMusicConfigured = appleMusicConfigured,
            appleMusicAuthorized = appleMusicAuthorized,
            appleMusicConnecting = appleMusicConnecting,
            onAppleMusicSaveToken = onAppleMusicSaveToken,
            onAppleMusicSaveStorefront = onAppleMusicSaveStorefront,
            onAppleMusicAuthorize = onAppleMusicAuthorize,
            onAppleMusicDisconnect = onAppleMusicDisconnect,
            onListenBrainzTokenSubmit = onListenBrainzTokenSubmit,
            onListenBrainzDisconnect = onListenBrainzDisconnect,
            listenBrainzAuthError = listenBrainzAuthError,
            onLibreFmConnect = onLibreFmConnect,
            onLibreFmDisconnect = onLibreFmDisconnect,
            libreFmAuthError = libreFmAuthError,
            onSaveAiConfig = onSaveAiConfig,
            onSaveAiModel = onSaveAiModel,
            getAiModel = getAiModel,
            onClearAiProvider = onClearAiProvider,
            hasPreferredSpotifyDevice = hasPreferredSpotifyDevice,
            onClearPreferredSpotifyDevice = onClearPreferredSpotifyDevice,
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
    val dragHaptics = rememberDragHaptics()

    // Calculate tile width based on available space
    // Each tile is ~1/3 of the row width with spacing
    val tileWidthDp = 100.dp // approximate, will use weight in practice

    // Compute drop target index during drag
    val dropTargetIndex = if (draggingIndex >= 0) {
        val tileWidthApprox = with(density) { 100.dp.toPx() + 12.dp.toPx() }
        val shift = (dragOffsetX / tileWidthApprox).roundToInt()
        (draggingIndex + shift).coerceIn(0, resolverOrder.size - 1)
    } else -1

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        resolverOrder.forEachIndexed { index, pluginId ->
            val plugin = findPlugin(pluginId) ?: return@forEachIndexed
            val isDragging = draggingIndex == index
            val isDropTarget = dropTargetIndex == index && !isDragging
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
                        } else if (isDropTarget) {
                            Modifier.border(
                                width = 2.dp,
                                color = Color(0xFF7C3AED),
                                shape = RoundedCornerShape(16.dp),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .pointerInput(index, resolverOrder.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffsetX = 0f
                                dragHaptics.onDragStart()
                            },
                            onDragEnd = {
                                // Calculate target index based on drag offset
                                val tileWidth = size.width.toFloat() + with(density) { 12.dp.toPx() }
                                val indexShift = (dragOffsetX / tileWidth).roundToInt()
                                val targetIndex = (index + indexShift).coerceIn(0, resolverOrder.size - 1)
                                if (targetIndex != index) {
                                    onReorder(index, targetIndex)
                                    dragHaptics.onDragMove()
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
    grayed: Boolean = false,
) {
    val tileAlpha = if (grayed) 0.35f else 1f

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(plugin.bgColor.copy(alpha = tileAlpha))
                .hapticClickable(onClick = onClick),
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
            color = if (grayed) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
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
    soundCloudCredentialsSaved: Boolean = false,
    onSoundCloudSaveCredentials: (String, String) -> Unit = { _, _ -> },
    onSoundCloudConnect: () -> Unit = {},
    onSoundCloudDisconnect: () -> Unit = {},
    onSoundCloudClearCredentials: () -> Unit = {},
    appleMusicHasToken: Boolean = false,
    appleMusicConfigured: Boolean = false,
    appleMusicAuthorized: Boolean = false,
    appleMusicConnecting: Boolean = false,
    onAppleMusicSaveToken: (String) -> Unit = {},
    onAppleMusicSaveStorefront: (String) -> Unit = {},
    onAppleMusicAuthorize: () -> Unit = {},
    onAppleMusicDisconnect: () -> Unit = {},
    onListenBrainzTokenSubmit: (String) -> Unit = {},
    onListenBrainzDisconnect: () -> Unit = {},
    listenBrainzAuthError: String? = null,
    onLibreFmConnect: (String, String) -> Unit = { _, _ -> },
    onLibreFmDisconnect: () -> Unit = {},
    libreFmAuthError: String? = null,
    onSaveAiConfig: (String, String, String) -> Unit = { _, _, _ -> },
    onSaveAiModel: (String, String) -> Unit = { _, _ -> },
    getAiModel: (String) -> StateFlow<String> = { MutableStateFlow("") },
    onClearAiProvider: (String) -> Unit = {},
    hasPreferredSpotifyDevice: Boolean = false,
    onClearPreferredSpotifyDevice: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
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
                "spotify" -> SpotifyConfig(
                    isConnected = isConnected,
                    onToggle = onToggleConnection,
                    hasPreferredDevice = hasPreferredSpotifyDevice,
                    onClearPreferredDevice = onClearPreferredSpotifyDevice,
                )

                "soundcloud" -> SoundCloudConfig(
                    isConnected = isConnected,
                    credentialsSaved = soundCloudCredentialsSaved,
                    onSaveCredentials = onSoundCloudSaveCredentials,
                    onConnect = onSoundCloudConnect,
                    onDisconnect = onSoundCloudDisconnect,
                    onClearCredentials = onSoundCloudClearCredentials,
                )
                "applemusic" -> AppleMusicConfig(
                    hasToken = appleMusicHasToken,
                    configured = appleMusicConfigured,
                    authorized = appleMusicAuthorized,
                    connecting = appleMusicConnecting,
                    onSaveToken = onAppleMusicSaveToken,
                    onSaveStorefront = onAppleMusicSaveStorefront,
                    onConnect = onAppleMusicAuthorize,
                    onDisconnect = onAppleMusicDisconnect,
                )
                "local-files" -> LocalFilesConfig()
                "lastfm" -> LastFmConfig(isConnected, onToggleConnection, scrobbling, onScrobblingChanged)
                "listenbrainz" -> ListenBrainzConfig(
                    isConnected = isConnected,
                    onTokenSubmit = onListenBrainzTokenSubmit,
                    onDisconnect = onListenBrainzDisconnect,
                    authError = listenBrainzAuthError,
                    scrobbling = scrobbling,
                    onScrobblingChanged = onScrobblingChanged,
                )
                "librefm" -> LibreFmConfig(
                    isConnected = isConnected,
                    onConnect = onLibreFmConnect,
                    onDisconnect = onLibreFmDisconnect,
                    authError = libreFmAuthError,
                    scrobbling = scrobbling,
                    onScrobblingChanged = onScrobblingChanged,
                )
                "discogs" -> ToggleableMetaConfig(
                    isEnabled = isConnected,
                    onToggle = onToggleConnection,
                    enabledText = "Discogs metadata provider is active",
                    disabledText = "Enable Discogs to get artist bios and images from the Discogs community database.",
                )
                "wikipedia" -> ToggleableMetaConfig(
                    isEnabled = isConnected,
                    onToggle = onToggleConnection,
                    enabledText = "Wikipedia metadata provider is active",
                    disabledText = "Enable Wikipedia to get encyclopedia-style artist bios and images via Wikidata.",
                )
                "chatgpt", "claude", "gemini" -> {
                    val currentModel by getAiModel(plugin.id).collectAsStateWithLifecycle()
                    AiProviderConfig(
                        providerId = plugin.id,
                        isConnected = isConnected,
                        onSaveConfig = onSaveAiConfig,
                        onSaveModel = onSaveAiModel,
                        onClear = onClearAiProvider,
                        currentModel = currentModel,
                    )
                }
            }
        }
    }
}

// ── Spotify Config ─────────────────────────────────────────────────

@Composable
private fun SpotifyConfig(
    isConnected: Boolean,
    onToggle: () -> Unit,
    hasPreferredDevice: Boolean = false,
    onClearPreferredDevice: () -> Unit = {},
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
                text = "Spotify Premium connected",
                style = MaterialTheme.typography.bodySmall,
                color = Success,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onToggle) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }

        // Preferred Device setting (matches desktop's "Preferred Device" row)
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Preferred Device",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (hasPreferredDevice) {
            Text(
                text = "A preferred device is saved. It will be used automatically for playback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onClearPreferredDevice) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }
        } else {
            Text(
                text = "No preferred device set. You\u2019ll be prompted to choose when multiple devices are available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

// ── SoundCloud Config ──────────────────────────────────────────────

@Composable
private fun SoundCloudConfig(
    isConnected: Boolean,
    credentialsSaved: Boolean,
    onSaveCredentials: (String, String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onClearCredentials: () -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    // Connection status
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
                text = "SoundCloud connected",
                style = MaterialTheme.typography.bodySmall,
                color = Success,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onDisconnect) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }
    } else {
        Text(
            text = if (credentialsSaved) "Credentials saved. Tap Connect to authorize with SoundCloud."
            else "Add your SoundCloud app credentials below, then connect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(
            onClick = onConnect,
            enabled = credentialsSaved,
        ) {
            Text(
                "Connect SoundCloud",
                color = if (credentialsSaved) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(12.dp))

    // Advanced — Client ID & Secret
    var advancedOpen by remember { mutableStateOf(!credentialsSaved && !isConnected) }
    TextButton(onClick = { advancedOpen = !advancedOpen }) {
        Text(
            text = if (advancedOpen) "▾ Advanced" else "▸ Advanced",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    if (advancedOpen) {
        val uriHandler = LocalUriHandler.current
        Text(
            text = "Create a SoundCloud app to get your Client ID and Secret:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "soundcloud.com/you/apps →",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.hapticClickable {
                uriHandler.openUri("https://soundcloud.com/you/apps")
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Set the Redirect URI to:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text = "parachord://auth/callback/soundcloud",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "SoundCloud only allows one redirect URI per app, so you'll need a separate app for Android.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        var clientId by remember { mutableStateOf("") }
        var clientSecret by remember { mutableStateOf("") }

        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Client ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = clientSecret,
            onValueChange = { clientSecret = it },
            label = { Text("Client Secret") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    if (clientId.isNotBlank() && clientSecret.isNotBlank()) {
                        onSaveCredentials(clientId.trim(), clientSecret.trim())
                        clientId = ""
                        clientSecret = ""
                    }
                },
                enabled = clientId.isNotBlank() && clientSecret.isNotBlank(),
            ) {
                Text("Save Credentials", color = MaterialTheme.colorScheme.primary)
            }
            if (credentialsSaved) {
                TextButton(onClick = onClearCredentials) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (credentialsSaved) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Success,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Client credentials saved",
                    style = MaterialTheme.typography.bodySmall,
                    color = Success,
                )
            }
        }
    }
}

// ── Apple Music Config ─────────────────────────────────────────────

@Composable
private fun AppleMusicConfig(
    hasToken: Boolean,
    configured: Boolean,
    authorized: Boolean,
    connecting: Boolean = false,
    onSaveToken: (String) -> Unit,
    onSaveStorefront: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val hasBuiltInToken = BuildConfig.APPLE_MUSIC_DEVELOPER_TOKEN.isNotBlank()

    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    // Connection status
    Text(
        text = "Connection",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (configured && authorized) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Apple Music connected",
                style = MaterialTheme.typography.bodySmall,
                color = Success,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onDisconnect) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }
    } else if (hasToken) {
        Text(
            text = "Sign in with your Apple ID to stream from Apple Music.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (connecting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Connecting…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            TextButton(onClick = onConnect) {
                Text("Connect Apple Music", color = MaterialTheme.colorScheme.primary)
            }
        }
    } else {
        Text(
            text = "Add your MusicKit developer token below, then connect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(12.dp))

    // Advanced — Developer Token & Storefront
    var advancedOpen by remember { mutableStateOf(!hasToken && !hasBuiltInToken) }
    TextButton(onClick = { advancedOpen = !advancedOpen }) {
        Text(
            text = if (advancedOpen) "▾ Advanced" else "▸ Advanced",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    if (advancedOpen) {
        if (hasBuiltInToken && !hasToken) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Success,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Using built-in developer token",
                    style = MaterialTheme.typography.bodySmall,
                    color = Success,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "You can override by entering a custom token below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "A MusicKit developer token is required to access the Apple Music catalog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        var tokenInput by remember { mutableStateOf("") }

        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text("Developer Token") },
            placeholder = { Text("eyJhbGciOiJFUzI1NiIs…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = {
                if (tokenInput.isNotBlank()) {
                    onSaveToken(tokenInput.trim())
                    tokenInput = ""
                }
            },
            enabled = tokenInput.isNotBlank(),
        ) {
            Text(
                "Save Token",
                color = if (tokenInput.isNotBlank()) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (hasToken) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Success,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Custom developer token saved",
                    style = MaterialTheme.typography.bodySmall,
                    color = Success,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Storefront
        var storefrontInput by remember { mutableStateOf("") }

        OutlinedTextField(
            value = storefrontInput,
            onValueChange = { storefrontInput = it },
            label = { Text("Storefront") },
            placeholder = { Text("us") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Country code for the Apple Music catalog (default: us)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = {
                val value = storefrontInput.trim().ifBlank { "us" }
                onSaveStorefront(value)
                storefrontInput = ""
            },
        ) {
            Text("Save Storefront", color = MaterialTheme.colorScheme.primary)
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

// ── ListenBrainz Config ────────────────────────────────────────────

@Composable
private fun ListenBrainzConfig(
    isConnected: Boolean,
    onTokenSubmit: (String) -> Unit,
    onDisconnect: () -> Unit,
    authError: String? = null,
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
                text = "ListenBrainz connected",
                style = MaterialTheme.typography.bodySmall,
                color = Success,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onDisconnect) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }
    } else {
        val uriHandler = LocalUriHandler.current
        val linkColor = MaterialTheme.colorScheme.primary
        val textColor = MaterialTheme.colorScheme.onSurfaceVariant
        val annotatedText = remember(linkColor, textColor) {
            buildAnnotatedString {
                withStyle(SpanStyle(color = textColor)) {
                    append("Enter your ListenBrainz user token to enable scrobbling. Find it at ")
                }
                pushLink(LinkAnnotation.Clickable("lb-settings") {
                    uriHandler.openUri("https://listenbrainz.org/settings/")
                })
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append("listenbrainz.org/settings/")
                }
                pop()
                withStyle(SpanStyle(color = textColor)) {
                    append(".")
                }
            }
        }
        Text(
            text = annotatedText,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(12.dp))

        var token by remember { mutableStateOf("") }
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("User Token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = {
                if (token.isNotBlank()) {
                    onTokenSubmit(token.trim())
                    token = ""
                }
            },
            enabled = token.isNotBlank(),
        ) {
            Text("Save Token", color = MaterialTheme.colorScheme.primary)
        }

        if (authError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = authError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
                text = "Send listening history to ListenBrainz",
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

// ── Libre.fm Config ───────────────────────────────────────────────

@Composable
private fun LibreFmConfig(
    isConnected: Boolean,
    onConnect: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    authError: String?,
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
                text = "Libre.fm connected",
                style = MaterialTheme.typography.bodySmall,
                color = Success,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onDisconnect) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }
    } else {
        Text(
            text = "Sign in with your Libre.fm account to enable scrobbling.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )

        if (authError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = authError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = {
                if (username.isNotBlank() && password.isNotBlank()) {
                    onConnect(username.trim(), password)
                }
            },
            enabled = username.isNotBlank() && password.isNotBlank(),
        ) {
            Text("Sign In", color = MaterialTheme.colorScheme.primary)
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
                text = "Send listening history to Libre.fm",
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

// ── Toggleable Meta Provider Config (Discogs, Wikipedia) ──────────

@Composable
private fun ToggleableMetaConfig(
    isEnabled: Boolean,
    onToggle: () -> Unit,
    enabledText: String,
    disabledText: String,
) {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Enabled",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (isEnabled) enabledText else disabledText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = { onToggle() },
        )
    }
}

// ── AI Provider Config ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiProviderConfig(
    providerId: String,
    isConnected: Boolean,
    onSaveConfig: (String, String, String) -> Unit,
    onSaveModel: (String, String) -> Unit = { _, _ -> },
    onClear: (String) -> Unit,
    currentModel: String = "",
) {
    val uriHandler = LocalUriHandler.current

    val (linkText, linkUrl) = when (providerId) {
        "chatgpt" -> "platform.openai.com \u2192" to "https://platform.openai.com/api-keys"
        "claude" -> "console.anthropic.com \u2192" to "https://console.anthropic.com/settings/keys"
        "gemini" -> "ai.google.dev \u2192" to "https://aistudio.google.com/apikey"
        else -> "" to ""
    }

    val models = when (providerId) {
        "chatgpt" -> listOf("gpt-4o-mini", "gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo")
        "claude" -> listOf("claude-sonnet-4-20250514", "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022")
        "gemini" -> listOf("gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-1.5-pro", "gemini-1.5-flash")
        else -> emptyList()
    }

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
                text = "Connected",
                style = MaterialTheme.typography.bodySmall,
                color = Success,
            )
        }
    } else {
        Text(
            text = "Add your API key below",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = linkText,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.hapticClickable { uriHandler.openUri(linkUrl) },
    )

    Spacer(modifier = Modifier.height(12.dp))

    var apiKey by remember { mutableStateOf("") }
    // Initialize to the currently saved model, falling back to the first available
    var selectedModel by remember {
        mutableStateOf(
            if (currentModel.isNotBlank() && currentModel in models) currentModel
            else models.firstOrNull() ?: ""
        )
    }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it },
        label = { Text("API Key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Model dropdown
    ExposedDropdownMenuBox(
        expanded = modelDropdownExpanded,
        onExpandedChange = { modelDropdownExpanded = it },
    ) {
        OutlinedTextField(
            value = selectedModel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = modelDropdownExpanded,
            onDismissRequest = { modelDropdownExpanded = false },
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        selectedModel = model
                        modelDropdownExpanded = false
                        // Auto-save model change (no need to re-enter API key)
                        if (isConnected) onSaveModel(providerId, model)
                    },
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = {
                if (apiKey.isNotBlank()) {
                    onSaveConfig(providerId, apiKey.trim(), selectedModel)
                    apiKey = ""
                }
            },
            enabled = apiKey.isNotBlank(),
        ) {
            Text("Save", color = MaterialTheme.colorScheme.primary)
        }
        if (isConnected) {
            TextButton(onClick = { onClear(providerId) }) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ── General Tab ────────────────────────────────────────────────────

@Composable
private fun GeneralTab(
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    persistQueue: Boolean,
    onPersistQueueChanged: (Boolean) -> Unit,
    spotifyConnected: Boolean,
) {
    val syncViewModel: SyncViewModel = hiltViewModel()
    val syncEnabled by syncViewModel.syncEnabled.collectAsStateWithLifecycle()
    val lastSyncAt by syncViewModel.lastSyncAt.collectAsStateWithLifecycle()
    var showSyncSetupSheet by remember { mutableStateOf(false) }
    var showStopSyncDialog by remember { mutableStateOf(false) }

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
        item { SectionHeader("Playback") }
        item {
            ListItem(
                headlineContent = { Text("Remember queue") },
                supportingContent = { Text("Restore your queue when the app restarts") },
                trailingContent = {
                    Switch(
                        checked = persistQueue,
                        onCheckedChange = onPersistQueueChanged,
                    )
                },
            )
        }

        // Spotify Sync section — only shown when Spotify is connected
        if (spotifyConnected) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { SectionHeader("Spotify Sync") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Sync enable/disable toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Sync Library",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    if (syncEnabled) "Syncing enabled" else "Syncing disabled",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = syncEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        showSyncSetupSheet = true
                                    } else {
                                        showStopSyncDialog = true
                                    }
                                },
                            )
                        }

                        // Last synced timestamp
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Last synced: ${formatRelativeTime(lastSyncAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (syncEnabled) {
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            // Sync Now button
                            Button(
                                onClick = {
                                    syncViewModel.syncNow()
                                    showSyncSetupSheet = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1DB954),
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text("Sync Now")
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Change sync settings button
                            OutlinedButton(
                                onClick = {
                                    syncViewModel.resetSetup()
                                    showSyncSetupSheet = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text("Change sync settings")
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Stop syncing button
                            TextButton(
                                onClick = { showStopSyncDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "Stop syncing",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Sync setup sheet
    if (showSyncSetupSheet) {
        SyncSetupSheet(
            onDismiss = { showSyncSetupSheet = false },
            viewModel = syncViewModel,
        )
    }

    // Stop syncing confirmation dialog
    if (showStopSyncDialog) {
        AlertDialog(
            onDismissRequest = { showStopSyncDialog = false },
            containerColor = ModalBg,
            titleContentColor = ModalTextActive,
            textContentColor = ModalTextPrimary,
            title = { Text("Stop Syncing") },
            text = { Text("Do you want to keep or remove the synced items from your Collection?") },
            confirmButton = {
                TextButton(onClick = {
                    syncViewModel.stopSyncing(removeItems = true)
                    showStopSyncDialog = false
                }) { Text("Remove Items", color = Color(0xFFEF4444)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    syncViewModel.stopSyncing(removeItems = false)
                    showStopSyncDialog = false
                }) { Text("Keep Items", color = ModalTextPrimary) }
            },
        )
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    if (timestamp == 0L) return "Never"
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000} minutes ago"
        diff < 86_400_000 -> "${diff / 3_600_000} hours ago"
        else -> "${diff / 86_400_000} days ago"
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
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}
