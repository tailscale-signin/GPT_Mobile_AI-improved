package dev.chungjungsoo.gptmobile.data.free

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class FreeQueueJob(val key: String, val id: String? = null, val state: String = "SUBMITTING", val createdAt: Long = System.currentTimeMillis(), val answer: String? = null, val servedModel: String? = null, val chatId: Int? = null, val protocol: String = "LEGACY")

/** No prompt storage. An unknown submission is never blindly repeated. Locks cover only one request. */
internal object FreeQueueJobs {
    private var directory: File? = null
    private val revokedChats = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val locks = Array(32) { Mutex() }
    private val memory = java.util.concurrent.ConcurrentHashMap<String, FreeQueueJob>()
    fun initialize(context: Context) {
        directory = File(context.noBackupFilesDir, "free-queue-jobs").apply { mkdirs() }
    }
    suspend fun forgetChat(chatId: Int) = withContext(Dispatchers.IO) {
        revokedChats[chatId] = System.currentTimeMillis()
        FreeProviderDiagnostics.forgetChat(chatId)
        memory.entries.removeIf { it.value.chatId == chatId }
        directory?.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            val job = runCatching { Json.decodeFromString<FreeQueueJob>(AtomicFile(file).readFully().decodeToString()) }.getOrNull()
            if (job?.chatId == chatId) AtomicFile(file).delete()
        }
    }
    suspend fun <T> withJob(key: String, action: suspend () -> T): T = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock { action() }
    suspend fun read(key: String, persistent: Boolean = false): FreeQueueJob? = withContext(Dispatchers.IO) {
        val file = directory?.takeIf { persistent }?.let { File(it, "$key.json") }
        if (file == null) {
            memory[key]
        } else if (file.exists()) {
            Json.decodeFromString<FreeQueueJob>(AtomicFile(file).readFully().decodeToString())
        } else {
            null
        }
    }
    suspend fun save(job: FreeQueueJob, persistent: Boolean = false) = withContext(Dispatchers.IO) {
        check(job.createdAt > (job.chatId?.let { revokedChats[it] } ?: Long.MIN_VALUE)) { "Queued conversation was deleted or made temporary" }
        val dir = directory?.takeIf { persistent }
        if (dir == null) {
            memory[job.key] = job
            if (job.createdAt <= (job.chatId?.let { revokedChats[it] } ?: Long.MIN_VALUE)) memory.remove(job.key, job)
            return@withContext
        }
        val atomic = AtomicFile(File(dir, "${job.key}.json"))
        val stream = atomic.startWrite()
        try {
            stream.write(Json.encodeToString(job).toByteArray())
            atomic.finishWrite(stream)
            if (job.createdAt <= (job.chatId?.let { revokedChats[it] } ?: Long.MIN_VALUE)) atomic.delete()
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
        // Completed/expired receipts are bounded, active jobs are never evicted to permit a duplicate.
        dir.listFiles()?.filter { it.extension == "json" && System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { file ->
            val old = runCatching { Json.decodeFromString<FreeQueueJob>(AtomicFile(file).readFully().decodeToString()) }.getOrNull()
            // Preserve the receipt as a small tombstone; even an expired submission must not replay.
            if (old?.answer != null) {
                val stale = AtomicFile(file)
                val output = stale.startWrite()
                try {
                    output.write(Json.encodeToString(old.copy(answer = null, state = "EXPIRED")).toByteArray())
                    stale.finishWrite(output)
                } catch (error: Exception) {
                    stale.failWrite(output)
                    throw error
                }
            }
        }
    }
}
