package com.jarvis.ai.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.jarvis.ai.memory.ModelStore
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.ArrayDeque
import kotlin.math.sqrt

/** Captures a short utterance and transcribes it offline with Sherpa-ONNX SenseVoice or Whisper. */
class SpeechToTextEngine(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val modelDirectory = ModelStore.modelDirectory(appContext)
    private val operationMutex = Mutex()

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var activeRecorder: AudioRecord? = null

    @Volatile
    private var closed = false

    /** Records until silence is detected, then runs local Sherpa-ONNX decoding. */
    suspend fun listenForCommand(): String = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            check(!closed) { "Speech recognition has stopped." }
            check(
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            ) { "Microphone permission is required. Open JarvisAI and allow microphone access." }

            val activeRecognizer = ensureRecognizer()
            val recording = captureUtterance()
            if (recording.isEmpty()) return@withContext ""

            val stream = activeRecognizer.createStream()
            try {
                stream.acceptWaveform(recording, SAMPLE_RATE)
                activeRecognizer.decode(stream)
                activeRecognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }
    }

    /** Stops any active microphone read and releases Sherpa's native recognizer. */
    fun stopCapture() {
        runCatching { activeRecorder?.stop() }
    }

    override fun close() {
        closed = true
        stopCapture()
        activeRecorder = null
        runCatching { recognizer?.release() }
        recognizer = null
    }

    private fun ensureRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }
        val files = modelDirectory.listFiles()?.filter { it.isFile && it.length() > 0L }.orEmpty()
        val senseVoice = ModelStore.senseVoiceFiles(files)
        val whisper = ModelStore.whisperFiles(files)
        val modelConfig = when {
            senseVoice != null -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = senseVoice.first.absolutePath,
                    language = "en",
                    useInverseTextNormalization = true,
                ),
                tokens = senseVoice.second.absolutePath,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                modelType = "sense_voice",
            )
            whisper != null -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = whisper.first.absolutePath,
                    decoder = whisper.second.absolutePath,
                    language = "en",
                    task = "transcribe",
                    tailPaddings = 1_000,
                ),
                tokens = whisper.third.absolutePath,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                modelType = "whisper",
            )
            else -> throw IllegalStateException(
                "Speech model files are missing. Import a Sherpa-ONNX SenseVoice model and tokens.txt, " +
                    "or a Whisper encoder, decoder, and tokens file from the dashboard.",
            )
        }
        return OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig = modelConfig,
            ),
        ).also { recognizer = it }
    }

    private fun captureUtterance(): FloatArray {
        val minimumBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBufferBytes > 0) { "This device does not support 16 kHz microphone capture." }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimumBufferBytes, FRAME_SAMPLES * 4),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "Android could not initialize the microphone. Check device privacy settings."
        }
        activeRecorder = recorder

        val captured = ArrayList<Float>(SAMPLE_RATE * MAX_RECORDING_SECONDS)
        val preRoll = ArrayDeque<FloatArray>()
        val pcm = ShortArray(FRAME_SAMPLES)
        var started = false
        var silentSamples = 0
        var totalSamples = 0
        try {
            recorder.startRecording()
            while (!closed && totalSamples < SAMPLE_RATE * MAX_RECORDING_SECONDS) {
                val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (count < 0) {
                    if (count == AudioRecord.ERROR_DEAD_OBJECT) error("The microphone was disconnected.")
                    error("Android microphone capture failed ($count).")
                }
                if (count == 0) continue
                totalSamples += count
                var energy = 0.0
                val chunk = FloatArray(count) { index ->
                    val sample = pcm[index] / 32768.0f
                    energy += sample * sample
                    sample
                }
                val rms = sqrt(energy / count).toFloat()
                if (!started) {
                    preRoll.addLast(chunk)
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    if (rms >= SPEECH_RMS_THRESHOLD) {
                        started = true
                        preRoll.forEach { captured.addAll(it.asIterable()) }
                        preRoll.clear()
                        silentSamples = 0
                    }
                } else {
                    captured.addAll(chunk.asIterable())
                    if (rms >= SPEECH_RMS_THRESHOLD) silentSamples = 0 else silentSamples += count
                    if (silentSamples >= SILENCE_SAMPLES) break
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            activeRecorder = null
        }

        if (!started || captured.isEmpty()) return FloatArray(0)
        return captured.toFloatArray()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val FRAME_SAMPLES = 1_600 // 100 ms
        const val PRE_ROLL_FRAMES = 3
        const val SILENCE_SAMPLES = 12_800 // 800 ms
        const val MAX_RECORDING_SECONDS = 20
        const val SPEECH_RMS_THRESHOLD = 0.012f
    }
}
