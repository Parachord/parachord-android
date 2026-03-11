package com.parachord.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import java.net.URLDecoder
import java.net.URLEncoder

/** Top-level route definitions. */
object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val NOW_PLAYING = "now_playing"
    const val SETTINGS = "settings"
    const val ARTIST = "artist/{artistName}"
    const val ALBUM = "album/{albumTitle}/{artistName}"

    fun artist(name: String): String =
        "artist/${URLEncoder.encode(name, "UTF-8")}"

    fun album(albumTitle: String, artistName: String): String =
        "album/${URLEncoder.encode(albumTitle, "UTF-8")}/${URLEncoder.encode(artistName, "UTF-8")}"
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
            com.parachord.android.ui.screens.search.SearchScreen(
                onNavigateToArtist = { name -> navController.navigate(Routes.artist(name)) },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
            )
        }
        composable(Routes.NOW_PLAYING) {
            com.parachord.android.ui.screens.nowplaying.NowPlayingScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            com.parachord.android.ui.screens.settings.SettingsScreen()
        }
        composable(
            route = Routes.ARTIST,
            arguments = listOf(navArgument("artistName") { type = NavType.StringType }),
        ) {
            com.parachord.android.ui.screens.artist.ArtistScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onNavigateToArtist = { name -> navController.navigate(Routes.artist(name)) },
            )
        }
        composable(
            route = Routes.ALBUM,
            arguments = listOf(
                navArgument("albumTitle") { type = NavType.StringType },
                navArgument("artistName") { type = NavType.StringType },
            ),
        ) {
            com.parachord.android.ui.screens.album.AlbumScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
