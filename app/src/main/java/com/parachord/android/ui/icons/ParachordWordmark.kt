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
 * Parachord wordmark logo matching the desktop SVG exactly.
 * ViewBox: 0 0 1046 273
 *
 * @param fill The primary fill color for the letterforms.
 */
fun parachordWordmark(fill: Color): ImageVector {
    return ImageVector.Builder(
        name = "ParachordWordmark",
        defaultWidth = 160.dp,
        defaultHeight = 41.8.dp,
        viewportWidth = 1046f,
        viewportHeight = 273f,
    ).apply {
        // Letter circles and bars making up "parachord"

        // Circle 1 - 'p' bowl (far left)
        path(
            fill = null,
            stroke = SolidColor(fill),
            strokeLineWidth = 27f,
        ) {
            moveTo(115.5f, 102.5f)
            curveTo(139.725f, 102.5f, 160.5f, 123.346f, 160.5f, 150.5f)
            curveTo(160.5f, 177.654f, 139.725f, 198.5f, 115.5f, 198.5f)
            curveTo(91.2754f, 198.5f, 70.5f, 177.654f, 70.5f, 150.5f)
            curveTo(70.5f, 123.346f, 91.2754f, 102.5f, 115.5f, 102.5f)
            close()
        }

        // 'p' descender
        path(fill = SolidColor(fill)) {
            moveTo(57f, 148f)
            lineTo(82f, 148f)
            lineTo(82f, 256.261f)
            lineTo(57f, 256.261f)
            close()
        }

        // 'p' descender triangle
        path(fill = SolidColor(fill)) {
            moveTo(82f, 256.261f)
            lineTo(57f, 272.5f)
            lineTo(57f, 255.66f)
            lineTo(57f, 238.819f)
            lineTo(82f, 256.261f)
            close()
        }

        // Circle 2 - 'a' bowl
        path(
            fill = null,
            stroke = SolidColor(fill),
            strokeLineWidth = 27f,
        ) {
            moveTo(237.5f, 102.5f)
            curveTo(261.725f, 102.5f, 282.5f, 123.347f, 282.5f, 150.5f)
            curveTo(282.5f, 177.654f, 261.725f, 198.5f, 237.5f, 198.5f)
            curveTo(213.275f, 198.5f, 192.5f, 177.654f, 192.5f, 150.5f)
            curveTo(192.5f, 123.347f, 213.275f, 102.5f, 237.5f, 102.5f)
            close()
        }

        // 'a' vertical bar
        path(fill = SolidColor(fill)) {
            moveTo(271f, 89.0005f)
            lineTo(296f, 89.0005f)
            lineTo(296f, 212.0005f)
            lineTo(271f, 212.0005f)
            close()
        }

        // 'r' vertical bar
        path(fill = SolidColor(fill)) {
            moveTo(307f, 152f)
            lineTo(334f, 152f)
            lineTo(334f, 212f)
            lineTo(307f, 212f)
            close()
        }

        // 'r' top curve
        path(fill = SolidColor(fill)) {
            moveTo(307f, 152f)
            curveTo(307f, 117.207f, 333.191f, 89.0005f, 365.5f, 89.0005f)
            curveTo(365.667f, 89.0005f, 365.833f, 89.0029f, 366f, 89.0044f)
            lineTo(366f, 116.008f)
            curveTo(365.833f, 116.005f, 365.667f, 116f, 365.5f, 116f)
            curveTo(350.195f, 116f, 334.499f, 129.759f, 334.012f, 150.984f)
            lineTo(334f, 152f)
            lineTo(307f, 152f)
            close()
        }

        // Circle 3 - 'a' bowl (second)
        path(
            fill = null,
            stroke = SolidColor(fill),
            strokeLineWidth = 27f,
        ) {
            moveTo(424.5f, 102.5f)
            curveTo(448.725f, 102.5f, 469.5f, 123.347f, 469.5f, 150.5f)
            curveTo(469.5f, 177.654f, 448.725f, 198.5f, 424.5f, 198.5f)
            curveTo(400.275f, 198.5f, 379.5f, 177.654f, 379.5f, 150.5f)
            curveTo(379.5f, 123.347f, 400.275f, 102.5f, 424.5f, 102.5f)
            close()
        }

        // Second 'a' vertical bar
        path(fill = SolidColor(fill)) {
            moveTo(458f, 89.0005f)
            lineTo(483f, 89.0005f)
            lineTo(483f, 212.0005f)
            lineTo(458f, 212.0005f)
            close()
        }

        // 'c' letter
        path(fill = SolidColor(fill)) {
            moveTo(547.5f, 92.0005f)
            curveTo(565.823f, 92.0005f, 582.177f, 100.857f, 592.903f, 114.719f)
            lineTo(569.128f, 128.445f)
            curveTo(563.289f, 122.472f, 555.491f, 119f, 547.5f, 119f)
            curveTo(531.36f, 119f, 516f, 133.158f, 516f, 153.5f)
            curveTo(516f, 173.843f, 531.36f, 188f, 547.5f, 188f)
            curveTo(554.915f, 188f, 562.163f, 185.011f, 567.835f, 179.808f)
            lineTo(591.812f, 193.651f)
            curveTo(581.084f, 206.724f, 565.212f, 215f, 547.5f, 215f)
            curveTo(515.191f, 215f, 489f, 187.466f, 489f, 153.5f)
            curveTo(489f, 119.535f, 515.191f, 92.0005f, 547.5f, 92.0005f)
            close()
        }

        // 'h' tall vertical bar
        path(fill = SolidColor(fill)) {
            moveTo(597f, 45.8784f)
            lineTo(622f, 45.8784f)
            lineTo(622f, 210.8784f)
            lineTo(597f, 210.8784f)
            close()
        }

        // 'h' triangle accent
        path(fill = SolidColor(fill)) {
            moveTo(622f, 38.0005f)
            lineTo(622f, 50.5005f)
            lineTo(597f, 45.8784f)
            lineTo(622f, 38.0005f)
            close()
        }

        // 'h' arch
        path(fill = SolidColor(fill)) {
            moveTo(648.702f, 96.0005f)
            curveTo(675.533f, 96.0005f, 698.054f, 122.271f, 700.898f, 156f)
            lineTo(675.816f, 156f)
            curveTo(673.171f, 131.764f, 657.459f, 121f, 648.702f, 121f)
            curveTo(640.176f, 121f, 624.661f, 131.504f, 622.075f, 156f)
            lineTo(597f, 156f)
            curveTo(599.787f, 122.271f, 621.871f, 96.0005f, 648.702f, 96.0005f)
            close()
        }

        // 'h' right vertical bar
        path(fill = SolidColor(fill)) {
            moveTo(676f, 152f)
            lineTo(701f, 152f)
            lineTo(701f, 212f)
            lineTo(676f, 212f)
            close()
        }

        // Circle 4 - 'o' bowl
        path(
            fill = null,
            stroke = SolidColor(fill),
            strokeLineWidth = 27f,
        ) {
            moveTo(764.5f, 102.5f)
            curveTo(788.725f, 102.5f, 809.5f, 123.347f, 809.5f, 150.5f)
            curveTo(809.5f, 177.654f, 788.725f, 198.5f, 764.5f, 198.5f)
            curveTo(740.275f, 198.5f, 719.5f, 177.654f, 719.5f, 150.5f)
            curveTo(719.5f, 123.347f, 740.275f, 102.5f, 764.5f, 102.5f)
            close()
        }

        // 'r' second vertical bar
        path(fill = SolidColor(fill)) {
            moveTo(828f, 152f)
            lineTo(855f, 152f)
            lineTo(855f, 212f)
            lineTo(828f, 212f)
            close()
        }

        // 'r' second curve
        path(fill = SolidColor(fill)) {
            moveTo(828f, 152f)
            curveTo(828f, 117.207f, 854.191f, 89.0005f, 886.5f, 89.0005f)
            curveTo(886.667f, 89.0005f, 886.833f, 89.0029f, 887f, 89.0044f)
            lineTo(887f, 116.008f)
            curveTo(886.833f, 116.005f, 886.667f, 116f, 886.5f, 116f)
            curveTo(871.195f, 116f, 855.499f, 129.759f, 855.012f, 150.984f)
            lineTo(855f, 152f)
            lineTo(828f, 152f)
            close()
        }

        // Circle 5 - 'd' bowl
        path(
            fill = null,
            stroke = SolidColor(fill),
            strokeLineWidth = 27f,
        ) {
            moveTo(945.5f, 102.5f)
            curveTo(969.725f, 102.5f, 990.5f, 123.347f, 990.5f, 150.5f)
            curveTo(990.5f, 177.654f, 969.725f, 198.5f, 945.5f, 198.5f)
            curveTo(921.275f, 198.5f, 900.5f, 177.654f, 900.5f, 150.5f)
            curveTo(900.5f, 123.347f, 921.275f, 102.5f, 945.5f, 102.5f)
            close()
        }

        // 'd' bowl shadow (20% opacity)
        path(
            fill = null,
            stroke = SolidColor(fill.copy(alpha = 0.2f)),
            strokeLineWidth = 27f,
        ) {
            moveTo(945.5f, 102.5f)
            curveTo(969.725f, 102.5f, 990.5f, 123.347f, 990.5f, 150.5f)
            curveTo(990.5f, 177.654f, 969.725f, 198.5f, 945.5f, 198.5f)
            curveTo(921.275f, 198.5f, 900.5f, 177.654f, 900.5f, 150.5f)
            curveTo(900.5f, 123.347f, 921.275f, 102.5f, 945.5f, 102.5f)
            close()
        }

        // 'd' tall vertical bar (rotated 180 = positioned right side)
        path(fill = SolidColor(fill)) {
            moveTo(979.01f, 51.132f)
            lineTo(1004.01f, 51.132f)
            lineTo(1004.01f, 151.398f)
            lineTo(979.01f, 151.398f)
            close()
        }

        // Red play arrow accent (brand mark)
        path(
            fill = SolidColor(Color(0xFFE12949)),
        ) {
            moveTo(1039.72f, 33.8887f)
            curveTo(1042.39f, 35.348f, 1042.43f, 39.1612f, 1039.8f, 40.6846f)
            lineTo(984.893f, 72.4893f)
            curveTo(982.313f, 73.9831f, 979.08f, 72.1478f, 979.039f, 69.167f)
            lineTo(978.192f, 6.85254f)
            curveTo(978.152f, 3.86674f, 981.345f, 1.94449f, 983.965f, 3.37793f)
            lineTo(1039.72f, 33.8887f)
            close()
        }

        // Gray decorative flourish (left side)
        path(fill = SolidColor(Color(0xFF767575))) {
            moveTo(7.29194f, 100.001f)
            curveTo(7.32449f, 100.03f, 7.16169f, 101.377f, 6.90132f, 102.986f)
            curveTo(6.08765f, 108.341f, 5.46898f, 114.633f, 5.14351f, 121.363f)
            curveTo(4.16715f, 140.559f, 7.45444f, 156.098f, 14.3544f, 165.316f)
            curveTo(18.3253f, 170.612f, 26.072f, 175.44f, 40.8486f, 181.878f)
            curveTo(49.0502f, 185.448f, 52.3379f, 187.262f, 56.2109f, 190.334f)
            lineTo(58.8476f, 192.412f)
            verticalLineTo(186.211f)
            horizontalLineTo(63.4042f)
            curveTo(63.4041f, 257.673f, 63.3388f, 265.772f, 62.8837f, 266.359f)
            curveTo(62.5258f, 266.827f, 62.0696f, 267.002f, 61.1259f, 267.002f)
            curveTo(59.0103f, 267.002f, 58.8476f, 266.534f, 58.8476f, 260.477f)
            verticalLineTo(255.238f)
            lineTo(57.1875f, 252.722f)
            curveTo(54.0629f, 247.923f, 51.0032f, 244.411f, 46.5117f, 240.373f)
            curveTo(41.6947f, 236.042f, 38.3425f, 233.818f, 32.6142f, 231.126f)
            curveTo(26.3002f, 228.112f, 22.6871f, 225.742f, 18.5537f, 221.821f)
            curveTo(8.39888f, 212.194f, 2.14945f, 198.528f, 0.261671f, 181.761f)
            curveTo(-0.226521f, 177.518f, 0.0335781f, 163.501f, 0.684523f, 158.994f)
            curveTo(1.0425f, 156.39f, 1.07508f, 155.512f, 0.684523f, 153.288f)
            curveTo(0.0336106f, 149.542f, -0.226209f, 135.731f, 0.229445f, 130.902f)
            curveTo(1.01061f, 122.738f, 3.06059f, 113.081f, 5.8271f, 104.215f)
            curveTo(6.57471f, 101.848f, 7.22547f, 99.9476f, 7.29194f, 100.001f)
            close()

            moveTo(5.66499f, 172.338f)
            curveTo(6.28338f, 178.015f, 7.8456f, 184.628f, 9.53804f, 188.871f)
            curveTo(12.4673f, 196.187f, 16.0803f, 200.635f, 22.3945f, 204.673f)
            curveTo(27.8298f, 208.184f, 31.7356f, 210.145f, 44.2011f, 215.588f)
            curveTo(46.6096f, 216.641f, 49.7671f, 218.222f, 51.1992f, 219.07f)
            curveTo(52.6312f, 219.89f, 54.9417f, 221.587f, 56.3085f, 222.757f)
            lineTo(58.8476f, 224.952f)
            verticalLineTo(223.986f)
            curveTo(58.8475f, 223.372f, 58.2943f, 222.143f, 57.3505f, 220.651f)
            curveTo(51.492f, 211.433f, 42.7362f, 203.649f, 33.1347f, 199.113f)
            curveTo(30.9543f, 198.089f, 28.1881f, 196.656f, 26.9511f, 195.924f)
            curveTo(18.4889f, 190.891f, 11.8166f, 183.341f, 6.5439f, 172.777f)
            lineTo(5.43648f, 170.582f)
            lineTo(5.66499f, 172.338f)
            close()
        }

        // Decorative lines
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 0.2f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(59f, 260.805f)
            lineTo(59f, 242.676f)
            moveTo(59f, 203.856f)
            lineTo(59f, 224.803f)
        }
    }.build()
}
