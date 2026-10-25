package dev.chungjungsoo.gptmobile.data.free

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import dev.chungjungsoo.gptmobile.data.network.LocalServerUrls
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeModelCatalogTest {
    @Test fun activeServicesHaveModelsAndRetiredIdentitiesRemainNonExecutable() {
        assertEquals(12, FreeAiProvider.entries.size)
        FreeAiProvider.active.forEach { provider ->
            val profile = provider.applyTo(PlatformV2(name = provider.displayName))
            assertTrue(profile.enabled)
            assertTrue(FreeModelCatalog.defaults(provider).any { it.id == profile.model })
            assertTrue(profile.excludesMemory())
            assertNull(profile.token)
            assertTrue(FreeModelCatalog.details(provider).limits.isNotBlank())
            assertTrue(FreeModelCatalog.details(provider).privacy.isNotBlank())
        }
        assertFalse(FreeAiProvider.POLLINATIONS.supportsTools)
        assertEquals(15, FreeModelCatalog.defaults(FreeAiProvider.KILO).size)
        assertEquals(7, FreeModelCatalog.defaults(FreeAiProvider.BLOCKRUN).size)
    }

    @Test fun mixedCatalogDoesNotAdmitPaidWalletOrNonChatRoutes() {
        val data = Json.parseToJsonElement("""{"data":[{"id":"nvidia/nemotron-3.5-lightning","pricing":{"input":0,"output":0}},{"id":"openai/paid","billing_mode":"paid","pricing":{"input":1,"output":3}},{"id":"unknown","pricing":{"input":0,"output":0}}]}""")
        assertEquals(listOf("nvidia/nemotron-3.5-lightning"), FreeCatalogState.decodeModels(FreeAiProvider.BLOCKRUN, data).map { it.id })
        assertFalse(FreeModelCatalog.acceptsDiscovered(FreeAiProvider.OPENCODE, "jev-1.13-free", true))
        assertFalse(FreeModelCatalog.acceptsDiscovered(FreeAiProvider.KILO, "kilo-auto/efficient", true))
        assertFalse(FreeModelCatalog.acceptsDiscovered(FreeAiProvider.KILO, "new-model:free", false))
        assertThrows(IllegalArgumentException::class.java) { FreeCatalogState.requireModel(FreeAiProvider.BLOCKRUN, "openai/paid") }
    }

    @Test fun preservesChosenFreeModelAndLocalPrivacy() {
        val provider = FreeAiProvider.BLOCKRUN
        val model = FreeModelCatalog.defaults(provider).last().id
        assertEquals(model, provider.applyTo(PlatformV2(name = "BlockRun", model = model)).model)
        assertFalse(PlatformV2(name = "Local", compatibleType = ClientType.CUSTOM, apiUrl = "http://192.168.1.20:8080/v1", model = "my-model").excludesMemory())
        assertEquals("http://192.168.1.20:11434/api/tags", LocalServerUrls.ollamaTags("http://192.168.1.20:11434/v1/"))
        assertEquals("http://192.168.1.20:11434/api/tags", LocalServerUrls.ollamaTags("http://192.168.1.20:11434/api/"))
        assertEquals("http://192.168.1.20:8080/v1", LocalServerUrls.openAiBase("http://192.168.1.20:8080/"))
        assertThrows(IllegalArgumentException::class.java) { LocalServerUrls.root("https://key@example.com/v1") }
    }
}
