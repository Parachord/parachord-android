package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.parachord.android.ui.theme.ParachordTheme

/**
 * Chip shown alongside a playlist's name when it's a hosted XSPF playlist
 * (mirrors a remote XSPF URL and re-polls every 5 min). Uses the brand
 * purple from the desktop palette so it sits next to the green Spotify chip
 * without clashing.
 */
@Composable
fun HostedBadge(modifier: Modifier = Modifier) {
    val accent = if (ParachordTheme.isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .background(
                color = accent.copy(alpha = 0.12f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CloudSync,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(10.dp),
        )
        Text(
            text = "Hosted",
            style = MaterialTheme.typography.labelSmall,
            color = accent,
        )
    }
}
