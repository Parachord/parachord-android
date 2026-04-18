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
 * Chip shown on a hosted XSPF playlist (mirrors a remote XSPF URL and re-polls
 * every 5 min). Uses the brand purple from the desktop palette as a solid
 * background so the chip reads well overlaying playlist artwork as well as on
 * a plain surface. White foreground keeps WCAG contrast on both purple shades.
 */
@Composable
fun HostedBadge(modifier: Modifier = Modifier) {
    val accent = if (ParachordTheme.isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .background(
                color = accent,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CloudSync,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = "Hosted",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}
