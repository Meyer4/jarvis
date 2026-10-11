package com.jarvis.ai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the storage-backed provider settings used by both the phone and laptop apps. */
class ProviderRepositoryTest {

    @Test
    fun savedSettingsComeBackAndOverrideTemplateDefaults() {
        val repository = ProviderRepository(InMemorySettingsStorage())
        repository.save(
            "groq",
            StoredProviderSettings(
                enabled = true,
                model = "llama-3.1-8b-instant",
                baseUrl = "https://api.groq.com/openai/v1",
                apiKey = "gsk-test",
            ),
        )
        val groq = repository.configs().first { it.id == "groq" }
        assertEquals("llama-3.1-8b-instant", groq.model)
        assertEquals("gsk-test", groq.apiKey)
        assertTrue(groq.enabled)
        assertTrue(groq.isUsable)
        assertEquals(listOf("groq"), repository.usableConfigs().map { it.id })
    }

    @Test
    fun aProviderWithNoSavedSettingsIsOffButStillListed() {
        val repository = ProviderRepository(InMemorySettingsStorage())
        assertNull(repository.stored("mistral"))
        val ids = repository.configs().map { it.id }
        assertEquals(ModelProviderCatalog.defaultOrder, ids)
        assertTrue(repository.usableConfigs().isEmpty())
    }

    @Test
    fun resetClearsEverythingTheUserTyped() {
        val repository = ProviderRepository(InMemorySettingsStorage())
        repository.save("openai", StoredProviderSettings(enabled = true, apiKey = "sk-app"))
        assertTrue(repository.usableConfigs().isNotEmpty())
        repository.reset("openai")
        assertNull(repository.stored("openai"))
        assertTrue(repository.usableConfigs().isEmpty())
    }

    @Test
    fun buildTimeKeysEnableAProviderUntilTheUserDecides() {
        val repository = ProviderRepository(
            storage = InMemorySettingsStorage(),
            buildTimeKeys = mapOf("MOONSHOT_API_KEY" to "sk-moonshot"),
        )
        val kimi = repository.configs().first { it.id == "moonshot" }
        assertTrue(kimi.enabled)
        assertTrue(kimi.isUsable)
        assertTrue(repository.hasBuildTimeKey("moonshot"))
        assertFalse(repository.hasBuildTimeKey("groq"))

        repository.save("moonshot", StoredProviderSettings(enabled = false))
        val disabled = repository.configs().first { it.id == "moonshot" }
        assertFalse(disabled.enabled)
        assertTrue(repository.usableConfigs().isEmpty())
    }

    @Test
    fun orderRoundTripsAndSurvivesReopeningTheStorage() {
        val storage = InMemorySettingsStorage()
        val repository = ProviderRepository(storage)
        // A larger delta is clamped at the top of the list.
        repository.move("groq", -100)
        assertEquals("groq", repository.order().first())

        val reopened = ProviderRepository(storage)
        assertEquals("groq", reopened.order().first())
        assertEquals("groq", reopened.configs().first().id)
    }

    @Test
    fun buildTimeKeyStringIsParsedDefensively() {
        val parsed = BuildTimeKeys.parse(
            "MOONSHOT_API_KEY=sk-abc;OPENAI_API_KEY=;GROQ_API_KEY=;BROKEN;CUSTOM_LLM_API_KEY=http://host?a=b",
        )
        assertEquals(mapOf("MOONSHOT_API_KEY" to "sk-abc", "CUSTOM_LLM_API_KEY" to "http://host?a=b"), parsed)
        assertTrue(BuildTimeKeys.parse("").isEmpty())
    }
}
