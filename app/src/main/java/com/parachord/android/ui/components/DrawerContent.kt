package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.ui.icons.parachordWordmark
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
    DrawerMenuItem(Routes.HISTORY, "History", Icons.Outlined.History, Color(0xFF06B6D4)),
)

private val discoverItems = listOf(
    DrawerMenuItem(Routes.FRESH_DROPS, "Fresh Drops", Icons.Outlined.WaterDrop, Color(0xFF10B981)),
    DrawerMenuItem(Routes.RECOMMENDATIONS, "Recommendations", Icons.Outlined.Star, Color(0xFFF59E0B)),
    DrawerMenuItem(Routes.POP_OF_THE_TOPS, "Pop of the Tops", Icons.Outlined.BarChart, Color(0xFFF97316)),
    DrawerMenuItem(Routes.CRITICAL_DARLINGS, "Critical Darlings", Icons.Outlined.EmojiEvents, Color(0xFFEF4444)),
    DrawerMenuItem(Routes.CONCERTS, "Concerts", Icons.Outlined.ConfirmationNumber, Color(0xFF14B8A6)),
)

private val FriendPurple = Color(0xFF7C3AED)
private val OnAirGreen = Color(0xFF22C55E)

private val settingsItem = DrawerMenuItem(
    Routes.SETTINGS, "Settings", Icons.Outlined.Settings, Color(0xFF9CA3AF),
)

@Composable
fun DrawerContent(
    currentRoute: String?,
    friends: List<FriendEntity> = emptyList(),
    onItemClick: (String) -> Unit,
    onFriendClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Wordmark color adapts to theme: dark text on light bg, light text on dark bg
    val wordmarkFill = MaterialTheme.colorScheme.onSurface
    val wordmarkIcon = parachordWordmark(wordmarkFill)

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 12.dp),
    ) {
        // ── Parachord wordmark at top ──
        Icon(
            imageVector = wordmarkIcon,
            contentDescription = "Parachord",
            tint = Color.Unspecified, // colors are baked into the vector (fill param + red accent)
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .height(35.dp),
        )

        // ── Scrollable content (everything except Settings) ──
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            // ── YOUR MUSIC Section ──
            DrawerSectionHeader("YOUR MUSIC")
            yourMusicItems.forEach { item ->
                DrawerNavItem(
                    item = item,
                    selected = currentRoute == item.route,
                    onClick = { onItemClick(item.route) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── DISCOVER Section ──
            DrawerSectionHeader("DISCOVER")
            discoverItems.forEach { item ->
                DrawerNavItem(
                    item = item,
                    selected = currentRoute == item.route,
                    onClick = { onItemClick(item.route) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── FRIENDS Section ──
            if (friends.isNotEmpty()) {
                DrawerSectionHeader("FRIENDS")
                friends.forEach { friend ->
                    DrawerFriendItem(
                        friend = friend,
                        selected = currentRoute == "friend/${friend.id}",
                        onClick = { onFriendClick(friend.id) },
                    )
                }
            }
        }

        // ── Settings pinned to bottom ──
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp,
        )
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            DrawerNavItem(
                item = settingsItem,
                selected = currentRoute == settingsItem.route,
                onClick = { onItemClick(settingsItem.route) },
            )
        }
    }
}

@Composable
private fun DrawerSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.88.sp, // ~0.08em at 11sp
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun DrawerNavItem(
    item: DrawerMenuItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val backgroundColor = if (selected) item.activeColor.copy(alpha = 0.10f) else Color.Transparent
    val textColor = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    }
    val fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = item.activeColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = item.label,
            fontSize = 14.sp,
            fontWeight = fontWeight,
            color = textColor,
        )
    }
}

@Composable
private fun DrawerFriendItem(
    friend: FriendEntity,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val backgroundColor = if (selected) FriendPurple.copy(alpha = 0.10f) else Color.Transparent
    val textColor = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    }
    val fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        // Avatar with on-air indicator
        Box {
            AlbumArtCard(
                artworkUrl = friend.avatarUrl,
                size = 24.dp,
                cornerRadius = 12.dp,
                placeholderName = friend.displayName,
            )
            if (friend.isOnAir) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(OnAirGreen),
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = friend.displayName,
            fontSize = 14.sp,
            fontWeight = fontWeight,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Show "now playing" snippet if on air
        if (friend.isOnAir && friend.cachedTrackName != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "♫",
                fontSize = 10.sp,
                color = OnAirGreen,
            )
        }
    }
}
