package com.jarvis.ai.gesture

import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.jarvis.ai.memory.ModelStore
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot

/** Runs MediaPipe Hand Landmarker and emits an index-tip position plus simple hand shortcuts. */
class GestureEngine(
    context: Context,
    private val onFrame: (GestureFrame) -> Unit,
    private val onFailure: (String) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private var landmarker: HandLandmarker? = null
    private var lastTimestampMs = 0L
    private var previousGesture = HandGesture.NONE
    private var lastPinchAtMs = 0L

    /** Loads an imported MediaPipe task model from private app storage. */
    fun initialize() {
        val modelFile = File(ModelStore.modelDirectory(appContext), ModelStore.HAND_MODEL)
        check(modelFile.isFile && modelFile.length() > MIN_TASK_FILE_BYTES) {
            "Import hand_landmarker.task from the JarvisAI dashboard before enabling Air Touch."
        }
        val modelBuffer = ByteBuffer.allocateDirect(modelFile.length().toInt())
            .order(ByteOrder.nativeOrder())
        modelFile.inputStream().use { input ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                modelBuffer.put(bytes, 0, count)
            }
        }
        modelBuffer.rewind()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(modelBuffer).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.55f)
            .setMinHandPresenceConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .build()
        landmarker = HandLandmarker.createFromOptions(appContext, options)
    }

    /** Processes and closes one CameraX frame; MediaPipe work stays on CameraX's analyzer thread. */
    @OptIn(ExperimentalGetImage::class)
    fun process(imageProxy: ImageProxy) {
        val activeLandmarker = landmarker
        val mediaImage = imageProxy.image
        if (activeLandmarker == null || mediaImage == null) {
            imageProxy.close()
            return
        }
        var mpImage: com.google.mediapipe.framework.image.MPImage? = null
        try {
            mpImage = MediaImageBuilder(mediaImage).build()
            val timestamp = maxOf(SystemClock.uptimeMillis(), lastTimestampMs + 1)
            lastTimestampMs = timestamp
            val imageOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
                .build()
            val result = activeLandmarker.detectForVideo(mpImage, imageOptions, timestamp)
            val landmarks = result.landmarks().firstOrNull()
            if (landmarks == null || landmarks.size < REQUIRED_LANDMARKS) {
                emit( GestureFrame(point = null, gesture = HandGesture.NONE) )
            } else {
                val positions = landmarks.map { PointF(it.x(), it.y()) }
                val indexTip = positions[INDEX_TIP]
                emit(
                    GestureFrame(
                        point = PointF(indexTip.x.coerceIn(0f, 1f), indexTip.y.coerceIn(0f, 1f)),
                        gesture = classify(positions),
                    ),
                )
            }
        } catch (error: Exception) {
            onFailure(error.message ?: "Hand tracking could not process a camera frame.")
        } finally {
            runCatching { mpImage?.close() }
            imageProxy.close()
        }
    }

    /** Releases MediaPipe's native model resources. */
    override fun close() {
        runCatching { landmarker?.close() }
        landmarker = null
    }

    private fun classify(points: List<PointF>): HandGesture {
        val wrist = points[WRIST]
        val palmScale = distance(wrist, points[MIDDLE_MCP]).coerceAtLeast(0.001f)
        val pinch = distance(points[THUMB_TIP], points[INDEX_TIP]) / palmScale < PINCH_RATIO
        if (pinch) return HandGesture.PINCH_TAP

        val extendedFingers = FINGER_TIPS.indices.count { index ->
            distance(wrist, points[FINGER_TIPS[index]]) >
                distance(wrist, points[FINGER_MCPS[index]]) * FINGER_EXTENSION_RATIO
        }
        return when {
            extendedFingers == 0 -> HandGesture.FIST
            extendedFingers >= 4 -> HandGesture.OPEN_PALM
            else -> HandGesture.NONE
        }
    }

    private fun emit(frame: GestureFrame) {
        val now = SystemClock.elapsedRealtime()
        val gesture = if (
            frame.gesture == HandGesture.PINCH_TAP &&
            previousGesture != HandGesture.PINCH_TAP &&
            now - lastPinchAtMs >= PINCH_DEBOUNCE_MILLIS
        ) {
            lastPinchAtMs = now
            HandGesture.PINCH_TAP
        } else if (frame.gesture == HandGesture.PINCH_TAP) {
            HandGesture.NONE
        } else {
            frame.gesture
        }
        previousGesture = frame.gesture
        onFrame(frame.copy(gesture = gesture))
    }

    private fun distance(first: PointF, second: PointF): Float =
        hypot(first.x - second.x, first.y - second.y)

    private companion object {
        const val MIN_TASK_FILE_BYTES = 1_024L
        const val REQUIRED_LANDMARKS = 21
        const val WRIST = 0
        const val THUMB_TIP = 4
        const val INDEX_TIP = 8
        const val MIDDLE_MCP = 9
        const val FINGER_TIPS = intArrayOf(8, 12, 16, 20)
        const val FINGER_MCPS = intArrayOf(5, 9, 13, 17)
        const val PINCH_RATIO = 0.32f
        const val FINGER_EXTENSION_RATIO = 1.16f
        const val PINCH_DEBOUNCE_MILLIS = 450L
    }
}

/** A normalized index-tip coordinate and the recognized shortcut for one camera frame. */
data class GestureFrame(
    val point: PointF?,
    val gesture: HandGesture,
)

/** Shortcut classes supported by Jarvis Air Touch. */
enum class HandGesture {
    NONE,
    PINCH_TAP,
    FIST,
    OPEN_PALM,
}
