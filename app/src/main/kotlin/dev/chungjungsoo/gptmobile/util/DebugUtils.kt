package dev.chungjungsoo.gptmobile.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugUtils {
    private const val TAG = "GPTMobileDebug"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun logDebug(message: String) {
        Log.d(TAG, "${dateFormat.format(Date())} - $message")
    }

    fun logError(message: String, throwable: Throwable? = null) {
        Log.e(TAG, "${dateFormat.format(Date())} - $message", throwable)
    }

    fun logInfo(message: String) {
        Log.i(TAG, "${dateFormat.format(Date())} - $message")
    }

    fun logWarning(message: String) {
        Log.w(TAG, "${dateFormat.format(Date())} - $message")
    }

    fun measureExecutionTime(block: () -> Unit): Long {
        val startTime = System.currentTimeMillis()
        block()
        val endTime = System.currentTimeMillis()
        return endTime - startTime
    }

    fun logExecutionTime(message: String, block: () -> Unit) {
        val executionTime = measureExecutionTime(block)
        logInfo("$message took $executionTime ms")
    }
}