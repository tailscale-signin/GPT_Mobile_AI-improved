package dev.chungjungsoo.gptmobile.data.delegation

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DelegationPlanStep(
    val id: String,
    val role: DelegationTemplate.Role = DelegationTemplate.Role.RESEARCH,
    val instruction: String = "Gather supported findings and cite the supplied evidence.",
    val dependsOn: List<String> = emptyList(),
    val workerUid: String = "",
    val outputTokens: Int = 512
)

/** Plans grant no tools or destinations. The existing admission policy remains authoritative. */
@Serializable
data class DelegationTaskPlan(val version: Int = 1, val steps: List<DelegationPlanStep> = emptyList()) {
    fun validate() {
        require(version == 1 && steps.size <= 12) { "Unsupported plan version or more than 12 steps" }
        require(steps.map { it.id }.distinct().size == steps.size) { "Duplicate step IDs" }
        steps.forEach { step ->
            require(step.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid step ID" }
            require(step.instruction.isNotBlank() && step.instruction.length <= 1000 && step.outputTokens in 128..4096) { "Invalid step instruction or output budget" }
            require(step.dependsOn.distinct().size == step.dependsOn.size && step.dependsOn.all { parent -> parent != step.id && steps.any { it.id == parent } }) { "Missing or self dependency" }
        }
        ordered()
    }
    fun ordered(): List<DelegationPlanStep> {
        val result = mutableListOf<DelegationPlanStep>()
        val remaining = steps.toMutableList()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { step -> step.dependsOn.all { id -> result.any { it.id == id } } }
                ?: throw IllegalArgumentException("Plan dependency cycle")
            result += next
            remaining.remove(next)
        }
        return result
    }
    fun fingerprint(policy: String) = researchHash(Json.encodeToString(this) + "\n" + policy)
}

@Serializable
enum class PlanStepState { PENDING, RUNNING, COMPLETE, FAILED, INTERRUPTED, RECONCILIATION_REQUIRED }

@Serializable
data class DelegationStepCheckpoint(
    val id: String,
    val status: PlanStepState = PlanStepState.PENDING,
    val attempts: Int = 0,
    val output: String = "",
    val evidenceIds: List<String> = emptyList(),
    val workerIdentity: String = "",
    val reason: String? = null
)

@Serializable
data class DelegationPlanCheckpoint(
    val version: Int = 1,
    val fingerprint: String,
    val steps: List<DelegationStepCheckpoint>,
    val reservedCalls: Int = 0,
    val deadlineEpochMs: Long = 0,
    val failedTokens: Long = 0,
    val canceledTokens: Long = 0
) {
    fun validate(plan: DelegationTaskPlan) {
        require(version == 1 && fingerprint.matches(Regex("[a-f0-9]{64}")) && reservedCalls in 0..48)
        require(failedTokens >= 0 && canceledTokens >= 0)
        require(steps.map { it.id } == plan.ordered().map { it.id })
        require(steps.all { it.attempts in 0..48 && it.output.length <= 32768 && it.evidenceIds.size <= 2400 })
    }
    fun interrupted() = copy(steps = steps.map { if (it.status == PlanStepState.RUNNING) it.copy(status = PlanStepState.INTERRUPTED, reason = "PROCESS_INTERRUPTED") else it })
}
