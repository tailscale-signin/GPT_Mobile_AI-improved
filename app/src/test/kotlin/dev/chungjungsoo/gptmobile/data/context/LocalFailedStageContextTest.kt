package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFailedStageContextTest {
    private fun turn(id: Int, request: String, answer: String?, current: Boolean = false) = ConversationTurn(
        MessageV2(id = id, chatId = 1, platformType = null, content = request),
        answer?.let { MessageV2(id = id + 100, chatId = 1, platformType = "local", content = it) },
        current
    )

    @Test fun retryOmitsFailedAnswersAndControlHistoryButPreservesOtherUserConstraints() {
        val before = turn(1, "Keep all quantities in kg.", "Understood.")
        val failed = turn(2, "Find the parcel mass.", "Repeated unverified answer ".repeat(1000))
        val oldRetry = turn(3, "retry", "Failed again ".repeat(1000))
        val constraint = turn(4, "The parcel identifier is ZX-43.", "Saved.")
        val current = turn(5, "Find the parcel mass.", null, true)
        assertEquals(listOf(before, constraint, current), LocalFailedStageContext.prepare(listOf(before, failed, oldRetry, constraint, current), 2))
        assertTrue(failed.assistantMessage!!.content.startsWith("Repeated unverified answer"))
    }

    @Test fun unknownSourceCannotSilentlyRemoveContext() {
        val turns = listOf(turn(1, "Earlier constraint", "Earlier answer"), turn(2, "Task", null, true))
        assertEquals(turns, LocalFailedStageContext.prepare(turns, 99))
    }
}
