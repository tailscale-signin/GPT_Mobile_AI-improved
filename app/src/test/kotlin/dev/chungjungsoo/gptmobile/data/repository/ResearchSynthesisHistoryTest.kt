package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchSynthesisHistoryTest {
    @Test fun defaultKeepsAllHistoricalAndCurrentConstraintsWhileExplicitCompressionPreservesOriginals() {
        val currentGoal = "Current goal 😀 東京 ".repeat(3000) + "Final constraint: never publish."
        val turns = (0..8).map { index ->
            ConversationTurn(
                MessageV2(content = if (index == 8) currentGoal else "Earlier task $index ".repeat(3000), platformType = null),
                if (index == 8) null else MessageV2(content = "Earlier evidence $index ".repeat(3000), platformType = "profile"),
                index == 8
            )
        }
        val frozen = turns.map { it.userMessage.content to it.assistantMessage?.content }
        assertSame(turns, prepareResearchSynthesisHistory(turns, compress = false))
        val compressed = prepareResearchSynthesisHistory(turns, compress = true)
        assertTrue(compressed.size < turns.size)
        assertEquals(currentGoal, compressed.single { it.isCurrentTurn }.userMessage.content)
        assertTrue(compressed.first().userMessage.content.contains("older context trimmed"))
        assertEquals(frozen, turns.map { it.userMessage.content to it.assistantMessage?.content })
    }
}
