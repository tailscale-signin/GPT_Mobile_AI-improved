package dev.chungjungsoo.gptmobile.data.rag

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryIntelligenceTest {
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

    @Test fun `recurring questions record topic evidence rather than inferred personal facts`() = runTest {
        val memory = repository()
        repeat(3) { memory.prepareTurn("How does astrophotography work?", 1, it + 1, isLocal = true) }
        val topic = memory.state.value.facts.single()
        assertEquals("DISCUSSES", topic.fact.relation.relationType)
        assertTrue(topic.fact.target.name.contains("astrophotography"))
        memory.prepareTurn("How does astrophotography work?", 1, 1, isLocal = true)
        assertEquals(3, memory.state.value.topics.single { it.label == "astrophotography" }.messageKeys.size)
    }

    @Test fun `retries do not inflate reinforcement and opposing preferences invalidate old facts`() = runTest {
        val memory = repository()
        for (id in listOf(1, 2, 1, 2)) memory.prepareTurn("I prefer coffee", 1, id, isLocal = true)
        assertEquals(2, memory.state.value.facts.single().occurrences)
        memory.prepareTurn("I avoid coffee", 1, 3, isLocal = true)
        assertEquals(1, memory.state.value.facts.count { it.enabled })
        assertEquals("AVOIDS", memory.state.value.facts.single { it.enabled }.fact.relation.relationType)
        assertTrue(memory.state.value.facts.single { !it.enabled }.supersededBy != null)
    }

    @Test fun `semantic relevance retrieves different wording but never an unrelated repeated memory`() {
        val fact = MemoryLearning.observation("Enjoys astronomy")
        val relevant = VaultFact("a", fact)
        val unrelated = VaultFact("b", MemoryLearning.observation("Prefers coffee"), occurrences = 1000)
        val ranked = MemoryRecallPolicy.rank("Observing celestial objects", listOf(relevant, unrelated), semanticScores = mapOf("a" to 0.88, "b" to 0.1))
        assertEquals(listOf("a"), ranked.map { it.id })
    }

    @Test fun `semantic consolidation preserves negation numbers and scope`() {
        val fact = MemoryLearning.observation("I want exactly 12 blue widgets")
        val entry = VaultFact("x", fact)
        assertTrue(MemoryConsolidation.canMerge(entry, fact, "personal", 1.0))
        assertFalse(MemoryConsolidation.canMerge(entry, MemoryLearning.observation("I want exactly 13 blue widgets"), "personal", 0.99))
        assertFalse(MemoryConsolidation.canMerge(entry, MemoryLearning.observation("I do not want exactly 12 blue widgets"), "personal", 0.99))
        assertFalse(MemoryConsolidation.canMerge(entry, fact, "project:other", 1.0))
    }

    @Test fun `new memory is local by default and deleting a topic prevents relearning`() = runTest {
        val memory = repository()
        repeat(3) { memory.prepareTurn("Astrophotography ideas?", 1, it + 1, isLocal = true) }
        assertFalse(memory.state.value.settings.allowCloudRecall)
        assertTrue(memory.prepareTurn("Astrophotography", 1, 4, capture = false, isLocal = false).facts.isEmpty())
        memory.state.value.facts.toList().forEach { memory.deleteFact(it.id) }
        memory.prepareTurn("Astrophotography ideas?", 1, 5, isLocal = true)
        assertTrue(memory.state.value.facts.isEmpty())
    }
}
