package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidents
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class VoicePlaybackFailureReportingTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun reset() {
        IssueReportIncidents.initialize(context)
        LocalDiagnosticsPolicy.setEnabled(false)
        IssueReportIncidents.privacyChanged(false)
        IssueReportIncidents.debugPreferenceChanged(false)
        IssueReportIncidents.clearRecent()
        VoiceDiagnosticTrace.clearAll()
    }

    @Test fun actualKokoroCatchKeepsCauseThroughStreamingAndTerminalReportsWithoutPrivateText() {
        for (complete in listOf(false, true)) {
            reset()
            LocalDiagnosticsPolicy.setEnabled(true)
            val cause = IllegalStateException("PRIVATE_PROMPT https://private.invalid/voice PRIVATE_MODEL.bin")
            cause.stackTrace = arrayOf(StackTraceElement("dev.chungjungsoo.gptmobile.data.voice.KokoroVoiceOutput", "observedFixture", "PRIVATE_MODEL.bin", 1171))
            val models = mockk<LocalVoiceModelStore>()
            every { models.acquireUse() } throws cause
            // The actual producer fails before loading JNI. This is exception-path evidence,
            // not a successful native synthesis or audible-playback test.
            val session = session(createKokoroVoiceOutput(context, models, ::attributes, { 1f }))
            try {
                assertTrue(session.beginMessage("message:run", diagnosticRunId = "private-run"))
                session.streamReply("message:run", "The PRIVATE_RESPONSE remains available in chat.", complete)
                awaitReport()
                val report = IssueReportIncidents.pending.value.single()
                assertEquals("VOICE_PLAYBACK_FAILED", report.errorCode)
                assertEquals("IllegalStateException", report.errorType)
                assertTrue(report.frames.any { it == "dev.chungjungsoo.gptmobile.data.voice.KokoroVoiceOutput.observedFixture:1171" })
                assertNotNull(report.context?.exceptionFingerprint)
                assertEquals(IssueReportContext.identifierHash("run", "private-run"), report.context?.runIdHash)
                assertEquals(JsonPrimitive("SYNTHESIS_FAILED"), report.context?.diagnostics?.values?.get("voice_playback_failure"))
                assertEquals(if (complete) VoicePhase.ERROR else VoicePhase.THINKING, session.state.phase)
                assertEquals(!complete, session.state.awaitingReply)
                assertTrue(session.state.reply.contains("PRIVATE_RESPONSE"))
                val payload = report.approvedPayload()
                assertFalse(payload.contains("PRIVATE_"))
                assertFalse(payload.contains("private.invalid"))
                assertTrue(report.frames.size <= 8)
                assertNull(report.context?.httpStatus)
                assertNull(report.context?.dispatched)
            } finally {
                session.close()
            }
        }
    }

    @Test fun actualIncompleteLexiconRetainsItsSpecificReasonAndCaughtTypeWithoutLoadingJni() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val directory = Files.createTempDirectory("kokoro-report").toFile()
        File(directory, KokoroVoice.HEART.lexicon).writeText("unrelated u n r\n")
        val models = mockk<LocalVoiceModelStore>()
        every { models.acquireUse() } returns true
        every { models.modelDirectory() } returns directory
        every { models.releaseUse() } just Runs
        val session = session(createKokoroVoiceOutput(context, models, ::attributes, { 1f }))
        try {
            assertTrue(session.beginMessage("lexicon:run"))
            session.reply("lexicon:run", "Here is the complete response.")
            awaitReport()
            val report = IssueReportIncidents.pending.value.single()
            assertEquals("LexiconUnavailable", report.errorType)
            assertEquals(JsonPrimitive("LEXICON_UNAVAILABLE"), report.context?.diagnostics?.values?.get("voice_playback_failure"))
            assertNotNull(report.context?.exceptionFingerprint)
            assertFalse(report.approvedPayload().contains(directory.absolutePath))
            assertEquals(2, report.context?.diagnostics?.requiredReporterVersion())
        } finally {
            session.close()
            directory.deleteRecursively()
        }
    }

    @Test fun pronunciationRefusalHasNoInventedExceptionAndRetiredCallbacksCannotReportIt() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val output = ObservedFailure()
        val session = session(DeferredKokoroOutput { output })
        try {
            assertNull(KokoroSpeechText.projected("privateword") { false }.parts)
            assertTrue(session.beginMessage("first:run"))
            session.reply("first:run", "The complete answer remains available.")
            val retired = output.failures.last()
            session.stopSpeaking()
            retired()
            assertTrue(IssueReportIncidents.pending.value.isEmpty())
            assertTrue(session.beginMessage("second:run"))
            session.reply("second:run", "The next complete answer remains available.")
            output.failures.last()()
            val report = IssueReportIncidents.pending.value.single()
            assertEquals(JsonPrimitive("PRONUNCIATION_UNAVAILABLE"), report.context?.diagnostics?.values?.get("voice_playback_failure"))
            assertNull(report.errorType)
            assertTrue(report.frames.isEmpty())
            assertNull(report.context?.exceptionFingerprint)
            assertFalse(report.approvedPayload().contains("privateword"))
        } finally {
            session.close()
        }
    }

    @Test fun privatePlaybackFailureCannotRetainCauseMetadataOrPopulateANewPublicTicket() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val output = ObservedFailure(IllegalStateException("PRIVATE_CAUSE https://private.invalid/model"))
        val session = session(DeferredKokoroOutput { output })
        try {
            assertTrue(session.beginMessage("private:run"))
            session.reply("private:run", "Private response.")
            val retired = output.failures.last()
            IssueReportIncidents.privacyChanged(true)
            retired()
            assertTrue(IssueReportIncidents.pending.value.isEmpty())
            assertNull(VoiceDiagnosticTrace.manualSnapshot())
            IssueReportIncidents.privacyChanged(false)
            assertTrue(session.beginMessage("public:run"))
            session.reply("public:run", "A new public response.")
            retired()
            assertTrue(IssueReportIncidents.pending.value.isEmpty())
            assertFalse(VoiceDiagnosticTrace.manualSnapshot()?.values?.containsKey("voice_playback_failure") == true)
        } finally {
            session.close()
        }
    }

    @Test fun privateOriginRemainsUncollectableWhenPrivacyReopensWithoutANewPlaybackTicket() {
        for (complete in listOf(false, true)) {
            assertEquals(0, transitionFailure(complete, privateOrigin = true, freshTicket = false).reportCount)
        }
    }

    @Test fun publicOriginStillReportsItsActualCauseForStreamingAndTerminalFailures() {
        for (complete in listOf(false, true)) {
            val observed = transitionFailure(complete, privateOrigin = false, freshTicket = false)
            assertEquals(1, observed.reportCount)
            assertTrue(observed.correctRun)
            assertTrue(observed.actualCause)
            assertFalse(observed.rawCanary)
        }
    }

    @Test fun freshPublicPlaybackCollectsOnlyItsOwnCauseAfterThePrivateOperationRetires() {
        for (complete in listOf(false, true)) {
            val observed = transitionFailure(complete, privateOrigin = true, freshTicket = true)
            assertEquals(1, observed.reportCount)
            assertTrue(observed.correctRun)
            assertTrue(observed.actualCause)
            assertFalse(observed.rawCanary)
        }
    }

    @Test fun cancellationCannotReauthorizeAPrivateOrInvalidatedTraceButAFreshPublicReplyCanReport() {
        for (privateOrigin in listOf(false, true)) {
            reset()
            LocalDiagnosticsPolicy.setEnabled(true)
            assertFalse(AppLogRecorder.enabled.value)
            IssueReportIncidents.privacyChanged(privateOrigin)
            val trace = VoiceDiagnosticTrace()
            val origin = trace.beginReply(1)
            if (!privateOrigin) IssueReportIncidents.privacyChanged(true)
            IssueReportIncidents.privacyChanged(false)
            val cause = IllegalStateException("PRIVATE_ORIGIN_CANARY")
            val fields = mapOf("run_id" to "private-run", "failed_stage" to "voice_reply")
            origin.reportFailure("VOICE_SESSION_FAILED", cause, fields)
            val cancelled = trace.advance(2)
            cancelled.phase(VoicePhase.ERROR)
            cancelled.reportFailure("VOICE_SESSION_FAILED", cause, fields)
            assertTrue(IssueReportIncidents.pending.value.isEmpty())
            assertTrue(trace.incidentFields().isEmpty())
            assertNull(VoiceDiagnosticTrace.manualSnapshot())
            val fresh = trace.beginReply(3)
            fresh.phase(VoicePhase.THINKING)
            fresh.reportFailure("VOICE_SESSION_FAILED", cause, mapOf("run_id" to "public-run", "failed_stage" to "voice_reply"))
            val report = IssueReportIncidents.pending.value.single()
            assertEquals(IssueReportContext.identifierHash("run", "public-run"), report.context?.runIdHash)
            assertEquals("IllegalStateException", report.errorType)
            assertFalse(report.approvedPayload().contains("PRIVATE_"))
            assertNotNull(VoiceDiagnosticTrace.manualSnapshot())
            trace.close()
        }
    }

    @Test fun suspendedReadFailureKeepsItsOriginWhileStopStillAcceptsSilentTerminalCaptions() {
        for (transition in listOf("private", "epoch", "stop", "replace", "close", "public")) {
            reset()
            LocalDiagnosticsPolicy.setEnabled(true)
            IssueReportIncidents.privacyChanged(transition == "private")
            val output = ObservedFailure()
            val session = session(DeferredKokoroOutput { output })
            try {
                assertTrue(session.beginMessage("original", diagnosticRunId = "original-run"))
                val failedRead = session.terminalReadFailureReporter("original")
                when (transition) {
                    "private" -> IssueReportIncidents.privacyChanged(false)
                    "epoch" -> {
                        IssueReportIncidents.privacyChanged(true)
                        IssueReportIncidents.privacyChanged(false)
                    }
                    "stop" -> session.stopSpeaking()
                    "replace" -> assertTrue(session.beginMessage("replacement", manual = true, diagnosticRunId = "replacement-run"))
                    "close" -> session.close()
                }
                failedRead(IllegalStateException("PRIVATE_READ_CANARY"))
                if (transition == "public") {
                    val report = IssueReportIncidents.pending.value.single()
                    assertEquals("IllegalStateException", report.errorType)
                    assertEquals(IssueReportContext.identifierHash("run", "original-run"), report.context?.runIdHash)
                    assertFalse(report.approvedPayload().contains("PRIVATE_"))
                } else {
                    assertTrue(transition, IssueReportIncidents.pending.value.isEmpty())
                }
                if (transition == "private" || transition == "epoch") {
                    session.stopSpeaking()
                    session.terminalReadFailureReporter("original")(IllegalStateException("PRIVATE_AFTER_STOP_CANARY"))
                    assertTrue(IssueReportIncidents.pending.value.isEmpty())
                }
                if (transition == "stop") {
                    session.reply("original", "The completed captions remain available.")
                    assertEquals("The completed captions remain available.", session.state.reply)
                    assertFalse(session.state.awaitingReply)
                    assertTrue(output.failures.isEmpty())
                }
            } finally {
                session.close()
            }
        }
    }

    private data class TransitionObservation(val reportCount: Int, val correctRun: Boolean, val actualCause: Boolean, val rawCanary: Boolean)

    private fun transitionFailure(complete: Boolean, privateOrigin: Boolean, freshTicket: Boolean): TransitionObservation {
        reset()
        LocalDiagnosticsPolicy.setEnabled(true)
        assertFalse(AppLogRecorder.enabled.value)
        IssueReportIncidents.privacyChanged(privateOrigin)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val initialCause = IllegalArgumentException("PRIVATE_ORIGIN_CANARY https://private.invalid/model.bin").apply {
            stackTrace = arrayOf(StackTraceElement("dev.chungjungsoo.gptmobile.data.voice.KokoroVoiceOutput", "privateOriginFixture", "PRIVATE_ORIGIN_CANARY.bin", 101))
        }
        val freshCause = IllegalStateException("PRIVATE_FRESH_MESSAGE_CANARY").apply {
            stackTrace = arrayOf(StackTraceElement("dev.chungjungsoo.gptmobile.data.voice.KokoroVoiceOutput", "freshPublicFixture", "PRIVATE_SOURCE_NAME.bin", 202))
        }
        val models = mockk<LocalVoiceModelStore>()
        every { models.acquireUse() } answers {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Fixture release timed out" }
                throw initialCause
            }
            throw freshCause
        }
        val session = session(createKokoroVoiceOutput(context, models, ::attributes, { 1f }))
        val originalRun = if (privateOrigin) "private-origin-run" else "public-origin-run"
        try {
            assertTrue(session.beginMessage("original", diagnosticRunId = originalRun))
            session.streamReply("original", "The complete response remains available in chat.", complete)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(IssueReportIncidents.pending.value.isEmpty())
            if (privateOrigin) IssueReportIncidents.privacyChanged(false)
            val expectedRun = if (freshTicket) "fresh-public-run" else originalRun
            if (freshTicket) {
                assertTrue(session.beginMessage("fresh", diagnosticRunId = expectedRun))
                session.streamReply("fresh", "The fresh response remains available in chat.", complete)
            }
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val notice = "Kokoro could not synthesize this reply. Your answer remains in chat."
            while (session.state.notice != notice && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(notice, session.state.notice)
            assertEquals(if (complete) VoicePhase.ERROR else VoicePhase.THINKING, session.state.phase)
            assertEquals(!complete, session.state.awaitingReply)
            val reports = IssueReportIncidents.pending.value
            val report = reports.singleOrNull()
            return TransitionObservation(
                reports.size,
                report?.context?.runIdHash == IssueReportContext.identifierHash("run", expectedRun),
                report?.errorType == (if (freshTicket) "IllegalStateException" else "IllegalArgumentException") && report.context?.exceptionFingerprint != null,
                reports.any { it.approvedPayload().contains("CANARY") || it.approvedPayload().contains("private.invalid") }
            )
        } finally {
            release.countDown()
            session.close()
            reset()
        }
    }

    private fun session(output: KokoroOutput): VoiceSession {
        shadowOf(context.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val system = object : VoiceAudio {
            override fun listen(listener: VoiceAudio.Listener) = Unit
            override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) = error("Unexpected fallback")
            override fun stop() = Unit
            override fun close() = Unit
        }
        val local = object : VoiceRecognitionInput {
            override var languageTag = "en-US"
            override fun listen(listener: VoiceAudio.Listener) = Unit
            override fun stop() = Unit
            override fun close() = Unit
        }
        val audio = VoicePluginAudio(system, local, output) {}.apply {
            kokoroVoice = KokoroVoice.HEART
            requireLocalOutput = true
        }
        return VoiceSession(audio, { true }, {}).apply {
            autoContinue = false
            access(true, true, true)
        }
    }

    private fun attributes() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun awaitReport() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (IssueReportIncidents.pending.value.isEmpty() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, IssueReportIncidents.pending.value.size)
    }

    private class ObservedFailure(private val cause: Throwable? = null) : KokoroOutput {
        val failures = mutableListOf<() -> Unit>()
        private var probe: VoiceDiagnosticTrace.Probe? = null
        override fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) {
            this.probe = probe
        }
        override fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) = Unit
        override fun openReplyWithFailure(voice: KokoroVoice, complete: () -> Unit, error: (VoicePlaybackFailure, Boolean) -> Unit): VoiceReplyPlayback {
            val ticket = probe
            failures += {
                ticket?.playbackFailure(if (cause == null) "PRONUNCIATION_UNAVAILABLE" else "SYNTHESIS_FAILED")
                error(VoicePlaybackFailure("Playback stopped.", cause), false)
            }
            return object : VoiceReplyPlayback {
                override fun enqueue(text: String) = Unit
                override fun finish() = Unit
            }
        }
        override fun stop() = Unit
        override fun close() = Unit
    }
}
