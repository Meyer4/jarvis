package com.jarvis.ai.network

import com.jarvis.ai.llm.ModelProviderConfig
import com.jarvis.ai.llm.ProviderProtocol
import com.jarvis.ai.llm.ProviderResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends chat requests to any enabled provider: OpenAI-compatible endpoints, Anthropic's Messages
 * API, or a self-hosted server. Runs on Android and on a laptop because it only needs OkHttp and
 * the Kotlin standard library — no Android APIs.
 *
 * Providers are attempted in the order produced by `providerSource`, so the user's preferred
 * provider answers first and the next one is tried when it fails.
 */
class CloudLLMClient(
    private val providerSource: () -> List<ModelProviderConfig>,
) {
    constructor(providers: List<ModelProviderConfig>) : this({ providers })

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    /** True when at least one enabled provider is ready to be called. */
    val isConfigured: Boolean
        get() = providerSource().any { it.isUsable }

    /** True when an enabled provider points at a plain `http://` endpoint such as a LAN server. */
    val hasLanEndpoint: Boolean
        get() = providerSource().any { it.isUsable && it.usesPlainHttp }

    /** Tries every usable provider in order and returns the first answer. */
    suspend fun complete(
        messages: List<ChatMessage>,
        maxTokens: Int = MAX_RESPONSE_TOKENS,
    ): CloudAnswer {
        val providers = providerSource().filter { it.isUsable }
        if (providers.isEmpty()) {
            throw IllegalStateException(
                "No cloud provider is ready. Enable one under AI providers and add its API key.",
            )
        }
        val failures = ArrayList<String>(providers.size)
        providers.forEach { provider ->
            try {
                return CloudAnswer(requestChat(provider, messages, maxTokens), provider)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += "${provider.label}: ${cleanMessage(error)}"
            }
        }
        throw IOException("Every enabled provider failed. " + failures.joinToString(" | "))
    }

    /** Sends a tiny prompt to one provider so the settings screen can report whether it works. */
    suspend fun verify(provider: ModelProviderConfig): String {
        require(provider.baseUrl.isNotBlank()) { "Enter a base URL first." }
        require(provider.model.isNotBlank()) { "Enter a model name first." }
        require(!provider.keyRequired || provider.hasKey) { "Add an API key first." }
        return requestChat(provider, listOf(ChatMessage("user", VERIFY_PROMPT)), VERIFY_MAX_TOKENS)
            .trim()
            .take(200)
    }

    /** Lists the model names the provider currently serves, so the user can pick instead of typing. */
    suspend fun listModels(provider: ModelProviderConfig): List<String> {
        val base = ProviderResolver.normalizeBaseUrl(provider.baseUrl)
        require(base.isNotBlank()) { "Enter a base URL first." }
        val builder = Request.Builder().url("$base/models").get()
        when (provider.protocol) {
            ProviderProtocol.OPENAI_COMPATIBLE ->
                if (provider.hasKey) builder.header("Authorization", "Bearer ${provider.apiKey}")
            ProviderProtocol.ANTHROPIC_MESSAGES -> {
                builder.header("x-api-key", provider.apiKey)
                builder.header("anthropic-version", ANTHROPIC_VERSION)
            }
        }
        provider.extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        val models = execute(builder.build(), provider.label) { payload ->
            parseJson(payload)?.field("data").asArray()
                ?.mapNotNull { it.asObject()?.get("id").asText() }
                .orEmpty()
        }
        if (models.isEmpty()) throw IOException("${provider.label} did not return any models.")
        return models
    }

    private suspend fun requestChat(
        provider: ModelProviderConfig,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): String {
        val base = ProviderResolver.normalizeBaseUrl(provider.baseUrl)
        require(base.isNotBlank()) { "${provider.label} has no base URL yet." }
        return when (provider.protocol) {
            ProviderProtocol.OPENAI_COMPATIBLE -> {
                val builder = Request.Builder()
                    .url("$base/chat/completions")
                    .post(openAiBody(provider, messages, maxTokens).toRequestBody(JSON_MEDIA_TYPE))
                if (provider.hasKey) builder.header("Authorization", "Bearer ${provider.apiKey}")
                provider.extraHeaders.forEach { (name, value) -> builder.header(name, value) }
                execute(builder.build(), provider.label) { payload -> parseOpenAi(payload, provider) }
            }
            ProviderProtocol.ANTHROPIC_MESSAGES -> {
                val request = Request.Builder()
                    .url("$base/messages")
                    .header("x-api-key", provider.apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .post(anthropicBody(provider, messages, maxTokens).toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                execute(request, provider.label) { payload -> parseAnthropic(payload, provider) }
            }
        }
    }

    private fun openAiBody(
        provider: ModelProviderConfig,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): String {
        val base = buildJsonObject {
            put("model", JsonPrimitive(provider.model))
            put(
                "messages",
                buildJsonArray {
                    messages.forEach { message ->
                        add(
                            buildJsonObject {
                                put("role", JsonPrimitive(message.role))
                                put("content", JsonPrimitive(message.content))
                            },
                        )
                    }
                },
            )
            put(provider.maxTokensParameter, JsonPrimitive(maxTokens))
        }
        // Provider quirks (for example Kimi's thinking switch) are merged over the base body.
        val extra = provider.extraBodyJson?.let { text -> parseJson(text)?.asObject() }
        if (extra == null) return base.toString()
        return buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            extra.forEach { (key, value) -> put(key, value) }
        }.toString()
    }

    private fun anthropicBody(
        provider: ModelProviderConfig,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): String {
        val systemText = messages.firstOrNull { it.role == "system" }?.content.orEmpty()
        return buildJsonObject {
            put("model", JsonPrimitive(provider.model))
            put("max_tokens", JsonPrimitive(maxTokens))
            put("system", JsonPrimitive(systemText))
            put(
                "messages",
                buildJsonArray {
                    messages.filter { it.role != "system" }.forEach { message ->
                        add(
                            buildJsonObject {
                                put(
                                    "role",
                                    JsonPrimitive(if (message.role == "assistant") "assistant" else "user"),
                                )
                                put("content", JsonPrimitive(message.content))
                            },
                        )
                    }
                },
            )
        }.toString()
    }

    private fun parseOpenAi(payload: String, provider: ModelProviderConfig): String {
        val first = parseJson(payload)?.field("choices").asArray()?.firstOrNull().asObject()
        val text = (
            first?.get("message").asObject()?.get("content").asText()
                ?: first?.get("text").asText()
            ).orEmpty()
        return text.requireAnswer(provider)
    }

    private fun parseAnthropic(payload: String, provider: ModelProviderConfig): String {
        val parts = parseJson(payload)?.field("content").asArray()
        val text = parts
            ?.mapNotNull { part ->
                val item = part.asObject() ?: return@mapNotNull null
                if (item.get("type").asText() == "text") item.get("text").asText() else null
            }
            ?.joinToString("\n")
            .orEmpty()
        return text.requireAnswer(provider)
    }

    private fun String.requireAnswer(provider: ModelProviderConfig): String {
        val cleaned = trim()
        if (cleaned.isBlank()) {
            throw IOException(
                "${provider.label} returned an empty answer for model ${provider.model}. " +
                    "Check that the model name is still offered by the provider.",
            )
        }
        return cleaned
    }

    private suspend fun <T> execute(
        request: Request,
        providerLabel: String,
        parse: (String) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWith(
                        Result.failure(
                            IOException("$providerLabel: ${error.message ?: "network error"}", error),
                        ),
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val payload = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            throw IOException(
                                "$providerLabel returned HTTP ${it.code}: ${errorSnippet(payload)}",
                            )
                        }
                        parse(payload)
                    }
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }

    /** Pulls the provider's own error message out of a failed response body when possible. */
    private fun errorSnippet(payload: String): String {
        val root = parseJson(payload)
        val message = root?.field("error").asObject()?.field("message").asText()
            ?: root?.field("error").asText()
            ?: root?.field("message").asText()
        val text = message ?: payload
        return text.replace('\n', ' ').trim().take(180).ifBlank { "no details" }
    }

    private fun parseJson(payload: String): JsonElement? =
        runCatching { JSON.parseToJsonElement(payload) }.getOrNull()

    private fun cleanMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() }?.take(200) ?: "request failed"

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_RESPONSE_TOKENS = 600
        const val VERIFY_MAX_TOKENS = 24
        const val VERIFY_PROMPT = "Reply with the single word: ready"
    }
}

private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

/** Reads one field from a JSON object; returns null when this element is not an object. */
private fun JsonElement?.field(name: String): JsonElement? = (this as? JsonObject)?.get(name)

/** Returns the text of a JSON string/number, or null for objects, arrays, and JSON null. */
private fun JsonElement?.asText(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content
}

/** An answer produced by a cloud provider, kept together with the provider that produced it. */
data class CloudAnswer(
    val text: String,
    val provider: ModelProviderConfig,
)

/** A provider-neutral message used by the local and cloud chat routers. */
data class ChatMessage(
    val role: String,
    val content: String,
)
