package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferenceMetrics
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBenchmarkOutputTest {
    @Test fun drainFenceIsAcknowledgedAndNativeMetricsArePreserved() = runTest {
        val acknowledged = CompletableDeferred<Unit>()
        val expected = LocalInferenceMetrics(estimatedTokens = 2)
        var metrics: LocalInferenceMetrics? = null
        val result = withTimeout(1000) {
            collectLocalBenchmarkOutput(
                flow {
                    emit(LocalRuntimeEvent.TextDelta("Hello"))
                    emit(LocalRuntimeEvent.Drain(acknowledged))
                    acknowledged.await()
                    emit(LocalRuntimeEvent.Metrics(expected))
                    emit(LocalRuntimeEvent.Done)
                }
            ) { metrics = it }
        }
        assertTrue(acknowledged.isCompleted)
        assertTrue(result.completed)
        assertEquals(5, result.outputCharacters)
        assertEquals(expected, metrics)
        assertNull(result.failure)
    }

    @Test fun emptyOrWhitespaceAnswerIsNotASuccess() = runTest {
        for (text in listOf("", " \n\t")) {
            val result = collectLocalBenchmarkOutput(flowOf(LocalRuntimeEvent.TextDelta(text), LocalRuntimeEvent.Done)) { }
            assertFalse(result.completed)
            assertTrue(result.failure.orEmpty().startsWith("LOCAL_EMPTY_OUTPUT"))
        }
    }

    @Test fun missingCompletionIsAnExplicitFailureEvenWithText() = runTest {
        val result = collectLocalBenchmarkOutput(flowOf(LocalRuntimeEvent.TextDelta("Partial"))) { }
        assertFalse(result.completed)
        assertTrue(result.failure.orEmpty().startsWith("LOCAL_STREAM_INCOMPLETE"))
    }

    @Test fun firstNativeErrorIsNotErasedByDoneOrLaterErrors() = runTest {
        val result = collectLocalBenchmarkOutput(flowOf(LocalRuntimeEvent.Error("original"), LocalRuntimeEvent.Error("later"), LocalRuntimeEvent.Done)) { }
        assertFalse(result.completed)
        assertEquals("original", result.failure)
    }

    @Test fun blankNativeErrorHasAnActionableClassification() = runTest {
        val result = collectLocalBenchmarkOutput(flowOf(LocalRuntimeEvent.Error(""), LocalRuntimeEvent.Done)) { }
        assertTrue(result.failure.orEmpty().startsWith("LOCAL_RUNTIME_ERROR"))
    }

    @Test fun thinkingAloneDoesNotCountAsAnAnswer() = runTest {
        val result = collectLocalBenchmarkOutput(flowOf(LocalRuntimeEvent.ThinkingDelta("thinking"), LocalRuntimeEvent.Done)) { }
        assertFalse(result.completed)
        assertEquals(0, result.outputCharacters)
    }

    @Test fun cancellationIsPropagatedInsteadOfBeingTurnedIntoSuccess() = runTest {
        val failure = runCatching {
            collectLocalBenchmarkOutput(flow { throw CancellationException("stop") }) { }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}
