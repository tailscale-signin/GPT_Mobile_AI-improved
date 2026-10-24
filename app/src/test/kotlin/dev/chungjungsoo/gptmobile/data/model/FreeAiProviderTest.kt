package dev.chungjungsoo.gptmobile.data.model

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeAiProviderTest {
    @Test
    fun `free defaults follow the provider while custom names survive a switch`() {
        val original = FreeAiProvider.KILO.applyTo(PlatformV2(name = "Free Models"))
        assertEquals("Kilo", original.name)
        assertEquals("Pollinations", FreeAiProvider.POLLINATIONS.applyTo(original).name)
        assertEquals("Research helper", FreeAiProvider.POLLINATIONS.applyTo(original.copy(name = "Research helper")).name)
    }

    @Test
    fun `imported free routes exclude memory without excluding ordinary paid or local models`() {
        val profile = PlatformV2(name = "Test", compatibleType = ClientType.CUSTOM)
        listOf("kilo-auto/free", "openrouter/free", "some-model:free").forEach {
            assertTrue(profile.copy(model = it).excludesMemory())
        }
        FreeAiProvider.entries.forEach { provider ->
            assertTrue(provider.applyTo(profile).excludesMemory())
            assertTrue(profile.copy(apiUrl = provider.apiUrl, model = provider.model).excludesMemory())
        }
        assertFalse(profile.copy(model = "paid-model").excludesMemory())
        assertFalse(profile.copy(compatibleType = ClientType.LITERT_LM, model = "gemma3").excludesMemory())
    }

    @Test
    fun `preset application removes credentials and paid settings but preserves profile identity`() {
        val profile = PlatformV2(name = "My profile", model = "paid", token = "secret", secretRef = "vault", maxTokens = 9000, batchMode = true)
        val free = FreeAiProvider.KILO.applyTo(profile)
        assertEquals(profile.uid, free.uid)
        assertEquals("My profile", free.name)
        assertEquals(FreeAiProvider.KILO.model, free.model)
        assertEquals(9000, free.maxTokens)
        assertNull(free.token)
        assertNull(free.secretRef)
        assertFalse(free.batchMode)
    }

    @Test
    fun `LLM7 does not advertise unsupported tool definitions`() {
        assertFalse(FreeAiProvider.LLM7.supportsTools)
        assertTrue(FreeAiProvider.LLM7.acceptsUserToken)
        assertFalse(FreeAiProvider.LLM7.requiresUserToken)
        assertEquals(ProviderAuthenticationMode.ANONYMOUS_OR_FREE_TOKEN, FreeAiProvider.LLM7.authenticationMode)
        assertTrue(FreeAiProvider.KILO.supportsTools)
    }

    @Test fun optionalLlm7CredentialsStayWithTheirProviderAndStaleDefaultsMigrate() {
        val source = PlatformV2(name = "LLM7", apiUrl = FreeAiProvider.LLM7.apiUrl, model = "mistral-Nemo-Instruct-2407", token = "synthetic", secretRef = "vault-ref")
        val llm7 = FreeAiProvider.LLM7.applyTo(source)
        assertEquals("automatic", llm7.model)
        assertEquals("synthetic", llm7.token)
        assertEquals("vault-ref", llm7.secretRef)
        assertNull(FreeAiProvider.KILO.applyTo(llm7).token)
        assertNull(FreeAiProvider.POLLINATIONS.applyTo(llm7).secretRef)
        assertEquals(1500, FreeAiProvider.POLLINATIONS.outputAllowance(9000))
        assertEquals(1000, FreeAiProvider.POLLINATIONS.outputAllowance(1000))
    }

    @Test
    fun `only exact approved endpoints resolve to Free providers`() {
        assertEquals(FreeAiProvider.KILO, FreeAiProvider.fromApiUrl(" ${FreeAiProvider.KILO.apiUrl}/ "))
        assertEquals(FreeAiProvider.KILO, FreeAiProvider.fromApiUrl(FreeAiProvider.KILO.chatCompletionsUrl))
        assertNull(FreeAiProvider.fromApiUrl("${FreeAiProvider.KILO.apiUrl}.example"))
        assertNull(FreeAiProvider.fromApiUrl("http://api.kilo.ai/api/gateway"))
    }
}
