package dev.chungjungsoo.gptmobile.data.voice

/** Versioned boundary for a future downloadable voice implementation. No Android or provider APIs. */
interface VoiceAudio {
    interface Listener {
        fun ready()
        fun level(value: Float)
        fun partial(text: String)
        fun final(text: String)
        fun error(message: String)
    }
    fun listen(listener: Listener)
    fun speak(text: String, complete: () -> Unit, error: (String) -> Unit)
    fun stop()
    fun close()
}

enum class VoicePhase { PAUSED, LISTENING, THINKING, SPEAKING, ERROR, CLOSED }

data class VoiceState(
    val phase: VoicePhase = VoicePhase.PAUSED,
    val transcript: String = "",
    val reply: String = "",
    val level: Float = 0f,
    val muted: Boolean = false,
    val speaker: Boolean = true,
    val notice: String? = null
)

/** Main-thread, half-duplex session. Generation fencing rejects callbacks from cancelled capture/playback. */
class VoiceSession(
    private val audio: VoiceAudio,
    private val submit: (String) -> Boolean,
    private val changed: (VoiceState) -> Unit
) {
    companion object {
        const val API_VERSION = 1
    }
    var state = VoiceState()
        private set
    private var enabled = false
    private var permission = false
    private var foreground = false
    private var generation = 0L
    private var waiting = false
    private var engaged = false
    private var lastReply: String? = null
    private fun update(next: VoiceState) {
        state = next
        changed(next)
    }
    private fun stopAudio() {
        generation++
        audio.stop()
    }
    private fun accessAllowed() = enabled && permission && foreground && state.phase != VoicePhase.CLOSED
    private fun playbackAllowed() = engaged && accessAllowed()
    private fun captureAllowed() = playbackAllowed() && !state.muted

    fun access(enabled: Boolean, permission: Boolean, foreground: Boolean) {
        this.enabled = enabled
        this.permission = permission
        this.foreground = foreground
        if (!accessAllowed() && state.phase != VoicePhase.CLOSED) pause()
    }

    fun resume() {
        if (!accessAllowed()) return
        engaged = true
        stopAudio()
        if (waiting) {
            update(state.copy(phase = VoicePhase.THINKING, level = 0f))
            return
        }
        if (state.muted) {
            update(state.copy(phase = VoicePhase.PAUSED, level = 0f))
            return
        }
        val ticket = generation
        update(state.copy(phase = VoicePhase.LISTENING, level = 0f, transcript = "", notice = null))
        fun valid() = ticket == generation && captureAllowed() && state.phase == VoicePhase.LISTENING
        audio.listen(object : VoiceAudio.Listener {
            override fun ready() = Unit
            override fun level(value: Float) {
                if (valid()) update(state.copy(level = value.coerceIn(0f, 1f)))
            }
            override fun partial(text: String) {
                if (valid()) update(state.copy(transcript = text.take(8000)))
            }
            override fun final(text: String) {
                if (!valid()) return
                stopAudio()
                val prompt = text.trim().take(8000)
                if (prompt.isEmpty()) {
                    pause()
                    return
                }
                waiting = true
                update(state.copy(phase = VoicePhase.THINKING, transcript = prompt, level = 0f))
                if (!submit(prompt)) fail("Could not send this turn. Open chat to review your profile and connection.")
            }
            override fun error(message: String) {
                if (valid()) fail(message)
            }
        })
    }

    fun reply(id: String, text: String, failed: Boolean = false) {
        if (!waiting || id == lastReply || state.phase == VoicePhase.CLOSED) return
        waiting = false
        lastReply = id
        if (failed || text.isBlank()) {
            fail("This turn did not finish. Open chat to review it or try again.")
            return
        }
        update(state.copy(reply = text))
        if (!playbackAllowed()) {
            pause()
            return
        }
        if (!state.speaker) {
            resume()
            return
        }
        stopAudio()
        val ticket = generation
        update(state.copy(phase = VoicePhase.SPEAKING, level = 0f, notice = null))
        audio.speak(text, complete = {
            if (ticket == generation && playbackAllowed()) resume()
        }, error = { message -> if (ticket == generation) fail(message) })
    }

    fun mute() {
        update(state.copy(muted = !state.muted))
        if (state.phase == VoicePhase.SPEAKING || state.phase == VoicePhase.THINKING) return
        if (state.muted) {
            stopAudio()
            update(state.copy(phase = VoicePhase.PAUSED, level = 0f))
        } else {
            resume()
        }
    }
    fun speaker() {
        update(state.copy(speaker = !state.speaker))
        if (!state.speaker && state.phase == VoicePhase.SPEAKING) resume()
    }
    fun pause() {
        if (state.phase == VoicePhase.CLOSED) return
        engaged = false
        stopAudio()
        update(state.copy(phase = VoicePhase.PAUSED, level = 0f))
    }
    fun fail(message: String) {
        if (state.phase == VoicePhase.CLOSED) return
        engaged = false
        stopAudio()
        waiting = false
        update(state.copy(phase = VoicePhase.ERROR, level = 0f, notice = message))
    }
    fun close() {
        if (state.phase == VoicePhase.CLOSED) return
        stopAudio()
        waiting = false
        audio.close()
        update(state.copy(phase = VoicePhase.CLOSED, level = 0f))
    }
}

/** Keep speech queues below Android engine limits without splitting surrogate pairs. */
internal fun speechChunks(text: String, limit: Int = 3000): List<String> {
    require(limit >= 2)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + limit).coerceAtMost(text.length)
        if (end < text.length && text[end - 1].isHighSurrogate()) end--
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}
