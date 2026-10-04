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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

@Singleton
class MemoryEnrichmentQueue @Inject constructor(@ApplicationContext private val context: Context) {
    private val diagnosticsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

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
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Memory", "ENRICHMENT_SCHEDULED · work=${request.id} · message=${message.id} · constraint=BATTERY_NOT_LOW · initialDelayMs=15000 · persisted=true · backoff=EXPONENTIAL_30000")
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork("memory-${message.id}-$hash", ExistingWorkPolicy.KEEP, request)
        if (!dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.enabled.value) return
        diagnosticsScope.launch {
            manager.getWorkInfosForUniqueWorkFlow("memory-${message.id}-$hash").onEach { infos ->
                infos.forEach { info ->
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Memory", "ENRICHMENT_LIFECYCLE · work=${info.id} · state=${info.state} · attempt=${info.runAttemptCount} · stopReason=${info.stopReason} · constraint=BATTERY_NOT_LOW · rescheduled=${info.state == androidx.work.WorkInfo.State.ENQUEUED && info.runAttemptCount > 0}")
                }
            }.takeWhile { infos -> infos.isEmpty() || infos.any { !it.state.isFinished } }.collect { }
        }
    }

    fun cancelChat(chatId: Int) {
        WorkManager.getInstance(context).cancelAllWorkByTag("memory-chat-$chatId")
    }

    companion object {
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
