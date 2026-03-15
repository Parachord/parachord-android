package com.parachord.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Parachord Material 3 color schemes — maps the desktop CSS tokens
 * into Material 3 color slots. Dynamic color is intentionally disabled.
 */
private val DarkColorScheme = darkColorScheme(
    primary = PurpleDark,
    onPrimary = Color.White,
    primaryContainer = PurpleHoverDark,
    secondary = TextSecondaryDark,
    tertiary = TextTertiaryDark,
    background = BgPrimaryDark,
    onBackground = TextPrimaryDark,
    surface = BgSecondaryDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = BgElevatedDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = TextTertiaryDark,
    outlineVariant = BorderDefaultDark,
    error = Error,
)

private val LightColorScheme = lightColorScheme(
    primary = PurpleLight,
    onPrimary = Color.White,
    primaryContainer = PurpleHoverLight,
    secondary = TextSecondaryLight,
    tertiary = TextTertiaryLight,
    background = BgPrimaryLight,
    onBackground = TextPrimaryLight,
    surface = BgSecondaryLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = BgInsetLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = BorderDefaultLight,
    outlineVariant = BorderLightLight,
    error = Error,
)

// ── Extended Colors ──────────────────────────────────────────────────
// Colors that don't map to Material 3 slots (alpha variants, resolver
// colors, always-dark player surfaces, card-specific styles).

@Immutable
data class ParachordExtendedColors(
    // Accent alpha variants
    val accentAlpha06: Color,
    val accentAlpha10: Color,
    val accentAlpha20: Color,
    val accentAlpha40: Color,
    val accentAlpha60: Color,
    val accentSurface: Color,

    // Card styling
    val cardBackground: Color,
    val cardBorder: Color,

    // Always-dark player surfaces (same in both themes)
    val playerSurface: Color = PlayerSurface,
    val playerSurfaceElevated: Color = PlayerSurfaceElevated,
    val playerTextPrimary: Color = PlayerTextPrimary,
    val playerTextSecondary: Color = PlayerTextSecondary,
)

private val LightExtendedColors = ParachordExtendedColors(
    accentAlpha06 = PurpleAlpha06,
    accentAlpha10 = PurpleAlpha10,
    accentAlpha20 = PurpleAlpha20,
    accentAlpha40 = PurpleAlpha40,
    accentAlpha60 = PurpleAlpha60,
    accentSurface = PurpleSurface,
    cardBackground = CardBgLight,
    cardBorder = CardBorderLight,
)

private val DarkExtendedColors = ParachordExtendedColors(
    accentAlpha06 = PurpleDarkAlpha10,
    accentAlpha10 = PurpleDarkAlpha10,
    accentAlpha20 = PurpleDarkAlpha20,
    accentAlpha40 = PurpleAlpha40,
    accentAlpha60 = PurpleAlpha60,
    accentSurface = Color(0xFF1E1E2E),
    cardBackground = CardBgDark,
    cardBorder = CardBorderDark,
)

val LocalParachordColors = staticCompositionLocalOf { LightExtendedColors }

/** User-configured resolver priority order, provided at the app root. */
val LocalResolverOrder = staticCompositionLocalOf { emptyList<String>() }

// ── Theme Accessor ───────────────────────────────────────────────────

object ParachordTheme {
    val colors: ParachordExtendedColors
        @Composable get() = LocalParachordColors.current
}

// ── Theme Composable ─────────────────────────────────────────────────

@Composable
fun ParachordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalParachordColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ParachordTypography,
            shapes = ParachordShapes,
            content = content,
        )
    }
}
