package dev.chungjungsoo.gptmobile.data.huggingface

import dev.chungjungsoo.gptmobile.data.catalog.LocalCatalogCompatibilityResolver
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HubDiscoveryTest {
    @Test fun shardsAndProjectorCandidatesArePinnedButNeverRawDownloads() {
        val client = HuggingFaceModelSearchClient(mockk(), mockk())
        val sha = "a".repeat(40)
        val metadata = Json.parseToJsonElement("""{"id":"owner/model","sha":"$sha","siblings":[{"rfilename":"model-Q4_K_M-00001-of-00002.gguf","size":100},{"rfilename":"model-Q4_K_M-00002-of-00002.gguf","size":100},{"rfilename":"mmproj-f16.gguf","size":50}]}""")
        val result = client.parseCompatibleFiles(metadata).single()
        assertEquals(3, result.discoveryAssets.size)
        assertEquals(1, result.discoveryAssets.count { it.role == "projector_candidate" })
        val entry = result.toCatalogEntry()
        assertEquals("", entry.downloadUrl)
        assertEquals(sha, entry.upstreamRevision)
        assertFalse(LocalCatalogCompatibilityResolver.resolve(entry, true, "SM8750").canDownload)
    }
}
