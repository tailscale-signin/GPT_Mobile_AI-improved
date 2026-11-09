package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CombinedOperationTracePrivacyTest {
    private val privateOwner = "frame-operation-privacy-test"

    @Before fun enableCapture() {
        AppLogRecorder.setPrivateSession(privateOwner, false)
        AppLogRecorder.setEnabled(false)
        AppLogRecorder.setEnabled(true)
    }

    @After fun stopCapture() {
        AppLogRecorder.setPrivateSession(privateOwner, false)
        AppLogRecorder.setEnabled(false)
    }

    private fun slowToken(runId: String) = requireNotNull(CombinedOperationTrace.begin("text_measure", CombinedOperationOwner(runId = runId)))
        .copy(startedNanos = System.nanoTime() - 200_000_000L)

    private fun observedRuns() = CombinedOperationTrace.frameWindow(Long.MIN_VALUE, Long.MAX_VALUE).samples.mapNotNull { it.runId }

    @Test fun frameCoverageRetainsWorkBelowTheVerboseTimingThreshold() {
        val token = requireNotNull(CombinedOperationTrace.begin("tool_grant_revoke", CombinedOperationOwner(runId = "short-work")))
            .copy(startedNanos = System.nanoTime() - 8_000_000L)
        CombinedOperationTrace.end(token)
        val sample = CombinedOperationTrace.frameWindow(Long.MIN_VALUE, Long.MAX_VALUE).samples.single()
        assertEquals("tool_grant_revoke", sample.operation)
        assertEquals("short-work", sample.runId)
        assertTrue(sample.endedNanos - sample.startedNanos >= CombinedOperationTrace.FRAME_SAMPLE_THRESHOLD_NANOS)
    }

    @Test fun shortWorkCannotRepopulateFrameCoverageAcrossAPrivateSession() {
        val token = requireNotNull(CombinedOperationTrace.begin("ui_Home_measure", CombinedOperationOwner(runId = "old-work")))
            .copy(startedNanos = System.nanoTime() - 8_000_000L)
        AppLogRecorder.setPrivateSession(privateOwner, true)
        AppLogRecorder.setPrivateSession(privateOwner, false)
        CombinedOperationTrace.end(token)
        assertTrue(observedRuns().isEmpty())
    }

    @Test fun privacyEntryClearsHistoryAndSpanningTokensCannotPublishAfterExit() {
        val inFlight = slowToken("public-in-flight")
        CombinedOperationTrace.end(slowToken("earlier-public"))
        assertEquals(listOf("earlier-public"), observedRuns())
        AppLogRecorder.setPrivateSession(privateOwner, true)
        assertTrue(observedRuns().isEmpty())
        val privateToken = CombinedOperationTrace.begin("text_measure", CombinedOperationOwner(runId = "private-run"))
        assertTrue(privateToken?.diagnosticCaptureEpoch == null)
        AppLogRecorder.setPrivateSession(privateOwner, false)
        CombinedOperationTrace.end(inFlight)
        CombinedOperationTrace.end(privateToken?.copy(startedNanos = System.nanoTime() - 200_000_000L))
        assertTrue(observedRuns().isEmpty())
        CombinedOperationTrace.end(slowToken("fresh-public"))
        assertEquals(listOf("fresh-public"), observedRuns())
    }

    @Test fun disablingCaptureInvalidatesOldTokensBeforeItIsEnabledAgain() {
        val inFlight = slowToken("before-disable")
        CombinedOperationTrace.end(slowToken("earlier-public"))
        AppLogRecorder.setEnabled(false)
        assertTrue(observedRuns().isEmpty())
        AppLogRecorder.setEnabled(true)
        CombinedOperationTrace.end(inFlight)
        assertTrue(observedRuns().isEmpty())
    }

    @Test fun clearingLogsCannotRestoreOperationHistoryFromAnInFlightToken() = runBlocking {
        val inFlight = slowToken("before-clear")
        val epoch = requireNotNull(inFlight.diagnosticCaptureEpoch)
        CombinedOperationTrace.end(slowToken("earlier-public"))
        AppLogRecorder.clear()
        withTimeout(5_000) {
            while (AppLogRecorder.operationCaptureCurrent(epoch) || observedRuns().isNotEmpty()) delay(1)
        }
        assertFalse(AppLogRecorder.operationCaptureCurrent(epoch))
        CombinedOperationTrace.end(inFlight)
        assertTrue(observedRuns().isEmpty())
        CombinedOperationTrace.end(slowToken("fresh-public"))
        assertEquals(listOf("fresh-public"), observedRuns())
    }
}
