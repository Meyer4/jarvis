package com.jarvis.ai.network

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.ai.BuildConfig
import com.jarvis.ai.llm.BuildTimeKeys
import com.jarvis.ai.llm.ModelProviderConfig
import com.jarvis.ai.llm.ProviderRepository
import com.jarvis.ai.llm.SettingsStorage
import com.jarvis.ai.llm.StoredProviderSettings

/**
 * Android binding for the shared provider brain: keeps the settings in app-private
 * SharedPreferences and feeds in any keys compiled from `local.properties` or the environment.
 *
 * Keys typed in the app stay inside the app's own sandbox and can be replaced or cleared at any
 * time from the "Cloud AI providers" panel.
 */
class CloudProviderStore(context: Context) {
    private val repository = ProviderRepository(
        storage = SharedPreferencesStorage(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ),
        buildTimeKeys = BuildTimeKeys.parse(BuildConfig.CLOUD_PROVIDER_API_KEYS),
    )

    /** Every catalog provider, merged with saved settings and ordered by user preference. */
    fun configs(): List<ModelProviderConfig> = repository.configs()

    /** Providers that are enabled and complete; these are attempted in order. */
    fun usableConfigs(): List<ModelProviderConfig> = repository.usableConfigs()

    fun stored(id: String): StoredProviderSettings? = repository.stored(id)

    fun save(id: String, settings: StoredProviderSettings) = repository.save(id, settings)

    /** Forgets every stored value for the provider, including any in-app key. */
    fun reset(id: String) = repository.reset(id)

    fun order(): List<String> = repository.order()

    fun setOrder(ids: List<String>) = repository.setOrder(ids)

    /** Moves a provider up (`-1`) or down (`+1`) in the call order and returns the new order. */
    fun move(id: String, delta: Int): List<String> = repository.move(id, delta)

    /** True when a key for this provider was compiled into the APK from the build environment. */
    fun hasBuildTimeKey(id: String): Boolean = repository.hasBuildTimeKey(id)

    private class SharedPreferencesStorage(
        private val preferences: SharedPreferences,
    ) : SettingsStorage {
        override fun read(key: String): String? = preferences.getString(key, null)

        override fun write(key: String, value: String?) {
            preferences.edit().apply {
                if (value == null) remove(key) else putString(key, value)
            }.apply()
        }
    }

    companion object {
        const val PREFS_NAME = "jarvis_cloud_providers"
    }
}
