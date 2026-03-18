package com.parachord.android.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/**
 * [clickable] wrapper that performs a light haptic tick on every tap.
 */
fun Modifier.hapticClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    clickable(enabled = enabled) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
}

/**
 * [combinedClickable] wrapper that performs haptic feedback on tap and long-press.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.hapticCombinedClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    combinedClickable(
        enabled = enabled,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        onLongClick = onLongClick?.let { handler ->
            {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                handler()
            }
        },
        onDoubleClick = onDoubleClick,
    )
}

/**
 * Provides a haptic click performer for use inside onClick lambdas
 * (e.g. IconButton, Button) where a Modifier extension isn't applicable.
 */
@Composable
fun rememberHapticClick(): () -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
}
