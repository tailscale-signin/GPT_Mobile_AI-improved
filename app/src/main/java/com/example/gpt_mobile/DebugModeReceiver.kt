package com.example.gpt_mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receiver for debug mode related broadcasts
 */
class DebugModeReceiver : BroadcastReceiver() {
    
    private val TAG = "DebugModeReceiver"
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Device booted - initializing debug mode")
                // Initialize debug mode components on boot
                initializeDebugMode(context)
            }
            "com.example.gpt_mobile.DEBUG_MODE_ENABLED" -> {
                Log.d(TAG, "Debug mode enabled via broadcast")
                // Handle debug mode enabled event
            }
            "com.example.gpt_mobile.DEBUG_MODE_DISABLED" -> {
                Log.d(TAG, "Debug mode disabled via broadcast")
                // Handle debug mode disabled event
            }
            else -> {
                Log.d(TAG, "Unknown broadcast received: ${intent.action}")
            }
        }
    }
    
    private fun initializeDebugMode(context: Context) {
        // Initialize debug mode components
        // This could include starting services, registering listeners, etc.
        Log.d(TAG, "Initializing debug mode components")
    }
}