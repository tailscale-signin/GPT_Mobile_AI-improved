package dev.chungjungsoo.gptmobile.data.voice

/** Explicit settings remain fixed; Auto responds to the shared device governor between replies. */
internal object VoiceCpuSchedulingPolicy {
    const val MAX_THREADS = 16
    const val AUTO = 0
    val supported = 1..MAX_THREADS
    fun deviceLimit(processors: Int = Runtime.getRuntime().availableProcessors()): Int = processors.coerceIn(supported)
    fun forDevice(requested: Int): Int = requested.coerceIn(1, deviceLimit())

    fun choice(requested: Int) = if (requested == AUTO) AUTO else forDevice(requested)
    fun initial(choice: Int) = forDevice(if (choice == AUTO) 2 else choice)

    fun threads(requested: Int, calibrated: Int?, adaptive: Boolean, moderatePressure: Boolean, severePressure: Boolean): Int {
        require(requested in supported)
        if (!adaptive) return requested
        val selected = (calibrated ?: 2).coerceIn(1, deviceLimit())
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
