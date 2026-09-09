package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.localruntime.LocalHistoryMessage
import dev.chungjungsoo.gptmobile.data.localruntime.LocalHistoryRole
import org.junit.Assert.assertEquals
import org.junit.Test

class RollingContextWindowCompactorTest {

    @Test
    fun `compactPriorTurns returns all turns when within context budget`() {
        val turns = listOf(
            turn("What is kotlin?", "Kotlin is a modern language."),
            turn("What about coroutines?", "Coroutines provide async programming.")
        )

        val result = RollingContextWindowCompactor.compactPriorTurns(
            priorTurns = turns,
            maxContextTokens = 1000,
            systemPrompt = "Be brief",
            currentUserPrompt = "Explain flows"
        )

        assertEquals(2, result.size)
        assertEquals(turns, result)
    }

    @Test
    fun `compactPriorTurns preserves anchor turn 0 and rolls recent turns when exceeding budget`() {
        val anchorTurn = turn("Anchor setup instruction for the task", "Understood, I will follow that task.")
        val middleTurn1 = turn("Intermediate step 1 with lots of words", "Reply 1")
        val middleTurn2 = turn("Intermediate step 2 with lots of words", "Reply 2")
        val recentTurn = turn("Recent question", "Recent answer")

        val turns = listOf(anchorTurn, middleTurn1, middleTurn2, recentTurn)

        // total char budget = 50 tokens * 4 = 200 chars.
        // system prompt = 10 chars, current user = 10 chars -> available = 180 chars.
        // anchorTurn chars ~ 75. Remaining budget ~ 105 chars.
        // recentTurn chars ~ 30.
        // middleTurn2 chars ~ 47.
        // middleTurn1 chars ~ 47 -> 30 + 47 + 47 = 124 > 105.
        // So middleTurn1 should be evicted, preserving anchorTurn + middleTurn2 + recentTurn.
        val result = RollingContextWindowCompactor.compactPriorTurns(
            priorTurns = turns,
            maxContextTokens = 50,
            systemPrompt = "1234567890",
            currentUserPrompt = "1234567890"
        )

        assertEquals(3, result.size)
        assertEquals(anchorTurn, result.first())
        assertEquals(middleTurn2, result[1])
        assertEquals(recentTurn, result.last())
    }

    @Test
    fun `compactPriorTurns keeps anchor turn if budget is tight`() {
        val anchorTurn = turn("Anchor", "Anchor reply")
        val turn1 = turn("Intermediate 1", "Reply 1")
        val turn2 = turn("Intermediate 2", "Reply 2")

        val result = RollingContextWindowCompactor.compactPriorTurns(
            priorTurns = listOf(anchorTurn, turn1, turn2),
            maxContextTokens = 6, // 24 chars
            systemPrompt = null,
            currentUserPrompt = ""
        )

        // Anchor turn alone is 18 chars; cannot fit turn 2 (21 chars) in remaining 6 chars.
        assertEquals(1, result.size)
        assertEquals(anchorTurn, result.single())
    }

    @Test
    fun `compactLocalHistoryMessages preserves anchor and compacts recent messages`() {
        val messages = listOf(
            LocalHistoryMessage(LocalHistoryRole.USER, "Anchor initial prompt"),
            LocalHistoryMessage(LocalHistoryRole.MODEL, "Anchor model response"),
            LocalHistoryMessage(LocalHistoryRole.USER, "Middle turn message that overflows"),
            LocalHistoryMessage(LocalHistoryRole.MODEL, "Middle turn reply that overflows"),
            LocalHistoryMessage(LocalHistoryRole.USER, "Recent user message"),
            LocalHistoryMessage(LocalHistoryRole.MODEL, "Recent model response")
        )

        // 35 tokens * 4 = 140 chars total budget.
        val result = RollingContextWindowCompactor.compactLocalHistoryMessages(
            messages = messages,
            maxContextTokens = 35,
            systemPrompt = null,
            currentUserPrompt = ""
        )

        // Preserves anchor (indices 0, 1) and recent (indices 4, 5) while evicting middle (2, 3)
        assertEquals(4, result.size)
        assertEquals("Anchor initial prompt", result[0].text)
        assertEquals("Anchor model response", result[1].text)
        assertEquals("Recent user message", result[2].text)
        assertEquals("Recent model response", result[3].text)
    }

    private fun turn(user: String, assistant: String) = ConversationTurn(
        userMessage = MessageV2(content = user, platformType = null),
        assistantMessage = MessageV2(content = assistant, platformType = "test"),
        isCurrentTurn = false
    )
}
