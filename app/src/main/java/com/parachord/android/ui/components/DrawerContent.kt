package com.parachord.android.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parachord.android.ui.navigation.Routes

/**
 * Drawer menu item definition.
 */
data class DrawerMenuItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val activeColor: Color,
)

// ── Drawer Item Definitions ─────────────────────────────────────────

private val yourMusicItems = listOf(
    DrawerMenuItem(Routes.HISTORY, "History", Icons.Filled.History, Color(0xFF06B6D4)),
)

private val discoverItems = listOf(
    DrawerMenuItem(Routes.FRESH_DROPS, "Fresh Drops", Icons.Filled.WaterDrop, Color(0xFF10B981)),
    DrawerMenuItem(Routes.RECOMMENDATIONS, "Recommendations", Icons.Filled.Star, Color(0xFFF59E0B)),
    DrawerMenuItem(Routes.POP_OF_THE_TOPS, "Pop of the Tops", Icons.Filled.BarChart, Color(0xFFF97316)),
    DrawerMenuItem(Routes.CRITICAL_DARLINGS, "Critical Darlings", Icons.Filled.EmojiEvents, Color(0xFFEF4444)),
    DrawerMenuItem(Routes.CONCERTS, "Concerts", Icons.Filled.ConfirmationNumber, Color(0xFF14B8A6)),
)

@Composable
fun DrawerContent(
    currentRoute: String?,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.padding(horizontal = 12.dp)) {
        // ── Branded Header ──
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "PARACHORD",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── YOUR MUSIC Section ──
        item {
            DrawerSectionHeader("YOUR MUSIC")
        }
        yourMusicItems.forEach { item ->
            item {
                DrawerNavItem(
                    item = item,
                    selected = currentRoute == item.route,
                    onClick = { onItemClick(item.route) },
                )
            }
        }

        // ── DISCOVER Section ──
        item {
            Spacer(modifier = Modifier.height(8.dp))
            DrawerSectionHeader("DISCOVER")
        }
        discoverItems.forEach { item ->
            item {
                DrawerNavItem(
                    item = item,
                    selected = currentRoute == item.route,
                    onClick = { onItemClick(item.route) },
                )
            }
        }

        // ── Bottom Items (no section header) ──
        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
        item {
            DrawerNavItem(
                item = DrawerMenuItem(
                    Routes.FRIENDS,
                    "Friends",
                    Icons.Filled.People,
                    MaterialTheme.colorScheme.primary,
                ),
                selected = currentRoute == Routes.FRIENDS,
                onClick = { onItemClick(Routes.FRIENDS) },
            )
        }
        item {
            DrawerNavItem(
                item = DrawerMenuItem(
                    Routes.SETTINGS,
                    "Settings",
                    Icons.Filled.Settings,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                selected = currentRoute == Routes.SETTINGS,
                onClick = { onItemClick(Routes.SETTINGS) },
            )
        }
    }
}

@Composable
private fun DrawerSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall.copy(
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun DrawerNavItem(
    item: DrawerMenuItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        icon = {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = item.activeColor,
            )
        },
        label = {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = item.activeColor.copy(alpha = 0.12f),
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            selectedIconColor = item.activeColor,
            unselectedTextColor = MaterialTheme.colorScheme.onSurface,
            unselectedIconColor = item.activeColor,
        ),
    )
}
