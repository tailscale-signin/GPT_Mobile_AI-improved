package dev.chungjungsoo.gptmobile.data.voice

/** A fixed benchmark offset never reads or changes the user's saved route calibration. */
internal class VoiceCaptionCalibration {
    @Volatile private var fixedOffsetMs: Int? = null
    val usesSavedRoutes: Boolean get() = fixedOffsetMs == null

    fun fixed(milliseconds: Int) {
        fixedOffsetMs = milliseconds.coerceIn(-500, 500)
    }

    fun useSavedRoutes() {
        fixedOffsetMs = null
    }

    fun resolve(defaultMs: Int, saved: () -> Int): Int =
        (fixedOffsetMs ?: runCatching(saved).getOrDefault(defaultMs)).coerceIn(-500, 500)
}

/** Counts newly observed underruns without inventing a delta when the initial counter was unavailable. */
internal class VoicePlaybackUnderruns {
    private var previous: Int? = null

    @Synchronized fun observe(total: Int?): Int {
        if (total == null || total < 0) return 0
        val before = previous
        // The same track owns this counter for its entire lifetime. A stale sample
        // cannot reset the baseline and make a later observation count twice.
        if (before != null && total < before) return 0
        previous = total
        return if (before == null) 0 else total - before
    }

    companion object {
        fun phase(firstPresented: Boolean, producerDone: Boolean, acceptedFrames: Long, consumedFrames: Long, refilling: Boolean, paused: Boolean): String = when {
            paused -> "paused"
            !firstPresented -> "startup"
            producerDone && consumedFrames >= acceptedFrames -> "draining"
            refilling -> "refill"
            else -> "active"
        }
    }
}

/** Retains deadline provenance even when the native operation has not produced an exception. */
internal class VoicePlaybackTimeout(
    val stage: VoiceSpeechStage?,
    val limitMs: Long,
    val elapsedMs: Long,
    val scope: String
) : IllegalStateException("Speech exceeded a bounded execution deadline")

internal object VoicePlaybackDeadlines {
    const val PREPARATION_MS = 90_000L
    const val SYNTHESIS_MS = 30_000L
    const val ATTEMPT_MS = 120_000L

    fun exceeded(progress: VoiceSpeechProgress?, idleMs: Long, preparing: Boolean, preparationLimitMs: Long): VoicePlaybackTimeout? {
        val running = progress?.takeIf {
            !preparing && it.active && it.attemptId > 0 && it.stage in setOf(VoiceSpeechStage.FRONTEND, VoiceSpeechStage.PREPARING, VoiceSpeechStage.SYNTHESIS, VoiceSpeechStage.VALIDATING)
        }
        if (running != null) {
            if (running.attemptElapsedMs >= ATTEMPT_MS) {
                return VoicePlaybackTimeout(running.stage, ATTEMPT_MS, running.attemptElapsedMs, "native_attempt")
            }
            val limit = when (running.stage) {
                VoiceSpeechStage.PREPARING -> PREPARATION_MS
                VoiceSpeechStage.SYNTHESIS -> SYNTHESIS_MS
                else -> 10_000L
            }
            return if (running.stageElapsedMs >= limit) VoicePlaybackTimeout(running.stage, limit, running.stageElapsedMs, "native_stage") else null
        }
        val limit = if (preparing) preparationLimitMs.coerceIn(SYNTHESIS_MS, 360_000L) else SYNTHESIS_MS
        return if (idleMs >= limit) VoicePlaybackTimeout(if (preparing) VoiceSpeechStage.PREPARING else null, limit, idleMs, "pipeline_idle") else null
    }
}

/** Native recurring work excludes this call's graph construction; the complete wall cost remains separately recorded. */
internal fun recurringSpeechCostMs(evidence: VoiceSpeechExecutionEvidence?, elapsedMs: Long): Long? {
    if (evidence?.verifiedAudio != true || evidence.stage != VoiceSpeechStage.READY || elapsedMs <= 0) return null
    val frontend = evidence.frontendMs?.takeIf { it in 0..elapsedMs } ?: return null
    val validation = evidence.validationMs?.takeIf { it in 0..elapsedMs } ?: return null
    val synthesis = evidence.synthesisMs.takeIf { it in 1..elapsedMs } ?: return null
    val profiling = evidence.profileFinalizeMs?.takeIf { it in 0..synthesis } ?: return null
    // Every component must fit inside the independently measured outer call; avoid overflow and stale receipts.
    if (frontend > elapsedMs - synthesis || validation > elapsedMs - synthesis - frontend) return null
    return (synthesis - profiling + frontend + validation).coerceAtLeast(1)
}
