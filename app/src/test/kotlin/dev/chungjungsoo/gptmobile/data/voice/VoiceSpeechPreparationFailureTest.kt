package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceSpeechPreparationFailureTest {
    private fun evidence(source: String = "qnn-gpu") = VoiceSpeechExecutionEvidence(
        VoiceSpeechBackend.QNN_GPU, "cpu", "model", "runtime", "device", VoiceSpeechStage.CPU_FALLBACK,
        failure = VoiceSpeechFailureCode.GRAPH_PARTITION, observedAtMillis = 100,
        nativeCode = 11, failureStage = VoiceSpeechStage.PREPARING, failedProvider = source, failedTokenCount = 17
    )

    @Test fun failedCpuOpenRetainsLiveAcceleratorCauseAndTypedEvidence() {
        val original = IllegalStateException("Accelerator error")
        val cpu = IllegalArgumentException("CPU error")
        val receipt = evidence()
        try {
            preserveVoicePreparationFailure(receipt, original) { throw cpu }
            fail("Expected preparation failure")
        } catch (failure: VoiceSpeechPreparationFailure) {
            assertSame(receipt, failure.executionEvidence)
            assertSame(cpu, failure.cause)
            assertEquals(listOf(original), failure.suppressed.toList())
            assertEquals(11, failure.executionEvidence.nativeCode)
            assertEquals(17, failure.executionEvidence.failedTokenCount)
        }
    }

    @Test fun cachedRejectionUsesCodedEvidenceWithoutInventingAnOriginalException() {
        val cpu = IllegalStateException("CPU error")
        try {
            preserveVoicePreparationFailure(evidence("qnn-htp")) { throw cpu }
            fail("Expected preparation failure")
        } catch (failure: VoiceSpeechPreparationFailure) {
            assertSame(cpu, failure.cause)
            assertTrue(failure.suppressed.isEmpty())
            assertEquals("qnn-htp", failure.executionEvidence.failedProvider)
        }
    }

    @Test fun cancellationAndErrorsPassThroughWithoutBeingReclassified() {
        for (stop in listOf(CancellationException("Cancelled"), LinkageError("Missing runtime"), OutOfMemoryError("Allocation"))) {
            try {
                preserveVoicePreparationFailure(evidence()) { throw stop }
                fail("Expected stop")
            } catch (actual: Throwable) {
                assertSame(stop, actual)
            }
        }
    }

    @Test fun cpuBaselineFailureCannotBecomeAnAcceleratorFallbackReceipt() {
        val cpu = IllegalStateException("CPU baseline unavailable")
        for (receipt in listOf(null, evidence("cpu"), evidence().copy(failure = null))) {
            try {
                preserveVoicePreparationFailure(receipt) { throw cpu }
                fail("Expected CPU failure")
            } catch (actual: IllegalStateException) {
                assertSame(cpu, actual)
            }
        }
        assertEquals("prepared", preserveVoicePreparationFailure(evidence()) { "prepared" })
    }
}
