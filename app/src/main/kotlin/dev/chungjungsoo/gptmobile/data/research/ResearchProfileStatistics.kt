package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationCheckpoint
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationJob
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage
import kotlinx.serialization.json.Json

@kotlinx.serialization.Serializable
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
    val lastInvocationId: String? = null,
    val statisticsSchemaVersion: Int = 2,
    val logicalStages: Int = 0,
    val physicalGenerations: Int = 0,
    val queuePolls: Int = 0,
    val unknownTransmissions: Int = 0,
    val admissionBlocks: Int = 0,
    val providerWaits: Int = 0,
    val queuedJobs: Int = 0,
    val cachedQueueResults: Int = 0,
    val uncorrelatedStageRecords: Int = 0,
    val uncorrelatedInvocations: Int = 0,
    val measuredInputTokens: Long = 0,
    val measuredOutputTokens: Long = 0,
    val estimatedInputTokens: Long = 0,
    val estimatedOutputTokens: Long = 0,
    val tokenProvenance: Map<String, Int> = emptyMap(),
    val reportedReasoningTokens: Long? = null,
    val visibleOutputEstimate: Long? = null,
    val sourcesPartiallyAnalyzed: Int = 0,
    val sourcesFullyAnalyzed: Int = 0,
    val sourcesInaccessible: Int = 0,
    val admissionReasons: Map<String, Int> = emptyMap(),
    val usageAvailable: Boolean = false,
    val missingInvocationRecords: Int = 0
)

/** Attribution survives corpus sharing. Missing legacy attribution never becomes invented work. */
internal fun researchProfileStatistics(root: String, participants: Set<String>, sessions: List<ResearchSession>, invocations: List<ModelInvocation>, jobs: List<ExaminationJob>): List<ResearchProfileStatistics> {
    val scopedSessions = sessions.filter { it.turnId == root }
    val sourceRows = scopedSessions.flatMap { it.snapshot.sources }
    val sources = sourceRows.groupBy { it.equivalenceGroup.ifBlank { it.canonicalUrl } }
    val discovered = sources.mapValues { (_, versions) -> versions.flatMap { it.discoveredByProfiles }.toSet().intersect(participants) }
    val readers = sources.mapValues { (_, versions) -> versions.filter { it.readable }.flatMap { it.readByProfiles }.toSet() }
    val scopedJobs = jobs.filter { runCatching { Json.decodeFromString<ExaminationCheckpoint>(it.checkpointJson).rootWorkId == root }.getOrDefault(false) }
    val complete = setOf(ExaminationStage.COMPLETE_FULL, ExaminationStage.COMPLETE_PARTIAL, ExaminationStage.ABSTRACT_ONLY)
    val scopedStages = scopedSessions.sortedBy { it.snapshot.updatedAt }.flatMap { it.snapshot.modelEvents }.associateBy { it.id }.values.toList()
    val linkedIds = scopedStages.flatMap { listOfNotNull(it.invocationId) + it.metrics?.invocationIds.orEmpty() }.toSet() +
        scopedJobs.flatMap { runCatching { Json.decodeFromString<ExaminationCheckpoint>(it.checkpointJson).invocationIds }.getOrDefault(emptyList()) }
    val requestGroups = invocations.filter { it.turnKey == root || it.id in linkedIds }.associateBy { it.id }.values.groupBy { it.profileUid }
    val stageGroups = scopedStages.groupBy { it.profileUid }
    val claims = scopedSessions.flatMap { it.snapshot.claims }.distinctBy { it.json().toString() }
    val completedSources = scopedSessions.flatMap { it.snapshot.extractedSourceVersions }.toSet()
    return participants.sorted().map { uid ->
        val requests = requestGroups[uid].orEmpty()
        val stages = stageGroups[uid].orEmpty().sortedBy { it.startedAt }
        val metrics = stages.mapNotNull { event -> event.metrics?.takeIf { it.schemaVersion == 1 && it.rootWorkId == root && it.stageAttemptId == event.id } }
        val transports = metrics.flatMap { it.transports }.groupBy { it.id }.values.map { observations ->
            observations.maxBy {
                when (it.state) {
                    "TRANSMITTED" -> 2
                    "NOT_DISPATCHED" -> 1
                    else -> 0
                }
            }
        }
        val localBlocks = stages.filter { it.outcome in setOf("SKIPPED", "WAITING_PROVIDER_LIMIT") && it.metrics?.transports?.none { transport -> transport.state == "TRANSMITTED" } == true }
        val blockedInvocations = stages.filter {
            it.outcome == "SKIPPED" &&
                it.metrics?.transports?.none { transport -> transport.state == "TRANSMITTED" } != false ||
                it.outcome == "WAITING_PROVIDER_LIMIT" &&
                it.providerLimit?.knownNotAccepted == true
        }
            .flatMap { listOfNotNull(it.invocationId) + it.metrics?.invocationIds.orEmpty() }.toSet()
        val queueJobs = metrics.flatMap { it.queueJobs }.distinctBy { it.id to it.invocationId }
        val cachedInvocations = queueJobs.filter { it.state == "CACHED" }.map { it.invocationId }.toSet()
        val queueByInvocation = queueJobs.groupBy { it.invocationId }.mapValues { (_, observations) -> observations.map { it.id }.distinct().singleOrNull() }
        fun usageKey(invocation: String) = queueByInvocation[invocation]?.let { "queue:$it" } ?: "invocation:$invocation"
        val counted = requests.filterNot { it.id in blockedInvocations || it.id in cachedInvocations || it.status == "NOT_DISPATCHED" || it.inputTokenSource == "not_dispatched" }
        fun input(request: ModelInvocation) = (if (request.status == "RUNNING") request.observedInputTokens ?: 0 else request.inputTokens.toLong()).coerceAtLeast(0)
        fun output(request: ModelInvocation) = (if (request.status == "RUNNING") request.observedOutputTokens ?: 0 else request.outputTokens.toLong()).coerceAtLeast(0)

        // Resuming one accepted job observes the same generation. Prefer a reported total;
        // otherwise retain its largest estimate, never the sum of repeated polling invocations.
        fun tokenTotals(value: (ModelInvocation) -> Long, source: (ModelInvocation) -> String): List<Pair<Long, String>> = counted.groupBy { usageKey(it.id) }.values.map { observations ->
            observations.maxWith(compareBy<ModelInvocation> { dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource(source(it)) }.thenBy(value)).let { value(it) to source(it) }
        }
        val inputTotals = tokenTotals(::input) { it.inputTokenSource }
        val outputTotals = tokenTotals(::output) { it.outputTokenSource }
        val usage = metrics.flatMap { it.usage }.associateBy { it.invocationId }.values.filterNot { it.invocationId in blockedInvocations || it.invocationId in cachedInvocations || it.inputSource == "not_dispatched" }
        fun observedTotal(value: (ResearchUsageObservation) -> Long?): Long? = usage.mapNotNull { observation -> value(observation)?.let { usageKey(observation.invocationId) to it } }
            .takeIf { it.isNotEmpty() }?.groupBy { it.first }?.values?.sumOf { group -> group.maxOf { it.second } }
        val invocationIds = requests.map { it.id }.toSet()
        val analyzed = claims.filter { claim -> claim.extractionInvocationId in invocationIds }.map { it.sourceId }.toSet()
        val fullyAnalyzed = sources.filterValues { versions -> versions.any { source -> source.id in analyzed && "${source.id}:${researchHash(source.versionId + source.passage)}" in completedSources } }.keys
        val partiallyAnalyzed = sources.filterValues { versions -> versions.any { it.id in analyzed } }.keys - fullyAnalyzed
        val lastStage = stages.lastOrNull()
        val examined = scopedJobs.filter { it.profileUid == uid && it.stage in complete }.map { it.workId }.toSet()
        ResearchProfileStatistics(
            uid,
            discovered.values.count { uid in it },
            discovered.values.count { it == setOf(uid) },
            readers.values.count { uid in it },
            examined.size,
            scopedJobs.filter { it.profileUid == uid && it.stage !in complete && it.stage != ExaminationStage.CANCELED }.map { it.workId }.toSet().minus(examined).size,
            inputTotals.sumOf { it.first },
            outputTotals.sumOf { it.first },
            (inputTotals + outputTotals).any { !dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource(it.second) },
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
            lastInvocationId = lastStage?.invocationId ?: metrics.lastOrNull()?.invocationIds?.lastOrNull() ?: requests.maxByOrNull { it.startedAt }?.id,
            logicalStages = metrics.map { it.logicalStageId }.distinct().size,
            physicalGenerations = transports.count { it.operation == "GENERATION" && it.state == "TRANSMITTED" },
            queuePolls = transports.count { it.operation == "QUEUE_POLL" && it.state == "TRANSMITTED" },
            unknownTransmissions = transports.count { it.state == "PREPARED" },
            admissionBlocks = localBlocks.size,
            providerWaits = stages.count { it.outcome == "WAITING_PROVIDER_LIMIT" },
            queuedJobs = queueJobs.map { it.id }.distinct().size,
            cachedQueueResults = cachedInvocations.size,
            uncorrelatedStageRecords = stages.count { event -> event.metrics !in metrics },
            uncorrelatedInvocations = requests.count { request -> metrics.none { request.id in it.invocationIds } },
            measuredInputTokens = inputTotals.filter { dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource(it.second) }.sumOf { it.first },
            measuredOutputTokens = outputTotals.filter { dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource(it.second) }.sumOf { it.first },
            estimatedInputTokens = inputTotals.filterNot { dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource(it.second) }.sumOf { it.first },
            estimatedOutputTokens = outputTotals.filterNot { dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource(it.second) }.sumOf { it.first },
            tokenProvenance = (inputTotals + outputTotals).map { it.second }.groupingBy { it }.eachCount().toSortedMap(),
            reportedReasoningTokens = observedTotal { it.reasoningTokens },
            visibleOutputEstimate = observedTotal { it.visibleOutputEstimate },
            sourcesPartiallyAnalyzed = partiallyAnalyzed.size,
            sourcesFullyAnalyzed = fullyAnalyzed.size,
            sourcesInaccessible = sources.values.count { versions -> versions.any { uid in it.discoveredByProfiles } && versions.none { it.readable } && versions.any { it.status in setOf("Blocked", "Failed", "Unavailable") } },
            admissionReasons = localBlocks.groupingBy { it.reason.ifBlank { it.failureCategory.ifBlank { "UNKNOWN" } } }.eachCount().toSortedMap(),
            usageAvailable = counted.isNotEmpty() && (inputTotals + outputTotals).any { it.first > 0 || it.second !in setOf("unknown", "unreported", "not_dispatched", "") },
            missingInvocationRecords = (stages.flatMap { listOfNotNull(it.invocationId) + it.metrics?.invocationIds.orEmpty() }.toSet() - requests.map { it.id }.toSet()).size
        )
    }
}
