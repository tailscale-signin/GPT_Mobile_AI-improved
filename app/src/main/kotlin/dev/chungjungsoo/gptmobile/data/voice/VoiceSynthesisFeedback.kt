package dev.chungjungsoo.gptmobile.data.voice

/** Measurements only: unset timings are not zero-latency observations. Contains no speech text. */
internal data class VoiceSynthesisFeedback(
    val generatedAudioMs: Long = 0,
    val synthesisMs: Long = 0,
    val bufferedAudioMs: Long = 0,
    val firstAudioMs: Long? = null,
    val threads: Int = 2,
    val suggestedFollowUpWords: Int = 16,
    val bufferMs: Int = 100,
    val route: String = "unknown",
    val captionOffsetMs: Int = 0,
    val clockBasis: String = "playback_head",
    val executionProvider: String = "cpu",
    val underruns: Int = 0,
    val warm: Boolean = false,
    val warmAtRequest: Boolean = false,
    val engineReused: Boolean = false,
    val productionRatio: Double? = null,
    val predictedNextSynthesisMs: Long? = null,
    val bufferState: String = "idle",
    val executionEvidence: VoiceSpeechExecutionEvidence? = null,
    val cancellationMs: Long? = null
)

/** Per-reply controller. Synthesis and playback may report measurements from separate workers. */
internal class VoiceAdaptivePlayback(private val mode: VoicePerformanceMode) {
    private var productionRatio = 0.0
    private var observations = 0
    private var refillPenalty = 0
    private val recentSynthesisMs = ArrayDeque<Long>()
    private val recentRecurringMs = ArrayDeque<Long>()
    private val recentWordCostMs = ArrayDeque<Double>()

    @Synchronized fun observe(synthesisMs: Long, audioMs: Long, recurringMs: Long? = null, spokenWords: Int = 0) {
        if (synthesisMs < 0 || audioMs <= 0) return
        val ratio = (synthesisMs.toDouble() / audioMs).coerceIn(0.01, 10.0)
        productionRatio = if (observations++ == 0) ratio else productionRatio * .75 + ratio * .25
        recentSynthesisMs.addLast(synthesisMs.coerceAtLeast(1))
        recentRecurringMs.addLast(recurringMs?.takeIf { it in 1..synthesisMs } ?: synthesisMs.coerceAtLeast(1))
        while (recentSynthesisMs.size > 8) recentSynthesisMs.removeFirst()
        while (recentRecurringMs.size > 8) recentRecurringMs.removeFirst()
        if (spokenWords > 0) {
            recentWordCostMs.addLast(recentRecurringMs.last().toDouble() / spokenWords)
            while (recentWordCostMs.size > 8) recentWordCostMs.removeFirst()
        }
    }

    @Synchronized fun underrun() {
        refillPenalty = (refillPenalty + 200).coerceAtMost(1_000)
    }

    @Synchronized fun bufferMs(startup: Boolean = false): Int {
        if (observations == 0) return mode.bufferMs
        // A 45–300 ms AudioTrack threshold cannot cover seconds of synchronous
        // phrase synthesis. Reserve accepted PCM for the observed slow tail of
        // the next phrase, while retaining a separate small hardware threshold.
        val tail = predictedSynthesisMs(startup) ?: return mode.bufferMs
        val margin = if (mode == VoicePerformanceMode.FAST) 1.05 else 1.2
        return maxOf(mode.bufferMs, (tail * margin).toInt() + refillPenalty).coerceAtMost(MAX_RESERVE_MS)
    }

    @Synchronized fun predictedSynthesisMs(startup: Boolean = false): Long? =
        (if (startup) recentRecurringMs else recentSynthesisMs).maxOrNull()

    @Synchronized fun ratio(): Double? = productionRatio.takeIf { observations > 0 }

    /** Wait only while an actual producer exists; an unsealed quiet stream cannot deadlock priming. */
    @Synchronized fun primeWaitMs(startup: Boolean = false): Long = (predictedSynthesisMs(startup)?.plus(250) ?: mode.refillWaitMs).coerceIn(0, MAX_RESERVE_MS.toLong())

    @Synchronized fun phraseWords(bufferedMs: Long): Int {
        if (mode != VoicePerformanceMode.AUTO) return mode.followUpWords
        if (observations == 0) return 8
        val wordCost = recentWordCostMs.maxOrNull()
        if (wordCost != null && bufferedMs >= 300) {
            val continuityBudget = (bufferedMs / (wordCost * 1.2)).toInt().coerceIn(6, 16)
            return if (productionRatio >= 1.0) minOf(8, continuityBudget) else continuityBudget
        }
        return when {
            bufferedMs < 300 -> 6
            productionRatio >= 1.0 -> 8
            bufferedMs < 1_000 -> 12
            else -> 16
        }
    }

    companion object {
        // Use existing queue headroom for measured slow phrases and refill margin.
        // Leave one slice of duration headroom; priming also exits if phrase tails fill every queue slot.
        const val MAX_RESERVE_MS = KokoroPcmBatches.QUEUE_LIMIT_MS - KokoroPcmBatches.DURATION_MS
    }
}

/** Chooses from actual warm, repeated measurements; never infers a speedup from core count. */
internal object VoiceCpuCalibration {
    fun select(samples: Map<Int, List<Long>>, fallback: Int = 2): Int {
        val qualified = samples.filter { (threads, times) -> threads in 1..4 && times.size >= 3 && times.all { it > 0 } }
        if (qualified.isEmpty()) return fallback.coerceIn(1, 4)
        fun score(times: List<Long>): Long = times.sorted()[(times.size * .95).toInt().coerceAtMost(times.lastIndex)]
        val best = qualified.minWith(compareBy<Map.Entry<Int, List<Long>>> { score(it.value) }.thenBy { it.key }).key
        val baseline = qualified[fallback] ?: return best
        return if (score(qualified.getValue(best)) < score(baseline) * .95) best else fallback
    }
}
