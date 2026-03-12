package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Deterministic gradient placeholder matching the desktop's generateArtistPattern().
 * Given a name, produces a consistent gradient background with initials overlay.
 *
 * 15 distinct color palettes, 6 gradient pattern variants.
 */

private data class GradientPalette(
    val colors: List<Color>,
)

private val palettes = listOf(
    // Deep blue / red
    GradientPalette(listOf(Color(0xFF1A1A2E), Color(0xFF16213E), Color(0xFF0F3460))),
    // Ocean blues
    GradientPalette(listOf(Color(0xFF1E3A5F), Color(0xFF3D5A80), Color(0xFF98C1D9))),
    // Forest green
    GradientPalette(listOf(Color(0xFF1B4332), Color(0xFF2D6A4F), Color(0xFF40916C))),
    // Warm amber
    GradientPalette(listOf(Color(0xFF3E2723), Color(0xFF5D4037), Color(0xFF8D6E63))),
    // Plum/purple
    GradientPalette(listOf(Color(0xFF2D1B69), Color(0xFF4A2C82), Color(0xFF6C3FA0))),
    // Teal
    GradientPalette(listOf(Color(0xFF0D3B3E), Color(0xFF1A5C5E), Color(0xFF2E8B8B))),
    // Slate/charcoal
    GradientPalette(listOf(Color(0xFF1A1A2E), Color(0xFF2D2D44), Color(0xFF3F3F5C))),
    // Crimson
    GradientPalette(listOf(Color(0xFF2E0A1E), Color(0xFF5C1A3A), Color(0xFF8B2252))),
    // Olive
    GradientPalette(listOf(Color(0xFF2C3E1E), Color(0xFF4A5E30), Color(0xFF6B7F45))),
    // Midnight
    GradientPalette(listOf(Color(0xFF0A0E27), Color(0xFF141B41), Color(0xFF1E2A5A))),
    // Rust
    GradientPalette(listOf(Color(0xFF3E1E0E), Color(0xFF6B3420), Color(0xFF984A32))),
    // Sage
    GradientPalette(listOf(Color(0xFF283828), Color(0xFF3E5A3E), Color(0xFF5A7A5A))),
    // Steel blue
    GradientPalette(listOf(Color(0xFF1A2A3E), Color(0xFF2A3E5A), Color(0xFF3E5A7A))),
    // Bronze
    GradientPalette(listOf(Color(0xFF2E1E0E), Color(0xFF5A3E1E), Color(0xFF7A5A2E))),
    // Indigo night
    GradientPalette(listOf(Color(0xFF0E0A2E), Color(0xFF1E1A5A), Color(0xFF2E2A7A))),
)

/**
 * Hash a name into a deterministic integer for palette/pattern selection.
 */
private fun stableHash(name: String): Int {
    var hash = 0
    for (char in name) {
        hash = 31 * hash + char.code
    }
    return abs(hash)
}

/**
 * Extract initials from a name (max 2 characters).
 */
private fun getInitials(name: String): String {
    val words = name.trim().split("\\s+".toRegex())
    return when {
        words.size >= 2 -> "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
        words.size == 1 && words[0].length >= 2 -> words[0].take(2).uppercase()
        words.size == 1 -> words[0].take(1).uppercase()
        else -> "?"
    }
}

/**
 * Create a deterministic gradient brush from a name string.
 */
fun gradientForName(name: String): Brush {
    val hash = stableHash(name)
    val palette = palettes[hash % palettes.size]
    val patternIndex = (hash / palettes.size) % 6

    return when (patternIndex) {
        0 -> Brush.linearGradient(palette.colors, start = Offset.Zero, end = Offset(1000f, 1000f))
        1 -> Brush.radialGradient(palette.colors, center = Offset(500f, 500f), radius = 700f)
        2 -> Brush.linearGradient(palette.colors, start = Offset(0f, 1000f), end = Offset(1000f, 0f))
        3 -> Brush.radialGradient(palette.colors.reversed(), center = Offset(300f, 300f), radius = 800f)
        4 -> Brush.linearGradient(palette.colors, start = Offset(0f, 0f), end = Offset(0f, 1000f))
        else -> Brush.linearGradient(
            palette.colors + palette.colors.reversed(),
            start = Offset(0f, 500f),
            end = Offset(1000f, 500f),
        )
    }
}

/**
 * Gradient placeholder with centered initials overlay.
 * Matches the desktop's generateArtistPattern() behavior.
 */
@Composable
fun GradientPlaceholder(
    name: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 18.sp,
) {
    val brush = remember(name) { gradientForName(name) }
    val initials = remember(name) { getInitials(name) }

    Box(
        modifier = modifier.background(brush),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White.copy(alpha = 0.85f),
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize,
        )
    }
}
