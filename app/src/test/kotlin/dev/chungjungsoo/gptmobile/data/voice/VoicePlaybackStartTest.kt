package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlaybackStartTest {
    @Test fun preparingPersistsUntilTheOutputStartsForWholeAndStreamedReplies() {
        for (streaming in listOf(false, true)) {
            val audio = ObservedAudio().apply { streamsReplies = streaming }
            val session = session(audio)
            audio.listeners.last().final("Read my answer")
            session.reply("first", "The complete answer.")
            assertEquals(VoicePhase.PREPARING, session.state.phase)
            session.resume()
            assertEquals("Preparation still owns the half-duplex session", 1, audio.listeners.size)

            audio.starts.last().invoke()
            assertEquals(VoicePhase.SPEAKING, session.state.phase)
            audio.completions.last().invoke()
            assertEquals(VoicePhase.LISTENING, session.state.phase)
            assertEquals(2, audio.listeners.size)
            session.close()
        }
    }

    @Test fun cancelledPreparationCannotMarkTheNextTurnAsSpeaking() {
        for (streaming in listOf(false, true)) {
            val audio = ObservedAudio().apply { streamsReplies = streaming }
            val session = session(audio)
            audio.listeners.last().final("First question")
            session.reply("first", "The first answer.")
            val cancelledStart = audio.starts.last()
            val cancelledCompletion = audio.completions.last()
            session.stopSpeaking()
            assertEquals(VoicePhase.LISTENING, session.state.phase)
            audio.listeners.last().final("Second question")
            session.reply("second", "The second answer.")
            cancelledStart()
            cancelledCompletion()
            assertEquals(VoicePhase.PREPARING, session.state.phase)
            assertEquals(2, audio.listeners.size)
            audio.starts.last().invoke()
            assertEquals(VoicePhase.SPEAKING, session.state.phase)
            session.close()
        }
    }

    @Test fun anEarlierChunkStartCannotSkipPreparationOfTheNextChunk() {
        val audio = ObservedAudio()
        val session = session(audio)
        audio.listeners.last().final("Explain this")
        session.streamReply("reply", "Here are four words ")
        val firstStart = audio.starts.single()
        firstStart()
        session.reply("reply", "Here are four words followed by the final answer.")
        audio.completions.first().invoke()
        assertEquals(2, audio.starts.size)
        assertEquals(VoicePhase.PREPARING, session.state.phase)
        firstStart()
        assertEquals(VoicePhase.PREPARING, session.state.phase)
        audio.starts.last().invoke()
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        session.close()
    }

    @Test fun offlineFallbackUsesItsOwnStartAndRejectsBothCancelledCallbacks() {
        val system = ObservedAudio()
        val output = ObservedKokoro()
        val plugin = plugin(system, output)
        var starts = 0
        plugin.bindPlaybackStarted { starts++ }
        plugin.speak("Read this", {}, { error(it) })
        val nativeStart = output.starts.single()
        assertTrue(plugin.reportsPlaybackStart)
        assertEquals(0, starts)
        output.errors.single().invoke("Use the installed voice", true)
        nativeStart()
        assertEquals(0, starts)
        val fallbackStart = system.starts.single()
        fallbackStart()
        assertEquals(1, starts)
        plugin.stop()
        nativeStart()
        fallbackStart()
        assertEquals(1, starts)
        plugin.close()
    }

    @Test fun streamedFallbackStaysPreparingUntilTheFallbackActuallyStarts() {
        val system = ObservedAudio()
        val output = ObservedKokoro()
        val plugin = plugin(system, output)
        var starts = 0
        plugin.bindPlaybackStarted { starts++ }
        val reply = requireNotNull(plugin.openReply({}, { error(it) }))
        reply.enqueue("Complete words ")
        val nativeStart = output.starts.single()
        output.errors.single().invoke("Use the installed voice", true)
        reply.enqueue("stay in order.")
        assertTrue(system.spoken.isEmpty())
        reply.finish()
        assertEquals(listOf("Complete words stay in order."), system.spoken)
        nativeStart()
        assertEquals(0, starts)
        system.starts.single().invoke()
        assertEquals(1, starts)
        plugin.close()
        system.starts.single().invoke()
        assertEquals(1, starts)
    }

    @Test fun bindingAStartObserverDoesNotCreateTheDeferredNativeEngine() {
        val delegate = ObservedKokoro()
        var created = 0
        var starts = 0
        val output = DeferredKokoroOutput {
            created++
            delegate
        }
        output.bindPlaybackStarted { starts++ }
        assertTrue(output.reportsPlaybackStart)
        assertEquals(0, created)
        output.speak("Hello", KokoroVoice.HEART, {}, { _, _ -> error("Unexpected failure") })
        assertEquals(1, created)
        assertEquals(0, starts)
        delegate.starts.single().invoke()
        assertEquals(1, starts)
        output.close()
    }

    private fun session(audio: VoiceAudio) = VoiceSession(audio, { true }, {}).apply {
        autoContinue = true
        access(true, true, true)
        resume()
    }

    private fun plugin(system: ObservedAudio, output: KokoroOutput): VoicePluginAudio {
        val local = object : VoiceRecognitionInput {
            override var languageTag = "en-US"
            override fun listen(listener: VoiceAudio.Listener) = listener.ready()
            override fun stop() = Unit
            override fun close() = Unit
        }
        return VoicePluginAudio(system, local, output, system::speak) {}.apply { kokoroVoice = KokoroVoice.HEART }
    }

    private class ObservedAudio : VoiceAudio {
        override val reportsPlaybackStart = true
        override var streamsReplies = false
        private var observer: (() -> Unit)? = null
        val listeners = mutableListOf<VoiceAudio.Listener>()
        val starts = mutableListOf<() -> Unit>()
        val completions = mutableListOf<() -> Unit>()
        val spoken = mutableListOf<String>()
        override fun bindPlaybackStarted(started: (() -> Unit)?) {
            observer = started
        }
        override fun listen(listener: VoiceAudio.Listener) {
            listeners += listener
            listener.ready()
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
            starts += requireNotNull(observer)
            completions += complete
            spoken += text
        }
        override fun openReply(complete: () -> Unit, error: (String) -> Unit): VoiceReplyPlayback? {
            if (!streamsReplies) return null
            starts += requireNotNull(observer)
            completions += complete
            return object : VoiceReplyPlayback {
                override fun enqueue(text: String) {
                    spoken += text
                }
                override fun finish() = Unit
            }
        }
        override fun stop() = Unit
        override fun close() = Unit
    }

    private class ObservedKokoro : KokoroOutput {
        override val reportsPlaybackStart = true
        private var observer: (() -> Unit)? = null
        val starts = mutableListOf<() -> Unit>()
        val errors = mutableListOf<(String, Boolean) -> Unit>()
        override fun bindPlaybackStarted(started: (() -> Unit)?) {
            observer = started
        }
        override fun speak(text: String, voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit) {
            starts += requireNotNull(observer)
            errors += error
        }
        override fun openReply(voice: KokoroVoice, complete: () -> Unit, error: (String, Boolean) -> Unit): VoiceReplyPlayback {
            starts += requireNotNull(observer)
            errors += error
            return object : VoiceReplyPlayback {
                override fun enqueue(text: String) = Unit
                override fun finish() = Unit
            }
        }
        override fun stop() = Unit
        override fun close() = Unit
    }
}
