package dev.chungjungsoo.gptmobile.data.huggingface

import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubArtifactIdentityTest {
    @Test
    fun identityKeepsCasePunctuationAndLongSuffixesDistinct() {
        val prefix = "a".repeat(180)
        val paths = listOf("a+b.litertlm", "a_b.litertlm", "A_b.litertlm", "${prefix}1.litertlm", "${prefix}2.litertlm")
        assertEquals(paths.size, paths.map { hubArtifactId("owner/model", it) }.distinct().size)
        assertNotEquals(hubArtifactId("Owner/model", "file"), hubArtifactId("owner/model", "file"))
        assertEquals(hubArtifactId("owner/model", "file"), hubArtifactId("owner/model", "file"))
    }

    @Test
    fun oneMetadataFailurePreservesOtherResults() = runBlocking {
        var failed = 0
        val results = listOf(
            async { isolatedHubRequest { listOf("working") } },
            async { isolatedHubRequest<String>(onFailure = { failed++ }) { error("timeout") } }
        ).awaitAll().flatten()
        assertEquals(listOf("working"), results)
        assertEquals(1, failed)
        var cancelled = false
        try {
            isolatedHubRequest<String> { throw CancellationException("user cancelled") }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun oldIdentityRequiresUnambiguousFullFileProvenance() {
        val url = "https://huggingface.co/owner/repo/resolve/old/sub/model.litertlm"
        val old = CatalogEntry(id = "hf_old", downloadUrl = url)
        val available = CatalogEntry(id = "hf_new", downloadUrl = url.replace("/old/", "/new/"))
        assertEquals("hf_old", preserveInstalledHubIdentity(available, listOf(old)).id)
        assertEquals("hf_new", preserveInstalledHubIdentity(available, listOf(old, old.copy(id = "hf_ambiguous"))).id)
        assertEquals("hf_new", preserveInstalledHubIdentity(available, listOf(old.copy(downloadUrl = url.replace("sub/", "other/")))).id)
    }
}
