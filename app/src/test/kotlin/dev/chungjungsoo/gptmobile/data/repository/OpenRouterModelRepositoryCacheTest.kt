package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCatalogLimits
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCatalogSnapshotStore
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterModelItem
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterReasoningCapabilities
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterTopProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenRouterModelRepositoryCacheTest {
    @Test fun requestAdmissionRestoresPublicAliasMetadataWithoutOpeningThePickerOrFetching() = runBlocking {
        val directory = Files.createTempDirectory("openrouter-repository-cache").toFile()
        try {
            val store = OpenRouterCatalogSnapshotStore(File(directory, "catalog.json"))
            val observed = System.currentTimeMillis() - 60_000
            val alias = "~deepseek/deepseek-v4-flash-latest"
            store.write(listOf(OpenRouterModelItem(alias, contextLength = 1_048_576, topProvider = OpenRouterTopProvider(maxCompletionTokens = 131_072), supportedParameters = listOf("reasoning", "max_tokens"), reasoning = OpenRouterReasoningCapabilities(supportedEfforts = listOf("high", "low", "none")))), observed)
            repeat(2) {
                OpenRouterCatalogLimits.remember(emptyList())
                val restarted = OpenRouterModelRepository(store)
                val limits = requireNotNull(restarted.prepareRequestLimits("OPENROUTER", "https://openrouter.ai/api/v1", alias, null, allowRefresh = false))
                assertEquals(1_048_576, limits.context)
                assertEquals(observed, limits.observedAtMillis)
                assertEquals(setOf("high", "low", "none"), limits.reasoningEfforts)
                assertNull(restarted.prepareRequestLimits("OPENROUTER", "https://custom.example/api/v1", alias, null, allowRefresh = false))
                assertNull(restarted.prepareRequestLimits("CUSTOM", "https://openrouter.ai/api/v1", alias, null, allowRefresh = false))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
