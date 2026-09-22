package dev.melo.gptmobile.improved.util.governor

import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlin.math.min

/**
 * Dynamic thermal and memory management for sustained performance.
 * Adaptive throttling based on device thermal state and available memory.
 */
class ThermalAndMemoryGovernor @Inject constructor() {
    private val powerManager: PowerManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(PowerManager::class.java)
    } else null

    private var isThrottling = false
    private var lastThrottleTime = 0L

    /**
     * Check current thermal state.
     */
    fun getThermalState(): ThermalState {
        return when {
            isOverheating() -> ThermalState.OVERHEATING
            isWarm() -> ThermalState.WARM
            else -> ThermalState.NORMAL
        }
    }

    /**
     * Check if device is overheating.
     */
    private fun isOverheating(): Boolean {
        return Build.MANUFACTURER == "Qualcomm" &&
                Build.MODEL.contains("Snapdragon") &&
                thermalThrottlingActive()
    }

    /**
     * Check if device is warm (moderate throttling).
     */
    private fun isWarm(): Boolean {
        return Build.MANUFACTURER == "Qualcomm" &&
                Build.MODEL.contains("Snapdragon") &&
                !thermalThrottlingActive()
    }

    /**
     * Check if thermal throttling is active.
     */
    private fun thermalThrottlingActive(): Boolean {
        return powerManager?.isThermalEngineActive == true
    }

    /**
     * Get available memory in MB.
     */
    fun getAvailableMemoryMB(): Int {
        val memInfo = android.os.Debug.MemoryInfo()
        powerManager?.getMemoryInfo(memInfo) ?: return 0
        return (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
    }

    /**
     * Get memory pressure level.
     */
    fun getMemoryPressureLevel(): MemoryPressure {
        val available = getAvailableMemoryMB()
        return when {
            available > 2048 -> MemoryPressure.LOW
            available > 1024 -> MemoryPressure.MODERATE
            else -> MemoryPressure.HIGH
        }
    }

    /**
     * Get recommended context token clamp based on thermal state.
     */
    fun getRecommendedContextClamp(
        maxTokens: Int,
        thermalState: ThermalState = getThermalState()
    ): Int {
        return when (thermalState) {
            ThermalState.OVERHEATING -> min(maxTokens, 1024)
            ThermalState.WARM -> min(maxTokens, 2048)
            ThermalState.NORMAL -> maxTokens
        }
    }

    /**
     * Get recommended stream interval based on thermal state.
     */
    fun getRecommendedStreamInterval(
        defaultMs: Long = 16L,
        thermalState: ThermalState = getThermalState()
    ): Long {
        return when (thermalState) {
            ThermalState.OVERHEATING -> defaultMs * 3
            ThermalState.WARM -> defaultMs * 2
            ThermalState.NORMAL -> defaultMs
        }
    }

    /**
     * Start throttling if needed.
     */
    fun startThrottling() {
        if (!isThrottling) {
            isThrottling = true
            lastThrottleTime = System.currentTimeMillis()
            Log.d("ThermalGovernor", "Throttling started")
        }
    }

    /**
     * Stop throttling.
     */
    fun stopThrottling() {
        if (isThrottling) {
            isThrottling = false
            Log.d("ThermalGovernor", "Throttling stopped")
        }
    }

    /**
     * Check if currently throttling.
     */
    fun isThrottling(): Boolean = isThrottling

    override fun close() {
        stopThrottling()
    }
}

/**
 * Thermal state enumeration.
 */
enum class ThermalState {
    NORMAL, WARM, OVERHEATING
}

/**
 * Memory pressure level.
 */
enum class MemoryPressure {
    LOW, MODERATE, HIGH
}