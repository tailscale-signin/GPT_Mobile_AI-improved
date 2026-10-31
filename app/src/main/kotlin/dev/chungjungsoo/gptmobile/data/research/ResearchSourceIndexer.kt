package dev.chungjungsoo.gptmobile.data.research

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Indexing is separate from checkpoint publication: a retained source survives archive failure. */
internal class ResearchSourceIndexer(
    private val database: ResearchDatabase,
    private val directory: File,
    private val runId: String,
    private val chatId: Int,
    private val turnId: String,
    private val authority: String,
    private val publicEvidence: Boolean,
    private val acceptedTools: ChatMcpToolConfig?,
    private val createdAt: Long
) {
    private val dao = database.research()
    private val archivedRevisions = mutableMapOf<String, String>()
    private var archiveEpoch: Long? = null
    suspend fun index(snapshot: ResearchSnapshot, settings: AppFeatureSettings?): Boolean {
        val epoch = archiveEpoch ?: (dao.sequence()?.archiveEpoch ?: 0L).also { archiveEpoch = it }
        val eligible = settings?.researchFeature?.enabled == true && settings.isToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.DEEP_RESEARCH) && (dao.deleted("archive-clear") ?: 0) < createdAt
        var scheduled = false
        for (source in snapshot.sources) scheduled = indexSource(source, snapshot, settings, eligible, epoch) || scheduled
        return scheduled
    }
    private suspend fun indexSource(source: ResearchSource, snapshot: ResearchSnapshot, settings: AppFeatureSettings?, eligible: Boolean, epoch: Long): Boolean {
        var scheduledAny = false
        val receiptId = researchHash("$runId:${source.id}")
        val needed = snapshot.claims.any { it.sourceId == source.id } || Regex("(?i)sample|participant|randomi[sz]|causal|efficacy|confidence interval|trial|systematic review").containsMatchIn(source.title + " " + source.publicationType + " " + source.passage)
        val revision = researchHash(source.json().toString() + "|needed=$needed")
        if (archivedRevisions[source.id] != revision) {
            val pending = dao.outboxById("ingest:$receiptId")
            // Saving progress must not turn a durable indexing failure into
            // dozens of immediate retries of the same source.
            if (pending != null &&
                !pending.retryDue(System.currentTimeMillis()) &&
                Json.parseToJsonElement(pending.operation).jsonObject["source"] == source.json()
            ) {
                return false
            }
            val outbox = dev.chungjungsoo.gptmobile.data.research.feature.ResearchOutbox(
                "ingest:$receiptId",
                buildJsonObject {
                    put("runId", runId)
                    put("chatId", chatId)
                    put("turnId", turnId)
                    put("authority", authority)
                    put("publicEvidence", publicEvidence)
                    put("epoch", epoch)
                    put("archiveEligible", eligible)
                    pending?.let { retained ->
                        val payload = Json.parseToJsonElement(retained.operation).jsonObject
                        if (payload["source"] == source.json()) payload["retryAttempt"]?.let { put("retryAttempt", it) }
                    }
                    put("source", source.json())
                }.toString(),
                System.currentTimeMillis()
            )
            dao.outbox(outbox)
            dao.saveReceipt(dev.chungjungsoo.gptmobile.data.research.feature.ResearchSaveReceipt(receiptId, runId, source.id, chatId, disposition = "SAVE_PENDING", updatedAt = System.currentTimeMillis()))
            try {
                val body = readBody(source)
                val captured = dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveIngestor.capture(database, source, authority, publicEvidence || source.publicCapture && source.retrievalReceipt.isNotBlank(), chatId, turnId, eligible, epoch, researchHash(body), body.toByteArray().size.toLong(), automaticOrganization = settings?.researchFeature?.automaticOrganization == true, unexaminedLimit = settings?.researchFeature?.unexaminedStudyLimit ?: 1000)
                val saved = dao.membership(captured.workId) != null
                val identityReviewRequired = dao.identityResolutions(captured.workId).any { it.disposition == "IDENTITY_REVIEW_REQUIRED" }
                dao.saveReceipt(
                    dev.chungjungsoo.gptmobile.data.research.feature.ResearchSaveReceipt(
                        receiptId,
                        runId,
                        source.id,
                        chatId,
                        captured.workId,
                        captured.versionId,
                        if (identityReviewRequired) {
                            "IDENTITY_REVIEW_REQUIRED"
                        } else if (saved) {
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
                if (!identityReviewRequired && eligible && acceptedTools != null && (source.paperId.isNotBlank() || source.doi.isNotBlank() || source.pmid.isNotBlank() || source.pmcid.isNotBlank())) {
                    val selection = settings?.conversationResearch?.get(chatId) ?: acceptedTools.research
                    val research = selection?.academicEligibleByUi == true
                    val request = dev.chungjungsoo.gptmobile.data.research.feature.ExaminationRequest(captured.workId, chatId = chatId, versionId = captured.versionId, authority = authority, origin = if (research) dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOrigin.AUTHORIZED_RESEARCH else dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOrigin.AUTOMATIC_CHAT, rootWorkId = turnId, parentRunId = runId)
                    val scheduled = dev.chungjungsoo.gptmobile.data.research.work.ScheduledExamination(request, acceptedTools, needed)
                    database.withTransaction {
                        val examinationId = "auto-examine:$chatId:${captured.versionId}"
                        val existing = dao.outboxById(examinationId)
                        if (existing == null) {
                            dao.outbox(dev.chungjungsoo.gptmobile.data.research.feature.ResearchOutbox(examinationId, Json.encodeToString(scheduled), System.currentTimeMillis()))
                            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ResearchExamine", "OUTBOX_ENQUEUED · row=$examinationId · durable=true · origin=${request.origin} · needed=$needed")
                            scheduledAny = true
                        } else if (needed) {
                            // Progress saves must preserve blocked/paused state and its accepted owner.
                            val previous = Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.work.ScheduledExamination>(existing.operation)
                            if (!previous.needed) {
                                dao.outbox(existing.copy(operation = Json.encodeToString(previous.copy(needed = true))))
                                scheduledAny = true
                            }
                        }
                    }
                }
                archivedRevisions[source.id] = revision
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val reason = archiveIngestionFailure(error)
                val now = System.currentTimeMillis()
                dao.outbox(outbox.failedIngestion(now, reason))
                dao.saveReceipt(dev.chungjungsoo.gptmobile.data.research.feature.ResearchSaveReceipt(receiptId, runId, source.id, chatId, disposition = if (reason == "IDENTITY_CONFLICT_REQUIRES_REVIEW") "IDENTITY_REVIEW_REQUIRED" else "SAVE_FAILED", reason = "$reason:${error.javaClass.simpleName}", updatedAt = now))
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ResearchArchive", "SAVE_FAILED · receiptId=$receiptId · runId=$runId · sourceId=${source.id} · reason=$reason · exception=${error.javaClass.simpleName} · retry=durable_backoff", "W")
            }
        }
        return scheduledAny
    }
    private fun readBody(source: ResearchSource): String = if (source.bodyChunks.isEmpty()) {
        source.passage
    } else {
        source.bodyChunks.joinToString("") { name ->
            require(name.matches(Regex("[a-f0-9]{64}\\.chunk")))
            val text = File(directory, name).readText()
            require(researchHash(text) + ".chunk" == name)
            text
        }
    }
}
