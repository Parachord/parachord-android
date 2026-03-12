package com.parachord.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Parachord typography — tuned letter spacing and weights
 * to match the desktop app's refined feel.
 *
 * Uses system Roboto (matches desktop's system font stack).
 */
val ParachordTypography = Typography(
    // Display styles (large hero text)
    displayLarge = Typography().displayLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.02).em,
        lineHeight = 40.sp,
    ),
    displayMedium = Typography().displayMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.02).em,
    ),
    displaySmall = Typography().displaySmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.01).em,
    ),

    // Headline styles (screen titles, section titles)
    headlineLarge = Typography().headlineLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.01).em,
        lineHeight = 36.sp,
    ),
    headlineMedium = Typography().headlineMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.005).em,
        lineHeight = 32.sp,
    ),
    headlineSmall = Typography().headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.005).em,
        lineHeight = 28.sp,
    ),

    // Title styles
    titleLarge = Typography().titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
    ),
    titleMedium = Typography().titleMedium.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.01.em,
    ),
    titleSmall = Typography().titleSmall.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.02.em,
    ),

    // Body styles
    bodyLarge = Typography().bodyLarge.copy(
        lineHeight = 24.sp,
        letterSpacing = 0.01.em,
    ),
    bodyMedium = Typography().bodyMedium.copy(
        lineHeight = 22.sp,
        letterSpacing = 0.02.em,
    ),
    bodySmall = Typography().bodySmall.copy(
        lineHeight = 18.sp,
        letterSpacing = 0.02.em,
    ),

    // Label styles (section headers, badges, metadata)
    labelLarge = Typography().labelLarge.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.05.em,
    ),
    labelMedium = Typography().labelMedium.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.05.em,
    ),
    labelSmall = Typography().labelSmall.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.08.em,
    ),
)
