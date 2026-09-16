package dev.chungjungsoo.gptmobile.util.debugging

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility class for debugging and logging in conversation features.
 *
 * This class provides:
 * - Timestamped logging
 * - Execution time measurement
 * - Debug information collection
 * - Error tracking
 */
object DebugUtils {
    private const val TAG = "GPT_Mobile_AI_Debug"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /**
     * Logs a debug message with timestamp
     */
    fun logDebug(message: String) {
        val timestamp = dateFormat.format(Date())
        Log.d(TAG, "[$timestamp] $message")
    }

    /**
     * Logs an error with timestamp
     */
    fun logError(message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        if (throwable != null) {
            Log.e(TAG, "[$timestamp] $message", throwable)
        } else {
            Log.e(TAG, "[$timestamp] $message")
        }
    }

    /**
     * Measures execution time of a block of code
     */
    fun <T> measureExecutionTime(block: () -> T): Pair<T, Long> {
        val startTime = System.currentTimeMillis()
        val result = block()
        val endTime = System.currentTimeMillis()
        val executionTime = endTime - startTime
        return Pair(result, executionTime)
    }

    /**
     * Generates a debug string with execution time
     */
    fun generateExecutionTimeString(executionTime: Long): String {
        return "Execution time: ${executionTime}ms"
    }

    /**
     * Formats conversation state for debugging
     */
    fun formatConversationState(conversationId: String, messageCount: Int, title: String): String {
        return "Conversation[$conversationId]: $messageCount messages, Title='$title'"
    }

    /**
     * Formats title generation info for debugging
     */
    fun formatTitleGenerationInfo(title: String, wordCount: Int, executionTime: Long): String {
        return "Title='$title' ($wordCount words), ${generateExecutionTimeString(executionTime)}"
    }

    /**
     * Formats auto-continue info for debugging
     */
    fun formatAutoContinueInfo(conversationId: String, shouldContinue: Boolean, executionTime: Long): String {
        return "Auto-continue for $conversationId: $shouldContinue, ${generateExecutionTimeString(executionTime)}"
    }
}
