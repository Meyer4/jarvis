package com.jarvis.ai.llm

/**
 * Describes how a cloud provider expects chat requests to be encoded.
 *
 * Everything here is plain Kotlin so the catalog, settings codec, and ordering rules can be
 * unit-tested on the JVM without an Android device.
 */
enum class ProviderProtocol {
    /** OpenAI-style `POST <baseUrl>/chat/completions` using `Authorization: Bearer <key>`. */
    OPENAI_COMPATIBLE,

    /** Anthropic-style `POST <baseUrl>/messages` using `x-api-key` and `anthropic-version`. */
    ANTHROPIC_MESSAGES,
}

/** A provider JarvisAI knows how to reach. Templates provide the defaults a user can override. */
data class ModelProviderTemplate(
    val id: String,
    val label: String,
    val protocol: ProviderProtocol,
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** Name of the environment variable / `local.properties` entry that can supply a key. */
    val keyEnvironment: String,
    val docsUrl: String,
    /** False for self-hosted servers that accept unauthenticated requests. */
    val keyRequired: Boolean = true,
    /**
     * Some OpenAI-compatible providers reject the legacy `max_tokens` field in favour of
     * `max_completion_tokens`; other providers only understand `max_tokens`.
     */
    val maxTokensParameter: String = "max_tokens",
    /** Optional JSON merged into the OpenAI-compatible request body. */
    val extraBodyJson: String? = null,
    /** Optional HTTP headers required or recommended by the provider. */
    val extraHeaders: Map<String, String> = emptyMap(),
    val notes: String = "",
)

/** A provider template merged with the user's choices. This is what the router actually calls. */
data class ModelProviderConfig(
    val id: String,
    val label: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val enabled: Boolean,
    val keyRequired: Boolean,
    val docsUrl: String,
    val maxTokensParameter: String = "max_tokens",
    val extraBodyJson: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val allowInsecure: Boolean = false,
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()

    val usesPlainHttp: Boolean get() = baseUrl.startsWith("http://", ignoreCase = true)

    /** Android blocks cleartext HTTP unless the user opts in, so `http://` needs an explicit switch. */
    val isTransportAllowed: Boolean get() = !usesPlainHttp || allowInsecure

    /** True when this provider can be called exactly as configured. */
    val isUsable: Boolean
        get() = enabled &&
            isTransportAllowed &&
            baseUrl.isNotBlank() &&
            model.isNotBlank() &&
            (!keyRequired || hasKey)

    /** Short reason shown on the dashboard when the provider is not usable. */
    fun blockedReason(): String? = when {
        !enabled -> "off"
        !isTransportAllowed -> "needs the plain-http switch"
        baseUrl.isBlank() -> "needs a base URL"
        model.isBlank() -> "needs a model name"
        keyRequired && !hasKey -> "needs an API key"
        else -> null
    }

    /** One-line description used by the dashboard and by error messages. */
    fun summary(): String = blockedReason()?.let { "$label ($it)" } ?: "$label · $model"
}

/** Known providers plus the shared catalog helpers used by settings, routing, and the dashboard. */
object ModelProviderCatalog {
    const val CUSTOM_PROVIDER_ID = "custom"

    /** Provider entries. Add one here and a matching key name in `app/build.gradle.kts` if needed. */
    val templates: List<ModelProviderTemplate> = listOf(
        ModelProviderTemplate(
            id = "moonshot",
            label = "Kimi · Moonshot AI",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.moonshot.ai/v1",
            defaultModel = "kimi-k2.6",
            keyEnvironment = "MOONSHOT_API_KEY",
            docsUrl = "https://platform.moonshot.ai/console/api-keys",
            extraBodyJson = "{\"thinking\":{\"type\":\"disabled\"}}",
            notes = "Thinking is disabled so spoken replies stay short and fast.",
        ),
        ModelProviderTemplate(
            id = "openai",
            label = "OpenAI",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-5.4-mini",
            keyEnvironment = "OPENAI_API_KEY",
            docsUrl = "https://platform.openai.com/api-keys",
            maxTokensParameter = "max_completion_tokens",
        ),
        ModelProviderTemplate(
            id = "anthropic",
            label = "Anthropic Claude",
            protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
            defaultBaseUrl = "https://api.anthropic.com/v1",
            defaultModel = "claude-haiku-4-5",
            keyEnvironment = "ANTHROPIC_API_KEY",
            docsUrl = "https://console.anthropic.com/settings/keys",
        ),
        ModelProviderTemplate(
            id = "gemini",
            label = "Google Gemini",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            defaultModel = "gemini-3.5-flash",
            keyEnvironment = "GEMINI_API_KEY",
            docsUrl = "https://aistudio.google.com/apikey",
        ),
        ModelProviderTemplate(
            id = "xai",
            label = "xAI Grok",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.x.ai/v1",
            defaultModel = "grok-4.7",
            keyEnvironment = "XAI_API_KEY",
            docsUrl = "https://console.x.ai",
        ),
        ModelProviderTemplate(
            id = "deepseek",
            label = "DeepSeek",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-v4-flash",
            keyEnvironment = "DEEPSEEK_API_KEY",
            docsUrl = "https://platform.deepseek.com/api_keys",
        ),
        ModelProviderTemplate(
            id = "groq",
            label = "Groq",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "llama-3.3-70b-versatile",
            keyEnvironment = "GROQ_API_KEY",
            docsUrl = "https://console.groq.com/keys",
            notes = "Very low latency; good for short spoken answers.",
        ),
        ModelProviderTemplate(
            id = "mistral",
            label = "Mistral AI",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.mistral.ai/v1",
            defaultModel = "mistral-medium-latest",
            keyEnvironment = "MISTRAL_API_KEY",
            docsUrl = "https://console.mistral.ai/api-keys",
        ),
        ModelProviderTemplate(
            id = "openrouter",
            label = "OpenRouter",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "google/gemini-2.5-flash",
            keyEnvironment = "OPENROUTER_API_KEY",
            docsUrl = "https://openrouter.ai/settings/keys",
            extraHeaders = mapOf(
                "HTTP-Referer" to "https://github.com/Meyer4/jarvis",
                "X-Title" to "JarvisAI",
            ),
            notes = "One key for hundreds of models. Model names look like vendor/model.",
        ),
        ModelProviderTemplate(
            id = CUSTOM_PROVIDER_ID,
            label = "Custom / self-hosted",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            defaultBaseUrl = "",
            defaultModel = "",
            keyEnvironment = "CUSTOM_LLM_API_KEY",
            docsUrl = "",
            keyRequired = false,
            notes = "Ollama, LM Studio, vLLM, llama.cpp server, or any OpenAI-compatible endpoint.",
        ),
    )

    fun template(id: String): ModelProviderTemplate? = templates.firstOrNull { it.id == id }

    val defaultOrder: List<String> = templates.map { it.id }

    /** Provider ids in catalog order, always ending with the custom endpoint entry. */
    fun hostedIds(): List<String> = templates.map { it.id }.filter { it != CUSTOM_PROVIDER_ID }
}

/** The user's saved choices for one provider. Absent values fall back to the catalog defaults. */
data class StoredProviderSettings(
    /** Null means "the user has not decided yet", which lets a build-time key enable the provider. */
    val enabled: Boolean? = null,
    val model: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val allowInsecure: Boolean = false,
)

/** Round-trips [StoredProviderSettings] through a small line-based format safe for SharedPreferences. */
object ProviderSettingsCodec {
    private const val ENABLED = "enabled"
    private const val MODEL = "model"
    private const val BASE_URL = "baseUrl"
    private const val API_KEY = "apiKey"
    private const val ALLOW_INSECURE = "allowInsecure"

    fun encode(settings: StoredProviderSettings): String = buildString {
        append(ENABLED).append('=').append(settings.enabled?.toString().orEmpty()).append('\n')
        append(MODEL).append('=').append(escape(settings.model)).append('\n')
        append(BASE_URL).append('=').append(escape(settings.baseUrl)).append('\n')
        append(API_KEY).append('=').append(escape(settings.apiKey)).append('\n')
        append(ALLOW_INSECURE).append('=').append(settings.allowInsecure).append('\n')
    }

    fun decode(raw: String?): StoredProviderSettings {
        if (raw.isNullOrBlank()) return StoredProviderSettings()
        var enabled: Boolean? = null
        var model = ""
        var baseUrl = ""
        var apiKey = ""
        var allowInsecure = false
        raw.lineSequence().forEach { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val name = line.substring(0, separator)
            val value = unescape(line.substring(separator + 1))
            when (name) {
                ENABLED -> enabled = when (value) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
                MODEL -> model = value
                BASE_URL -> baseUrl = value
                API_KEY -> apiKey = value
                ALLOW_INSECURE -> allowInsecure = value == "true"
            }
        }
        return StoredProviderSettings(
            enabled = enabled,
            model = model,
            baseUrl = baseUrl,
            apiKey = apiKey,
            allowInsecure = allowInsecure,
        )
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n")

    private fun unescape(value: String): String {
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\\' && index + 1 < value.length) {
                index += 1
                when (val next = value[index]) {
                    'n' -> builder.append('\n')
                    '\\' -> builder.append('\\')
                    else -> builder.append(next)
                }
            } else {
                builder.append(character)
            }
            index += 1
        }
        return builder.toString()
    }
}

/** Merges catalog templates with stored settings, build-time keys, and the user's preferred order. */
object ProviderResolver {
    /** Keeps known ids in the stored order, then appends any catalog entries the order is missing. */
    fun orderedIds(order: List<String>): List<String> {
        val known = ModelProviderCatalog.defaultOrder
        val result = ArrayList<String>(known.size)
        order.forEach { id ->
            if (id in known && id !in result) result += id
        }
        known.forEach { id -> if (id !in result) result += id }
        return result
    }

    /** Moves one provider up (`delta = -1`) or down (`delta = +1`) inside the call order. */
    fun move(order: List<String>, id: String, delta: Int): List<String> {
        val ids = orderedIds(order).toMutableList()
        val from = ids.indexOf(id)
        if (from < 0) return ids
        val to = (from + delta).coerceIn(0, ids.size - 1)
        if (to == from) return ids
        ids.removeAt(from)
        ids.add(to, id)
        return ids
    }

    /** Builds the callable provider list: enabled, key-complete, and ordered by user preference. */
    fun resolve(
        stored: Map<String, StoredProviderSettings?>,
        order: List<String>,
        buildTimeKeys: Map<String, String>,
    ): List<ModelProviderConfig> {
        val orderedTemplates = orderedIds(order).mapNotNull { ModelProviderCatalog.template(it) }
        return orderedTemplates.map { template ->
            val settings = stored[template.id] ?: StoredProviderSettings()
            val buildTimeKey = buildTimeKeys[template.keyEnvironment].orEmpty()
            val apiKey = settings.apiKey.ifBlank { buildTimeKey }
            ModelProviderConfig(
                id = template.id,
                label = template.label,
                protocol = template.protocol,
                baseUrl = normalizeBaseUrl(settings.baseUrl.ifBlank { template.defaultBaseUrl }),
                model = settings.model.ifBlank { template.defaultModel },
                apiKey = apiKey,
                // A compiled-in key keeps working until the user explicitly toggles the provider.
                enabled = settings.enabled ?: apiKey.isNotBlank(),
                keyRequired = template.keyRequired,
                docsUrl = template.docsUrl,
                maxTokensParameter = template.maxTokensParameter,
                extraBodyJson = template.extraBodyJson,
                extraHeaders = template.extraHeaders,
                allowInsecure = settings.allowInsecure,
            )
        }
    }

    /** Accepts a pasted endpoint or a bare base URL and always returns a clean base URL. */
    fun normalizeBaseUrl(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""
        while (value.endsWith("/")) value = value.dropLast(1)
        for (suffix in listOf("/chat/completions", "/messages")) {
            if (value.endsWith(suffix)) {
                value = value.dropLast(suffix.length)
                break
            }
        }
        return value
    }
}
