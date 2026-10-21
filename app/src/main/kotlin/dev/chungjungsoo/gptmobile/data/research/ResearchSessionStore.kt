package dev.chungjungsoo.gptmobile.data.research

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class ResearchSession(val runId: String, val chatId: Int, val snapshot: ResearchSnapshot, val turnId: String = runId, val authority: String = "legacy")

/** App-private bounded history, included in complete backups; temporary chats stay in RAM. */
@Singleton
class ResearchSessionStore @Inject constructor(@ApplicationContext context: Context, private val featureDatabase: dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase? = null, private val featureSettings: dev.chungjungsoo.gptmobile.data.repository.SettingRepository? = null) {
    private val appContext = context
    private val directory = File(context.filesDir, "research-history")
    private val taskStore = ResearchTaskStore(directory)
    private val sessionIndex = ResearchSessionIndex(directory)
    private val lock = Mutex()
    private val readerHealth = ConcurrentHashMap<Pair<Int, String>, ResearchReaderHealth>()
    private val turnBudgets = linkedMapOf<Pair<Int, String>, Pair<Long, ResearchBudget>>()
    private val taskLocks = Array(64) { Mutex() }
    private val planLocks = Array(64) { Mutex() }
    private val stops = ConcurrentHashMap.newKeySet<String>()
    private val stopOrigins = ConcurrentHashMap<String, String>()
    private val stoppedTurns = ConcurrentHashMap.newKeySet<Pair<Int, String>>()
    private val runTurns = ConcurrentHashMap<String, Pair<Int, String>>()
    private val generations = ConcurrentHashMap<Pair<Int, String>, Long>()
    private val resumeControls = ConcurrentHashMap<Pair<Int, String>, String>()

    private fun fenceFile(chatId: Int, turnId: String) = File(directory, researchHash("generation:$chatId:$turnId") + ".fence")

    @Synchronized private fun currentGeneration(chatId: Int, turnId: String, persistent: Boolean): Long {
        val key = chatId to turnId
        if (!persistent) return generations[key] ?: 0L
        val file = fenceFile(chatId, turnId)
        val saved = if (file.isFile) Json.parseToJsonElement(AtomicFile(file).openRead().use { it.readBytes() }.decodeToString()).jsonObject else null
        val fence = saved?.text("fence")?.toLongOrNull() ?: if (saved == null) 0L else error("Invalid generation checkpoint")
        require(fence >= 0 && (saved == null || saved.number("chatId") == chatId))
        generations[key] = maxOf(generations[key] ?: 0L, fence)
        return generations.getValue(key)
    }

    /** Explicit Continue advances durable ownership without resetting budget or authority. */
    suspend fun resume(chatId: Int, turnId: String, controlMessageId: Int, persistent: Boolean = true) = resumeGeneration(chatId, turnId, controlMessageId.toString(), persistent)

    suspend fun resumeGeneration(chatId: Int, turnId: String, controlId: String, persistent: Boolean = true) = withContext(Dispatchers.IO) {
        lock.withLock { advanceGeneration(chatId, turnId, controlId, persistent) }
    }

    @Synchronized private fun advanceGeneration(chatId: Int, turnId: String, controlId: String, persistent: Boolean) {
        check(chatId !in deletedChats) { "Conversation was deleted" }
        val key = chatId to turnId
        val current = currentGeneration(chatId, turnId, persistent)
        val file = fenceFile(chatId, turnId)
        val savedControl = if (persistent && file.isFile) Json.parseToJsonElement(file.readText()).jsonObject.let { it.text("controlId").ifBlank { it.text("controlMessageId") } } else null
        if (resumeControls[key] == controlId || savedControl == controlId) return
        val next = Math.addExact(current, 1L)
        if (persistent) {
            check(directory.mkdirs() || directory.isDirectory)
            writeAtomic(
                file,
                buildJsonObject {
                    put("chatId", chatId)
                    put("fence", next)
                    put("controlId", controlId)
                }.toString().toByteArray()
            )
        }
        generations[key] = next
        resumeControls[key] = controlId
        runTurns.filterValues { it == key }.keys.forEach {
            stops += it
            stopOrigins[it] = "SUPERSEDED_GENERATION"
        }
        stoppedTurns.remove(key)
    }
    private data class Lease(val owner: String, val until: Long)
    private val workLeases = ConcurrentHashMap<String, Lease>()
    private val sourceRefs = ConcurrentHashMap<String, Pair<ResearchSource, String>>()
    private var completedFiles: MutableMap<File, Long>? = null
    private val temporaryBodies = ConcurrentHashMap<String, String>()
    private val deletedChats = ConcurrentHashMap.newKeySet<Int>()
    private val mutable = MutableStateFlow<Map<String, ResearchSession>>(emptyMap())
    val sessions = mutable.asStateFlow()

    /** Expose only version-bound appraisal fields whose exact supporting quotes remain captured. */
    suspend fun deliveryStudyFields(runId: String): Map<String, JsonObject> = withContext(Dispatchers.IO) {
        val session = sessions.value[runId] ?: return@withContext emptyMap()
        val database = featureDatabase ?: return@withContext emptyMap()
        if (session.chatId in deletedChats) return@withContext emptyMap()
        val dao = database.research()
        val result = mutableMapOf<String, JsonObject>()
        for (source in session.snapshot.sources.filter { it.readable }.take(64)) {
            val aliases = listOfNotNull(source.doi.takeIf(String::isNotBlank)?.let { "doi:${SourceIdentity.normalizeDoi(it)}" }, "url:${source.canonicalUrl}")
            val ids = aliases.mapNotNull { dao.aliasWork("${researchHash(session.authority)}|$it") }.distinct().ifEmpty { aliases.mapNotNull { dao.aliasWork("public|$it") }.distinct() }
            if (ids.size != 1) continue
            val version = dao.versions(ids.single()).singleOrNull { it.sourceVersion == source.versionId && (it.publicSource || it.authority == session.authority) && it.bodyChunksJson == kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), source.bodyChunks) } ?: continue
            val passages = dao.passages(version.versionId).filter { it.quote in source.passage && researchHash(it.quote) == it.textHash }.map { it.passageId }.toSet()
            val fields = dao.fields(version.versionId).filter { field ->
                val refs = runCatching { Json.decodeFromString<List<String>>(field.passageIdsJson) }.getOrDefault(emptyList())
                refs.isNotEmpty() && refs.all { it in passages }
            }
            result[source.id] = buildJsonObject {
                put(
                    "fields",
                    JsonArray(
                        fields.map { field ->
                            buildJsonObject {
                                put("name", field.name)
                                put("value", field.value)
                                put("unit", field.unit)
                                put("missingness", field.missingness.name)
                                put("outcomeKey", field.outcomeKey)
                            }
                        }
                    )
                )
            }
        }
        result
    }

    suspend fun acceptTask(chatId: Int, rootWorkId: String, sourceMessageId: Int, task: String, participants: List<String>, primary: String?, settingsRevision: String, authorityRevision: String, persistent: Boolean): AcceptedResearchTask = withContext(Dispatchers.IO) {
        check(chatId !in deletedChats) { "Conversation was deleted" }
        taskStore.accept(AcceptedResearchTask(taskId = researchHash("task:$rootWorkId"), rootWorkId = rootWorkId, chatId = chatId, originatingUserMessageId = sourceMessageId, taskText = task, intent = if (isResearchConversationOnly(task)) "NOT_NEEDED" else "REQUIRED", participantUids = participants.distinct(), primaryUid = primary, settingsRevision = settingsRevision, sourceAuthorityRevision = authorityRevision, acceptedAt = System.currentTimeMillis()), persistent)
    }
    suspend fun acceptedTask(rootWorkId: String, persistent: Boolean = true): AcceptedResearchTask? = withContext(Dispatchers.IO) { taskStore.accepted(rootWorkId, persistent) }
    suspend fun evidenceManifest(rootWorkId: String, persistent: Boolean = true): ResearchEvidenceManifest? = withContext(Dispatchers.IO) { taskStore.manifest(rootWorkId, persistent) }
    suspend fun exportAcceptedTasks(chatIds: Set<Int>): List<AcceptedResearchTask> = withContext(Dispatchers.IO) { taskStore.exportTasks(chatIds) }
    suspend fun restoreAcceptedTasks(tasks: List<AcceptedResearchTask>) = withContext(Dispatchers.IO) {
        tasks.filter { it.chatId !in deletedChats }.forEach { task -> taskStore.accept(task, true) }
    }
    suspend fun recordResume(rootWorkId: String, actionId: String, stage: String, destination: String, policyRevision: String, persistent: Boolean) = withContext(Dispatchers.IO) {
        val task = taskStore.accepted(rootWorkId, persistent) ?: return@withContext
        val scopes = taskStore.manifest(rootWorkId, persistent)?.revisions.orEmpty().mapTo(linkedSetOf()) { it.authority }
        val candidates = mutable.value.values.filter { it.turnId == rootWorkId } + taskStore.retainedEvidence(rootWorkId, scopes, persistent)
        val strongest = richestResearchSnapshot(candidates.map { it.snapshot })
        val latest = candidates.firstOrNull { it.snapshot == strongest }
        val revision = latest?.snapshot?.let { researchHash(it.json().toString()) }.orEmpty()
        taskStore.resume(ResearchResumeRequest(actionId, task.taskId, rootWorkId, latest?.runId.orEmpty(), stage, destination, revision, policyRevision, System.currentTimeMillis()), persistent)
    }

    fun stop(runId: String) {
        stopOrigins[runId] = "USER_STOP_RESEARCH_AND_SUMMARIZE"
        stops += runId
        runTurns[runId]?.let { stoppedTurns += it }
    }
    fun journal(runId: String, chatId: Int, persistent: Boolean, turnId: String = runId, authority: String = "legacy", shareEnabled: Boolean = true, ownerScope: String = runId, publicEvidence: Boolean = false, acceptedTools: dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig? = null): ResearchJournal = object : ResearchJournal {
        private val createdAt = System.currentTimeMillis()
        private var archiveEpoch: Long? = null
        private val archivedRevisions = mutableMapOf<String, String>()
        private val generation = currentGeneration(chatId, turnId, persistent)
        init {
            runTurns[runId] = chatId to turnId
        }
        override fun scoped(authority: String, sharePublicEvidence: Boolean): ResearchJournal = journal(runId, chatId, persistent, turnId, if (shareEnabled && sharePublicEvidence) authority else "$authority:$ownerScope", shareEnabled, ownerScope, sharePublicEvidence, acceptedTools)
        override suspend fun featurePolicy() = featureSettings?.getFeatureSettings()?.researchFeature ?: dev.chungjungsoo.gptmobile.data.research.feature.ResearchFeatureSettings()
        override suspend fun archiveLeads(task: String): List<ResearchSource> {
            val database = featureDatabase ?: return emptyList()
            val features = featureSettings?.getFeatureSettings() ?: return emptyList()
            if (!persistent || !features.researchFeature.enabled || !features.researchFeature.archivePreflight || !features.isToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.DEEP_RESEARCH) || stopRequested()) return emptyList()
            val dao = database.research()
            val words = task.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length in 3..80 }.distinct().take(5)
            return words.flatMap { dao.page(it, "", features.researchFeature.peerReviewedOnly, 12, 0) }.distinctBy { it.workId }.filter { row -> dao.version(row.latestVersionId)?.let { it.publicSource || it.authority == authority } == true }.take(30).map { row ->
                val aliases = dao.aliases(row.workId).map { it.alias.substringAfter('|') }
                fun id(kind: String) = aliases.firstOrNull { it.startsWith("$kind:") }?.substringAfter(':').orEmpty()
                ResearchSource(
                    "lead", row.canonicalUrl, row.title, status = "Discovered", engines = listOf("archive-preflight"), doi = id("doi"), pmid = id("pmid"), pmcid = id("pmcid"), publicationYear = row.publicationYear,
                    paperId = aliases.firstOrNull { it.startsWith("doi:") || it.startsWith("pmid:") || it.startsWith("pmcid:") || it.startsWith("arxiv:") }.orEmpty()
                )
            }
        }
        override fun sourceId(identity: String, fallback: String) = "S" + researchHash(identity).take(16)
        private fun workKey(key: String) = "$chatId:$turnId:$authority:$key"
        override fun claimWork(key: String, leaseMillis: Long): Boolean {
            if (stopRequested()) return false
            val now = System.nanoTime() / 1_000_000
            val lease = workLeases.compute(workKey(key)) { _, old -> if (old == null || old.until <= now) Lease(runId, now + leaseMillis.coerceIn(1000, 300_000)) else old }
            return lease?.owner == runId
        }
        override fun claimPublicWork(key: String, leaseMillis: Long): Boolean {
            if (!shareEnabled) return claimWork(key, leaseMillis)
            if (stopRequested()) return false
            val now = System.nanoTime() / 1_000_000
            val lease = workLeases.compute("$chatId:$turnId:public:$key") { _, old -> if (old == null || old.until <= now) Lease(runId, now + leaseMillis.coerceIn(1000, 300_000)) else old }
            return lease?.owner == runId
        }
        override fun releasePublicWork(key: String) {
            if (!shareEnabled) return releaseWork(key)
            workLeases.computeIfPresent("$chatId:$turnId:public:$key") { _, old -> old.takeUnless { it.owner == runId } }
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
            ledger.configureAcquisition(plan.sourceGoal)
            ResearchBudget(1, ledger = ledger).also { turnBudgets[key] = System.currentTimeMillis() to it }
        }
        override fun cancellationOrigin(): String = if (chatId in deletedChats) {
            "CHAT_DELETED"
        } else if (generation != currentGeneration(chatId, turnId, persistent)) {
            "SUPERSEDED_GENERATION"
        } else {
            stopOrigins[runId] ?: "PARENT_CANCELLED"
        }
        override fun stopRequested() = runId in stops || chatId in deletedChats || (chatId to turnId) in stoppedTurns || generation != currentGeneration(chatId, turnId, persistent)
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
                check(!persistent || !pointer.isFile || pointed != null) { "Saved research checkpoint is corrupt or incomplete. Evidence is preserved; review the saved run before restarting acquisition." }
                val persisted = if (pointed != null) {
                    listOf(pointed)
                } else if (persistent && inMemory.isEmpty()) {
                    sessionIndex.entries(chatId, setOf(turnId), setOf(authority)).mapNotNull { read(it.file) }
                } else {
                    emptyList()
                }
                val retained = taskStore.retainedEvidence(turnId, setOf(authority), persistent)
                val own = richestResearchSnapshot((persisted + inMemory + retained).filter { it.chatId == chatId && it.turnId == turnId && it.authority == authority && normalizedTask(it.snapshot.task) == normalizedTask(task) }.map { it.snapshot })
                val siblings = if (shareEnabled) mutable.value.values.filter { it.chatId == chatId && it.turnId == turnId && it.authority != authority && normalizedTask(it.snapshot.task) == normalizedTask(task) } else emptyList()
                val public = authorizedResearchTransfer(siblings, null).filter { it.sources.isNotEmpty() }
                if (public.isEmpty()) own else richestResearchSnapshot(public + listOfNotNull(own))

            }
        }
        override suspend fun <T> withTaskLock(task: String, block: suspend () -> T): T {
            val key = "$chatId:$turnId:$authority:${normalizedTask(task)}"
            val locks = if (task.startsWith("delegation-plan:")) planLocks else taskLocks
            val taskLock = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]
            return taskLock.withLock { block() }
        }
        override suspend fun retainDeliveryReceipt(receipt: dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt): Boolean = withContext(Dispatchers.IO) {
            lock.withLock {
                if (stopRequested() || generation != currentGeneration(chatId, turnId, persistent)) return@withLock false
                val accepted = taskStore.accepted(turnId, persistent) ?: return@withLock false
                if (accepted.chatId != chatId) return@withLock false
                taskStore.retainDelivery(turnId, runId, authority, generation, receipt, persistent)
            }
        }

        override suspend fun retainPartial(stage: String, profileUid: String, text: String): String? = withContext(Dispatchers.IO) {
            if (!persistent || text.isBlank()) return@withContext null
            lock.withLock {
                if (stopRequested() || generation != currentGeneration(chatId, turnId, persistent)) return@withLock null
                check(directory.mkdirs() || directory.isDirectory)
                var retained = text.take(120 * 1024)
                fun payload() = buildJsonObject {
                    put("schemaVersion", 1)
                    put("chatId", chatId)
                    put("runId", runId)
                    put("turnId", turnId)
                    put("authority", authority)
                    put("generationFence", generation)
                    put("stage", stage.take(96))
                    put("profileUid", profileUid.take(256))
                    put("accepted", false)
                    put("truncated", retained.length < text.length)
                    put("text", retained)
                }.toString().toByteArray()
                var bytes = payload()
                while (bytes.size > 128 * 1024 && retained.isNotEmpty()) {
                    retained = retained.dropLast(maxOf(1, retained.length / 8))
                    if (retained.lastOrNull()?.isHighSurrogate() == true) retained = retained.dropLast(1)
                    bytes = payload()
                }
                val name = researchHash("$chatId:$turnId:$authority:$runId:$generation:$stage:$profileUid") + ".partial"
                writeAtomic(File(directory, name), bytes)
                taskStore.retainPartial(turnId, name, persistent)
                name
            }
        }

        override suspend fun retainBody(text: String): List<String> = withContext(Dispatchers.IO) {
            lock.withLock {
                check(!stopRequested()) { "Research stopped" }
                researchTextChunks(text).map { chunk ->
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
        private suspend fun indexCommittedSources(snapshot: ResearchSnapshot): Boolean {
            var examinationScheduled = false
            try {
                featureDatabase?.let { database ->
                    val settings = featureSettings?.getFeatureSettings()
                    val dao = database.research()
                    val epoch = archiveEpoch ?: (dao.sequence()?.archiveEpoch ?: 0L).also { archiveEpoch = it }
                    val eligible = settings?.researchFeature?.enabled == true && settings.isToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.DEEP_RESEARCH) && (dao.deleted("archive-clear") ?: 0) < createdAt
                    snapshot.sources.forEach { source ->
                        val receiptId = researchHash("$runId:${source.id}")
                        val needed = snapshot.claims.any { it.sourceId == source.id } || Regex("(?i)sample|participant|randomi[sz]|causal|efficacy|confidence interval|trial|systematic review").containsMatchIn(source.title + " " + source.publicationType + " " + source.passage)
                        val revision = researchHash(source.json().toString() + "|needed=$needed")
                        if (archivedRevisions[source.id] != revision) {
                            val outbox = dev.chungjungsoo.gptmobile.data.research.feature.ResearchOutbox(
                                "ingest:$receiptId",
                                buildJsonObject {
                                    put("runId", runId)
                                    put("chatId", chatId)
                                    put("turnId", turnId)
                                    put("authority", authority)
                                    put("publicEvidence", publicEvidence)
                                    put("epoch", epoch)
                                    put("source", source.json())
                                }.toString(),
                                System.currentTimeMillis()
                            )
                            dao.outbox(outbox)
                            dao.saveReceipt(dev.chungjungsoo.gptmobile.data.research.feature.ResearchSaveReceipt(receiptId, runId, source.id, chatId, disposition = "SAVE_PENDING", updatedAt = System.currentTimeMillis()))
                            try {
                                val body = if (source.bodyChunks.isEmpty()) {
                                    source.passage
                                } else {
                                    source.bodyChunks.joinToString("") { name ->
                                        require(name.matches(Regex("[a-f0-9]{64}\\.chunk")))
                                        val text = File(directory, name).readText()
                                        require(researchHash(text) + ".chunk" == name)
                                        text
                                    }
                                }
                                val captured = dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveIngestor.capture(database, source, authority, publicEvidence || source.publicCapture && source.retrievalReceipt.isNotBlank(), chatId, turnId, eligible, epoch, researchHash(body), body.toByteArray().size.toLong(), automaticOrganization = settings?.researchFeature?.automaticOrganization == true, unexaminedLimit = settings?.researchFeature?.unexaminedStudyLimit ?: 1000)
                                val saved = dao.membership(captured.workId) != null
                                dao.saveReceipt(
                                    dev.chungjungsoo.gptmobile.data.research.feature.ResearchSaveReceipt(
                                        receiptId,
                                        runId,
                                        source.id,
                                        chatId,
                                        captured.workId,
                                        captured.versionId,
                                        if (saved) {
                                            "ARCHIVED"
                                        } else if (dao.deleted("archive:${captured.workId}") != null || (dao.sequence()?.archiveEpoch ?: 0L) != epoch) {
                                            "SUPPRESSED_BY_EXPLICIT_DELETION"
                                        } else {
                                            "NOT_ELIGIBLE"
                                        },
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                                dao.acknowledge(outbox.id, outbox.createdAt)
                                if (eligible && acceptedTools != null && (source.paperId.isNotBlank() || source.doi.isNotBlank() || source.pmid.isNotBlank() || source.pmcid.isNotBlank())) {
                                    val selection = settings?.conversationResearch?.get(chatId) ?: acceptedTools.research
                                    val research = selection?.academicEligibleByUi == true
                                    val request = dev.chungjungsoo.gptmobile.data.research.feature.ExaminationRequest(captured.workId, chatId = chatId, versionId = captured.versionId, authority = authority, origin = if (research) dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOrigin.AUTHORIZED_RESEARCH else dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOrigin.AUTOMATIC_CHAT, rootWorkId = turnId, parentRunId = runId)
                                    val scheduled = dev.chungjungsoo.gptmobile.data.research.work.ScheduledExamination(request, acceptedTools, needed)
                                    database.withTransaction {
                                        val examinationId = "auto-examine:$chatId:${captured.versionId}"
                                        val existing = dao.outboxById(examinationId)
                                        if (existing == null) {
                                            dao.outbox(dev.chungjungsoo.gptmobile.data.research.feature.ResearchOutbox(examinationId, Json.encodeToString(scheduled), System.currentTimeMillis()))
                                            examinationScheduled = true
                                        } else if (needed) {
                                            // Progress saves must preserve blocked/paused state and its accepted owner.
                                            val previous = Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.work.ScheduledExamination>(existing.operation)
                                            if (!previous.needed) {
                                                dao.outbox(existing.copy(operation = Json.encodeToString(previous.copy(needed = true))))
                                                examinationScheduled = true
                                            }
                                        }
                                    }
                                }
                                archivedRevisions[source.id] = revision
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                throw cancel
                            } catch (error: Exception) {
                                dao.saveReceipt(dev.chungjungsoo.gptmobile.data.research.feature.ResearchSaveReceipt(receiptId, runId, source.id, chatId, disposition = "SAVE_FAILED", reason = "ARCHIVE_WRITE_FAILED:${error.javaClass.simpleName}", updatedAt = System.currentTimeMillis()))
                                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ResearchArchive", "SAVE_FAILED · receiptId=$receiptId · runId=$runId · sourceId=${source.id} · reason=ARCHIVE_WRITE_FAILED", "W")
                            }
                        }
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Research", "Archive index update failed; original run evidence remains retained", "W")
            }
            return examinationScheduled
        }

        override suspend fun bindTask(snapshot: ResearchSnapshot): ResearchSnapshot = withContext(Dispatchers.IO) {
            val accepted = taskStore.accepted(turnId, persistent) ?: return@withContext snapshot
            require(snapshot.taskId.isBlank() || snapshot.taskId == accepted.taskId) { "RESEARCH_CHECKPOINT_TASK_MISMATCH" }
            snapshot.copy(task = accepted.taskText, taskId = accepted.taskId, rootWorkId = accepted.rootWorkId, originatingUserMessageId = accepted.originatingUserMessageId, taskChatId = accepted.chatId)
        }

        override suspend fun save(snapshot: ResearchSnapshot) = withContext(Dispatchers.IO) {
            lock.withLock {
                if (chatId in deletedChats || generation != currentGeneration(chatId, turnId, persistent)) return@withLock
                val previousSourceCount = mutable.value[runId]?.snapshot?.sources?.size ?: 0
                val priorPlan = mutable.value[runId]?.takeIf { it.authority == authority }?.snapshot?.delegationPlan ?: if (persistent) read(file(runId))?.takeIf { it.authority == authority }?.snapshot?.delegationPlan else null
                val inherited = mutable.value.values.filter { it.chatId == chatId && it.turnId == turnId && it.authority == authority }.map { it.snapshot }
                val merged = mergeResearchSnapshots(inherited, if (snapshot.delegationPlan == null) snapshot.copy(delegationPlan = priorPlan) else snapshot)
                val accepted = taskStore.accepted(turnId, persistent)
                val snapshot = bindTask(merged)
                accepted?.let { accepted ->
                    taskStore.checkpoint(accepted, runId, authority, generation, snapshot, persistent)
                }
                val session = ResearchSession(runId, chatId, snapshot, turnId, authority)
                if (!persistent) {
                    mutable.update { boundedSessions(it + (runId to session)) }
                    return@withLock
                }
                try {
                    check(directory.mkdirs() || directory.isDirectory)
                    val atomic = AtomicFile(file(runId))
                    val data = buildJsonObject {
                        put("runId", runId)
                        put("chatId", chatId)
                        put("turnId", turnId)
                        put("rootWorkId", turnId)
                        put("generationId", runId)
                        put("generationFence", generation)
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
                            trimSourceCache()
                            JsonPrimitive(name)
                        }
                        val claimRefs = snapshot.claims.map { claim ->
                            val bytes = claim.json().toString().toByteArray()
                            require(bytes.size <= 16 * 1024) { "Research claim is oversized" }
                            val name = researchHash(bytes.decodeToString()) + ".claim"
                            val destination = File(directory, name)
                            if (!destination.isFile) writeAtomic(destination, bytes)
                            JsonPrimitive(name)
                        }
                        put("snapshot", kotlinx.serialization.json.JsonObject(snapshot.copy(sources = emptyList(), queries = emptyList(), claims = emptyList()).json() - "sources" - "claims"))
                        put("storageVersion", 4)
                        put("claimPages", JsonArray(listOf(JsonPrimitive(writeReferenceTree(claimRefs)))))
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
                    writeAtomic(
                        File(directory, researchHash("turn:$chatId:$turnId:$authority") + ".cursor"),
                        buildJsonObject {
                            put("chatId", chatId)
                            put("runId", runId)
                        }.toString().toByteArray()
                    )
                    sessionIndex.record(session, file(runId))
                    val examinationScheduled = indexCommittedSources(snapshot)
                    mutable.update { boundedSessions(it + (runId to session)) }
                    if (acceptedTools != null && (examinationScheduled || snapshot.complete || snapshot.sources.size != previousSourceCount)) dev.chungjungsoo.gptmobile.data.research.work.ResearchExaminationWorker.enqueue(appContext)
                    // Retention is incremental; never rescan the corpus per network chunk.
                    val retained = completedFiles ?: sessionIndex.entries().filter { it.complete }
                        .associate { it.file to it.updatedAt }.toMutableMap().also { completedFiles = it }
                    if (snapshot.complete) retained[file(runId)] = System.currentTimeMillis()
                    retained.entries.sortedByDescending { it.value }.drop(48).forEach { (old, _) ->
                        AtomicFile(old).delete()
                        sessionIndex.remove(old)
                        retained.remove(old)
                    }
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
                    mutable.update { boundedSessions(it + (runId to session.copy(snapshot = partial))) }
                    throw failure
                }
            }
        }
    }

    data class StorageStats(val retainedSources: Int, val bytes: Long, val resumable: List<ResearchSession>)

    suspend fun storageStats(): StorageStats = withContext(Dispatchers.IO) {
        lock.withLock {
            val files = directory.listFiles().orEmpty()
            val resumable = boundedSessions(sessionIndex.entries().asSequence().filter { !it.complete }.take(24).mapNotNull { read(it.file) }.associateBy { it.runId }).values.toList()
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

    suspend fun migrateFeatureHistory(allowedChats: Set<Int>) = withContext(Dispatchers.IO) {
        val database = featureDatabase ?: return@withContext
        lock.withLock {
            sessionIndex.entries().filter { it.chatId in allowedChats }.forEach { entry ->
                val session = read(entry.file) ?: return@forEach
                if (session.chatId !in allowedChats || database.research().deleted("chat:${session.chatId}") != null) return@forEach
                val receipt = "history:" + researchHash("${session.runId}|${session.authority}|${session.snapshot.updatedAt}")
                if (database.research().imported(receipt)) return@forEach
                session.snapshot.sources.forEach { source ->
                    val body = if (source.bodyChunks.isEmpty()) {
                        source.passage
                    } else {
                        buildString {
                            source.bodyChunks.forEach { name ->
                                require(name.matches(Regex("[a-f0-9]{64}\\.chunk")))
                                val chunk = File(directory, name)
                                require(chunk.isFile && chunk.length() <= 128 * 1024)
                                val text = AtomicFile(chunk).openRead().use { it.readBytes().decodeToString(throwOnInvalidSequence = true) }
                                require(researchHash(text) + ".chunk" == name)
                                append(text)
                            }
                        }
                    }
                    // Old manifests cannot prove current global-public authority. Keep their original chat scope.
                    dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveIngestor.capture(
                        database, source, session.authority, false, session.chatId, session.runId, false,
                        database.research().sequence()?.archiveEpoch ?: 0, researchHash(body), body.toByteArray().size.toLong()
                    )
                }
                database.research().importReceipt(dev.chungjungsoo.gptmobile.data.research.feature.ResearchImportReceipt(receipt, database.research().sequence()?.archiveEpoch ?: 0, System.currentTimeMillis()))
            }
        }
    }

    suspend fun loadChat(chatId: Int) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (chatId in deletedChats) return@withLock
            val restored = linkedMapOf<String, ResearchSession>()
            var bytes = 0L
            for (entry in sessionIndex.entries(chatId).take(24)) {
                val session = read(entry.file) ?: continue
                val cost = retainedBytes(session)
                if (bytes + cost > SESSION_CACHE_BYTES) continue
                restored[session.runId] = session
                bytes += cost
            }
            mutable.update { current -> boundedSessions(restored + current, chatId) }
        }
    }

    suspend fun exportChat(chatId: Int): List<ResearchSession> = exportSelected(chatId, null, null)

    suspend fun exportTurn(chatId: Int, turnIds: Set<String>, allowedAuthorities: Set<String>? = null): List<ResearchSession> =
        exportSelected(chatId, turnIds, allowedAuthorities)

    private suspend fun exportSelected(chatId: Int, turnIds: Set<String>?, authorities: Set<String>?): List<ResearchSession> = withContext(Dispatchers.IO) {
        lock.withLock {
            if (chatId in deletedChats) return@withLock emptyList()
            val stored = sessionIndex.entries(chatId, turnIds, authorities).mapNotNull { read(it.file) }
            val memory = mutable.value.filterValues { it.chatId == chatId && (turnIds == null || it.turnId in turnIds) && (authorities == null || it.authority in authorities) }
            val roots = turnIds ?: (stored.map { it.turnId } + memory.values.map { it.turnId }).toSet()
            val retained = roots.flatMap { root ->
                val scopes = authorities ?: taskStore.manifest(root, true)?.revisions.orEmpty().mapTo(linkedSetOf()) { it.authority }
                taskStore.retainedEvidence(root, scopes, true) + taskStore.retainedEvidence(root, scopes, false)
            }
            (stored + memory.values + retained).groupBy { it.runId to it.authority }.values.map { versions ->
                val strongest = requireNotNull(richestResearchSnapshot(versions.map { it.snapshot }))
                versions.first().copy(snapshot = strongest)
            }.sortedBy { it.snapshot.updatedAt }
        }
    }

    /** The export consumer releases each session before the next evidence body is decoded. */
    suspend fun streamChat(chatId: Int, consume: suspend (ResearchSession) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (chatId in deletedChats) return@withLock
            val memory = mutable.value.filterValues { it.chatId == chatId }
            val seen = hashSetOf<String>()
            for (entry in sessionIndex.entries(chatId).asReversed()) {
                val session = memory[entry.runId] ?: read(entry.file) ?: continue
                seen += session.runId
                consume(session)
            }
            memory.values.filter { it.runId !in seen }.sortedBy { it.snapshot.updatedAt }.forEach { consume(it) }
        }
    }

    suspend fun deleteChat(chatId: Int) = withContext(Dispatchers.IO) {
        featureDatabase?.let { database ->
            database.withTransaction {
                val dao = database.research()
                dao.tombstone(dev.chungjungsoo.gptmobile.data.research.feature.ResearchDeletionTombstone("chat:$chatId", System.currentTimeMillis()))
                dao.deleteBindings(chatId)
                dao.deleteReportSections(chatId)
                dao.deleteChatRoots("$chatId:")
                dao.chatJobs(chatId).forEach { job ->
                    dao.job(job.copy(stage = dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.CANCELED, terminalReason = "Conversation deleted", lease = job.lease + 1))
                    dao.removeRoots("job", job.jobId)
                }
            }
        }
        deletedChats += chatId
        taskStore.clearChat(chatId)
        temporaryBodies.keys.removeAll { it.startsWith("$chatId:") }
        readerHealth.keys.removeAll { it.first == chatId }
        workLeases.keys.removeAll { it.startsWith("$chatId:") }
        sourceRefs.keys.removeAll { it.startsWith("$chatId:") }
        runTurns.entries.removeAll { it.value.first == chatId }
        stoppedTurns.removeAll { it.first == chatId }
        synchronized(turnBudgets) { turnBudgets.keys.removeAll { it.first == chatId } }
        lock.withLock {
            directory.listFiles().orEmpty().filter { (it.extension == "json" && sessionIndex.metadata(it)?.chatId == chatId) || (it.extension in setOf("budget", "ledger", "cursor", "fence", "partial") && it.length() <= 256 * 1024 && runCatching { Json.parseToJsonElement(it.readText()).jsonObject.number("chatId") == chatId }.getOrDefault(false)) }.forEach { AtomicFile(it).delete(); sessionIndex.remove(it) }
            val retained = directory.listFiles().orEmpty().filter { it.extension == "json" }.flatMap { file ->
                runCatching { references(Json.parseToJsonElement(file.readText()).jsonObject).orEmpty().map { (it as JsonPrimitive).content } }.getOrDefault(emptyList())
            }.toSet()
            directory.listFiles().orEmpty().filter { it.extension == "source" && it.name !in retained }.forEach { AtomicFile(it).delete() }
            val retainedClaims = directory.listFiles().orEmpty().filter { it.extension == "json" }.flatMap { manifest ->
                runCatching {
                    val value = Json.parseToJsonElement(manifest.readText()).jsonObject
                    (value["claimPages"] as? JsonArray).orEmpty().flatMap { readReferenceTree((it as JsonPrimitive).content) }.map { it.content }
                }.getOrDefault(emptyList())
            }.toSet()
            directory.listFiles().orEmpty().filter { it.extension == "claim" && it.name !in retainedClaims }.forEach { AtomicFile(it).delete() }
            val retainedIndexes = mutableSetOf<String>()
            fun markIndex(name: String) {
                if (!name.matches(Regex("[a-f0-9]{64}\\.index")) || !retainedIndexes.add(name)) return
                val file = File(directory, name)
                if (file.isFile) (Json.parseToJsonElement(file.readText()) as JsonArray).forEach { markIndex((it as JsonPrimitive).content) }
            }
            directory.listFiles().orEmpty().filter { it.extension == "json" }.forEach { manifest ->
                val value = Json.parseToJsonElement(manifest.readText()).jsonObject
                listOf("sourcePages", "queryPages", "claimPages").forEach { field -> (value[field] as? JsonArray).orEmpty().forEach { markIndex((it as JsonPrimitive).content) } }
            }
            directory.listFiles().orEmpty().filter { it.extension == "index" && it.name !in retainedIndexes }.forEach { AtomicFile(it).delete() }
            val featureChunks = featureDatabase?.research()?.retainedBodies().orEmpty().flatMap { runCatching { Json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList()) }.toSet()
            val pendingChunks = featureDatabase?.research()?.pendingIngestions().orEmpty().flatMap { row ->
                (Json.parseToJsonElement(row.operation).jsonObject["source"] as? JsonObject)?.strings("bodyChunks").orEmpty()
            }.toSet()
            val retainedChunks = retained.flatMap { name ->
                runCatching { Json.parseToJsonElement(File(directory, name).readText()).jsonObject.strings("bodyChunks") }.getOrDefault(emptyList())
            }.toSet() + featureChunks + pendingChunks + taskStore.retainedBodyChunks()
            directory.listFiles().orEmpty().filter { it.extension == "chunk" && it.name !in retainedChunks }.forEach { AtomicFile(it).delete() }
            mutable.update { sessions -> sessions.filterValues { it.chatId != chatId } }
        }
    }

    suspend fun <T> importFeatureFiles(files: Map<String, File>, commit: suspend () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            check(directory.mkdirs() || directory.isDirectory)
            files.forEach { (name, source) ->
                require(name.matches(Regex("[a-f0-9]{64}\\.chunk")) && source.isFile && source.length() <= 128 * 1024)
                val bytes = source.readBytes()
                require(researchHash(bytes.decodeToString(throwOnInvalidSequence = true)) + ".chunk" == name)
                writeAtomic(File(directory, name), bytes)
            }
            commit()
        }
    }

    suspend fun reportSections(chatId: Int, owner: String, digest: String): Map<String, String> =
        featureDatabase?.research()?.reportSections(chatId, owner, digest).orEmpty().associate { it.heading to it.body }

    suspend fun saveReportSection(chatId: Int, owner: String, digest: String, heading: String, body: String) {
        require(body.length <= 100_000 && heading.length <= 200)
        val database = featureDatabase ?: return
        database.withTransaction {
            val dao = database.research()
            check(dao.deleted("chat:$chatId") == null) { "Report owner was deleted" }
            dao.reportSection(dev.chungjungsoo.gptmobile.data.research.feature.ResearchReportSection(researchHash("$chatId:$owner:$digest:$heading"), chatId, owner, digest, heading, body, System.currentTimeMillis()))
        }
    }

    suspend fun featureDiagnostics(chatId: Int): JsonObject = withContext(Dispatchers.IO) {
        val database = featureDatabase ?: return@withContext buildJsonObject { put("available", false) }
        val dao = database.research()
        database.withTransaction {
            val receipts = dao.saveReceipts(chatId)
            val jobs = dao.chatJobs(chatId)
            val workIds = (receipts.mapNotNull { it.workId } + jobs.map { it.workId }).toSet()
            val memberships = dao.allMemberships().filter { it.workId in workIds }
            buildJsonObject {
                put("schemaVersion", 2)
                put("acceptedTasks", Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(AcceptedResearchTask.serializer()), taskStore.exportTasks(setOf(chatId))))
                put("sourceValidations", Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(dev.chungjungsoo.gptmobile.data.research.feature.ResearchSourceValidation.serializer()), dao.allSourceValidations().filter { row -> dao.version(row.versionId)?.workId in workIds }))
                put("identityResolutions", Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(dev.chungjungsoo.gptmobile.data.research.feature.ResearchIdentityResolution.serializer()), dao.allIdentityResolutions().filter { it.workId in workIds && it.relatedWorkId in workIds }))
                put("jobTransitions", Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(dev.chungjungsoo.gptmobile.data.research.feature.ExaminationTransition.serializer()), jobs.flatMap { dao.transitions(it.jobId) }))
                put("databaseVersion", database.openHelper.readableDatabase.version)
                put("scope", "selected_chat")
                put("chatId", chatId)
                put("archivedStudies", memberships.size)
                put("protectedStudies", memberships.count { it.protectionReason != null })
                put("pendingSaves", receipts.count { it.disposition == "SAVE_PENDING" })
                put("failedSaves", receipts.count { it.disposition == "SAVE_FAILED" })
                put("saveReceipts", Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(dev.chungjungsoo.gptmobile.data.research.feature.ResearchSaveReceipt.serializer()), receipts))
                put(
                    "jobs",
                    JsonArray(
                        jobs.map { job ->
                            buildJsonObject {
                                put("actionId", job.jobId)
                                put("jobId", job.jobId)
                                put("workId", job.workId)
                                put("versionId", job.requestedVersionId)
                                put("examiner", job.profileUid)
                                put("stage", job.stage.name)
                                put("lease", job.lease)
                                put("reason", job.terminalReason)
                                put("updatedAt", job.updatedAt)
                                put("lastHeartbeatAt", job.lastHeartbeatAt)
                                put("deadlineAt", job.deadlineAt)
                                put("workerId", job.workerId)
                                put("cancellationOrigin", job.cancellationOrigin)
                            }
                        }
                    )
                )
                put(
                    "archive",
                    JsonArray(
                        memberships.map { membership ->
                            buildJsonObject {
                                put("workId", membership.workId)
                                put("protection", membership.protectionReason)
                                put("acceptedReviewId", membership.acceptedReviewId)
                                put("verifiedVersionId", membership.verifiedVersionId)
                                put("holdUntil", membership.holdUntil)
                            }
                        }
                    )
                )
                put("historyCoverage", "Persisted task, job transition, source validation and save receipt records; legacy log events may be incomplete")
            }
        }
    }

    suspend fun retryPendingDiscovery(): Boolean = withContext(Dispatchers.IO) {
        val database = featureDatabase ?: return@withContext true
        var complete = true
        for (row in database.research().pendingIngestions()) {
            try {
                val payload = Json.parseToJsonElement(row.operation).jsonObject
                val source = ResearchSnapshot.parse(ResearchSnapshot("retry").json().let { JsonObject(it + ("sources" to JsonArray(listOf(requireNotNull(payload["source"]))))) })?.sources?.singleOrNull() ?: error("Invalid discovery outbox record")
                val dao = database.research()
                val chatId = payload.number("chatId")
                val receiptId = row.id.removePrefix("ingest:")
                if (dao.deleted("chat:$chatId") != null) {
                    dao.acknowledge(row.id, row.createdAt)
                    continue
                }
                val body = if (source.bodyChunks.isEmpty()) source.passage else readFeatureBody(source.bodyChunks) ?: error("Missing retained source body")
                val epoch = payload.text("epoch").toLongOrNull() ?: error("Missing archive generation")
                val policy = featureSettings?.getFeatureSettings()?.researchFeature
                val version = dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveIngestor.capture(database, source, payload.text("authority"), payload.text("publicEvidence") == "true" || source.publicCapture && source.retrievalReceipt.isNotBlank(), chatId, payload.text("turnId"), true, epoch, researchHash(body), body.toByteArray().size.toLong(), unexaminedLimit = policy?.unexaminedStudyLimit ?: 1000)
                dao.saveReceipt(dev.chungjungsoo.gptmobile.data.research.feature.ResearchSaveReceipt(receiptId, payload.text("runId"), source.id, chatId, version.workId, version.versionId, if (dao.membership(version.workId) != null) "ARCHIVED" else "SUPPRESSED_BY_EXPLICIT_DELETION", updatedAt = System.currentTimeMillis()))
                dao.acknowledge(row.id, row.createdAt)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                complete = false
            }
        }
        complete
    }

    suspend fun reconcileFeatureBodies() = withContext(Dispatchers.IO) {
        lock.withLock {
            val retained = featureDatabase?.research()?.retainedBodies().orEmpty().flatMap { Json.decodeFromString<List<String>>(it) }.toMutableSet()
            featureDatabase?.research()?.pendingIngestions().orEmpty().forEach { row ->
                val payload = Json.parseToJsonElement(row.operation).jsonObject
                retained += (payload["source"] as? JsonObject)?.strings("bodyChunks").orEmpty()
            }
            directory.listFiles().orEmpty().filter { it.extension == "source" }.forEach { file ->
                val source = Json.parseToJsonElement(AtomicFile(file).openRead().use { it.readBytes().decodeToString() }).jsonObject
                retained += source.strings("bodyChunks")
            }
            retained += taskStore.retainedBodyChunks()
            directory.listFiles().orEmpty().filter { it.extension == "chunk" && it.name !in retained }.forEach { AtomicFile(it).delete() }
        }
    }

    suspend fun snapshotFeatureFiles(refs: Set<String>, destination: File): Map<String, File> = withContext(Dispatchers.IO) {
        lock.withLock {
            check(destination.mkdirs() || destination.isDirectory)
            refs.associateWith { name ->
                require(name.matches(Regex("[a-f0-9]{64}\\.chunk")))
                val source = File(directory, name)
                require(source.isFile && source.length() <= 128 * 1024) { "Research body chunk is missing" }
                val text = AtomicFile(source).openRead().use { it.readBytes().decodeToString(throwOnInvalidSequence = true) }
                require(researchHash(text) + ".chunk" == name) { "Research body integrity failed" }
                File(destination, name).apply { writeText(text) }
            }
        }
    }

    /** Shared immutable chunks, published before the metadata transaction under the same GC lock. */
    suspend fun <T> publishFeatureBody(text: String, commit: suspend (List<String>) -> T): T = withContext(Dispatchers.IO) {
        require(text.toByteArray().size <= 2 * 1024 * 1024)
        lock.withLock {
            check(directory.mkdirs() || directory.isDirectory)
            val refs = researchTextChunks(text).map { chunk ->
                val name = researchHash(chunk) + ".chunk"
                val target = File(directory, name)
                if (!target.isFile) {
                    check(directory.usableSpace > 8L * 1024 * 1024 + chunk.toByteArray().size)
                    writeAtomic(target, chunk.toByteArray())
                }
                name
            }
            commit(refs)
        }
    }
    suspend fun readFeatureBody(refs: List<String>): String? = withContext(Dispatchers.IO) {
        if (refs.size > 128) return@withContext null
        lock.withLock {
            val output = StringBuilder()
            for (name in refs) {
                if (!name.matches(Regex("[a-f0-9]{64}\\.chunk"))) return@withLock null
                val file = File(directory, name)
                if (!file.isFile || file.length() > 64 * 1024) return@withLock null
                val text = runCatching { file.readText() }.getOrNull() ?: return@withLock null
                if (researchHash(text) + ".chunk" != name) return@withLock null
                output.append(text)
            }
            output.toString()
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
        require(value.number("storageVersion") in 3..4)
        return JsonArray(pages.flatMap { readReferenceTree((it as JsonPrimitive).content) })
    }

    private fun retainedBytes(session: ResearchSession): Long = 4096L + session.snapshot.sources.sumOf { source ->
        512L + 2L * (source.passage.length + source.title.length + source.url.length)
    } + session.snapshot.claims.sumOf { claim -> 384L + 2L * (claim.text.length + claim.quote.length + claim.rationale.length + claim.counterEvidence.length) }

    private fun boundedSessions(values: Map<String, ResearchSession>, preferredChat: Int? = null): Map<String, ResearchSession> {
        var bytes = 0L
        val result = linkedMapOf<String, ResearchSession>()
        values.values.sortedWith(compareByDescending<ResearchSession> { it.chatId == preferredChat }.thenByDescending { it.snapshot.updatedAt }).forEach { session ->
            val cost = retainedBytes(session)
            if (result.size < 24 && bytes + cost <= SESSION_CACHE_BYTES) {
                result[session.runId] = session
                bytes += cost
            }
        }
        return result
    }

    private fun trimSourceCache() {
        var bytes = sourceRefs.values.sumOf { 512L + 2L * (it.first.passage.length + it.first.title.length + it.first.url.length) }
        val entries = sourceRefs.entries.iterator()
        while ((sourceRefs.size > 128 || bytes > 4L * 1024 * 1024) && entries.hasNext()) {
            val entry = entries.next()
            bytes -= 512L + 2L * (entry.value.first.passage.length + entry.value.first.title.length + entry.value.first.url.length)
            sourceRefs.remove(entry.key, entry.value)
        }
    }

    private companion object {
        const val SESSION_CACHE_BYTES = 16L * 1024 * 1024
    }

    private fun file(runId: String) = File(directory, researchHash(runId) + ".json")
    private fun normalizedTask(task: String) = task.trim().replace(Regex("\\s+"), " ").lowercase()
    private fun read(file: File): ResearchSession? = runCatching {
        val bytes = AtomicFile(file).openRead().use { stream ->
            require(stream.channel.size() in 1..512 * 1024)
            stream.readBytes()
        }
        val value = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val runId = value.text("runId")
        val metadata = requireNotNull(value["snapshot"]).jsonObject
        val queryPages = value["queryPages"] as? JsonArray
        val claimPages = value["claimPages"] as? JsonArray
        val claimRows = claimPages?.flatMap { readReferenceTree((it as JsonPrimitive).content) }?.map { ref ->
            val name = ref.content
            require(name.matches(Regex("[a-f0-9]{64}\\.claim")))
            val record = File(directory, name)
            require(record.length() in 1..16 * 1024)
            val text = AtomicFile(record).openRead().use { it.readBytes() }.decodeToString()
            require(researchHash(text) + ".claim" == name)
            Json.parseToJsonElement(text).jsonObject
        }
        val claimMetadata = if (claimRows == null) metadata else JsonObject(metadata + ("claims" to JsonArray(claimRows)))
        val raw = if (queryPages == null) claimMetadata else kotlinx.serialization.json.JsonObject(claimMetadata + ("queries" to JsonArray(queryPages.flatMap { readReferenceTree((it as JsonPrimitive).content) })))
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

/** Preserve complete UTF-16 scalar pairs at persisted UTF-8 chunk boundaries. */
internal fun researchTextChunks(text: String): List<String> = buildList {
    var start = 0
    while (start < text.length) {
        var end = minOf(start + 16 * 1024, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        add(text.substring(start, end))
        start = end
    }
}
