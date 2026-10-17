package dev.chungjungsoo.gptmobile.data.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySelectionTest {
    private fun fact(id: String, text: String, pinned: Boolean = false) = VaultFact(id, MemoryLearning.observation(text), pinned = pinned)

    @Test fun selectionCollapsesEquivalentUnicodeAndWhitespaceButKeepsDifferentQuantities() {
        val result = MemoryRecallPolicy.select("coffee", listOf(fact("a", "Coffee 12 cups"), fact("b", "Ｃｏｆｆｅｅ  12 cups"), fact("c", "Coffee 13 cups")), FactVaultSettings())
        assertEquals(2, result.size)
        assertTrue(result.any { "13" in it.fact.target.name })
    }

    @Test fun pinnedPreferenceAppliesOnlyToRelevantMemories() {
        val result = MemoryRecallPolicy.select("coffee", listOf(fact("a", "Coffee beans from the cafe"), fact("b", "Coffee habit", true), fact("c", "Astronomy hobby", true)), FactVaultSettings(maxRecall = 1))
        assertEquals(listOf("b"), result.map { it.id })
    }

    @Test fun oversizedMemoryCannotCrowdOutFittingRelevantMemory() {
        val result = MemoryRecallPolicy.select("coffee", listOf(fact("long", "coffee ".repeat(300), true), fact("short", "Coffee preference")), FactVaultSettings(recallTokens = 128))
        assertEquals(listOf("short"), result.map { it.id })
    }
}
