package dev.chungjungsoo.gptmobile.data.localmodel

import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.repository.FakeLocalModelRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelSelectionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    @Test
    fun `missing download fails preflight without starting a download`() {
        val repo = FakeLocalModelRepository()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.resolveLocalModelSelection("missing", "gpu") }
        }
        assertEquals(emptyList<String>(), repo.startDownloadCalls)
    }

    @Test
    fun legacyImportedNpuModelCannotRunThroughGpuProfile() = runBlocking {
        val id = "local_legacy"
        val folder = temporaryFolder.newFolder(id, "local")
        val file = File(folder, "model.litertlm").apply { writeText("LITERTLM fixture") }
        LocalModelMetadata.save(
            folder,
            CatalogEntry(
                id = id,
                displayName = "gemma-4-E2B-it_qualcomm_sm8750",
                variantLabel = "LITERT_LM"
            )
        )
        val repo = FakeLocalModelRepository(downloadedPaths = mapOf(id to file.absolutePath))
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.resolveLocalModelSelection(id, "GPU") }
        }
        assertTrue(error.message.orEmpty().contains("NPU package"))
        assertEquals(id, repo.resolveLocalModelSelection(id, "NPU").modelId)
    }

    @Test
    fun `legacy GPU profile resolves the installed GPU edition`() = runBlocking {
        val repo = FakeLocalModelRepository(downloadedPaths = mapOf("gemma" to "/models/gemma-sm8750.litertlm", "gemma-litert" to "/models/gemma-gpu.litertlm"))
        assertEquals("gemma-litert", repo.resolveLocalModelSelection("gemma", "GPU").modelId)
        assertEquals("gemma", repo.resolveLocalModelSelection("gemma", "NPU").modelId)
    }
}
