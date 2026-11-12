package dev.chungjungsoo.gptmobile.data.diagnostics

/** Small windows remain measurements; a single severe frame is classified separately. */
internal object FrameJankPolicy {
    const val MINIMUM_FRAMES = 30L
    const val THRESHOLD_PPM = 50_000L

    fun sustained(frames: Long?, missed: Long?): Boolean =
        frames != null &&
            frames >= MINIMUM_FRAMES &&
            missed != null &&
            missed in 0..frames &&
            missed.toDouble() / frames >= THRESHOLD_PPM / 1_000_000.0
}
