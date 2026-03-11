package com.parachord.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Parachord color palette — matches the desktop app's CSS design tokens.
 *
 * Desktop uses purple (#7c3aed light / #a78bfa dark) as the primary accent,
 * NOT blue. See CLAUDE.md for the full color reference.
 */

// Accent purple
private val PurpleLight = Color(0xFF7C3AED)   // --accent-primary (light)
private val PurpleDark = Color(0xFFA78BFA)     // --accent-primary (dark)
private val PurpleHoverLight = Color(0xFF6D28D9)
private val PurpleHoverDark = Color(0xFF8B5CF6)

// Semantic
private val Success = Color(0xFF10B981)
private val Warning = Color(0xFFF59E0B)
private val Error = Color(0xFFEF4444)

private val DarkColorScheme = darkColorScheme(
    primary = PurpleDark,
    onPrimary = Color.White,
    primaryContainer = PurpleHoverDark,
    secondary = Color(0xFF9CA3AF),             // --text-secondary (dark)
    tertiary = Color(0xFF6B7280),              // --text-tertiary (dark)
    background = Color(0xFF161616),            // --bg-primary (dark)
    onBackground = Color(0xFFF3F4F6),          // --text-primary (dark)
    surface = Color(0xFF1E1E1E),               // --bg-secondary (dark)
    onSurface = Color(0xFFF3F4F6),             // --text-primary (dark)
    surfaceVariant = Color(0xFF252525),         // --bg-elevated (dark)
    onSurfaceVariant = Color(0xFF9CA3AF),       // --text-secondary (dark)
    outline = Color(0xFF6B7280),               // --text-tertiary (dark)
    error = Error,
)

private val LightColorScheme = lightColorScheme(
    primary = PurpleLight,
    onPrimary = Color.White,
    primaryContainer = PurpleHoverLight,
    secondary = Color(0xFF6B7280),             // --text-secondary (light)
    tertiary = Color(0xFF9CA3AF),              // --text-tertiary (light)
    background = Color(0xFFFFFFFF),            // --bg-primary (light)
    onBackground = Color(0xFF111827),          // --text-primary (light)
    surface = Color(0xFFF9FAFB),               // --bg-secondary (light)
    onSurface = Color(0xFF111827),             // --text-primary (light)
    surfaceVariant = Color(0xFFF3F4F6),        // --bg-inset (light)
    onSurfaceVariant = Color(0xFF6B7280),       // --text-secondary (light)
    outline = Color(0xFFE5E7EB),               // --border-default (light)
    outlineVariant = Color(0xFFF3F4F6),        // --border-light (light)
    error = Error,
)

@Composable
fun ParachordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Disable dynamic color — use the Parachord brand palette
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
