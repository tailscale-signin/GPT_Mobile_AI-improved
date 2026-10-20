package dev.chungjungsoo.gptmobile.data.research.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.feature.ArticleExaminationCoordinator
import dev.chungjungsoo.gptmobile.data.research.feature.AutoExamination
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationRequest
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchArchiveRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ScheduledExamination(val request: ExaminationRequest, val tools: ChatMcpToolConfig, val needed: Boolean, val waitingReason: String? = null, val waitingRevision: String? = null)

/** The durable outbox is authoritative. A unique worker never derives permissions from a card. */
@HiltWorker
class ResearchExaminationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val archive: ResearchArchiveRepository,
    private val settings: SettingRepository,
    private val coordinator: ArticleExaminationCoordinator,
    private val sessions: dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        coordinator.recover()
        var pending = !sessions.retryPendingDiscovery()
        val features = settings.getFeatureSettings()
        if (!features.researchFeature.enabled || features.researchFeature.autoExamination == AutoExamination.OFF) {
            archive.dao.trimUnexamined(features.researchFeature.unexaminedStudyLimit, System.currentTimeMillis())
            return if (pending) Result.retry() else Result.success()
        }
        if (!features.researchFeature.backgroundExamination && !withContext(Dispatchers.Main) { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }) return Result.retry()
        val configurationRevision = examinationConfigurationRevision(features, settings.fetchPlatformV2s())
        // Only process-loss recovery is automatic; user Pause/Stop always requires user action.
        archive.dao.allJobs().filter { it.origin != dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOrigin.USER_EXAMINE && it.stage == dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.PAUSED && (it.terminalReason == "Process interrupted; explicit resume required" || it.cancellationOrigin == "WAITING_FOR_CONFIGURATION") }.forEach { job ->
            val checkpoint = runCatching { Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.feature.ExaminationCheckpoint>(job.checkpointJson) }.getOrNull()
            val tools = checkpoint?.acceptedTools?.copy(combined = checkpoint.combined)
            if (checkpoint?.waitingConfigurationRevision == configurationRevision) return@forEach
            val combined = checkpoint?.combined
            if (combined != null) {
                val budget = dev.chungjungsoo.gptmobile.data.agent.CombinedTurnBudgetStore.installedOrNull()?.open(combined)?.snapshot()
                if (budget == null || budget.cancelled || budget.closed) {
                    archive.dao.interruptJob(job.jobId, job.lease, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.PAUSED, "The accepted Combined turn is closed or unavailable. Use Examine / Resume to authorize a new attempt; saved evidence is retained.", "PARENT_TURN_CLOSED", System.currentTimeMillis())
                    return@forEach
                }
            }
            if (tools != null && System.currentTimeMillis() - job.updatedAt < 24 * 60 * 60_000L) {
                try {
                    val id = coordinator.automaticExamine(ExaminationRequest(job.workId, job.profileUid, job.chatId, job.requestedVersionId, job.authority, job.origin, checkpoint.rootWorkId, checkpoint.parentRunId)) { tools }
                    if (coordinator.awaitExecution(id) !in setOf(dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.COMPLETE_FULL, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.COMPLETE_PARTIAL, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.ABSTRACT_ONLY, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.CANCELED)) pending = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (configurationBlocked(failure)) {
                        archive.database.withTransaction {
                            val live = archive.dao.job(job.jobId)
                            if (live?.lease == job.lease) archive.dao.job(live.copy(checkpointJson = Json.encodeToString(requireNotNull(checkpoint).copy(waitingConfigurationRevision = configurationRevision)), cancellationOrigin = "WAITING_FOR_CONFIGURATION", terminalReason = "Examination is waiting for configuration. Review the selected examiner, reader and paper permissions."))
                        }
                    } else {
                        pending = true
                    }
                }
            }
        }
        var cursor = ""
        var admitted = 0
        while (admitted < 100) {
            val batch = archive.dao.pendingExaminations(cursor)
            if (batch.isEmpty()) break
            for (row in batch) {
                try {
                    val scheduled = Json.decodeFromString<ScheduledExamination>(row.operation)
                    if (scheduled.waitingReason in setOf("USER_PAUSED", "OUTPUT_INVALID") || scheduled.waitingReason != null && scheduled.waitingRevision == configurationRevision) continue
                    val policy = settings.getFeatureSettings().researchFeature
                    if (!policy.enabled || policy.autoExamination == AutoExamination.OFF || policy.autoExamination == AutoExamination.WHEN_NEEDED && !scheduled.needed) continue
                    if (archive.dao.deleted("archive:${scheduled.request.workId}") != null || archive.dao.deleted("chat:${scheduled.request.chatId}") != null) {
                        archive.dao.acknowledge(row.id, row.createdAt)
                        continue
                    }
                    val id = coordinator.automaticExamine(scheduled.request) { scheduled.tools }
                    admitted++
                    AppLogRecorder.record("ResearchExamine", "AUTO_ADMITTED · actionId=${row.id} · jobId=$id · workId=${scheduled.request.workId} · chatId=${scheduled.request.chatId}")
                    val stage = coordinator.awaitExecution(id)
                    if (stage in setOf(dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.COMPLETE_FULL, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.COMPLETE_PARTIAL, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.ABSTRACT_ONLY, dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.CANCELED)) {
                        archive.dao.acknowledge(row.id, row.createdAt)
                    } else if (stage == dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.PAUSED && archive.dao.job(id)?.terminalReason?.startsWith("Paused;") == true) {
                        archive.dao.outbox(row.copy(operation = Json.encodeToString(scheduled.copy(waitingReason = "USER_PAUSED", waitingRevision = null))))
                    } else if (stage == dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage.FAILED && archive.dao.job(id)?.terminalReason?.contains("EXAMINATION_OUTPUT_INVALID") == true) {
                        archive.dao.outbox(row.copy(operation = Json.encodeToString(scheduled.copy(waitingReason = "OUTPUT_INVALID", waitingRevision = null))))
                    } else {
                        pending = true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (configurationBlocked(failure)) {
                        val scheduled = Json.decodeFromString<ScheduledExamination>(row.operation)
                        archive.dao.outbox(row.copy(operation = Json.encodeToString(scheduled.copy(waitingReason = "WAITING_FOR_CONFIGURATION", waitingRevision = configurationRevision))))
                    } else {
                        pending = true
                    }
                    AppLogRecorder.record("ResearchExamine", "AUTO_BLOCKED · actionId=${row.id} · reason=${dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor.redact(failure.message.orEmpty()).take(300)}", "W")
                }
            }
            cursor = batch.last().id
            if (admitted >= 100) pending = true
        }
        archive.dao.trimUnexamined(settings.getFeatureSettings().researchFeature.unexaminedStudyLimit, System.currentTimeMillis())
        return if (pending) Result.retry() else Result.success()
    }

    private fun configurationBlocked(failure: Exception): Boolean = failure.message.orEmpty().let { it.contains("Choose an enabled examination") || it.contains("consent") || it.contains("disabled") || it.contains("reviewer unavailable", true) || it.contains("REQUIRED_REVIEWER_UNAVAILABLE") || it.contains("policy changed", true) || it.contains("does not permit article retrieval") || it.contains("Article tools are excluded") || it.contains("Web-only scope") || it.contains("Increase Research Depth") || it.contains("Research is off") }

    companion object {
        fun enqueue(context: Context, reconsider: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork("research-examination-outbox", if (reconsider) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ResearchExaminationWorker>().setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS).build())
        }
    }
}
