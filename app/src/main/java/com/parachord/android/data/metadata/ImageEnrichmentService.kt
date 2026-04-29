@file:Suppress("unused")
package com.parachord.android.data.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.File
import java.io.FileOutputStream

/**
 * Source-compat typealias. The real implementation moved to
 * `com.parachord.shared.metadata.ImageEnrichmentService` so iOS can
 * share the artist/album/track/playlist enrichment pipeline. The 2x2
 * mosaic composite step is platform-specific (Coil + Bitmap on Android,
 * Kingfisher / CoreGraphics on iOS) and is forwarded into shared via
 * a `composeMosaic` suspend lambda — see [composeMosaicAndroid] and the
 * Koin binding in `AndroidModule`.
 */
typealias ImageEnrichmentService = com.parachord.shared.metadata.ImageEnrichmentService

/**
 * Android's mosaic composite: download 4 images via Coil, draw them into
 * a 600×600 bitmap (2×2 grid), and write the result as JPEG to internal
 * storage at `<filesDir>/playlist_mosaics/<playlistId>.jpg`. Returns the
 * `file://` URI the UI can render, or null if the composite failed.
 *
 * URLs are pre-padded to exactly 4 entries by the shared caller.
 */
internal suspend fun composeMosaicAndroid(
    context: Context,
    playlistId: String,
    urls: List<String>,
): String? {
    val mosaicSize = 600 // 600x600 output
    val tileSize = mosaicSize / 2

    val bitmaps = urls.mapNotNull { url ->
        try {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(tileSize)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            (result as? SuccessResult)?.drawable?.let { drawable ->
                val bitmap = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, tileSize, tileSize)
                drawable.draw(canvas)
                bitmap
            }
        } catch (_: Exception) {
            null
        }
    }

    if (bitmaps.isEmpty()) return null

    // Pad if some downloads failed
    val tiles = when {
        bitmaps.size >= 4 -> bitmaps.take(4)
        bitmaps.size == 1 -> return null // Can't make a mosaic from 1
        else -> {
            // Fill remaining slots by repeating
            val padded = bitmaps.toMutableList()
            while (padded.size < 4) padded.add(bitmaps[padded.size % bitmaps.size])
            padded
        }
    }

    val mosaic = Bitmap.createBitmap(mosaicSize, mosaicSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(mosaic)

    // Top-left, Top-right, Bottom-left, Bottom-right
    val positions = listOf(
        0 to 0,
        tileSize to 0,
        0 to tileSize,
        tileSize to tileSize,
    )
    tiles.forEachIndexed { i, tile ->
        canvas.drawBitmap(tile, positions[i].first.toFloat(), positions[i].second.toFloat(), null)
    }

    // Save to internal storage
    val dir = File(context.filesDir, "playlist_mosaics")
    dir.mkdirs()
    val file = File(dir, "$playlistId.jpg")
    FileOutputStream(file).use { out ->
        mosaic.compress(Bitmap.CompressFormat.JPEG, 85, out)
    }

    // Clean up bitmaps
    tiles.forEach { it.recycle() }
    mosaic.recycle()

    return file.toURI().toString()
}
