package com.example.gpt_mobile_ai.diagnostics

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.annotation.WorkerThread
import com.example.gpt_mobile_ai.BuildConfig
import java.io.File

/**
 * Collects and provides device diagnostic information
 */
data class DeviceDiagnostics(
    val deviceModel: String,
    val manufacturer: String,
    val androidVersion: String,
    val appVersion: String,
    val isDebugBuild: Boolean,
    val totalMemory: Long,
    val availableMemory: Long,
    val totalStorage: Long,
    val availableStorage: Long,
    val timestamp: Long,
    val isQnnSupported: Boolean,
    val qnnLibraryVersion: String?
) {
    companion object {
        @WorkerThread
        fun collect(context: Context): DeviceDiagnostics {
            val build = Build
            val memoryInfo = getMemoryInfo(context)
            val storageInfo = getStorageInfo(context)

            return DeviceDiagnostics(
                deviceModel = build.MODEL,
                manufacturer = build.MANUFACTURER,
                androidVersion = build.VERSION.RELEASE,
                appVersion = BuildConfig.VERSION_NAME,
                isDebugBuild = BuildConfig.DEBUG,
                totalMemory = memoryInfo.totalMemory,
                availableMemory = memoryInfo.availableMemory,
                totalStorage = storageInfo.totalStorage,
                availableStorage = storageInfo.availableStorage,
                timestamp = System.currentTimeMillis(),
                isQnnSupported = isQnnSupported(context),
                qnnLibraryVersion = getQnnLibraryVersion(context)
            )
        }

        @WorkerThread
        private fun getMemoryInfo(context: Context): MemoryInfo {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            return MemoryInfo(
                totalMemory = memoryInfo.totalMemory,
                availableMemory = memoryInfo.availMem
            )
        }

        @WorkerThread
        private fun getStorageInfo(context: Context): StorageInfo {
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

        @WorkerThread
        private fun isQnnSupported(context: Context): Boolean {
            // Check if QNN libraries are available
            val qnnLibraries = listOf(
                "libQnnHtp.so",
                "libQnnHtpV73.so",
                "libQnnHtpV75.so",
                "libQnnHtpV80.so"
            )

            return qnnLibraries.any { library ->
                try {
                    val libFile = File(context.applicationContext.nativeLibraryDir, library)
                    libFile.exists()
                } catch (e: Exception) {
                    false
                }
            }
        }

        @WorkerThread
        private fun getQnnLibraryVersion(context: Context): String? {
            // In a real implementation, this would check the actual QNN library version
            // For now, we'll return a placeholder
            return "QNN v1.0" // Placeholder - actual implementation would check library versions
        }
    }

    data class MemoryInfo(
        val totalMemory: Long,
        val availableMemory: Long
    )

    data class StorageInfo(
        val totalStorage: Long,
        val availableStorage: Long
    )
}