package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock

/** Main-process admission owns optional work. Native contexts are never silently resized. */
class LocalWorkloadAdmission(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private var recoveredAt: Long? = null

    @Synchronized
    fun rejection(hardware: DeviceHardwareState, availableBytes: Long?, requiredBytes: Long, reserveBytes: Long, optional: Boolean): String? {
        if (hardware.thermalState >= DeviceThermalState.SEVERE || hardware.thermalHeadroom?.let { it.isFinite() && it >= 1f } == true) {
            recoveredAt = null
            return "Device is too hot for a new local request. Let it cool and retry."
        }
        if (availableBytes != null && availableBytes < requiredBytes.coerceAtLeast(0) + reserveBytes.coerceAtLeast(0)) {
            recoveredAt = null
            return "Not enough available memory for this model and context. Release another model or choose a smaller profile."
        }
        if (!optional) return null
        if (hardware.isModeratePressure || hardware.isThrottlingRequired) {
            recoveredAt = null
            return "Optional local work is paused under device pressure"
        }
        val now = clock()
        val since = recoveredAt ?: now.also { recoveredAt = it }
        return if (now - since < 30_000) "Waiting for stable device headroom" else null
    }

    companion object {
        fun availableMemory(context: Context): Long? {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            return ActivityManager.MemoryInfo().also(manager::getMemoryInfo).availMem
        }
    }
}
