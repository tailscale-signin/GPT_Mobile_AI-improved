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
    private val runtime: LocalRuntime,
    private val vault: dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository
) {
    private val observedStates = mutableMapOf<String, String>()
    private val diagnosticsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    init {
        diagnosticsScope.launch {
            kotlinx.coroutines.flow.combine(
                runtime.state,
                WorkManager.getInstance(context).getWorkInfosByTagFlow("memory-enrichment"),
                dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission.availability,
                dev.chungjungsoo.gptmobile.data.readiness.ReadinessWorkloadSignals.active
            ) { state, infos, _, _ -> state to infos }.collect { (state, infos) ->
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
                if (state.engineSpec != null && !runtime.isBusy && !runtime.hasOpenConversation() && !dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission.isExclusiveBusy && dev.chungjungsoo.gptmobile.data.readiness.ReadinessWorkloadSignals.active.value == 0) {
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { resumeWaiting() }
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
        if (runtimeCoolingDown(context, runtime)) return
        val manager = WorkManager.getInstance(context)
        for ((key, value) in waitingStore(context).all) {
            val id = key.toIntOrNull() ?: continue
            val saved = value as? String ?: continue
            val message = dev.chungjungsoo.gptmobile.data.diagnostics.timedDatabaseOperation("memory.source_resume") { database.messageDao().message(id) }
            if (message == null || saved != "${message.chatId}:${digest(message.content)}") {
                clearWaiting(context, id)
                continue
            }
            val uniqueName = uniqueName(message.id, digest(message.content))
            val work = manager.getWorkInfosForUniqueWorkFlow(uniqueName).first()
            if (work.any { !it.state.isFinished }) continue
            // A terminal inference failure waits for a compatible runtime change or explicit retry,
            // not a WorkInfo completion event that would recreate the same 15-second loop.
            if (work.any { it.state == androidx.work.WorkInfo.State.FAILED && it.outputData.getString("runtimeFingerprint") == runtimeFingerprint(runtime) }) continue
            // Terminal work plus loaded-model/idle signals close the wakeup race without polling or replacing active work.
            enqueueEligible(message)
        }
    }

    fun enqueue(message: MessageV2) {
        diagnosticsScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                enqueueEligible(message)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Memory", "ENRICHMENT_ENQUEUE_FAILED · message=${message.id} · persistedWorkRetained=${waitingStore(context).contains(message.id.toString())}", "W")
            }
        }
    }

    private suspend fun enqueueEligible(message: MessageV2) {
        if (message.id <= 0 || message.chatId <= 0) return
        if (!vault.canEnrich(message)) {
            clearWaiting(context, message.id)
            return
        }
        if (runtime.state.value.engineSpec == null || runtime.isBusy || runtime.hasOpenConversation() || dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission.isExclusiveBusy || dev.chungjungsoo.gptmobile.data.readiness.ReadinessWorkloadSignals.active.value > 0) {
            markWaiting(context, workDataOf("messageId" to message.id, "chatId" to message.chatId, "hash" to digest(message.content)))
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Memory", "ENRICHMENT_DEFERRED · message=${message.id} · reason=${if (runtime.state.value.engineSpec == null) "WAITING_FOR_MODEL" else "WAITING_FOR_IDLE"} · workerScheduled=false")
            return
        }
        if (runtimeCoolingDown(context, runtime)) {
            markWaiting(context, workDataOf("messageId" to message.id, "chatId" to message.chatId, "hash" to digest(message.content)))
            return
        }
        val hash = digest(message.content)
        if (completedStore(context).getString(message.id.toString(), null) == completionKey(message.chatId, hash)) {
            clearWaiting(context, message.id)
            return
        }
        // Persist the source reference before asynchronous WorkManager admission can fail or the process can exit.
        markWaiting(context, workDataOf("messageId" to message.id, "chatId" to message.chatId, "hash" to hash))
        val request = OneTimeWorkRequestBuilder<MemoryEnrichmentWorker>()
            .setInputData(workDataOf("messageId" to message.id, "chatId" to message.chatId, "hash" to hash, "runtimeFingerprint" to runtimeFingerprint(runtime)))
            .setInitialDelay(15, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("memory-enrichment")
            .addTag("memory-chat-${message.chatId}")
            .build()
        // WorkManager persists only references and a digest, never user message text.
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Memory", "ENRICHMENT_ENQUEUE_REQUESTED · candidateWork=${request.id} · policy=KEEP · message=${message.id} · constraint=BATTERY_NOT_LOW · initialDelayMs=15000 · persisted=true · backoff=EXPONENTIAL_30000")
        val manager = WorkManager.getInstance(context)
        val operation = manager.enqueueUniqueWork(uniqueName(message.id, hash), ExistingWorkPolicy.KEEP, request)
        operation.result.addListener({
            // The future has completed before this listener runs; get() does not wait here.
            runCatching { operation.result.get() }.onFailure {
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Memory", "ENRICHMENT_ENQUEUE_FAILED · message=${message.id} · persistedWorkRetained=true", "W")
            }
        }, java.util.concurrent.Executor { it.run() })
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
        internal fun runtimeCoolingDown(context: Context, runtime: LocalRuntime): Boolean {
            val store = context.getSharedPreferences("memory-enrichment-cooldown", Context.MODE_PRIVATE)
            return store.getString("runtime", null) == runtimeFingerprint(runtime) &&
                System.currentTimeMillis() < store.getLong("until", 0L)
        }
        internal fun coolDownRuntime(context: Context, runtime: LocalRuntime) {
            context.getSharedPreferences("memory-enrichment-cooldown", Context.MODE_PRIVATE).edit()
                .putString("runtime", runtimeFingerprint(runtime))
                .putLong("until", System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5))
                .apply()
        }
        internal fun runtimeFingerprint(runtime: LocalRuntime): String = digest("${runtime.state.value.engineSpec}|${runtime.state.value.backend}|${dev.chungjungsoo.gptmobile.data.localmodel.LocalModelInstallationEpoch.current()}")
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
            check(waitingStore(context).edit().putString(data.getInt("messageId", 0).toString(), "${data.getInt("chatId", 0)}:${data.getString("hash")}").commit()) { "Cannot persist pending memory enrichment" }
        }
        internal fun clearWaiting(context: Context, messageId: Int) {
            waitingStore(context).edit().remove(messageId.toString()).apply()
        }
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
