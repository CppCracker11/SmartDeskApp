package com.smartdesk.controller

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val CLICK_TAG = "SD_CLICK"

private data class GestureHud(
    val handDetected: Boolean = false,
    val mode: GestureMode = GestureMode.CURSOR,
    val pinchRatio: Float = 0f,
    val pinching: Boolean = false,
    val lastEvent: String = "-"
)

/** Translates one interpreted frame into commands on the existing connection. */
private fun dispatchToDesktop(
    state: GestureState,
    connection: SmartDeskConnection
) {
    if (!connection.isConnected()) return

    when (state.mode) {
        GestureMode.CURSOR -> {
            if (state.moveDx != 0 || state.moveDy != 0) {
                connection.sendMouseMove(state.moveDx, state.moveDy)
            }

            when (state.pinchEvent) {
                PinchEvent.PRESSED -> {
                    Log.d(CLICK_TAG, "leftClick reason=PRESSED")
                    connection.leftClick()
                }

                PinchEvent.HOLD_SECOND_CLICK -> {
                    Log.d(CLICK_TAG, "leftClick reason=HOLD_SECOND_CLICK")
                    connection.leftClick()
                }

                else -> Unit
            }
        }

        GestureMode.SCROLL -> {
            if (state.scrollAmount != 0) {
                connection.scroll(0, state.scrollAmount)
            }
        }
    }
}

@Composable
fun GestureCameraPreview(
    connection: SmartDeskConnection,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val connectionState = rememberUpdatedState(connection)

    var hud by remember { mutableStateOf(GestureHud()) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode =
                PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        val providerFuture =
            ProcessCameraProvider.getInstance(context)

        val analysisExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)

        // A fresh interpreter per camera session: no state leaks between sessions.
        val interpreter = GestureInterpreter(GestureConfig())

        val disposed = AtomicBoolean(false)
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        var gestureAnalyzer: GestureAnalyzer? = null

        // Only touched from the hand-landmarker thread.
        var lastHudPostMs = 0L
        var lastEventLabel = "-"

        providerFuture.addListener(
            {
                if (disposed.get()) return@addListener

                try {
                    val cameraProvider = providerFuture.get()
                    provider = cameraProvider

                    val frameAnalyzerRef =
                        AtomicReference<GestureFrameAnalyzer?>(null)

                    val handAnalyzer = GestureAnalyzer(
                        context = context,
                        onResult = { frame ->
                            val state = interpreter.interpret(
                                landmarks = frame.landmarks,
                                frameWidth = frame.frameWidth,
                                frameHeight = frame.frameHeight,
                                nowMs = frame.timestampMs
                            )

                            dispatchToDesktop(state, connectionState.value)

                            val eventLabel = when (state.pinchEvent) {
                                PinchEvent.PRESSED -> "Click"
                                PinchEvent.HOLD_SECOND_CLICK -> "Second click"
                                PinchEvent.RELEASED -> "Released"
                                PinchEvent.NONE -> null
                            }
                            if (eventLabel != null) lastEventLabel = eventLabel

                            if (eventLabel != null ||
                                frame.timestampMs - lastHudPostMs >= 100L
                            ) {
                                lastHudPostMs = frame.timestampMs

                                val snapshot = GestureHud(
                                    handDetected = state.handDetected,
                                    mode = state.mode,
                                    pinchRatio = state.pinchRatio,
                                    pinching = state.pinchDetected,
                                    lastEvent = lastEventLabel
                                )

                                mainExecutor.execute {
                                    if (!disposed.get()) hud = snapshot
                                }
                            }
                        },
                        onError = { message ->
                            mainExecutor.execute {
                                if (!disposed.get()) cameraError = message
                            }
                        },
                        onFrameProcessed = {
                            frameAnalyzerRef.get()?.onFrameProcessed()
                        }
                    )
                    gestureAnalyzer = handAnalyzer

                    val previewUseCase = Preview.Builder()
                        .build()
                        .also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                    preview = previewUseCase

                    val analysisUseCase = ImageAnalysis.Builder()
                        .setOutputImageFormat(
                            ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                        )
                        .setBackpressureStrategy(
                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .build()
                    analysis = analysisUseCase

                    val frameAnalyzer = GestureFrameAnalyzer(
                        gestureAnalyzer = handAnalyzer,
                        onDetection = { _ -> },
                        onError = { message ->
                            mainExecutor.execute {
                                if (!disposed.get()) cameraError = message
                            }
                        }
                    )
                    frameAnalyzerRef.set(frameAnalyzer)

                    analysisUseCase.setAnalyzer(
                        analysisExecutor,
                        frameAnalyzer
                    )

                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        previewUseCase,
                        analysisUseCase
                    )

                    cameraError = null
                } catch (exception: Exception) {
                    cameraError =
                        exception.localizedMessage
                            ?: "Unable to start camera detection."
                }
            },
            mainExecutor
        )

        onDispose {
            disposed.set(true)
            analysis?.clearAnalyzer()
            analysis?.let { provider?.unbind(it) }
            preview?.let { provider?.unbind(it) }
            gestureAnalyzer?.close()
            analysisExecutor.shutdown()
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (hud.handDetected) "Hand detected" else "No hand detected",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Mode: " + when (hud.mode) {
                    GestureMode.CURSOR -> "MOVE / CLICK"
                    GestureMode.SCROLL -> "SCROLL"
                },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "Pinch: ${if (hud.pinching) "ACTIVE" else "inactive"} " +
                        "(ratio ${"%.2f".format(hud.pinchRatio)})",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = "Last event: ${hud.lastEvent}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )

            cameraError?.let { error ->
                Text(
                    text = error,
                    color = Color(0xFFFF8A80),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}