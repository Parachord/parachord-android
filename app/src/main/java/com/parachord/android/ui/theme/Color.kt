package com.parachord.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Parachord color palette — matches the desktop app's CSS design tokens.
 *
 * Desktop uses purple (#7c3aed light / #a78bfa dark) as the primary accent.
 * See CLAUDE.md for the full color reference.
 */

// ── Accent Purple Scale ──────────────────────────────────────────────
val PurpleLight = Color(0xFF7C3AED)        // --accent-primary (light)
val PurpleDark = Color(0xFFA78BFA)         // --accent-primary (dark)
val PurpleHoverLight = Color(0xFF6D28D9)   // --accent-primary-hover (light)
val PurpleHoverDark = Color(0xFF8B5CF6)    // --accent-primary-hover (dark)
val PurpleSecondary = Color(0xFF8B5CF6)    // --accent-secondary
val PurpleTertiary = Color(0xFFA855F7)     // --accent-tertiary
val PurpleSoft = Color(0xFFA78BFA)         // --accent-soft
val PurpleSurface = Color(0xFFEDE9FE)      // --accent-surface

// Alpha variants (matching desktop rgba(124,58,237,x))
val PurpleAlpha06 = Color(0x0F7C3AED)      // 6% opacity
val PurpleAlpha10 = Color(0x1A7C3AED)      // 10% opacity
val PurpleAlpha20 = Color(0x337C3AED)      // 20% opacity
val PurpleAlpha40 = Color(0x667C3AED)      // 40% opacity
val PurpleAlpha60 = Color(0x997C3AED)      // 60% opacity

// Dark theme alpha variants
val PurpleDarkAlpha10 = Color(0x1AA78BFA)
val PurpleDarkAlpha20 = Color(0x33A78BFA)

// ── Semantic Colors ──────────────────────────────────────────────────
val Success = Color(0xFF10B981)
val Warning = Color(0xFFF59E0B)
val Error = Color(0xFFEF4444)

// ── Background Colors ────────────────────────────────────────────────
// Light theme
val BgPrimaryLight = Color(0xFFFFFFFF)
val BgSecondaryLight = Color(0xFFF9FAFB)
val BgElevatedLight = Color(0xFFFFFFFF)
val BgInsetLight = Color(0xFFF3F4F6)

// Dark theme
val BgPrimaryDark = Color(0xFF161616)
val BgSecondaryDark = Color(0xFF1E1E1E)
val BgElevatedDark = Color(0xFF252525)
val BgInsetDark = Color(0xFF1A1A1A)

// ── Text Colors ──────────────────────────────────────────────────────
val TextPrimaryLight = Color(0xFF111827)
val TextSecondaryLight = Color(0xFF6B7280)
val TextTertiaryLight = Color(0xFF9CA3AF)

val TextPrimaryDark = Color(0xFFF3F4F6)
val TextSecondaryDark = Color(0xFF9CA3AF)
val TextTertiaryDark = Color(0xFF6B7280)

// ── Border Colors ────────────────────────────────────────────────────
val BorderDefaultLight = Color(0xFFE5E7EB)
val BorderLightLight = Color(0xFFF3F4F6)
val BorderDefaultDark = Color(0xFF2E2E2E)
val BorderLightDark = Color(0xFF252525)

// ── Card Colors ──────────────────────────────────────────────────────
val CardBgLight = Color(0xFFFFFFFF)
val CardBorderLight = Color(0xFFE5E7EB)
val CardBgDark = Color(0xFF1E1E1E)
val CardBorderDark = Color(0xFF2A2A2A)

// ── Always-Dark Player Surface ───────────────────────────────────────
val PlayerSurface = Color(0xFF161616)
val PlayerSurfaceElevated = Color(0xFF1E1E1E)
val PlayerTextPrimary = Color(0xFFF3F4F6)
val PlayerTextSecondary = Color(0xFF9CA3AF)

// ── Resolver-Specific Colors ─────────────────────────────────────────
// Each resolver has a background (alpha-20) and text (bright) color pair.

data class ResolverColorPair(val background: Color, val text: Color)

object ResolverColors {
    val spotify = ResolverColorPair(
        background = Color(0x33059669),  // green-600/20
        text = Color(0xFF4ADE80),        // green-400
    )
    val youtube = ResolverColorPair(
        background = Color(0x33DC2626),  // red-600/20
        text = Color(0xFFF87171),        // red-400
    )
    val bandcamp = ResolverColorPair(
        background = Color(0x330891B2),  // cyan-600/20
        text = Color(0xFF22D3EE),        // cyan-400
    )
    val soundcloud = ResolverColorPair(
        background = Color(0x33EA580C),  // orange-600/20
        text = Color(0xFFFB923C),        // orange-400
    )
    val applemusic = ResolverColorPair(
        background = Color(0x33DC2626),  // red-600/20
        text = Color(0xFFF87171),        // red-400
    )
    val localfiles = ResolverColorPair(
        background = Color(0x334B5563),  // gray-600/20
        text = Color(0xFF9CA3AF),        // gray-400
    )

    fun forResolver(resolver: String?): ResolverColorPair? = when (resolver?.lowercase()) {
        "spotify" -> spotify
        "youtube" -> youtube
        "bandcamp" -> bandcamp
        "soundcloud" -> soundcloud
        "applemusic" -> applemusic
        "localfiles" -> localfiles
        else -> null
    }
}
