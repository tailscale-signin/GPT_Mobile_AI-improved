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
    val cancellationMs: Long? = null
)

/** Per-reply controller. Synthesis and playback may report measurements from separate workers. */
internal class VoiceAdaptivePlayback(private val mode: VoicePerformanceMode) {
    private var productionRatio = 0.0
    private var observations = 0
    private var refillPenalty = 0

    @Synchronized fun observe(synthesisMs: Long, audioMs: Long) {
        if (synthesisMs < 0 || audioMs <= 0) return
        val ratio = (synthesisMs.toDouble() / audioMs).coerceIn(0.01, 10.0)
        productionRatio = if (observations++ == 0) ratio else productionRatio * .75 + ratio * .25
    }

    @Synchronized fun underrun() {
        refillPenalty = (refillPenalty + 40).coerceAtMost(160)
    }

    @Synchronized fun bufferMs(): Int {
        if (mode != VoicePerformanceMode.AUTO) return mode.bufferMs
        val base = when {
            observations == 0 -> 100
            productionRatio < .35 -> 45
            productionRatio < .7 -> 80
            productionRatio < 1.0 -> 140
            else -> 220
        }
        return (base + refillPenalty).coerceIn(45, 300)
    }

    @Synchronized fun phraseWords(bufferedMs: Long): Int {
        if (mode != VoicePerformanceMode.AUTO) return mode.followUpWords.coerceAtMost(16)
        return when {
            observations == 0 -> 12
            bufferedMs < 300 -> 6
            productionRatio >= 1.0 -> 8
            bufferedMs < 1_000 -> 12
            else -> 16
        }
    }
}

/** Chooses from actual warm, repeated measurements; never infers a speedup from core count. */
internal object VoiceCpuCalibration {
    fun select(samples: Map<Int, List<Long>>, fallback: Int = 2): Int {
        val qualified = samples.filter { (threads, times) -> threads in 2..4 && times.size >= 3 && times.all { it > 0 } }
        if (qualified.isEmpty()) return fallback.coerceIn(2, 4)
        fun score(times: List<Long>): Long = times.sorted()[(times.size * .95).toInt().coerceAtMost(times.lastIndex)]
        val best = qualified.minWith(compareBy<Map.Entry<Int, List<Long>>> { score(it.value) }.thenBy { it.key }).key
        val baseline = qualified[fallback] ?: return best
        return if (score(qualified.getValue(best)) < score(baseline) * .95) best else fallback
    }
}
