package com.parachord.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.parachord.android.R

/**
 * Resolver icon colors matching the desktop's resolverIconColors.
 * These are the solid brand colors used as icon background squares.
 */
object ResolverIconColors {
    val spotify = Color(0xFF1DB954)
    val bandcamp = Color(0xFF1DA0C3)
    val qobuz = Color(0xFF4285F4)
    val youtube = Color(0xFFFF0000)
    val localfiles = Color(0xFFA855F7)
    val soundcloud = Color(0xFFFF5500)
    val applemusic = Color(0xFFFA2D48)
    val lastfm = Color(0xFFD51007)
    val listenbrainz = Color(0xFF353070)
    val librefm = Color(0xFF2E7D32)
    val discogs = Color(0xFF333333)
    val wikipedia = Color(0xFF636466)
    val chatgpt = Color(0xFF10A37F)
    val claude = Color(0xFFD97757)
    val gemini = Color(0xFF4285F4)

    fun forResolver(resolver: String?): Color? = when (resolver?.lowercase()) {
        "spotify" -> spotify
        "bandcamp" -> bandcamp
        "qobuz" -> qobuz
        "youtube" -> youtube
        "localfiles" -> localfiles
        "soundcloud" -> soundcloud
        "applemusic" -> applemusic
        "lastfm" -> lastfm
        "listenbrainz" -> listenbrainz
        "librefm" -> librefm
        "discogs" -> discogs
        "wikipedia" -> wikipedia
        "chatgpt" -> chatgpt
        "claude" -> claude
        "gemini" -> gemini
        else -> null
    }
}

/**
 * SVG path data for resolver service logos, matching the desktop's SERVICE_LOGO_PATHS.
 * All paths use viewBox 0 0 24 24.
 */
object ResolverIconPaths {
    val spotify = "M12 0C5.4 0 0 5.4 0 12s5.4 12 12 12 12-5.4 12-12S18.66 0 12 0zm5.521 17.34c-.24.359-.66.48-1.021.24-2.82-1.74-6.36-2.101-10.561-1.141-.418.122-.779-.179-.899-.539-.12-.421.18-.78.54-.9 4.56-1.021 8.52-.6 11.64 1.32.42.18.479.659.301 1.02zm1.44-3.3c-.301.42-.841.6-1.262.3-3.239-1.98-8.159-2.58-11.939-1.38-.479.12-1.02-.12-1.14-.6-.12-.48.12-1.021.6-1.141C9.6 9.9 15 10.561 18.72 12.84c.361.181.54.78.241 1.2zm.12-3.36C15.24 8.4 8.82 8.16 5.16 9.301c-.6.179-1.2-.181-1.38-.721-.18-.601.18-1.2.72-1.381 4.26-1.26 11.28-1.02 15.721 1.621.539.3.719 1.02.419 1.56-.299.421-1.02.599-1.559.3z"

    val youtube = "M23.498 6.186a3.016 3.016 0 0 0-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 0 0 .502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 0 0 2.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 0 0 2.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12l-6.273 3.568z"

    val bandcamp = "M0 18.75l7.437-13.5H24l-7.438 13.5H0z"

    val localfiles = "M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-6 10h-4v-4H8l4-4 4 4h-2v4z"

    // SoundCloud uses a PNG drawable — no SVG path needed

    val applemusic = "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"

    val qobuz = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14.5c-2.49 0-4.5-2.01-4.5-4.5S9.51 7.5 12 7.5s4.5 2.01 4.5 4.5-2.01 4.5-4.5 4.5zm0-7c-1.38 0-2.5 1.12-2.5 2.5s1.12 2.5 2.5 2.5 2.5-1.12 2.5-2.5-1.12-2.5-2.5-2.5z"

    val lastfm = "M10.584 17.209l-.88-2.392s-1.43 1.595-3.573 1.595c-1.897 0-3.244-1.65-3.244-4.289 0-3.381 1.704-4.591 3.382-4.591 2.419 0 3.188 1.567 3.849 3.574l.88 2.75c.879 2.667 2.528 4.811 7.284 4.811 3.409 0 5.719-1.044 5.719-3.793 0-2.227-1.265-3.381-3.629-3.932l-1.76-.385c-1.209-.275-1.566-.77-1.566-1.594 0-.935.742-1.485 1.952-1.485 1.319 0 2.034.495 2.144 1.677l2.749-.33c-.22-2.474-1.924-3.491-4.729-3.491-2.474 0-4.893.935-4.893 3.931 0 1.87.907 3.052 3.188 3.602l1.869.439c1.402.33 1.869.907 1.869 1.705 0 1.017-.989 1.43-2.858 1.43-2.776 0-3.932-1.457-4.591-3.464l-.907-2.749c-1.155-3.574-2.997-4.894-6.653-4.894-4.041-.001-6.186 2.556-6.186 6.899 0 4.179 2.145 6.433 5.993 6.433 3.107.001 4.591-1.457 4.591-1.457z"

    // ListenBrainz — headphones icon (Material Icons)
    val listenbrainz = "M12 1c-4.97 0-9 4.03-9 9v7c0 1.66 1.34 3 3 3h3v-8H5v-2c0-3.87 3.13-7 7-7s7 3.13 7 7v2h-4v8h3c1.66 0 3-1.34 3-3v-7c0-4.97-4.03-9-9-9z"

    // Libre.fm — music note icon
    val librefm = "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"

    // Discogs — vinyl record icon
    val discogs = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm0-12.5c-2.49 0-4.5 2.01-4.5 4.5s2.01 4.5 4.5 4.5 4.5-2.01 4.5-4.5-2.01-4.5-4.5-4.5zm0 5.5c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1z"

    // Wikipedia — book/encyclopedia icon
    val wikipedia = "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"

    // ChatGPT — OpenAI logo (hexagonal flower)
    val chatgpt = "M22.282 9.821a5.985 5.985 0 0 0-.516-4.91 6.046 6.046 0 0 0-6.51-2.9A6.065 6.065 0 0 0 4.981 4.18a5.998 5.998 0 0 0-3.998 2.9 6.046 6.046 0 0 0 .743 7.097 5.98 5.98 0 0 0 .51 4.911 6.051 6.051 0 0 0 6.515 2.9A5.985 5.985 0 0 0 13.26 24a6.056 6.056 0 0 0 5.772-4.206 5.99 5.99 0 0 0 3.997-2.9 6.056 6.056 0 0 0-.747-7.073zM13.26 22.43a4.476 4.476 0 0 1-2.876-1.04l.141-.081 4.779-2.758a.795.795 0 0 0 .392-.681v-6.737l2.02 1.168a.071.071 0 0 1 .038.052v5.583a4.504 4.504 0 0 1-4.494 4.494zM3.6 18.304a4.47 4.47 0 0 1-.535-3.014l.142.085 4.783 2.759a.771.771 0 0 0 .78 0l5.843-3.369v2.332a.08.08 0 0 1-.033.062L9.74 19.95a4.5 4.5 0 0 1-6.14-1.646zM2.34 7.896a4.485 4.485 0 0 1 2.366-1.973V11.6a.766.766 0 0 0 .388.676l5.815 3.355-2.02 1.168a.076.076 0 0 1-.071 0l-4.83-2.786A4.504 4.504 0 0 1 2.34 7.872zm16.597 3.855l-5.833-3.387L15.119 7.2a.076.076 0 0 1 .071 0l4.83 2.791a4.494 4.494 0 0 1-.676 8.105v-5.678a.79.79 0 0 0-.407-.667zm2.01-3.023l-.141-.085-4.774-2.782a.776.776 0 0 0-.785 0L9.409 9.23V6.897a.066.066 0 0 1 .028-.061l4.83-2.787a4.5 4.5 0 0 1 6.68 4.66zm-12.64 4.135l-2.02-1.164a.08.08 0 0 1-.038-.057V6.075a4.5 4.5 0 0 1 7.375-3.453l-.142.08L8.704 5.46a.795.795 0 0 0-.393.681zm1.097-2.365l2.602-1.5 2.607 1.5v2.999l-2.597 1.5-2.607-1.5z"

    // Claude — Anthropic asterisk/sparkle logo
    val claude = "M16.549 3.013C15.89 1.263 14.68.212 13.155.212c-.624 0-1.255.18-1.873.536l-.113.067c-.578.345-1.094.85-1.533 1.5l-.1.153c-.41.639-.74 1.388-.977 2.226l-.054.2C8.175 6.024 8.02 7.251 8.02 8.545c0 .86.066 1.697.196 2.496l.038.223c.148.844.37 1.644.658 2.381l.075.185c.302.741.676 1.402 1.112 1.965l.105.13c.458.565.987 1.001 1.573 1.296l.126.06c.559.266 1.147.4 1.749.4.535 0 1.046-.102 1.52-.305l.112-.05c.508-.225.951-.558 1.317-.99l.09-.11c.356-.428.628-.938.808-1.516l.044-.148c.167-.566.252-1.18.252-1.83 0-.593-.067-1.17-.198-1.72l-.037-.146c-.143-.56-.358-1.07-.64-1.518l-.072-.11c-.29-.448-.641-.828-1.046-1.13l-.1-.072c-.396-.287-.843-.486-1.333-.594l-.12-.024c-.3-.061-.605-.092-.91-.092-.433 0-.854.066-1.255.196l-.143.05c-.143.052-.283.112-.42.18l.037-.19c.108-.508.255-.978.44-1.402l.066-.144c.21-.459.461-.856.75-1.183l.082-.09c.283-.31.596-.543.937-.696l.094-.04c.251-.105.512-.158.78-.158.424 0 .81.165 1.148.49l.09.092c.252.265.463.611.626 1.03L16.549 3.013z"

    // Gemini — Google AI sparkle/star
    val gemini = "M12 0L14.59 8.41L24 12L14.59 15.59L12 24L9.41 15.59L0 12L9.41 8.41L12 0z"

    fun forResolver(resolver: String?): String? = when (resolver?.lowercase()) {
        "spotify" -> spotify
        "youtube" -> youtube
        "bandcamp" -> bandcamp
        // SoundCloud uses PNG drawable, handled separately in ResolverIconSquare
        "localfiles" -> localfiles
        "applemusic" -> applemusic
        "qobuz" -> qobuz
        "lastfm" -> lastfm
        "listenbrainz" -> listenbrainz
        "librefm" -> librefm
        "discogs" -> discogs
        "wikipedia" -> wikipedia
        "chatgpt" -> chatgpt
        "claude" -> claude
        "gemini" -> gemini
        else -> null
    }
}

/**
 * Build an ImageVector from an SVG path string (viewBox 0 0 24 24).
 */
private fun resolverImageVector(name: String, pathData: String): ImageVector {
    return ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            val commands = parseSvgPath(pathData)
            commands.forEach { it(this) }
        }
    }.build()
}

/**
 * Simple SVG path parser supporting M, m, L, l, H, h, V, v, C, c, S, s, A, a, Z, z commands.
 */
private fun parseSvgPath(d: String): List<androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit> {
    val result = mutableListOf<androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit>()
    // Tokenize: split on command letters, keeping the letter
    val regex = Regex("([MmLlHhVvCcSsAaZz])")
    val parts = mutableListOf<String>()
    var lastIndex = 0
    for (match in regex.findAll(d)) {
        if (match.range.first > lastIndex) {
            // append numbers before this command to previous part
            if (parts.isNotEmpty()) {
                parts[parts.size - 1] = parts.last() + d.substring(lastIndex, match.range.first)
            }
        }
        parts.add(d.substring(match.range.first, match.range.first + 1))
        lastIndex = match.range.first + 1
    }
    if (lastIndex < d.length && parts.isNotEmpty()) {
        parts[parts.size - 1] = parts.last() + d.substring(lastIndex)
    }

    var i = 0
    while (i < parts.size) {
        val cmd = parts[i][0]
        val nums = if (parts[i].length > 1) parseNumbers(parts[i].substring(1)) else emptyList()
        when (cmd) {
            'M' -> {
                var j = 0
                while (j + 1 < nums.size) {
                    if (j == 0) {
                        val x = nums[j]; val y = nums[j + 1]
                        result.add { moveTo(x, y) }
                    } else {
                        val x = nums[j]; val y = nums[j + 1]
                        result.add { lineTo(x, y) }
                    }
                    j += 2
                }
            }
            'm' -> {
                var j = 0
                while (j + 1 < nums.size) {
                    if (j == 0) {
                        val dx = nums[j]; val dy = nums[j + 1]
                        result.add { moveToRelative(dx, dy) }
                    } else {
                        val dx = nums[j]; val dy = nums[j + 1]
                        result.add { lineToRelative(dx, dy) }
                    }
                    j += 2
                }
            }
            'L' -> {
                var j = 0
                while (j + 1 < nums.size) {
                    val x = nums[j]; val y = nums[j + 1]
                    result.add { lineTo(x, y) }
                    j += 2
                }
            }
            'l' -> {
                var j = 0
                while (j + 1 < nums.size) {
                    val dx = nums[j]; val dy = nums[j + 1]
                    result.add { lineToRelative(dx, dy) }
                    j += 2
                }
            }
            'H' -> nums.forEach { x -> result.add { horizontalLineTo(x) } }
            'h' -> nums.forEach { dx -> result.add { horizontalLineToRelative(dx) } }
            'V' -> nums.forEach { y -> result.add { verticalLineTo(y) } }
            'v' -> nums.forEach { dy -> result.add { verticalLineToRelative(dy) } }
            'C' -> {
                var j = 0
                while (j + 5 < nums.size) {
                    val x1 = nums[j]; val y1 = nums[j + 1]
                    val x2 = nums[j + 2]; val y2 = nums[j + 3]
                    val x = nums[j + 4]; val y = nums[j + 5]
                    result.add { curveTo(x1, y1, x2, y2, x, y) }
                    j += 6
                }
            }
            'c' -> {
                var j = 0
                while (j + 5 < nums.size) {
                    val dx1 = nums[j]; val dy1 = nums[j + 1]
                    val dx2 = nums[j + 2]; val dy2 = nums[j + 3]
                    val dx = nums[j + 4]; val dy = nums[j + 5]
                    result.add { curveToRelative(dx1, dy1, dx2, dy2, dx, dy) }
                    j += 6
                }
            }
            'S' -> {
                var j = 0
                while (j + 3 < nums.size) {
                    val x2 = nums[j]; val y2 = nums[j + 1]
                    val x = nums[j + 2]; val y = nums[j + 3]
                    result.add { reflectiveCurveTo(x2, y2, x, y) }
                    j += 4
                }
            }
            's' -> {
                var j = 0
                while (j + 3 < nums.size) {
                    val dx2 = nums[j]; val dy2 = nums[j + 1]
                    val dx = nums[j + 2]; val dy = nums[j + 3]
                    result.add { reflectiveCurveToRelative(dx2, dy2, dx, dy) }
                    j += 4
                }
            }
            'A' -> {
                var j = 0
                while (j + 6 < nums.size) {
                    val rx = nums[j]; val ry = nums[j + 1]
                    val rotation = nums[j + 2]
                    val largeArc = nums[j + 3] != 0f
                    val sweep = nums[j + 4] != 0f
                    val x = nums[j + 5]; val y = nums[j + 6]
                    result.add { arcTo(rx, ry, rotation, largeArc, sweep, x, y) }
                    j += 7
                }
            }
            'a' -> {
                var j = 0
                while (j + 6 < nums.size) {
                    val rx = nums[j]; val ry = nums[j + 1]
                    val rotation = nums[j + 2]
                    val largeArc = nums[j + 3] != 0f
                    val sweep = nums[j + 4] != 0f
                    val dx = nums[j + 5]; val dy = nums[j + 6]
                    result.add { arcToRelative(rx, ry, rotation, largeArc, sweep, dx, dy) }
                    j += 7
                }
            }
            'Z', 'z' -> result.add { close() }
        }
        i++
    }
    return result
}

private fun parseNumbers(s: String): List<Float> {
    // Handle negative numbers and comma/space separation
    val nums = mutableListOf<Float>()
    val pattern = Regex("-?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?")
    for (match in pattern.findAll(s)) {
        match.value.toFloatOrNull()?.let { nums.add(it) }
    }
    return nums
}

/**
 * Small colored square with white service logo icon.
 * Matches the desktop's 20x20 colored squares with 4px border radius.
 *
 * @param showBackground If true (default), shows the colored square background.
 *   If false, renders just the white icon (useful for overlaying on a colored tile).
 */
@Composable
fun ResolverIconSquare(
    resolver: String,
    size: Dp = 20.dp,
    showBackground: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val bgColor = ResolverIconColors.forResolver(resolver)

    // SoundCloud uses a PNG drawable instead of an SVG path
    if (resolver.lowercase() == "soundcloud") {
        if (showBackground && bgColor != null) {
            Box(
                modifier = modifier
                    .size(size)
                    .clip(RoundedCornerShape(4.dp))
                    .background(bgColor),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.soundcloud_icon_white),
                    contentDescription = "SoundCloud",
                    modifier = Modifier.size(size * 0.65f),
                )
            }
        } else {
            Image(
                painter = painterResource(R.drawable.soundcloud_icon_white),
                contentDescription = "SoundCloud",
                modifier = modifier.size(size),
            )
        }
        return
    }

    val pathData = ResolverIconPaths.forResolver(resolver) ?: return
    val iconVector = resolverImageVector(resolver, pathData)

    if (showBackground) {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(4.dp))
                .background(bgColor ?: return),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = resolver,
                modifier = Modifier.size(size * 0.65f),
                tint = Color.White,
            )
        }
    } else {
        // No background — just the white icon
        Icon(
            imageVector = iconVector,
            contentDescription = resolver,
            modifier = modifier.size(size),
            tint = Color.White,
        )
    }
}

/**
 * Row of resolver icon squares for a track.
 * Shows all available resolvers as small colored squares.
 */
@Composable
fun ResolverIconRow(
    resolvers: List<String>,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier,
) {
    if (resolvers.isEmpty()) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        resolvers.forEachIndexed { index, resolver ->
            if (index > 0) Spacer(modifier = Modifier.width(3.dp))
            ResolverIconSquare(resolver = resolver, size = size)
        }
    }
}
