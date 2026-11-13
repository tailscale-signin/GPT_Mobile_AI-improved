package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidents
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import dev.chungjungsoo.gptmobile.data.diagnostics.ReportFeature
import dev.chungjungsoo.gptmobile.data.openrouter.PrivacyAdmissionException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
@Config(sdk = [35], application = Application::class)
class VoiceFailureReportingTest {
    @Before
    @After
    fun reset() {
        IssueReportIncidents.initialize(ApplicationProvider.getApplicationContext<Context>())
        LocalDiagnosticsPolicy.setEnabled(false)
        IssueReportIncidents.privacyChanged(false)
        IssueReportIncidents.debugPreferenceChanged(false)
        IssueReportIncidents.clearRecent()
    }

    @Test
    fun automaticFailureUsesOnlyItsCurrentCaptureGenerationAndNeverExportsRecognizedText() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val listeners = mutableListOf<VoiceAudio.Listener>()
        var probe: VoiceDiagnosticTrace.Probe? = null
        val audio = object : VoiceAudio {
            override fun bindDiagnostics(value: VoiceDiagnosticTrace.Probe?) {
                probe = value
            }
            override fun listen(listener: VoiceAudio.Listener) {
                probe?.engine(VoiceDiagnosticTrace.Engine.LOCAL)
                probe?.captureAttempt()
                probe?.captureReadsAvailable()
                listeners += listener
                listener.ready()
            }
            override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = Unit
            override fun stop() = Unit
            override fun close() = Unit
        }
        val trace = VoiceDiagnosticTrace()
        val session = VoiceSession(audio, { true }, {}, diagnosticTrace = trace)
        session.access(true, true, true)
        session.resume()
        val stale = requireNotNull(probe)
        stale.read(1600, 1600)
        session.pause()
        session.resume()
        stale.read(1600, 1600)
        requireNotNull(probe).read(400, 80)
        listeners.last().partial("private recognized words")
        val observed = trace.incidentFields()
        listeners.last().failure("Capture stopped", "VOICE_MICROPHONE_READ_FAILED")
        val report = IssueReportIncidents.pending.value.single()
        val diagnostics = requireNotNull(report.context?.diagnostics)
        assertEquals("INCIDENT", diagnostics.captureMode)
        assertEquals(JsonPrimitive(400L), diagnostics.values["voice_captured_samples"])
        assertEquals(JsonPrimitive(80L), diagnostics.values["voice_nonzero_samples"])
        assertEquals(JsonPrimitive(1L), diagnostics.values["voice_capture_attempts"])
        assertEquals(JsonPrimitive(observed["voice_generation"] as Long), diagnostics.values["voice_generation"])
        assertEquals(JsonPrimitive(observed["voice_session_id_hash"] as String), diagnostics.values["voice_session_id_hash"])
        assertFalse(IssueReport.json.encodeToString(IssueReport.serializer(), report).contains("private recognized words"))
        assertEquals("private recognized words", session.state.transcript)
        session.close()
    }

    @Test
    fun privateSessionAuthorityRejectsNewAndRetiredVoiceObservations() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val trace = VoiceDiagnosticTrace()
        val old = trace.advance(1, reset = true)
        old.engine(VoiceDiagnosticTrace.Engine.LOCAL)
        IssueReportIncidents.privacyChanged(true)
        old.read(1600, 1600)
        assertTrue(trace.incidentFields().isEmpty())
        val privateTrace = VoiceDiagnosticTrace()
        val privateTicket = privateTrace.advance(1, reset = true)
        privateTicket.engine(VoiceDiagnosticTrace.Engine.SYSTEM)
        assertEquals(null, VoiceDiagnosticTrace.manualSnapshot())
        IssueReportIncidents.privacyChanged(false)
        privateTicket.read(1600, 1600)
        assertEquals(null, VoiceDiagnosticTrace.manualSnapshot())
        privateTrace.advance(2, reset = true).engine(VoiceDiagnosticTrace.Engine.SYSTEM)
        assertTrue(VoiceDiagnosticTrace.manualSnapshot() != null)
        trace.close()
        privateTrace.close()
    }

    @Test
    fun quietTurnsKeepBoundedRetryGuidanceWithoutCreatingBugReports() {
        LocalDiagnosticsPolicy.setEnabled(true)
        for (emptyFinal in listOf(false, true)) {
            var time = 1_000L
            val listeners = mutableListOf<VoiceAudio.Listener>()
            val audio = object : VoiceAudio {
                override fun listen(listener: VoiceAudio.Listener) {
                    listeners += listener
                }
                override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = Unit
                override fun stop() = Unit
                override fun close() = Unit
            }
            val session = VoiceSession(audio, { error("Silence must not submit a message") }, {}, { time }).apply { autoContinue = true }
            session.access(true, true, true)
            session.resume()
            repeat(4) {
                if (emptyFinal) {
                    listeners.last().final("")
                } else {
                    listeners.last().failure("No speech detected. Tap to try again.", "VOICE_NO_SPEECH", retryMillis = 500, retryable = true)
                }
                time += 500
                session.tick()
            }
            assertEquals(4, listeners.size)
            assertEquals(VoicePhase.ERROR, session.state.phase)
            assertTrue(session.state.notice.orEmpty().contains("speech"))
            assertFalse(session.state.hasDraft)
            assertTrue(IssueReportIncidents.pending.value.isEmpty())
            assertFalse(IssueReportIncidents.recentEvents().any { "VOICE_NO_SPEECH" in it })
            session.close()
        }
    }

    @Test
    fun silenceWithCapturedWordsKeepsTheDraftAndReportsTheMissingFinalResult() {
        LocalDiagnosticsPolicy.setEnabled(true)
        lateinit var listener: VoiceAudio.Listener
        val audio = object : VoiceAudio {
            override fun listen(value: VoiceAudio.Listener) {
                listener = value
            }
            override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = Unit
            override fun stop() = Unit
            override fun close() = Unit
        }
        val session = VoiceSession(audio, { false }, {}).apply { autoContinue = true }
        session.access(true, true, true)
        session.resume()
        listener.partial("Words that must stay available")
        listener.failure("No final transcript", "VOICE_NO_SPEECH", retryMillis = 500, retryable = true)
        assertEquals("VOICE_NO_SPEECH", IssueReportIncidents.pending.value.single().errorCode)
        assertTrue(session.state.hasDraft)
        session.resume()
        assertEquals(VoicePhase.REVIEWING, session.state.phase)
        assertEquals("Words that must stay available", session.state.transcript)
        session.close()
    }

    @Test
    fun microphoneFocusFailureRetainsCaptureStageAndSpecificCodeInTheReport() {
        LocalDiagnosticsPolicy.setEnabled(true)
        lateinit var capture: VoiceAudio.Listener
        val audio = object : VoiceAudio {
            override fun listen(listener: VoiceAudio.Listener) {
                capture = listener
            }
            override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = Unit
            override fun stop() = Unit
            override fun close() = Unit
        }
        val session = VoiceSession(audio, { false }, {})
        session.access(true, true, true)
        session.resume()
        val failure = LocalVoiceCaptureException("Private OS reason", "MICROPHONE_FOCUS_DENIED", "audio_focus", retryable = true, audioFocusResult = 0)
        capture.failure("Audio is busy", "VOICE_SESSION_FAILED", retryMillis = 1_000, retryable = true, cause = failure)
        val report = IssueReportIncidents.pending.value.single()
        assertEquals("VOICE_MICROPHONE_FOCUS_DENIED", report.errorCode)
        assertEquals("audio_focus", report.context?.stage)
        assertEquals("voice_capture", report.context?.operation)
        assertEquals(true, report.context?.retryable)
        assertEquals(1_000L, report.context?.retryAfterMillis)
        assertEquals("LocalVoiceCaptureException", report.errorType)
        assertFalse(IssueReport.json.encodeToString(IssueReport.serializer(), report).contains("Private OS reason"))
        session.close()
    }

    @Test
    fun privacyRejectionAfterCaptureKeepsTheDraftWithoutDuplicatingTheChatIncident() {
        LocalDiagnosticsPolicy.setEnabled(true)
        lateinit var capture: VoiceAudio.Listener
        var submissions = 0
        val audio = object : VoiceAudio {
            override fun listen(listener: VoiceAudio.Listener) {
                capture = listener
            }
            override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = Unit
            override fun stop() = Unit
            override fun close() = Unit
        }
        val session = VoiceSession(audio, {
            submissions++
            true
        }, {}, awaitsAdmission = true).apply { autoContinue = true }
        session.access(true, true, true)
        session.resume()
        capture.final("Keep my recognized request")
        session.admission(VoiceAdmissionStage.ACCEPTED)
        val rejected = PrivacyAdmissionException("POLICY_RESTRICTED: This model cannot satisfy the account policy.").asApiError("voice-run")
        IssueReportIncidents.failure(
            ReportFeature.CHAT,
            requireNotNull(rejected.errorCode),
            error = null,
            fields = rejected.failureDetails.orEmpty(),
            source = "CHAT_FAILURE"
        )
        val original = IssueReportIncidents.pending.value.single()
        val failure = ProfileFailureRecord.fromProvider(rejected.message, rejected.errorCode, rejected.failureDetails)
        session.reply("voice-run", "", failed = true, failure = failure)
        assertEquals(original, IssueReportIncidents.pending.value.single())
        assertEquals("PRIVACY_CONFIGURATION", original.errorCode)
        assertFalse(IssueReportIncidents.recentEvents().any { "VOICE_SESSION_FAILED" in it })
        assertEquals(VoicePhase.ERROR, session.state.phase)
        assertTrue(session.state.hasDraft)
        assertFalse(session.state.awaitingReply)
        assertTrue(session.state.notice.orEmpty().contains("privacy settings"))
        session.resume()
        assertEquals(VoicePhase.REVIEWING, session.state.phase)
        assertEquals("Keep my recognized request", session.state.transcript)
        assertEquals(1, submissions)
        session.close()
    }

    @Test
    fun owningChatIncidentKeepsRunAndInvocationEvidenceWithoutAddingAVoiceFailure() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val audio = object : VoiceAudio {
            override fun listen(listener: VoiceAudio.Listener) = Unit
            override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = Unit
            override fun stop() = Unit
            override fun close() = Unit
        }
        val session = VoiceSession(audio, { false }, {})
        session.restore("private recognized words", "partial reply", review = false, pending = true)
        val details = kotlinx.serialization.json.buildJsonObject {
            put("run_id", kotlinx.serialization.json.JsonPrimitive("private-run"))
            put("failed_stage", kotlinx.serialization.json.JsonPrimitive("response_stream"))
            put("dispatch_state", kotlinx.serialization.json.JsonPrimitive("FAILED"))
            put("dispatched", kotlinx.serialization.json.JsonPrimitive(true))
            put("remote_acceptance", kotlinx.serialization.json.JsonPrimitive("UNKNOWN"))
            put("invocation_id", kotlinx.serialization.json.JsonPrimitive("private-invocation"))
            put("server_request_id", kotlinx.serialization.json.JsonPrimitive("private-request"))
        }
        IssueReportIncidents.failure(ReportFeature.CHAT, "NETWORK_TIMEOUT", null, details, "CHAT_FAILURE")
        val original = IssueReportIncidents.pending.value.single()
        session.reply(
            "private-run",
            "",
            failed = true,
            failure = ProfileFailureRecord.fromProvider("private provider detail", "NETWORK_TIMEOUT", details)
        )
        val report = IssueReportIncidents.pending.value.single()
        assertEquals(original, report)
        assertEquals("NETWORK_TIMEOUT", report.errorCode)
        assertEquals("response_stream", report.context?.stage)
        assertEquals(true, report.context?.dispatched)
        assertEquals("UNKNOWN", report.context?.remoteAcceptance)
        assertEquals(dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.identifierHash("run", "private-run"), report.context?.runIdHash)
        assertEquals(dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.identifierHash("invocation", "private-invocation"), report.context?.invocationIdHash)
        org.junit.Assert.assertNull(report.context?.operation)
        assertFalse(IssueReport.json.encodeToString(IssueReport.serializer(), report).contains("private"))
        assertEquals(VoicePhase.ERROR, session.state.phase)
        assertFalse(session.state.awaitingReply)
        session.close()
    }

    @Test
    fun untypedReplyProseAddsOnlyRunCorrelationAndDoesNotPromoteDispatchEvidence() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val audio = object : VoiceAudio {
            override fun listen(listener: VoiceAudio.Listener) = Unit
            override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = Unit
            override fun stop() = Unit
            override fun close() = Unit
        }
        val session = VoiceSession(audio, { false }, {})
        session.restore("recognized words", "", review = false, pending = true)
        session.reply("legacy-run", "HTTP 429: rate limit", failed = true)
        val report = IssueReportIncidents.pending.value.single()
        assertEquals("VOICE_SESSION_FAILED", report.errorCode)
        assertEquals("voice_reply", report.context?.stage)
        assertEquals(JsonPrimitive("FAILED_WITHOUT_DETAILS"), report.context?.diagnostics?.values?.get("voice_reply_state"))
        assertEquals(JsonPrimitive(0), report.context?.diagnostics?.values?.get("voice_reply_characters"))
        org.junit.Assert.assertNull(report.context?.dispatched)
        org.junit.Assert.assertNull(report.context?.remoteAcceptance)
        org.junit.Assert.assertNull(report.context?.httpStatus)
        org.junit.Assert.assertNull(report.context?.retryable)
        assertEquals(dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.identifierHash("run", "legacy-run"), report.context?.runIdHash)
        session.close()
    }

    @Test
    fun legacyClassifiedReplyKeepsTheKnownChatIncidentAndDraftWithoutInventingTransportFacts() {
        LocalDiagnosticsPolicy.setEnabled(true)
        var captures = 0
        var submissions = 0
        val audio = object : VoiceAudio {
            override fun listen(listener: VoiceAudio.Listener) {
                captures++
            }
            override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = Unit
            override fun stop() = Unit
            override fun close() = Unit
        }
        val session = VoiceSession(audio, {
            submissions++
            true
        }, {}).apply { autoContinue = true }
        session.access(true, true, true)
        session.restore("recognized words", "Retained partial answer", review = false, pending = true)
        // The chat owns this recorded failure. Legacy classification is UI guidance,
        // not typed evidence of an HTTP response or a provider invocation.
        IssueReportIncidents.failure(
            ReportFeature.CHAT,
            "CHAT_FAILED",
            null,
            buildJsonObject { put("run_id", "legacy-run") },
            "CHAT_FAILURE"
        )
        val original = IssueReportIncidents.pending.value.single()
        session.reply(
            "legacy-run",
            "Retained partial answer",
            failed = true,
            failure = ProfileFailureRecord.classify("HTTP 429: rate limit")
        )
        val report = IssueReportIncidents.pending.value.single()
        assertEquals(original, report)
        assertEquals("CHAT_FAILED", report.errorCode)
        org.junit.Assert.assertNull(report.context?.stage)
        org.junit.Assert.assertNull(report.context?.dispatched)
        org.junit.Assert.assertNull(report.context?.remoteAcceptance)
        org.junit.Assert.assertNull(report.context?.httpStatus)
        org.junit.Assert.assertNull(report.context?.retryable)
        org.junit.Assert.assertNull(report.context?.invocationIdHash)
        assertEquals(dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.identifierHash("run", "legacy-run"), report.context?.runIdHash)
        assertFalse(IssueReportIncidents.recentEvents().any { "VOICE_SESSION_FAILED" in it })
        assertEquals(VoicePhase.ERROR, session.state.phase)
        assertEquals("Retained partial answer", session.state.reply)
        assertTrue(session.state.hasDraft)
        assertFalse(session.state.awaitingReply)
        session.tick()
        session.resume()
        assertEquals(VoicePhase.REVIEWING, session.state.phase)
        assertEquals("recognized words", session.state.transcript)
        assertEquals(0, captures)
        assertEquals(0, submissions)
        session.close()
    }

    @Test
    fun typedGatewayAndUnknownOutcomeFailuresKeepOnlyTheOwningChatIncident() {
        val causes = listOf(
            Triple("GATEWAY_ENGINEERING_UNAVAILABLE", "connection_preflight", false),
            Triple("GATEWAY_OUTCOME_UNKNOWN", "gateway_dispatch", true),
            Triple("TOOL_OUTCOME_UNKNOWN", "tool_dispatch", true)
        )
        for ((code, stage, dispatched) in causes) {
            reset()
            LocalDiagnosticsPolicy.setEnabled(true)
            lateinit var capture: VoiceAudio.Listener
            var submissions = 0
            val audio = object : VoiceAudio {
                override fun listen(listener: VoiceAudio.Listener) {
                    capture = listener
                }
                override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = Unit
                override fun stop() = Unit
                override fun close() = Unit
            }
            val session = VoiceSession(audio, {
                submissions++
                true
            }, {}, awaitsAdmission = true).apply { autoContinue = true }
            session.access(true, true, true)
            session.resume()
            capture.final("Keep the request after a chat failure")
            session.admission(VoiceAdmissionStage.ACCEPTED)
            val details = buildJsonObject {
                put("failed_stage", stage)
                put("dispatched", dispatched)
                put("remote_acceptance", if (dispatched) "UNKNOWN" else "NO")
                put("request_replay_allowed", false)
                put("requires_reconciliation", dispatched)
            }
            IssueReportIncidents.failure(ReportFeature.CHAT, code, null, details, "CHAT_FAILURE")
            val original = IssueReportIncidents.pending.value.single()
            val failure = ProfileFailureRecord.fromProvider(code, code, details)
            session.reply("accepted", "Retained partial response", failed = true, failure = failure)
            assertEquals(original, IssueReportIncidents.pending.value.single())
            assertFalse(IssueReportIncidents.recentEvents().any { "VOICE_SESSION_FAILED" in it })
            assertEquals(VoicePhase.ERROR, session.state.phase)
            assertTrue(session.state.hasDraft)
            assertFalse(session.state.awaitingReply)
            assertEquals("Retained partial response", session.state.reply)
            session.tick()
            session.resume()
            assertEquals(VoicePhase.REVIEWING, session.state.phase)
            assertEquals("Keep the request after a chat failure", session.state.transcript)
            assertEquals(1, submissions)
            session.close()
        }
    }

    @Test
    fun failedReplyWithoutMetadataRetainsItsObservedWaitingPhaseAndDraft() {
        LocalDiagnosticsPolicy.setEnabled(true)
        lateinit var capture: VoiceAudio.Listener
        val audio = object : VoiceAudio {
            override fun listen(listener: VoiceAudio.Listener) {
                capture = listener
            }
            override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = Unit
            override fun stop() = Unit
            override fun close() = Unit
        }
        var submissions = 0
        val session = VoiceSession(audio, {
            submissions++
            true
        }, {}, awaitsAdmission = true).apply { autoContinue = true }
        session.access(true, true, true)
        session.resume()
        capture.final("Keep the unclassified request")
        session.admission(VoiceAdmissionStage.ACCEPTED)
        session.reply("accepted", "", failed = true)
        val report = IssueReportIncidents.pending.value.single()
        assertEquals("VOICE_SESSION_FAILED", report.errorCode)
        assertEquals("voice_response_wait", report.context?.stage)
        assertEquals(JsonPrimitive("THINKING"), report.context?.diagnostics?.values?.get("voice_phase"))
        assertEquals(JsonPrimitive("MISSING_FAILURE_RECORD"), report.context?.diagnostics?.values?.get("voice_reply_failure_evidence"))
        assertEquals(null, report.context?.operation)
        assertTrue(session.state.hasDraft)
        assertFalse(session.state.awaitingReply)
        session.reply("accepted", "", failed = true)
        session.resume()
        assertEquals(report, IssueReportIncidents.pending.value.single())
        assertEquals(VoicePhase.REVIEWING, session.state.phase)
        assertEquals("Keep the unclassified request", session.state.transcript)
        assertEquals(1, submissions)
        session.close()
    }

    @Test
    fun detailedFailureCreatesOneSpecificReportWithSafeCauseAndNoGenericDuplicate() {
        LocalDiagnosticsPolicy.setEnabled(true)
        lateinit var capture: VoiceAudio.Listener
        val audio = object : VoiceAudio {
            override fun listen(listener: VoiceAudio.Listener) {
                capture = listener
            }
            override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = Unit
            override fun stop() = Unit
            override fun close() = Unit
        }
        val session = VoiceSession(audio, { false }, {})
        session.access(true, true, true)
        session.resume()
        val cause = UnsatisfiedLinkError("private native library path")
        cause.stackTrace = arrayOf(
            StackTraceElement("dev.chungjungsoo.gptmobile.data.voice.MoonshineVoiceRecognizer", "recognizeExclusive", "MoonshineVoiceRecognizer.kt", 200)
        )
        capture.failure(
            "Select another speech engine.",
            "VOICE_RECOGNIZER_UNAVAILABLE",
            recoveryAction = VoiceRecoveryAction.LOCAL_MODEL,
            cause = cause
        )
        capture.error("Late duplicate failure")
        val report = IssueReportIncidents.pending.value.single()
        assertEquals("VOICE_RECOGNIZER_UNAVAILABLE", report.errorCode)
        assertEquals("UnsatisfiedLinkError", report.errorType)
        assertEquals("VOICE_STATE", report.context?.source)
        assertEquals("voice_recognition", report.context?.stage)
        assertEquals("voice_recognition", report.context?.operation)
        assertEquals("LOCAL_MODEL", report.context?.recoveryAction)
        assertEquals(listOf("dev.chungjungsoo.gptmobile.data.voice.MoonshineVoiceRecognizer.recognizeExclusive:200"), report.frames)
        assertFalse(report.events.any { "VOICE_SESSION_FAILED" in it })
        assertFalse(IssueReport.json.encodeToString(IssueReport.serializer(), report).contains("private native library path"))
        assertEquals(VoiceRecoveryAction.LOCAL_MODEL, session.state.recoveryAction)
        assertEquals(VoicePhase.ERROR, session.state.phase)
        session.close()
    }

    @Test
    fun resumingDuringCooldownKeepsTheOriginalFailureWithoutReportingAnotherAttempt() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val attempts = mutableListOf<VoiceAudio.Listener>()
        val audio = object : VoiceAudio {
            override fun listen(listener: VoiceAudio.Listener) {
                attempts += listener
            }
            override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = Unit
            override fun stop() = Unit
            override fun close() = Unit
        }
        val session = VoiceSession(audio, { false }, {}, { 1000L })
        session.access(true, true, true)
        session.resume()
        attempts.single().failure(
            "Review the speech service settings.",
            "VOICE_RECOGNIZER_RATE_LIMITED",
            retryMillis = 15_000,
            recoveryAction = VoiceRecoveryAction.SPEECH_SERVICE
        )
        val originalReport = IssueReportIncidents.pending.value.single()
        assertEquals(15_000L, originalReport.context?.retryAfterMillis)
        assertEquals("SPEECH_SERVICE", originalReport.context?.recoveryAction)
        repeat(3) {
            session.pause()
            session.resume()
        }
        assertEquals(1, attempts.size)
        assertEquals(originalReport, IssueReportIncidents.pending.value.single())
        assertFalse(IssueReportIncidents.recentEvents().any { "VOICE_SESSION_FAILED" in it })
        assertEquals(VoicePhase.ERROR, session.state.phase)
        assertEquals(16_000L, session.state.retryAtMillis)
        assertEquals("Review the speech service settings.", session.state.notice)
        assertEquals(VoiceRecoveryAction.SPEECH_SERVICE, session.state.recoveryAction)
        session.close()
    }

    @Test
    fun nonStreamingPlaybackFailureKeepsItsSpecificCodeAndTheCompleteAnswer() {
        LocalDiagnosticsPolicy.setEnabled(true)
        lateinit var capture: VoiceAudio.Listener
        lateinit var playbackError: (String) -> Unit
        val audio = object : VoiceAudio {
            override fun listen(listener: VoiceAudio.Listener) {
                capture = listener
            }
            override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
                playbackError = error
            }
            override fun stop() = Unit
            override fun close() = Unit
        }
        val session = VoiceSession(audio, { true }, {}).apply { autoContinue = true }
        session.access(true, true, true)
        session.resume()
        capture.final("Read the answer")
        session.reply("accepted-turn", "The complete answer remains available.")
        playbackError("The audio route is unavailable.")
        playbackError("A stale duplicate failure")
        val report = IssueReportIncidents.pending.value.single()
        assertEquals("VOICE_PLAYBACK_FAILED", report.errorCode)
        assertEquals("voice_playback", report.context?.stage)
        assertFalse(report.events.any { "VOICE_SESSION_FAILED" in it })
        assertEquals("The complete answer remains available.", session.state.reply)
        assertEquals(VoicePhase.ERROR, session.state.phase)
        assertFalse(session.state.awaitingReply)
        session.close()
    }
}
