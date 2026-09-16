package dev.chungjungsoo.gptmobile.util

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Enhanced debugging utilities with timestamped logging
 */
object DebugUtils {
    private const val TAG = "GPTMobileDebug"

    /**
     * Logs a message with timestamp
     */
    fun log(message: String, level: LogLevel = LogLevel.INFO) {
        val timestamp = System.currentTimeMillis()
        val formattedMessage = "[${formatTimestamp(timestamp)}] $message"
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, formattedMessage)
            LogLevel.INFO -> Log.i(TAG, formattedMessage)
            LogLevel.WARN -> Log.w(TAG, formattedMessage)
            LogLevel.ERROR -> Log.e(TAG, formattedMessage)
        }
    }

    /**
     * Measures execution time of a block
     */
    suspend fun <T> measureExecutionTime(block: suspend () -> T): Pair<T, Long> {
        val startTime = System.nanoTime()
        val result = withContext(Dispatchers.Default) { block() }
        val endTime = System.nanoTime()
        val duration = (endTime - startTime) / 1_000_000 // Convert to milliseconds
        return Pair(result, duration)
    }

    /**
     * Composable function to log execution time of a composable block
     */
    @Composable
    fun <T> measureComposableExecutionTime(
        block: @Composable () -> T
    ): T {
        val startTime = System.nanoTime()
        val result = block()
        val endTime = System.nanoTime()
        val duration = (endTime - startTime) / 1_000_000 // Convert to milliseconds
        Log.d(TAG, "Composable execution time: ${duration}ms")
        return result
    }

    /**
     * Format timestamp for logging
     */
    private fun formatTimestamp(timestamp: Long): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
    }

    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR
    }
}

/**
 * Composable function to log a message with timestamp
 */
@Composable
fun DebugLog(message: String, level: DebugUtils.LogLevel = DebugUtils.LogLevel.INFO) {
    val context = LocalContext.current
    LaunchedEffect(message) {
        DebugUtils.log(message, level)
    }
}