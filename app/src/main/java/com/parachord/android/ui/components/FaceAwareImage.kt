package com.parachord.android.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.ImageLoader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * An image composable that detects faces and aligns the crop toward them.
 *
 * Uses ML Kit face detection to find the average face center in the image,
 * then biases the ContentScale.Crop alignment so faces aren't cut off.
 * Falls back to center crop if no faces are detected.
 */
@Composable
fun FaceAwareImage(
    imageUrl: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = {},
    error: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    var faceAlignment by remember { mutableStateOf(Alignment.Center) }

    // Detect faces asynchronously when the URL changes
    LaunchedEffect(imageUrl) {
        faceAlignment = detectFaceAlignment(context, imageUrl)
    }

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(imageUrl)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        alignment = faceAlignment,
        loading = { loading() },
        error = { error() },
    )
}

/**
 * Load the image as a bitmap and run face detection to compute an alignment bias.
 *
 * Returns an [Alignment] that biases toward the average face center:
 * - If faces are in the top third → TopCenter
 * - If faces are in the bottom third → BottomCenter
 * - If faces are left/right of center → biased accordingly
 * - Falls back to Center if no faces detected
 */
private suspend fun detectFaceAlignment(
    context: android.content.Context,
    imageUrl: String,
): Alignment = withContext(Dispatchers.IO) {
    try {
        // Load the image as a bitmap using Coil
        val loader = ImageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .allowHardware(false) // ML Kit needs software bitmap
            .build()
        val result = loader.execute(request)
        val bitmap = (result as? SuccessResult)?.drawable?.let { drawable ->
            (drawable as? BitmapDrawable)?.bitmap
        } ?: return@withContext Alignment.Center

        // Run face detection
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        val detector = FaceDetection.getClient(options)
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val faces = detector.process(inputImage).await()

        if (faces.isEmpty()) return@withContext Alignment.Center

        // Compute average face center as fraction of image dimensions
        val avgX = faces.map { it.boundingBox.centerX().toFloat() }.average() / bitmap.width
        val avgY = faces.map { it.boundingBox.centerY().toFloat() }.average() / bitmap.height

        // Convert to alignment bias (-1.0 to 1.0)
        val biasX = ((avgX - 0.5) * 2.0).toFloat().coerceIn(-1f, 1f)
        val biasY = ((avgY - 0.5) * 2.0).toFloat().coerceIn(-1f, 1f)

        Alignment { size, space, _ ->
            val centerX = (space.width - size.width) / 2
            val centerY = (space.height - size.height) / 2
            val offsetX = (centerX * biasX).toInt()
            val offsetY = (centerY * biasY).toInt()
            androidx.compose.ui.unit.IntOffset(centerX + offsetX, centerY + offsetY)
        }
    } catch (_: Exception) {
        Alignment.Center
    }
}
