package com.parachord.android.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom icons matching the desktop Parachord app exactly.
 */
object ParachordIcons {

    /**
     * Shuffleupagus mammoth icon — used for AI chat feature.
     * Source: desktop app.js custom SVG, viewBox 0 0 122.88 89.38
     */
    val Shuffleupagus: ImageVector by lazy {
        ImageVector.Builder(
            name = "Shuffleupagus",
            defaultWidth = 24.dp,
            defaultHeight = 17.56.dp,
            viewportWidth = 122.88f,
            viewportHeight = 89.38f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                fillAlpha = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
                // Tusk/eye detail
                moveTo(51.57f, 34.4f)
                curveToRelative(-1.27f, 0.06f, -2.35f, -0.91f, -2.41f, -2.18f)
                curveToRelative(-0.06f, -1.27f, 0.91f, -2.35f, 2.18f, -2.41f)
                curveToRelative(1.66f, -0.09f, 3.31f, 0.06f, 4.94f, 0.44f)
                curveToRelative(1.63f, 0.38f, 3.24f, 0.98f, 4.84f, 1.8f)
                curveToRelative(1.13f, 0.58f, 1.57f, 1.97f, 0.99f, 3.1f)
                curveToRelative(-0.58f, 1.13f, -1.97f, 1.57f, -3.1f, 0.99f)
                curveToRelative(-1.25f, -0.65f, -2.51f, -1.12f, -3.76f, -1.41f)
                curveTo(54.02f, 34.45f, 52.79f, 34.34f, 51.57f, 34.4f)
                lineTo(51.57f, 34.4f)
                close()

                // Main body outline
                moveTo(29.24f, 60.23f)
                curveToRelative(-3.02f, 0.16f, -5.82f, -0.11f, -8.39f, -0.73f)
                curveToRelative(-6.34f, -1.53f, -11.26f, -5.21f, -14.73f, -10.03f)
                curveToRelative(-3.41f, -4.74f, -5.4f, -10.58f, -5.96f, -16.56f)
                curveToRelative(-0.38f, -4.04f, -0.1f, -8.16f, 0.84f, -12.05f)
                curveToRelative(1.19f, -4.91f, 3.22f, -8.91f, 5.8f, -12.05f)
                curveToRelative(3.22f, -3.92f, 7.29f, -6.47f, 11.65f, -7.77f)
                curveToRelative(4.34f, -1.3f, 8.97f, -1.36f, 13.38f, -0.29f)
                curveToRelative(3.52f, 0.85f, 6.9f, 2.43f, 9.85f, 4.66f)
                curveToRelative(3.4f, -0.52f, 6.75f, -0.85f, 10.01f, -0.86f)
                curveToRelative(3.64f, -0.01f, 7.15f, 0.37f, 10.44f, 1.32f)
                curveToRelative(3.01f, 0.87f, 5.87f, 2.21f, 8.52f, 4.15f)
                curveToRelative(2.63f, 1.92f, 5.04f, 4.41f, 7.16f, 7.59f)
                lineToRelative(0.02f, 0.03f)
                lineToRelative(0f, 0f)
                curveToRelative(1.53f, 2.35f, 2.89f, 4.77f, 4.1f, 7.24f)
                curveToRelative(1.2f, 2.46f, 2.24f, 5f, 3.16f, 7.6f)
                curveToRelative(2.49f, 7.08f, 3.97f, 14.26f, 5.51f, 21.72f)
                lineToRelative(0.38f, 1.83f)
                curveToRelative(0.93f, 4.48f, 2.29f, 7.49f, 3.86f, 9.33f)
                curveToRelative(1.48f, 1.74f, 3.12f, 2.31f, 4.65f, 2.02f)
                curveToRelative(7.47f, -1.41f, 7.21f, -11.73f, 7.03f, -19.2f)
                curveToRelative(-0.03f, -1.26f, -0.06f, -2.45f, -0.05f, -3.57f)
                curveToRelative(0f, -1.05f, 0.72f, -1.94f, 1.69f, -2.21f)
                lineToRelative(7.7f, -2.33f)
                curveToRelative(1.19f, -0.36f, 2.44f, 0.29f, 2.84f, 1.45f)
                curveToRelative(0.27f, 0.7f, 0.47f, 1.26f, 0.63f, 1.74f)
                curveToRelative(0.16f, 0.5f, 0.35f, 1.11f, 0.55f, 1.78f)
                curveToRelative(1.53f, 5.15f, 2.76f, 9.88f, 2.99f, 14.72f)
                curveToRelative(0.23f, 4.95f, -0.6f, 9.9f, -3.24f, 15.33f)
                curveToRelative(-2.17f, 4.47f, -5.41f, 8.28f, -9.67f, 10.84f)
                curveToRelative(-3.87f, 2.33f, -8.57f, 3.63f, -14.04f, 3.44f)
                curveToRelative(-4.55f, -0.15f, -9.07f, -1.56f, -13.26f, -3.96f)
                curveToRelative(-4.2f, -2.41f, -8.09f, -5.84f, -11.3f, -10.02f)
                curveToRelative(-2.07f, -2.7f, -4.1f, -5.7f, -6.07f, -8.99f)
                curveToRelative(-1.95f, 1.68f, -4f, 3.18f, -6.14f, 4.51f)
                curveToRelative(-2.92f, 1.81f, -6f, 3.29f, -9.21f, 4.47f)
                curveToRelative(-0.56f, 0.2f, -1.14f, 0.18f, -1.65f, -0.03f)
                curveToRelative(-3.95f, -1.34f, -7.63f, -3.39f, -10.97f, -6.15f)
                curveTo(34.32f, 66.77f, 31.63f, 63.77f, 29.24f, 60.23f)
                lineTo(29.24f, 60.23f)
                close()

                // Inner body detail
                moveTo(62.93f, 62.34f)
                curveToRelative(-1.34f, -2.42f, -2.66f, -4.97f, -3.96f, -7.66f)
                curveToRelative(-0.55f, -1.14f, -0.07f, -2.52f, 1.07f, -3.07f)
                curveToRelative(1.14f, -0.55f, 2.52f, -0.07f, 3.07f, 1.07f)
                curveToRelative(1.91f, 3.92f, 3.86f, 7.58f, 5.85f, 10.93f)
                curveToRelative(1.98f, 3.33f, 3.98f, 6.33f, 6.02f, 8.97f)
                curveToRelative(2.84f, 3.69f, 6.26f, 6.71f, 9.95f, 8.83f)
                curveToRelative(3.56f, 2.04f, 7.36f, 3.24f, 11.11f, 3.36f)
                curveToRelative(4.53f, 0.15f, 8.37f, -0.9f, 11.52f, -2.79f)
                curveToRelative(3.47f, -2.09f, 6.12f, -5.21f, 7.91f, -8.9f)
                curveToRelative(2.27f, -4.66f, 2.98f, -8.9f, 2.78f, -13.13f)
                curveToRelative(-0.2f, -4.33f, -1.37f, -8.76f, -2.81f, -13.62f)
                curveToRelative(-0.11f, -0.36f, -0.23f, -0.76f, -0.38f, -1.23f)
                lineToRelative(-4f, 1.21f)
                curveToRelative(0.01f, 0.56f, 0.03f, 1.15f, 0.04f, 1.76f)
                curveToRelative(0.23f, 9.11f, 0.54f, 21.69f, -10.77f, 23.83f)
                curveToRelative(-3.11f, 0.59f, -6.32f, -0.4f, -9.01f, -3.57f)
                curveToRelative(-2.03f, -2.38f, -3.75f, -6.08f, -4.85f, -11.39f)
                lineToRelative(-0.38f, -1.83f)
                curveToRelative(-1.5f, -7.3f, -2.95f, -14.31f, -5.35f, -21.12f)
                curveToRelative(-0.85f, -2.41f, -1.83f, -4.79f, -2.96f, -7.11f)
                curveToRelative(-1.12f, -2.31f, -2.39f, -4.56f, -3.81f, -6.73f)
                curveToRelative(-1.82f, -2.72f, -3.84f, -4.82f, -6.03f, -6.42f)
                curveToRelative(-2.17f, -1.59f, -4.55f, -2.7f, -7.08f, -3.42f)
                curveToRelative(-2.89f, -0.83f, -5.97f, -1.17f, -9.18f, -1.16f)
                curveToRelative(-3.27f, 0.01f, -6.72f, 0.38f, -10.27f, 0.96f)
                lineToRelative(0f, 0f)
                curveToRelative(-0.63f, 0.1f, -1.3f, -0.06f, -1.83f, -0.49f)
                curveToRelative(-2.6f, -2.14f, -5.64f, -3.63f, -8.84f, -4.4f)
                curveToRelative(-3.62f, -0.88f, -7.43f, -0.83f, -10.98f, 0.24f)
                curveToRelative(-3.53f, 1.06f, -6.81f, 3.12f, -9.42f, 6.28f)
                curveToRelative(-2.16f, 2.62f, -3.86f, 6f, -4.88f, 10.21f)
                curveToRelative(-0.82f, 3.4f, -1.07f, 7.01f, -0.74f, 10.56f)
                curveToRelative(0.48f, 5.19f, 2.19f, 10.24f, 5.11f, 14.29f)
                curveToRelative(2.86f, 3.97f, 6.89f, 6.99f, 12.08f, 8.24f)
                curveToRelative(2.34f, 0.57f, 4.93f, 0.78f, 7.77f, 0.56f)
                curveToRelative(0.97f, -0.31f, 2.07f, 0.06f, 2.64f, 0.97f)
                curveToRelative(2.32f, 3.67f, 4.96f, 6.7f, 7.87f, 9.1f)
                curveToRelative(2.73f, 2.25f, 5.7f, 3.95f, 8.88f, 5.11f)
                curveToRelative(2.67f, -1.03f, 5.22f, -2.28f, 7.62f, -3.77f)
                curveTo(58.88f, 65.66f, 60.96f, 64.11f, 62.93f, 62.34f)
                lineTo(62.93f, 62.34f)
                close()

                // Head/face detail
                moveTo(30.41f, 16.02f)
                curveToRelative(1.25f, -0.21f, 2.44f, 0.64f, 2.65f, 1.89f)
                curveToRelative(0.21f, 1.25f, -0.64f, 2.44f, -1.89f, 2.65f)
                curveToRelative(-1.9f, 0.32f, -3.27f, 1.43f, -4.11f, 2.92f)
                curveToRelative(-0.69f, 1.21f, -1.04f, 2.67f, -1.07f, 4.17f)
                curveToRelative(-0.03f, 1.53f, 0.3f, 3.1f, 0.97f, 4.5f)
                curveToRelative(0.97f, 2.04f, 2.7f, 3.7f, 5.19f, 4.32f)
                curveToRelative(1.23f, 0.3f, 1.99f, 1.55f, 1.68f, 2.78f)
                curveToRelative(-0.3f, 1.23f, -1.55f, 1.99f, -2.78f, 1.68f)
                curveToRelative(-3.99f, -0.99f, -6.73f, -3.61f, -8.26f, -6.8f)
                curveToRelative(-0.97f, -2.04f, -1.44f, -4.32f, -1.4f, -6.56f)
                curveToRelative(0.04f, -2.26f, 0.59f, -4.48f, 1.66f, -6.37f)
                curveTo(24.55f, 18.58f, 27f, 16.6f, 30.41f, 16.02f)
                lineTo(30.41f, 16.02f)
                close()
            }
        }.build()
    }

    /**
     * Spinoff fork/branch icon — used for spinoff playback mode.
     * Source: desktop app.js IBM Carbon-style fork icon, viewBox 0 0 32 32
     */
    val Spinoff: ImageVector by lazy {
        ImageVector.Builder(
            name = "Spinoff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 32f,
            viewportHeight = 32f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                pathFillType = PathFillType.EvenOdd,
            ) {
                moveTo(26f, 18f)
                arcToRelative(3.9955f, 3.9955f, 0f, false, false, -3.858f, 3f)
                horizontalLineTo(18f)
                arcToRelative(3.0033f, 3.0033f, 0f, false, true, -3f, -3f)
                verticalLineTo(14f)
                arcToRelative(4.9514f, 4.9514f, 0f, false, false, -1.0256f, -3f)
                horizontalLineTo(22.142f)
                arcToRelative(4f, 4f, 0f, true, false, 0f, -2f)
                horizontalLineTo(9.858f)
                arcToRelative(4f, 4f, 0f, true, false, 0f, 2f)
                horizontalLineTo(10f)
                arcToRelative(3.0033f, 3.0033f, 0f, false, true, 3f, 3f)
                verticalLineToRelative(4f)
                arcToRelative(5.0059f, 5.0059f, 0f, false, false, 5f, 5f)
                horizontalLineToRelative(4.142f)
                arcTo(3.9935f, 3.9935f, 0f, true, false, 26f, 18f)
                close()
                moveTo(26f, 8f)
                arcToRelative(2f, 2f, 0f, true, true, -2f, 2f)
                arcTo(2.0023f, 2.0023f, 0f, false, true, 26f, 8f)
                close()
                moveTo(6f, 12f)
                arcToRelative(2f, 2f, 0f, true, true, 2f, -2f)
                arcTo(2.002f, 2.002f, 0f, false, true, 6f, 12f)
                close()
                moveTo(26f, 24f)
                arcToRelative(2f, 2f, 0f, true, true, 2f, -2f)
                arcTo(2.0027f, 2.0027f, 0f, false, true, 26f, 24f)
                close()
            }
        }.build()
    }

    /**
     * Shuffle icon — crossing arrows for shuffle mode.
     * Source: desktop app.js custom SVG, viewBox 0 0 24 24
     */
    val Shuffle: ImageVector by lazy {
        ImageVector.Builder(
            name = "Shuffle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(10.59f, 9.17f)
                lineTo(5.41f, 4f)
                lineTo(4f, 5.41f)
                lineToRelative(5.17f, 5.17f)
                lineToRelative(1.42f, -1.41f)
                close()
                moveTo(14.5f, 4f)
                lineToRelative(2.04f, 2.04f)
                lineTo(4f, 18.59f)
                lineTo(5.41f, 20f)
                lineTo(17.96f, 7.46f)
                lineTo(20f, 9.5f)
                verticalLineTo(4f)
                horizontalLineToRelative(-5.5f)
                close()
                moveTo(14.83f, 13.41f)
                lineToRelative(-1.41f, 1.41f)
                lineToRelative(3.13f, 3.13f)
                lineTo(14.5f, 20f)
                horizontalLineTo(20f)
                verticalLineToRelative(-5.5f)
                lineToRelative(-2.04f, 2.04f)
                lineToRelative(-3.13f, -3.13f)
                close()
            }
        }.build()
    }

    /**
     * Queue icon — three horizontal lines matching Lucide List.
     * Source: desktop app.js Lucide List icon, viewBox 0 0 24 24
     */
    val Queue: ImageVector by lazy {
        ImageVector.Builder(
            name = "Queue",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 6f)
                lineTo(20f, 6f)
                moveTo(4f, 12f)
                lineTo(20f, 12f)
                moveTo(4f, 18f)
                lineTo(20f, 18f)
            }
        }.build()
    }
}
