package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
enum class CombinedRunRole {
    CONTRIBUTOR,
    RESEARCH,
    VERIFIER,
    SYNTHESIS,
    REPAIR,
    CONTINUATION;

    val finalization: Boolean get() = this in setOf(SYNTHESIS, REPAIR, CONTINUATION)
}

@Serializable
enum class CombinedCapOrigin { USER_SET, PROFILE_CONFIGURED, PROVIDER, DEVICE, APPLICATION_DEFAULT, LEGACY_UNKNOWN }

@Serializable
enum class CombinedCapScope { INVOCATION, WORKER_RUN, COMBINED_TURN }

@Serializable
data class CombinedCapRecord(
    val name: String,
    val value: Long,
    val origin: CombinedCapOrigin,
    val scope: CombinedCapScope,
    val reason: String,
    val profileUid: String? = null
)

/** Input and generated tokens are independent dimensions; visible/reasoning are output subsets. */
@Serializable
data class CombinedResourceQuota(
    val inputTokens: Long = 0,
    val generatedTokens: Long = 0,
    val toolOperations: Long = 0
) {
    init {
        require(inputTokens >= 0 && generatedTokens >= 0 && toolOperations >= 0)
    }

    operator fun plus(other: CombinedResourceQuota) = CombinedResourceQuota(
        Math.addExact(inputTokens, other.inputTokens),
        Math.addExact(generatedTokens, other.generatedTokens),
        Math.addExact(toolOperations, other.toolOperations)
    )

    fun minusFloor(other: CombinedResourceQuota) = CombinedResourceQuota(
        (inputTokens - other.inputTokens).coerceAtLeast(0),
        (generatedTokens - other.generatedTokens).coerceAtLeast(0),
        (toolOperations - other.toolOperations).coerceAtLeast(0)
    )

    fun fits(available: CombinedResourceQuota): Boolean = inputTokens <= available.inputTokens &&
        generatedTokens <= available.generatedTokens &&
        toolOperations <= available.toolOperations

    fun scale(count: Long) = CombinedResourceQuota(
        Math.multiplyExact(inputTokens, count),
        Math.multiplyExact(generatedTokens, count),
        Math.multiplyExact(toolOperations, count)
    )
}

@Serializable
data class CombinedHardLimits(
    val inputTokens: Long? = null,
    val generatedTokens: Long? = null,
    val totalTokens: Long? = null,
    val toolOperations: Long? = null,
    val costMicros: Long? = null,
    val deadlineEpochMillis: Long? = null
) {
    init {
        require(listOfNotNull(inputTokens, generatedTokens, totalTokens, toolOperations, costMicros, deadlineEpochMillis).all { it >= 0 })
    }
}

@Serializable
data class CombinedResearchRequirement(
    val depth: Int,
    val sourceGoal: Long,
    val contentScope: String,
    val gatewayConnectionUid: String? = null,
    val allowNativeFallback: Boolean = false,
    val examinationRequested: Boolean = false,
    val independentReviewRequested: Boolean = false
) {
    init {
        require(depth in 1..100 && sourceGoal > 0)
        require(contentScope in setOf("WEB_AND_PAPERS", "PAPERS_ONLY", "WEB_ONLY"))
    }
}

@Serializable
data class CombinedRequestedPolicy(
    val version: Int = 1,
    // The frozen v1 wire default remains strict. New conversation preferences
    // explicitly seal their continuation policy without rewriting archived turns.
    val settings: ConversationCombinedSettings = ConversationCombinedSettings(continueLongAnswers = false),
    val participantUids: List<String> = emptyList(),
    val requestedModels: Map<String, String> = emptyMap(),
    val requestedTemperatures: Map<String, Float?> = emptyMap(),
    val preferredOwnerUid: String? = null,
    val userPrompt: String = "",
    val acceptedAtMillis: Long = 0,
    val settingsRevision: Long = 0,
    val requestedPolicyDigest: String = "",
    val acceptedResearch: CombinedResearchRequirement? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val singlePass: Boolean = false,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val completionPolicy: String? = null
) {
    /** Absent v1 fields retain their original serialized digest and one-request contract. */
    val appendContinuations: Boolean get() = version == 2 && singlePass && settings.continueLongAnswers && completionPolicy == "APPEND_CONTINUATIONS_V1"
}

/** Finite work sealed by the controller. Workers cannot add scope by calling a tool. */
@Serializable
enum class CombinedWorkKind { STAGE, EVIDENCE }

@Serializable
data class CombinedWorkItem(
    val id: String,
    val role: CombinedRunRole,
    val remainingUnits: Long = 1,
    val required: Boolean = true,
    val reason: String = "USER_REQUEST",
    val kind: CombinedWorkKind = CombinedWorkKind.STAGE,
    val description: String = "",
    val sourceUrls: List<String> = emptyList(),
    val assignedOwnerKey: String? = null
)

/** Workers claim sources from one task ledger; changing the worker never creates another goal. */
@Serializable
data class CombinedResearchAssignment(
    val coordinatorProfileUid: String,
    val eligibleWorkerUids: List<String>,
    val sourceGoal: Long,
    val strategy: String = "SHARED_SOURCE_CLAIMS"
)

@Serializable
data class CombinedWorkPlan(
    val version: Int = 1,
    val requested: CombinedRequestedPolicy,
    val baselineByOwner: Map<String, CombinedResourceQuota>,
    val finalizationReserve: CombinedResourceQuota,
    val optionalAllowance: CombinedResourceQuota,
    val costReservesByOwner: Map<String, Long> = emptyMap(),
    val hardLimits: CombinedHardLimits = CombinedHardLimits(),
    val caps: List<CombinedCapRecord> = emptyList(),
    val workItems: List<CombinedWorkItem> = emptyList(),
    val blockedParticipantUids: Set<String> = emptySet(),
    val unfundedRequiredWorkIds: Set<String> = emptySet(),
    val initialDraftOutputTokens: Int = 2048,
    val finalOutputTokens: Int = 2500,
    val verificationOutputTokens: Int = 1024,
    val appraisalOutputTokens: Int = 6144,
    val toolsAllowed: Boolean = true,
    val searchAllowed: Boolean = true,
    val renewable: Boolean = false,
    val maxParallelToolCalls: Int = 6,
    val effectivePlanDigest: String = "",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val researchAssignment: CombinedResearchAssignment? = null
) {
    val baseline: CombinedResourceQuota get() = baselineByOwner.values.fold(CombinedResourceQuota()) { total, quota -> total + quota }
    val totalInitialAllowance: CombinedResourceQuota get() = baseline + finalizationReserve + optionalAllowance
    val requiredCorrectionAllowed: Boolean get() = finalizationReserve.generatedTokens > 0
    val verificationAllowed: Boolean get() = baselineByOwner.containsKey("verification")

    fun outputTokens(role: CombinedRunRole): Int = when (role) {
        CombinedRunRole.CONTRIBUTOR -> initialDraftOutputTokens
        CombinedRunRole.RESEARCH -> appraisalOutputTokens
        CombinedRunRole.VERIFIER -> verificationOutputTokens
        else -> finalOutputTokens
    }
}

/** Stable stage assignments keep Research analysis away from verification and writer escrow. */
object CombinedResearchStages {
    val names = listOf("plan", "extract", "find_gaps", "review", "appraisal", "independent_review", "study_review")
    fun outputTokens(stage: String): Int = when (stage) {
        "appraisal" -> 6144
        "study_review" -> 4096
        else -> 2048
    }
    fun id(profileUid: String, stage: String): String = "research:$profileUid:$stage"
    fun taskId(stage: String): String = "research:task:$stage"
    fun isTaskStage(id: String): Boolean = names.any { id == taskId(it) }
    fun id(context: CombinedExecutionContext, profileUid: String, stage: String): String =
        taskId(stage).takeIf { id -> context.plan?.workItems?.any { it.id == id } == true } ?: id(profileUid, stage)
    fun item(context: CombinedExecutionContext, profileUid: String, requestRole: String): CombinedWorkItem? {
        val stage = when (requestRole) {
            "research-extraction" -> "extract"
            "research-appraisal", "research-appraisal-part" -> "appraisal"
            "research-independent-review" -> "independent_review"
            "research-study-review", "research-study-review-part" -> "study_review"
            else -> requestRole.removePrefix("research-").takeIf { requestRole.startsWith("research-") && it in names }
        } ?: return null
        return context.plan?.workItems?.firstOrNull { it.id == id(context, profileUid, stage) }
    }
}

/** Serializable routing context, never a tool grant. A role change keeps the same accepted turn. */
@Serializable
data class CombinedExecutionContext(
    val requested: CombinedRequestedPolicy,
    val plan: CombinedWorkPlan? = null,
    val rootBudgetId: String,
    val role: CombinedRunRole = CombinedRunRole.CONTRIBUTOR,
    val profileUid: String? = null,
    val workItemId: String? = null,
    val chatId: Int = 0,
    val persistBudget: Boolean = true,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val acceptedTask: AcceptedTaskRef? = null
) {
    fun forRole(role: CombinedRunRole, profileUid: String? = this.profileUid, workItemId: String? = if (role == this.role) this.workItemId else null) =
        copy(role = role, profileUid = profileUid, workItemId = workItemId)

    /** Physical helper identity belongs to its invocation; shared stage ownership stays with its accepted worker. */
    fun forResearchTarget(targetUid: String, stageId: String?) = forRole(
        CombinedRunRole.RESEARCH,
        if (stageId != null && CombinedResearchStages.isTaskStage(stageId)) profileUid ?: targetUid else targetUid,
        stageId
    )

    val ownerKey: String get() = when (role) {
        CombinedRunRole.CONTRIBUTOR -> "contributor:${profileUid.orEmpty()}"
        CombinedRunRole.RESEARCH -> plan?.workItems?.firstOrNull { it.id == workItemId }?.assignedOwnerKey ?: "research:${profileUid.orEmpty()}"
        CombinedRunRole.VERIFIER -> "verification"
        else -> "finalization"
    }
}

internal fun combinedDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
