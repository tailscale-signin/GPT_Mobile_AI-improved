package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidents
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
