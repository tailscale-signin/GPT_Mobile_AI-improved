package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class VoicePlaybackFailureTest {
    @Test fun primaryCpuAndSystemFailuresRetainTheirOwnObservedCausesAndRequestedBackend() {
        val acceleratorCause = IllegalStateException("accelerator failure")
        val cpuCause = IllegalArgumentException("CPU failure")
        val systemCause = UnsupportedOperationException("system failure")
        val primary = VoicePlaybackFailure("primary", acceleratorCause, "ENGINE_FAILED", "qnn-htp", "QNN_HTP", VoicePlaybackFailureStage.PREPARATION)
        val cpu = VoicePlaybackFailure("cpu", cpuCause, "SYNTHESIS_FAILED", "cpu", stage = VoicePlaybackFailureStage.SYNTHESIS)
        val system = VoicePlaybackFailure("system", systemCause, "SERVICE_FAILED", "system", stage = VoicePlaybackFailureStage.PLAYBACK, platformErrorCode = -4)
        val failure = primary.afterFallback(cpu).afterFallback(system)
        assertEquals("system", failure.message)
        assertSame(acceleratorCause, failure.cause)
        assertSame(cpuCause, failure.fallbackFailure?.cause)
        assertSame(systemCause, failure.terminalFallback?.cause)
        assertEquals("QNN_HTP", failure.requestedBackend)
        assertEquals(-4, failure.terminalFallback?.platformErrorCode)
        assertNull(failure.terminalFallback?.fallbackFailure)
        assertNull(primary.fallbackFailure)
    }
}
