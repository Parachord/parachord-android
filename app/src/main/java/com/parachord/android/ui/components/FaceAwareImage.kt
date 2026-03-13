package com.parachord.android.ui.components

import android.graphics.drawable.BitmapDrawable
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
 * Loads the image and runs face detection BEFORE displaying, so the alignment
 * is correct from the first frame — no visible shift.
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
    var faceAlignment by remember { mutableStateOf<Alignment?>(null) }
    var detectionFailed by remember { mutableStateOf(false) }

    // Pre-load image and detect faces before displaying
    LaunchedEffect(imageUrl) {
        faceAlignment = null
        detectionFailed = false
        faceAlignment = detectFaceAlignment(context, imageUrl)
        if (faceAlignment == null) detectionFailed = true
    }

    if (faceAlignment != null) {
        // Image is cached by Coil from the detection pass — displays instantly with correct alignment
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            alignment = faceAlignment!!,
            error = { error() },
        )
    } else if (detectionFailed) {
        // Detection failed, show with center alignment
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            error = { error() },
        )
    } else {
        // Still loading/detecting — show loading placeholder
        loading()
    }
}

/**
 * Load the image as a bitmap and run face detection to compute an alignment bias.
 * The image will be cached by Coil for instant display afterward.
 */
private suspend fun detectFaceAlignment(
    context: android.content.Context,
    imageUrl: String,
): Alignment? = withContext(Dispatchers.IO) {
    try {
        val loader = ImageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(imageUrl)
            .allowHardware(false)
            .build()
        val result = loader.execute(request)
        val bitmap = (result as? SuccessResult)?.drawable?.let { drawable ->
            (drawable as? BitmapDrawable)?.bitmap
        } ?: return@withContext Alignment.Center

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        val detector = FaceDetection.getClient(options)
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val faces = detector.process(inputImage).await()

        if (faces.isEmpty()) return@withContext Alignment.Center

        val avgX = faces.map { it.boundingBox.centerX().toFloat() }.average() / bitmap.width
        val avgY = faces.map { it.boundingBox.centerY().toFloat() }.average() / bitmap.height

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
        null
    }
}
