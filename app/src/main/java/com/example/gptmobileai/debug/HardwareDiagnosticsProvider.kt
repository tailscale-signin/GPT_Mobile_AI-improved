package com.example.gptmobileai.debug

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.example.gptmobileai.data.model.HardwareDiagnostics
import com.example.gptmobileai.data.model.NetworkType
import com.example.gptmobileai.data.model.PowerMode
import com.example.gptmobileai.data.model.ThermalStatus

class HardwareDiagnosticsProvider(
    private val context: Context
) {
    fun getSnapshot(): HardwareDiagnostics {
        val socModel = detectSoC()
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val gpuModel = detectGpu()
        val npuModel = detectNpu()

        val availableRamMb = getAvailableRam()
        val totalRamGb = getTotalRam()

        val thermalStatus = getThermalStatus()
        val gpuThermalCelsius = getGpuTemperature()
        val npuThermalCelsius = getNpuTemperature()

        val qnnReady = checkQnnReadiness()
        val backendName = detectBackend()

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val status = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
        val batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val powerMode = getPowerMode()
        val networkType = getNetworkType()
        val signalStrength = getSignalStrength()

        return HardwareDiagnostics(
            socModel = socModel,
            cpuCores = cpuCores,
            gpuModel = gpuModel,
            npuModel = npuModel,
            availableRamMb = availableRamMb,
            totalRamGb = totalRamGb,
            thermalStatus = thermalStatus,
            gpuThermalCelsius = gpuThermalCelsius,
            npuThermalCelsius = npuThermalCelsius,
            qnnReady = qnnReady,
            backendName = backendName,
            batteryLevel = batteryLevel,
            batteryCharging = batteryCharging,
            powerMode = powerMode,
            networkType = networkType,
            signalStrength = signalStrength
        )
    }

    private fun detectSoC(): String {
        return when (Build.MANUFACTURER.lowercase()) {
            "qualcomm" -> "Snapdragon ${Build.HARDWARE}"
            "samsung" -> "Exynos ${Build.HARDWARE}"
            "google" -> "Tensor ${Build.HARDWARE}"
            else -> "${Build.MANUFACTURER} ${Build.MODEL}"
        }
    }

    private fun detectGpu(): String {
        return when (Build.MANUFACTURER.lowercase()) {
            "qualcomm" -> "Adreno GPU"
            "samsung" -> "Mali GPU"
            else -> "System GPU"
        }
    }

    private fun detectNpu(): String? {
        return when (Build.MANUFACTURER.lowercase()) {
            "qualcomm" -> "Qualcomm Hexagon HTP"
            else -> null
        }
    }

    private fun getAvailableRam(): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.availMem / (1024 * 1024)).toInt()
    }

    private fun getTotalRam(): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.totalMem / (1024 * 1024 * 1024)).toInt()
    }

    private fun getThermalStatus(): ThermalStatus {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val status = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
            return when {
                status >= PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
                status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.WARNING
                else -> ThermalStatus.NORMAL
            }
        }
        return ThermalStatus.NORMAL
    }

    private fun getGpuTemperature(): Double? {
        return null
    }

    private fun getNpuTemperature(): Double? {
        return null
    }

    private fun checkQnnReadiness(): Boolean {
        return try {
            System.loadLibrary("QnnHtp")
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun detectBackend(): String {
        return "Qualcomm QNN / LiteRT"
    }

    private fun getPowerMode(): PowerMode {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isPowerSaveMode == true) {
            return PowerMode.BATTERY_SAVER
        }
        return PowerMode.NORMAL
    }

    private fun getNetworkType(): NetworkType {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkType.UNKNOWN
        val network = connectivityManager.activeNetwork ?: return NetworkType.UNKNOWN
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.UNKNOWN

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR_5G
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.UNKNOWN
        }
    }

    private fun getSignalStrength(): Int? {
        return null
    }
}
