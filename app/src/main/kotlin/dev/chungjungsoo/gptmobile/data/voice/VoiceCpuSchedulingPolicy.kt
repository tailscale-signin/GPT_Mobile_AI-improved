package dev.chungjungsoo.gptmobile.data.voice

/** Explicit settings remain fixed; Auto responds to the shared device governor between replies. */
internal object VoiceCpuSchedulingPolicy {
    fun threads(requested: Int, calibrated: Int?, adaptive: Boolean, moderatePressure: Boolean, severePressure: Boolean): Int {
        require(requested in 1..4)
        if (!adaptive) return requested
        val selected = (calibrated ?: 2).coerceIn(1, 4)
        return when {
            severePressure -> 1
            moderatePressure -> minOf(selected, 2)
            else -> selected
        }
    }

    /** Work targets describe a presentation deadline, never a promise of measured speed. */
    fun targetNanos(firstAudio: Boolean, bufferedAudioMs: Long): Long =
        (if (firstAudio) 5_000L else bufferedAudioMs.coerceIn(250L, 5_000L)) * 1_000_000
}
