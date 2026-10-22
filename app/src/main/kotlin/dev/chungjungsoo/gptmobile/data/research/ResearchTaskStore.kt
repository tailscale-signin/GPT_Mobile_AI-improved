package dev.chungjungsoo.gptmobile.data.research

import android.util.AtomicFile
import java.io.File
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
    val acceptedAt: Long
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
    private val temporary = mutableMapOf<String, String>()
    private fun file(kind: String, key: String) = File(directory, researchHash("$kind:$key") + ".$kind")

    @Synchronized private fun read(kind: String, key: String, persistent: Boolean): String? {
        if (!persistent) return temporary["$kind:$key"]
        val path = file(kind, key)
        return if (path.isFile) AtomicFile(path).openRead().bufferedReader().use { it.readText() } else null
    }

    @Synchronized private fun write(kind: String, key: String, body: String, persistent: Boolean) {
        if (!persistent) {
            temporary["$kind:$key"] = body
            return
        }
        check(directory.mkdirs() || directory.isDirectory)
        val atomic = AtomicFile(file(kind, key))
        val output = atomic.startWrite()
        try {
            output.write(body.toByteArray())
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }

    @Synchronized fun accepted(root: String, persistent: Boolean): AcceptedResearchTask? = read("task", root, persistent)?.let { json.decodeFromString(it) }

    @Synchronized fun accept(value: AcceptedResearchTask, persistent: Boolean): AcceptedResearchTask {
        require(value.taskId == researchHash("task:${value.rootWorkId}") && value.taskText.isNotBlank() && value.participantUids.isNotEmpty())
        val old = accepted(value.rootWorkId, persistent)
        if (old != null) {
            check(old.chatId == value.chatId && old.originatingUserMessageId == value.originatingUserMessageId && old.taskText == value.taskText) { "RESEARCH_TASK_IDENTITY_CHANGED" }
            return old
        }
        write("task", value.rootWorkId, json.encodeToString(value), persistent)
        return value
    }

    @Synchronized fun resume(value: ResearchResumeRequest, persistent: Boolean): ResearchResumeRequest {
        val task = requireNotNull(accepted(value.rootWorkId, persistent)) { "Accepted research task is unavailable" }
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

    @Synchronized fun checkpoint(task: AcceptedResearchTask, runId: String, authority: String, generation: Long, snapshot: ResearchSnapshot, persistent: Boolean) {
        require(snapshot.task == task.taskText) { "RESEARCH_CHECKPOINT_TASK_MISMATCH" }
        val dependencies = snapshot.sources.map { source -> ResearchEvidenceDependency(source.id, source.versionId, researchHash(source.passage), authority, source.publicCapture && source.retrievalReceipt.isNotBlank(), source.engines, (source.providerReceipts + source.retrievalReceipt).filter(String::isNotBlank).distinct(), task.sourceAuthorityRevision) }
        val revision = researchHash(snapshot.sources.joinToString { "${it.id}:${it.versionId}:${researchHash(it.passage)}" } + snapshot.claims.joinToString { "${it.sourceId}:${it.quote}:${it.verdict}" })
        write("checkpoint", "$runId:$authority", json.encodeToString(ResearchTaskCheckpoint(task.taskId, runId, authority, generation, snapshot.phase, revision, dependencies, snapshot.updatedAt)), persistent)
        retainEvidence(task, runId, authority, generation, snapshot, persistent)
    }

    @Synchronized fun manifest(root: String, persistent: Boolean): ResearchEvidenceManifest? = read("manifest", root, persistent)?.let { json.decodeFromString(it) }

    @Synchronized private fun retainEvidence(task: AcceptedResearchTask, runId: String, authority: String, generation: Long, snapshot: ResearchSnapshot, persistent: Boolean) {
        // A progress timestamp alone is not a new evidence revision. Keep counters,
        // stage, validation and source changes, without duplicating the full corpus
        // on every otherwise identical save.
        val captured = RetainedResearchEvidence(task.taskId, task.rootWorkId, task.chatId, researchHash(task.taskText), runId, authority, generation, snapshot.copy(updatedAt = 0).json())
        val encoded = json.encodeToString(captured)
        val id = researchHash(encoded)
        val old = manifest(task.rootWorkId, persistent) ?: ResearchEvidenceManifest(taskId = task.taskId, rootWorkId = task.rootWorkId, chatId = task.chatId, originatingUserMessageId = task.originatingUserMessageId, originalRequestDigest = researchHash(task.taskText))
        require(old.taskId == task.taskId && old.originalRequestDigest == researchHash(task.taskText)) { "RESEARCH_MANIFEST_TASK_CHANGED" }
        if (old.revisions.any { it.id == id }) return
        // The immutable page is durable before its manifest pointer can be observed.
        write("evidence", id, encoded, persistent)
        val entry = ResearchEvidenceRevision(id, runId, authority, generation, snapshot.evidenceRevisionDigest(), snapshot.phase, snapshot.sources.count { it.readable }, snapshot.claims.count { it.exactQuoteVerified && it.verdict != "Unreviewed" }, snapshot.sources.size, snapshot.updatedAt)
        write("manifest", task.rootWorkId, json.encodeToString(old.copy(revisions = old.revisions + entry)), persistent)
    }

    @Synchronized fun retainedEvidence(root: String, authorities: Set<String>, persistent: Boolean): List<ResearchSession> {
        val task = accepted(root, persistent) ?: return emptyList()
        val index = manifest(root, persistent) ?: return emptyList()
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

    @Synchronized fun retainedBodyChunks(): Set<String> {
        val retained = linkedSetOf<String>()
        directory.listFiles().orEmpty().filter { it.extension == "evidence" }.forEach { path ->
            val page = json.decodeFromString<RetainedResearchEvidence>(AtomicFile(path).openRead().bufferedReader().use { it.readText() })
            page.snapshot.rows("sources").forEach { retained += it.strings("bodyChunks") }
        }
        return retained
    }

    @Synchronized fun retainPartial(root: String, checkpointId: String, persistent: Boolean) {
        val task = accepted(root, persistent) ?: return
        val index = manifest(root, persistent) ?: ResearchEvidenceManifest(taskId = task.taskId, rootWorkId = root, chatId = task.chatId, originatingUserMessageId = task.originatingUserMessageId, originalRequestDigest = researchHash(task.taskText))
        write("manifest", root, json.encodeToString(index.copy(partialCheckpointIds = index.partialCheckpointIds + checkpointId)), persistent)
    }

    @Synchronized fun retainDelivery(root: String, runId: String, authority: String, generation: Long, receipt: dev.chungjungsoo.gptmobile.data.research.delivery.ResearchDeliveryReceipt, persistent: Boolean): Boolean {
        val task = accepted(root, persistent) ?: return false
        val index = manifest(root, persistent) ?: ResearchEvidenceManifest(taskId = task.taskId, rootWorkId = root, chatId = task.chatId, originatingUserMessageId = task.originatingUserMessageId, originalRequestDigest = researchHash(task.taskText))
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

    @Synchronized fun plan(root: String, value: String? = null, persistent: Boolean): String? {
        val task = accepted(root, persistent) ?: return null
        val existing = read("plan", root, persistent)
        check(existing == null || existing.substringBefore('\n') == task.taskId) { "RESEARCH_PLAN_TASK_MISMATCH" }
        if (existing != null || value == null) return existing?.substringAfter('\n')
        write("plan", root, task.taskId + "\n" + value, persistent)
        return value
    }

    @Synchronized fun exportTasks(chatIds: Set<Int>): List<AcceptedResearchTask> = directory.listFiles().orEmpty().filter { it.extension == "task" }.map { json.decodeFromString<AcceptedResearchTask>(AtomicFile(it).openRead().bufferedReader().use { input -> input.readText() }) }.filter { it.chatId in chatIds }

    @Synchronized fun clearChat(chatId: Int) {
        val taskIds = temporary.filterKeys { it.startsWith("task:") }.values.map { json.decodeFromString<AcceptedResearchTask>(it) }.filter { it.chatId == chatId }.map { it.taskId }.toSet()
        temporary.entries.removeAll { (_, body) -> taskIds.any { body.contains(it) } }
        directory.listFiles().orEmpty().filter { it.extension == "task" }.forEach { path ->
            val task = runCatching { json.decodeFromString<AcceptedResearchTask>(path.readText()) }.getOrNull() ?: return@forEach
            if (task.chatId == chatId) {
                directory.listFiles().orEmpty().filter { it.extension in setOf("resume", "checkpoint", "manifest", "evidence", "plan", "delivery") && runCatching { it.readText().contains(task.taskId) }.getOrDefault(false) }.forEach { it.delete() }
                path.delete()
            }
        }
    }
}
