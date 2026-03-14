package com.parachord.android.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.parachord.android.auth.OAuthManager
import com.parachord.android.ui.components.ActionOverlay
import com.parachord.android.ui.components.CreatePlaylistDialog
import com.parachord.android.ui.components.DrawerContent
import com.parachord.android.ui.components.MiniPlayer
import com.parachord.android.ui.navigation.BottomNavItem
import com.parachord.android.ui.navigation.ParachordNavHost
import com.parachord.android.ui.navigation.Routes
import com.parachord.android.ui.theme.ParachordTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var oAuthManager: OAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOAuthIntent(intent)
        setContent {
            ParachordApp()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthIntent(intent)
    }

    private fun handleOAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "parachord" && uri.host == "auth") {
            CoroutineScope(Dispatchers.IO).launch {
                oAuthManager.handleRedirect(uri)
            }
        }
    }
}

@Composable
fun ParachordApp() {
    val mainViewModel: MainViewModel = hiltViewModel()
    val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle()
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    ParachordTheme(darkTheme = darkTheme) {
        ParachordAppContent(mainViewModel)
    }
}

@Composable
private fun ParachordAppContent(mainViewModel: MainViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val fullScreenRoutes = setOf(Routes.NOW_PLAYING, Routes.CHAT)
    val showBottomBar = currentDestination?.route !in fullScreenRoutes

    val playbackState by mainViewModel.playbackState.collectAsStateWithLifecycle()
    val currentTrack = playbackState.currentTrack
    val isCurrentTrackFavorited by mainViewModel.isCurrentTrackFavorited.collectAsStateWithLifecycle()
    val friends by mainViewModel.friends.collectAsStateWithLifecycle()

    // Observe toast events from ViewModel (listen-along notifications, etc.)
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        mainViewModel.toastEvents.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    var showActionOverlay by rememberSaveable { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onCreate = { name ->
                mainViewModel.createPlaylist(name)
                showCreatePlaylistDialog = false
                // Navigate to playlists to see the new playlist
                navController.navigate(Routes.PLAYLISTS) {
                    launchSingleTop = true
                }
            },
        )
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val onOpenDrawer: () -> Unit = {
        if (currentDestination?.route !in fullScreenRoutes) {
            scope.launch { drawerState.open() }
        }
    }
    val onOpenChat: () -> Unit = {
        if (currentDestination?.route !in fullScreenRoutes) {
            navController.navigate(Routes.CHAT) { launchSingleTop = true }
        }
    }

    // NestedScrollConnection: catches HorizontalPager boundary overscroll on tab screens
    val overscrollConnection = remember(onOpenDrawer, onOpenChat) {
        object : NestedScrollConnection {
            private var accumulatedX = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Reset accumulator at start of new gesture direction
                if (available.x != 0f && (available.x > 0f) != (accumulatedX > 0f)) {
                    accumulatedX = 0f
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput) {
                    accumulatedX += available.x
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val accumulated = accumulatedX
                accumulatedX = 0f
                if (accumulated > 60f || available.x > 400f) {
                    onOpenDrawer()
                } else if (accumulated < -60f || available.x < -400f) {
                    onOpenChat()
                }
                return Velocity.Zero
            }
        }
    }

    // Draggable state for non-tab screens (dispatches horizontal nested scroll)
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 80.dp.toPx() }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    val draggableState = rememberDraggableState { delta ->
        dragAccumulator += delta
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    DrawerContent(
                        currentRoute = currentDestination?.route,
                        friends = friends,
                        onItemClick = { route ->
                            scope.launch { drawerState.close() }
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        },
                        onFriendClick = { friendId ->
                            scope.launch { drawerState.close() }
                            navController.navigate(Routes.friendDetail(friendId)) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        },
                        onListenAlong = { friend ->
                            scope.launch { drawerState.close() }
                            mainViewModel.startListenAlong(friend)
                        },
                        onUnpinFriend = { friendId ->
                            mainViewModel.unpinFriend(friendId)
                        },
                    )
                }
            },
        ) {
            Scaffold(
                bottomBar = {
                    if (showBottomBar) {
                        Column {
                            if (currentTrack != null) {
                                val progress = if (playbackState.duration > 0) {
                                    playbackState.position.toFloat() / playbackState.duration.toFloat()
                                } else 0f
                                MiniPlayer(
                                    trackTitle = currentTrack.title,
                                    artistName = currentTrack.artist,
                                    artworkUrl = currentTrack.artworkUrl,
                                    isPlaying = playbackState.isPlaying,
                                    isFavorited = isCurrentTrackFavorited,
                                    progress = progress,
                                    onPlayPause = { mainViewModel.togglePlayPause() },
                                    onSkipNext = { mainViewModel.skipNext() },
                                    onToggleFavorite = { mainViewModel.toggleCurrentTrackFavorite() },
                                    onClick = {
                                        navController.navigate(Routes.NOW_PLAYING) {
                                            launchSingleTop = true
                                        }
                                    },
                                )
                            }
                            // Subtle top border
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                            val navBarBg = if (isSystemInDarkTheme()) Color(0xFF262626) else Color(0xFF1F2937)
                            val selectedTabBg = Color.White.copy(alpha = 0.12f)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(navBarBg)
                                    .navigationBarsPadding()
                                    .height(56.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BottomNavItem.entries.forEach { item ->
                                    val selected = item.route != null &&
                                        currentDestination?.hierarchy?.any { it.route == item.route } == true

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .background(if (selected) selectedTabBg else Color.Transparent)
                                            .clickable {
                                                if (item.isAction) {
                                                    showActionOverlay = !showActionOverlay
                                                } else {
                                                    item.route?.let { route ->
                                                        navController.navigate(route) {
                                                            popUpTo(navController.graph.findStartDestination().id) {
                                                                inclusive = false
                                                            }
                                                            launchSingleTop = true
                                                        }
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (item.isAction) {
                                            Icon(
                                                item.icon,
                                                contentDescription = item.label,
                                                tint = Color.White.copy(alpha = 0.7f),
                                                modifier = Modifier.size(28.dp),
                                            )
                                        } else {
                                            Icon(
                                                item.icon,
                                                contentDescription = item.label,
                                                tint = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
                                                modifier = Modifier.size(26.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .padding(innerPadding)
                        .nestedScroll(overscrollConnection)
                        .draggable(
                            state = draggableState,
                            orientation = Orientation.Horizontal,
                            onDragStarted = { dragAccumulator = 0f },
                            onDragStopped = {
                                if (dragAccumulator > swipeThresholdPx) onOpenDrawer()
                                else if (dragAccumulator < -swipeThresholdPx) onOpenChat()
                                dragAccumulator = 0f
                            },
                        ),
                ) {
                    ParachordNavHost(
                        navController = navController,
                        onOpenDrawer = onOpenDrawer,
                        onOpenChat = onOpenChat,
                        onListenAlong = { friend ->
                            mainViewModel.startListenAlong(friend)
                        },
                    )
                }
            }
        }

        // Full-screen action overlay on top of everything
        ActionOverlay(
            visible = showActionOverlay,
            onDismiss = { showActionOverlay = false },
            onCreatePlaylist = {
                showActionOverlay = false
                showCreatePlaylistDialog = true
            },
            onImportPlaylist = { showActionOverlay = false },
            onAddFriend = {
                showActionOverlay = false
                navController.navigate(Routes.FRIENDS) {
                    launchSingleTop = true
                }
            },
            onChatWithShuffleupagus = {
                showActionOverlay = false
                navController.navigate(Routes.CHAT) {
                    launchSingleTop = true
                }
            },
        )
    }
}
