package com.jarvis.ai.llm

/** Stores small string settings somewhere platform-specific (Android prefs, desktop file). */
interface SettingsStorage {
    fun read(key: String): String?

    /** Writing `null` removes the entry. */
    fun write(key: String, value: String?)
}

/** Parses the `NAME=value;NAME=value` string a build script compiles into the app. */
object BuildTimeKeys {
    fun parse(encoded: String): Map<String, String> = encoded
        .split(';')
        .mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = entry.substring(0, separator).trim()
            val value = entry.substring(separator + 1).trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }
        .toMap()
}

/** The system prompt shared by every platform, so Jarvis answers consistently everywhere. */
object JarvisPrompts {
    fun system(platform: String): String =
        "You are Jarvis, a helpful, privacy-conscious $platform assistant. " +
            "Be truthful, concise, and ask for clarification if a request is ambiguous. " +
            "Answers are read aloud, so keep them short and avoid markdown or code blocks. " +
            "Never claim a device action succeeded unless the app explicitly confirmed it."
}

/**
 * Reads and writes the user's cloud provider choices for any platform, on top of a
 * [SettingsStorage]. This is the single source of truth for which models Jarvis may call.
 */
class ProviderRepository(
    private val storage: SettingsStorage,
    private val buildTimeKeys: Map<String, String> = emptyMap(),
) {
    /** Every catalog provider, merged with saved settings and ordered by user preference. */
    fun configs(): List<ModelProviderConfig> = ProviderResolver.resolve(
        stored = ModelProviderCatalog.templates.associate { it.id to stored(it.id) },
        order = order(),
        buildTimeKeys = buildTimeKeys,
    )

    /** Providers that are enabled and complete; these are attempted in order. */
    fun usableConfigs(): List<ModelProviderConfig> = configs().filter { it.isUsable }

    fun stored(id: String): StoredProviderSettings? =
        storage.read(key(id))?.let(ProviderSettingsCodec::decode)

    fun save(id: String, settings: StoredProviderSettings) {
        storage.write(key(id), ProviderSettingsCodec.encode(settings))
    }

    /** Forgets every stored value for the provider, including any key typed in the app. */
    fun reset(id: String) {
        storage.write(key(id), null)
    }

    fun order(): List<String> = ProviderResolver.orderedIds(
        storage.read(KEY_ORDER)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty(),
    )

    fun setOrder(ids: List<String>) {
        storage.write(KEY_ORDER, ProviderResolver.orderedIds(ids).joinToString(","))
    }

    /** Moves a provider up (`-1`) or down (`+1`) in the call order and returns the new order. */
    fun move(id: String, delta: Int): List<String> {
        val updated = ProviderResolver.move(order(), id, delta)
        setOrder(updated)
        return updated
    }

    /** True when a key for this provider was compiled into the build from the environment. */
    fun hasBuildTimeKey(id: String): Boolean {
        val template = ModelProviderCatalog.template(id) ?: return false
        return buildTimeKeys[template.keyEnvironment].orEmpty().isNotBlank()
    }

    private fun key(id: String) = KEY_PREFIX + id

    companion object {
        const val KEY_PREFIX = "provider_"
        const val KEY_ORDER = "provider_order"
    }
}

/** Keeps every setting in memory; used by tests and by platforms without persistent storage. */
class InMemorySettingsStorage(
    initial: Map<String, String> = emptyMap(),
) : SettingsStorage {
    private val values = LinkedHashMap(initial)

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
