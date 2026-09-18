package com.example.gpt_mobile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the debug mode state and configuration
 */
class DebugModeManager(private val context: Context) {
    
    private val _debugModeEnabled = MutableStateFlow(false)
    val debugModeEnabled: StateFlow<Boolean> = _debugModeEnabled.asStateFlow()
    
    private val _privacySettings = MutableStateFlow(
        PrivacySettings(
            hardwareTelemetry = true,
            networkTelemetry = true,
            tokenMetrics = true,
            errorTracking = true,
            analytics = false
        )
    )
    val privacySettings: StateFlow<PrivacySettings> = _privacySettings.asStateFlow()
    
    private val database = DebugDatabase.getDatabase(context)
    
    /**
     * Enable debug mode
     */
    fun enableDebugMode() {
        _debugModeEnabled.value = true
        DiagnosticsTelemetryProvider.initialize(context)
        DiagnosticsTelemetryProvider.setEnabled(true)
    }
    
    /**
     * Disable debug mode
     */
    fun disableDebugMode() {
        _debugModeEnabled.value = false
        DiagnosticsTelemetryProvider.setEnabled(false)
    }
    
    /**
     * Toggle debug mode
     */
    fun toggleDebugMode() {
        if (debugModeEnabled.value) {
            disableDebugMode()
        } else {
            enableDebugMode()
        }
    }
    
    /**
     * Update privacy settings
     */
    fun updatePrivacySettings(settings: PrivacySettings) {
        _privacySettings.value = settings
    }
    
    /**
     * Check if debug mode is enabled
     */
    fun isDebugModeEnabled(): Boolean = debugModeEnabled.value
    
    /**
     * Get the debug database instance
     */
    fun getDatabase(): DebugDatabase = database
    
    /**
     * Get the debug DAO for database operations
     */
    fun getDao(): DebugDatabaseDao = database.debugDao()
}