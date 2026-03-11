package com.parachord.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, "Home", Icons.Default.Home),
    LIBRARY(Routes.LIBRARY, "Library", Icons.Default.LibraryMusic),
    SEARCH(Routes.SEARCH, "Search", Icons.Default.Search),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Default.Settings),
}
