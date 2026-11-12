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
        val reply = "Here are four words "
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
