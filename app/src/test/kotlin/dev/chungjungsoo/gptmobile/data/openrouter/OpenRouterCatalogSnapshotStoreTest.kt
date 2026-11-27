package dev.chungjungsoo.gptmobile.data.openrouter

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterCatalogSnapshotStoreTest {
    private val alias = "~deepseek/deepseek-v4-flash-latest"
    private val model = Json { ignoreUnknownKeys = true }.decodeFromString<OpenRouterModelItem>(
        """{"id":"~deepseek/deepseek-v4-flash-latest","canonical_slug":"~deepseek/deepseek-v4-flash-latest","name":"Display only","description":"Discard public prose","context_length":1048576,"top_provider":{"context_length":1048576,"max_completion_tokens":131072},"supported_parameters":["reasoning","reasoning_effort","max_tokens","tools"],"reasoning":{"mandatory":false,"default_enabled":true,"supported_efforts":["max","high","low","none"],"default_effort":"high"}}"""
    )

    @Test fun restartRestoresTheExactAliasCapabilitiesAndOriginalExpiry() = withStore { store, file ->
        val now = System.currentTimeMillis()
        val observed = now - 1_800_000
        store.write(listOf(model), observed)
        assertFalse(file.readText().contains("Discard public prose"))
        val restored = requireNotNull(OpenRouterCatalogSnapshotStore(file).read(now))
        assertEquals(observed, restored.observedAtMillis)
        assertEquals(alias, restored.models.single().id)
        assertEquals(alias, restored.models.single().canonicalSlug)
        OpenRouterCatalogLimits.remember(emptyList())
        OpenRouterCatalogLimits.restore(restored.models, restored.observedAtMillis, now, monotonicMillis = 10_000_000)
        val limits = requireNotNull(OpenRouterCatalogLimits.forRequest("OPENROUTER", null, alias, monotonicMillis = 10_000_000))
        assertEquals(1_048_576, limits.context)
        assertEquals(131_072, limits.output)
        assertEquals(setOf("max", "high", "low", "none"), limits.reasoningEfforts)
        assertEquals(observed, limits.observedAtMillis)
        assertNull(OpenRouterCatalogLimits.forRequest("OPENROUTER", null, alias.removePrefix("~"), monotonicMillis = 10_000_000))
        assertNotNull(OpenRouterCatalogLimits.forRequest("OPENROUTER", null, alias, monotonicMillis = 11_800_000))
        assertNull(OpenRouterCatalogLimits.forRequest("OPENROUTER", null, alias, monotonicMillis = 11_800_001))
        assertNull(store.read(observed + OpenRouterCatalogLimits.MAX_AGE_MILLIS + 1))
    }

    @Test fun staleOrUnrelatedCacheCannotSupplyAContextCeiling() = withStore { store, file ->
        val now = System.currentTimeMillis()
        val snapshot = OpenRouterCatalogSnapshotStore.Snapshot(observedAtMillis = now, models = listOf(model))
        for (invalid in listOf(snapshot.copy(source = "https://proxy.example/models"), snapshot.copy(schema = 2), snapshot.copy(observedAtMillis = now + 1), snapshot.copy(observedAtMillis = now - OpenRouterCatalogLimits.MAX_AGE_MILLIS - 1))) {
            file.writeText(Json.encodeToString(invalid))
            assertNull(store.read(now))
        }
        file.writeText("not a catalog")
        assertNull(store.read(now))
    }

    @Test fun newerAliasRowReplacesOldCapabilitiesAndCannotBeRolledBackByDiskRestore() {
        val now = System.currentTimeMillis()
        OpenRouterCatalogLimits.remember(listOf(model), now - 1000, now, 10_000_000)
        val rollover = model.copy(contextLength = 262_144, topProvider = OpenRouterTopProvider(maxCompletionTokens = 16_384), reasoning = model.reasoning?.copy(supportedEfforts = listOf("high")))
        OpenRouterCatalogLimits.remember(listOf(rollover), now, now, 10_001_000)
        OpenRouterCatalogLimits.restore(listOf(model), now - 1000, now, 10_001_000)
        val limits = requireNotNull(OpenRouterCatalogLimits.forRequest("OPENROUTER", null, alias, monotonicMillis = 10_001_000))
        assertEquals(262_144, limits.context)
        assertEquals(16_384, limits.output)
        assertEquals(setOf("high"), limits.reasoningEfforts)
        val pinned = requireNotNull(OpenRouterCatalogLimits.forRequest("OPENROUTER", null, alias, "pinned-route", monotonicMillis = 10_001_000))
        assertTrue(pinned.reasoningEfforts.isEmpty())
        assertFalse(pinned.reasoningControlAdvertised)
        assertNull(pinned.output)
    }

    private fun withStore(block: (OpenRouterCatalogSnapshotStore, File) -> Unit) {
        val directory = Files.createTempDirectory("openrouter-capabilities-test").toFile()
        try {
            val file = File(directory, "catalog.json")
            block(OpenRouterCatalogSnapshotStore(file), file)
        } finally {
            directory.deleteRecursively()
        }
    }
}
