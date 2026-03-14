package com.parachord.android.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/** Top-level route definitions. */
object Routes {
    const val HOME = "home"
    const val COLLECTION = "collection"
    const val SEARCH = "search"
    const val PLAYLISTS = "playlists"
    const val NOW_PLAYING = "now_playing"
    const val SETTINGS = "settings"
    const val ARTIST = "artist/{artistName}"
    const val ALBUM = "album/{albumTitle}/{artistName}"

    const val CHAT = "chat"

    // Drawer destinations
    const val HISTORY = "history"
    const val FRESH_DROPS = "fresh_drops"
    const val RECOMMENDATIONS = "recommendations"
    const val POP_OF_THE_TOPS = "pop_of_the_tops"
    const val CRITICAL_DARLINGS = "critical_darlings"
    const val CONCERTS = "concerts"
    const val FRIENDS = "friends"
    const val FRIEND_DETAIL = "friend/{friendId}"

    fun artist(name: String): String =
        "artist/${Uri.encode(name)}"

    fun album(albumTitle: String, artistName: String): String =
        "album/${Uri.encode(albumTitle)}/${Uri.encode(artistName)}"

    fun friendDetail(friendId: String): String =
        "friend/${Uri.encode(friendId)}"
}

@Composable
fun ParachordNavHost(
    navController: NavHostController,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = {
            fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                initialOffsetX = { it / 4 },
                animationSpec = tween(300),
            )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(300))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(300),
            )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(300)) + slideOutHorizontally(
                targetOffsetX = { it / 4 },
                animationSpec = tween(300),
            )
        },
    ) {
        composable(Routes.HOME) {
            com.parachord.android.ui.screens.home.HomeScreen(
                onNavigateToNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                onOpenDrawer = onOpenDrawer,
            )
        }
        composable(Routes.COLLECTION) {
            com.parachord.android.ui.screens.library.CollectionScreen(
                onOpenDrawer = onOpenDrawer,
                onNavigateToFriend = { friendId ->
                    navController.navigate(Routes.friendDetail(friendId))
                },
                onNavigateToArtist = { name ->
                    navController.navigate(Routes.artist(name))
                },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
            )
        }
        composable(Routes.PLAYLISTS) {
            com.parachord.android.ui.screens.playlists.PlaylistsScreen(
                onOpenDrawer = onOpenDrawer,
            )
        }
        composable(Routes.SEARCH) {
            com.parachord.android.ui.screens.search.SearchScreen(
                onNavigateToArtist = { name -> navController.navigate(Routes.artist(name)) },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onOpenDrawer = onOpenDrawer,
            )
        }
        composable(
            Routes.NOW_PLAYING,
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(400),
                )
            },
            exitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(400),
                )
            },
            popEnterTransition = {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(400),
                )
            },
            popExitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(400),
                )
            },
        ) {
            com.parachord.android.ui.screens.nowplaying.NowPlayingScreen(
                onBack = { navController.popBackStack() },
                onNavigateToArtist = { name -> navController.navigate(Routes.artist(name)) },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
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

        composable(
            Routes.CHAT,
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(350),
                )
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(350),
                )
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it },
                    animationSpec = tween(350),
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it },
                    animationSpec = tween(350),
                )
            },
        ) {
            com.parachord.android.ui.screens.chat.ChatScreen(
                onBack = { navController.popBackStack() },
                onNavigateToArtist = { name -> navController.navigate(Routes.artist(name)) },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
            )
        }

        // ── Drawer Destinations ─────────────────────────────────────────
        composable(Routes.HISTORY) {
            com.parachord.android.ui.screens.history.HistoryScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onNavigateToArtist = { name ->
                    navController.navigate(Routes.artist(name))
                },
            )
        }
        composable(Routes.FRESH_DROPS) {
            com.parachord.android.ui.screens.discover.FreshDropsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onNavigateToArtist = { name ->
                    navController.navigate(Routes.artist(name))
                },
            )
        }
        composable(Routes.RECOMMENDATIONS) {
            com.parachord.android.ui.screens.discover.RecommendationsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToArtist = { name ->
                    navController.navigate(Routes.artist(name))
                },
            )
        }
        composable(Routes.POP_OF_THE_TOPS) {
            com.parachord.android.ui.screens.discover.PopOfTheTopsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CRITICAL_DARLINGS) {
            com.parachord.android.ui.screens.discover.CriticalDarlingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onNavigateToArtist = { name ->
                    navController.navigate(Routes.artist(name))
                },
            )
        }
        composable(Routes.CONCERTS) {
            com.parachord.android.ui.screens.discover.ConcertsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.FRIENDS) {
            com.parachord.android.ui.screens.friends.FriendsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToFriend = { friendId ->
                    navController.navigate(Routes.friendDetail(friendId))
                },
            )
        }
        composable(
            route = Routes.FRIEND_DETAIL,
            arguments = listOf(navArgument("friendId") { type = NavType.StringType }),
        ) {
            com.parachord.android.ui.screens.friends.FriendDetailScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAlbum = { albumTitle, artistName ->
                    navController.navigate(Routes.album(albumTitle, artistName))
                },
                onNavigateToArtist = { name ->
                    navController.navigate(Routes.artist(name))
                },
            )
        }
    }
}
