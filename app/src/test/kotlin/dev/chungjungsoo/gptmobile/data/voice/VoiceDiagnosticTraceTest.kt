package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDiagnosticTraceTest {
    @After fun clear() = VoiceDiagnosticTrace.clearAll()

    @Test fun streamingDelegateRequestCannotInheritObservedNativeExecution() {
        var time = 100L
        val trace = VoiceDiagnosticTrace({ time }, { 1_000 + time }, { true }, {})
        val probe = trace.beginReply(1)
        probe.engine(VoiceDiagnosticTrace.Engine.KOKORO)
        probe.speechProvider("cpu")
        assertEquals("KOKORO_CPU", trace.incidentFields()["voice_effective_provider"])
        probe.engine(VoiceDiagnosticTrace.Engine.MEDIAPIPE)
        assertNull(trace.incidentFields()["voice_effective_provider"])
        probe.selectedSpeechBackend("LITERT_KOKORO_GPU")
        probe.speechProvider("mediapipe-kokoro-gpu-requested")
        assertEquals("LITERT_KOKORO_GPU", trace.incidentFields()["voice_selected_backend"])
        assertNull(trace.incidentFields()["voice_effective_provider"])
        probe.responseText()
        time += 200
        probe.firstAudio(VoiceDiagnosticTrace.FirstAudio.PLAYBACK_HEAD)
        assertEquals("MEDIAPIPE_KOKORO", trace.performance.snapshots.value.rows.single { it.metric == "text_to_audio" }.engine)
        probe.playbackFallback(VoicePlaybackFailure("Engine failed", code = "ENGINE_FAILED", sourceProvider = "mediapipe-kokoro-gpu-requested"), "STARTED")
        assertEquals("LITERT_KOKORO", trace.incidentFields()["voice_fallback_source"])
        assertEquals("SYSTEM", trace.incidentFields()["voice_fallback_target"])
    }

    @Test fun actualFallbackProviderOwnsPerformanceSamplesAndExportsOnlyClosedIdentity() {
        var time = 100L
        val trace = VoiceDiagnosticTrace({ time }, { 1_000 + time }, { true }, {})
        fun play(provider: String, duration: Long) {
            val probe = trace.beginReply(time)
            probe.engine(VoiceDiagnosticTrace.Engine.KOKORO)
            probe.responseText()
            probe.speechProvider(provider)
            probe.engineReady(true)
            time += duration
            probe.firstAudio(VoiceDiagnosticTrace.FirstAudio.AUDIO_TIMESTAMP)
        }
        play("qnn-gpu", 100)
        play("cpu", 400)
        val rows = trace.performance.snapshots.value.rows.filter { it.metric == "text_to_audio" }
        assertEquals(setOf("QNN_GPU", "KOKORO_CPU"), rows.map { it.engine }.toSet())
        assertEquals(100L, rows.single { it.engine == "QNN_GPU" }.p50Ms)
        assertEquals(400L, rows.single { it.engine == "KOKORO_CPU" }.p50Ms)
        assertEquals("KOKORO", trace.incidentFields()["voice_engine"])
        assertEquals("KOKORO_CPU", trace.incidentFields()["voice_effective_provider"])
        assertFalse(trace.incidentFields().values.contains("qnn-gpu"))
        assertFalse("voice_first_audio_basis" in trace.incidentFields())
        assertEquals(400L, trace.incidentFields()["voice_response_to_first_audio_ms"])
        val fallback = trace.beginReply(time)
        fallback.engine(VoiceDiagnosticTrace.Engine.KOKORO)
        fallback.speechProvider("qnn-gpu")
        fallback.engineReady(true)
        fallback.responseText()
        fallback.speechProvider("cpu")
        time += 500
        fallback.firstAudio(VoiceDiagnosticTrace.FirstAudio.PLAYBACK_HEAD)
        val cold = trace.performance.snapshots.value.rows.single { it.metric == "text_to_audio" && it.warm == false }
        assertEquals("KOKORO_CPU", cold.engine)
        assertEquals(500L, cold.p50Ms)
    }

    @Test fun successfulAndroidRecoveryRetainsOriginalFailureAndProvider() {
        val trace = VoiceDiagnosticTrace({ 100 }, { 1_100 }, { true }, {})
        val probe = trace.beginReply(1)
        probe.engine(VoiceDiagnosticTrace.Engine.KOKORO)
        probe.selectedSpeechBackend("QNN_GPU")
        probe.speechProvider("qnn-gpu")
        val failure = VoicePlaybackFailure("Private utterance must never be a receipt", IllegalStateException("private path"), "SYNTHESIS_FAILED", "qnn-gpu")
        probe.playbackFallback(failure, "STARTED")
        probe.engine(VoiceDiagnosticTrace.Engine.SYSTEM)
        probe.playbackFallback(failure, "SUCCEEDED")
        val fields = trace.incidentFields()
        assertEquals("QNN_GPU", fields["voice_selected_backend"])
        assertEquals("QNN_GPU", fields["voice_fallback_source"])
        assertEquals("SYSTEM", fields["voice_fallback_target"])
        assertEquals("SYSTEM", fields["voice_effective_provider"])
        assertEquals("SUCCEEDED", fields["voice_fallback_outcome"])
        assertEquals("SYNTHESIS_FAILED", fields["voice_fallback_failure"])
        assertEquals(1L, fields["voice_fallback_count"])
        assertFalse(fields.toString().contains("Private utterance"))
        assertFalse(fields.toString().contains("private path"))
    }

    @Test fun aSpokenSearchUpdateCannotSatisfyTheTimeToAnswerMeasurement() {
        var time = 100L
        val trace = VoiceDiagnosticTrace({ time }, { 1_000 + time }, { true }, {})
        val probe = trace.beginReply(1)
        probe.requestAccepted()
        time = 300
        probe.lookupAudioStarted()
        assertFalse(trace.performance.snapshots.value.rows.any { it.metric == "request_to_answer_audio" })
        time = 900
        probe.firstAudio(VoiceDiagnosticTrace.FirstAudio.PLAYBACK_HEAD)
        time = 950
        probe.firstAudio(VoiceDiagnosticTrace.FirstAudio.PLAYBACK_HEAD)
        val rows = trace.performance.snapshots.value.rows.associateBy { it.metric }
        assertEquals(200L, rows.getValue("request_to_lookup_audio").p50Ms)
        assertEquals(800L, rows.getValue("request_to_answer_audio").p50Ms)
        assertEquals(1, rows.getValue("request_to_answer_audio").count)
    }

    @Test fun firstTextPublishablePhrasePreparedPcmAndPresentedAudioHaveSeparateObservedTimings() {
        var time = 100L
        val trace = VoiceDiagnosticTrace({ time }, { 1_000 + time }, { true }, {})
        val probe = trace.beginReply(1)
        probe.engine(VoiceDiagnosticTrace.Engine.KOKORO)
        probe.speechProvider("cpu")
        probe.responseText()
        time = 250
        probe.publishableSpeech()
        time = 300
        probe.queued()
        time = 700
        probe.firstPcm()
        time = 900
        probe.firstAudio(VoiceDiagnosticTrace.FirstAudio.PLAYBACK_HEAD)
        val rows = trace.performance.snapshots.value.rows.associateBy { it.metric }
        assertEquals(150L, rows.getValue("text_to_publishable").p50Ms)
        assertEquals(50L, rows.getValue("publishable_to_queue").p50Ms)
        assertEquals(400L, rows.getValue("queue_to_pcm").p50Ms)
        assertEquals(200L, rows.getValue("pcm_to_audio").p50Ms)
        assertEquals("KOKORO_CPU", rows.getValue("pcm_to_audio").engine)
    }

    @Test fun explicitlySelectedGatewayRecoveryRetainsTheFailedRemoteProvider() {
        val trace = VoiceDiagnosticTrace({ 100 }, { 1_100 }, { true }, {})
        val probe = trace.beginReply(1)
        probe.selectedSpeechBackend("GATEWAY_CUDA")
        val failure = VoicePlaybackFailure("Gateway preparation failed", code = "SYNTHESIS_FAILED", sourceProvider = "gateway-cuda")
        probe.playbackFallback(failure, "STARTED")
        probe.engine(VoiceDiagnosticTrace.Engine.SYSTEM)
        probe.playbackFallback(failure, "SUCCEEDED")
        assertEquals("GATEWAY_CUDA", trace.incidentFields()["voice_selected_backend"])
        assertEquals("GATEWAY_CUDA", trace.incidentFields()["voice_fallback_source"])
        assertEquals("SYSTEM", trace.incidentFields()["voice_effective_provider"])
    }

    @Test fun captureReadinessAndAcousticEndpointUseSeparateObservedClocks() {
        var time = 100L
        val trace = VoiceDiagnosticTrace({ time }, { 1_000 + time }, { true }, {})
        val probe = trace.advance(1, reset = true)
        probe.engine(VoiceDiagnosticTrace.Engine.LOCAL)
        probe.captureRequested()
        time = 300
        probe.microphoneReady()
        time = 500
        probe.acousticSpeechEnded(elapsedAgoMs = 150)
        time = 800
        probe.speechEnded()
        probe.finalTranscript()
        probe.finalTranscript()
        val rows = trace.performance.snapshots.value.rows
        assertEquals(200L, rows.single { it.metric == "capture_ready" }.p50Ms)
        assertEquals(450L, rows.single { it.metric == "speech_end" }.p50Ms)
        assertEquals(1, rows.single { it.metric == "speech_end" }.count)
        assertEquals(0L, trace.incidentFields()["voice_speech_end_to_transcript_ms"])
    }

    @Test fun timingUsesObservedStagesAndSnapshotAgeDoesNotRefreshOnRead() {
        var time = 100L
        val retained = mutableListOf<IssueReportDiagnostics>()
        val trace = VoiceDiagnosticTrace({ time }, { 1_000 + time }, { true }, retained::add)
        val probe = trace.advance(3, reset = true)
        probe.engine(VoiceDiagnosticTrace.Engine.KOKORO)
        probe.phase(VoicePhase.THINKING)
        probe.responseText()
        time = 250
        probe.queued()
        probe.warmupStarted()
        time = 450
        probe.engineReady(warm = false)
        time = 550
        probe.synthesisStarted()
        time = 1_000
        probe.firstAudio(VoiceDiagnosticTrace.FirstAudio.PLAYBACK_HEAD)
        val fields = trace.incidentFields()
        assertEquals(150L, fields["voice_response_to_queue_ms"])
        assertEquals(200L, fields["voice_warmup_ms"])
        assertEquals(300L, fields["voice_queue_to_synthesis_ms"])
        assertEquals(450L, fields["voice_synthesis_to_first_audio_ms"])
        assertEquals(900L, fields["voice_response_to_first_audio_ms"])
        assertEquals("PLAYBACK_HEAD", fields["voice_first_audio_basis"])
        assertTrue((fields["voice_session_id_hash"] as String).matches(Regex("[a-f0-9]{64}")))
        val snapshot = requireNotNull(VoiceDiagnosticTrace.manualSnapshot())
        assertEquals(2_000L, snapshot.capturedAtMillis)
        time = 2_000
        assertEquals(snapshot, VoiceDiagnosticTrace.manualSnapshot())
        trace.retain()
        assertEquals(snapshot, retained.single())
        time = 602_000
        assertNull(VoiceDiagnosticTrace.manualSnapshot())
    }

    @Test
    fun pronunciationAndFocusEvidenceIsBoundedSourceOwnedAndDoesNotExposeSpeech() {
        val trace = VoiceDiagnosticTrace({ 10 }, { 1000 }, { true }, {})
        val first = trace.advance(1, reset = true)
        first.engine(VoiceDiagnosticTrace.Engine.KOKORO)
        first.pronunciationFailure(Int.MAX_VALUE, 4, audioQueued = true, firstAudioObserved = true, failure = KokoroPronunciationFailure.UNKNOWN_WORD, unknownWordLength = 300)
        first.playbackFocus(VoiceDiagnosticTrace.Focus.DENIED, 2, VoiceDiagnosticTrace.PlaybackAudioMode.IN_CALL)
        first.playbackFocusTransition("PAUSE")
        first.playbackFocusTransition("RESTORE")
        first.playbackFocusTransition("private-unknown-event")
        val observed = trace.incidentFields()
        assertEquals(16_000_000L, observed["voice_pronunciation_unknown_words"])
        assertEquals(4L, observed["voice_pronunciation_initialisms"])
        assertEquals("UNKNOWN_WORD", observed["voice_pronunciation_failure_kind"])
        assertEquals(true, observed["voice_pronunciation_audio_queued"])
        assertEquals(true, observed["voice_pronunciation_first_audio_observed"])
        assertEquals("IN_CALL", observed["voice_playback_audio_mode"])
        assertEquals("RESTORE", observed["voice_focus_last_transition"])
        assertEquals(2L, observed["voice_focus_transition_count"])
        val approved = requireNotNull(VoiceDiagnosticTrace.manualSnapshot()).values
        assertTrue("voice_pronunciation_unknown_words" in approved)
        assertEquals("256", approved["voice_pronunciation_unknown_word_length"].toString())
        assertEquals("UNKNOWN_WORD", approved["voice_pronunciation_failure_kind"]?.toString()?.trim('"'))
        assertTrue("voice_focus_last_transition" in approved)
        assertFalse(approved.toString().contains("private-unknown-event"))
        val fresh = trace.beginReply(2)
        fresh.engine(VoiceDiagnosticTrace.Engine.KOKORO)
        assertFalse("voice_pronunciation_unknown_words" in trace.incidentFields())
        assertFalse("voice_pronunciation_unknown_word_length" in trace.incidentFields())
        assertFalse("voice_pronunciation_failure_kind" in trace.incidentFields())
        assertFalse("voice_focus_transition_count" in trace.incidentFields())
        assertFalse("voice_playback_audio_mode" in trace.incidentFields())
        first.playbackFocusTransition("STOP")
        assertFalse("voice_focus_last_transition" in trace.incidentFields())
    }

    @Test fun internalPronunciationReasonsRemainTypedAndRejectRetiredPlayback() {
        val trace = VoiceDiagnosticTrace({ 10 }, { 1000 }, { true }, {})
        val retired = trace.advance(1, reset = true)
        retired.engine(VoiceDiagnosticTrace.Engine.KOKORO)
        retired.pronunciationFailure(0, 1, audioQueued = false, firstAudioObserved = false, failure = KokoroPronunciationFailure.MISSING_LETTER)
        assertEquals("MISSING_LETTER", trace.incidentFields()["voice_pronunciation_failure_kind"])
        assertEquals(false, trace.incidentFields()["voice_pronunciation_audio_queued"])
        val fresh = trace.beginReply(2)
        fresh.engine(VoiceDiagnosticTrace.Engine.KOKORO)
        retired.pronunciationFailure(1, 0, audioQueued = true, firstAudioObserved = true, failure = KokoroPronunciationFailure.UNSUPPORTED_SCRIPT)
        assertFalse("voice_pronunciation_failure_kind" in trace.incidentFields())
        fresh.pronunciationFailure(1, 0, audioQueued = false, firstAudioObserved = false, failure = KokoroPronunciationFailure.UNKNOWN_WORD)
        assertEquals("UNKNOWN_WORD", trace.incidentFields()["voice_pronunciation_failure_kind"])
    }

    @Test fun staleGenerationsAndOlderSessionsCannotPopulateTheCurrentSnapshot() {
        val retained = mutableListOf<IssueReportDiagnostics>()
        val trace = VoiceDiagnosticTrace({ 10 }, { 1000 }, { true }, retained::add)
        val old = trace.advance(1, reset = true)
        old.engine(VoiceDiagnosticTrace.Engine.LOCAL)
        old.read(100, 50)
        val current = trace.advance(2, reset = true)
        current.engine(VoiceDiagnosticTrace.Engine.SYSTEM)
        old.read(100, 100)
        old.firstAudio(VoiceDiagnosticTrace.FirstAudio.PLAYBACK_HEAD)
        assertFalse("voice_read_calls" in trace.incidentFields())
        assertFalse("voice_first_audio_basis" in trace.incidentFields())
        assertEquals(2L, trace.incidentFields()["voice_generation"])
        val oldHash = trace.incidentFields()["voice_session_id_hash"]
        val other = VoiceDiagnosticTrace({ 10 }, { 1000 }, { true }, retained::add)
        other.advance(1, reset = true).engine(VoiceDiagnosticTrace.Engine.KOKORO)
        current.phase(VoicePhase.ERROR)
        trace.close()
        assertTrue(retained.isEmpty())
        assertNotEquals(oldHash, other.incidentFields()["voice_session_id_hash"])
        assertEquals("KOKORO", requireNotNull(VoiceDiagnosticTrace.manualSnapshot()).values["voice_engine"]?.toString()?.trim('"'))
    }

    @Test fun privacyClearRejectsOldCallbacksAndDisabledTicketsCannotResumeCollection() {
        var enabled = true
        val trace = VoiceDiagnosticTrace({ 10 }, { 1000 }, { enabled }, {})
        val old = trace.advance(1, reset = true)
        old.engine(VoiceDiagnosticTrace.Engine.LOCAL)
        VoiceDiagnosticTrace.clearAll()
        old.read(100, 100)
        assertTrue(trace.incidentFields().isEmpty())
        enabled = false
        val disabled = trace.advance(2, reset = true)
        disabled.engine(VoiceDiagnosticTrace.Engine.LOCAL)
        enabled = true
        disabled.read(100, 100)
        assertTrue(trace.incidentFields().isEmpty())
        val current = trace.advance(3, reset = true)
        current.engine(VoiceDiagnosticTrace.Engine.SYSTEM)
        assertEquals("SYSTEM", trace.incidentFields()["voice_engine"])
        assertFalse("voice_read_calls" in trace.incidentFields())
        assertTrue(VoiceDiagnosticTrace.manualSnapshot() != null)
    }

    @Test fun unknownPcmStaysAbsentAndActualCountsRemainBounded() {
        var time = 0L
        val trace = VoiceDiagnosticTrace({ time }, { 1000 + time }, { true }, {})
        val probe = trace.advance(1, reset = true)
        probe.engine(VoiceDiagnosticTrace.Engine.SYSTEM)
        probe.captureAttempt()
        assertFalse("voice_captured_samples" in trace.incidentFields())
        probe.captureReadsAvailable()
        assertEquals(0L, trace.incidentFields()["voice_captured_samples"])
        probe.read(1600, 400)
        probe.read(0, 0)
        probe.recognized(27)
        probe.responseText()
        time = 90_000_000
        probe.queued()
        val fields = trace.incidentFields()
        assertEquals(2L, fields["voice_read_calls"])
        assertEquals(1600L, fields["voice_captured_samples"])
        assertEquals(400L, fields["voice_nonzero_samples"])
        assertEquals(27L, fields["voice_recognized_characters"])
        assertEquals(86_400_000L, fields["voice_response_to_queue_ms"])
        probe.read(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(16_000_000L, trace.incidentFields()["voice_captured_samples"])
        assertEquals(16_000_000L, trace.incidentFields()["voice_nonzero_samples"])
    }

    @Test fun sessionMeasuresTextToQueueAndFencesCancelledPlaybackWithoutChangingCaptions() {
        var time = 0L
        val trace = VoiceDiagnosticTrace({ time }, { 1000 + time }, { true }, {})
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {}, { time }, diagnosticTrace = trace)
        session.access(true, true, true)
        session.resume()
        requireNotNull(audio.listener).final("Keep these words")
        time = 100
        session.streamReply("run", "Here")
        assertTrue(audio.spoken.isEmpty())
        time = 260
        val reply = "Here are four words. "
        session.streamReply("run", reply)
        assertEquals(reply, session.state.reply)
        assertEquals(listOf(reply), audio.spoken)
        assertEquals(160L, trace.incidentFields()["voice_response_to_queue_ms"])
        val playback = requireNotNull(audio.playback)
        time = 380
        playback.synthesisStarted()
        time = 560
        playback.firstAudio(VoiceDiagnosticTrace.FirstAudio.UTTERANCE_START)
        assertEquals(460L, trace.incidentFields()["voice_response_to_first_audio_ms"])
        assertEquals(180L, trace.incidentFields()["voice_synthesis_to_first_audio_ms"])
        session.interrupt()
        playback.read(1600, 1600)
        playback.firstAudio(VoiceDiagnosticTrace.FirstAudio.UTTERANCE_START)
        assertFalse("voice_response_to_first_audio_ms" in trace.incidentFields())
        assertFalse("voice_read_calls" in trace.incidentFields())
        session.close()
        assertNull(VoiceDiagnosticTrace.manualSnapshot())
    }

    @Test fun explicitReplayMeasuresANewPlaybackWithoutReusingThePreviousDelay() {
        var time = 10L
        val trace = VoiceDiagnosticTrace({ time }, { 1000 + time }, { true }, {})
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {}, { time }, diagnosticTrace = trace)
        session.access(true, true, true)
        session.resume()
        requireNotNull(audio.listener).final("Keep this draft")
        val caption = "This is the answer."
        time = 100
        session.reply("first-run", caption)
        time = 200
        requireNotNull(audio.playback).synthesisStarted()
        time = 400
        requireNotNull(audio.playback).firstAudio(VoiceDiagnosticTrace.FirstAudio.UTTERANCE_START)
        assertEquals(300L, trace.incidentFields()["voice_response_to_first_audio_ms"])
        requireNotNull(audio.complete).invoke()
        time = 900
        session.replay()
        assertFalse("voice_response_to_first_audio_ms" in trace.incidentFields())
        time = 950
        requireNotNull(audio.playback).synthesisStarted()
        time = 1000
        requireNotNull(audio.playback).firstAudio(VoiceDiagnosticTrace.FirstAudio.UTTERANCE_START)
        assertEquals(100L, trace.incidentFields()["voice_response_to_first_audio_ms"])
        assertEquals(50L, trace.incidentFields()["voice_synthesis_to_first_audio_ms"])
        assertEquals(caption, session.state.reply)
        assertEquals(listOf(caption, caption), audio.spoken)
        session.close()
    }

    @Test fun sequentialAndroidChunksKeepTheFirstColdReadinessAndStartupTiming() {
        var time = 10L
        val trace = VoiceDiagnosticTrace({ time }, { 1000 + time }, { true }, {})
        val audio = Audio()
        val session = VoiceSession(audio, { true }, {}, { time }, diagnosticTrace = trace)
        session.access(true, true, true)
        session.resume()
        requireNotNull(audio.listener).final("Please answer briefly")
        time = 100
        session.streamReply("run", "These first four words. ")
        val first = requireNotNull(audio.playback)
        first.warmupStarted()
        time = 200
        first.engineReady(warm = false)
        time = 250
        first.synthesisStarted()
        time = 350
        first.firstAudio(VoiceDiagnosticTrace.FirstAudio.UTTERANCE_START)
        val initial = trace.incidentFields()
        val caption = "These first four words. Continue into a second sentence. "
        time = 400
        session.streamReply("run", caption)
        requireNotNull(audio.complete).invoke()
        val second = requireNotNull(audio.playback)
        second.warmupStarted()
        second.engineReady(warm = true)
        time = 450
        second.synthesisStarted()
        time = 500
        second.firstAudio(VoiceDiagnosticTrace.FirstAudio.UTTERANCE_START)
        val observed = trace.incidentFields()
        assertEquals(false, observed["voice_engine_warm"])
        for (field in listOf("voice_warmup_ms", "voice_response_to_queue_ms", "voice_queue_to_synthesis_ms", "voice_synthesis_to_first_audio_ms", "voice_response_to_first_audio_ms")) {
            assertEquals(field, initial[field], observed[field])
        }
        assertEquals(listOf("These first four words. ", "Continue into a second sentence. "), audio.spoken)
        assertEquals(caption, session.state.reply)
        session.close()
    }

    private class Audio : VoiceAudio {
        private var probe: VoiceDiagnosticTrace.Probe? = null
        var listener: VoiceAudio.Listener? = null
        var playback: VoiceDiagnosticTrace.Probe? = null
        var complete: (() -> Unit)? = null
        val spoken = mutableListOf<String>()
        override fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) {
            this.probe = probe
        }
        override fun listen(listener: VoiceAudio.Listener) {
            this.listener = listener
            probe?.engine(VoiceDiagnosticTrace.Engine.LOCAL)
            probe?.captureAttempt()
            listener.ready()
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
            this.complete = complete
            spoken += text
            probe?.engine(VoiceDiagnosticTrace.Engine.SYSTEM)
            playback = probe
        }
        override fun stop() = Unit
        override fun close() = Unit
    }
}
