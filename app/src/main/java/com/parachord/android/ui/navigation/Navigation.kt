package com.parachord.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

/** Top-level route definitions. */
object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val NOW_PLAYING = "now_playing"
    const val SETTINGS = "settings"
}

@Composable
fun ParachordNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        composable(Routes.HOME) {
            com.parachord.android.ui.screens.home.HomeScreen(
                onNavigateToNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
            )
        }
        composable(Routes.LIBRARY) {
            com.parachord.android.ui.screens.library.LibraryScreen()
        }
        composable(Routes.SEARCH) {
            com.parachord.android.ui.screens.search.SearchScreen()
        }
        composable(Routes.NOW_PLAYING) {
            com.parachord.android.ui.screens.nowplaying.NowPlayingScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            com.parachord.android.ui.screens.settings.SettingsScreen()
        }
    }
}
