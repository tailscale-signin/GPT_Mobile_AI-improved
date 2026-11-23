package dev.chungjungsoo.gptmobile.data.voice

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Fixed benchmark inputs only. No waveform, spoken text or arbitrary input enters this record. */
internal data class VoiceSpeechAudioObservation(
    val inputSha256: String,
    val inputBytes: Int,
    val sampleCount: Int,
    val tokenDurations: List<Long>,
    val rms: Double,
    val peak: Double,
    val clippedSamples: Long,
    val nonSilentSamples: Long
) {
    val audioMs: Double get() = sampleCount * 1000.0 / 24_000
    val valid: Boolean
        get() = inputSha256.matches(Regex("[0-9a-f]{64}")) &&
            inputBytes in 1..VoiceSpeechWorkload.MAX_PHRASE_BYTES &&
            sampleCount in 1..720_000 &&
            tokenDurations.size in 1..511 &&
            tokenDurations.all { it in 1..1200 } &&
            tokenDurations.sum() * 600 == sampleCount.toLong() &&
            rms.isFinite() &&
            rms >= 1e-6 &&
            peak.isFinite() &&
            peak + 1e-9 >= rms &&
            clippedSamples in 0..sampleCount.toLong() &&
            nonSilentSamples in 1..sampleCount.toLong()

    companion object {
        fun measure(phrase: String, audio: VoicePhraseAudio): VoiceSpeechAudioObservation {
            var squares = 0.0
            var peak = 0.0
            var clipped = 0L
            var nonSilent = 0L
            check(audio.samples.size in 1..720_000 && audio.durations.size in 1..511) { "OUTPUT_INVALID" }
            for (sample in audio.samples) {
                check(sample.isFinite()) { "OUTPUT_INVALID" }
                val magnitude = abs(sample.toDouble())
                squares += sample.toDouble() * sample
                peak = max(peak, magnitude)
                if (magnitude >= 1) clipped++
                if (magnitude >= 1e-5) nonSilent++
            }
            return VoiceSpeechAudioObservation(
                VoiceSpeechWorkload.digest(phrase),
                phrase.toByteArray(Charsets.UTF_8).size,
                audio.samples.size,
                audio.durations.toList(),
                sqrt(squares / audio.samples.size),
                peak,
                clipped,
                nonSilent
            ).also { check(it.valid) { "OUTPUT_INVALID" } }
        }
    }
}

internal enum class VoiceSpeechAudioReferenceStatus { CPU_REFERENCE, CONSISTENT, MISMATCH, UNOBSERVED }
internal enum class VoiceSpeechAudioReferenceReason {
    CPU_REFERENCE_MISSING,
    MEASUREMENT_MISSING,
    INPUT_MISMATCH,
    TOKEN_COUNT_MISMATCH,
    DURATION_MISMATCH,
    TOKEN_TIMING_MISMATCH,
    SIGNAL_LEVEL_MISMATCH,
    CLIPPING_MISMATCH,
    SIGNAL_ACTIVITY_MISMATCH
}

internal data class VoiceSpeechAudioComparison(
    val status: VoiceSpeechAudioReferenceStatus,
    val reason: VoiceSpeechAudioReferenceReason? = null,
    val referenceAudioMs: Double? = null,
    val candidateAudioMs: Double? = null,
    val durationRatio: Double? = null,
    val tokenTimingDistance: Double? = null,
    val rmsRatio: Double? = null
)

/** A numerical consistency gate, not a claim of perceptual or linguistic equivalence. */
internal object VoiceSpeechAudioReference {
    const val PROTOCOL = "cpu-reference-duration-timing-signal-v1"
    const val MIN_DURATION_RATIO = .9
    const val MAX_DURATION_RATIO = 1.1
    const val MAX_TOKEN_TIMING_DISTANCE = .15
    const val MIN_RMS_RATIO = .5
    const val MAX_RMS_RATIO = 2.0
    const val MAX_PEAK_RATIO = 2.0
    const val MAX_PEAK_ABSOLUTE_INCREASE = .1
    const val MAX_CLIPPED_FRACTION_INCREASE = .001
    const val MAX_ACTIVITY_FRACTION_DIFFERENCE = .15

    fun compare(reference: VoiceSpeechAudioObservation?, candidate: VoiceSpeechAudioObservation?): VoiceSpeechAudioComparison {
        if (reference == null) return VoiceSpeechAudioComparison(VoiceSpeechAudioReferenceStatus.UNOBSERVED, VoiceSpeechAudioReferenceReason.CPU_REFERENCE_MISSING)
        if (candidate == null || !reference.valid || !candidate.valid) {
            return VoiceSpeechAudioComparison(VoiceSpeechAudioReferenceStatus.UNOBSERVED, VoiceSpeechAudioReferenceReason.MEASUREMENT_MISSING)
        }
        val ratio = candidate.sampleCount.toDouble() / reference.sampleCount
        val rmsRatio = candidate.rms / reference.rms
        val sameTokens = candidate.tokenDurations.size == reference.tokenDurations.size
        val timingDistance = if (sameTokens) candidate.tokenDurations.zip(reference.tokenDurations).sumOf { (actual, expected) -> abs(actual - expected) }.toDouble() / reference.tokenDurations.sum() else null
        val reason = when {
            reference.inputSha256 != candidate.inputSha256 || reference.inputBytes != candidate.inputBytes -> VoiceSpeechAudioReferenceReason.INPUT_MISMATCH
            !sameTokens -> VoiceSpeechAudioReferenceReason.TOKEN_COUNT_MISMATCH
            ratio !in MIN_DURATION_RATIO..MAX_DURATION_RATIO -> VoiceSpeechAudioReferenceReason.DURATION_MISMATCH
            timingDistance == null || timingDistance > MAX_TOKEN_TIMING_DISTANCE -> VoiceSpeechAudioReferenceReason.TOKEN_TIMING_MISMATCH
            rmsRatio !in MIN_RMS_RATIO..MAX_RMS_RATIO || candidate.peak > max(reference.peak * MAX_PEAK_RATIO, reference.peak + MAX_PEAK_ABSOLUTE_INCREASE) -> VoiceSpeechAudioReferenceReason.SIGNAL_LEVEL_MISMATCH
            candidate.clippedSamples.toDouble() / candidate.sampleCount > reference.clippedSamples.toDouble() / reference.sampleCount + MAX_CLIPPED_FRACTION_INCREASE -> VoiceSpeechAudioReferenceReason.CLIPPING_MISMATCH
            abs(candidate.nonSilentSamples.toDouble() / candidate.sampleCount - reference.nonSilentSamples.toDouble() / reference.sampleCount) > MAX_ACTIVITY_FRACTION_DIFFERENCE -> VoiceSpeechAudioReferenceReason.SIGNAL_ACTIVITY_MISMATCH
            else -> null
        }
        return VoiceSpeechAudioComparison(
            if (reason == null) VoiceSpeechAudioReferenceStatus.CONSISTENT else VoiceSpeechAudioReferenceStatus.MISMATCH,
            reason,
            reference.audioMs,
            candidate.audioMs,
            ratio,
            timingDistance,
            rmsRatio
        )
    }
}
