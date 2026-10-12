package dev.chungjungsoo.gptmobile.data.research

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.agent.ResearchBudget
import dev.chungjungsoo.gptmobile.data.agent.ResearchReaderHealth
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class ResearchSession(val runId: String, val chatId: Int, val snapshot: ResearchSnapshot, val turnId: String = runId)

/** App-private bounded history, included in complete backups; temporary chats stay in RAM. */
@Singleton
class ResearchSessionStore @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.filesDir, "research-history")
    private val lock = Mutex()
    private val readerHealth = ConcurrentHashMap<Pair<Int, String>, ResearchReaderHealth>()
    private val turnBudgets = linkedMapOf<Pair<Int, String>, Pair<Long, ResearchBudget>>()
    private val taskLocks = Array(64) { Mutex() }
    private val stops = ConcurrentHashMap.newKeySet<String>()
    private val deletedChats = ConcurrentHashMap.newKeySet<Int>()
    private val mutable = MutableStateFlow<Map<String, ResearchSession>>(emptyMap())
    val sessions = mutable.asStateFlow()

    fun stop(runId: String) {
        stops += runId
    }
    fun journal(runId: String, chatId: Int, persistent: Boolean, turnId: String = runId): ResearchJournal = object : ResearchJournal {
        override fun readerHealth(): ResearchReaderHealth = readerHealth.computeIfAbsent(chatId to turnId) { ResearchReaderHealth() }
        override fun budget(totalLogicalCalls: Int, downloadLimitBytes: Int): ResearchBudget = synchronized(turnBudgets) {
            val now = System.currentTimeMillis()
            // A turn cannot acquire a new allowance by changing task or profile.
            turnBudgets.entries.removeAll { entry ->
                (now - entry.value.first > 24 * 60 * 60 * 1000L).also { expired -> if (expired) readerHealth.remove(entry.key) }
            }
            val key = chatId to turnId
            turnBudgets[key]?.second ?: run {
                check(turnBudgets.size < 512) { "Research budget capacity reached. Retry after existing turns expire." }
                val budgetFile = File(directory, researchHash("budget:$chatId:$turnId") + ".budget")
                val saved = if (persistent && budgetFile.isFile) {
                    runCatching {
                        Json.parseToJsonElement(AtomicFile(budgetFile).openRead().use { it.readBytes() }.decodeToString()).jsonObject
                    }.getOrNull()
                } else {
                    null
                }
                check(!persistent || !budgetFile.isFile || saved != null) { "Research budget checkpoint is corrupt; start a new turn" }
                check(saved == null || saved.number("total") in 1..2400) { "Invalid research budget checkpoint" }
                val total = saved?.number("total") ?: totalLogicalCalls
                val byteLimit = saved?.number("downloadLimit")?.takeIf { it in 1..512 * 1024 * 1024 } ?: downloadLimitBytes
                val persist: ((ResearchBudget.Snapshot) -> Unit)? = if (!persistent) {
                    null
                } else {
                    { snapshot ->
                        check(directory.mkdirs() || directory.isDirectory)
                        val bytes = buildJsonObject {
                            put("chatId", chatId)
                            put("total", snapshot.total)
                            put("used", snapshot.usedLogical)
                            put("physical", snapshot.physicalRequests)
                            put("downloaded", snapshot.downloadedBytes)
                            put("downloadLimit", snapshot.downloadLimitBytes)
                            put("accounting", snapshot.accountingVersion)
                            put("phases", buildJsonObject { snapshot.remainingByPhase.forEach { (phase, remaining) -> put(phase.name, remaining) } })
                        }.toString().toByteArray()
                        val atomic = AtomicFile(budgetFile)
                        val output = atomic.startWrite()
                        try {
                            output.write(bytes)
                            atomic.finishWrite(output)
                        } catch (failure: Exception) {
                            atomic.failWrite(output)
                            throw failure
                        }
                    }
                }
                ResearchBudget(total, persist = persist, downloadLimitBytes = byteLimit).also { budget ->
                    if (saved != null) budget.restore(ResearchBudget.Snapshot(total, saved.number("used"), saved.number("physical"), total - saved.number("used"), ResearchBudget.Phase.entries.associateWith { saved["phases"]!!.jsonObject.number(it.name) }, saved.number("downloaded"), byteLimit, saved.number("accounting").takeIf { it in 1..2 } ?: 1))
                    turnBudgets[key] = now to budget
                }
            }
        }
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
            // A turn has one corpus, including sibling subquestions.
            lock.withLock {
                val persisted = if (persistent) directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull(::read) else emptyList()
                (persisted + mutable.value.values.filter { it.chatId == chatId })
                    .asSequence()
                    .filter { it.chatId == chatId && it.turnId == turnId }
                    .maxByOrNull { it.snapshot.updatedAt }
                    ?.snapshot
            }
        }
        override suspend fun <T> withTaskLock(task: String, block: suspend () -> T): T {
            val key = "$chatId:$turnId"
            val taskLock = taskLocks[(key.hashCode() and Int.MAX_VALUE) % taskLocks.size]
            return taskLock.withLock { block() }
        }
        override suspend fun save(snapshot: ResearchSnapshot) = withContext(Dispatchers.IO) {
            lock.withLock {
                if (chatId in deletedChats) return@withLock
                val session = ResearchSession(runId, chatId, snapshot, turnId)
                if (!persistent) {
                    mutable.update { (it + (runId to session)).entries.sortedByDescending { entry -> entry.value.snapshot.updatedAt }.take(24).associate { entry -> entry.toPair() } }
                    return@withLock
                }
                try {
                    check(directory.mkdirs() || directory.isDirectory)
                    var corpusBytes = directory.listFiles().orEmpty().filter { it.extension == "source" }.sumOf { it.length() }
                    val atomic = AtomicFile(file(runId))
                    val data = buildJsonObject {
                        put("runId", runId)
                        put("chatId", chatId)
                        put("turnId", turnId)
                        val sourceRefs = snapshot.sources.map { source ->
                            val bytes = source.json().toString().toByteArray()
                            require(bytes.size <= 64 * 1024) { "Research source snapshot is too large" }
                            val name = researchHash(bytes.decodeToString()) + ".source"
                            val destination = File(directory, name)
                            if (!destination.isFile) {
                                check(corpusBytes + bytes.size <= 128L * 1024 * 1024) { "Research corpus storage limit reached" }
                                corpusBytes += bytes.size
                                val sourceFile = AtomicFile(destination)
                                val sourceStream = sourceFile.startWrite()
                                try {
                                    sourceStream.write(bytes)
                                    sourceFile.finishWrite(sourceStream)
                                } catch (error: Exception) {
                                    sourceFile.failWrite(sourceStream)
                                    throw error
                                }
                            }
                            JsonPrimitive(name)
                        }
                        put("snapshot", kotlinx.serialization.json.JsonObject(snapshot.json() - "sources"))
                        put("sourceRefs", JsonArray(sourceRefs))
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
                    mutable.update { (it + (runId to session)).entries.sortedByDescending { entry -> entry.value.snapshot.updatedAt }.take(24).associate { entry -> entry.toPair() } }
                    // Never evict active or resumable runs to satisfy historical retention.
                    directory.listFiles().orEmpty().filter { it.extension == "json" && read(it)?.snapshot?.complete == true }
                        .sortedByDescending { it.lastModified() }.drop(48).forEach { AtomicFile(it).delete() }
                } catch (failure: Exception) {
                    val previouslyRetained = mutable.value.values.filter { it.chatId == chatId && it.turnId == turnId }
                        .flatMap { it.snapshot.sources }.filter { it.readable }.map { it.equivalenceGroup.ifBlank { it.canonicalUrl } }.toSet()
                    val partial = snapshot.copy(
                        complete = false,
                        phase = "Storage error",
                        sources = snapshot.sources.map { source ->
                            if (source.readable && source.equivalenceGroup.ifBlank { source.canonicalUrl } !in previouslyRetained) source.copy(status = "Failed", retrievalQuality = "EMPTY") else source
                        },
                        notes = snapshot.notes + "Research storage failed; new documents are not counted as durably retained."
                    )
                    mutable.update { it + (runId to session.copy(snapshot = partial)) }
                    throw failure
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
        readerHealth.keys.removeAll { it.first == chatId }
        synchronized(turnBudgets) { turnBudgets.keys.removeAll { it.first == chatId } }
        lock.withLock {
            directory.listFiles().orEmpty().filter { read(it)?.chatId == chatId || (it.extension == "budget" && runCatching { Json.parseToJsonElement(it.readText()).jsonObject.number("chatId") == chatId }.getOrDefault(false)) }.forEach { AtomicFile(it).delete() }
            val retained = directory.listFiles().orEmpty().filter { it.extension == "json" }.flatMap { file ->
                runCatching { (Json.parseToJsonElement(file.readText()).jsonObject["sourceRefs"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content } }.getOrDefault(emptyList())
            }.toSet()
            directory.listFiles().orEmpty().filter { it.extension == "source" && it.name !in retained }.forEach { AtomicFile(it).delete() }
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
        val runId = value.text("runId")
        val raw = value["snapshot"]!!.jsonObject
        val references = value["sourceRefs"] as? JsonArray
        val restored = if (references == null) {
            raw
        } else {
            require(references.size <= 900)
            val rows = references.map { entry ->
                val name = (entry as? JsonPrimitive)?.contentOrNull.orEmpty()
                require(name.matches(Regex("[a-f0-9]{64}\\.source")))
                val sourceBytes = AtomicFile(File(directory, name)).openRead().use { input ->
                    require(input.channel.size() <= 64 * 1024)
                    input.readBytes()
                }
                require(researchHash(sourceBytes.decodeToString()) + ".source" == name)
                Json.parseToJsonElement(sourceBytes.decodeToString()).jsonObject
            }
            kotlinx.serialization.json.JsonObject(raw + ("sources" to JsonArray(rows)))
        }
        ResearchSession(runId, value.number("chatId"), requireNotNull(ResearchSnapshot.parse(restored)), value.text("turnId").ifBlank { runId })
    }.getOrNull()
}
