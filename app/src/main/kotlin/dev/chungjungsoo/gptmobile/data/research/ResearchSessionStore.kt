package dev.chungjungsoo.gptmobile.data.research

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class ResearchSession(val runId: String, val chatId: Int, val snapshot: ResearchSnapshot)

/** App-private bounded history, included in complete backups; temporary chats stay in RAM. */
@Singleton
class ResearchSessionStore @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.filesDir, "research-history")
    private val lock = Mutex()
    private val taskLocks = Array(64) { Mutex() }
    private val stops = ConcurrentHashMap.newKeySet<String>()
    private val deletedChats = ConcurrentHashMap.newKeySet<Int>()
    private val mutable = MutableStateFlow<Map<String, ResearchSession>>(emptyMap())
    val sessions = mutable.asStateFlow()

    fun stop(runId: String) {
        stops += runId
    }
    fun journal(runId: String, chatId: Int, persistent: Boolean): ResearchJournal = object : ResearchJournal {
        override fun stopRequested() = runId in stops || chatId in deletedChats
        override suspend fun load(): ResearchSnapshot? = withContext(Dispatchers.IO) {
            if (chatId in deletedChats) return@withContext null
            lock.withLock {
                mutable.value[runId]?.takeIf { it.chatId == chatId }?.snapshot
                    ?: if (persistent) read(file(runId))?.takeIf { it.chatId == chatId }?.snapshot else null
            }
        }
        override suspend fun load(task: String): ResearchSnapshot? = withContext(Dispatchers.IO) {
            if (chatId in deletedChats) return@withContext null
            val taskKey = normalizedTask(task)
            lock.withLock {
                val persisted = if (persistent) directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull(::read) else emptyList()
                (persisted + mutable.value.values.filter { it.chatId == chatId })
                    .asSequence()
                    .filter { it.chatId == chatId && normalizedTask(it.snapshot.task) == taskKey }
                    .maxByOrNull { it.snapshot.updatedAt }
                    ?.snapshot
            }
        }
        override suspend fun <T> withTaskLock(task: String, block: suspend () -> T): T {
            val key = "$chatId:${researchHash(normalizedTask(task))}"
            val taskLock = taskLocks[(key.hashCode() and Int.MAX_VALUE) % taskLocks.size]
            return taskLock.withLock { block() }
        }
        override suspend fun save(snapshot: ResearchSnapshot) = withContext(Dispatchers.IO) {
            lock.withLock {
                if (chatId in deletedChats) return@withLock
                val session = ResearchSession(runId, chatId, snapshot)
                mutable.update { (it + (runId to session)).entries.sortedByDescending { row -> row.value.snapshot.updatedAt }.take(24).associate { row -> row.toPair() } }
                if (!persistent) return@withLock
                try {
                    check(directory.mkdirs() || directory.isDirectory)
                    val atomic = AtomicFile(file(runId))
                    val data = buildJsonObject {
                        put("runId", runId)
                        put("chatId", chatId)
                        put("snapshot", snapshot.json())
                    }.toString().toByteArray()
                    require(data.size <= 512 * 1024)
                    val stream = atomic.startWrite()
                    try {
                        stream.write(data)
                        atomic.finishWrite(stream)
                    } catch (e: Exception) {
                        atomic.failWrite(stream)
                        throw e
                    }
                    directory.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }.drop(48).forEach { AtomicFile(it).delete() }
                } catch (_: Exception) {
                    mutable.update { it + (runId to session.copy(snapshot = snapshot.copy(notes = snapshot.notes + "Research history could not be saved."))) }
                }
            }
        }
    }

    suspend fun loadChat(chatId: Int) = withContext(Dispatchers.IO) {
        lock.withLock {
            val restored = directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull(::read).filter { it.chatId == chatId }
            mutable.update { current -> (restored.associateBy { it.runId } + current).entries.sortedWith(compareByDescending<Map.Entry<String, ResearchSession>> { it.value.chatId == chatId }.thenByDescending { it.value.snapshot.updatedAt }).take(24).associate { it.toPair() } }
        }
    }

    suspend fun exportChat(chatId: Int): List<ResearchSession> = withContext(Dispatchers.IO) {
        lock.withLock {
            val stored = directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull(::read).filter { it.chatId == chatId }
            (stored.associateBy { it.runId } + mutable.value.filterValues { it.chatId == chatId }).values.sortedBy { it.snapshot.updatedAt }
        }
    }

    suspend fun deleteChat(chatId: Int) = withContext(Dispatchers.IO) {
        deletedChats += chatId
        lock.withLock {
            directory.listFiles().orEmpty().filter { read(it)?.chatId == chatId }.forEach { AtomicFile(it).delete() }
            mutable.update { sessions -> sessions.filterValues { it.chatId != chatId } }
        }
    }

    private fun file(runId: String) = File(directory, researchHash(runId) + ".json")
    private fun normalizedTask(task: String) = task.trim().replace(Regex("\\s+"), " ").take(8_000).lowercase()
    private fun read(file: File): ResearchSession? = runCatching {
        val bytes = AtomicFile(file).openRead().use { stream ->
            require(stream.channel.size() in 1..512 * 1024)
            stream.readBytes()
        }
        val value = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        ResearchSession(value.text("runId"), value.number("chatId"), requireNotNull(ResearchSnapshot.parse(value["snapshot"]!!.jsonObject)))
    }.getOrNull()
}
