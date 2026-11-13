package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidents
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import dev.chungjungsoo.gptmobile.data.diagnostics.ReportDiagnosticSnapshots
import dev.chungjungsoo.gptmobile.data.diagnostics.ReportFeature
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
    enum class Engine { SYSTEM, LOCAL, KOKORO }
    enum class Focus { GRANTED, DENIED, ERROR, NOT_REQUESTED }
    enum class FirstAudio { UTTERANCE_START, PLAYBACK_HEAD }

    private val lock = Any()
    private val sessionHash = MessageDigest.getInstance("SHA-256")
        .digest("gptmobile.voice-diagnostic.session.v1:${UUID.randomUUID()}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private val values = linkedMapOf<String, Any?>()
    private val moments = mutableMapOf<String, Long>()
    private var capturedAt = 0L
    private var closed = false
    private var probe = Probe(this, 0, privacyEpoch.get(), allowed())

    init {
        latest.set(this)
    }

    /** A new capture resets the turn; cancellation only fences callbacks for the current turn. */
    internal fun advance(generation: Long, reset: Boolean = false): Probe = synchronized(lock) {
        if (reset || probe.epoch != privacyEpoch.get()) {
            if (reset && !closed && allowed()) {
                latest.compareAndSet(null, this)
            }
            values.clear()
            moments.clear()
            capturedAt = 0
        }
        Probe(this, generation.coerceAtLeast(0), privacyEpoch.get(), allowed()).also { probe = it }
    }

    internal fun incidentFields(): Map<String, Any?> = fields("INCIDENT")?.first.orEmpty()

    /** Explicit retries/replay are new playback measurements, even when the draft is retained. */
    internal fun beginReply(generation: Long): Probe = synchronized(lock) {
        moments.clear()
        values.keys.removeAll { it.endsWith("_ms") || it in setOf("voice_engine_warm", "voice_first_audio_basis") }
        advance(generation)
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
        capturedAt = 0
        if (dispose) closed = true
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
        if (closed || !ticket.collectable || ticket !== probe || ticket.epoch != privacyEpoch.get() || !allowed()) return@synchronized
        action()
        capturedAt = wallClock()
    }

    private fun elapsed(field: String, start: String, end: String) {
        val from = moments[start] ?: return
        val to = moments[end] ?: return
        if (to >= from) values[field] = (to - from).coerceAtMost(MAX_DURATION_MS)
    }

    /** Backends retain this immutable ticket inside their capture/playback operation. */
    class Probe internal constructor(private val owner: VoiceDiagnosticTrace, internal val generation: Long, internal val epoch: Long, internal val collectable: Boolean) {
        /** Keep a terminal run join through pause/close, but clear it before another capture generation. */
        internal fun replyFailure(fields: Map<String, Any?>) {
            val snapshot = IssueReportContext.fromFields(
                "VOICE_STATE",
                IssueReportIncidentCapsule.fields(ReportFeature.VOICE, "VOICE_STATE", "VOICE_SESSION_FAILED", fields + ("diagnostic_area" to "VOICE"))
            ).diagnostics ?: return
            owner.record(this) {
                snapshot.values.filterKeys { it in IssueReportIncidentCapsule.fieldsFor("VOICE") }.forEach { (key, value) -> owner.values[key] = value }
            }
        }
        fun engine(engine: Engine) = owner.record(this) {
            val previous = owner.values["voice_engine"]
            if (previous != null && previous != engine.name) {
                listOf("warmup", "ready", "synthesis", "audio").forEach(owner.moments::remove)
                listOf("voice_warmup_ms", "voice_engine_warm", "voice_queue_to_synthesis_ms", "voice_synthesis_to_first_audio_ms", "voice_response_to_first_audio_ms", "voice_first_audio_basis").forEach(owner.values::remove)
            }
            owner.values["voice_engine"] = engine.name
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
        fun inputSilenced(silenced: Boolean) = owner.record(this) { owner.values["voice_input_silenced"] = silenced }
        fun captureAttempt() = owner.record(this) {
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
        fun responseText() = owner.record(this) { owner.moments.putIfAbsent("response", owner.now()) }
        fun queued() = owner.record(this) {
            owner.moments.putIfAbsent("queue", owner.now())
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
        fun firstAudio(basis: FirstAudio) = owner.record(this) {
            if (owner.moments.putIfAbsent("audio", owner.now()) == null) owner.values["voice_first_audio_basis"] = basis.name
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
