package dev.chungjungsoo.gptmobile.data.rag

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRebuildPolicyTest {
    private class Vault : SecretVault {
        val values = mutableMapOf<String, ByteArray>()
        override suspend fun put(secretRef: String, secret: ByteArray) {
            values[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = values[secretRef]?.copyOf()
        override suspend fun delete(secretRef: String) {
            values.remove(secretRef)
        }
    }

    @Test fun `stale edit fails and local only facts never reach remote recall`() = runBlocking {
        val repo = FactVaultRepository(Vault(), KnowledgeGraphEngine())
        repo.saveManual("I prefer concise explanations", localOnly = true)
        val first = repo.state.value.facts.single()
        repo.saveManual("I prefer detailed explanations", first.id, expectedRevision = first.revision, localOnly = true)
        assertTrue(runCatching { repo.saveManual("stale overwrite", first.id, expectedRevision = first.revision) }.isFailure)
        repo.setEnabled(true)
        repo.updateSettings(repo.state.value.settings.copy(allowCloudRecall = true))
        assertTrue(repo.prepareTurn("my preferences", 1, 2, isLocal = false, capture = false).facts.isEmpty())
        assertEquals(1, repo.prepareTurn("my preferences", 1, 2, isLocal = true, capture = false, includeLocalOnly = true).facts.size)
    }

    @Test fun `unrelated pin and expired memory do not override eligibility`() = runBlocking {
        val repo = FactVaultRepository(Vault(), KnowledgeGraphEngine())
        repo.saveManual("I use Blender")
        repo.pin(repo.state.value.facts.single().id, true)
        repo.saveManual("My gardening preference is roses", validUntilMillis = 1)
        repo.setEnabled(true)
        assertTrue(repo.prepareTurn("explain volcanoes", 1, 2, isLocal = true, capture = false, includeLocalOnly = true).facts.isEmpty())
        assertTrue(repo.prepareTurn("gardening roses", 1, 2, isLocal = true, capture = false, includeLocalOnly = true).facts.isEmpty())
    }

    @Test fun `permission revocation rejects an already prepared packet`() = runBlocking {
        val repo = FactVaultRepository(Vault(), KnowledgeGraphEngine())
        repo.saveManual("My phone is an ASUS")
        repo.setEnabled(true)
        repo.updateSettings(repo.state.value.settings.copy(allowCloudRecall = true))
        val packet = repo.prepareTurn("my phone", 1, 2, capture = false)
        assertFalse(packet.facts.isEmpty())
        repo.updateSettings(repo.state.value.settings.copy(allowCloudRecall = false))
        assertTrue(runCatching { repo.validateRecall(packet, 1, false) }.isFailure)
    }
}
