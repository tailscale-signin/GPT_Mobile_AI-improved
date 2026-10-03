package dev.chungjungsoo.gptmobile.data.memory

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryEnrichmentQueue @Inject constructor(@ApplicationContext private val context: Context) {
    fun enqueue(message: MessageV2) {
        if (message.id <= 0 || message.chatId <= 0) return
        val hash = digest(message.content)
        val request = OneTimeWorkRequestBuilder<MemoryEnrichmentWorker>()
            .setInputData(workDataOf("messageId" to message.id, "chatId" to message.chatId, "hash" to hash))
            .setInitialDelay(15, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("memory-enrichment")
            .addTag("memory-chat-${message.chatId}")
            .build()
        // WorkManager persists only references and a digest, never user message text.
        WorkManager.getInstance(context).enqueueUniqueWork("memory-${message.id}-$hash", ExistingWorkPolicy.KEEP, request)
    }

    fun cancelChat(chatId: Int) {
        WorkManager.getInstance(context).cancelAllWorkByTag("memory-chat-$chatId")
    }

    companion object {
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
