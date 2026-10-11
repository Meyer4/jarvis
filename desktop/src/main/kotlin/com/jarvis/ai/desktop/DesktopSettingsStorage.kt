package com.jarvis.ai.desktop

import com.jarvis.ai.llm.SettingsStorage
import java.io.File
import java.util.Properties

/**
 * Keeps the desktop provider settings in a plain properties file under the user's home directory.
 * On Android the same data lives in app-private SharedPreferences.
 */
class DesktopSettingsStorage(
    private val file: File = defaultFile(),
) : SettingsStorage {
    private val values = Properties().apply {
        if (file.isFile) runCatching { file.inputStream().use { load(it) } }
    }

    override fun read(key: String): String? = values.getProperty(key)

    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { values.store(it, "JarvisAI provider settings") }
        }
    }

    companion object {
        fun defaultFile(): File = File(System.getProperty("user.home"), ".jarvisai/settings.properties")
    }
}
