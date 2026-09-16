package com.example.gpt_mobile_ai

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages device hardware state and adaptive throttling for AI inference
 */
class DeviceHardwareGovernor(
    private val context: Context
) {
    private val TAG = "DeviceHardwareGovernor"
    private val isInitialized = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val MIN_MEMORY_THRESHOLD_BYTES = 50 * 1024 * 1024 // 50MB
        private const val MIN_STORAGE_THRESHOLD_BYTES = 100 * 1024 * 1024 // 100MB
        private const val MAX_MODEL_SIZE_BYTES = 100 * 1024 * 1024 // 100MB
    }

    /**
     * Initializes the hardware governor
     */
    fun initialize(): Boolean {
        if (isInitialized.get()) {
            Log.d(TAG, "Hardware governor already initialized")
            return true
        }

        return try {
            Log.d(TAG, "Initializing hardware governor")
            
            // Perform initial hardware checks
            performHardwareChecks()
            
            isInitialized.set(true)
            Log.d(TAG, "Hardware governor initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize hardware governor", e)
            false
        }
    }

    /**
     * Performs initial hardware checks
     */
    private fun performHardwareChecks() {
        try {
            val memoryInfo = getMemoryInfo()
            val storageInfo = getStorageInfo()
            val deviceInfo = getDeviceInfo()
            
            Log.d(TAG, "Device info: ${deviceInfo.model} (${deviceInfo.manufacturer})")
            Log.d(TAG, "Memory: ${memoryInfo.totalMemory} bytes available")
            Log.d(TAG, "Storage: ${storageInfo.availableStorage} bytes available")
            
            // Check if device meets minimum requirements
            if (memoryInfo.availableMemory < MIN_MEMORY_THRESHOLD_BYTES) {
                Log.w(TAG, "Low memory warning: ${memoryInfo.availableMemory} bytes available")
            }
            
            if (storageInfo.availableStorage < MIN_STORAGE_THRESHOLD_BYTES) {
                Log.w(TAG, "Low storage warning: ${storageInfo.availableStorage} bytes available")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during hardware checks", e)
        }
    }

    /**
     * Gets memory information
     */
    private fun getMemoryInfo(): MemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        return MemoryInfo(
            totalMemory = memoryInfo.totalMemory,
            availableMemory = memoryInfo.availMem
        )
    }

    /**
     * Gets storage information
     */
    private fun getStorageInfo(): StorageInfo {
        val externalStorage = Environment.getExternalStorageDirectory()
        val stat = StatFs(externalStorage.path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        return StorageInfo(
            totalStorage = blockSize * totalBlocks,
            availableStorage = blockSize * availableBlocks
        )
    }

    /**
     * Gets device information
     */
    private fun getDeviceInfo(): DeviceInfo {
        val build = Build
        
        return DeviceInfo(
            model = build.MODEL,
            manufacturer = build.MANUFACTURER,
            androidVersion = build.VERSION.RELEASE,
            cpuAbi = build.CPU_ABI,
            totalMemory = getMemoryInfo().totalMemory
        )
    }

    /**
     * Checks if the device is suitable for QNN execution
     */
    fun isQnnSupported(): Boolean {
        try {
            val deviceInfo = getDeviceInfo()
            val memoryInfo = getMemoryInfo()
            val storageInfo = getStorageInfo()
            
            // Check minimum requirements for QNN
            val isSupported = 
                memoryInfo.availableMemory >= MIN_MEMORY_THRESHOLD_BYTES &&
                storageInfo.availableStorage >= MIN_STORAGE_THRESHOLD_BYTES &&
                deviceInfo.model.contains("Snapdragon", ignoreCase = true) // Qualcomm devices
            
            Log.d(TAG, "QNN support check: $isSupported (device: ${deviceInfo.model})")
            return isSupported
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking QNN support", e)
            return false
        }
    }

    /**
     * Gets current device health status
     */
    fun getDeviceHealth(): Map<String, Any> {
        return try {
            val deviceInfo = getDeviceInfo()
            val memoryInfo = getMemoryInfo()
            val storageInfo = getStorageInfo()
            
            mapOf(
                "device_info" to mapOf(
                    "model" to deviceInfo.model,
                    "manufacturer" to deviceInfo.manufacturer,
                    "android_version" to deviceInfo.androidVersion,
                    "cpu_abi" to deviceInfo.cpuAbi
                ),
                "memory_info" to mapOf(
                    "total_memory" to memoryInfo.totalMemory,
                    "available_memory" to memoryInfo.availableMemory,
                    "is_low_memory" to (memoryInfo.availableMemory < MIN_MEMORY_THRESHOLD_BYTES)
                ),
                "storage_info" to mapOf(
                    "total_storage" to storageInfo.totalStorage,
                    "available_storage" to storageInfo.availableStorage,
                    "is_low_storage" to (storageInfo.availableStorage < MIN_STORAGE_THRESHOLD_BYTES)
                ),
                "qnn_support" to isQnnSupported(),
                "timestamp" to System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting device health", e)
            emptyMap()
        }
    }

    /**
     * Gets the current device state for adaptive throttling
     */
    fun getDeviceState(): DeviceState {
        val memoryInfo = getMemoryInfo()
        val storageInfo = getStorageInfo()
        
        val memoryState = when {
            memoryInfo.availableMemory < MIN_MEMORY_THRESHOLD_BYTES -> DeviceState.MemoryState.LOW
            memoryInfo.availableMemory < MIN_MEMORY_THRESHOLD_BYTES * 2 -> DeviceState.MemoryState.MEDIUM
            else -> DeviceState.MemoryState.HIGH
        }
        
        val storageState = when {
            storageInfo.availableStorage < MIN_STORAGE_THRESHOLD_BYTES -> DeviceState.StorageState.LOW
            storageInfo.availableStorage < MIN_STORAGE_THRESHOLD_BYTES * 2 -> DeviceState.StorageState.MEDIUM
            else -> DeviceState.StorageState.HIGH
        }
        
        return DeviceState(
            memoryState = memoryState,
            storageState = storageState,
            isLowPower = false // In a real implementation, this would check battery level
        )
    }

    /**
     * Cleans up resources
     */
    fun cleanup() {
        if (isInitialized.get()) {
            Log.d(TAG, "Cleaning up hardware governor")
            scope.cancel()
            isInitialized.set(false)
        }
    }

    /**
     * Performs a health check
     */
    fun healthCheck(): Map<String, Any> {
        return try {
            val health = getDeviceHealth()
            val state = getDeviceState()
            
            mapOf(
                "health" to health,
                "state" to mapOf(
                    "memory" to state.memoryState.name,
                    "storage" to state.storageState.name,
                    "is_low_power" to state.isLowPower
                ),
                "is_healthy" to isInitialized.get(),
                "timestamp" to System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
            mapOf(
                "is_healthy" to false,
                "error" to e.message
            )
        }
    }

    /**
     * Data class for memory information
     */
    data class MemoryInfo(
        val totalMemory: Long,
        val availableMemory: Long
    )

    /**
     * Data class for storage information
     */
    data class StorageInfo(
        val totalStorage: Long,
        val availableStorage: Long
    )

    /**
     * Data class for device information
     */
    data class DeviceInfo(
        val model: String,
        val manufacturer: String,
        val androidVersion: String,
        val cpuAbi: String,
        val totalMemory: Long
    )

    /**
     * Data class for device state
     */
    data class DeviceState(
        val memoryState: MemoryState,
        val storageState: StorageState,
        val isLowPower: Boolean
    ) {
        enum class MemoryState {
            LOW, MEDIUM, HIGH
        }
        
        enum class StorageState {
            LOW, MEDIUM, HIGH
        }
    }
}