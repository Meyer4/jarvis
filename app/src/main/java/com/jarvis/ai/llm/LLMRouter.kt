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

/**
 * Routes conversations to whichever model is available: the on-device GGUF first, then the cloud
 * providers the user enabled, tried in the order they chose.
 */
class LLMRouter(
    private val localEngine: LocalLLMEngine,
    private val cloudClient: CloudLLMClient,
    private val networkUtils: NetworkUtils,
    private val conversationDao: ConversationDao,
) {
    private val responseMutex = Mutex()

    /** Answers a request and returns only the text, for callers that do not need the source. */
    suspend fun respond(userText: String): String = respondDetailed(userText).text

    /** Uses recent Room history as context, persists both sides, and reports which model answered. */
    suspend fun respondDetailed(userText: String): AssistantReply = responseMutex.withLock {
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
        val reply = generate(messages)
        remember(cleanText, reply.text)
        reply
    }

    /** Persists a result produced by an allowlisted native system tool. */
    suspend fun rememberToolExchange(userText: String, assistantText: String) {
        remember(userText.trim().take(MAX_USER_CHARS), assistantText.trim().take(MAX_RESPONSE_CHARS))
    }

    private suspend fun generate(messages: List<ChatMessage>): AssistantReply {
        var localFailure: Throwable? = null
        if (localEngine.isModelAvailable) {
            val prompt = formatLocalPrompt(messages)
            try {
                val text = localEngine.generate(prompt).take(MAX_RESPONSE_CHARS)
                return AssistantReply(text, localSourceName())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                localFailure = error
            }
        }

        if (cloudClient.isConfigured && cloudReachable()) {
            try {
                val answer = cloudClient.complete(messages)
                return AssistantReply(
                    answer.text.take(MAX_RESPONSE_CHARS),
                    "${answer.provider.label} · ${answer.provider.model}",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cloudError: Exception) {
                if (localFailure != null) {
                    throw IllegalStateException(
                        "The on-device model and every enabled cloud provider failed. " +
                            safeMessage(localFailure),
                        cloudError,
                    )
                }
                throw cloudError
            }
        }

        if (localFailure != null) {
            throw IllegalStateException(
                "The on-device model failed: ${safeMessage(localFailure)}. ${localRecoveryHint()}",
                localFailure,
            )
        }

        throw IllegalStateException(noModelHint())
    }

    private fun cloudReachable(): Boolean =
        networkUtils.hasValidatedInternet() || (networkUtils.hasNetwork() && cloudClient.hasLanEndpoint)

    private fun localSourceName(): String =
        localEngine.modelFilename()?.let { "on-device model ($it)" } ?: "on-device model"

    private fun localRecoveryHint(): String = when {
        cloudClient.isConfigured && !cloudReachable() ->
            "Connect to the internet to use the cloud providers you enabled."
        cloudClient.isConfigured ->
            "Your enabled cloud providers were tried too. Check their keys and model names in the JarvisAI dashboard."
        else -> "Import a smaller GGUF model, or enable a cloud provider in the JarvisAI dashboard."
    }

    private fun noModelHint(): String {
        val cloudList = "Kimi, OpenAI, Claude, Gemini, Grok, DeepSeek, Groq, Mistral, OpenRouter, or your own endpoint"
        return when {
            !localEngine.isModelAvailable && cloudClient.isConfigured && !cloudReachable() ->
                "No on-device model is installed and there is no usable connection. Import a GGUF model, " +
                    "or go online to use the cloud providers you enabled."
            !localEngine.isModelAvailable ->
                "No AI model is ready. Import a quantized GGUF model, or enable a cloud provider " +
                    "($cloudList) in the JarvisAI dashboard."
            cloudClient.isConfigured && !cloudReachable() ->
                "Jarvis is offline and no on-device model is installed. Import a GGUF model to answer without a network."
            else -> "Jarvis is offline and no local model is ready."
        }
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
                "Answers are read aloud, so keep them short and avoid markdown or code blocks. " +
                "Never claim a device action succeeded unless the app explicitly confirmed it."
    }
}

/** An answer plus the model that produced it, so the dashboard can show the active route. */
data class AssistantReply(
    val text: String,
    val source: String,
)
