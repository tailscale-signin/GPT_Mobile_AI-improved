package dev.chungjungsoo.gptmobile.data.voice

import android.speech.SpeechRecognizer
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceBenchRecognitionSupportTest {
    private class Service(private val answer: (Int, (RecognitionQualification) -> Unit) -> Unit) {
        val bindings = mutableListOf<Binding>()
        inner class Binding(private val index: Int) : VoiceBenchRecognitionBinding {
            var queries = 0
            var closed = 0
            override fun query(result: (RecognitionQualification) -> Unit) {
                queries++
                answer(index, result)
            }
            override fun close() {
                closed++
            }
        }
        fun create(): VoiceBenchRecognitionBinding {
            assertTrue(bindings.all { it.closed == 1 })
            return Binding(bindings.size).also(bindings::add)
        }
        fun verifyReleased() {
            bindings.forEach {
                assertEquals(1, it.queries)
                assertEquals(1, it.closed)
            }
        }
    }

    @Test fun aTimeoutRebindsLocallyOnceAndRecordsRecoveryInsteadOfLosingTheLocale() = runTest {
        val service = Service { index, result -> if (index == 1) result(RecognitionQualification(RecognitionSupportStatus.SUPPORTED)) }
        val interrupt = AtomicReference<(() -> Unit)?>(null)
        val outcome = voiceBenchRecognitionSupport({ true }, interrupt, create = service::create)
        assertEquals(RecognitionSupportStatus.SUPPORTED, outcome.qualification?.status)
        assertEquals(2, outcome.attempts)
        assertEquals(1, outcome.timedOutAttempts)
        assertNull(outcome.reason)
        assertNull(interrupt.get())
        assertEquals(2, service.bindings.size)
        service.verifyReleased()
    }

    @Test fun lateSuccessFromRetiredBindingCannotQualifyTheNewBinding() = runTest {
        var first: ((RecognitionQualification) -> Unit)? = null
        val service = Service { index, result ->
            if (index == 0) {
                first = result
            } else {
                requireNotNull(first)(RecognitionQualification(RecognitionSupportStatus.SUPPORTED))
                result(RecognitionQualification(RecognitionSupportStatus.UNSUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
            }
        }
        val outcome = voiceBenchRecognitionSupport({ true }, AtomicReference(null), create = service::create)
        assertEquals(RecognitionSupportStatus.UNSUPPORTED, outcome.qualification?.status)
        assertEquals("UNSUPPORTED_ANDROID_ERROR_${SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED}", outcome.reason)
        assertEquals(1.0, outcome.metrics["capabilityTimeouts"])
        service.verifyReleased()
    }

    @Test fun repeatedTimeoutRemainsUnavailableAfterTheBoundedRetry() = runTest {
        val service = Service { _, _ -> }
        val outcome = voiceBenchRecognitionSupport({ true }, AtomicReference(null), create = service::create)
        assertNull(outcome.qualification)
        assertEquals("CAPABILITY_QUERY_TIMEOUT", outcome.reason)
        assertEquals(2, outcome.attempts)
        assertEquals(2, outcome.timedOutAttempts)
        service.verifyReleased()
    }

    @Test fun explicitUnsupportedOrRateLimitedRepliesAreNotRetriedOrPromoted() = runTest {
        for (qualification in listOf(
            RecognitionQualification(RecognitionSupportStatus.UNVERIFIED),
            RecognitionQualification(RecognitionSupportStatus.LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE),
            RecognitionQualification(RecognitionSupportStatus.FAILED, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS)
        )) {
            val service = Service { _, result -> result(qualification) }
            val outcome = voiceBenchRecognitionSupport({ true }, AtomicReference(null), create = service::create)
            assertEquals(qualification, outcome.qualification)
            assertEquals(1, outcome.attempts)
            assertEquals(0, outcome.timedOutAttempts)
            service.verifyReleased()
        }
    }

    @Test fun userCancellationReleasesThePendingBindingWithoutRetrying() = runTest {
        val service = Service { _, _ -> }
        val interrupt = AtomicReference<(() -> Unit)?>(null)
        val job = async { voiceBenchRecognitionSupport({ true }, interrupt, create = service::create) }
        runCurrent()
        requireNotNull(interrupt.get()).invoke()
        try {
            job.await()
            throw AssertionError("Cancellation must not become capability support")
        } catch (_: CancellationException) {
            assertTrue(job.isCancelled)
        }
        assertEquals(1, service.bindings.size)
        assertNull(interrupt.get())
        service.verifyReleased()
    }
}
