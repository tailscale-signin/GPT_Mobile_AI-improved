package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.presentation.ui.voice.playKokoroPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredKokoroOutputTest {
    @Test fun aPendingReplyIdentityAndCancellationOriginReachTheLazyBackend() {
        val delegate = FakeKokoro()
        var constructions = 0
        val output = DeferredKokoroOutput {
            constructions++
            delegate
        }
        output.bindReply("reply-one")
        output.stop("session_paused")
        assertEquals(0, constructions)
        output.prewarm(KokoroVoice.HEART)
        assertEquals(listOf("reply-one"), delegate.replies)
        output.bindReply("reply-two")
        output.stop("user_barge_in")
        assertEquals(listOf("reply-one", "reply-two"), delegate.replies)
        assertEquals(listOf("user_barge_in"), delegate.stops)
        output.close()
        output.bindReply("after-close")
        assertEquals(listOf("reply-one", "reply-two"), delegate.replies)
    }

    @Test fun configurationStopAndCloseDoNotLoadNativePlayback() {
        var constructions = 0
        val output = DeferredKokoroOutput {
            constructions++
            FakeKokoro()
        }

        output.configureThreads(4)
        output.stop()
        output.releaseWarmModel()
        output.close()

        assertEquals(0, constructions)
    }

    @Test fun firstPlaybackLoadsOnceAndAppliesThePendingThreadCount() {
        var constructions = 0
        val delegate = FakeKokoro()
        val output = DeferredKokoroOutput {
            constructions++
            delegate
        }
        output.configureThreads(4)

        output.prewarm(KokoroVoice.HEART)
        output.prewarm(KokoroVoice.ADAM)

        assertEquals(1, constructions)
        assertEquals(4, delegate.threads)
        assertEquals(listOf(KokoroVoice.HEART, KokoroVoice.ADAM), delegate.prewarmed)
        output.close()
        assertTrue(delegate.closed)
    }

    @Test fun unavailableNativePlaybackRequestsTheExistingOfflineFallback() {
        val output = DeferredKokoroOutput { null }
        var fallback = false
        output.speak("hello", KokoroVoice.EMMA, {}, { _, canFallback -> fallback = canFallback })

        assertTrue(fallback)
        assertNull(output.openReply(KokoroVoice.EMMA, {}, { _, _ -> }))
    }

    @Test fun settingsPreviewCannotReuseReplyTimingAndReplayAndCaptureRebindTheirOwnProbe() {
        var time = 10L
        val trace = VoiceDiagnosticTrace({ time }, { 1000 + time }, { true }, {})
        var systemProbe: VoiceDiagnosticTrace.Probe? = null
        var systemComplete: (() -> Unit)? = null
        val system = object : VoiceAudio {
            override fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) {
                systemProbe = probe
            }
            override fun listen(listener: VoiceAudio.Listener) = Unit
            override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
                systemProbe?.engine(VoiceDiagnosticTrace.Engine.SYSTEM)
                systemProbe?.synthesisStarted()
                systemComplete = complete
            }
            override fun stop() = Unit
            override fun close() = Unit
        }
        var localProbe: VoiceDiagnosticTrace.Probe? = null
        var recognitionListener: VoiceAudio.Listener? = null
        val local = object : VoiceRecognitionInput {
            override var languageTag = "en-US"
            override fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) {
                localProbe = probe
            }
            override fun listen(listener: VoiceAudio.Listener) {
                recognitionListener = listener
                localProbe?.engine(VoiceDiagnosticTrace.Engine.LOCAL)
                localProbe?.captureAttempt()
                listener.ready()
            }
            override fun stop() = Unit
            override fun close() = Unit
        }
        var backendProbe: VoiceDiagnosticTrace.Probe? = null
        var backendReply: String? = null
        val playedWith = mutableListOf<VoiceDiagnosticTrace.Probe?>()
        val playedReplies = mutableListOf<String?>()
        val spoken = mutableListOf<String>()
        val output = DeferredKokoroOutput {
            object : KokoroOutput {
                override fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) {
                    backendProbe = probe
                }
                override fun bindReply(id: String) {
                    backendReply = id
                }
                override fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) {
                    playedWith += backendProbe
                    playedReplies += backendReply
                    spoken += text
                    backendProbe?.engine(VoiceDiagnosticTrace.Engine.KOKORO)
                    backendProbe?.synthesisStarted()
                    time += 100
                    backendProbe?.firstAudio(VoiceDiagnosticTrace.FirstAudio.PLAYBACK_HEAD)
                    complete()
                }
                override fun stop() = Unit
                override fun close() = Unit
            }
        }
        val audio = VoicePluginAudio(system, local, output, releaseSystemRecognition = {})
        val session = VoiceSession(audio, { true }, {}, { time }, diagnosticTrace = trace)
        session.access(true, true, true)
        session.resume()
        requireNotNull(recognitionListener).final("Please answer briefly")
        val caption = "This original reply ends."
        time = 100
        session.streamReply("run", caption, complete = true)
        time = 200
        requireNotNull(systemProbe).firstAudio(VoiceDiagnosticTrace.FirstAudio.UTTERANCE_START)
        requireNotNull(systemComplete).invoke()
        session.pause()
        val original = trace.incidentFields()
        time = 1000
        audio.stop()
        playKokoroPreview(output, KokoroVoice.HEART) { message, _ -> error(message) }
        assertNull(playedWith.single())
        assertEquals("", playedReplies.single())
        assertEquals(original, trace.incidentFields())
        audio.kokoroVoice = KokoroVoice.HEART
        time = 2000
        session.replay()
        assertTrue(playedWith.last() != null)
        assertTrue(playedReplies.drop(1).all { it?.startsWith("replay:") == true })
        assertEquals(100L, trace.incidentFields()["voice_response_to_first_audio_ms"])
        assertEquals("Hello. This is your Kokoro voice. I am ready when you are.", spoken.first())
        assertEquals(caption, spoken.drop(1).joinToString(""))
        assertEquals(caption, session.state.reply)
        session.resume()
        assertTrue(backendProbe === localProbe && backendProbe !== playedWith.last())
        assertEquals("LOCAL", trace.incidentFields()["voice_engine"])
        assertEquals(1L, trace.incidentFields()["voice_capture_attempts"])
        session.close()
    }

    private class FakeKokoro : KokoroOutput {
        var threads = 2
        var closed = false
        val prewarmed = mutableListOf<KokoroVoice>()
        val replies = mutableListOf<String>()
        val stops = mutableListOf<String>()

        override fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) = complete()
        override fun configureThreads(value: Int) {
            threads = value
        }
        override fun prewarm(voice: KokoroVoice) {
            prewarmed += voice
        }
        override fun stop() = Unit
        override fun stop(reason: String) {
            stops += reason
        }
        override fun bindReply(id: String) {
            replies += id
        }
        override fun close() {
            closed = true
        }
    }
}
