package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Each lane owns its evidence and denominator; contract coverage never becomes a coding score. */
@Serializable
enum class BenchmarkEngineeringLane(val label: String, val description: String, val evidenceClass: String) {
    TOOL_CONTRACT("Tool contracts", "Prescribed tool names and arguments with isolated responses.", "OFFLINE_TOOL_CONTRACT"),
    WORKFLOW_SIMULATION("Workflow simulation", "Choose actions, read returned evidence and complete isolated workflows.", "OFFLINE_ENGINEERING_SIMULATION"),
    EXECUTABLE_CODING("Executable coding", "Repair frozen code tasks and run independent hidden and regression checks.", "VERIFIED_EXECUTABLE_CODING"),
    LIVE_GITHUB("Live GitHub", "Complete authorized disposable repository tasks with remote state readback.", "VERIFIED_LIVE_GITHUB")
}

@Serializable
enum class BenchmarkEngineeringExternalTransport { GATEWAY, PHONE_GITHUB_ACTIONS }

@Serializable
enum class BenchmarkEngineeringOrdering { SELECTED_ORDER, COUNTERBALANCED }

@Serializable
data class BenchmarkEngineeringBudget(
    val taskDeadlineMs: Long = 300_000,
    val outputTokensPerGeneration: Int = 4096,
    val maxToolCalls: Int = 24,
    val maxModelRounds: Int = 16,
    val maxRepairAttempts: Int = 2
) {
    fun valid(): Boolean = taskDeadlineMs in 10_000..3_600_000 &&
        outputTokensPerGeneration in 256..32_768 &&
        maxToolCalls in 1..128 &&
        maxModelRounds in 1..64 &&
        maxRepairAttempts in 0..5 &&
        maxModelRounds >= maxRepairAttempts + 1
}

/** Frozen before a model receives a task. Credentials are resolved at dispatch and never retained here. */
@Serializable
data class BenchmarkEngineeringPlan(
    val id: String,
    val lane: BenchmarkEngineeringLane,
    val repeats: Int,
    val ordering: BenchmarkEngineeringOrdering,
    val profileUids: List<String>,
    val profileConfigKeys: Map<String, String>,
    val fixtureManifestHash: String,
    val executionPolicy: BenchmarkExecutionPolicy,
    val budget: BenchmarkEngineeringBudget,
    val sourceCommit: String,
    val environmentDigest: String,
    val permissionsDigest: String,
    val cohortId: String = "",
    val registryVersion: Int = BenchmarkEngineeringRegistry.VERSION,
    val preset: String = "quick",
    val seed: Int = 17,
    val taskPack: String = "coding",
    val repositoryFullName: String? = null,
    val baseSha: String? = null,
    val externalTransport: BenchmarkEngineeringExternalTransport = BenchmarkEngineeringExternalTransport.GATEWAY,
    val githubConnectionUid: String? = null,
    val externalFixtureDigests: Map<String, String> = emptyMap(),
    val graderDigest: String? = null,
    val externalRouteDigests: Map<String, String> = emptyMap(),
    val externalAuthorityDigests: Map<String, String> = emptyMap(),
    val externalSetupMs: Long? = null,
    val externalSetupRequestCount: Int? = null,
    val practicalMarginPercentagePoints: Double = 5.0,
    val minimumIndependentFamilies: Int = 12,
    val minimumRepeats: Int = 3,
    val contextExperiment: BenchmarkContextExperiment? = null,
    val contextManifestDigests: Map<String, String> = emptyMap(),
    val contextSourceIdentities: Map<String, BenchmarkContextSourceIdentity> = emptyMap()
) {
    fun identityDigest(): String = benchmarkHash(
        (
            listOf(
                id, registryVersion, lane, repeats, ordering, profileUids.joinToString(","),
                profileConfigKeys.toSortedMap(), fixtureManifestHash, executionPolicy, budget,
                sourceCommit, environmentDigest, permissionsDigest, preset, seed, taskPack,
                repositoryFullName, baseSha, externalTransport, githubConnectionUid, externalFixtureDigests.toSortedMap(), graderDigest, externalRouteDigests.toSortedMap(), externalAuthorityDigests.toSortedMap(),
                practicalMarginPercentagePoints, minimumIndependentFamilies, minimumRepeats
            ) + if (contextExperiment == null && contextManifestDigests.isEmpty() && contextSourceIdentities.isEmpty()) emptyList() else listOf(contextExperiment, contextManifestDigests.toSortedMap(), contextSourceIdentities.toSortedMap())
            ).joinToString("\u0000")
    )

    fun frozen(): BenchmarkEngineeringPlan = copy(cohortId = identityDigest())

    fun valid(): Boolean = id.isNotBlank() &&
        registryVersion == BenchmarkEngineeringRegistry.VERSION &&
        repeats in 1..10 &&
        profileUids.size in 1..2 &&
        profileUids.distinct().size == profileUids.size &&
        profileConfigKeys.keys == profileUids.toSet() &&
        profileConfigKeys.values.all { it.matches(Regex("[0-9a-f]{64}")) } &&
        fixtureManifestHash.matches(Regex("[0-9a-f]{64}")) &&
        environmentDigest.matches(Regex("[0-9a-f]{64}")) &&
        permissionsDigest.matches(Regex("[0-9a-f]{64}")) &&
        sourceCommit.isNotBlank() &&
        budget.valid() &&
        preset in setOf("quick", "standard", "deep") &&
        taskPack in setOf("coding", "repository") &&
        (taskPack == "coding" || lane in setOf(BenchmarkEngineeringLane.EXECUTABLE_CODING, BenchmarkEngineeringLane.LIVE_GITHUB)) &&
        (
            externalTransport != BenchmarkEngineeringExternalTransport.PHONE_GITHUB_ACTIONS ||
                lane == BenchmarkEngineeringLane.LIVE_GITHUB &&
                !githubConnectionUid.isNullOrBlank() &&
                repositoryFullName?.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) == true &&
                baseSha?.matches(Regex("[0-9a-f]{40}")) == true
            ) &&
        (externalTransport != BenchmarkEngineeringExternalTransport.GATEWAY || githubConnectionUid == null) &&
        practicalMarginPercentagePoints in 0.0..25.0 &&
        minimumIndependentFamilies >= 2 &&
        minimumRepeats >= 1 &&
        (contextExperiment == null || contextExperiment.valid() && profileUids.size == 1 && lane in setOf(BenchmarkEngineeringLane.EXECUTABLE_CODING, BenchmarkEngineeringLane.LIVE_GITHUB)) &&
        contextManifestDigests.all { (task, hash) -> task.isNotBlank() && hash.matches(Regex("[0-9a-f]{64}")) } &&
        contextSourceIdentities.keys == contextManifestDigests.keys &&
        contextSourceIdentities.values.all { it.valid() } &&
        (contextExperiment != null || contextManifestDigests.isEmpty()) &&
        (externalRouteDigests.isEmpty() || externalRouteDigests.keys == profileUids.toSet() && externalRouteDigests.values.all { it.matches(Regex("[0-9a-f]{64}")) }) &&
        (externalAuthorityDigests.isEmpty() || externalAuthorityDigests.keys == profileUids.toSet() && externalAuthorityDigests.values.all { it.matches(Regex("[0-9a-f]{64}")) }) &&
        externalFixtureDigests.all { (task, digest) -> task.isNotBlank() && digest.matches(Regex("[0-9a-f]{64}")) } &&
        (graderDigest == null || graderDigest.matches(Regex("[0-9a-f]{64}"))) &&
        (externalSetupMs == null || externalSetupMs >= 0) &&
        (externalSetupRequestCount == null || externalSetupRequestCount >= 0) &&
        cohortId == identityDigest()

    fun matches(cases: List<BenchmarkCase>, requireExternalReceipts: Boolean = false): Boolean {
        if (!valid() ||
            cases.isEmpty() ||
            BenchmarkEngineeringRegistry.manifestHash(cases) != fixtureManifestHash ||
            cases.map { it.id }.distinct().size != cases.size ||
            cases.map { it.engineeringTaskId }.distinct().size != cases.size ||
            cases.any { it.engineeringLane != lane || it.engineeringTaskId.isNullOrBlank() || it.engineeringFamilyId.isNullOrBlank() || it.engineeringBudget != budget }
        ) {
            return false
        }
        val external = lane in setOf(BenchmarkEngineeringLane.EXECUTABLE_CODING, BenchmarkEngineeringLane.LIVE_GITHUB)
        return !external ||
            !requireExternalReceipts ||
            externalFixtureDigests.keys == cases.mapNotNull { it.engineeringTaskId }.toSet() &&
            (contextExperiment == null || contextManifestDigests.keys == externalFixtureDigests.keys) &&
            graderDigest != null &&
            externalRouteDigests.keys == profileUids.toSet()
    }
}

data class BenchmarkEngineeringPreview(
    val lane: BenchmarkEngineeringLane,
    val label: String,
    val taskCount: Int,
    val taskFamilies: Int,
    val repeats: Int,
    val totalTasksPerModel: Int,
    val requiresGateway: Boolean,
    val description: String,
    val qualification: String,
    val preset: String,
    val taskPack: String = "coding",
    val budget: BenchmarkEngineeringBudget = BenchmarkEngineeringBudget(),
    val externalTransport: BenchmarkEngineeringExternalTransport = BenchmarkEngineeringExternalTransport.GATEWAY
)

object BenchmarkEngineeringRegistry {
    const val VERSION = 1

    fun defaultBudget(lane: BenchmarkEngineeringLane): BenchmarkEngineeringBudget = BenchmarkEngineeringBudget(
        taskDeadlineMs = if (lane == BenchmarkEngineeringLane.LIVE_GITHUB) 900_000 else 300_000
    )

    fun cases(lane: BenchmarkEngineeringLane, preset: String = "quick", seed: Int = 17, allActions: Boolean = true, taskPack: String = "coding"): List<BenchmarkCase> {
        require(preset in setOf("quick", "standard", "deep"))
        require(taskPack in setOf("coding", "repository") && (taskPack == "coding" || lane in setOf(BenchmarkEngineeringLane.EXECUTABLE_CODING, BenchmarkEngineeringLane.LIVE_GITHUB)))
        return when (lane) {
            BenchmarkEngineeringLane.TOOL_CONTRACT -> GitHubProfileAuditSuite.cases(allActions)
            BenchmarkEngineeringLane.WORKFLOW_SIMULATION -> GitHubEngineeringBenchmarkSuite.cases(autonomous = true)
            BenchmarkEngineeringLane.EXECUTABLE_CODING, BenchmarkEngineeringLane.LIVE_GITHUB -> benchmarkExternalEngineeringCases(lane, preset, seed, taskPack)
        }.map { test ->
            test.copy(
                engineeringLane = lane,
                engineeringTaskId = test.engineeringTaskId ?: test.id,
                engineeringFamilyId = test.engineeringFamilyId ?: when (lane) {
                    BenchmarkEngineeringLane.TOOL_CONTRACT -> test.id.substringAfter(':').substringBefore(':')
                    else -> test.id.substringAfterLast(':')
                }
            )
        }
    }

    fun manifestHash(cases: List<BenchmarkCase>): String = benchmarkHash(Json.encodeToString(cases))

    fun preview(lane: BenchmarkEngineeringLane, repeats: Int = 3, preset: String = "quick", allActions: Boolean = true, taskPack: String = "coding", externalTransport: BenchmarkEngineeringExternalTransport = BenchmarkEngineeringExternalTransport.GATEWAY): BenchmarkEngineeringPreview {
        require(repeats in 1..10)
        val cases = cases(lane, preset, allActions = allActions, taskPack = taskPack)
        return BenchmarkEngineeringPreview(
            lane, lane.label, cases.size, cases.map { it.engineeringFamilyId }.distinct().size,
            repeats, cases.size * repeats,
            lane in setOf(BenchmarkEngineeringLane.EXECUTABLE_CODING, BenchmarkEngineeringLane.LIVE_GITHUB) && externalTransport == BenchmarkEngineeringExternalTransport.GATEWAY,
            lane.description,
            when (lane) {
                BenchmarkEngineeringLane.TOOL_CONTRACT -> "Contract accuracy only. Coding and live repository completion are not tested."
                BenchmarkEngineeringLane.WORKFLOW_SIMULATION -> "Simulated workflow results. No real GitHub write, CI or Copilot job is claimed."
                BenchmarkEngineeringLane.EXECUTABLE_CODING -> "A task is solved only when independent executable checks verify its submitted artifact."
                BenchmarkEngineeringLane.LIVE_GITHUB -> "Requires a configured authorized disposable repository and matching remote completion receipts."
            },
            preset, taskPack, defaultBudget(lane), externalTransport
        )
    }

    fun queue(profiles: List<PlatformV2>, plan: BenchmarkEngineeringPlan): List<BenchmarkQueueItem> {
        require(plan.valid())
        require(profiles.map { it.uid } == plan.profileUids)
        return benchmarkEngineeringQueueOrder(plan).mapIndexed { ordinal, entry ->
            val (uid, repeat, variant) = entry
            val profile = profiles.single { it.uid == uid }
            BenchmarkQueueItem(
                ordinal,
                profile.benchmarkSnapshot(),
                UUID.randomUUID().toString(),
                requireNotNull(plan.profileConfigKeys[profile.uid]),
                engineeringRepeatIndex = repeat,
                engineeringContextVariant = variant
            )
        }
    }
}

@Serializable
enum class BenchmarkEngineeringFailureDomain { MODEL, UNSUPPORTED, INFRASTRUCTURE, HARNESS, USER, UNKNOWN }

/** Independent grader evidence is bound to a task, fixture and candidate, never to model prose. */
@Serializable
data class BenchmarkEngineeringVerification(
    val taskId: String,
    val fixtureDigest: String,
    val graderDigest: String,
    val candidateDigest: String?,
    val receiptId: String,
    val evidenceDigest: String,
    val evidenceClass: String,
    val independentChecksExecuted: Boolean,
    val requiredChecksKnown: Boolean,
    val hiddenChecksPassed: Boolean?,
    val regressionPassed: Boolean?,
    val integrityPassed: Boolean?,
    val firstAttemptPassed: Boolean?,
    val finalArtifactPassed: Boolean?,
    val submittedAttempts: Int,
    val testedRevision: String? = null,
    val publishedRevision: String? = null,
    val remoteReadbackVerified: Boolean? = null,
    val failureDomain: BenchmarkEngineeringFailureDomain? = null,
    val reasonCode: String? = null,
    val passedChecks: Int? = null,
    val totalChecks: Int? = null,
    val bestAttemptPassed: Boolean? = null,
    val selectedAttemptIndex: Int? = null,
    val finalAttemptIndex: Int? = null,
    val remoteEvidence: JsonObject? = null,
    val verificationScope: String = "INDEPENDENT_GRADER"
) {
    fun boundTo(plan: BenchmarkEngineeringPlan, test: BenchmarkCase): Boolean =
        taskId == (test.engineeringTaskId ?: test.id) &&
            fixtureDigest == plan.externalFixtureDigests[taskId] &&
            graderDigest == plan.graderDigest &&
            listOf(fixtureDigest, graderDigest, evidenceDigest).all { it.matches(Regex("[0-9a-f]{64}")) } &&
            candidateDigest?.matches(Regex("[0-9a-f]{64}")) == true &&
            receiptId.isNotBlank() &&
            submittedAttempts in 1..(plan.budget.maxRepairAttempts + 1) &&
            evidenceClass == plan.lane.evidenceClass &&
            verificationScope in setOf("INDEPENDENT_GRADER", "LOCAL_GENERATION_CONTRACT") &&
            (
                verificationScope != "LOCAL_GENERATION_CONTRACT" ||
                    failureDomain == BenchmarkEngineeringFailureDomain.MODEL &&
                    finalArtifactPassed == false &&
                    !independentChecksExecuted &&
                    !requiredChecksKnown &&
                    hiddenChecksPassed == null &&
                    regressionPassed == null &&
                    integrityPassed == null &&
                    testedRevision == null &&
                    publishedRevision == null &&
                    remoteReadbackVerified != true &&
                    remoteEvidence == null
                )

    fun taskComplete(plan: BenchmarkEngineeringPlan, test: BenchmarkCase): Boolean = boundTo(plan, test) &&
        verificationScope == "INDEPENDENT_GRADER" &&
        independentChecksExecuted &&
        requiredChecksKnown &&
        hiddenChecksPassed == true &&
        regressionPassed == true &&
        integrityPassed == true &&
        finalArtifactPassed == true &&
        totalChecks != null &&
        totalChecks > 0 &&
        passedChecks == totalChecks &&
        (
            plan.lane != BenchmarkEngineeringLane.LIVE_GITHUB ||
                remoteReadbackVerified == true &&
                testedRevision != null &&
                testedRevision == publishedRevision
            )
}

/** Queue order, snapshots and repeat membership are accepted together with the task plan. */
internal fun benchmarkEngineeringQueueMatches(batch: BenchmarkBatch, plan: BenchmarkEngineeringPlan): Boolean {
    if (batch.id != plan.id ||
        batch.mode != BenchmarkMode.GITHUB_INTEGRATION ||
        batch.executionPolicy != plan.executionPolicy ||
        batch.delegationPrimary != null ||
        batch.delegationReviewer != null ||
        batch.delegationSettings != null
    ) {
        return false
    }
    val expected = benchmarkEngineeringQueueOrder(plan)
    return batch.items.map { Triple(it.profile.uid, it.engineeringRepeatIndex, it.engineeringContextVariant) } == expected &&
        batch.items.withIndex().all { (index, item) ->
            item.ordinal == index &&
                item.configKey == plan.profileConfigKeys[item.profile.uid] &&
                benchmarkConfigKey(item.profile, batch.localEnvironment, plan.executionPolicy) == item.configKey
        } &&
        batch.items.map { it.runId }.distinct().size == batch.items.size
}

internal fun benchmarkEngineeringCleanupReceipt(external: Boolean, sample: BenchmarkSample?, localCleanup: String): String =
    if (external) sample?.cleanupReceipt ?: localCleanup else localCleanup
