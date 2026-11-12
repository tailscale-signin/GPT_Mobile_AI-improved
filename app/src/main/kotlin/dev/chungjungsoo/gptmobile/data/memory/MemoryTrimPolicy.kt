package dev.chungjungsoo.gptmobile.data.memory

/** Running-pressure (5..19) and cached/background (40+) signals are distinct from UI hidden (20). */
internal object MemoryTrimPolicy {
    fun releaseImmediately(level: Int): Boolean = level in 5 until 20 || level >= 40
    fun releaseReason(memoryPressure: Boolean, trimSignal: Int?, idleMillis: Long): String = when {
        trimSignal == 40 -> "background_trim"
        memoryPressure -> "memory_pressure"
        idleMillis <= 0 -> "explicit_release"
        else -> "idle_timeout"
    }
    const val REACQUIRE_COOLDOWN_MS = 30_000L
}
