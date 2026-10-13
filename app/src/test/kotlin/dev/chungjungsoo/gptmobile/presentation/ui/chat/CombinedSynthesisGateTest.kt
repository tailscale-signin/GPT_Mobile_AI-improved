package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedSynthesisGateTest {
    @Test fun `stale room emissions cannot dispatch the same synthesis twice`() {
        val gate = CombinedSynthesisGate()
        val source = CombinedModelResponse(platformUid = "one", platformName = "One", modelName = "model", content = "original evidence")
        assertTrue(gate.reserve(7, listOf(source)))
        assertFalse(gate.reserve(7, listOf(source.copy())))
        assertTrue(gate.reserve(7, listOf(source.copy(content = "new evidence"))))
        gate.reset(7)
        assertTrue(gate.reserve(7, listOf(source.copy(content = "new evidence"))))
    }

    @Test fun `persisted row follows its identity when the history window moves`() {
        val optimistic = MessageV2(id = 0, chatId = 7, platformType = null, content = "new")
        val saved = optimistic.copy(id = 30)
        assertNull(persistedTurnIndex(emptyList(), optimistic, saved))
        assertEquals(0, persistedTurnIndex(listOf(saved), optimistic, saved))
        assertEquals(1, persistedTurnIndex(listOf(optimistic.copy(id = 20, content = "older"), optimistic), optimistic, saved))
        assertNull(persistedTurnIndex(listOf(optimistic.copy(id = 40, content = "different")), optimistic, saved))
    }
}
