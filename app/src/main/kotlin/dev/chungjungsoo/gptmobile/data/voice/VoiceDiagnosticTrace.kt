package dev.chungjungsoo.gptmobile.data.voice

import android.media.AudioManager
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidents
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import dev.chungjungsoo.gptmobile.data.diagnostics.ReportDiagnosticSnapshots
import dev.chungjungsoo.gptmobile.data.diagnostics.ReportFeature
import dev.chungjungsoo.gptmobile.data.diagnostics.VoiceExecutionDiagnostics
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Finite observations only. Reply identifiers are hashed by the closed reporting projection before retention. */
class VoiceDiagnosticTrace internal constructor(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val allowed: () -> Boolean = { LocalDiagnosticsPolicy.enabled && !AppLogRecorder.privateSessionActive && !IssueReportIncidents.privateSession.value },
    private val retainSnapshot: (IssueReportDiagnostics) -> Unit = ReportDiagnosticSnapshots::retain
) {
    enum class Engine { SYSTEM, LOCAL, KOKORO, MEDIAPIPE }
    enum class Focus { GRANTED, DENIED, ERROR, NOT_REQUESTED }
    enum class PlaybackAudioMode {
        NORMAL,
        RINGTONE,
        IN_CALL,
        IN_COMMUNICATION,
        OTHER;

        companion object {
            fun observed(mode: Int?): PlaybackAudioMode? = when (mode) {
                null -> null
                AudioManager.MODE_NORMAL -> NORMAL
                AudioManager.MODE_RINGTONE -> RINGTONE
                AudioManager.MODE_IN_CALL -> IN_CALL
                AudioManager.MODE_IN_COMMUNICATION -> IN_COMMUNICATION
                else -> OTHER
            }
        }
    }
    enum class FirstAudio { UTTERANCE_START, PLAYBACK_HEAD, AUDIO_TIMESTAMP }

    private val lock = Any()
    private val sessionHash = MessageDigest.getInstance("SHA-256")
        .digest("gptmobile.voice-diagnostic.session.v1:${UUID.randomUUID()}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val performance = VoicePerformanceHistory()
    private val values = linkedMapOf<String, Any?>()
    private val moments = mutableMapOf<String, Long>()
    private var speechProvider: String? = null
    private var capturedAt = 0L
    private var closed = false
    private var probe = Probe(this, 0, privacyEpoch.get(), allowed())

    init {
        latest.set(this)
    }

    /** A new capture resets the turn; cancellation only fences callbacks for the current turn. */
    internal fun advance(generation: Long, reset: Boolean = false, newOrigin: Boolean = false): Probe = synchronized(lock) {
        if ((reset || newOrigin) && !closed && allowed()) {
            latest.compareAndSet(null, this)
        }
        if (reset || probe.epoch != privacyEpoch.get()) {
            values.clear()
            moments.clear()
            speechProvider = null
            capturedAt = 0
        }
        // Cancellation fences callbacks without making an old/private operation public.
        // Only an explicit reply or new capture acquires fresh diagnostic authority.
        val renew = reset || newOrigin
        Probe(this, generation.coerceAtLeast(0), if (renew) privacyEpoch.get() else probe.epoch, if (renew) allowed() else probe.collectable).also { probe = it }
    }

    internal fun incidentFields(): Map<String, Any?> = fields("INCIDENT")?.first.orEmpty()

    /** Explicit retries/replay are new playback measurements, even when the draft is retained. */
    internal fun beginReply(generation: Long): Probe = synchronized(lock) {
        speechProvider = null
        values.keys.removeAll { it in IssueReportIncidentCapsule.fieldsFor("VOICE") || it in setOf("voice_reply_state", "voice_reply_characters") }
        moments.keys.retainAll(setOf("speech_end", "final_transcript"))
        values.keys.removeAll { it.endsWith("_ms") && it != "voice_speech_end_to_transcript_ms" || it in setOf("voice_engine_warm", "voice_playback_audio_mode", "voice_first_audio_basis", "voice_playback_failure", "voice_playback_focus_attempts", "voice_caption_alignment") }
        advance(generation, newOrigin = true)
    }

    internal fun retain() {
        val epoch = privacyEpoch.get()
        val snapshot = snapshot() ?: return
        if (latest.get() === this && epoch == privacyEpoch.get() && allowed()) retainSnapshot(snapshot)
    }

    internal fun close() {
        retain()
        clear(dispose = true)
        latest.compareAndSet(this, null)
    }

    private fun clear(dispose: Boolean = false) = synchronized(lock) {
        values.clear()
        moments.clear()
        speechProvider = null
        capturedAt = 0
        if (dispose) closed = true
        performance.clear()
    }

    private fun fields(mode: String): Pair<Map<String, Any?>, Long>? = synchronized(lock) {
        if (closed || !probe.collectable || !allowed() || probe.epoch != privacyEpoch.get() || capturedAt <= 0 || values.isEmpty()) return@synchronized null
        val age = wallClock() - capturedAt
        if (age !in 0..MAX_AGE_MS) return@synchronized null
        (
            values + mapOf(
                "diagnostic_area" to "VOICE",
                "diagnostic_capture_mode" to mode,
                "voice_session_id_hash" to sessionHash,
                "voice_generation" to probe.generation
            )
            ) to capturedAt
    }

    private fun snapshot(): IssueReportDiagnostics? {
        val (fields, capturedAt) = fields("SELECTED_CAPTURE") ?: return null
        return IssueReportDiagnostics.fromFields("VOICE_STATE", fields, capturedAt)
    }

    private fun record(ticket: Probe, action: () -> Unit) = synchronized(lock) {
        if (!accepts(ticket)) return@synchronized
        action()
        capturedAt = wallClock()
    }

    private fun accepts(ticket: Probe): Boolean = !closed && ticket.collectable && ticket === probe && ticket.epoch == privacyEpoch.get() && allowed()

    private fun elapsed(field: String, start: String, end: String) {
        val from = moments[start] ?: return
        val to = moments[end] ?: return
        if (to >= from) {
            val duration = (to - from).coerceAtMost(MAX_DURATION_MS)
            if (values[field] != duration) {
                val metric = when (field) {
                    "voice_capture_ready_ms" -> "capture_ready"
                    "voice_acoustic_end_to_transcript_ms" -> "speech_end"
                    "voice_transcript_to_request_ms" -> "final_to_request"
                    "voice_request_to_response_ms" -> "request_to_text"
                    "voice_response_to_queue_ms" -> "text_to_queue"
                    "voice_warmup_ms" -> "warmup"
                    "voice_queue_to_synthesis_ms" -> "queue_to_synthesis"
                    "voice_synthesis_to_first_audio_ms" -> "synthesis_to_audio"
                    "voice_response_to_first_audio_ms" -> "text_to_audio"
                    else -> null
                }
                if (metric != null) performance.observe(metric, duration, performanceEngine(metric), values["voice_engine_warm"] as? Boolean)
            }
            values[field] = duration
        }
    }

    private fun performanceEngine(metric: String): String = when (metric) {
        "final_to_request", "request_to_text", "text_to_queue" -> "SESSION"
        else -> if (values["voice_engine"] in setOf(Engine.KOKORO.name, Engine.MEDIAPIPE.name)) speechProvider ?: values["voice_engine"] as String else values["voice_engine"] as? String ?: "SESSION"
    }

    /** Backends retain this immutable ticket inside their capture/playback operation. */
    class Probe internal constructor(private val owner: VoiceDiagnosticTrace, internal val generation: Long, internal val epoch: Long, internal val collectable: Boolean) {
        internal fun reportFailure(code: String, error: Throwable?, fields: Map<String, Any?>) {
            // Incidents takes its privacy lock before this Trace lock, as privacyChanged
            // already does. Never invoke the collector while holding the Trace lock.
            IssueReportIncidents.failureFromOrigin(ReportFeature.VOICE, code, error, fields, "VOICE_STATE") {
                synchronized(owner.lock) { owner.accepts(this) }
            }
        }

        /** Keep a terminal run join through pause/close, but clear it before another capture or reply. */
        internal fun replyFailure(fields: Map<String, Any?>) {
            val snapshot = IssueReportContext.fromFields(
                "VOICE_STATE",
                IssueReportIncidentCapsule.fields(ReportFeature.VOICE, "VOICE_STATE", "VOICE_SESSION_FAILED", fields + ("diagnostic_area" to "VOICE"))
            ).diagnostics ?: return
            owner.record(this) {
                snapshot.values.filterKeys { it in IssueReportIncidentCapsule.fieldsFor("VOICE") || it in setOf("voice_reply_state", "voice_reply_characters") }.forEach { (key, value) -> owner.values[key] = value }
            }
        }
        fun engine(engine: Engine) = owner.record(this) {
            val previous = owner.values["voice_engine"]
            if (previous != null && previous != engine.name) {
                owner.speechProvider = null
                listOf("warmup", "ready", "synthesis", "pcm", "audio").forEach(owner.moments::remove)
                listOf("voice_warmup_ms", "voice_engine_warm", "voice_playback_audio_mode", "voice_queue_to_synthesis_ms", "voice_synthesis_to_first_audio_ms", "voice_response_to_first_audio_ms", "voice_first_audio_basis", "voice_playback_failure", "voice_playback_focus_attempts", "voice_focus_last_transition", "voice_focus_transition_count", "voice_pronunciation_unknown_words", "voice_pronunciation_unknown_word_length", "voice_pronunciation_initialisms", "voice_pronunciation_failure_kind", "voice_pronunciation_audio_queued", "voice_pronunciation_first_audio_observed").forEach(owner.values::remove)
            }
            owner.values["voice_engine"] = engine.name
            if (engine == Engine.SYSTEM) owner.values["voice_effective_provider"] = "SYSTEM"
            if (engine != Engine.SYSTEM) owner.values.remove("voice_effective_provider")
        }

        /** Engine identity can be observed even when the SDK supplies no processor-execution proof. */
        fun speechProvider(raw: String) = owner.record(this) {
            val provider = when (raw) {
                "cpu" -> "KOKORO_CPU"
                "qnn-gpu" -> "QNN_GPU"
                "qnn-htp" -> "QNN_HTP"
                "gateway-cuda" -> "GATEWAY_CUDA"
                else -> null
            }
            val engine = provider ?: when (raw) {
                "mediapipe-kokoro-cpu-requested", "mediapipe-kokoro-gpu-requested", "mediapipe-kokoro-npu-requested" -> "MEDIAPIPE_KOKORO"
                "mediapipe-phonon-cpu-requested" -> "MEDIAPIPE_PHONON"
                "mediapipe-pocket-cpu-requested" -> "MEDIAPIPE_POCKET"
                else -> null
            }
            if (owner.speechProvider != null && engine != null && owner.speechProvider != engine) {
                owner.values["voice_engine_warm"] = false
            }
            owner.speechProvider = engine
            if (provider != null) owner.values["voice_effective_provider"] = provider else owner.values.remove("voice_effective_provider")
        }

        fun selectedSpeechBackend(selected: String) = owner.record(this) {
            if (selected in VoiceExecutionDiagnostics.enums.getValue("voice_selected_backend")) owner.values["voice_selected_backend"] = selected
        }

        /** A recovery receipt keeps the failed producer linked to the same reply after engine changes. */
        fun playbackFallback(failure: VoicePlaybackFailure, outcome: String, target: String = "SYSTEM") = owner.record(this) {
            if (outcome !in setOf("STARTED", "SUCCEEDED", "FAILED") || target !in setOf("SYSTEM", "KOKORO_CPU")) return@record
            val source = when (failure.sourceProvider) {
                "cpu" -> "KOKORO_CPU"
                "qnn-gpu" -> "QNN_GPU"
                "qnn-htp" -> "QNN_HTP"
                "gateway-cuda" -> "GATEWAY_CUDA"
                "mediapipe-kokoro-cpu-requested", "mediapipe-kokoro-gpu-requested", "mediapipe-kokoro-npu-requested" -> "LITERT_KOKORO"
                "mediapipe-phonon-cpu-requested" -> "PHONON"
                "mediapipe-pocket-cpu-requested" -> "POCKET"
                else -> "KOKORO"
            }
            owner.values["voice_fallback_source"] = source
            owner.values["voice_fallback_target"] = target
            owner.values["voice_fallback_outcome"] = outcome
            owner.values["voice_fallback_failure"] = failure.code?.takeIf { it in VoiceExecutionDiagnostics.playbackFailures } ?: "ENGINE_FAILED"
            owner.values["voice_fallback_source_observation"] = if (failure.sourceProvider == null) "NOT_INITIALIZED" else "INITIALIZED"
            recordPlaybackCauses(failure)
            if (outcome == "STARTED") owner.values["voice_fallback_count"] = ((owner.values["voice_fallback_count"] as? Long ?: 0L) + 1).coerceAtMost(100)
        }

        fun playbackFailure(failure: VoicePlaybackFailure) = owner.record(this) {
            failure.code?.takeIf { it in VoiceExecutionDiagnostics.playbackFailures }?.let { owner.values["voice_playback_failure"] = it }
            recordPlaybackCauses(failure)
        }

        private fun recordPlaybackCauses(failure: VoicePlaybackFailure) {
            VoiceExecutionDiagnostics.fallbackCauseFields.filterNot { it == "voice_fallback_source_observation" }.forEach(owner.values::remove)
            failure.requestedBackend?.takeIf { it in VoiceExecutionDiagnostics.enums.getValue("voice_failure_requested_backend") }
                ?.let { owner.values["voice_failure_requested_backend"] = it }
            failure.stage?.let { owner.values["voice_primary_failure_stage"] = it.name }
            failure.cause?.let(IssueReportContext::exceptionFingerprint)?.let { owner.values["voice_primary_cause_fingerprint"] = it }
            failure.terminalFallback?.let { fallback ->
                fallback.code?.takeIf { it in VoiceExecutionDiagnostics.playbackFailures }?.let { owner.values["voice_fallback_terminal_failure"] = it }
                fallback.stage?.let { owner.values["voice_fallback_failure_stage"] = it.name }
                fallback.cause?.let(IssueReportContext::exceptionFingerprint)?.let { owner.values["voice_fallback_cause_fingerprint"] = it }
            }
            (failure.terminalFallback ?: failure).platformErrorCode?.takeIf { it in -9..-1 }?.let { owner.values["voice_system_error_code"] = it }
        }
        fun phase(phase: VoicePhase) = owner.record(this) { owner.values["voice_phase"] = phase.name }
        fun access(permission: Boolean, foreground: Boolean, muted: Boolean) = owner.record(this) {
            owner.values["voice_permission_granted"] = permission
            owner.values["voice_foreground"] = foreground
            owner.values["voice_muted"] = muted
        }
        fun permission(granted: Boolean) = owner.record(this) { owner.values["voice_permission_granted"] = granted }
        fun muted(muted: Boolean) = owner.record(this) { owner.values["voice_muted"] = muted }
        fun focus(result: Focus) = owner.record(this) { owner.values["voice_focus_result"] = result.name }
        fun playbackFocus(result: Focus?, attempt: Int, audioMode: PlaybackAudioMode? = null) = owner.record(this) {
            if (audioMode == null) owner.values.remove("voice_playback_audio_mode") else owner.values["voice_playback_audio_mode"] = audioMode.name
            if (result == null) owner.values.remove("voice_focus_result") else owner.values["voice_focus_result"] = result.name
            owner.values["voice_playback_focus_attempts"] = attempt
        }

        /** Counts and already-audible state only; no unsupported word or reply text leaves the device. */
        internal fun pronunciationFailure(unknownWords: Int, initialisms: Int, audioQueued: Boolean, firstAudioObserved: Boolean, failure: KokoroPronunciationFailure? = null, unknownWordLength: Int? = null) = owner.record(this) {
            owner.values.remove("voice_pronunciation_unknown_word_length")
            if (failure == KokoroPronunciationFailure.UNKNOWN_WORD && unknownWordLength != null && unknownWordLength > 0) {
                owner.values["voice_pronunciation_unknown_word_length"] = unknownWordLength.toLong().coerceAtMost(256L)
            }
            owner.values["voice_pronunciation_unknown_words"] = unknownWords.toLong().coerceIn(0L, MAX_COUNT)
            owner.values["voice_pronunciation_initialisms"] = initialisms.toLong().coerceIn(0L, MAX_COUNT)
            failure?.let { owner.values["voice_pronunciation_failure_kind"] = it.name }
            owner.values["voice_pronunciation_audio_queued"] = audioQueued
            owner.values["voice_pronunciation_first_audio_observed"] = firstAudioObserved
        }

        /** A transient focus interruption is not the same event as permanent loss. */
        fun playbackFocusTransition(action: String) = owner.record(this) {
            if (action in setOf("RESTORE", "PAUSE", "DUCK", "STOP")) {
                owner.values["voice_focus_last_transition"] = action
                owner.values["voice_focus_transition_count"] =
                    ((owner.values["voice_focus_transition_count"] as? Long ?: 0L) + 1L).coerceAtMost(MAX_COUNT)
            }
        }
        fun playbackFailure(reason: String) = owner.record(this) { owner.values["voice_playback_failure"] = reason }
        fun captureSilenced(stage: String, systemMuted: Boolean? = null, mode: PlaybackAudioMode? = null) = owner.record(this) {
            owner.values["voice_capture_silenced_stage"] = stage
            if (systemMuted == null) owner.values.remove("voice_silenced_system_mic_muted") else owner.values["voice_silenced_system_mic_muted"] = systemMuted
            if (mode == null) owner.values.remove("voice_silenced_audio_mode") else owner.values["voice_silenced_audio_mode"] = mode.name
        }
        fun captionAlignment(state: String) = owner.record(this) { owner.values["voice_caption_alignment"] = state }
        fun inputSilenced(silenced: Boolean) = owner.record(this) { owner.values["voice_input_silenced"] = silenced }
        fun captureAttempt() = owner.record(this) {
            listOf("voice_capture_silenced_stage", "voice_silenced_system_mic_muted", "voice_silenced_audio_mode", "voice_input_silenced").forEach(owner.values::remove)
            owner.values["voice_capture_attempts"] = ((owner.values["voice_capture_attempts"] as? Long ?: 0) + 1).coerceAtMost(MAX_COUNT)
        }
        fun captureReadsAvailable() = owner.record(this) {
            listOf("voice_read_calls", "voice_captured_samples", "voice_nonzero_samples").forEach { owner.values.putIfAbsent(it, 0L) }
        }
        fun read(samples: Int, nonzeroSamples: Int) = owner.record(this) {
            owner.values["voice_read_calls"] = ((owner.values["voice_read_calls"] as? Long ?: 0) + 1).coerceAtMost(MAX_COUNT)
            owner.values["voice_captured_samples"] = ((owner.values["voice_captured_samples"] as? Long ?: 0) + samples.coerceAtLeast(0)).coerceAtMost(MAX_COUNT)
            owner.values["voice_nonzero_samples"] = ((owner.values["voice_nonzero_samples"] as? Long ?: 0) + nonzeroSamples.coerceIn(0, samples.coerceAtLeast(0))).coerceAtMost(MAX_COUNT)
        }
        fun recognized(characters: Int) = owner.record(this) {
            owner.values["voice_recognized_characters"] = maxOf(owner.values["voice_recognized_characters"] as? Long ?: 0, characters.toLong().coerceIn(0, MAX_COUNT))
        }
        fun captureRequested() = owner.record(this) { owner.moments.putIfAbsent("capture_requested", owner.now()) }
        fun microphoneReady() = owner.record(this) {
            owner.moments.putIfAbsent("microphone_ready", owner.now())
            owner.elapsed("voice_capture_ready_ms", "capture_requested", "microphone_ready")
        }
        fun preparation(stage: String, durationMs: Long) = owner.record(this) {
            val metric = when (stage) {
                "GATE_WAIT" -> "gate_wait"
                "VERIFICATION" -> "verification"
                "MODEL_LOAD" -> "model_load"
                "AUDIO_TRACK_SETUP" -> "audio_track_setup"
                else -> null
            }
            if (metric != null) owner.performance.observe(metric, durationMs, owner.performanceEngine(metric), owner.values["voice_engine_warm"] as? Boolean)
        }

        /** Audio-timeline VAD boundary, including detector smoothing; not a laboratory last-phoneme measurement. */
        fun acousticSpeechEnded(elapsedAgoMs: Long = 0) = owner.record(this) { owner.moments["acoustic_speech_end"] = owner.now() - elapsedAgoMs.coerceIn(0, 60_000) }
        fun captureQueue(ageMs: Long) = owner.record(this) { owner.performance.queueAge(ageMs) }
        fun speechEnded() = owner.record(this) { owner.moments.putIfAbsent("speech_end", owner.now()) }
        fun finalTranscript() = owner.record(this) {
            owner.moments.putIfAbsent("final_transcript", owner.now())
            owner.elapsed("voice_speech_end_to_transcript_ms", "speech_end", "final_transcript")
            owner.elapsed("voice_acoustic_end_to_transcript_ms", "acoustic_speech_end", "final_transcript")
        }
        fun requestAccepted() = owner.record(this) {
            owner.moments.putIfAbsent("accepted", owner.now())
            owner.elapsed("voice_transcript_to_request_ms", "final_transcript", "accepted")
        }
        fun responseText() = owner.record(this) {
            owner.moments.putIfAbsent("response", owner.now())
            owner.elapsed("voice_request_to_response_ms", "accepted", "response")
        }
        fun publishableSpeech() = owner.record(this) {
            if (owner.moments.putIfAbsent("publishable", owner.now()) == null) {
                owner.moments["response"]?.let { first -> owner.performance.observe("text_to_publishable", owner.now() - first) }
            }
        }
        fun queued() = owner.record(this) {
            if (owner.moments.putIfAbsent("queue", owner.now()) == null) {
                owner.moments["publishable"]?.let { first -> owner.performance.observe("publishable_to_queue", owner.now() - first) }
            }
            owner.elapsed("voice_response_to_queue_ms", "response", "queue")
        }
        fun warmupStarted() = owner.record(this) { owner.moments.putIfAbsent("warmup", owner.now()) }
        fun engineReady(warm: Boolean) = owner.record(this) {
            if (owner.moments.putIfAbsent("ready", owner.now()) == null) {
                owner.values["voice_engine_warm"] = warm
                owner.elapsed("voice_warmup_ms", "warmup", "ready")
            }
        }
        fun synthesisStarted() = owner.record(this) {
            owner.moments.putIfAbsent("synthesis", owner.now())
            owner.elapsed("voice_queue_to_synthesis_ms", "queue", "synthesis")
        }
        fun firstPcm() = owner.record(this) {
            if (owner.moments.putIfAbsent("pcm", owner.now()) == null) {
                owner.moments["queue"]?.let { first -> owner.performance.observe("queue_to_pcm", owner.now() - first, owner.performanceEngine("synthesis_to_audio"), owner.values["voice_engine_warm"] as? Boolean) }
            }
        }
        internal fun openingAudioStarted(lookup: Boolean) = owner.record(this) {
            owner.moments["accepted"]?.let { accepted ->
                val elapsed = owner.now() - accepted
                owner.performance.observe("request_to_cue_audio", elapsed)
                if (lookup) owner.performance.observe("request_to_lookup_audio", elapsed)
            }
        }

        fun firstAudio(basis: FirstAudio) = owner.record(this) {
            val first = owner.moments.putIfAbsent("audio", owner.now()) == null
            if (first && owner.moments.putIfAbsent("answer_audio", owner.now()) == null) {
                owner.moments["accepted"]?.let { accepted ->
                    owner.performance.observe("request_to_answer_audio", owner.now() - accepted, owner.performanceEngine("synthesis_to_audio"), owner.values["voice_engine_warm"] as? Boolean)
                }
            }
            if (first) owner.moments["pcm"]?.let { pcm -> owner.performance.observe("pcm_to_audio", owner.now() - pcm, owner.performanceEngine("synthesis_to_audio"), owner.values["voice_engine_warm"] as? Boolean) }
            if (first && basis != FirstAudio.AUDIO_TIMESTAMP) {
                // Detailed timestamp-clock evidence stays in the local dashboard. Existing hosted
                // reporters advertise the older closed vocabulary; do not mislabel this as a head read.
                owner.values["voice_first_audio_basis"] = basis.name
            }
            owner.elapsed("voice_synthesis_to_first_audio_ms", "synthesis", "audio")
            owner.elapsed("voice_response_to_first_audio_ms", "response", "audio")
        }
    }

    companion object {
        private const val MAX_COUNT = 16_000_000L
        private const val MAX_DURATION_MS = 86_400_000L
        private const val MAX_AGE_MS = 600_000L
        private val privacyEpoch = AtomicLong()
        private val latest = AtomicReference<VoiceDiagnosticTrace?>()

        /** Preserves the original observation time; opening a report does not refresh its age. */
        internal fun manualSnapshot(): IssueReportDiagnostics? = latest.get()?.snapshot()

        /** Invalidates every outstanding generation ticket before dropping the latest owner. */
        fun clearAll() {
            privacyEpoch.incrementAndGet()
            latest.getAndSet(null)?.clear()
        }
    }
}
