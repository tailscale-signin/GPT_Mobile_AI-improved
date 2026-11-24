package dev.chungjungsoo.gptmobile.data.voice

/** Credits completed, validated and presented replies; waiting alone never advances the audio target. */
internal class VoiceSpeechSustainedSeries(
    val targetAudioMs: Long,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val started = nanoTime()
    val deadlineMs = deadlineMs(targetAudioMs)
    private var attempts = 0
    private var completed = 0
    private var observedAudioMs = 0L
    private var terminal = false

    init {
        require(targetAudioMs in TARGETS)
    }

    fun elapsedMs(): Long = ((nanoTime() - started).coerceAtLeast(0) / 1_000_000)
    fun remainingMs(): Long = (deadlineMs - elapsedMs()).coerceAtLeast(0)
    fun canStart(thermalStatus: Int? = null): Boolean = !terminal && attempts < MAX_REPLIES && remainingMs() > 0 && (thermalStatus ?: -1) < 2
    fun sampleDeadlineMs(): Long = minOf(MAX_REPLY_WALL_MS, remainingMs())

    fun observe(result: VoiceSpeechPlaybackResult): VoiceSpeechPlaybackResult {
        check(!terminal && attempts < MAX_REPLIES)
        attempts++
        val valid = creditsAudio(result)
        if (valid) {
            completed++
            observedAudioMs += result.totalAudioMs
        }
        val status = when {
            !valid && result.timeoutScope == "SUSTAINED_SERIES" -> "INSUFFICIENT_DURATION"
            !valid -> "INTERRUPTED"
            observedAudioMs >= targetAudioMs -> "TARGET_REACHED"
            attempts >= MAX_REPLIES || remainingMs() == 0L -> "INSUFFICIENT_DURATION"
            else -> "IN_PROGRESS"
        }
        terminal = status != "IN_PROGRESS"
        return result.copy(
            sustainedTargetAudioMs = targetAudioMs,
            sustainedObservedAudioMs = observedAudioMs,
            sustainedWallElapsedMs = elapsedMs(),
            sustainedWallDeadlineMs = deadlineMs,
            sustainedCompletedRepeats = completed,
            sustainedQualification = status
        )
    }

    fun finishIncomplete(result: VoiceSpeechPlaybackResult, interrupted: Boolean): VoiceSpeechPlaybackResult =
        if (result.sustainedQualification != "IN_PROGRESS") {
            result
        } else {
            terminal = true
            result.copy(
                sustainedWallElapsedMs = elapsedMs(),
                sustainedQualification = if (interrupted) "INTERRUPTED" else "INSUFFICIENT_DURATION"
            )
        }

    companion object {
        val TARGETS = setOf(60_000L, 300_000L, 600_000L)
        const val MAX_REPLIES = 16
        const val MAX_REPLY_WALL_MS = 240_000L
        fun deadlineMs(targetAudioMs: Long): Long = targetAudioMs * 2 + 120_000L

        fun creditsAudio(result: VoiceSpeechPlaybackResult): Boolean =
            result.protocolVersion == VoiceSpeechPlaybackProtocol.VERSION &&
                result.scenario == VoiceSpeechPlaybackScenario.SUSTAINED &&
                result.state == VoiceSpeechPlaybackState.COMPLETE &&
                (result.executionVerified || result.streamingStructureVerified) &&
                result.captionManifestValid &&
                result.firstPresentedAudioMs?.let { it in 0..result.elapsedMs } == true &&
                result.elapsedMs in 1..MAX_REPLY_WALL_MS + 1_000 &&
                result.totalAudioMs in 1..MAX_REPLY_WALL_MS &&
                result.totalAudioMs <= result.elapsedMs + 1_000

        /** Recompute duration/count evidence; an exported label alone cannot qualify a series. */
        fun qualified(rows: List<VoiceSpeechPlaybackResult>, targetAudioMs: Long): Boolean {
            if (targetAudioMs !in TARGETS || rows.size !in 1..MAX_REPLIES) return false
            var audio = 0L
            var wall = 0L
            var replyWall = 0L
            for ((index, row) in rows.withIndex()) {
                if (audio >= targetAudioMs ||
                    !creditsAudio(row) ||
                    row.repeat != index ||
                    row.expectedResident != (index > 0) ||
                    index > 0 &&
                    row.engineReused != true
                ) {
                    return false
                }
                audio += row.totalAudioMs
                replyWall += row.elapsedMs
                val currentWall = row.sustainedWallElapsedMs ?: return false
                if (currentWall < wall ||
                    currentWall > deadlineMs(targetAudioMs) + 1_000 ||
                    row.sustainedTargetAudioMs != targetAudioMs ||
                    row.sustainedObservedAudioMs != audio ||
                    row.sustainedCompletedRepeats != index + 1 ||
                    row.sustainedWallDeadlineMs != deadlineMs(targetAudioMs) ||
                    audio > currentWall + 1_000L * (index + 1) ||
                    replyWall > currentWall + 1_000L * (index + 1) ||
                    row.sustainedQualification != if (index == rows.lastIndex) "TARGET_REACHED" else "IN_PROGRESS"
                ) {
                    return false
                }
                wall = currentWall
            }
            return audio >= targetAudioMs
        }
    }
}

internal fun safeSpeechSustainedQualification(value: String?): String? = value?.takeIf {
    it in setOf("IN_PROGRESS", "TARGET_REACHED", "INSUFFICIENT_DURATION", "INTERRUPTED")
}
