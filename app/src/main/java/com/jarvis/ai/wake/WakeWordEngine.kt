package com.jarvis.ai.wake

import android.content.Context
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.jarvis.ai.memory.ModelStore
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.exp

/** Runs the streaming openWakeWord ONNX feature, embedding, and Jarvis classifier pipeline. */
class WakeWordEngine(context: Context) : Closeable {
    private val modelDirectory = ModelStore.modelDirectory(context.applicationContext)
    private val inferenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-wake-inference").apply { priority = Thread.MIN_PRIORITY }
    }
    private val inferenceDispatcher: ExecutorCoroutineDispatcher = inferenceExecutor.asCoroutineDispatcher()
    private val rawAudioRing = ShortArray(RAW_AUDIO_HISTORY_SAMPLES)
    private val melFrames = ArrayDeque<FloatArray>()
    private val embeddingWindow = ArrayDeque<FloatArray>()

    @Volatile
    var unavailableReason: String? = null
        private set

    @Volatile
    var isReady: Boolean = false
        private set

    private var environment: OrtEnvironment? = null
    private var melSession: OrtSession? = null
    private var embeddingSession: OrtSession? = null
    private var wakeSession: OrtSession? = null
    private var rawWriteIndex = 0
    private var rawSamplesAvailable = 0
    private var framesSinceStart = 0
    private var lastDetectionAt = 0L
    private var rearmed = true

    /** Loads compatible openWakeWord models from private storage and reports missing files calmly. */
    suspend fun initialize(): Boolean = withContext(inferenceDispatcher) {
        if (isReady) return@withContext true
        val files = modelDirectory.listFiles()
            ?.filter { it.isFile && it.extension.equals("onnx", ignoreCase = true) }
            .orEmpty()
        val melFile = files.firstOrNull { it.name.equals(ModelStore.MEL_MODEL, true) }
        val embeddingFile = files.firstOrNull { it.name.equals(ModelStore.EMBEDDING_MODEL, true) }
        val wakeFile = ModelStore.wakeModelFile(files)
        if (melFile == null || embeddingFile == null || wakeFile == null) {
            unavailableReason =
                "Wake-word setup needs melspectrogram.onnx, embedding_model.onnx, and a Hey Jarvis classifier. Import them from the dashboard."
            return@withContext false
        }

        try {
            val ort = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            try {
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                melSession = ort.createSession(melFile.absolutePath, options)
                embeddingSession = ort.createSession(embeddingFile.absolutePath, options)
                wakeSession = ort.createSession(wakeFile.absolutePath, options)
            } finally {
                options.close()
            }
            environment = ort
            resetStreamingState()
            unavailableReason = null
            isReady = true
            true
        } catch (error: Exception) {
            closeSessions()
            unavailableReason = "Could not load the wake-word models: ${error.message ?: "incompatible ONNX files"}"
            false
        }
    }

    /** Scores one 80 ms, 16 kHz mono PCM frame on a dedicated low-priority inference thread. */
    suspend fun detect(samples: ShortArray): Boolean = withContext(inferenceDispatcher) {
        if (!isReady || samples.size != FRAME_SAMPLES) return@withContext false
        val ort = environment ?: return@withContext false
        val mel = melSession ?: return@withContext false
        val embedding = embeddingSession ?: return@withContext false
        val classifier = wakeSession ?: return@withContext false

        appendRawAudio(samples)
        val melInputInfo = mel.inputInfo.values.first().info as TensorInfo
        val fixedMelInputSize = fixedElementCount(melInputInfo.shape)
        val preferredInputSize = minOf(rawSamplesAvailable, FRAME_SAMPLES + MEL_OVERLAP_SAMPLES)
        val melInputSize = if (fixedMelInputSize != null && fixedMelInputSize > 1) {
            minOf(rawSamplesAvailable, fixedMelInputSize)
        } else {
            preferredInputSize
        }
        val audioInput = recentRawAudio(melInputSize)
        val melShape = modelShape(
            melInputInfo.shape,
            longArrayOf(1, audioInput.size.toLong()),
            audioInput.size,
        )
        // openWakeWord's ONNX mel frontend expects PCM values in the original int16 scale.
        val melOutput = runModel(
            mel,
            mel.inputNames.first(),
            FloatArray(audioInput.size) { audioInput[it].toFloat() },
            melShape,
            ort,
        )
        appendMelSpectrogram(melOutput)

        val embeddingInfo = embedding.inputInfo.values.first().info as TensorInfo
        val embeddingShape = modelShape(
            embeddingInfo.shape,
            longArrayOf(1, MEL_CONTEXT_FRAMES.toLong(), MEL_BINS.toLong(), 1),
            expectedSize = MEL_CONTEXT_FRAMES * MEL_BINS,
        )
        val melWindow = recentMelWindow()
        val embeddingValues = runModel(
            embedding,
            embedding.inputNames.first(),
            melWindow,
            embeddingShape,
            ort,
        )
        if (embeddingValues.isEmpty()) return@withContext false

        val classifierInfo = classifier.inputInfo.values.first().info as TensorInfo
        val classifierShape = modelShape(
            classifierInfo.shape,
            longArrayOf(1, WAKE_WINDOW.toLong(), EMBEDDING_SIZE.toLong()),
            expectedSize = WAKE_WINDOW * EMBEDDING_SIZE,
        )
        val sequenceSize = classifierShape[classifierShape.lastIndex - 1].toInt()
        val vectorSize = classifierShape.last().toInt()
        embeddingWindow.addLast(fitVector(embeddingValues, vectorSize))
        while (embeddingWindow.size > sequenceSize) embeddingWindow.removeFirst()
        framesSinceStart++
        if (framesSinceStart < STARTUP_WARMUP_FRAMES) return@withContext false

        val classifierInput = FloatArray(sequenceSize * vectorSize)
        var offset = 0
        embeddingWindow.forEach { vector ->
            vector.copyInto(classifierInput, offset)
            offset += vector.size
        }
        val scoreTensor = runModel(
            classifier,
            classifier.inputNames.first(),
            classifierInput,
            classifierShape,
            ort,
        )
        val rawScore = scoreTensor.firstOrNull() ?: return@withContext false
        val score = if (rawScore in 0.0f..1.0f) rawScore else (1.0f / (1.0f + exp(-rawScore)))
        if (score < REARM_THRESHOLD) rearmed = true

        val now = SystemClock.elapsedRealtime()
        if (score >= SCORE_THRESHOLD && rearmed && now - lastDetectionAt >= DETECTION_COOLDOWN_MILLIS) {
            lastDetectionAt = now
            rearmed = false
            true
        } else {
            false
        }
    }

    /** Queues ONNX session cleanup on the same inference thread used for model calls. */
    override fun close() {
        isReady = false
        runCatching {
            inferenceExecutor.execute {
                closeSessions()
                inferenceDispatcher.close()
                inferenceExecutor.shutdown()
            }
        }.onFailure {
            closeSessions()
            inferenceDispatcher.close()
        }
    }

    private fun appendRawAudio(samples: ShortArray) {
        samples.forEach { sample ->
            rawAudioRing[rawWriteIndex] = sample
            rawWriteIndex = (rawWriteIndex + 1) % rawAudioRing.size
            rawSamplesAvailable = (rawSamplesAvailable + 1).coerceAtMost(rawAudioRing.size)
        }
    }

    private fun recentRawAudio(count: Int): ShortArray {
        val size = count.coerceIn(0, rawSamplesAvailable)
        val output = ShortArray(size)
        val start = (rawWriteIndex - size + rawAudioRing.size) % rawAudioRing.size
        for (index in 0 until size) output[index] = rawAudioRing[(start + index) % rawAudioRing.size]
        return output
    }

    private fun appendMelSpectrogram(values: FloatArray) {
        check(values.isNotEmpty() && values.size % MEL_BINS == 0) {
            "The openWakeWord mel model must output frames with $MEL_BINS bins."
        }
        values.indices.step(MEL_BINS).forEach { offset ->
            val frame = FloatArray(MEL_BINS) { bin -> values[offset + bin] / 10.0f + 2.0f }
            melFrames.addLast(frame)
        }
        while (melFrames.size > MAX_MEL_FRAMES) melFrames.removeFirst()
    }

    private fun recentMelWindow(): FloatArray {
        check(melFrames.size >= MEL_CONTEXT_FRAMES) { "Not enough mel context to run openWakeWord." }
        val output = FloatArray(MEL_CONTEXT_FRAMES * MEL_BINS)
        val iterator = melFrames.descendingIterator()
        for (frameIndex in MEL_CONTEXT_FRAMES - 1 downTo 0) {
            val frame = iterator.next()
            frame.copyInto(output, frameIndex * MEL_BINS)
        }
        return output
    }

    private fun runModel(
        session: OrtSession,
        inputName: String,
        input: FloatArray,
        shape: LongArray,
        environment: OrtEnvironment,
    ): FloatArray {
        val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape)
        try {
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result[0] as? OnnxTensor ?: return FloatArray(0)
                val buffer = output.floatBuffer.duplicate()
                return FloatArray(buffer.remaining()).also { copy -> buffer.get(copy) }
            }
        } finally {
            tensor.close()
        }
    }

    private fun modelShape(original: LongArray, fallback: LongArray, expectedSize: Int?): LongArray {
        var shape = original.copyOf()
        if (shape.isEmpty()) shape = fallback.copyOf()
        val dynamicIndices = shape.indices.filter { shape[it] <= 0L }
        if (dynamicIndices.isNotEmpty()) {
            if (dynamicIndices.size == 1 && expectedSize != null) {
                val knownProduct = shape.indices.filterNot { it in dynamicIndices }
                    .fold(1L) { product, index -> product * shape[index].coerceAtLeast(1L) }
                shape[dynamicIndices.single()] = (expectedSize / knownProduct).coerceAtLeast(1L)
            } else {
                shape = fallback.copyOf()
            }
        }
        if (expectedSize != null && elementCount(shape) != expectedSize.toLong()) {
            if (elementCount(fallback) == expectedSize.toLong()) shape = fallback.copyOf()
            else throw IllegalArgumentException("ONNX input shape does not match the model tensor size.")
        }
        return shape
    }

    private fun fixedElementCount(shape: LongArray): Int? {
        if (shape.isEmpty() || shape.any { it <= 0L }) return null
        val count = elementCount(shape)
        return count.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun elementCount(shape: LongArray): Long =
        shape.fold(1L) { product, dimension -> product * dimension }

    private fun fitVector(values: FloatArray, size: Int): FloatArray = when {
        values.size == size -> values
        values.size > size -> values.copyOfRange(values.size - size, values.size)
        else -> FloatArray(size).also { values.copyInto(it) }
    }

    private fun resetStreamingState() {
        rawWriteIndex = 0
        rawSamplesAvailable = 0
        framesSinceStart = 0
        lastDetectionAt = 0L
        rearmed = true
        rawAudioRing.fill(0)
        melFrames.clear()
        repeat(MEL_CONTEXT_FRAMES) { melFrames.addLast(FloatArray(MEL_BINS) { 1.0f }) }
        embeddingWindow.clear()
        repeat(WAKE_WINDOW) { embeddingWindow.addLast(FloatArray(EMBEDDING_SIZE)) }
    }

    private fun closeSessions() {
        runCatching { wakeSession?.close() }
        runCatching { embeddingSession?.close() }
        runCatching { melSession?.close() }
        wakeSession = null
        embeddingSession = null
        melSession = null
        environment = null
        resetStreamingState()
    }

    private companion object {
        const val FRAME_SAMPLES = 1_280
        const val MEL_OVERLAP_SAMPLES = 480
        const val RAW_AUDIO_HISTORY_SAMPLES = 160_000
        const val MEL_CONTEXT_FRAMES = 76
        const val MEL_BINS = 32
        const val MAX_MEL_FRAMES = 970
        const val EMBEDDING_SIZE = 96
        const val WAKE_WINDOW = 16
        const val STARTUP_WARMUP_FRAMES = 5
        const val SCORE_THRESHOLD = 0.50f
        const val REARM_THRESHOLD = 0.20f
        const val DETECTION_COOLDOWN_MILLIS = 2_500L
    }
}
