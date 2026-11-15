package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidents
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceAdmissionFailureReportingTest {
    @Before
    @After
    fun reset() {
        IssueReportIncidents.initialize(ApplicationProvider.getApplicationContext<Context>())
        LocalDiagnosticsPolicy.setEnabled(false)
        IssueReportIncidents.privacyChanged(false)
        IssueReportIncidents.debugPreferenceChanged(false)
        IssueReportIncidents.clearRecent()
    }

    @Test fun submissionFalseReportsOnlyItsObservedBoundaryWithoutBorrowingThePreviousRequest() {
        LocalDiagnosticsPolicy.setEnabled(true)
        assertFalse(AppLogRecorder.enabled.value)
        val fixture = Fixture(submitResult = false)
        try {
            fixture.capture()
            fixture.session.admission(VoiceAdmissionStage.FAILED, "SELECTED_MODEL_NOT_LOADED", "prior-request")
            val report = IssueReportIncidents.pending.value.single()
            assertAdmissionBoundary(report)
            val values = requireNotNull(report.context?.diagnostics).values
            assertFalse(values.containsKey("voice_chat_failure_phase"))
            assertFalse(values.containsKey("voice_chat_failure_code"))
            assertFalse(values.containsKey("voice_chat_failure_code_hash"))
            assertFalse(report.approvedPayload().contains("prior-request"))
            assertEquals("prior-request", fixture.requestId)
            assertEquals(VoicePhase.ERROR, fixture.session.state.phase)
            assertEquals("PRIVATE_PROMPT", fixture.session.state.transcript)
            assertTrue(fixture.session.state.hasDraft)
            assertFalse(fixture.session.state.awaitingReply)
            assertEquals(1, fixture.submissions)
            assertEquals(1, fixture.audio.captures)
        } finally {
            fixture.session.close()
        }
    }

    @Test fun exactOwnedAdmissionRetainsAKnownCodeOrOnlyTheAllowlistedReasonHash() {
        for (reason in listOf("SELECTED_MODEL_NOT_LOADED", "QUEUED_TURN_REMOVED")) {
            reset()
            LocalDiagnosticsPolicy.setEnabled(true)
            val fixture = Fixture()
            try {
                fixture.capture()
                if (reason == "QUEUED_TURN_REMOVED") {
                    fixture.session.admission(VoiceAdmissionStage.QUEUED, requestId = fixture.requestId)
                    assertFalse(fixture.session.state.hasDraft)
                    assertTrue(IssueReportIncidents.pending.value.isEmpty())
                }
                fixture.session.admission(VoiceAdmissionStage.FAILED, reason, fixture.requestId)
                val report = IssueReportIncidents.pending.value.single()
                assertAdmissionBoundary(report)
                val values = requireNotNull(report.context?.diagnostics).values
                assertEquals(JsonPrimitive("message_admission"), values["voice_chat_failure_phase"])
                assertEquals(JsonPrimitive(IssueReportContext.identifierHash("voice-chat-failure", reason)), values["voice_chat_failure_code_hash"])
                assertEquals(if (reason == "SELECTED_MODEL_NOT_LOADED") JsonPrimitive(reason) else null, values["voice_chat_failure_code"])
                assertEquals(VoicePhase.ERROR, fixture.session.state.phase)
                assertTrue(fixture.session.state.hasDraft)
                assertFalse(fixture.session.state.awaitingReply)
                assertEquals("PRIVATE_PROMPT", fixture.session.state.transcript)
                assertEquals(1, fixture.submissions)
                assertEquals(1, fixture.audio.captures)
                assertTrue(fixture.session.state.notice.orEmpty().contains(reason))
            } finally {
                fixture.session.close()
            }
        }
    }

    @Test fun unknownAdmissionReasonIsNeitherSerializedNorFingerprinted() {
        for (reason in listOf(null, "UNRECOGNIZED_REASON", "PRIVATE_REASON https://private.invalid/secret", "SELECTED_MODEL_NOT_LOADED PRIVATE_SUFFIX")) {
            reset()
            LocalDiagnosticsPolicy.setEnabled(true)
            val fixture = Fixture()
            try {
                fixture.capture()
                fixture.session.admission(VoiceAdmissionStage.FAILED, reason, fixture.requestId)
                val report = IssueReportIncidents.pending.value.single()
                assertAdmissionBoundary(report)
                val values = requireNotNull(report.context?.diagnostics).values
                assertEquals(JsonPrimitive("message_admission"), values["voice_chat_failure_phase"])
                assertFalse(values.containsKey("voice_chat_failure_code"))
                assertFalse(values.containsKey("voice_chat_failure_code_hash"))
                if (reason != null) {
                    assertTrue(fixture.session.state.notice.orEmpty().contains(reason))
                    assertFalse(report.approvedPayload().contains(reason))
                    assertFalse(report.approvedPayload().contains(requireNotNull(IssueReportContext.identifierHash("voice-chat-failure", reason))))
                }
            } finally {
                fixture.session.close()
            }
        }
    }

    @Test fun foreignAndRetiredAdmissionReasonsCannotReportOrInterruptAnotherPlayback() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val fixture = Fixture()
        try {
            fixture.capture()
            val original = fixture.requestId
            fixture.session.admission(VoiceAdmissionStage.FAILED, "SELECTED_MODEL_NOT_LOADED", "foreign-request")
            assertEquals(VoicePhase.THINKING, fixture.session.state.phase)
            assertTrue(fixture.session.state.awaitingReply)
            assertTrue(IssueReportIncidents.pending.value.isEmpty())
            assertTrue(fixture.session.beginMessage("historical", manual = true))
            fixture.session.reply("historical", "Historical captions stay available.")
            val stops = fixture.audio.stops
            fixture.session.admission(VoiceAdmissionStage.FAILED, "PREPARATION_FAILED", original)
            assertEquals(VoicePhase.SPEAKING, fixture.session.state.phase)
            assertEquals("Historical captions stay available.", fixture.session.state.reply)
            assertEquals(stops, fixture.audio.stops)
            assertTrue(fixture.session.state.hasDraft)
            assertTrue(IssueReportIncidents.pending.value.isEmpty())
            fixture.session.admission(VoiceAdmissionStage.FAILED, "SELECTED_MODEL_NOT_LOADED", original)
            assertTrue(IssueReportIncidents.pending.value.isEmpty())
        } finally {
            fixture.session.close()
        }
        reset()
        LocalDiagnosticsPolicy.setEnabled(true)
        val restored = Fixture()
        try {
            restored.session.restore("PRIVATE_RESTORED_PROMPT", "", review = true, pending = true, pendingRequestId = "restored-request")
            restored.session.admission(VoiceAdmissionStage.FAILED, "ADMISSION_INTERRUPTED", "restored-request")
            assertEquals(VoicePhase.REVIEWING, restored.session.state.phase)
            assertTrue(restored.session.state.hasDraft)
            assertEquals(0, restored.audio.captures)
            assertEquals(0, restored.submissions)
            assertTrue(IssueReportIncidents.pending.value.isEmpty())
        } finally {
            restored.session.close()
        }
    }

    @Test fun privateAndInvalidatedAdmissionOriginsCannotReportWhenPrivacyReopens() {
        for (submissionFalse in listOf(false, true)) {
            for (privateOrigin in listOf(false, true)) {
                reset()
                LocalDiagnosticsPolicy.setEnabled(true)
                IssueReportIncidents.privacyChanged(privateOrigin)
                val reopen: () -> Unit = {
                    if (!privateOrigin) IssueReportIncidents.privacyChanged(true)
                    IssueReportIncidents.privacyChanged(false)
                }
                val fixture = Fixture(submitResult = !submissionFalse, beforeSubmitReturn = if (submissionFalse) reopen else ({}))
                try {
                    fixture.capture()
                    if (!submissionFalse) {
                        reopen()
                        fixture.session.admission(VoiceAdmissionStage.FAILED, "SELECTED_MODEL_NOT_LOADED", fixture.requestId)
                    }
                    assertEquals(VoicePhase.ERROR, fixture.session.state.phase)
                    assertTrue(fixture.session.state.hasDraft)
                    assertEquals("PRIVATE_PROMPT", fixture.session.state.transcript)
                    assertTrue(IssueReportIncidents.pending.value.isEmpty())
                    assertNull(VoiceDiagnosticTrace.manualSnapshot())
                } finally {
                    fixture.session.close()
                }
            }
        }
    }

    private fun assertAdmissionBoundary(report: IssueReport) {
        assertEquals("VOICE_SESSION_FAILED", report.errorCode)
        val context = requireNotNull(report.context)
        assertEquals("message_admission", context.stage)
        assertNull(context.operation)
        assertNull(context.runIdHash)
        assertNull(context.invocationIdHash)
        assertNull(context.dispatched)
        assertNull(context.remoteAcceptance)
        assertNull(context.retryable)
        assertNull(context.resumeAllowed)
        assertNull(context.requiresReconciliation)
        assertNull(context.httpStatus)
        assertNull(context.exceptionFingerprint)
        assertNull(report.errorType)
        val diagnostics = requireNotNull(context.diagnostics)
        assertEquals(JsonPrimitive("message_admission"), diagnostics.values["incident_phase"])
        assertEquals(JsonPrimitive("APPLICATION"), diagnostics.values["incident_origin"])
        assertEquals(JsonPrimitive("THINKING"), diagnostics.values["voice_phase"])
        assertFalse(diagnostics.values.containsKey("causal_id_hash"))
        assertFalse(diagnostics.values.keys.any { it.startsWith("voice_reply_") })
        assertEquals(0, diagnostics.requiredReporterVersion())
        val payload = report.approvedPayload()
        assertFalse(payload.contains("PRIVATE_"))
        assertFalse(payload.contains("private.invalid"))
    }

    private class Audio : VoiceAudio {
        var listener: VoiceAudio.Listener? = null
        var captures = 0
        var stops = 0
        private var probe: VoiceDiagnosticTrace.Probe? = null
        override fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) {
            this.probe = probe
        }
        override fun listen(listener: VoiceAudio.Listener) {
            captures++
            this.listener = listener
            probe?.engine(VoiceDiagnosticTrace.Engine.LOCAL)
            listener.ready()
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = Unit
        override fun stop() {
            stops++
        }
        override fun close() = Unit
    }

    private class Fixture(private val submitResult: Boolean = true, private val beforeSubmitReturn: () -> Unit = {}) {
        val audio = Audio()
        var submissions = 0
        var requestId = "prior-request"
        val session = VoiceSession(
            audio,
            submit = {
                submissions++
                if (submitResult) requestId = "owned-request-$submissions"
                beforeSubmitReturn()
                submitResult
            },
            changed = {},
            awaitsAdmission = true,
            submittedRequestId = { requestId }
        )

        fun capture() {
            session.access(true, true, true)
            session.resume()
            requireNotNull(audio.listener).final("PRIVATE_PROMPT")
        }
    }
}
