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

data class ResearchSession(val runId: String, val chatId: Int, val snapshot: ResearchSnapshot, val turnId: String = runId, val authority: String = "legacy")

/** App-private bounded history, included in complete backups; temporary chats stay in RAM. */
@Singleton
class ResearchSessionStore @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.filesDir, "research-history")
    private val lock = Mutex()
    private val readerHealth = ConcurrentHashMap<Pair<Int, String>, ResearchReaderHealth>()
    private val turnBudgets = linkedMapOf<Pair<Int, String>, Pair<Long, ResearchBudget>>()
    private val taskLocks = Array(64) { Mutex() }
    private val planLocks = Array(64) { Mutex() }
    private val stops = ConcurrentHashMap.newKeySet<String>()
    private val stoppedTurns = ConcurrentHashMap.newKeySet<Pair<Int, String>>()
    private val runTurns = ConcurrentHashMap<String, Pair<Int, String>>()
    private data class Lease(val owner: String, val until: Long)
    private val workLeases = ConcurrentHashMap<String, Lease>()
    private val sourceRefs = ConcurrentHashMap<String, Pair<ResearchSource, String>>()
    private var completedFiles: MutableMap<File, Long>? = null
    private val temporaryBodies = ConcurrentHashMap<String, String>()
    private val deletedChats = ConcurrentHashMap.newKeySet<Int>()
    private val mutable = MutableStateFlow<Map<String, ResearchSession>>(emptyMap())
    val sessions = mutable.asStateFlow()

    fun stop(runId: String) {
        stops += runId
        runTurns[runId]?.let { stoppedTurns += it }
    }
    fun journal(runId: String, chatId: Int, persistent: Boolean, turnId: String = runId, authority: String = "legacy", shareEnabled: Boolean = true): ResearchJournal = object : ResearchJournal {
        init {
            runTurns[runId] = chatId to turnId
        }
        override fun scoped(authority: String, sharePublicEvidence: Boolean): ResearchJournal = journal(runId, chatId, persistent, turnId, if (shareEnabled && sharePublicEvidence) authority else "$authority:$runId", shareEnabled)
        override fun sourceId(identity: String, fallback: String) = "S" + researchHash(identity).take(16)
        private fun workKey(key: String) = "$chatId:$turnId:$authority:$key"
        override fun claimWork(key: String, leaseMillis: Long): Boolean {
            if (stopRequested()) return false
            val now = System.nanoTime() / 1_000_000
            val lease = workLeases.compute(workKey(key)) { _, old -> if (old == null || old.until <= now) Lease(runId, now + leaseMillis.coerceIn(1000, 300_000)) else old }
            return lease?.owner == runId
        }
        override fun releaseWork(key: String) {
            workLeases.computeIfPresent(workKey(key)) { _, old -> old.takeUnless { it.owner == runId } }
        }
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
        override fun goalBudget(plan: dev.chungjungsoo.gptmobile.data.model.EffectiveResearchPlan): ResearchBudget = synchronized(turnBudgets) {
            val key = chatId to turnId
            turnBudgets[key]?.second?.let { existing ->
                check(existing.ledger?.fingerprint == plan.fingerprint) { "Research policy changed; resume requires the original plan" }
                return@synchronized existing
            }
            val ledgerFile = File(directory, researchHash("budget:$chatId:$turnId") + ".ledger")
            val legacyFile = File(directory, researchHash("budget:$chatId:$turnId") + ".budget")
            check(!persistent || ledgerFile.isFile || !legacyFile.isFile) { "Legacy run requires explicit research policy migration before resume" }
            val ledger = dev.chungjungsoo.gptmobile.data.agent.ResearchRunLedger(plan.fingerprint, plan.limits) { state ->
                if (persistent) {
                    check(directory.mkdirs() || directory.isDirectory)
                    val atomic = AtomicFile(ledgerFile)
                    val output = atomic.startWrite()
                    try {
                        val envelope = buildJsonObject {
                            put("chatId", chatId)
                            put("state", Json.encodeToJsonElement(dev.chungjungsoo.gptmobile.data.agent.ResearchRunLedger.State.serializer(), state))
                        }
                        output.write(envelope.toString().toByteArray())
                        atomic.finishWrite(output)
                    } catch (error: Exception) {
                        atomic.failWrite(output)
                        throw error
                    }
                }
            }
            if (persistent && ledgerFile.isFile) {
                val envelope = Json.parseToJsonElement(AtomicFile(ledgerFile).openRead().use { it.readBytes() }.decodeToString()).jsonObject
                check(envelope.number("chatId") == chatId)
                ledger.restore(Json.decodeFromJsonElement(dev.chungjungsoo.gptmobile.data.agent.ResearchRunLedger.State.serializer(), requireNotNull(envelope["state"])))
            }
            ResearchBudget(1, ledger = ledger).also { turnBudgets[key] = System.currentTimeMillis() to it }
        }
        override fun stopRequested() = runId in stops || chatId in deletedChats || (chatId to turnId) in stoppedTurns
        override suspend fun load(): ResearchSnapshot? = withContext(Dispatchers.IO) {
            if (chatId in deletedChats) return@withContext null
            lock.withLock {
                mutable.value[runId]?.takeIf { it.chatId == chatId && it.authority == authority }?.snapshot
                    ?: if (persistent) read(file(runId))?.takeIf { it.chatId == chatId && it.authority == authority }?.snapshot else null
            }
        }
        override suspend fun load(task: String): ResearchSnapshot? = withContext(Dispatchers.IO) {
            if (chatId in deletedChats) return@withContext null
            // A turn has one corpus, including sibling subquestions.
            lock.withLock {
                val inMemory = mutable.value.values.filter { it.chatId == chatId && it.turnId == turnId && it.authority == authority }
                val pointer = File(directory, researchHash("turn:$chatId:$turnId:$authority") + ".cursor")
                val pointed = if (persistent && pointer.isFile) runCatching { read(file(Json.parseToJsonElement(pointer.readText()).jsonObject.text("runId"))) }.getOrNull() else null
                val persisted = if (pointed != null) {
                    listOf(pointed)
                } else if (persistent && inMemory.isEmpty()) {
                    directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull(::read).filter { it.authority == authority }
                } else {
                    emptyList()
                }
                (persisted + inMemory)
                    .asSequence()
                    .filter { it.chatId == chatId && it.turnId == turnId && it.authority == authority }
                    .maxByOrNull { it.snapshot.updatedAt }
                    ?.snapshot
            }
        }
        override suspend fun <T> withTaskLock(task: String, block: suspend () -> T): T {
            val key = "$chatId:$turnId:$authority:${normalizedTask(task)}"
            val locks = if (task.startsWith("delegation-plan:")) planLocks else taskLocks
            val taskLock = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]
            return taskLock.withLock { block() }
        }
        override suspend fun retainBody(text: String): List<String> = withContext(Dispatchers.IO) {
            lock.withLock {
                check(!stopRequested()) { "Research stopped" }
                text.chunked(16 * 1024).map { chunk ->
                    val name = researchHash(chunk) + ".chunk"
                    if (persistent) {
                        check(directory.mkdirs() || directory.isDirectory)
                        val destination = File(directory, name)
                        if (!destination.isFile) {
                            check(directory.usableSpace >= 8L * 1024 * 1024 + chunk.toByteArray().size) { "Paused for device resources: insufficient storage" }
                            writeAtomic(destination, chunk.toByteArray())
                        }
                    } else {
                        val runtime = Runtime.getRuntime()
                        check(runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory() >= 16L * 1024 * 1024) { "Paused for device resources: temporary memory pressure" }
                        temporaryBodies["$chatId:$turnId:$name"] = chunk
                    }
                    name
                }
            }
        }
        override suspend fun save(snapshot: ResearchSnapshot) = withContext(Dispatchers.IO) {
            lock.withLock {
                if (chatId in deletedChats) return@withLock
                val priorPlan = mutable.value[runId]?.takeIf { it.authority == authority }?.snapshot?.delegationPlan ?: if (persistent) read(file(runId))?.takeIf { it.authority == authority }?.snapshot?.delegationPlan else null
                val inherited = mutable.value.values.filter { it.chatId == chatId && it.turnId == turnId && it.authority == authority }.map { it.snapshot }
                val snapshot = mergeResearchSnapshots(inherited, if (snapshot.delegationPlan == null) snapshot.copy(delegationPlan = priorPlan) else snapshot)
                val session = ResearchSession(runId, chatId, snapshot, turnId, authority)
                if (!persistent) {
                    mutable.update { (it + (runId to session)).entries.sortedByDescending { entry -> entry.value.snapshot.updatedAt }.take(24).associate { entry -> entry.toPair() } }
                    return@withLock
                }
                try {
                    check(directory.mkdirs() || directory.isDirectory)
                    val atomic = AtomicFile(file(runId))
                    val data = buildJsonObject {
                        put("runId", runId)
                        put("chatId", chatId)
                        put("turnId", turnId)
                        put("authority", authority)
                        val sourceRefs = snapshot.sources.map { source ->
                            val cacheKey = "$chatId:$turnId:$authority:${source.id}"
                            this@ResearchSessionStore.sourceRefs[cacheKey]?.takeIf { it.first == source }?.let { return@map JsonPrimitive(it.second) }
                            val bytes = source.json().toString().toByteArray()
                            require(bytes.size <= 64 * 1024) { "Research source snapshot is too large" }
                            val name = researchHash(bytes.decodeToString()) + ".source"
                            val destination = File(directory, name)
                            if (!destination.isFile) {
                                check(directory.usableSpace >= bytes.size + 8L * 1024 * 1024) { "Paused for device resources: insufficient storage" }
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
                            this@ResearchSessionStore.sourceRefs[cacheKey] = source to name
                            JsonPrimitive(name)
                        }
                        put("snapshot", kotlinx.serialization.json.JsonObject(snapshot.copy(sources = emptyList(), queries = emptyList()).json() - "sources"))
                        put("storageVersion", 3)
                        put("sourcePages", JsonArray(listOf(JsonPrimitive(writeReferenceTree(sourceRefs)))))
                        put("queryPages", JsonArray(listOf(JsonPrimitive(writeReferenceTree(snapshot.queries.map(::JsonPrimitive))))))
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
                    writeAtomic(
                        File(directory, researchHash("turn:$chatId:$turnId:$authority") + ".cursor"),
                        buildJsonObject {
                            put("chatId", chatId)
                            put("runId", runId)
                        }.toString().toByteArray()
                    )
                    // Retention is incremental; never rescan the corpus per network chunk.
                    val retained = completedFiles ?: directory.listFiles().orEmpty()
                        .filter { it.extension == "json" && read(it)?.snapshot?.complete == true }
                        .associateWith { it.lastModified() }.toMutableMap().also { completedFiles = it }
                    if (snapshot.complete) retained[file(runId)] = System.currentTimeMillis()
                    retained.entries.sortedByDescending { it.value }.drop(48).forEach { (old, _) ->
                        AtomicFile(old).delete()
                        retained.remove(old)
                    }
                    if (sourceRefs.size > 8000) sourceRefs.clear()
                } catch (failure: Exception) {
                    val previouslyRetained = mutable.value.values.filter { it.chatId == chatId && it.turnId == turnId && it.authority == authority }
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

    data class StorageStats(val retainedSources: Int, val bytes: Long, val resumable: List<ResearchSession>)

    suspend fun storageStats(): StorageStats = withContext(Dispatchers.IO) {
        lock.withLock {
            val files = directory.listFiles().orEmpty()
            val resumable = files.asSequence().filter { it.extension == "json" }.mapNotNull(::read).filter { !it.snapshot.complete }.take(24).toList()
            StorageStats(files.count { it.extension == "source" }, files.sumOf { it.length() }, resumable)
        }
    }

    /** User-triggered migration only. Old checkpoints remain untouched for audit/rollback. */
    suspend fun migrateLegacyRun(session: ResearchSession, plan: dev.chungjungsoo.gptmobile.data.model.EffectiveResearchPlan) = withContext(Dispatchers.IO) {
        lock.withLock {
            require(!session.snapshot.complete) { "Completed runs remain historical" }
            require(plan.active)
            val key = session.chatId to session.turnId
            val legacy = File(directory, researchHash("budget:${session.chatId}:${session.turnId}") + ".budget")
            val target = File(directory, researchHash("budget:${session.chatId}:${session.turnId}") + ".ledger")
            require(!target.exists()) { "Run already uses a migrated research policy" }
            require(legacy.isFile && legacy.length() in 1..64 * 1024) { "Legacy checkpoint is unavailable" }
            val saved = Json.parseToJsonElement(AtomicFile(legacy).openRead().use { it.readBytes() }.decodeToString()).jsonObject
            val total = saved.number("total")
            val byteLimit = saved.number("downloadLimit")
            require(total in 1..2400 && byteLimit in 1..512 * 1024 * 1024)
            val old = ResearchBudget(total, downloadLimitBytes = byteLimit)
            old.restore(ResearchBudget.Snapshot(total, saved.number("used"), saved.number("physical"), total - saved.number("used"), ResearchBudget.Phase.entries.associateWith { requireNotNull(saved["phases"]).jsonObject.number(it.name) }, saved.number("downloaded"), byteLimit, saved.number("accounting").takeIf { it in 1..2 } ?: 1))
            val previous = old.snapshot()
            val state = dev.chungjungsoo.gptmobile.data.agent.ResearchRunLedger.State(
                fingerprint = plan.fingerprint,
                limits = plan.limits,
                logical = previous.usedLogical.toLong(),
                physical = previous.physicalRequests.toLong(),
                downloaded = previous.downloadedBytes.toLong()
            )
            dev.chungjungsoo.gptmobile.data.agent.ResearchRunLedger(plan.fingerprint, plan.limits).restore(state)
            val manifestFile = file(session.runId)
            val manifest = Json.parseToJsonElement(AtomicFile(manifestFile).openRead().use { it.readBytes() }.decodeToString()).jsonObject
            val previousMetadata = requireNotNull(manifest["snapshot"]).jsonObject
            val newMetadata = kotlinx.serialization.json.JsonObject(previousMetadata + mapOf("targetSources" to JsonPrimitive(plan.sourceGoal), "phase" to JsonPrimitive("Paused")))
            writeAtomic(manifestFile, kotlinx.serialization.json.JsonObject(manifest + ("snapshot" to newMetadata)).toString().toByteArray())
            writeAtomic(
                target,
                buildJsonObject {
                    put("chatId", session.chatId)
                    put("migratedFrom", previous.accountingVersion)
                    put("state", Json.encodeToJsonElement(dev.chungjungsoo.gptmobile.data.agent.ResearchRunLedger.State.serializer(), state))
                }.toString().toByteArray()
            )
            synchronized(turnBudgets) { turnBudgets.remove(key) }
            mutable.update { it + (session.runId to session.copy(snapshot = session.snapshot.copy(targetSources = plan.sourceGoal, phase = "Paused"))) }
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ResearchPolicy", "legacy_migrated · schema=3 · run=${researchHash(session.runId).take(12)} · policy=${plan.fingerprint.take(12)}")
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
        temporaryBodies.keys.removeAll { it.startsWith("$chatId:") }
        readerHealth.keys.removeAll { it.first == chatId }
        workLeases.keys.removeAll { it.startsWith("$chatId:") }
        sourceRefs.keys.removeAll { it.startsWith("$chatId:") }
        runTurns.entries.removeAll { it.value.first == chatId }
        stoppedTurns.removeAll { it.first == chatId }
        synchronized(turnBudgets) { turnBudgets.keys.removeAll { it.first == chatId } }
        lock.withLock {
            directory.listFiles().orEmpty().filter { read(it)?.chatId == chatId || (it.extension in setOf("budget", "ledger", "cursor") && runCatching { Json.parseToJsonElement(it.readText()).jsonObject.number("chatId") == chatId }.getOrDefault(false)) }.forEach { AtomicFile(it).delete() }
            val retained = directory.listFiles().orEmpty().filter { it.extension == "json" }.flatMap { file ->
                runCatching { references(Json.parseToJsonElement(file.readText()).jsonObject).orEmpty().map { (it as JsonPrimitive).content } }.getOrDefault(emptyList())
            }.toSet()
            directory.listFiles().orEmpty().filter { it.extension == "source" && it.name !in retained }.forEach { AtomicFile(it).delete() }
            val retainedIndexes = mutableSetOf<String>()
            fun markIndex(name: String) {
                if (!name.matches(Regex("[a-f0-9]{64}\\.index")) || !retainedIndexes.add(name)) return
                val file = File(directory, name)
                if (file.isFile) (Json.parseToJsonElement(file.readText()) as JsonArray).forEach { markIndex((it as JsonPrimitive).content) }
            }
            directory.listFiles().orEmpty().filter { it.extension == "json" }.forEach { manifest ->
                val value = Json.parseToJsonElement(manifest.readText()).jsonObject
                listOf("sourcePages", "queryPages").forEach { field -> (value[field] as? JsonArray).orEmpty().forEach { markIndex((it as JsonPrimitive).content) } }
            }
            directory.listFiles().orEmpty().filter { it.extension == "index" && it.name !in retainedIndexes }.forEach { AtomicFile(it).delete() }
            val retainedChunks = retained.flatMap { name ->
                runCatching { Json.parseToJsonElement(File(directory, name).readText()).jsonObject.strings("bodyChunks") }.getOrDefault(emptyList())
            }.toSet()
            directory.listFiles().orEmpty().filter { it.extension == "chunk" && it.name !in retainedChunks }.forEach { AtomicFile(it).delete() }
            mutable.update { sessions -> sessions.filterValues { it.chatId != chatId } }
        }
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
    }

    private fun writeReferenceTree(values: List<JsonPrimitive>): String {
        if (values.size > 100) return writeReferenceTree(values.chunked(100).map { JsonPrimitive(writeReferenceTree(it)) })
        val page = JsonArray(values)
        val bytes = page.toString().toByteArray()
        val name = researchHash(bytes.decodeToString()) + ".index"
        val destination = File(directory, name)
        if (!destination.isFile) writeAtomic(destination, bytes)
        return name
    }

    private fun readReferenceTree(name: String, depth: Int = 0): List<JsonPrimitive> {
        require(depth <= 8 && name.matches(Regex("[a-f0-9]{64}\\.index")))
        val page = File(directory, name)
        require(page.length() in 1..64 * 1024)
        val text = AtomicFile(page).openRead().use { it.readBytes() }.decodeToString()
        require(researchHash(text) + ".index" == name)
        val rows = Json.parseToJsonElement(text) as JsonArray
        require(rows.size <= 100)
        return rows.flatMap { entry ->
            val value = entry as JsonPrimitive
            if (value.content.matches(Regex("[a-f0-9]{64}\\.index"))) readReferenceTree(value.content, depth + 1) else listOf(value)
        }
    }

    private fun references(value: kotlinx.serialization.json.JsonObject): JsonArray? {
        val pages = value["sourcePages"] as? JsonArray ?: return value["sourceRefs"] as? JsonArray
        require(value.number("storageVersion") == 3)
        return JsonArray(pages.flatMap { readReferenceTree((it as JsonPrimitive).content) })
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
        val metadata = requireNotNull(value["snapshot"]).jsonObject
        val queryPages = value["queryPages"] as? JsonArray
        val raw = if (queryPages == null) metadata else kotlinx.serialization.json.JsonObject(metadata + ("queries" to JsonArray(queryPages.flatMap { readReferenceTree((it as JsonPrimitive).content) })))
        val references = references(value)
        val restored = if (references == null) {
            raw
        } else {
            val rows = references.map { entry ->
                val name = (entry as? JsonPrimitive)?.contentOrNull.orEmpty()
                require(name.matches(Regex("[a-f0-9]{64}\\.source")))
                val sourceBytes = AtomicFile(File(directory, name)).openRead().use { input ->
                    require(input.channel.size() <= 64 * 1024)
                    input.readBytes()
                }
                require(researchHash(sourceBytes.decodeToString()) + ".source" == name)
                Json.parseToJsonElement(sourceBytes.decodeToString()).jsonObject.also { source ->
                    source.strings("bodyChunks").forEach { chunkName ->
                        require(chunkName.matches(Regex("[a-f0-9]{64}\\.chunk")))
                        val chunk = File(directory, chunkName)
                        require(chunk.isFile && chunk.length() <= 64 * 1024 && researchHash(chunk.readText()) + ".chunk" == chunkName)
                    }
                }
            }
            kotlinx.serialization.json.JsonObject(raw + ("sources" to JsonArray(rows)))
        }
        ResearchSession(runId, value.number("chatId"), requireNotNull(ResearchSnapshot.parse(restored)), value.text("turnId").ifBlank { runId }, value.text("authority").ifBlank { "legacy" })
    }.getOrNull()
}
