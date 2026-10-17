package com.smartdesk.controller

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * One processed camera frame. frameWidth/frameHeight are the dimensions of the
 * upright (rotation already applied) image the landmarks refer to.
 */
data class HandFrame(
    val handCount: Int,
    val landmarks: List<NormalizedLandmark>,
    val frameWidth: Int,
    val frameHeight: Int,
    val timestampMs: Long
)

class GestureAnalyzer(
    context: Context,
    private val onResult: (HandFrame) -> Unit,
    private val onError: (String) -> Unit,
    private val onFrameProcessed: () -> Unit
) : AutoCloseable {

    @Volatile
    private var closed = false

    @Volatile
    private var pendingBitmap: Bitmap? = null

    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    private var lastTimestamp = 0L

    private val landmarker: HandLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.35f)
            .setMinHandPresenceConfidence(0.35f)
            .setMinTrackingConfidence(0.35f)
            .setResultListener { result: HandLandmarkerResult, _ ->
                try {
                    if (!closed) {
                        val hands = result.landmarks()

                        onResult(
                            HandFrame(
                                handCount = hands.size,
                                landmarks = hands.firstOrNull() ?: emptyList(),
                                frameWidth = frameWidth,
                                frameHeight = frameHeight,
                                timestampMs = result.timestampMs()
                            )
                        )
                    }
                } catch (exception: Exception) {
                    if (!closed) {
                        onError(
                            exception.localizedMessage
                                ?: "Gesture processing failed."
                        )
                    }
                } finally {
                    pendingBitmap = null
                    if (!closed) onFrameProcessed()
                }
            }
            .setErrorListener { error ->
                try {
                    if (!closed) {
                        onError(
                            error.message ?: "Hand detection failed."
                        )
                    }
                } finally {
                    pendingBitmap = null
                    if (!closed) onFrameProcessed()
                }
            }
            .build()

        landmarker = HandLandmarker.createFromOptions(
            context.applicationContext,
            options
        )
    }

    fun detect(bitmap: Bitmap, rotationDegrees: Int = 0) {
        if (closed) {
            onFrameProcessed()
            return
        }

        try {
            val timestamp = maxOf(
                SystemClock.uptimeMillis(),
                lastTimestamp + 1
            )
            lastTimestamp = timestamp
            pendingBitmap = bitmap

            val rotated = ((rotationDegrees % 180) + 180) % 180 != 0
            frameWidth = if (rotated) bitmap.height else bitmap.width
            frameHeight = if (rotated) bitmap.width else bitmap.height

            val image = BitmapImageBuilder(bitmap).build()

            val processingOptions =
                ImageProcessingOptions.builder()
                    .setRotationDegrees(rotationDegrees)
                    .build()

            landmarker.detectAsync(
                image,
                processingOptions,
                timestamp
            )
        } catch (exception: Exception) {
            pendingBitmap = null

            if (!closed) {
                onError(
                    exception.localizedMessage
                        ?: "Unable to process camera frame."
                )
                onFrameProcessed()
            }
        }
    }

    override fun close() {
        if (closed) return

        closed = true
        pendingBitmap = null

        try {
            landmarker.close()
        } catch (_: Exception) {
        }
    }
}