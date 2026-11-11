package dev.chungjungsoo.gptmobile.data.voice

/** Versioned boundary shared by installed local recognition and Android speech. */
interface VoiceAudio {
    interface Listener {
        fun ending() = Unit
        fun failure(message: String, retryMillis: Long) = error(message)
        fun retryableFailure(message: String, retryMillis: Long) = failure(message, retryMillis)
        fun repairRequired(message: String, retryMillis: Long, action: VoiceRecoveryAction) = failure(message, retryMillis)
        fun ready()
        fun level(value: Float)
        fun partial(text: String)
        fun final(text: String)
        fun error(message: String)
    }
    fun retryAfterMillis(): Long = 0
    fun listen(listener: Listener)
    fun speak(text: String, complete: () -> Unit, error: (String) -> Unit)
    fun stop()
    fun close()
}

enum class VoicePhase { PAUSED, STARTING, LISTENING, FINALIZING, REVIEWING, THINKING, SPEAKING, ERROR, CLOSED }
enum class VoiceRecoveryAction { MICROPHONE_PERMISSION, SPEECH_SERVICE, SPEECH_LANGUAGE, LOCAL_MODEL }

data class VoiceState(
    val phase: VoicePhase = VoicePhase.PAUSED,
    val transcript: String = "",
    val reply: String = "",
    val level: Float = 0f,
    val muted: Boolean = false,
    val speaker: Boolean = true,
    val notice: String? = null,
    val retryAtMillis: Long = 0,
    val hasAudioLevel: Boolean = false,
    val awaitingReply: Boolean = false,
    val hasDraft: Boolean = false,
    val recoveryAction: VoiceRecoveryAction? = null
)

/** Main-thread, half-duplex session. Generation fencing rejects callbacks from cancelled capture/playback. */
class VoiceSession(
    private val audio: VoiceAudio,
    private val submit: (String) -> Boolean,
    private val changed: (VoiceState) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    companion object {
        const val API_VERSION = 2
    }
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(VoiceState())
    val states: kotlinx.coroutines.flow.StateFlow<VoiceState> = mutableState
    var state = VoiceState()
        private set
    private var enabled = false
    private var permission = false
    private var foreground = false
    private var generation = 0L
    private var waiting = false
    private var draftPending = false
    private var engaged = false
    private var automaticRetry = false
    private var recognitionFailures = 0
    private var lastReply: String? = null
    private var streamId: String? = null
    private var speechBuffer = VoiceSpeechBuffer()
    private val queuedSpeech = ArrayDeque<String>()
    private var chunkPlaying = false
    private var streamComplete = false
    private var streamAudio = true
    private var playbackSequence = 0L
    private fun resetStream() {
        streamId = null
        speechBuffer = VoiceSpeechBuffer()
        queuedSpeech.clear()
        chunkPlaying = false
        streamComplete = false
        streamAudio = true
    }
    var editBeforeSend = false
    var autoContinue = false
    fun editDraft(text: String) {
        if (state.phase != VoicePhase.REVIEWING || waiting) return
        draftPending = true
        update(state.copy(transcript = text.take(8000)))
    }
    private fun continueTurn() {
        if (autoContinue) resume() else pause()
    }
    fun tick() {
        if (state.phase != VoicePhase.CLOSED && state.retryAtMillis > 0 && now() >= state.retryAtMillis) {
            val retry = automaticRetry && autoContinue && !state.muted && accessAllowed()
            automaticRetry = false
            update(state.copy(retryAtMillis = 0))
            if (retry) resume()
        }
    }
    fun discardDraft() {
        if (state.phase == VoicePhase.CLOSED || waiting) return
        draftPending = false
        update(state.copy(phase = VoicePhase.PAUSED, notice = null))
    }
    fun sendDraft(text: String = state.transcript) {
        if (state.phase != VoicePhase.REVIEWING || !accessAllowed()) return
        val prompt = text.trim().take(8000)
        if (prompt.isEmpty()) return
        resetStream()
        waiting = true
        draftPending = false
        update(state.copy(phase = VoicePhase.THINKING, transcript = prompt, level = 0f))
        if (!submit(prompt)) {
            draftPending = true
            fail("Could not send this turn. Open chat to review your profile and connection.")
        }
    }
    fun restore(transcript: String, reply: String, review: Boolean, pending: Boolean) {
        if (state.phase != VoicePhase.PAUSED || engaged) return
        waiting = pending
        draftPending = review
        update(state.copy(transcript = transcript, reply = reply, phase = if (review) VoicePhase.REVIEWING else VoicePhase.PAUSED))
    }
    fun replay() {
        if (state.reply.isBlank() || !accessAllowed() || waiting) return
        waiting = true
        lastReply = null
        engaged = true
        reply("replay:$generation", state.reply)
    }
    private fun update(next: VoiceState) {
        state = next.copy(awaitingReply = waiting, hasDraft = draftPending)
        mutableState.value = state
        changed(state)
    }
    private fun stopAudio() {
        generation++
        queuedSpeech.clear()
        chunkPlaying = false
        if (streamId != null) streamAudio = false
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
        if (!accessAllowed() || state.phase in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING, VoicePhase.REVIEWING)) return
        // Reviewing preserved text never touches the recognizer and must stay usable
        // during its cooldown, including after an error or session recreation.
        if (draftPending) {
            engaged = true
            automaticRetry = false
            update(state.copy(phase = VoicePhase.REVIEWING))
            return
        }
        val remaining = maxOf(audio.retryAfterMillis(), (state.retryAtMillis - now()).coerceAtLeast(0))
        if (remaining > 0) {
            update(state.copy(phase = VoicePhase.ERROR, retryAtMillis = now() + remaining, notice = "Speech service is busy. Your chat is still available."))
            return
        }
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
        update(state.copy(phase = VoicePhase.STARTING, transcript = "", level = 0f, hasAudioLevel = false, notice = null, retryAtMillis = 0, recoveryAction = null))
        fun valid() = ticket == generation && captureAllowed() && state.phase in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING)
        audio.listen(object : VoiceAudio.Listener {
            override fun ready() {
                if (valid()) update(state.copy(phase = VoicePhase.LISTENING))
            }
            override fun ending() {
                if (valid()) update(state.copy(phase = VoicePhase.FINALIZING, level = 0f))
            }
            override fun failure(message: String, retryMillis: Long) {
                if (valid()) {
                    fail(message)
                    update(state.copy(retryAtMillis = now() + retryMillis))
                }
            }
            override fun repairRequired(message: String, retryMillis: Long, action: VoiceRecoveryAction) {
                if (!valid()) return
                fail(message)
                update(state.copy(retryAtMillis = now() + retryMillis, recoveryAction = action))
            }
            override fun retryableFailure(message: String, retryMillis: Long) {
                if (!valid()) return
                val retry = autoContinue && !state.muted && ++recognitionFailures <= 3
                fail(message)
                // Partial speech is user input, not a disposable failed attempt.
                // Keep it for explicit review instead of opening a fresh microphone.
                automaticRetry = retry && !draftPending
                update(state.copy(retryAtMillis = now() + maxOf(500, retryMillis)))
            }
            override fun level(value: Float) {
                if (valid()) update(state.copy(level = value.coerceIn(0f, 1f), hasAudioLevel = true))
            }
            override fun partial(text: String) {
                if (valid()) update(state.copy(transcript = text.take(8000)))
            }
            override fun final(text: String) {
                if (!valid()) return
                stopAudio()
                val prompt = text.trim().take(8000)
                if (prompt.isEmpty()) {
                    if (state.transcript.isNotBlank()) {
                        fail("Speech recognition ended without a final transcript. Review the captured text before sending.")
                        return
                    }
                    if (autoContinue && !state.muted && ++recognitionFailures <= 3) {
                        automaticRetry = true
                        update(state.copy(phase = VoicePhase.PAUSED, retryAtMillis = now() + maxOf(500, audio.retryAfterMillis())))
                    } else {
                        if (autoContinue && !state.muted) fail("No speech was detected after several attempts. Tap to try again.") else pause()
                    }
                    return
                }
                recognitionFailures = 0
                draftPending = true
                update(state.copy(phase = VoicePhase.REVIEWING, transcript = prompt, level = 0f))
                if (!editBeforeSend) sendDraft()
            }
            override fun error(message: String) {
                if (valid()) fail(message)
            }
        })
    }

    fun streamReply(id: String, text: String, complete: Boolean = false) {
        if (!waiting || state.phase == VoicePhase.CLOSED || (streamId != null && streamId != id)) return
        if (streamId == null) {
            streamId = id
            streamAudio = playbackAllowed() && state.speaker
        }
        update(state.copy(reply = text))
        streamComplete = complete
        val chunks = speechBuffer.append(text, complete)
        if (chunks == null) {
            stopAudio()
            update(state.copy(phase = VoicePhase.THINKING, notice = "The response was revised. The current answer is available in chat."))
        } else if (streamAudio) {
            queuedSpeech.addAll(chunks)
        }
        pumpSpeech()
    }
    private fun pumpSpeech() {
        if (chunkPlaying || state.phase == VoicePhase.CLOSED) return
        if (!streamAudio || !playbackAllowed() || !state.speaker) {
            queuedSpeech.clear()
            if (streamComplete) {
                waiting = false
                if (engaged) continueTurn() else pause()
            }
            return
        }
        val chunk = queuedSpeech.removeFirstOrNull()
        if (chunk == null) {
            if (streamComplete) {
                waiting = false
                continueTurn()
            } else {
                update(state.copy(phase = VoicePhase.THINKING))
            }
            return
        }
        chunkPlaying = true
        val ticket = generation
        val sequence = ++playbackSequence
        update(state.copy(phase = VoicePhase.SPEAKING))
        audio.speak(chunk, complete = {
            if (generation == ticket && sequence == playbackSequence && chunkPlaying) {
                chunkPlaying = false
                pumpSpeech()
            }
        }, error = { message -> if (generation == ticket && sequence == playbackSequence) fail(message) })
    }
    fun interrupt() {
        if (state.phase == VoicePhase.CLOSED) return
        waiting = false
        stopAudio()
        resetStream()
        update(state.copy(phase = VoicePhase.PAUSED))
        resume()
    }
    fun reply(id: String, text: String, failed: Boolean = false) {
        if (!waiting || id == lastReply || state.phase == VoicePhase.CLOSED) return
        if (streamId == id && !failed && text.isNotBlank()) {
            lastReply = id
            streamReply(id, text, complete = true)
            return
        }
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
            continueTurn()
            return
        }
        stopAudio()
        val ticket = generation
        update(state.copy(phase = VoicePhase.SPEAKING, level = 0f, notice = null))
        audio.speak(text, complete = {
            if (ticket == generation && playbackAllowed()) continueTurn()
        }, error = { message -> if (ticket == generation) fail(message) })
    }

    fun mute() {
        if (state.phase == VoicePhase.CLOSED) return
        automaticRetry = false
        recognitionFailures = 0
        update(state.copy(muted = !state.muted))
        if (state.phase == VoicePhase.SPEAKING || state.phase == VoicePhase.THINKING || state.phase == VoicePhase.REVIEWING) return
        if (state.muted) {
            stopAudio()
            update(state.copy(phase = VoicePhase.PAUSED, level = 0f))
        } else {
            resume()
        }
    }
    fun speaker() {
        if (state.phase == VoicePhase.CLOSED) return
        update(state.copy(speaker = !state.speaker))
        if (!state.speaker && state.phase == VoicePhase.SPEAKING) {
            stopAudio()
            continueTurn()
        }
    }
    fun pause() {
        if (state.phase == VoicePhase.CLOSED) return
        if (state.phase in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING) && state.transcript.isNotBlank()) draftPending = true
        engaged = false
        automaticRetry = false
        stopAudio()
        update(state.copy(phase = VoicePhase.PAUSED, level = 0f))
    }

    /** A deliberate engine change invalidates old recovery UI; each engine retains its own gate. */
    fun recognitionConfigurationChanged() {
        if (state.phase == VoicePhase.CLOSED) return
        pause()
        recognitionFailures = 0
        update(state.copy(retryAtMillis = 0, notice = null, recoveryAction = null))
    }
    fun fail(message: String) {
        if (state.phase == VoicePhase.CLOSED) return
        if (state.phase in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING) && state.transcript.isNotBlank()) draftPending = true
        engaged = false
        automaticRetry = false
        stopAudio()
        waiting = false
        update(state.copy(phase = VoicePhase.ERROR, level = 0f, notice = message, recoveryAction = null))
    }
    fun close() {
        if (state.phase == VoicePhase.CLOSED) return
        stopAudio()
        waiting = false
        draftPending = false
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
