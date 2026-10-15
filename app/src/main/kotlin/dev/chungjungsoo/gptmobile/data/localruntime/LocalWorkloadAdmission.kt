package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock

internal enum class LocalAdmissionReason { THERMAL, MEMORY, PRESSURE, RECOVERING }
internal data class LocalAdmissionRejection(val reason: LocalAdmissionReason, val message: String, val recoveryCause: LocalAdmissionReason? = null, val remainingMillis: Long? = null)

/** Main-process admission owns optional work. Native contexts are never silently resized. */
class LocalWorkloadAdmission(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private var recoveredAt: Long? = null
    private var recoveringFromPressure = false
    private var recoveryCause: LocalAdmissionReason? = null
    private var recoveryDetail: String = ""

    fun rejection(hardware: DeviceHardwareState, availableBytes: Long?, requiredBytes: Long, reserveBytes: Long, optional: Boolean): String? =
        evaluate(hardware, availableBytes, requiredBytes, reserveBytes, optional)?.message

    @Synchronized
    internal fun evaluate(hardware: DeviceHardwareState, availableBytes: Long?, requiredBytes: Long, reserveBytes: Long, optional: Boolean): LocalAdmissionRejection? {
        if (hardware.thermalState >= DeviceThermalState.SEVERE || hardware.thermalHeadroom?.let { it.isFinite() && it >= 1f } == true) {
            recoveredAt = null
            recoveringFromPressure = true
            recoveryCause = LocalAdmissionReason.THERMAL
            recoveryDetail = "thermal=${hardware.thermalState}; headroom=${hardware.thermalHeadroom}"
            return LocalAdmissionRejection(LocalAdmissionReason.THERMAL, "Device is too hot for a new local request. Let it cool and retry.")
        }
        if (LocalMemoryBudget(requiredBytes, reserveBytes, availableBytes).hasShortfall) {
            recoveredAt = null
            recoveringFromPressure = true
            recoveryCause = LocalAdmissionReason.MEMORY
            recoveryDetail = "availableBytes=$availableBytes; requiredBytes=$requiredBytes; reserveBytes=$reserveBytes"
            return LocalAdmissionRejection(LocalAdmissionReason.MEMORY, "Not enough available memory for this model and context. Release another model or choose a smaller profile.")
        }
        if (!optional) return null
        if (hardware.isModeratePressure || hardware.isThrottlingRequired) {
            recoveredAt = null
            recoveringFromPressure = true
            recoveryCause = LocalAdmissionReason.PRESSURE
            recoveryDetail = listOfNotNull("thermal=${hardware.thermalState}".takeIf { hardware.isModeratePressure }, "Battery Saver".takeIf { hardware.isPowerSaveMode }, "battery=${hardware.batteryPct}%; unplugged".takeIf { !hardware.isCharging && hardware.batteryPct <= 25 }, "headroom=${hardware.thermalHeadroom}").joinToString("; ")
            return LocalAdmissionRejection(LocalAdmissionReason.PRESSURE, "Optional local work is paused under device pressure: $recoveryDetail")
        }
        if (!recoveringFromPressure) return null
        val now = clock()
        val since = recoveredAt ?: now.also { recoveredAt = it }
        val remaining = (30_000 - (now - since).coerceAtLeast(0)).coerceAtLeast(0)
        if (remaining > 0) return LocalAdmissionRejection(LocalAdmissionReason.RECOVERING, "Waiting for stable device headroom: ${(remaining + 999) / 1000}s remaining; cause=$recoveryCause ($recoveryDetail)", recoveryCause, remaining)
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
