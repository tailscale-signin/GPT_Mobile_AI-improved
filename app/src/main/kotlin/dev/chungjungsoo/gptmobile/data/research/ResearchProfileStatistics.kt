package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationCheckpoint
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationJob
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage
import kotlinx.serialization.json.Json

data class ResearchProfileStatistics(
    val profileUid: String,
    val sources: Int = 0,
    val uniqueSources: Int = 0,
    val sourcesRead: Int = 0,
    val studiesExamined: Int = 0,
    val examinationsPending: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val estimatedTokens: Boolean = false,
    val modelRequests: Int = 0,
    val completedRequests: Int = 0,
    val failedRequests: Int = 0,
    val runningRequests: Int = 0,
    val cancelledRequests: Int = 0,
    val acceptedStages: Int = 0,
    val failedStages: Int = 0,
    val skippedStages: Int = 0,
    val modelDurationMs: Long = 0,
    val lastStage: String? = null,
    val lastStageOutcome: String? = null,
    val lastFailureCategory: String? = null,
    val lastInvocationId: String? = null
)

/** Attribution survives corpus sharing. Missing legacy attribution never becomes invented work. */
internal fun researchProfileStatistics(root: String, participants: Set<String>, sessions: List<ResearchSession>, invocations: List<ModelInvocation>, jobs: List<ExaminationJob>): List<ResearchProfileStatistics> {
    val sources = sessions.filter { it.turnId == root }.flatMap { it.snapshot.sources }.groupBy { it.equivalenceGroup.ifBlank { it.canonicalUrl } }
    val discovered = sources.mapValues { (_, versions) -> versions.flatMap { it.discoveredByProfiles }.toSet().intersect(participants) }
    val readers = sources.mapValues { (_, versions) -> versions.filter { it.readable }.flatMap { it.readByProfiles }.toSet() }
    val scopedJobs = jobs.filter { runCatching { Json.decodeFromString<ExaminationCheckpoint>(it.checkpointJson).rootWorkId == root }.getOrDefault(false) }
    val complete = setOf(ExaminationStage.COMPLETE_FULL, ExaminationStage.COMPLETE_PARTIAL, ExaminationStage.ABSTRACT_ONLY)
    return participants.sorted().map { uid ->
        val requests = invocations.filter { it.turnKey == root && it.profileUid == uid }.distinctBy { it.id }
        val stages = sessions.filter { it.turnId == root }.sortedBy { it.snapshot.updatedAt }.flatMap { it.snapshot.modelEvents }
            .filter { it.profileUid == uid }.associateBy { it.id }.values.sortedBy { it.startedAt }
        val lastStage = stages.lastOrNull()
        val examined = scopedJobs.filter { it.profileUid == uid && it.stage in complete }.map { it.workId }.toSet()
        ResearchProfileStatistics(
            uid,
            discovered.values.count { uid in it },
            discovered.values.count { it == setOf(uid) },
            readers.values.count { uid in it },
            examined.size,
            scopedJobs.filter { it.profileUid == uid && it.stage !in complete && it.stage != ExaminationStage.CANCELED }.map { it.workId }.toSet().minus(examined).size,
            requests.sumOf { if (it.status == "RUNNING") it.observedInputTokens ?: 0 else it.inputTokens.toLong() },
            requests.sumOf { if (it.status == "RUNNING") it.observedOutputTokens ?: 0 else it.outputTokens.toLong() },
            requests.any { it.estimated },
            requests.size,
            completedRequests = requests.count { it.status == "COMPLETED" },
            failedRequests = requests.count { it.status in setOf("FAILED", "ERROR") },
            runningRequests = requests.count { it.status == "RUNNING" },
            cancelledRequests = requests.count { it.status in setOf("CANCELLED", "CANCELED", "INTERRUPTED") },
            acceptedStages = stages.count { it.stageAccepted },
            failedStages = stages.count { it.outcome in setOf("FAILED", "INVALID_RESPONSE", "VALIDATION_FAILED", "TIMED_OUT") },
            skippedStages = stages.count { it.outcome == "SKIPPED" },
            modelDurationMs = requests.sumOf { it.durationMs.coerceAtLeast(0) },
            lastStage = lastStage?.stage,
            lastStageOutcome = lastStage?.outcome,
            lastFailureCategory = stages.lastOrNull { it.failureCategory.isNotBlank() }?.failureCategory,
            lastInvocationId = lastStage?.invocationId ?: requests.maxByOrNull { it.startedAt }?.id
        )
    }
}
