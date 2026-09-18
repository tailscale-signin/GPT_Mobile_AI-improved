package com.example.gpt_mobile

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility class for debug mode operations
 */
object DebugModeUtils {
    
    private val TAG = "DebugModeUtils"
    private const val DEBUG_DIR = "debug_mode"
    
    /**
     * Get the debug directory path
     */
    fun getDebugDirectory(context: Context): File {
        val dir = File(context.filesDir, DEBUG_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    /**
     * Save telemetry data to file
     */
    fun saveTelemetryToFile(context: Context, data: String, fileName: String? = null): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val fileName = fileName ?: "telemetry_$timestamp.json"
        val file = File(getDebugDirectory(context), fileName)
        
        try {
            file.writeText(data)
            Log.d(TAG, "Telemetry data saved to $file")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving telemetry data", e)
        }
        
        return file
    }
    
    /**
     * Get device information
     */
    fun getDeviceInfo(context: Context): String {
        return """
            Device Info:
            - Model: ${Build.MODEL}
            - Manufacturer: ${Build.MANUFACTURER}
            - Android Version: ${Build.VERSION.RELEASE}
            - SDK Version: ${Build.VERSION.SDK_INT}
            - Device ID: ${getDeviceId(context)}
        """.trimIndent()
    }
    
    /**
     * Get a unique device identifier
     */
    private fun getDeviceId(context: Context): String {
        // In a real implementation, this would use a more robust method
        // This is a placeholder implementation
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
    }
    
    /**
     * Format timestamp for display
     */
    fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    /**
     * Check if debug mode is supported on this device
     */
    fun isDebugModeSupported(context: Context): Boolean {
        // In a real implementation, this would check for required permissions and features
        // For now, we'll assume it's supported
        return true
    }
    
    /**
     * Validate telemetry data
     */
    fun validateTelemetryData(data: String): Boolean {
        // Basic validation - in a real implementation, this would be more comprehensive
        return data.isNotBlank() && data.length < 1000000 // 1MB limit
    }
    
    /**
     * Clear debug data
     */
    fun clearDebugData(context: Context) {
        val dir = getDebugDirectory(context)
        if (dir.exists()) {
            dir.listFiles()?.forEach { file ->
                file.delete()
            }
            Log.d(TAG, "Cleared debug data directory")
        }
    }
    
    /**
     * Get debug mode status
     */
    fun getDebugModeStatus(context: Context): DebugModeStatus {
        // In a real implementation, this would check actual debug mode status
        return DebugModeStatus(
            isEnabled = DiagnosticsTelemetryProvider.isEnabled(),
            isSupported = isDebugModeSupported(context),
            dataDirectorySize = getDebugDirectory(context).listFiles()?.size ?: 0
        )
    }
}

/**
 * Data class for debug mode status
 */
data class DebugModeStatus(
    val isEnabled: Boolean,
    val isSupported: Boolean,
    val dataDirectorySize: Int
)