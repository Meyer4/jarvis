package com.jarvis.ai.memory

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/** Resolves, imports, and reports model files without exposing them outside the app sandbox. */
object ModelStore {
    const val MODEL_DIRECTORY = "models"
    const val WAKE_MODEL = "jarvis_wake_word.onnx"
    const val MEL_MODEL = "melspectrogram.onnx"
    const val EMBEDDING_MODEL = "embedding_model.onnx"
    const val HAND_MODEL = "hand_landmarker.task"

    /** Current model readiness and the names of installed private model files. */
    data class Snapshot(
        val wakeReady: Boolean,
        val speechReady: Boolean,
        val localLlmReady: Boolean,
        val gestureReady: Boolean,
        val installedFiles: List<String>,
    ) {
        val readyCount: Int
            get() = listOf(wakeReady, speechReady, localLlmReady, gestureReady).count { it }
    }

    /** Copies recognized optional assets into private files on an IO dispatcher. */
    suspend fun installBundledAssets(context: Context) = withContext(Dispatchers.IO) {
        val target = modelDirectory(context)
        target.mkdirs()
        val roots = listOf("", "models")
        roots.forEach { root ->
            val names = runCatching { context.assets.list(root).orEmpty() }.getOrDefault(emptyArray())
            names.filter(::isModelFilename).forEach assetLoop@{ name ->
                val assetPath = if (root.isBlank()) name else "$root/$name"
                val output = File(target, canonicalFilename(name))
                if (output.isFile && output.length() > 0L) return@assetLoop
                try {
                    context.assets.open(assetPath).use { input ->
                        val temporary = File(target, "${output.name}.part")
                        FileOutputStream(temporary).use { stream ->
                            input.copyTo(stream)
                            stream.fd.sync()
                        }
                        if (temporary.length() > 0L) moveIntoPlace(temporary, output)
                        else temporary.delete()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // An unavailable optional asset is reported by the dashboard as a missing model.
                }
            }
        }
    }

    /** Imports a selected model file atomically and rejects unknown or empty files. */
    suspend fun import(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(context, uri)
        require(isModelFilename(displayName)) {
            "Unsupported file. Choose ONNX, GGUF, TASK, TXT, BIN, JSON, or VOCAB model files."
        }
        val target = modelDirectory(context).apply { mkdirs() }
        val safeName = canonicalFilename(displayName)
        val destination = File(target, safeName)
        val temporary = File(target, "$safeName.part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            } ?: error("Android could not open the selected file.")
            require(temporary.length() > 0L) { "The selected file is empty." }
            moveIntoPlace(temporary, destination)
            destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    /** Returns a concise readiness summary for the dashboard. */
    fun snapshot(context: Context): Snapshot {
        val directory = modelDirectory(context)
        val files = directory.listFiles()?.filter { it.isFile && it.length() > 0L }.orEmpty()
        val names = files.map { it.name }.sorted()
        val wakeReady = fileNamed(files, MEL_MODEL) != null &&
            fileNamed(files, EMBEDDING_MODEL) != null && wakeModelFile(files) != null
        val speechReady = senseVoiceFiles(files) != null || whisperFiles(files) != null
        return Snapshot(
            wakeReady = wakeReady,
            speechReady = speechReady,
            localLlmReady = files.any { it.extension.equals("gguf", ignoreCase = true) },
            gestureReady = fileNamed(files, HAND_MODEL) != null,
            installedFiles = names,
        )
    }

    /** Returns the private model directory shared by engines. */
    fun modelDirectory(context: Context): File = File(context.filesDir, MODEL_DIRECTORY)

    /** Finds the bundled openWakeWord classifier, accepting its common upstream filename. */
    fun wakeModelFile(files: List<File>): File? =
        fileNamed(files, WAKE_MODEL)
            ?: files.firstOrNull { it.name.equals("hey_jarvis_v0.1.onnx", ignoreCase = true) }
            ?: files.firstOrNull { it.name.equals("hey_jarvis.onnx", ignoreCase = true) }

    /** Returns paths for an imported SenseVoice model and its vocabulary, if both exist. */
    fun senseVoiceFiles(files: List<File>): Pair<File, File>? {
        val model = files.firstOrNull {
            it.name.equals("model.int8.onnx", ignoreCase = true) ||
                it.name.equals("sensevoice.onnx", ignoreCase = true) ||
                it.name.equals("model.onnx", ignoreCase = true)
        } ?: return null
        val tokens = fileNamed(files, "tokens.txt") ?: return null
        return model to tokens
    }

    /** Returns a compatible Whisper encoder, decoder, and tokens file, if present. */
    fun whisperFiles(files: List<File>): Triple<File, File, File>? {
        val encoders = files.filter { it.extension.equals("onnx", true) && it.name.contains("encoder", true) }
        val decoders = files.filter { it.extension.equals("onnx", true) && it.name.contains("decoder", true) }
        val tokenFiles = files.filter { it.extension.equals("txt", true) && it.name.contains("token", true) }
            .ifEmpty { listOfNotNull(fileNamed(files, "tokens.txt")) }
        for (encoder in encoders) {
            val prefix = encoder.name.substringBefore("encoder", missingDelimiterValue = "")
            val decoder = decoders.firstOrNull {
                it.name.startsWith(prefix, ignoreCase = true)
            } ?: decoders.firstOrNull() ?: continue
            val tokens = tokenFiles.firstOrNull { it.name.startsWith(prefix, ignoreCase = true) }
                ?: tokenFiles.firstOrNull() ?: continue
            return Triple(encoder, decoder, tokens)
        }
        return null
    }

    private fun moveIntoPlace(temporary: File, destination: File) {
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fileNamed(files: List<File>, name: String): File? =
        files.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
        val raw = fromProvider ?: uri.lastPathSegment ?: "model"
        return File(raw).name
    }

    private fun canonicalFilename(filename: String): String {
        val safe = File(filename).name.trim().take(180)
        return when (safe.lowercase(Locale.ROOT)) {
            "hey_jarvis_v0.1.onnx", "hey_jarvis.onnx" -> WAKE_MODEL
            else -> safe
        }
    }

    private fun isModelFilename(filename: String): Boolean {
        val name = File(filename).name
        if (name.isBlank() || name.startsWith(".")) return false
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("onnx", "gguf", "task", "txt", "tokens", "bin", "json", "vocab")
    }
}
