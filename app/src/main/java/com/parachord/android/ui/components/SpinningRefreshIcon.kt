package com.parachord.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

/**
 * Refresh icon button that spins continuously while [isLoading] is true.
 */
@Composable
fun SpinningRefreshIcon(
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val rotation = if (isLoading) {
        val infiniteTransition = rememberInfiniteTransition(label = "refresh_spin")
        val angle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000, easing = LinearEasing),
            ),
            label = "refresh_rotation",
        )
        angle
    } else {
        0f
    }

    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = if (isLoading) "Loading…" else "Refresh",
            modifier = Modifier
                .size(20.dp)
                .rotate(rotation),
            tint = tint,
        )
    }
}
