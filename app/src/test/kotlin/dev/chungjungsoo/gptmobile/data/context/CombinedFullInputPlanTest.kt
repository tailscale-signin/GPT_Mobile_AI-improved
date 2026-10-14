package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedFullInputPlanTest {
    private fun turn(text: String, current: Boolean) = ConversationTurn(MessageV2(content = text, platformType = null), null, current)

    @Test fun fullInputPreservesHistoryThatOrdinaryPlanningWouldEvict() {
        val turns = listOf(turn("old constraint ".repeat(1200), false), turn("Current frozen input", true))
        val settings = TokenBudgetSettings(contextTokens = 1024, outputTokens = 128)
        assertEquals(1, ContextBudgetService.plan(turns, "", emptyList(), settings).turns.size)
        val failure = runCatching { ContextBudgetService.planFullInput(turns, "", settings) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().startsWith("COMBINED_CONTEXT_CAPACITY"))
    }

    @Test fun fittingCorpusIsDeliveredInOrderWithoutChangingCurrentTurnFlags() {
        val turns = listOf(turn("Original restriction", false), turn("Full contributions", true))
        val result = ContextBudgetService.planFullInput(turns, "Frozen system", TokenBudgetSettings(contextTokens = 2048, outputTokens = 256))
        assertEquals(turns, result.turns)
        assertEquals(256, result.outputTokens)
        assertTrue(result.tools.isEmpty())
        assertTrue(result.notice.contains("FULL_INPUT"))
        assertTrue(result.notice.contains("does not establish semantic coverage"))
    }

    @Test fun outputAllowanceCannotConsumeTheWholeFiniteContext() {
        val failure = runCatching {
            ContextBudgetService.planFullInput(listOf(turn("Goal", true)), "", TokenBudgetSettings(contextTokens = 1024, outputTokens = 1024))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun unlimitedAppSettingStillRetainsTheFullCorpus() {
        val turns = listOf(turn("history ".repeat(3000), false), turn("contribution ".repeat(3000), true))
        assertEquals(turns, ContextBudgetService.planFullInput(turns, "", TokenBudgetSettings()).turns)
    }
}
