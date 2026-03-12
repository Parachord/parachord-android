package com.parachord.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.parachord.android.auth.OAuthManager
import com.parachord.android.ui.components.ActionOverlay
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
            ParachordTheme {
                ParachordApp()
            }
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
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val fullScreenRoutes = setOf(Routes.NOW_PLAYING)
    val showBottomBar = currentDestination?.route !in fullScreenRoutes

    val mainViewModel: MainViewModel = hiltViewModel()
    val playbackState by mainViewModel.playbackState.collectAsStateWithLifecycle()
    val currentTrack = playbackState.currentTrack

    var showActionOverlay by rememberSaveable { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    DrawerContent(
                        currentRoute = currentDestination?.route,
                        onItemClick = { route ->
                            scope.launch { drawerState.close() }
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
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
                                    progress = progress,
                                    onPlayPause = { mainViewModel.togglePlayPause() },
                                    onSkipNext = { mainViewModel.skipNext() },
                                    onClick = { navController.navigate(Routes.NOW_PLAYING) },
                                )
                            }
                            // Subtle top border
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                            NavigationBar {
                                BottomNavItem.entries.forEach { item ->
                                    val selected = item.route != null &&
                                        currentDestination?.hierarchy?.any { it.route == item.route } == true

                                    NavigationBarItem(
                                        icon = {
                                            if (item.isAction) {
                                                // Circular purple background for the "+" action
                                                Box(
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.primary),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        item.icon,
                                                        contentDescription = item.label,
                                                        tint = Color.White,
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                }
                                            } else {
                                                Icon(item.icon, contentDescription = item.label)
                                            }
                                        },
                                        label = if (item.isAction) null else {
                                            {
                                                Text(
                                                    item.label,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        },
                                        selected = selected,
                                        onClick = {
                                            if (item.isAction) {
                                                showActionOverlay = !showActionOverlay
                                            } else {
                                                item.route?.let { route ->
                                                    navController.navigate(route) {
                                                        popUpTo(navController.graph.findStartDestination().id) {
                                                            saveState = true
                                                        }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }
                                            }
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                },
            ) { innerPadding ->
                ParachordNavHost(
                    navController = navController,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }

        // Full-screen action overlay on top of everything
        ActionOverlay(
            visible = showActionOverlay,
            onDismiss = { showActionOverlay = false },
            onCreatePlaylist = { showActionOverlay = false },
            onImportPlaylist = { showActionOverlay = false },
            onAddFriend = { showActionOverlay = false },
        )
    }
}
