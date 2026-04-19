package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Chip shown on a hosted XSPF playlist (mirrors a remote XSPF URL and re-polls
 * every 5 min). Matches desktop's hosted indicator (app.js L41224+):
 * `🌐 Hosted` in `text-blue-500` on a near-white pill — readable both as an
 * overlay on playlist artwork and inline on a plain surface.
 */
@Composable
fun HostedBadge(modifier: Modifier = Modifier) {
    // Tailwind blue-500 / blue-50 (matches desktop's bg-blue-50 + text-blue-500).
    // White-ish background works in both light and dark Compose themes since
    // the chip is always rendered on either artwork or a colored surface.
    val accent = Color(0xFF3B82F6)
    val bg = Color(0xFFEFF6FF)
    Text(
        text = "\uD83C\uDF10 Hosted", // 🌐 (U+1F310) globe-with-meridians
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = accent,
        modifier = modifier
            .background(color = bg, shape = RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
