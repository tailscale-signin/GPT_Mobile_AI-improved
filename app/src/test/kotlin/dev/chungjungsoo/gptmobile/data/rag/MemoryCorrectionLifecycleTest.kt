package dev.chungjungsoo.gptmobile.data.rag

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCorrectionLifecycleTest {
    private fun repository() = FactVaultRepository(
        object : SecretVault {
            private val values = mutableMapOf<String, ByteArray>()
            override suspend fun put(secretRef: String, secret: ByteArray) {
                values[secretRef] = secret.copyOf()
            }
            override suspend fun read(secretRef: String) = values[secretRef]?.copyOf()
            override suspend fun delete(secretRef: String) {
                values.remove(secretRef)
            }
        },
        KnowledgeGraphEngine()
    )

    @Test fun reviewedCorrectionPreservesHistoryAndNeverRevivesDeletedEvidence() = runTest {
        val memory = repository()
        memory.prepareTurn("The toolbar should appear at the top", 1, 1, isLocal = true)
        val old = memory.state.value.facts.single { it.fact.relation.relationType == "MEMORY_EPISODE" }
        memory.prepareTurn("Actually, the toolbar should appear at the bottom", 1, 2, isLocal = true)
        val correction = memory.state.value.facts.single { it.meaning?.kind == "CORRECTION" }
        assertTrue(correction.awaitingReview)
        assertFalse(correction.enabled)
        assertEquals(setOf(old.id), correction.pendingReplacements.keys)
        memory.reviewFacts(setOf(correction.id), true)
        val active = memory.state.value.facts.single { it.id == correction.id }
        assertEquals(setOf(old.id), active.meaning?.correctsRecordIds)
        val now = memory.prepareTurn("Where should the toolbar appear?", 1, 3, isLocal = true, capture = false)
        assertFalse(now.facts.any { it.id == old.id })
        memory.validateRecall(now, 1, true)
        val history = memory.prepareTurn("What was the previous toolbar requirement?", 1, 4, isLocal = true, capture = false)
        assertTrue(history.facts.any { it.id == old.id })
        assertTrue(history.prefix().contains("SUPERSEDED"))
        memory.validateRecall(history, 1, true)
        memory.deleteFact(old.id)
        assertFalse(memory.prepareTurn("What was the previous toolbar requirement?", 1, 5, isLocal = true, capture = false).facts.any { it.id == old.id })
    }

    @Test fun privateAndCloudDisabledMemoriesStayOutOfCloudRecall() = runTest {
        val memory = repository()
        memory.prepareTurn("The toolbar should appear at the top", 1, 1, isLocal = true)
        assertTrue(memory.prepareTurn("toolbar", 1, 2, isLocal = false, capture = false).facts.isEmpty())
        memory.updateSettings(memory.state.value.settings.copy(allowCloudRecall = true))
        memory.saveManual("I decided to use SQLite because the data is private", localOnly = true)
        assertFalse(memory.prepareTurn("SQLite", 1, 3, isLocal = false, capture = false).facts.any { it.localOnly })
    }
}
