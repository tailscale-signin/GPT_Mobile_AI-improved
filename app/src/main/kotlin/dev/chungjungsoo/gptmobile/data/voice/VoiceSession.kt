package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidents
import dev.chungjungsoo.gptmobile.data.diagnostics.ReportFeature

/** Main-thread input for one reply. stop() on the owning VoiceAudio cancels the stream. */
interface VoiceReplyPlayback {
    fun enqueue(text: String)
    fun finish()
}

/** Versioned boundary shared by installed local recognition and Android speech. */
interface VoiceAudio {
    interface Listener {
        fun ending() = Unit
        fun failure(message: String, retryMillis: Long) = error(message)
        fun retryableFailure(message: String, retryMillis: Long) = failure(message, retryMillis)
        fun repairRequired(message: String, retryMillis: Long, action: VoiceRecoveryAction) = failure(message, retryMillis)
        fun failure(
            message: String,
            code: String,
            retryMillis: Long = 0,
            retryable: Boolean = false,
            recoveryAction: VoiceRecoveryAction? = null,
            cause: Throwable? = null
        ) {
            when {
                recoveryAction != null -> repairRequired(message, retryMillis, recoveryAction)
                retryable -> retryableFailure(message, retryMillis)
                else -> failure(message, retryMillis)
            }
        }
        fun ready()
        fun level(value: Float)
        fun partial(text: String)
        fun final(text: String)
        fun error(message: String)
    }
    fun retryAfterMillis(): Long = 0
    fun listen(listener: Listener)
    fun speak(text: String, complete: () -> Unit, error: (String) -> Unit)
    val streamsReplies: Boolean get() = false
    fun openReply(complete: () -> Unit, error: (String) -> Unit): VoiceReplyPlayback? = null
    fun stop()

    /** Diagnostic origin accompanies intentional cancellation; generation completion is not a stop. */
    fun stop(reason: String) = stop()
    fun bindReply(id: String) = Unit
    fun bindDiagnostics(probe: VoiceDiagnosticTrace.Probe?) = Unit
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
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val awaitsAdmission: Boolean = false,
    private val diagnosticTrace: VoiceDiagnosticTrace = VoiceDiagnosticTrace(now)
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
    private var diagnosticProbe = diagnosticTrace.advance(generation)
    init {
        audio.bindDiagnostics(diagnosticProbe)
    }
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
    private var replyPlayback: VoiceReplyPlayback? = null
    private var playbackFinished = false
    private fun resetStream() {
        streamId = null
        speechBuffer = VoiceSpeechBuffer()
        queuedSpeech.clear()
        chunkPlaying = false
        streamComplete = false
        streamAudio = true
        replyPlayback = null
        playbackFinished = false
    }
    var editBeforeSend = false
    var autoContinue = false
    fun editDraft(text: String) {
        if (state.phase != VoicePhase.REVIEWING || waiting) return
        draftPending = true
        update(state.copy(transcript = text.take(8000)))
    }
    private fun continueTurn() {
        diagnosticTrace.retain()
        if (state.phase == VoicePhase.SPEAKING) update(state.copy(phase = VoicePhase.PAUSED))
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
        diagnosticProbe = diagnosticTrace.beginReply(generation)
        audio.bindDiagnostics(diagnosticProbe)
        waiting = true
        draftPending = awaitsAdmission
        update(state.copy(phase = VoicePhase.THINKING, transcript = prompt, level = 0f))
        if (!submit(prompt)) {
            draftPending = true
            fail("Could not send this turn. Open chat to review your profile and connection.")
        }
    }
    fun admission(stage: VoiceAdmissionStage, reason: String? = null) {
        if (!waiting || state.phase == VoicePhase.CLOSED) return
        when (stage) {
            VoiceAdmissionStage.CHECKING -> draftPending = true
            VoiceAdmissionStage.QUEUED, VoiceAdmissionStage.ACCEPTED -> draftPending = false
            VoiceAdmissionStage.FAILED -> {
                draftPending = true
                fail("This message was not accepted. Your words are kept; open chat to review the connection or retry. ${reason.orEmpty()}")
                return
            }
        }
        update(state)
    }

    fun permissionDenied() {
        diagnosticProbe.permission(false)
        fail("Microphone access is off. Allow it in Android app permissions to use voice chat.", "VOICE_MICROPHONE_PERMISSION_REQUIRED", failureFields = mapOf("failed_stage" to "voice_capture", "operation" to "voice_capture", "recovery_action" to "MICROPHONE_PERMISSION", "dispatched" to false))
        update(state.copy(recoveryAction = VoiceRecoveryAction.MICROPHONE_PERMISSION))
    }

    fun restore(transcript: String, reply: String, review: Boolean, pending: Boolean) {
        if (state.phase != VoicePhase.PAUSED || engaged) return
        waiting = pending
        draftPending = review
        update(state.copy(transcript = transcript, reply = reply, phase = if (review) VoicePhase.REVIEWING else VoicePhase.PAUSED))
    }
    fun replay() {
        if (state.reply.isBlank() || !accessAllowed() || waiting) return
        diagnosticProbe = diagnosticTrace.beginReply(generation)
        audio.bindDiagnostics(diagnosticProbe)
        waiting = true
        lastReply = null
        engaged = true
        reply("replay:$generation", state.reply)
    }
    private fun update(next: VoiceState, errorCode: String = "VOICE_SESSION_FAILED", error: Throwable? = null, reportFailure: Boolean = true, failureFields: Map<String, Any?> = emptyMap()) {
        // Silence is a normal listening outcome. Preserve its cooldown and retry
        // guidance without prompting for a bug report after every quiet turn.
        // A lost partial transcript or a real underlying exception still reports.
        val expectedSilence = errorCode == "VOICE_NO_SPEECH" && error == null && next.transcript.isBlank()
        if (reportFailure && !expectedSilence && next.phase == VoicePhase.ERROR && state.phase != VoicePhase.ERROR) {
            IssueReportIncidents.failure(ReportFeature.VOICE, errorCode, error, voiceFailureFields() + failureFields, "VOICE_STATE")
        }
        state = next.copy(awaitingReply = waiting, hasDraft = draftPending)
        diagnosticProbe.phase(state.phase)
        mutableState.value = state
        changed(state)
    }
    private fun voiceFailureFields(): Map<String, Any?> {
        val operation = when (state.phase) {
            VoicePhase.STARTING -> "voice_capture"
            VoicePhase.LISTENING, VoicePhase.FINALIZING -> "voice_recognition"
            VoicePhase.SPEAKING -> "voice_playback"
            else -> null
        }
        return diagnosticTrace.incidentFields() + mapOf("failed_stage" to operation, "operation" to operation)
    }
    private fun replyFailureFields(id: String): Map<String, Any?> = mapOf(
        "run_id" to id,
        // Terminal prose is not evidence of a microphone fault or provider dispatch.
        "operation" to null,
        "failed_stage" to "delivery"
    )
    private fun stopAudio(reason: String = "session_reset") {
        generation++
        diagnosticProbe = diagnosticTrace.advance(generation)
        queuedSpeech.clear()
        chunkPlaying = false
        replyPlayback = null
        playbackFinished = false
        if (streamId != null) streamAudio = false
        audio.stop(reason)
        audio.bindDiagnostics(diagnosticProbe)
    }
    private fun accessAllowed() = enabled && permission && foreground && state.phase != VoicePhase.CLOSED
    private fun playbackAllowed() = engaged && accessAllowed()
    private fun captureAllowed() = playbackAllowed() && !state.muted

    fun access(enabled: Boolean, permission: Boolean, foreground: Boolean) {
        this.enabled = enabled
        this.permission = permission
        this.foreground = foreground
        diagnosticProbe.access(permission, foreground, state.muted)
        if (!accessAllowed() && state.phase != VoicePhase.CLOSED) pause()
    }

    fun resume() {
        if (!accessAllowed() || state.phase in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING, VoicePhase.REVIEWING, VoicePhase.SPEAKING)) return
        if (waiting) {
            engaged = true
            update(state.copy(phase = VoicePhase.THINKING, level = 0f))
            return
        }
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
            // A blocked retry did not start another recognition attempt. Keep the
            // original repair guidance without inventing a generic failure report.
            update(
                state.copy(phase = VoicePhase.ERROR, retryAtMillis = now() + remaining, notice = state.notice ?: "Speech service is busy. Your chat is still available."),
                reportFailure = false
            )
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
        diagnosticTrace.retain()
        diagnosticProbe = diagnosticTrace.advance(generation, reset = true)
        diagnosticProbe.access(permission, foreground, state.muted)
        audio.bindDiagnostics(diagnosticProbe)
        update(state.copy(phase = VoicePhase.STARTING, transcript = "", level = 0f, hasAudioLevel = false, notice = null, retryAtMillis = 0, recoveryAction = null))
        fun valid() = ticket == generation && captureAllowed() && state.phase in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING)
        fun recognitionFailed(
            message: String,
            retryMillis: Long,
            retryable: Boolean = false,
            recoveryAction: VoiceRecoveryAction? = null,
            code: String = "VOICE_SESSION_FAILED",
            cause: Throwable? = null
        ) {
            if (!valid()) return
            val retry = retryable && recoveryAction == null && autoContinue && !state.muted && ++recognitionFailures <= 3
            val captureFailure = cause as? LocalVoiceCaptureException
            fail(
                message,
                if (code == "VOICE_SESSION_FAILED" && captureFailure != null) "VOICE_${captureFailure.code}" else code,
                cause,
                mapOf(
                    "failed_stage" to (captureFailure?.phase ?: "voice_recognition"),
                    "operation" to if (captureFailure != null) "voice_capture" else "voice_recognition",
                    "retry_after_ms" to retryMillis,
                    "retryable" to retryable,
                    "recovery_action" to recoveryAction?.name
                )
            )
            // Partial speech is user input, not a disposable failed attempt.
            automaticRetry = retry && !draftPending
            update(state.copy(retryAtMillis = now() + if (retryable) maxOf(500, retryMillis) else retryMillis, recoveryAction = recoveryAction))
        }
        audio.listen(object : VoiceAudio.Listener {
            override fun ready() {
                if (valid()) update(state.copy(phase = VoicePhase.LISTENING))
            }
            override fun ending() {
                if (valid()) update(state.copy(phase = VoicePhase.FINALIZING, level = 0f))
            }
            override fun failure(message: String, retryMillis: Long) {
                recognitionFailed(message, retryMillis)
            }
            override fun repairRequired(message: String, retryMillis: Long, action: VoiceRecoveryAction) {
                recognitionFailed(message, retryMillis, recoveryAction = action)
            }
            override fun retryableFailure(message: String, retryMillis: Long) {
                recognitionFailed(message, retryMillis, retryable = true)
            }
            override fun failure(message: String, code: String, retryMillis: Long, retryable: Boolean, recoveryAction: VoiceRecoveryAction?, cause: Throwable?) {
                recognitionFailed(message, retryMillis, retryable, recoveryAction, code, cause)
            }
            override fun level(value: Float) {
                if (valid()) update(state.copy(level = value.coerceIn(0f, 1f), hasAudioLevel = true))
            }
            override fun partial(text: String) {
                if (valid()) {
                    diagnosticProbe.recognized(text.length)
                    update(state.copy(transcript = text.take(8000)))
                }
            }
            override fun final(text: String) {
                if (!valid()) return
                diagnosticProbe.recognized(text.length)
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
                        if (autoContinue && !state.muted) fail("No speech was detected after several attempts. Tap to try again.", "VOICE_NO_SPEECH") else pause()
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
        if (text.isNotBlank()) diagnosticProbe.responseText()
        if (streamId == null) {
            streamId = id
            audio.bindReply(id)
            streamAudio = playbackAllowed() && state.speaker
        }
        update(state.copy(reply = text))
        streamComplete = complete
        // Android can speak a stable first phrase without waiting for a complete sentence.
        val chunks = speechBuffer.append(text, complete, earlyStart = true)
        if (chunks == null) {
            stopAudio("response_revised")
            update(state.copy(phase = VoicePhase.THINKING, notice = "The response was revised. The current answer is available in chat."))
        } else if (streamAudio) {
            if (chunks.isNotEmpty()) diagnosticProbe.queued()
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
        if (replyPlayback == null && queuedSpeech.isNotEmpty() && audio.streamsReplies) {
            val ticket = generation
            val opened = audio.openReply(
                complete = {
                    if (ticket == generation && replyPlayback != null) {
                        replyPlayback = null
                        waiting = false
                        continueTurn()
                    }
                },
                error = { message -> if (ticket == generation) playbackFailed(message) }
            )
            if (ticket != generation) return
            replyPlayback = opened
            if (replyPlayback != null) update(state.copy(phase = VoicePhase.SPEAKING))
        }
        replyPlayback?.let { playback ->
            while (replyPlayback === playback) playback.enqueue(queuedSpeech.removeFirstOrNull() ?: break)
            if (replyPlayback === playback && streamComplete && !playbackFinished) {
                playbackFinished = true
                playback.finish()
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
        }, error = { message -> if (generation == ticket && sequence == playbackSequence) playbackFailed(message) })
    }
    private fun playbackFailed(message: String) {
        // Audio can fail while the model is still streaming. Keep its turn and captions alive.
        val diagnosticFields = diagnosticTrace.incidentFields()
        stopAudio()
        streamAudio = false
        engaged = false
        automaticRetry = false
        if (streamComplete) waiting = false
        if (waiting) {
            IssueReportIncidents.failure(ReportFeature.VOICE, "VOICE_PLAYBACK_FAILED", null, diagnosticFields + mapOf("failed_stage" to "voice_playback", "operation" to "voice_playback"), "VOICE_STATE")
        }
        update(state.copy(phase = if (waiting) VoicePhase.THINKING else VoicePhase.ERROR, notice = message, level = 0f), "VOICE_PLAYBACK_FAILED", failureFields = diagnosticFields)
    }

    fun stopSpeaking() {
        if (state.phase != VoicePhase.SPEAKING) return
        stopAudio("user_stop_speaking")
        streamAudio = false
        if (waiting && !streamComplete) {
            update(state.copy(phase = VoicePhase.THINKING, level = 0f))
        } else {
            waiting = false
            update(state.copy(phase = VoicePhase.PAUSED, level = 0f))
            continueTurn()
        }
    }

    fun interrupt() {
        if (state.phase == VoicePhase.CLOSED) return
        waiting = false
        stopAudio("user_barge_in")
        resetStream()
        update(state.copy(phase = VoicePhase.PAUSED))
        resume()
    }
    fun reply(id: String, text: String, failed: Boolean = false, failure: ProfileFailureRecord? = null) {
        if (!waiting || id == lastReply || state.phase == VoicePhase.CLOSED) return
        if (streamId == id && !failed && text.isNotBlank()) {
            lastReply = id
            streamReply(id, text, complete = true)
            return
        }
        if (streamId == null && !failed && text.isNotBlank() && audio.streamsReplies) {
            lastReply = id
            streamReply(id, text, complete = true)
            return
        }
        waiting = false
        lastReply = id
        if (failed || text.isBlank()) {
            draftPending = state.transcript.isNotBlank()
            if (text.isNotBlank()) update(state.copy(reply = text))
            if (failed && failure != null) {
                // The owning chat run already retains/reports the typed failure.
                // Keep recognized words for explicit review without inventing a
                // second microphone/session incident or automatically resending.
                fail(
                    when {
                        failure.category == "PRIVACY_CONFIGURATION" -> "This AI profile does not satisfy the selected privacy settings. Your words are kept. Choose a compatible profile in Voice settings or review it in chat."
                        !failure.retryAllowed || failure.requestReplayAllowed == false -> "This AI turn needs review before it can continue. Your words are kept. Open chat to inspect the original failure and saved outcome before sending again."
                        else -> "This AI turn did not finish. Your words are kept. Open chat to review the original failure and recovery options."
                    },
                    code = failure.category,
                    reportFailure = false
                )
                return
            }
            fail(
                "This turn did not finish. Your words are kept. Open chat to review it or try again.",
                failureFields = replyFailureFields(id)
            )
            return
        }
        diagnosticProbe.responseText()
        update(state.copy(reply = text))
        if (!playbackAllowed()) {
            pause()
            return
        }
        if (!state.speaker) {
            continueTurn()
            return
        }
        val spoken = VoiceSpeechBuffer().append(text, final = true).orEmpty().joinToString(" ").trim()
        if (spoken.isEmpty()) {
            continueTurn()
            return
        }
        stopAudio()
        val ticket = generation
        update(state.copy(phase = VoicePhase.SPEAKING, level = 0f, notice = null))
        diagnosticProbe.queued()
        audio.bindReply(id)
        audio.speak(spoken, complete = {
            if (ticket == generation && playbackAllowed()) continueTurn()
        }, error = { message -> if (ticket == generation) fail(message, "VOICE_PLAYBACK_FAILED") })
    }

    fun mute() {
        if (state.phase == VoicePhase.CLOSED) return
        automaticRetry = false
        recognitionFailures = 0
        update(state.copy(muted = !state.muted))
        diagnosticProbe.muted(state.muted)
        if (state.phase == VoicePhase.SPEAKING || state.phase == VoicePhase.THINKING || state.phase == VoicePhase.REVIEWING) return
        if (state.muted) {
            pause()
        } else {
            resume()
        }
    }
    fun speaker() {
        if (state.phase == VoicePhase.CLOSED) return
        update(state.copy(speaker = !state.speaker))
        if (!state.speaker) stopSpeaking()
    }
    fun pause() {
        if (state.phase == VoicePhase.CLOSED) return
        if (state.phase in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING) && state.transcript.isNotBlank()) draftPending = true
        engaged = false
        automaticRetry = false
        stopAudio("session_paused")
        update(state.copy(phase = VoicePhase.PAUSED, level = 0f))
    }

    /** A deliberate engine change invalidates old recovery UI; each engine retains its own gate. */
    fun recognitionConfigurationChanged() {
        if (state.phase == VoicePhase.CLOSED) return
        pause()
        recognitionFailures = 0
        update(state.copy(retryAtMillis = 0, notice = null, recoveryAction = null))
    }
    fun fail(message: String, code: String = "VOICE_SESSION_FAILED", cause: Throwable? = null, failureFields: Map<String, Any?> = emptyMap(), reportFailure: Boolean = true) {
        if (state.phase == VoicePhase.CLOSED) return
        val diagnosticFields = diagnosticTrace.incidentFields()
        if (state.phase in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING) && state.transcript.isNotBlank()) draftPending = true
        engaged = false
        automaticRetry = false
        stopAudio()
        waiting = false
        update(state.copy(phase = VoicePhase.ERROR, level = 0f, notice = message, recoveryAction = null), code, cause, reportFailure = reportFailure, failureFields = diagnosticFields + failureFields)
    }
    fun close() {
        if (state.phase == VoicePhase.CLOSED) return
        stopAudio("session_closed")
        waiting = false
        draftPending = false
        audio.close()
        update(state.copy(phase = VoicePhase.CLOSED, level = 0f))
        diagnosticTrace.close()
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
