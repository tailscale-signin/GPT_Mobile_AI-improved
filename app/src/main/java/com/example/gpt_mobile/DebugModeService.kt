package com.example.gpt_mobile

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Service for background debug mode operations
 */
class DebugModeService : Service() {
    
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private val TAG = "DebugModeService"
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Debug mode service created")
        
        // Start background telemetry collection
        coroutineScope.launch {
            // In a real implementation, this would collect telemetry data in the background
            Log.d(TAG, "Started background telemetry collection")
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Debug mode service started")
        // Keep service running
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Debug mode service destroyed")
        // Clean up resources
        coroutineScope.cancel()
    }
}