package com.parachord.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.parachord.android.ui.theme.ParachordTheme

/**
 * Styled card matching the desktop app's card component:
 * custom background, 1dp border, 8dp rounded corners, subtle shadow.
 */
@Composable
fun ParachordCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = ParachordTheme.colors.cardBackground,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, ParachordTheme.colors.cardBorder),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}
