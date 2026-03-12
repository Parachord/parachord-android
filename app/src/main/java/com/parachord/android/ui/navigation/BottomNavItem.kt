package com.parachord.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector

enum class BottomNavItem(
    val route: String?,
    val label: String,
    val icon: ImageVector,
    val isAction: Boolean = false,
) {
    HOME(Routes.HOME, "Home", Icons.Default.Home),
    SEARCH(Routes.SEARCH, "Search", Icons.Default.Search),
    COLLECTION(Routes.COLLECTION, "Collection", Icons.Default.LibraryMusic),
    PLAYLISTS(Routes.PLAYLISTS, "Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    ADD(null, "+", Icons.Default.Add, isAction = true),
}
