package com.jarvis.ai.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Wraps Android's system text-to-speech engine with coroutine-safe initialization and playback. */
class TTSEngine(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private var tts: TextToSpeech? = null
    private var initialization: CompletableDeferred<Boolean>? = null
    private var playback: CompletableDeferred<Unit>? = null
    private var configured = false
    private var speed = DEFAULT_SPEED

    /** Initializes the installed Android speech engine and selects the device's current locale. */
    suspend fun initialize() {
        val ready = withContext(Dispatchers.Main.immediate) {
            if (closed.get()) error("Text-to-speech has been shut down.")
            tts?.let { return@withContext initialization ?: error("Text-to-speech initialization was lost.") }
            CompletableDeferred<Boolean>().also { pending ->
                initialization = pending
                tts = TextToSpeech(appContext) { status ->
                    pending.complete(status == TextToSpeech.SUCCESS)
                }
            }
        }
        check(withTimeout(INITIALIZATION_TIMEOUT_MILLIS) { ready.await() }) {
            "No usable Android text-to-speech engine is installed. Install or enable a system TTS voice."
        }
        withContext(Dispatchers.Main.immediate) {
            if (closed.get()) error("Text-to-speech has been shut down.")
            if (!configured) {
                val engine = tts ?: error("Text-to-speech is unavailable.")
                engine.setSpeechRate(speed)
                val localeStatus = engine.setLanguage(Locale.getDefault())
                if (localeStatus == TextToSpeech.LANG_MISSING_DATA ||
                    localeStatus == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    engine.setLanguage(Locale.US)
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        playback?.complete(Unit)
                    }

                    override fun onError(utteranceId: String?) {
                        playback?.completeExceptionally(
                            IllegalStateException("Android speech synthesis failed."),
                        )
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
                })
                configured = true
            }
        }
    }

    /** Speaks a short response and suspends until the utterance finishes. */
    suspend fun speak(text: String, speechRate: Float = speed) {
        val utterance = text.trim().take(MAX_UTTERANCE_CHARS)
        if (utterance.isBlank()) return
        initialize()
        val completion = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main.immediate) {
            check(!closed.get()) { "Text-to-speech has been shut down." }
            val engine = tts ?: error("Text-to-speech is unavailable.")
            speed = speechRate.coerceIn(MIN_SPEED, MAX_SPEED)
            engine.setSpeechRate(speed)
            playback = completion
            val result = engine.speak(
                utterance,
                TextToSpeech.QUEUE_FLUSH,
                Bundle(),
                "jarvis-${UUID.randomUUID()}",
            )
            if (result != TextToSpeech.SUCCESS) {
                playback = null
                completion.completeExceptionally(IllegalStateException("Android could not start speech synthesis."))
            }
        }
        try {
            withTimeout(SPEECH_TIMEOUT_MILLIS) { completion.await() }
        } finally {
            if (playback === completion) playback = null
        }
    }

    /** Stops playback and releases Android's TTS resources. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val cleanup = {
            playback?.cancel()
            playback = null
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) cleanup()
        else android.os.Handler(android.os.Looper.getMainLooper()).post { cleanup() }
    }

    private companion object {
        const val DEFAULT_SPEED = 1.0f
        const val MIN_SPEED = 0.65f
        const val MAX_SPEED = 1.35f
        const val MAX_UTTERANCE_CHARS = 2_000
        const val INITIALIZATION_TIMEOUT_MILLIS = 10_000L
        const val SPEECH_TIMEOUT_MILLIS = 90_000L
    }
}
