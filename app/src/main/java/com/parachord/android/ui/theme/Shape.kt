package com.parachord.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Parachord shape scale — matches the desktop app's border-radius tokens.
 *
 * Desktop: small=4px, medium=6px, large=8px, xl=10px, 2xl=12px, pill=16px
 */
val ParachordShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),   // badges, chips, small thumbnails
    small = RoundedCornerShape(6.dp),        // input fields, small cards
    medium = RoundedCornerShape(8.dp),       // standard cards (desktop "rounded-lg")
    large = RoundedCornerShape(12.dp),       // album art, larger cards
    extraLarge = RoundedCornerShape(16.dp),  // modals, bottom sheets, large art
)
