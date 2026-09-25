package com.jarvis.ai.llm

import com.jarvis.ai.memory.ConversationDao
import com.jarvis.ai.memory.ConversationMessage
import com.jarvis.ai.network.ChatMessage
import com.jarvis.ai.network.CloudLLMClient
import com.jarvis.ai.network.NetworkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** Routes conversations to local llama.cpp first and to configured cloud APIs when available. */
class LLMRouter(
    private val localEngine: LocalLLMEngine,
    private val cloudClient: CloudLLMClient,
    private val networkUtils: NetworkUtils,
    private val conversationDao: ConversationDao,
) {
    private val responseMutex = Mutex()

    /** Uses recent Room history as context and persists both sides of the completed exchange. */
    suspend fun respond(userText: String): String = responseMutex.withLock {
        val cleanText = userText.trim().take(MAX_USER_CHARS)
        require(cleanText.isNotBlank()) { "The recognized request was empty." }
        val recent = withContext(Dispatchers.IO) {
            try {
                conversationDao.getRecent(HISTORY_LIMIT).asReversed()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
        val messages = buildMessages(recent, cleanText)
        val answer = generate(messages)
        remember(cleanText, answer)
        answer
    }

    /** Persists a result produced by an allowlisted native system tool. */
    suspend fun rememberToolExchange(userText: String, assistantText: String) {
        remember(userText.trim().take(MAX_USER_CHARS), assistantText.trim().take(MAX_RESPONSE_CHARS))
    }

    private suspend fun generate(messages: List<ChatMessage>): String {
        var localFailure: Throwable? = null
        if (localEngine.isModelAvailable) {
            val prompt = formatLocalPrompt(messages)
            try {
                return localEngine.generate(prompt).take(MAX_RESPONSE_CHARS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                localFailure = error
            }
        }

        if (networkUtils.hasValidatedInternet() && cloudClient.isConfigured) {
            try {
                return cloudClient.complete(messages).take(MAX_RESPONSE_CHARS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cloudError: Exception) {
                if (localFailure != null) {
                    throw IllegalStateException(
                        "The local model and cloud providers are unavailable. ${safeMessage(localFailure)}",
                        cloudError,
                    )
                }
                throw cloudError
            }
        }

        if (localFailure != null) {
            throw IllegalStateException(
                "Local inference failed: ${safeMessage(localFailure)}. " +
                    if (networkUtils.hasValidatedInternet() && !cloudClient.isConfigured) {
                        "Add an API key to local.properties to enable cloud fallback."
                    } else {
                        "Import a smaller GGUF or connect to a configured cloud provider."
                    },
                localFailure,
            )
        }

        throw IllegalStateException(
            if (!localEngine.isModelAvailable) {
                "No local GGUF is installed. Import a quantized GGUF model, or configure an API key in local.properties while online."
            } else {
                "Jarvis is offline and no local model is ready."
            },
        )
    }

    private suspend fun remember(userText: String, assistantText: String) = withContext(Dispatchers.IO) {
        try {
            conversationDao.insertAll(
                listOf(
                    ConversationMessage(role = "user", content = userText),
                    ConversationMessage(role = "assistant", content = assistantText),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A completed answer remains useful even if the local history database is temporarily unavailable.
        }
    }

    private fun buildMessages(history: List<ConversationMessage>, userText: String): List<ChatMessage> {
        val messages = ArrayList<ChatMessage>(history.size + 2)
        messages += ChatMessage("system", SYSTEM_PROMPT)
        history.takeLast(HISTORY_LIMIT).forEach { item ->
            val role = if (item.role == "assistant") "assistant" else "user"
            messages += ChatMessage(role, item.content.take(MAX_HISTORY_MESSAGE_CHARS))
        }
        messages += ChatMessage("user", userText)
        return messages
    }

    private fun formatLocalPrompt(messages: List<ChatMessage>): String {
        val modelName = localEngine.modelFilename().orEmpty().lowercase(Locale.ROOT)
        return when {
            "llama" in modelName -> buildString {
                messages.forEach { message ->
                    append("<|start_header_id|>${message.role}<|end_header_id|>\n\n")
                    append(message.content)
                    append("<|eot_id|>\n")
                }
                append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }
            "phi" in modelName -> buildString {
                messages.forEach { message ->
                    val role = if (message.role == "assistant") "assistant" else message.role
                    append("<|$role|>\n")
                    append(message.content)
                    append("<|end|>\n")
                }
                append("<|assistant|>\n")
            }
            else -> buildString {
                messages.forEach { message ->
                    val role = when (message.role) {
                        "assistant" -> "Jarvis"
                        "system" -> "System"
                        else -> "User"
                    }
                    append("$role: ")
                    appendLine(message.content)
                }
                append("Jarvis:")
            }
        }
    }

    private fun safeMessage(error: Throwable?): String =
        error?.message?.takeIf { it.isNotBlank() }?.take(180) ?: "model error"

    private companion object {
        const val HISTORY_LIMIT = 8
        const val MAX_USER_CHARS = 2_000
        const val MAX_HISTORY_MESSAGE_CHARS = 600
        const val MAX_RESPONSE_CHARS = 4_000
        const val SYSTEM_PROMPT =
            "You are Jarvis, a helpful, privacy-conscious Android assistant. " +
                "Be truthful, concise, and ask for clarification if a request is ambiguous. " +
                "Never claim a device action succeeded unless the app explicitly confirmed it."
    }
}
