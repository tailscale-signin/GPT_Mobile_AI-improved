package dev.chungjungsoo.gptmobile.data.research

import android.util.AtomicFile
import java.io.File
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AcceptedResearchTask(
    val schemaVersion: Int = 1,
    val taskId: String,
    val rootWorkId: String,
    val chatId: Int,
    val originatingUserMessageId: Int,
    val taskText: String,
    val intent: String,
    val participantUids: List<String>,
    val primaryUid: String?,
    val settingsRevision: String,
    val sourceAuthorityRevision: String,
    val acceptedAt: Long,
    val acceptedPolicyJson: String = "",
    val sourceScope: String = "",
    val targetSources: Int = 0,
    val requestedModels: Map<String, String> = emptyMap()
)

@Serializable
data class ResearchResumeRequest(val actionId: String, val taskId: String, val rootWorkId: String, val checkpointId: String, val requestedStage: String, val destinationProfileUid: String, val evidenceRevision: String, val policyRevision: String, val requestedAt: Long)

@Serializable
data class ResearchEvidenceDependency(val sourceId: String, val versionId: String, val bodyHash: String, val authority: String, val publicCapture: Boolean, val originatingProviders: List<String>, val receiptIds: List<String>, val acquiredPolicyRevision: String)

@Serializable
data class ResearchTaskCheckpoint(val taskId: String, val runId: String, val authority: String, val generation: Long, val phase: String, val evidenceRevision: String, val dependencies: List<ResearchEvidenceDependency>, val updatedAt: Long)

/** An immutable accepted envelope plus append-by-identity recovery/checkpoint records, never a grant. */
internal class ResearchTaskStore(private val directory: File) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val temporary = java.util.concurrent.ConcurrentHashMap<String, String>()
    private class Coordination {
        val tasks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
        val gate = ResearchStorageGate()
    }
    private companion object {
        val coordination = java.util.concurrent.ConcurrentHashMap<String, Coordination>()
    }
    private val shared = coordination.computeIfAbsent(directory.absolutePath) { Coordination() }
    private val taskLocks = shared.tasks
    private val deletedChats = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private fun isDeleted(chatId: Int): Boolean = chatId in deletedChats || read("deleted-chat", chatId.toString(), true) != null
    private suspend fun <T> task(root: String, action: () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        shared.gate.withReadLock { taskLocks.computeIfAbsent(root) { kotlinx.coroutines.sync.Mutex() }.withLock { action() } }
    }

    private val executionState = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ResearchExecutionRecord>>(emptyMap())
    val executions: kotlinx.coroutines.flow.StateFlow<Map<String, ResearchExecutionRecord>> get() = executionState

    suspend fun execution(root: String, persistent: Boolean) = task(root) { executionUnlocked(root, persistent) }
    suspend fun transition(root: String, attemptId: String, generation: Long, state: ResearchAttemptState, persistent: Boolean, reason: String? = null, runId: String? = null, profileUid: String? = null, dispatched: Boolean = false, checkpointId: String? = null, evidenceRevision: String? = null, readableSources: Int? = null, queueMillis: Long? = null): ResearchExecutionRecord? = task(root) {
        val old = executionUnlocked(root, persistent) ?: return@task null
        check(!isDeleted(old.accepted.chatId)) { "Conversation was deleted" }
        val next = old.transition(attemptId, generation, state, System.currentTimeMillis(), reason, runId, profileUid, dispatched, checkpointId ?: old.attempt.checkpointId, evidenceRevision ?: old.attempt.evidenceRevision, readableSources ?: old.attempt.readableSources, queueMillis ?: old.attempt.queueMillis)
        if (next != old) writeExecution(next, persistent)
        next
    }
    suspend fun resumeAttempt(root: String, actionId: String, persistent: Boolean, reconciled: Boolean = false, expectedAttempt: ResearchAttempt? = null): ResearchExecutionRecord? = task(root) {
        val old = executionUnlocked(root, persistent) ?: return@task null
        check(!isDeleted(old.accepted.chatId)) { "Conversation was deleted" }
        if (old.attempt.recoveryActionId == actionId || old.previousAttempts.any { it.recoveryActionId == actionId }) return@task old
        if (expectedAttempt != null) {
            check(old.attempt.attemptId == expectedAttempt.attemptId && old.attempt.generation == expectedAttempt.generation) { "RESEARCH_REVIEW_STALE: The attempt changed after this review." }
        }
        old.resume(actionId, System.currentTimeMillis(), reconciled).also { if (it != old) writeExecution(it, persistent) }
    }

    /** Recover projections only. Existing AgentRunCoordinator remains the sole dispatcher. */
    suspend fun reconcile(chatIds: Set<Int>? = null): List<ResearchExecutionRecord> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val canonical = files("execution").mapNotNull { path -> readFile(path)?.let { json.decodeFromString<ResearchExecutionRecord>(it).validated().accepted } }
        val legacy = files("task").mapNotNull { path -> readFile(path)?.let { json.decodeFromString<AcceptedResearchTask>(it) } }
        val roots = (canonical + legacy).distinctBy { it.rootWorkId }.filter { (chatIds == null || it.chatId in chatIds) && !isDeleted(it.chatId) }
        roots.mapNotNull { record ->
            task(record.rootWorkId) {
                val live = executionUnlocked(record.rootWorkId, true) ?: return@task null
                // An atomic execution survives a crash before the legacy task projection/link.
                write("task", live.accepted.rootWorkId, json.encodeToString(live.accepted), true)
                live
            }
        }
    }
    suspend fun exportExecutions(chatIds: Set<Int>): List<ResearchExecutionRecord> = reconcile(chatIds)

    suspend fun accepted(root: String, persistent: Boolean) = task(root) { acceptedUnlocked(root, persistent) }
    suspend fun accept(value: AcceptedResearchTask, persistent: Boolean) = task(value.rootWorkId) {
        check(!isDeleted(value.chatId)) { "Conversation was deleted" }
        acceptUnlocked(value, persistent)
    }
    suspend fun resume(value: ResearchResumeRequest, persistent: Boolean) = task(value.rootWorkId) { resumeUnlocked(value, persistent) }
    suspend fun checkpoint(task: AcceptedResearchTask, runId: String, authority: String, generation: Long, snapshot: ResearchSnapshot, persistent: Boolean, expectedAttemptId: String? = null, expectedAttemptGeneration: Long? = null) = task(task.rootWorkId) {
        check(!isDeleted(task.chatId)) { "Conversation was deleted" }
        val current = executionUnlocked(task.rootWorkId, persistent)?.attempt
        if (expectedAttemptId != null && (current?.attemptId != expectedAttemptId || current.generation != expectedAttemptGeneration || current.state.terminal)) return@task
        checkpointUnlocked(task, runId, authority, generation, snapshot, persistent)
    }
    suspend fun manifest(root: String, persistent: Boolean) = task(root) { manifestUnlocked(root, persistent) }
    suspend fun retainedEvidence(root: String, authorities: Set<String>, persistent: Boolean) = task(root) { retainedEvidenceUnlocked(root, authorities, persistent) }
    suspend fun retainPartial(root: String, checkpointId: String, persistent: Boolean) = task(root) { retainPartialUnlocked(root, checkpointId, persistent) }
    suspend fun retainDelivery(root: String, runId: String, authority: String, generation: Long, receipt: dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt, persistent: Boolean) = task(root) { retainDeliveryUnlocked(root, runId, authority, generation, receipt, persistent) }
    suspend fun plan(root: String, value: String? = null, persistent: Boolean) = task(root) { planUnlocked(root, value, persistent) }

    private fun file(kind: String, key: String) = File(directory, researchHash("$kind:$key") + ".$kind")

    private fun read(kind: String, key: String, persistent: Boolean): String? {
        if (!persistent) return temporary["$kind:$key"]
        val path = file(kind, key)
        return readFile(path)
    }

    private fun files(kind: String): List<File> = directory.listFiles().orEmpty().map { path ->
        if (path.name.endsWith(".$kind.bak")) File(directory, path.name.removeSuffix(".bak")) else path
    }.filter { it.extension == kind }.distinctBy { it.absolutePath }

    private fun readFile(path: File): String? = ResearchAtomicFiles.locked(path) {
        if (path.isFile || File(path.path + ".bak").isFile) AtomicFile(path).openRead().bufferedReader().use { it.readText() } else null
    }

    private fun write(kind: String, key: String, body: String, persistent: Boolean) {
        if (!persistent) {
            temporary["$kind:$key"] = body
            return
        }
        check(directory.mkdirs() || directory.isDirectory)
        val path = file(kind, key)
        ResearchAtomicFiles.locked(path) {
            val atomic = AtomicFile(path)
            val output = atomic.startWrite()
            try {
                output.write(body.toByteArray())
                atomic.finishWrite(output)
            } catch (failure: Throwable) {
                atomic.failWrite(output)
                throw failure
            }
        }
    }

    private fun writeExecution(value: ResearchExecutionRecord, persistent: Boolean) {
        value.validated()
        write("execution", value.accepted.rootWorkId, json.encodeToString(value), persistent)
        executionState.update { it + (value.accepted.rootWorkId to value) }
    }

    private fun executionUnlocked(root: String, persistent: Boolean): ResearchExecutionRecord? {
        val body = read("execution", root, persistent)
        if (body != null) {
            val record = json.decodeFromString<ResearchExecutionRecord>(body).validated()
            require(record.accepted.rootWorkId == root)
            if (isDeleted(record.accepted.chatId)) return null
            executionState.update { it + (root to record) }
            return record
        }
        val legacy = read("task", root, persistent)?.let { json.decodeFromString<AcceptedResearchTask>(it) } ?: return null
        if (isDeleted(legacy.chatId)) return null
        // Historical files cannot establish whether dispatch/stop already happened. Import
        // them once as interrupted rather than silently enqueueing old research again.
        return ResearchExecutionRecord.accept(legacy, legacy = true).also { writeExecution(it, persistent) }
    }

    private fun acceptedUnlocked(root: String, persistent: Boolean): AcceptedResearchTask? = executionUnlocked(root, persistent)?.accepted

    private fun acceptUnlocked(value: AcceptedResearchTask, persistent: Boolean): AcceptedResearchTask {
        require(value.taskId == researchHash("task:${value.rootWorkId}") && value.taskText.isNotBlank() && value.participantUids.isNotEmpty())
        val old = acceptedUnlocked(value.rootWorkId, persistent)
        if (old != null) {
            check(old.chatId == value.chatId && old.originatingUserMessageId == value.originatingUserMessageId && old.taskText == value.taskText) { "RESEARCH_TASK_IDENTITY_CHANGED" }
            return old
        }
        // One atomic authority includes the complete payload, attempt and pending action.
        // The compatibility task file is an idempotent projection, never the dispatcher.
        writeExecution(ResearchExecutionRecord.accept(value), persistent)
        write("task", value.rootWorkId, json.encodeToString(value), persistent)
        return value
    }

    private fun resumeUnlocked(value: ResearchResumeRequest, persistent: Boolean): ResearchResumeRequest {
        val task = requireNotNull(acceptedUnlocked(value.rootWorkId, persistent)) { "Accepted research task is unavailable" }
        require(task.taskId == value.taskId && value.destinationProfileUid in task.participantUids)
        val key = "${value.rootWorkId}:${value.actionId}:${value.destinationProfileUid}"
        val old = read("resume", key, persistent)?.let { json.decodeFromString<ResearchResumeRequest>(it) }
        if (old != null) {
            check(old.taskId == value.taskId && old.rootWorkId == value.rootWorkId && old.requestedStage == value.requestedStage && old.destinationProfileUid == value.destinationProfileUid && old.policyRevision == value.policyRevision) { "RESEARCH_RECOVERY_ACTION_CHANGED" }
            return old
        }
        write("resume", key, json.encodeToString(value), persistent)
        return value
    }

    private fun checkpointUnlocked(task: AcceptedResearchTask, runId: String, authority: String, generation: Long, snapshot: ResearchSnapshot, persistent: Boolean) {
        require(snapshot.task == task.taskText) { "RESEARCH_CHECKPOINT_TASK_MISMATCH" }
        val dependencies = snapshot.sources.map { source -> ResearchEvidenceDependency(source.id, source.versionId, researchHash(source.passage), authority, source.publicCapture && source.retrievalReceipt.isNotBlank(), source.engines, (source.providerReceipts + source.retrievalReceipt).filter(String::isNotBlank).distinct(), task.sourceAuthorityRevision) }
        val revision = researchHash(snapshot.sources.joinToString { "${it.id}:${it.versionId}:${researchHash(it.passage)}" } + snapshot.claims.joinToString { "${it.sourceId}:${it.quote}:${it.verdict}" })
        write("checkpoint", "$runId:$authority", json.encodeToString(ResearchTaskCheckpoint(task.taskId, runId, authority, generation, snapshot.phase, revision, dependencies, snapshot.updatedAt)), persistent)
        retainEvidence(task, runId, authority, generation, snapshot, persistent)
        executionUnlocked(task.rootWorkId, persistent)?.let { record ->
            val phase = when {
                snapshot.phase.contains("search", true) -> ResearchAttemptState.SEARCHING
                snapshot.phase.contains("read", true) || snapshot.phase.contains("fetch", true) -> ResearchAttemptState.FETCHING
                snapshot.phase.contains("review", true) || snapshot.phase.contains("extract", true) || snapshot.phase.contains("analy", true) -> ResearchAttemptState.ANALYZING
                else -> record.attempt.state
            }
            // Session generation is independently checked by its journal; attempt identity
            // is frozen at dispatch, so old attempts cannot update this projection.
            val next = record.transition(record.attempt.attemptId, record.attempt.generation, phase, snapshot.updatedAt, checkpointId = runId, evidenceRevision = revision, readableSources = snapshot.sources.count { it.readable })
            if (next != record) writeExecution(next, persistent)
        }
    }

    private fun manifestUnlocked(root: String, persistent: Boolean): ResearchEvidenceManifest? = read("manifest", root, persistent)?.let { json.decodeFromString(it) }

    private fun retainEvidence(task: AcceptedResearchTask, runId: String, authority: String, generation: Long, snapshot: ResearchSnapshot, persistent: Boolean) {
        // A progress timestamp alone is not a new evidence revision. Keep counters,
        // stage, validation and source changes, without duplicating the full corpus
        // on every otherwise identical save.
        val captured = RetainedResearchEvidence(task.taskId, task.rootWorkId, task.chatId, researchHash(task.taskText), runId, authority, generation, snapshot.copy(updatedAt = 0).json())
        val encoded = json.encodeToString(captured)
        val id = researchHash(encoded)
        val old = manifestUnlocked(task.rootWorkId, persistent) ?: ResearchEvidenceManifest(taskId = task.taskId, rootWorkId = task.rootWorkId, chatId = task.chatId, originatingUserMessageId = task.originatingUserMessageId, originalRequestDigest = researchHash(task.taskText))
        require(old.taskId == task.taskId && old.originalRequestDigest == researchHash(task.taskText)) { "RESEARCH_MANIFEST_TASK_CHANGED" }
        if (old.revisions.any { it.id == id }) return
        // The immutable page is durable before its manifest pointer can be observed.
        write("evidence", id, encoded, persistent)
        val entry = ResearchEvidenceRevision(id, runId, authority, generation, snapshot.evidenceRevisionDigest(), snapshot.phase, snapshot.sources.count { it.readable }, snapshot.claims.count { it.exactQuoteVerified && it.verdict != "Unreviewed" }, snapshot.sources.size, snapshot.updatedAt)
        write("manifest", task.rootWorkId, json.encodeToString(old.copy(revisions = old.revisions + entry)), persistent)
    }

    private fun retainedEvidenceUnlocked(root: String, authorities: Set<String>, persistent: Boolean): List<ResearchSession> {
        val task = acceptedUnlocked(root, persistent) ?: return emptyList()
        val index = manifestUnlocked(root, persistent) ?: return emptyList()
        require(index.taskId == task.taskId && index.chatId == task.chatId && index.originalRequestDigest == researchHash(task.taskText)) { "RESEARCH_MANIFEST_TASK_CHANGED" }
        return index.revisions.filter { it.authority in authorities }.groupBy { it.authority }.values.mapNotNull { entries ->
            var retainedSession: ResearchSession? = null
            entries.sortedWith(compareByDescending<ResearchEvidenceRevision> { it.readableSources }.thenByDescending { it.reviewedClaims }.thenByDescending { it.totalSources }.thenByDescending { it.updatedAt }).forEach { entry ->
                val encoded = read("evidence", entry.id, persistent) ?: return@forEach
                require(researchHash(encoded) == entry.id) { "RESEARCH_MANIFEST_INTEGRITY_FAILED" }
                val retained = json.decodeFromString<RetainedResearchEvidence>(encoded)
                require(retained.taskId == task.taskId && retained.rootWorkId == root && retained.chatId == task.chatId && retained.authority == entry.authority && retained.originalRequestDigest == researchHash(task.taskText)) { "RESEARCH_MANIFEST_SCOPE_CHANGED" }
                val snapshot = ResearchSnapshot.parse(retained.snapshot)?.copy(updatedAt = entry.updatedAt) ?: return@forEach
                require(snapshot.task == task.taskText) { "RESEARCH_MANIFEST_EVIDENCE_CHANGED" }
                val strongest = richestResearchSnapshot(listOfNotNull(retainedSession?.snapshot, snapshot)) ?: return@forEach
                retainedSession = ResearchSession(if (strongest === snapshot) retained.runId else retainedSession?.runId ?: retained.runId, retained.chatId, strongest, root, retained.authority)
            }
            retainedSession
        }
    }

    suspend fun retainedBodyChunks(): Set<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val retained = linkedSetOf<String>()
        files("evidence").forEach { path ->
            val page = json.decodeFromString<RetainedResearchEvidence>(readFile(path) ?: return@forEach)
            page.snapshot.rows("sources").forEach { retained += it.strings("bodyChunks") }
        }
        retained
    }

    private fun retainPartialUnlocked(root: String, checkpointId: String, persistent: Boolean) {
        val task = acceptedUnlocked(root, persistent) ?: return
        val index = manifestUnlocked(root, persistent) ?: ResearchEvidenceManifest(taskId = task.taskId, rootWorkId = root, chatId = task.chatId, originatingUserMessageId = task.originatingUserMessageId, originalRequestDigest = researchHash(task.taskText))
        write("manifest", root, json.encodeToString(index.copy(partialCheckpointIds = index.partialCheckpointIds + checkpointId)), persistent)
    }

    private fun retainDeliveryUnlocked(root: String, runId: String, authority: String, generation: Long, receipt: dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt, persistent: Boolean): Boolean {
        val task = acceptedUnlocked(root, persistent) ?: return false
        val index = manifestUnlocked(root, persistent) ?: ResearchEvidenceManifest(taskId = task.taskId, rootWorkId = root, chatId = task.chatId, originatingUserMessageId = task.originatingUserMessageId, originalRequestDigest = researchHash(task.taskText))
        val body = kotlinx.serialization.json.buildJsonObject {
            put("taskId", kotlinx.serialization.json.JsonPrimitive(task.taskId))
            put("rootWorkId", kotlinx.serialization.json.JsonPrimitive(root))
            put("runId", kotlinx.serialization.json.JsonPrimitive(runId))
            put("authority", kotlinx.serialization.json.JsonPrimitive(authority))
            put("generation", kotlinx.serialization.json.JsonPrimitive(generation))
            put("receipt", json.encodeToJsonElement(dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt.serializer(), receipt))
        }.toString()
        val id = researchHash(body)
        write("delivery", id, body, persistent)
        write("manifest", root, json.encodeToString(index.copy(deliveryReceiptIds = index.deliveryReceiptIds + id)), persistent)
        return true
    }

    private fun planUnlocked(root: String, value: String? = null, persistent: Boolean): String? {
        val task = acceptedUnlocked(root, persistent) ?: return null
        val existing = read("plan", root, persistent)
        check(existing == null || existing.substringBefore('\n') == task.taskId) { "RESEARCH_PLAN_TASK_MISMATCH" }
        if (existing != null || value == null) return existing?.substringAfter('\n')
        write("plan", root, task.taskId + "\n" + value, persistent)
        return value
    }

    suspend fun exportTasks(chatIds: Set<Int>): List<AcceptedResearchTask> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val authoritative = files("execution").mapNotNull { path -> readFile(path)?.let { json.decodeFromString<ResearchExecutionRecord>(it).validated().accepted } }
        val legacy = files("task").mapNotNull { path -> readFile(path)?.let { json.decodeFromString<AcceptedResearchTask>(it) } }
        (authoritative + legacy).distinctBy { it.rootWorkId }.filter { it.chatId in chatIds && !isDeleted(it.chatId) }
    }

    suspend fun clearChat(chatId: Int) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        shared.gate.withLock {
            val tasks = temporary.filterKeys { it.startsWith("task:") }.values.map { json.decodeFromString<AcceptedResearchTask>(it) }.filter { it.chatId == chatId } + exportTasks(setOf(chatId))
            if (exportTasks(setOf(chatId)).isNotEmpty()) write("deleted-chat", chatId.toString(), "deleted", true)
            deletedChats += chatId
            executionState.update { state -> state.filterValues { it.accepted.chatId != chatId } }
            tasks.distinctBy { it.rootWorkId }.forEach { accepted ->
                taskLocks.computeIfAbsent(accepted.rootWorkId) { kotlinx.coroutines.sync.Mutex() }.withLock {
                    temporary.entries.removeAll { (_, body) -> body.contains(accepted.taskId) }
                    setOf("task", "execution", "resume", "checkpoint", "manifest", "evidence", "plan", "delivery").flatMap(::files).forEach { path ->
                        ResearchAtomicFiles.locked(path) {
                            if (readFile(path)?.contains(accepted.taskId) == true) AtomicFile(path).delete()
                        }
                    }
                }
            }
        }
    }
}
