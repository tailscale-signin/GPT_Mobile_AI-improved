package com.example.gpt_mobile_ai.diagnostics

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service for handling remote diagnostics operations
 */
class RemoteDiagnosticsService : Service() {
    private val TAG = "RemoteDiagnosticsService"
    private val isInitialized = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var diagnosticManager: DiagnosticManager? = null

    companion object {
        private const val ACTION_START_DIAGNOSTICS = "com.example.gpt_mobile_ai.START_DIAGNOSTICS"
        private const val ACTION_STOP_DIAGNOSTICS = "com.example.gpt_mobile_ai.STOP_DIAGNOSTICS"

        fun startDiagnostics(context: Context) {
            val intent = Intent(context, RemoteDiagnosticsService::class.java)
            intent.action = ACTION_START_DIAGNOSTICS
            context.startService(intent)
        }

        fun stopDiagnostics(context: Context) {
            val intent = Intent(context, RemoteDiagnosticsService::class.java)
            intent.action = ACTION_STOP_DIAGNOSTICS
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Remote diagnostics service created")
        initializeService()
    }

    override fun onDestroy() {
        Log.d(TAG, "Remote diagnostics service destroyed")
        cleanupService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Remote diagnostics service started")
        
        when (intent?.action) {
            ACTION_START_DIAGNOSTICS -> {
                Log.d(TAG, "Starting diagnostics reporting")
                reportDiagnostics()
            }
            ACTION_STOP_DIAGNOSTICS -> {
                Log.d(TAG, "Stopping diagnostics reporting")
                stopSelf(startId)
            }
        }
        
        return START_NOT_STICKY
    }

    private fun initializeService() {
        if (isInitialized.get()) {
            Log.d(TAG, "Service already initialized")
            return
        }

        try {
            Log.d(TAG, "Initializing remote diagnostics service")
            
            // Initialize diagnostic manager
            diagnosticManager = DiagnosticManager(this)
            diagnosticManager?.initialize()
            
            isInitialized.set(true)
            Log.d(TAG, "Remote diagnostics service initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize service", e)
        }
    }

    private fun reportDiagnostics() {
        if (!isInitialized.get()) {
            Log.w(TAG, "Service not initialized")
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "Reporting diagnostics")
                diagnosticManager?.reportAllDiagnostics()
                Log.d(TAG, "Diagnostics reported successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report diagnostics", e)
            }
        }
    }

    private fun cleanupService() {
        if (isInitialized.get()) {
            Log.d(TAG, "Cleaning up remote diagnostics service")
            diagnosticManager?.cleanup()
            scope.cancel()
            isInitialized.set(false)
        }
    }
}