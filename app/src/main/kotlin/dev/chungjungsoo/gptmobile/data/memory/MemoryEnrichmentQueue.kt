package dev.chungjungsoo.gptmobile.data.memory

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
class MemoryEnrichmentQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ChatDatabaseV2,
    private val runtime: LocalRuntime
) {
    private val observedStates = mutableMapOf<String, String>()
    private val diagnosticsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    init {
        diagnosticsScope.launch {
            kotlinx.coroutines.flow.combine(
                runtime.state,
                WorkManager.getInstance(context).getWorkInfosByTagFlow("memory-enrichment"),
                dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission.availability
            ) { state, infos, _ -> state to infos }.collect { (state, infos) ->
                if (dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.enabled.value) {
                    infos.forEach { info ->
                        val outcome = info.outputData.getString("outcome") ?: info.progress.getString("outcome") ?: "PENDING"
                        val key = "${info.state}:$outcome:${info.runAttemptCount}"
                        if (observedStates.put(info.id.toString(), key) != key) {
                            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Memory", "ENRICHMENT_LIFECYCLE · work=${info.id} · workerState=${info.state} · outcome=$outcome · attempt=${info.runAttemptCount} · deferred=${outcome.startsWith("WAITING_")}")
                        }
                    }
                    observedStates.keys.retainAll(infos.map { it.id.toString() }.toSet())
                }
                if (state.engineSpec != null && !runtime.isBusy) {
                    try {
                        resumeWaiting()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Memory", "ENRICHMENT_RESUME_DEFERRED · persistedWorkRetained=true", "W")
                    }
                }
            }
        }
    }

    private suspend fun resumeWaiting() {
        val manager = WorkManager.getInstance(context)
        for ((key, value) in waitingStore(context).all) {
            val id = key.toIntOrNull() ?: continue
            val saved = value as? String ?: continue
            val message = database.messageDao().message(id)
            if (message == null || saved != "${message.chatId}:${digest(message.content)}") {
                clearWaiting(context, id)
                continue
            }
            val uniqueName = uniqueName(message.id, digest(message.content))
            val work = manager.getWorkInfosForUniqueWorkFlow(uniqueName).first()
            if (work.any { !it.state.isFinished }) continue
            // Terminal work plus loaded-model/idle signals close the wakeup race without polling or replacing active work.
            enqueue(message)
        }
    }

    fun enqueue(message: MessageV2) {
        if (message.id <= 0 || message.chatId <= 0) return
        val hash = digest(message.content)
        if (completedStore(context).getString(message.id.toString(), null) == completionKey(message.chatId, hash)) return
        val request = OneTimeWorkRequestBuilder<MemoryEnrichmentWorker>()
            .setInputData(workDataOf("messageId" to message.id, "chatId" to message.chatId, "hash" to hash))
            .setInitialDelay(15, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("memory-enrichment")
            .addTag("memory-chat-${message.chatId}")
            .build()
        // WorkManager persists only references and a digest, never user message text.
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Memory", "ENRICHMENT_ENQUEUE_REQUESTED · candidateWork=${request.id} · policy=KEEP · message=${message.id} · constraint=BATTERY_NOT_LOW · initialDelayMs=15000 · persisted=true · backoff=EXPONENTIAL_30000")
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork(uniqueName(message.id, hash), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelChat(chatId: Int) {
        val completed = completedStore(context)
        val cleanup = completed.edit()
        completed.all.filterValues { (it as? String)?.startsWith("$chatId:") == true }.keys.forEach(cleanup::remove)
        cleanup.apply()
        WorkManager.getInstance(context).cancelAllWorkByTag("memory-chat-$chatId")
        val store = waitingStore(context)
        val editor = store.edit()
        store.all.filterValues { (it as? String)?.startsWith("$chatId:") == true }.keys.forEach(editor::remove)
        editor.apply()
    }

    companion object {
        private const val ENRICHMENT_VERSION = 2
        internal fun uniqueName(id: Int, hash: String) = "memory-v$ENRICHMENT_VERSION-$id-$hash"
        internal fun completionKey(chatId: Int, hash: String) = "$chatId:v$ENRICHMENT_VERSION:$hash"
        internal fun completedStore(context: Context) = context.getSharedPreferences("memory-enrichment-completed", Context.MODE_PRIVATE)
        internal fun markCompleted(context: Context, data: androidx.work.Data) {
            val id = data.getInt("messageId", 0)
            val hash = data.getString("hash") ?: return
            if (id > 0) completedStore(context).edit().putString(id.toString(), completionKey(data.getInt("chatId", 0), hash)).apply()
        }
        internal fun waitingStore(context: Context) = context.getSharedPreferences("memory-enrichment-waiting", Context.MODE_PRIVATE)
        internal fun markWaiting(context: Context, data: androidx.work.Data) {
            waitingStore(context).edit().putString(data.getInt("messageId", 0).toString(), "${data.getInt("chatId", 0)}:${data.getString("hash")}").commit()
        }
        internal fun clearWaiting(context: Context, messageId: Int) {
            waitingStore(context).edit().remove(messageId.toString()).apply()
        }
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
