package com.jarvis.ai.network

import com.jarvis.ai.llm.ModelProviderConfig
import com.jarvis.ai.llm.ProviderProtocol
import com.jarvis.ai.llm.ProviderResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends chat requests to any enabled provider: OpenAI-compatible endpoints, Anthropic's Messages
 * API, or a self-hosted server on the local network.
 *
 * Providers are attempted in the order produced by [providerSource], so the user's preferred
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
                "No cloud provider is ready. Enable one under cloud AI providers and add its API key.",
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
        throw IOException("Every enabled cloud provider failed. " + failures.joinToString(" | "))
    }

    /** Sends a tiny prompt to one provider so the dashboard can report whether it works. */
    suspend fun verify(provider: ModelProviderConfig): String {
        require(provider.baseUrl.isNotBlank()) { "Enter a base URL first." }
        require(provider.model.isNotBlank()) { "Enter a model name first." }
        require(provider.isTransportAllowed) {
            "Android blocks plain http:// endpoints unless the insecure switch is enabled."
        }
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
            val data = JSONObject(payload).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { index ->
                val id = data.optJSONObject(index)?.stringOrEmpty("id").orEmpty()
                id.takeIf { it.isNotBlank() }
            }
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
        val body = JSONObject()
            .put("model", provider.model)
            .put(
                "messages",
                JSONArray().apply {
                    messages.forEach { message ->
                        put(JSONObject().put("role", message.role).put("content", message.content))
                    }
                },
            )
            .put(provider.maxTokensParameter, maxTokens)
        provider.extraBodyJson?.let { extra ->
            runCatching { JSONObject(extra) }.getOrNull()?.let { overrides ->
                overrides.keys().forEach { name -> body.put(name, overrides.get(name)) }
            }
        }
        return body.toString()
    }

    private fun anthropicBody(
        provider: ModelProviderConfig,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): String {
        val systemText = messages.firstOrNull { it.role == "system" }?.content.orEmpty()
        val conversation = JSONArray().apply {
            messages.filter { it.role != "system" }.forEach { message ->
                put(
                    JSONObject()
                        .put("role", if (message.role == "assistant") "assistant" else "user")
                        .put("content", message.content),
                )
            }
        }
        return JSONObject()
            .put("model", provider.model)
            .put("max_tokens", maxTokens)
            .put("system", systemText)
            .put("messages", conversation)
            .toString()
    }

    private fun parseOpenAi(payload: String, provider: ModelProviderConfig): String {
        val choices = JSONObject(payload).optJSONArray("choices")
        val first = choices?.optJSONObject(0)
        val text = first?.optJSONObject("message")?.stringOrEmpty("content").orEmpty()
            .ifBlank { first?.stringOrEmpty("text").orEmpty() }
            .trim()
        if (text.isBlank()) {
            throw IOException(
                "${provider.label} returned an empty answer for model ${provider.model}. " +
                    "Check that the model name is still offered by the provider.",
            )
        }
        return text
    }

    private fun parseAnthropic(payload: String, provider: ModelProviderConfig): String {
        val parts = JSONObject(payload).optJSONArray("content")
        val text = buildString {
            if (parts != null) {
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    if (part.stringOrEmpty("type") == "text") appendLine(part.stringOrEmpty("text"))
                }
            }
        }.trim()
        if (text.isBlank()) {
            throw IOException(
                "${provider.label} returned an empty answer for model ${provider.model}. " +
                    "Check that the model name is still offered by the provider.",
            )
        }
        return text
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
        val fromJson = runCatching {
            val root = JSONObject(payload)
            val nested = root.optJSONObject("error")?.stringOrEmpty("message").orEmpty()
            nested.ifBlank { root.stringOrEmpty("message") }
        }.getOrDefault("")
        val text = fromJson.ifBlank { payload }
        return text.replace('\n', ' ').trim().take(180).ifBlank { "no details" }
    }

    private fun cleanMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() }?.take(200) ?: "request failed"

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_RESPONSE_TOKENS = 600
        const val VERIFY_MAX_TOKENS = 24
        const val VERIFY_PROMPT = "Reply with the single word: ready"
    }
}

/** org.json turns a JSON `null` into the string "null"; treat that as empty. */
private fun JSONObject.stringOrEmpty(name: String): String {
    if (isNull(name)) return ""
    val value = optString(name)
    return if (value == "null") "" else value
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
