package com.jarvis.ai.network

import android.content.Context
import com.jarvis.ai.BuildConfig
import com.jarvis.ai.llm.ModelProviderCatalog
import com.jarvis.ai.llm.ModelProviderConfig
import com.jarvis.ai.llm.ProviderResolver
import com.jarvis.ai.llm.ProviderSettingsCodec
import com.jarvis.ai.llm.StoredProviderSettings

/**
 * Persists which cloud providers are enabled, their model names, endpoints, and API keys.
 *
 * Keys entered in the app live in the app's private SharedPreferences sandbox. Keys can also be
 * compiled in from `local.properties` / environment variables, which is convenient for personal
 * builds and documented in the README.
 */
class CloudProviderStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Every catalog provider, merged with saved settings and ordered by user preference. */
    fun configs(): List<ModelProviderConfig> = ProviderResolver.resolve(
        stored = ModelProviderCatalog.templates.associate { it.id to stored(it.id) },
        order = order(),
        buildTimeKeys = buildTimeKeys,
    )

    /** Providers that are enabled and complete; these are attempted in order. */
    fun usableConfigs(): List<ModelProviderConfig> = configs().filter { it.isUsable }

    fun stored(id: String): StoredProviderSettings? {
        val raw = preferences.getString(key(id), null) ?: return null
        return ProviderSettingsCodec.decode(raw)
    }

    fun save(id: String, settings: StoredProviderSettings) {
        preferences.edit().putString(key(id), ProviderSettingsCodec.encode(settings)).apply()
    }

    /** Forgets every stored value for the provider, including any in-app key. */
    fun reset(id: String) {
        preferences.edit().remove(key(id)).apply()
    }

    fun order(): List<String> = ProviderResolver.orderedIds(
        preferences.getString(KEY_ORDER, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList(),
    )

    fun setOrder(ids: List<String>) {
        preferences.edit()
            .putString(KEY_ORDER, ProviderResolver.orderedIds(ids).joinToString(","))
            .apply()
    }

    /** Moves a provider up (`-1`) or down (`+1`) in the call order and returns the new order. */
    fun move(id: String, delta: Int): List<String> {
        val updated = ProviderResolver.move(order(), id, delta)
        setOrder(updated)
        return updated
    }

    /** True when a key for this provider was compiled into the APK from the build environment. */
    fun hasBuildTimeKey(id: String): Boolean {
        val template = ModelProviderCatalog.template(id) ?: return false
        return buildTimeKeys[template.keyEnvironment].orEmpty().isNotBlank()
    }

    private fun key(id: String) = KEY_PREFIX + id

    private val buildTimeKeys: Map<String, String> by lazy {
        BuildConfig.CLOUD_PROVIDER_API_KEYS
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

    companion object {
        const val PREFS_NAME = "jarvis_cloud_providers"
        private const val KEY_PREFIX = "provider_"
        private const val KEY_ORDER = "provider_order"
    }
}
