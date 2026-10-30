package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRenderRecoveryTest {
    @Test fun timeoutRetriesLocallyAndReturnsSuccessfulFormatting() = runTest {
        var calls = 0
        val result = recoverChatRender(5000, false, RenderBudget(10, 3)) {
            calls++
            if (calls < 3) delay(20)
            "formatted"
        }
        assertEquals("formatted", result)
        assertEquals(3, calls)
    }

    @Test fun activeStreamDoesNotRepeatExpiredFormatting() = runTest {
        var calls = 0
        assertNull(
            recoverChatRender(5000, true, RenderBudget(10, 3)) {
                calls++
                delay(20)
                "late"
            }
        )
        assertEquals(1, calls)
    }

    @Test fun parseErrorsDoNotRepeatAndCancellationPropagates() = runTest {
        var calls = 0
        assertNull(
            recoverChatRender<String>(5000, false) {
                calls++
                error("invalid")
            }
        )
        assertEquals(1, calls)
        assertTrue(runCatching { recoverChatRender<String>(5000, false) { throw CancellationException("left screen") } }.exceptionOrNull() is CancellationException)
    }

    @Test fun staleAnswerDoesNotRestartFormatting() = runTest {
        var calls = 0
        assertNull(
            recoverChatRender(5000, false, stillCurrent = { false }) {
                calls++
                "old"
            }
        )
        assertEquals(0, calls)
    }

    @Test fun heapAlertsUseActualHeapBudget() {
        assertEquals("normal", ChatMemorySample(400, 1000).level)
        assertEquals("warning", ChatMemorySample(850, 1000).level)
        assertEquals("critical", ChatMemorySample(960, 1000).level)
        assertEquals("critical", ChatMemorySample(systemLowMemory = true).level)
    }
}
