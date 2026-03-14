package com.parachord.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
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
    COLLECTION(Routes.COLLECTION_BASE, "Collection", Icons.Default.Inventory2),
    PLAYLISTS(Routes.PLAYLISTS, "Playlists", Icons.AutoMirrored.Filled.ViewList),
    ADD(null, "+", Icons.Default.Add, isAction = true),
}
