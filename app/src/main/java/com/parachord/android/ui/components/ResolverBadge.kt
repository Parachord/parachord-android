package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.parachord.android.ui.theme.ResolverColors

/**
 * Small colored pill showing the resolver source (Spotify, YouTube, etc.).
 * Matches the desktop's resolver badge pattern with alpha-20 background
 * and bright text color.
 */
@Composable
fun ResolverBadge(
    resolver: String,
    modifier: Modifier = Modifier,
) {
    val colors = ResolverColors.forResolver(resolver) ?: return
    val shape = RoundedCornerShape(4.dp)

    Text(
        text = resolver.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = colors.text,
        modifier = modifier
            .clip(shape)
            .background(colors.background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
