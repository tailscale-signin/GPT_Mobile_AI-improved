package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock

/** Hints only the app-owned CPU synthesis worker. SDK/private worker IDs are never guessed. */
internal class VoiceSynthesisPerformanceHint private constructor(private var session: PerformanceHintManager.Session?) : AutoCloseable {
    fun <T> measure(targetNanos: Long, work: () -> T): T {
        runCatching { session?.updateTargetWorkDuration(targetNanos) }.onFailure { close() }
        val started = SystemClock.elapsedRealtimeNanos()
        return try {
            work()
        } finally {
            val elapsed = (SystemClock.elapsedRealtimeNanos() - started).coerceAtLeast(1)
            runCatching { session?.reportActualWorkDuration(elapsed) }.onFailure { close() }
        }
    }

    override fun close() {
        val retired = session
        session = null
        runCatching { retired?.close() }
    }

    companion object {
        fun open(context: Context): VoiceSynthesisPerformanceHint? = runCatching {
            context.getSystemService(PerformanceHintManager::class.java)
                ?.createHintSession(intArrayOf(Process.myTid()), VoiceCpuSchedulingPolicy.targetNanos(true, 0))
                ?.let(::VoiceSynthesisPerformanceHint)
        }.getOrNull()
    }
}
