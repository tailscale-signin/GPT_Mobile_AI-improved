package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConflatedRenderPreparationTest {
    @Test fun slowPreparationPublishesIntermediateFramesAndTheFinalAnswer() = runTest {
        val rendered = mutableListOf<Int>()
        flow {
            for (value in 1..20) {
                emit(value)
                delay(20)
            }
        }.prepareConflatedRender { value ->
            delay(100)
            value
        }.toList(rendered)
        assertEquals(1, rendered.first())
        assertEquals(20, rendered.last())
        assertTrue(rendered.size in 3..6)
        assertTrue(rendered.zipWithNext().all { (left, right) -> left < right })
    }

    @Test fun leavingRendererCancelsPendingPreparationWithoutPublishing() = runTest {
        val requests = MutableSharedFlow<Int>()
        val rendered = mutableListOf<Int>()
        var released = false
        val job = launch {
            requests.prepareConflatedRender { value ->
                try {
                    delay(10_000)
                    value
                } finally {
                    released = true
                }
            }.toList(rendered)
        }
        runCurrent()
        requests.emit(1)
        advanceTimeBy(1)
        job.cancelAndJoin()
        assertTrue(released)
        assertTrue(rendered.isEmpty())
    }
}
