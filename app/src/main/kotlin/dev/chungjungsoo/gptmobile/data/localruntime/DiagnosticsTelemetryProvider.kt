package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

/**
 * Diagnostics and hardware state inspector for debug mode.
 */
object DiagnosticsTelemetryProvider {

    data class DiagnosticsSnapshot(
        val backendName: String,
        val accelerator: String,
        val socModel: String,
        val totalRamGb: Long,
        val availableRamMb: Long,
        val thermalStatus: String,
        val batteryPct: Int,
        val isCharging: Boolean,
        val qnnReady: Boolean,
        val dispatchDir: String,
        val skelExists: Boolean
    )

    fun getSnapshot(context: Context, backendName: String, accelerator: String): DiagnosticsSnapshot {
        val hwState = DeviceHardwareGovernor.inspectHardwareState(context)
        val qnnProbe = QnnEnvironment.getProbeStatus(context)

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Nominal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe Throttling"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical Throttling"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> "Unknown"
            }
        } else {
            if (hwState.isThrottlingRequired) "Throttled" else "Nominal"
        }

        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { !it.isNullOrBlank() } ?: Build.HARDWARE.orEmpty()
        } else {
            Build.HARDWARE.orEmpty()
        }

        return DiagnosticsSnapshot(
            backendName = backendName,
            accelerator = accelerator,
            socModel = soc,
            totalRamGb = hwState.totalDeviceRamBytes / (1024L * 1024L * 1024L),
            availableRamMb = hwState.availableRamMb,
            thermalStatus = thermalStatus,
            batteryPct = batteryPct,
            isCharging = isCharging,
            qnnReady = qnnProbe.isReady,
            dispatchDir = qnnProbe.dispatchDir,
            skelExists = qnnProbe.skelFileExists
        )
    }

    fun formatDiagnosticsText(snapshot: DiagnosticsSnapshot, telemetryNotice: String?): String = buildString {
        appendLine("=== Local Model Diagnostics ===")
        appendLine("Engine Backend: ${snapshot.backendName}")
        appendLine("Target Accelerator: ${snapshot.accelerator.uppercase()}")
        appendLine("SoC / Processor: ${snapshot.socModel}")
        appendLine("System RAM: Available ${snapshot.availableRamMb} MB / Total ${snapshot.totalRamGb} GB")
        appendLine("Thermal State: ${snapshot.thermalStatus}")
        appendLine("Battery: ${if (snapshot.batteryPct >= 0) "${snapshot.batteryPct}%" else "N/A"}${if (snapshot.isCharging) " (Charging)" else ""}")
        appendLine("QNN HTP Native Status: ${if (snapshot.qnnReady) "Ready" else "Not Ready"}")
        appendLine("QNN Dispatch Dir: ${snapshot.dispatchDir}")
        appendLine("QNN Skel Present: ${if (snapshot.skelExists) "Yes" else "No"}")
        telemetryNotice?.takeIf { it.isNotBlank() }?.let {
            appendLine("Inference Telemetry: $it")
        }
    }
}
