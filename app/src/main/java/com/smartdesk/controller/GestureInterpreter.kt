package com.smartdesk.controller

import android.os.SystemClock
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

enum class PinchEvent {
    NONE,
    PRESSED,
    RELEASED,
    HOLD_SECOND_CLICK
}

enum class GestureMode {
    CURSOR,
    SCROLL
}

/**
 * All tunable values live here. Units:
 *  - "palm widths" (pw): hand movement is measured relative to the hand's own
 *    size so behaviour is the same at any distance from the camera.
 */
data class GestureConfig(
    // ---- Cursor ----
    /** Set to false if the cursor moves the wrong way horizontally. */
    val mirrorX: Boolean = true,
    /** Pixels per palm-width of hand travel at slow speed (precision). */
    val cursorGainLow: Float = 100f,
    /** Pixels per palm-width of hand travel at fast speed (reach). */
    val cursorGainHigh: Float = 350f,
    /** Hand speed (palm widths / second) where acceleration starts / ends. */
    val cursorSpeedLow: Float = 0.5f,
    val cursorSpeedHigh: Float = 2.5f,
    /** Safety clamp for a single mouse-move message. */
    val maxStepPx: Int = 70,
    /** A one-frame jump larger than this many palm widths is treated as a glitch. */
    val maxJumpPalmWidths: Float = 2.0f,

    // ---- Scroll (rate control: offset from start point = speed) ----
    /** true: hand above start point sends positive dy (same as the old code). */
    val scrollUpIsPositive: Boolean = true,
    /** Offset (palm widths) inside which nothing scrolls. */
    val scrollDeadZone: Float = 0.25f,
    /** Offset (palm widths) at which scrolling reaches max speed. */
    val scrollFullDeflection: Float = 1.2f,
    /** Scroll units per second just outside the dead zone / at full deflection. */
    val scrollMinRate: Float = 60f,
    val scrollMaxRate: Float = 800f,

    // ---- Pinch / click ----
    val pinchStartRatio: Float = 0.35f,
    val pinchReleaseRatio: Float = 0.55f,
    val pinchConfirmFrames: Int = 2,
    val pinchReleaseFrames: Int = 2,
    val holdSecondClickMs: Long = 500L,
    /** Minimum time between a release and the next possible press. */
    val minPinchGapMs: Long = 150L,
    /** Cursor stays frozen this long after a release. */
    val freezeAfterReleaseMs: Long = 150L,

    // ---- Pose detection ----
    /** tip-to-wrist / pip-to-wrist distance above this = finger extended. */
    val fingerExtendRatio: Float = 1.25f,
    /** ... below this = finger folded (in between keeps previous state). */
    val fingerFoldRatio: Float = 0.95f,
    val scrollPoseFrames: Int = 5,
    val cursorPoseFrames: Int = 4,

    // ---- Tracking loss ----
    val handLostGraceMs: Long = 250L
)

data class GestureState(
    val handDetected: Boolean,
    val mode: GestureMode = GestureMode.CURSOR,
    val moveDx: Int = 0,
    val moveDy: Int = 0,
    val scrollAmount: Int = 0,
    val pinchRatio: Float = 1f,
    val pinchDetected: Boolean = false,
    val pinchEvent: PinchEvent = PinchEvent.NONE
)

class GestureInterpreter(
    private val config: GestureConfig = GestureConfig()
) {

    /** Adaptive low-pass (One Euro style): steady when slow, low-lag when fast. */
    private class SmoothingFilter(
        private val minCutoff: Float,
        private val beta: Float,
        private val derivativeCutoff: Float
    ) {
        var isInitialized = false
            private set
        var x = 0f
            private set
        var y = 0f
            private set
        private var dx = 0f
        private var dy = 0f

        fun reset() {
            isInitialized = false
            dx = 0f
            dy = 0f
        }

        fun update(rawX: Float, rawY: Float, dt: Float) {
            if (!isInitialized) {
                isInitialized = true
                x = rawX
                y = rawY
                dx = 0f
                dy = 0f
                return
            }

            val derivativeAlpha = alpha(dt, derivativeCutoff)
            dx += derivativeAlpha * ((rawX - x) / dt - dx)
            dy += derivativeAlpha * ((rawY - y) / dt - dy)

            val cutoff = minCutoff + beta * hypot(dx, dy)
            val a = alpha(dt, cutoff)
            x += a * (rawX - x)
            y += a * (rawY - y)
        }

        private fun alpha(dt: Float, cutoff: Float): Float {
            val tau = 1f / (2f * PI.toFloat() * cutoff)
            return 1f / (1f + tau / dt)
        }
    }

    private val filter = SmoothingFilter(
        minCutoff = 1.2f,
        beta = 20f,
        derivativeCutoff = 2.0f
    )

    // Mode / pose
    private var mode = GestureMode.CURSOR
    private var poseCandidate = GestureMode.CURSOR
    private var poseFrames = 0
    // index, middle, ring, pinky
    private val fingerExtended = BooleanArray(4)

    // Timing / tracking
    private var lastFrameMs = 0L
    private var lostSinceMs = -1L
    private var palmScale = 0f
    private var speedEma = 0f

    // Cursor
    private var moveAccX = 0f
    private var moveAccY = 0f

    // Scroll
    private var scrollAnchorY: Float? = null
    private var scrollAcc = 0f

    // Pinch
    private var isPinching = false
    private var armed = false
    private var openFrames = 0
    private var pinchCandidateFrames = 0
    private var pinchCandidateStartMs = 0L
    private var pinchStartMs = 0L
    private var releaseFrames = 0
    private var secondClickSent = false
    private var nextPinchAllowedMs = 0L
    private var freezeUntilMs = 0L

    fun interpret(
        landmarks: List<NormalizedLandmark>,
        frameWidth: Int,
        frameHeight: Int,
        nowMs: Long = SystemClock.uptimeMillis()
    ): GestureState {
        if (landmarks.size < 21) return handLost(nowMs)

        // Work in isotropic "frame-width" units: x as is, y scaled by aspect.
        val aspect =
            if (frameWidth > 0 && frameHeight > 0) frameHeight.toFloat() / frameWidth
            else 0.75f

        fun px(i: Int) = landmarks[i].x()
        fun py(i: Int) = landmarks[i].y() * aspect
        fun dist(a: Int, b: Int) = hypot(px(a) - px(b), py(a) - py(b))

        val palmWidth = dist(5, 17)
        val palmLength = dist(0, 9)
        val scale = max(palmWidth, palmLength * 0.85f)

        if (scale < MIN_PALM_SCALE) return handLost(nowMs)

        // ---- Resume after a (brief) loss ----
        val resumed = lostSinceMs >= 0
        if (resumed) {
            lostSinceMs = -1L
            filter.reset()
            moveAccX = 0f
            moveAccY = 0f
            scrollAcc = 0f
            scrollAnchorY = null
            speedEma = 0f
            poseCandidate = mode
            poseFrames = 0
            pinchCandidateFrames = 0
            releaseFrames = 0
        }

        val dt =
            if (lastFrameMs == 0L || resumed) 0.033f
            else ((nowMs - lastFrameMs) / 1000f).coerceIn(0.008f, 0.1f)
        lastFrameMs = nowMs

        palmScale =
            if (palmScale <= 0f) scale
            else palmScale + 0.1f * (scale - palmScale)

        // ---- Palm centre (unaffected by finger movement) ----
        val centerX = (px(0) + px(5) + px(9) + px(13) + px(17)) / 5f
        val centerY = (py(0) + py(5) + py(9) + py(13) + py(17)) / 5f

        if (filter.isInitialized &&
            hypot(centerX - filter.x, centerY - filter.y) >
            config.maxJumpPalmWidths * scale
        ) {
            // Landmark teleport: re-anchor instead of moving the cursor.
            filter.reset()
            moveAccX = 0f
            moveAccY = 0f
            scrollAnchorY = null
            speedEma = 0f
        }

        val hadPrevious = filter.isInitialized
        val previousX = filter.x
        val previousY = filter.y
        filter.update(centerX, centerY, dt)
        val filteredDx = if (hadPrevious) filter.x - previousX else 0f
        val filteredDy = if (hadPrevious) filter.y - previousY else 0f

        // ---- Finger pose (hysteresis per finger) ----
        val tips = intArrayOf(8, 12, 16, 20)
        val pips = intArrayOf(6, 10, 14, 18)
        for (i in 0..3) {
            val ratio = dist(0, tips[i]) / max(dist(0, pips[i]), 0.0001f)
            if (ratio > config.fingerExtendRatio) {
                fingerExtended[i] = true
            } else if (ratio < config.fingerFoldRatio) {
                fingerExtended[i] = false
            }
        }

        val classified =
            if (fingerExtended[0] && fingerExtended[1] &&
                !fingerExtended[2] && !fingerExtended[3]
            ) GestureMode.SCROLL
            else GestureMode.CURSOR

        // Never enter scroll while a pinch is in progress.
        val rawPose =
            if (classified == GestureMode.SCROLL &&
                (isPinching || pinchCandidateFrames > 0)
            ) GestureMode.CURSOR
            else classified

        if (rawPose == poseCandidate) {
            poseFrames++
        } else {
            poseCandidate = rawPose
            poseFrames = 1
        }

        val framesNeeded =
            if (poseCandidate == GestureMode.SCROLL) config.scrollPoseFrames
            else config.cursorPoseFrames

        if (poseCandidate != mode && poseFrames >= framesNeeded) {
            switchMode(poseCandidate)
        }

        // ---- Pinch ----
        val pinchRatio = dist(4, 8) / scale
        var pinchEvent = PinchEvent.NONE

        if (mode == GestureMode.CURSOR) {
            if (!isPinching) {
                if (!armed) {
                    // After start-up, hand loss or a mode change the pinch must
                    // be seen open before it can click again.
                    pinchCandidateFrames = 0
                    if (pinchRatio >= config.pinchReleaseRatio) {
                        openFrames++
                        if (openFrames >= 2) armed = true
                    } else {
                        openFrames = 0
                    }
                } else if (
                    rawPose == GestureMode.CURSOR &&
                    pinchRatio < config.pinchStartRatio &&
                    nowMs >= nextPinchAllowedMs
                ) {
                    if (pinchCandidateFrames == 0) pinchCandidateStartMs = nowMs
                    pinchCandidateFrames++

                    if (pinchCandidateFrames >= config.pinchConfirmFrames) {
                        isPinching = true
                        pinchStartMs = pinchCandidateStartMs
                        secondClickSent = false
                        releaseFrames = 0
                        pinchCandidateFrames = 0
                        pinchEvent = PinchEvent.PRESSED
                    }
                } else {
                    pinchCandidateFrames = 0
                }
            } else {
                if (pinchRatio >= config.pinchReleaseRatio) {
                    releaseFrames++
                    if (releaseFrames >= config.pinchReleaseFrames) {
                        isPinching = false
                        releaseFrames = 0
                        nextPinchAllowedMs = nowMs + config.minPinchGapMs
                        freezeUntilMs = nowMs + config.freezeAfterReleaseMs
                        pinchEvent = PinchEvent.RELEASED
                    }
                } else {
                    releaseFrames = 0
                    if (!secondClickSent &&
                        nowMs - pinchStartMs >= config.holdSecondClickMs
                    ) {
                        secondClickSent = true
                        pinchEvent = PinchEvent.HOLD_SECOND_CLICK
                    }
                }
            }
        }

        // ---- Cursor movement ----
        var moveDx = 0
        var moveDy = 0

        val speed = hypot(filteredDx, filteredDy) / dt / palmScale
        speedEma += 0.5f * (speed - speedEma)

        if (mode == GestureMode.CURSOR) {
            val frozen =
                isPinching ||
                        pinchCandidateFrames > 0 ||
                        nowMs < freezeUntilMs ||
                        rawPose != GestureMode.CURSOR ||
                        resumed

            if (frozen) {
                moveAccX = 0f
                moveAccY = 0f
            } else {
                val gain = cursorGain(speedEma)
                val direction = if (config.mirrorX) -1f else 1f

                moveAccX += (filteredDx / palmScale) * gain * direction
                moveAccY += (filteredDy / palmScale) * gain

                moveDx = moveAccX.toInt()
                moveDy = moveAccY.toInt()
                moveAccX -= moveDx
                moveAccY -= moveDy

                moveDx = moveDx.coerceIn(-config.maxStepPx, config.maxStepPx)
                moveDy = moveDy.coerceIn(-config.maxStepPx, config.maxStepPx)
            }
        }

        // ---- Scroll (offset from start point sets speed) ----
        var scrollAmount = 0

        if (mode == GestureMode.SCROLL) {
            val anchor = scrollAnchorY ?: filter.y.also { scrollAnchorY = it }

            if (rawPose == GestureMode.SCROLL) {
                // Positive = hand above the start point.
                val offset = (anchor - filter.y) / palmScale
                val magnitude = abs(offset)

                if (magnitude > config.scrollDeadZone) {
                    val t = ((magnitude - config.scrollDeadZone) /
                            (config.scrollFullDeflection - config.scrollDeadZone))
                        .coerceIn(0f, 1f)
                    val rate = config.scrollMinRate +
                            (config.scrollMaxRate - config.scrollMinRate) * t
                    val direction = if (config.scrollUpIsPositive) 1f else -1f

                    scrollAcc += sign(offset) * rate * dt * direction
                    scrollAmount = scrollAcc.toInt()
                    scrollAcc -= scrollAmount
                } else {
                    scrollAcc = 0f
                }
            } else {
                // Fingers are changing pose: don't scroll on a half-formed gesture.
                scrollAcc = 0f
            }
        }

        return GestureState(
            handDetected = true,
            mode = mode,
            moveDx = moveDx,
            moveDy = moveDy,
            scrollAmount = scrollAmount,
            pinchRatio = pinchRatio,
            pinchDetected = isPinching,
            pinchEvent = pinchEvent
        )
    }

    fun reset() {
        hardReset()
        lostSinceMs = -1L
        lastFrameMs = 0L
    }

    private fun handLost(nowMs: Long): GestureState {
        if (lostSinceMs < 0) lostSinceMs = nowMs

        val withinGracePeriod =
            nowMs - lostSinceMs <= config.handLostGraceMs

        if (!withinGracePeriod) {
            hardReset()
        }

        return GestureState(
            handDetected = withinGracePeriod,
            mode = mode
        )
    }

    private fun switchMode(newMode: GestureMode) {
        mode = newMode
        moveAccX = 0f
        moveAccY = 0f
        scrollAcc = 0f

        // Silent pinch reset: must see an open hand before the next click.
        isPinching = false
        pinchCandidateFrames = 0
        releaseFrames = 0
        openFrames = 0
        armed = false
        secondClickSent = false

        scrollAnchorY = if (newMode == GestureMode.SCROLL) filter.y else null
    }

    private fun hardReset() {
        mode = GestureMode.CURSOR
        poseCandidate = GestureMode.CURSOR
        poseFrames = 0
        fingerExtended.fill(false)

        filter.reset()
        palmScale = 0f
        speedEma = 0f
        moveAccX = 0f
        moveAccY = 0f
        scrollAnchorY = null
        scrollAcc = 0f

        isPinching = false
        armed = false
        openFrames = 0
        pinchCandidateFrames = 0
        releaseFrames = 0
        secondClickSent = false
        nextPinchAllowedMs = 0L
        freezeUntilMs = 0L
    }

    private fun cursorGain(speed: Float): Float {
        // Ignore tracking noise at rest, accelerate with hand speed.
        val still = smoothstep(0.04f, 0.2f, speed)
        val boost = smoothstep(config.cursorSpeedLow, config.cursorSpeedHigh, speed)
        return (config.cursorGainLow +
                (config.cursorGainHigh - config.cursorGainLow) * boost) * still
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private companion object {
        const val MIN_PALM_SCALE = 0.02f
    }
}