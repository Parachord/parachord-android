package com.parachord.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.parachord.android.ui.components.MiniPlayer
import com.parachord.android.ui.navigation.BottomNavItem
import com.parachord.android.ui.navigation.ParachordNavHost
import com.parachord.android.ui.navigation.Routes
import com.parachord.android.ui.theme.ParachordTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ParachordTheme {
                ParachordApp()
            }
        }
    }
}

@Composable
fun ParachordApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val fullScreenRoutes = setOf(Routes.NOW_PLAYING, Routes.ARTIST)
    val showBottomBar = currentDestination?.route !in fullScreenRoutes

    val mainViewModel: MainViewModel = hiltViewModel()
    val playbackState by mainViewModel.playbackState.collectAsStateWithLifecycle()
    val currentTrack = playbackState.currentTrack

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Column {
                    if (currentTrack != null) {
                        MiniPlayer(
                            trackTitle = currentTrack.title,
                            artistName = currentTrack.artist,
                            isPlaying = playbackState.isPlaying,
                            onPlayPause = { mainViewModel.togglePlayPause() },
                            onClick = { navController.navigate(Routes.NOW_PLAYING) },
                        )
                    }
                    NavigationBar {
                        BottomNavItem.entries.forEach { item ->
                            NavigationBarItem(
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                                selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        ParachordNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
