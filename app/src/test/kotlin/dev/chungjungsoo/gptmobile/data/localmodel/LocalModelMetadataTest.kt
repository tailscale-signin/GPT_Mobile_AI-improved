package dev.chungjungsoo.gptmobile.data.localmodel

import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalModelMetadataTest {
    private val legacy = CatalogEntry(
        id = "local_gguf_legacy",
        displayName = "Chat model · GenieX Preview",
        variantLabel = "LLAMA_CPP",
        runtimeRequirement = "llama_cpp:pinned",
        supportedAccelerators = listOf("cpu")
    )

    @Test fun existingGgufImportGainsGpuWithoutReimportingWeights() {
        val upgraded = LocalModelMetadata.withGgufBackends(legacy, setOf("cpu", "gpu"))
        assertEquals(listOf("cpu", "gpu"), upgraded.supportedAccelerators)
        assertEquals("Chat model · GGUF", upgraded.displayName)
        assertEquals(legacy.id, upgraded.id)
    }

    @Test fun cpuOnlyBuildRemovesGpuFromPreviouslyImportedMetadata() {
        val imported = legacy.copy(supportedAccelerators = listOf("cpu", "gpu"))
        assertEquals(listOf("cpu"), LocalModelMetadata.withGgufBackends(imported, setOf("cpu")).supportedAccelerators)
    }

    @Test fun otherRuntimesRetainTheirOwnBackendRestrictions() {
        for (runtime in listOf("LITERT_LM", "GENIEX_LLAMA_CPP", "GENIEX_QAIRT")) {
            val entry = legacy.copy(variantLabel = runtime, runtimeRequirement = runtime.lowercase())
            assertEquals(entry, LocalModelMetadata.withGgufBackends(entry, setOf("cpu", "gpu")))
        }
    }
}
