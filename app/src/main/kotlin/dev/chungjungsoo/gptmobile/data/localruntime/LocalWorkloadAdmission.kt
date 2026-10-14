package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock

internal enum class LocalAdmissionReason { THERMAL, MEMORY, PRESSURE, RECOVERING }
internal data class LocalAdmissionRejection(val reason: LocalAdmissionReason, val message: String)

/** Main-process admission owns optional work. Native contexts are never silently resized. */
class LocalWorkloadAdmission(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private var recoveredAt: Long? = null
    private var recoveringFromPressure = false

    fun rejection(hardware: DeviceHardwareState, availableBytes: Long?, requiredBytes: Long, reserveBytes: Long, optional: Boolean): String? =
        evaluate(hardware, availableBytes, requiredBytes, reserveBytes, optional)?.message

    @Synchronized
    internal fun evaluate(hardware: DeviceHardwareState, availableBytes: Long?, requiredBytes: Long, reserveBytes: Long, optional: Boolean): LocalAdmissionRejection? {
        if (hardware.thermalState >= DeviceThermalState.SEVERE || hardware.thermalHeadroom?.let { it.isFinite() && it >= 1f } == true) {
            recoveredAt = null
            recoveringFromPressure = true
            return LocalAdmissionRejection(LocalAdmissionReason.THERMAL, "Device is too hot for a new local request. Let it cool and retry.")
        }
        if (LocalMemoryBudget(requiredBytes, reserveBytes, availableBytes).hasShortfall) {
            recoveredAt = null
            recoveringFromPressure = true
            return LocalAdmissionRejection(LocalAdmissionReason.MEMORY, "Not enough available memory for this model and context. Release another model or choose a smaller profile.")
        }
        if (!optional) return null
        if (hardware.isModeratePressure || hardware.isThrottlingRequired) {
            recoveredAt = null
            recoveringFromPressure = true
            return LocalAdmissionRejection(LocalAdmissionReason.PRESSURE, "Optional local work is paused under device pressure")
        }
        if (!recoveringFromPressure) return null
        val now = clock()
        val since = recoveredAt ?: now.also { recoveredAt = it }
        if (now - since < 30_000) return LocalAdmissionRejection(LocalAdmissionReason.RECOVERING, "Waiting for stable device headroom")
        recoveringFromPressure = false
        return null
    }

    companion object {
        fun availableMemory(context: Context): Long? {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            return ActivityManager.MemoryInfo().also(manager::getMemoryInfo).availMem
        }
    }
}
