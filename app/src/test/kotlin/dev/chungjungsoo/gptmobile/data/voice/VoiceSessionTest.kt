package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionTest {
    private class Audio : VoiceAudio {
        val listeners = mutableListOf<VoiceAudio.Listener>()
        val spoken = mutableListOf<String>()
        var done: (() -> Unit)? = null
        var stopped = 0
        var closed = false
        override fun listen(listener: VoiceAudio.Listener) {
            listeners += listener
        }
        override fun speak(text: String, complete: () -> Unit, error: (String) -> Unit) {
            spoken += text
            done = complete
        }
        override fun stop() {
            stopped++
        }
        override fun close() {
            closed = true
        }
    }
    private val audio = Audio()
    private val prompts = mutableListOf<String>()
    private val session = VoiceSession(audio, {
        prompts += it
        true
    }, {})
    private fun start() {
        session.access(true, true, true)
        session.resume()
    }

    @Test fun `disabled permissionless or background sessions cannot listen`() {
        for (flags in listOf(Triple(false, true, true), Triple(true, false, true), Triple(true, true, false))) {
            session.access(flags.first, flags.second, flags.third)
            session.resume()
        }
        assertTrue(audio.listeners.isEmpty())
    }

    @Test fun `partial results never submit and final callbacks submit once`() {
        start()
        val listener = audio.listeners.last()
        listener.partial("Draft speech")
        assertTrue(prompts.isEmpty())
        listener.final("  Hello  ")
        listener.final("Duplicate")
        assertEquals(listOf("Hello"), prompts)
        assertEquals(VoicePhase.THINKING, session.state.phase)
    }

    @Test fun `speech never overlaps microphone and completion starts next turn`() {
        start()
        audio.listeners.last().final("Hello")
        assertEquals(1, audio.listeners.size)
        session.reply("run-1", "Welcome")
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        assertEquals(listOf("Welcome"), audio.spoken)
        audio.done?.invoke()
        assertEquals(2, audio.listeners.size)
        assertEquals(VoicePhase.LISTENING, session.state.phase)
        session.reply("run-1", "Welcome")
        assertEquals(1, audio.spoken.size)
    }

    @Test fun `mute fences late recognition and unmute starts fresh capture`() {
        start()
        val old = audio.listeners.last()
        session.mute()
        old.final("Late private audio")
        assertTrue(prompts.isEmpty())
        assertEquals(VoicePhase.PAUSED, session.state.phase)
        session.mute()
        assertEquals(2, audio.listeners.size)
    }

    @Test fun `microphone mute still permits the pending assistant response`() {
        start()
        audio.listeners.last().final("Hello")
        session.mute()
        session.reply("run", "Reply")
        assertEquals(listOf("Reply"), audio.spoken)
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        audio.done?.invoke()
        assertEquals(1, audio.listeners.size)
        assertEquals(VoicePhase.PAUSED, session.state.phase)
        session.mute()
        assertEquals(2, audio.listeners.size)
    }

    @Test fun `muting during playback preserves speech and prevents automatic capture`() {
        start()
        audio.listeners.last().final("Hello")
        session.reply("run", "Reply")
        val stopped = audio.stopped
        session.mute()
        assertEquals(stopped, audio.stopped)
        assertEquals(VoicePhase.SPEAKING, session.state.phase)
        audio.done?.invoke()
        assertEquals(1, audio.listeners.size)
    }

    @Test fun `background stops playback and ignores late completion`() {
        start()
        audio.listeners.last().final("Hello")
        session.reply("run", "Reply")
        val callback = audio.done
        session.access(true, true, false)
        callback?.invoke()
        assertEquals(1, audio.listeners.size)
        assertEquals(VoicePhase.PAUSED, session.state.phase)
        session.access(true, true, true)
        assertEquals(1, audio.listeners.size)
    }

    @Test fun `reply arriving while backgrounded stays silent`() {
        start()
        audio.listeners.last().final("Hello")
        session.access(true, true, false)
        session.reply("run", "Reply")
        assertTrue(audio.spoken.isEmpty())
        assertEquals("Reply", session.state.reply)
    }

    @Test fun `returning to foreground needs an explicit resume before audio restarts`() {
        start()
        audio.listeners.last().final("Hello")
        session.access(true, true, false)
        session.access(true, true, true)
        session.reply("run", "Reply")
        assertTrue(audio.spoken.isEmpty())
        assertEquals(1, audio.listeners.size)
        session.resume()
        assertEquals(2, audio.listeners.size)
    }

    @Test fun `speaker off keeps captions and restarts listening`() {
        start()
        session.speaker()
        audio.listeners.last().final("Hello")
        session.reply("run", "Reply")
        assertTrue(audio.spoken.isEmpty())
        assertEquals("Reply", session.state.reply)
        assertEquals(VoicePhase.LISTENING, session.state.phase)
    }

    @Test fun `interrupt invalidates old speech completion and close prevents resurrection`() {
        start()
        audio.listeners.last().final("Hello")
        session.reply("run", "Reply")
        val old = audio.done
        session.resume()
        assertEquals(2, audio.listeners.size)
        old?.invoke()
        assertEquals(2, audio.listeners.size)
        val listener = audio.listeners.last()
        session.close()
        listener.final("Late")
        old?.invoke()
        session.resume()
        assertEquals(listOf("Hello"), prompts)
        assertTrue(audio.closed)
        assertEquals(VoicePhase.CLOSED, session.state.phase)
    }

    @Test fun `failed dispatch and recognition error never retry automatically`() {
        val rejecting = VoiceSession(audio, { false }, {})
        rejecting.access(true, true, true)
        rejecting.resume()
        audio.listeners.last().final("Hello")
        assertEquals(VoicePhase.ERROR, rejecting.state.phase)
        assertEquals(1, audio.listeners.size)
        rejecting.resume()
        audio.listeners.last().error("Network unavailable")
        assertEquals(2, audio.listeners.size)
        assertEquals(VoicePhase.ERROR, rejecting.state.phase)
    }

    @Test fun `chunking preserves all text and unicode boundaries`() {
        val original = "ab😀cd".repeat(2000)
        val chunks = speechChunks(original, 7)
        assertEquals(original, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 7 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }
}
