package com.jarvis.ai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the provider catalog, the settings codec, and the resolution/ordering rules. */
class ModelProviderCatalogTest {

    @Test
    fun catalogIdsAndKeyNamesStayUnique() {
        val ids = ModelProviderCatalog.templates.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val keyNames = ModelProviderCatalog.templates.map { it.keyEnvironment }
        assertEquals(keyNames.size, keyNames.toSet().size)
    }

    @Test
    fun catalogKeyNamesMatchTheBuildScript() {
        // app/build.gradle.kts emits BuildConfig.CLOUD_PROVIDER_API_KEYS from this exact list.
        val expected = setOf(
            "MOONSHOT_API_KEY",
            "OPENAI_API_KEY",
            "ANTHROPIC_API_KEY",
            "GEMINI_API_KEY",
            "XAI_API_KEY",
            "DEEPSEEK_API_KEY",
            "GROQ_API_KEY",
            "MISTRAL_API_KEY",
            "OPENROUTER_API_KEY",
            "CUSTOM_LLM_API_KEY",
        )
        assertEquals(expected, ModelProviderCatalog.templates.map { it.keyEnvironment }.toSet())
    }

    @Test
    fun hostedProvidersDefaultToHttpsAndRealModels() {
        ModelProviderCatalog.templates.forEach { template ->
            if (template.id == ModelProviderCatalog.CUSTOM_PROVIDER_ID) return@forEach
            assertTrue(template.id, template.defaultBaseUrl.startsWith("https://"))
            assertFalse(template.id, template.defaultBaseUrl.endsWith("/"))
            assertTrue(template.id, template.defaultModel.isNotBlank())
            assertTrue(template.id, template.keyRequired)
        }
    }

    @Test
    fun theCustomProviderIsLastAndDoesNotNeedAKey() {
        val last = ModelProviderCatalog.defaultOrder.last()
        val template = ModelProviderCatalog.template(last)
        assertEquals(ModelProviderCatalog.CUSTOM_PROVIDER_ID, last)
        assertFalse(template?.keyRequired ?: true)
    }

    @Test
    fun settingsRoundTripThroughTheCodec() {
        val settings = StoredProviderSettings(
            enabled = true,
            model = "kimi-k2.6",
            baseUrl = "https://api.moonshot.ai/v1",
            apiKey = "sk-test=value\\with/slash",
            allowInsecure = true,
        )
        assertEquals(settings, ProviderSettingsCodec.decode(ProviderSettingsCodec.encode(settings)))
    }

    @Test
    fun codecReturnsDefaultsForMissingSettings() {
        assertEquals(StoredProviderSettings(), ProviderSettingsCodec.decode(null))
        assertEquals(StoredProviderSettings(), ProviderSettingsCodec.decode(""))
    }

    @Test
    fun codecTreatsABlankEnabledFlagAsUndecided() {
        val decoded = ProviderSettingsCodec.decode("enabled=\nmodel=gpt-5.4-mini\n")
        assertNull(decoded.enabled)
        assertEquals("gpt-5.4-mini", decoded.model)
        assertFalse(decoded.allowInsecure)
    }

    @Test
    fun resolveUsesTemplateDefaultsUntilTheUserChangesThem() {
        val configs = ProviderResolver.resolve(
            stored = emptyMap(),
            order = emptyList(),
            buildTimeKeys = emptyMap(),
        )
        val groq = configs.first { it.id == "groq" }
        assertEquals("https://api.groq.com/openai/v1", groq.baseUrl)
        assertEquals("llama-3.3-70b-versatile", groq.model)
        assertFalse(groq.enabled)
        assertFalse(groq.isUsable)
        assertEquals("off", groq.blockedReason())
    }

    @Test
    fun resolveEnablesAProviderThatHasABuildTimeKey() {
        val configs = ProviderResolver.resolve(
            stored = mapOf("openai" to null),
            order = emptyList(),
            buildTimeKeys = mapOf("OPENAI_API_KEY" to "sk-build"),
        )
        val openAi = configs.first { it.id == "openai" }
        assertTrue(openAi.enabled)
        assertTrue(openAi.isUsable)
        assertEquals("sk-build", openAi.apiKey)
        assertNull(openAi.blockedReason())
    }

    @Test
    fun explicitUserChoicesWinOverBuildTimeKeys() {
        val configs = ProviderResolver.resolve(
            stored = mapOf(
                "openai" to StoredProviderSettings(
                    enabled = false,
                    model = "gpt-custom",
                    baseUrl = "https://proxy.example/v1",
                    apiKey = "sk-app",
                ),
            ),
            order = listOf("openai"),
            buildTimeKeys = mapOf("OPENAI_API_KEY" to "sk-build"),
        )
        val openAi = configs.first { it.id == "openai" }
        assertEquals("gpt-custom", openAi.model)
        assertEquals("https://proxy.example/v1", openAi.baseUrl)
        assertEquals("sk-app", openAi.apiKey)
        assertFalse(openAi.enabled)
    }

    @Test
    fun theSavedOrderControlsWhichProviderIsTriedFirst() {
        val configs = ProviderResolver.resolve(
            stored = mapOf(
                "openai" to StoredProviderSettings(enabled = true, apiKey = "sk-a"),
                "groq" to StoredProviderSettings(enabled = true, apiKey = "sk-b"),
            ),
            order = listOf("groq", "openai"),
            buildTimeKeys = emptyMap(),
        )
        assertEquals(listOf("groq", "openai"), configs.filter { it.isUsable }.map { it.id })
        assertEquals("groq", configs.first().id)
    }

    @Test
    fun unknownOrderEntriesAreDroppedAndMissingOnesAppended() {
        val resolved = ProviderResolver.orderedIds(listOf("groq", "nonsense"))
        assertEquals(ModelProviderCatalog.defaultOrder.size, resolved.size)
        assertEquals("groq", resolved.first())
        assertEquals(ModelProviderCatalog.defaultOrder.toSet(), resolved.toSet())
    }

    @Test
    fun moveReordersProvidersAndClampsAtTheEdges() {
        val order = listOf("openai", "groq", "mistral")
        val movedDown = ProviderResolver.move(order, "openai", 1)
        assertTrue(movedDown.indexOf("groq") < movedDown.indexOf("openai"))
        val movedBackUp = ProviderResolver.move(movedDown, "openai", -1)
        assertTrue(movedBackUp.indexOf("openai") < movedBackUp.indexOf("groq"))
        val clamped = ProviderResolver.move(order, "openai", -1)
        assertEquals("openai", clamped.first())
        assertEquals(ModelProviderCatalog.defaultOrder.toSet(), clamped.toSet())
    }

    @Test
    fun plainHttpEndpointsNeedTheExplicitSwitch() {
        val base = ModelProviderConfig(
            id = ModelProviderCatalog.CUSTOM_PROVIDER_ID,
            label = "Custom",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "http://192.168.1.20:11434/v1",
            model = "llama3.1:8b",
            apiKey = "",
            enabled = true,
            keyRequired = false,
            docsUrl = "",
        )
        assertFalse(base.isUsable)
        assertEquals("needs the plain-http switch", base.blockedReason())
        assertTrue(base.copy(allowInsecure = true).isUsable)
        assertTrue(base.copy(baseUrl = "https://models.example/v1").isUsable)
    }

    @Test
    fun hostedProvidersNeedAKeyButCustomEndpointsDoNot() {
        fun config(id: String, keyRequired: Boolean) = ModelProviderConfig(
            id = id,
            label = id,
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://models.example/v1",
            model = "some-model",
            apiKey = "",
            enabled = true,
            keyRequired = keyRequired,
            docsUrl = "",
        )
        assertEquals("needs an API key", config("groq", keyRequired = true).blockedReason())
        assertTrue(config("custom", keyRequired = false).isUsable)
    }

    @Test
    fun baseUrlNormalizationAcceptsPastedEndpoints() {
        assertEquals(
            "https://api.openai.com/v1",
            ProviderResolver.normalizeBaseUrl("https://api.openai.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.anthropic.com/v1",
            ProviderResolver.normalizeBaseUrl(" https://api.anthropic.com/v1/messages "),
        )
        assertEquals(
            "http://192.168.1.20:11434/v1",
            ProviderResolver.normalizeBaseUrl("http://192.168.1.20:11434/v1/"),
        )
        assertEquals("", ProviderResolver.normalizeBaseUrl("   "))
    }
}
