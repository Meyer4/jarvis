package com.jarvis.ai.network

import com.jarvis.ai.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Sends chat requests to configured OpenAI or Anthropic endpoints without embedding secrets. */
class CloudLLMClient(
    private val openAiApiKey: String = BuildConfig.OPENAI_API_KEY,
    private val anthropicApiKey: String = BuildConfig.ANTHROPIC_API_KEY,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean
        get() = openAiApiKey.isNotBlank() || anthropicApiKey.isNotBlank()

    /** Requests a concise answer, trying the alternate configured provider after a failure. */
    suspend fun complete(messages: List<ChatMessage>): String {
        require(isConfigured) { "No cloud provider key is configured." }
        val primaryFailure = try {
            return if (openAiApiKey.isNotBlank()) completeOpenAi(messages) else completeAnthropic(messages)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error
        }

        if (openAiApiKey.isNotBlank() && anthropicApiKey.isNotBlank()) {
            try {
                return completeAnthropic(messages)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (alternateFailure: Exception) {
                throw IOException("Both configured cloud providers failed.", alternateFailure)
            }
        }
        throw IOException("Cloud provider request failed.", primaryFailure)
    }

    private suspend fun completeOpenAi(messages: List<ChatMessage>): String {
        val bodyMessages = JSONArray().apply {
            messages.forEach { message ->
                put(JSONObject().put("role", message.role).put("content", message.content))
            }
        }
        val body = JSONObject()
            .put("model", "gpt-4.1-mini")
            .put("messages", bodyMessages)
            .put("temperature", 0.4)
            .put("max_completion_tokens", 600)
            .toString()
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $openAiApiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request, "OpenAI") { payload ->
            val content = JSONObject(payload)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
            content.ifBlank { throw IOException("OpenAI returned an empty response.") }
        }
    }

    private suspend fun completeAnthropic(messages: List<ChatMessage>): String {
        val systemText = messages.firstOrNull { it.role == "system" }?.content.orEmpty()
        val conversation = JSONArray().apply {
            messages.filter { it.role != "system" }.forEach { message ->
                put(JSONObject().put("role", message.role).put("content", message.content))
            }
        }
        val body = JSONObject()
            .put("model", "claude-haiku-4-5")
            .put("max_tokens", 600)
            .put("system", systemText)
            .put("messages", conversation)
            .toString()
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", anthropicApiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request, "Anthropic") { payload ->
            val content = JSONObject(payload)
                .getJSONArray("content")
                .let { parts ->
                    (0 until parts.length()).mapNotNull { index ->
                        parts.optJSONObject(index)?.takeIf { it.optString("type") == "text" }
                            ?.optString("text")
                    }.joinToString("\n")
                }
                .trim()
            content.ifBlank { throw IOException("Anthropic returned an empty response.") }
        }
    }

    private suspend fun execute(
        request: Request,
        provider: String,
        parseContent: (String) -> String,
    ): String = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val payload = it.body?.string().orEmpty()
                        if (!it.isSuccessful) throw IOException("$provider returned HTTP ${it.code}.")
                        parseContent(payload)
                    }
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** A provider-neutral message used by the local and cloud chat routers. */
data class ChatMessage(
    val role: String,
    val content: String,
)
