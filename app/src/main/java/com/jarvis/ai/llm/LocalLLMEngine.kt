package com.jarvis.ai.llm

import android.content.Context
import android.net.Uri
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.nehuatl.llamacpp.LlamaHelper
import java.io.File

/** Owns a llama.cpp GGUF model and serializes its load and inference lifecycle. */
class LocalLLMEngine(
    context: Context,
    private val scope: CoroutineScope,
) : Closeable {
    private val appContext = context.applicationContext
    private val operationMutex = Mutex()
    private val eventFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        extraBufferCapacity = 64,
    )
    private var helper: LlamaHelper? = null
    private var loadedModel: File? = null

    val isModelAvailable: Boolean
        get() = modelFile()?.isFile == true

    /** Returns the active GGUF filename for choosing its chat prompt template. */
    fun modelFilename(): String? = modelFile()?.name

    /** Generates locally when a GGUF has been imported; inference runs off the main thread. */
    suspend fun generate(prompt: String): String = operationMutex.withLock {
        val file = modelFile() ?: throw IllegalStateException(
            "No local GGUF model is installed. Import a .gguf model from the JarvisAI dashboard.",
        )
        val activeHelper = ensureLoaded(file)
        val result = CompletableDeferred<String>()
        val generatedText = StringBuilder()
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            eventFlow.collect { event ->
                when (event) {
                    is LlamaHelper.LLMEvent.Ongoing -> generatedText.append(event.word)
                    is LlamaHelper.LLMEvent.Done -> {
                        val answer = event.fullText.ifBlank { generatedText.toString() }.trim()
                        if (answer.isNotEmpty()) result.complete(answer)
                        else result.completeExceptionally(IllegalStateException("The local model returned no text."))
                    }
                    is LlamaHelper.LLMEvent.Error -> result.completeExceptionally(
                        IllegalStateException(event.message.ifBlank { "Local model inference failed." }),
                    )
                    else -> Unit
                }
            }
        }
        try {
            activeHelper.predict(prompt, null, true)
            withTimeout(INFERENCE_TIMEOUT_MILLIS) { result.await() }
        } catch (timeout: TimeoutCancellationException) {
            activeHelper.stopPrediction()
            throw IllegalStateException("The local model took too long to respond. Try a smaller GGUF.", timeout)
        } catch (cancelled: CancellationException) {
            activeHelper.stopPrediction()
            throw cancelled
        } finally {
            collector.cancel()
        }
    }

    /** Releases llama.cpp native state when the foreground service stops. */
    override fun close() {
        runCatching { helper?.abort() }
        runCatching { helper?.release() }
        helper = null
        loadedModel = null
    }

    private suspend fun ensureLoaded(file: File): LlamaHelper {
        helper?.let { if (loadedModel == file) return it }
        runCatching { helper?.release() }
        val newHelper = LlamaHelper(appContext.contentResolver, scope, eventFlow)
        val modelUri = Uri.fromFile(file).toString()
        val loaded = CompletableDeferred<Unit>()
        val loadCollector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            eventFlow.collect { event ->
                when (event) {
                    is LlamaHelper.LLMEvent.Loaded -> {
                        if (event.path == modelUri) loaded.complete(Unit)
                    }
                    is LlamaHelper.LLMEvent.Error -> loaded.completeExceptionally(
                        IllegalStateException(event.message.ifBlank { "Local model loading failed." }),
                    )
                    else -> Unit
                }
            }
        }
        try {
            newHelper.load(modelUri, 2048, null) {
                loaded.complete(Unit)
            }
            withTimeout(MODEL_LOAD_TIMEOUT_MILLIS) { loaded.await() }
        } catch (error: Throwable) {
            runCatching { newHelper.abort() }
            runCatching { newHelper.release() }
            if (error is TimeoutCancellationException) {
                throw IllegalStateException(
                    "Local model loading timed out. A smaller, quantized GGUF may work better on this device.",
                    error,
                )
            }
            throw error
        } finally {
            loadCollector.cancel()
        }
        helper = newHelper
        loadedModel = file
        return newHelper
    }

    private fun modelFile(): File? = appContext.filesDir.resolve("models")
        .listFiles()
        ?.filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
        ?.maxByOrNull(File::length)

    private companion object {
        const val MODEL_LOAD_TIMEOUT_MILLIS = 5 * 60 * 1000L
        const val INFERENCE_TIMEOUT_MILLIS = 2 * 60 * 1000L
    }
}
