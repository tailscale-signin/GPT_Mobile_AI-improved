package com.example.gptmobileai.data.model

data class HardwareDiagnostics(
    val socModel: String,
    val cpuCores: Int,
    val gpuModel: String,
    val npuModel: String?,
    val availableRamMb: Int,
    val totalRamGb: Int,
    val thermalStatus: ThermalStatus,
    val gpuThermalCelsius: Double?,
    val npuThermalCelsius: Double?,
    val qnnReady: Boolean,
    val backendName: String,
    val batteryLevel: Int,
    val batteryCharging: Boolean,
    val powerMode: PowerMode,
    val networkType: NetworkType,
    val signalStrength: Int?,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ThermalStatus { NORMAL, WARNING, CRITICAL }
enum class PowerMode { NORMAL, PERFORMANCE, BATTERY_SAVER }
enum class NetworkType { WIFI, CELLULAR_5G, CELLULAR_LTE, ETHERNET, UNKNOWN }
