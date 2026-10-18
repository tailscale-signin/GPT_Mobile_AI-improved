package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.tool.selectionId
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

/** Inputs are pre-admitted read-only tools and profiles. This component cannot grant access. */
internal object LocalAssistToolBinder {
    suspend fun bind(
        tools: List<ResolvedAgentTool>,
        candidates: List<PlatformV2>,
        enabled: Boolean,
        policy: LocalAssistPolicy,
        goal: String,
        turnId: String,
        eligible: suspend (PlatformV2) -> Boolean,
        profileAllowed: suspend (PlatformV2) -> Boolean,
        generate: suspend (PlatformV2, String, Int) -> String,
        notice: suspend (String) -> Unit,
        stillEnabled: suspend () -> Boolean,
        producerInvocationId: () -> String? = { null },
        contextTokens: () -> Int = { 16_384 },
        runtimeFingerprint: () -> String = { "unverified" },
        status: suspend (AssistTaskState) -> Unit = {},
        policySnapshot: EffectiveAssistPolicy? = null,
        supportsPrimaryRuntime: Boolean = true,
        workflowOwnsAssistance: Boolean = false,
        allowPerTurnTool: Boolean = false,
        admitPerTurnTool: (dev.chungjungsoo.gptmobile.data.agent.AgentTool) -> dev.chungjungsoo.gptmobile.data.agent.AgentTool = { it }
    ): List<ResolvedAgentTool> {
        val expected = AssistTaskState(taskId = "expected:$turnId", profileUid = policy.profileUid, policySnapshot = policySnapshot)
        if (!enabled) {
            status(expected.copy(admission = AssistAdmission.OFF, execution = AssistExecution.SKIPPED, reason = "ASSIST_OFF"))
            return tools
        }
        if (!policy.shareDerivedEvidence) {
            status(expected.copy(admission = AssistAdmission.INELIGIBLE, execution = AssistExecution.SKIPPED, reason = "DERIVED_EVIDENCE_SHARING_NOT_GRANTED"))
            return tools
        }
        if (!supportsPrimaryRuntime) {
            status(expected.copy(admission = AssistAdmission.INELIGIBLE, execution = AssistExecution.SKIPPED, reason = "PRIMARY_RUNTIME_CANNOT_YIELD_TO_HELPER"))
            return tools
        }
        if (workflowOwnsAssistance) {
            status(expected.copy(reason = "RESEARCH_WORKFLOW_OWNS_ASSIST"))
            return tools
        }
        status(expected)
        AssistReadinessResolver.publish(AssistReadiness.CHECKING, "Local Assist ready · waiting for an eligible evidence task", policy.profileUid)
        val target = candidates.firstOrNull { policy.profileUid != null && it.uid == policy.profileUid } ?: run {
            status(expected.copy(sequence = 1, admission = AssistAdmission.INELIGIBLE, execution = AssistExecution.SKIPPED, reason = "NO_ELIGIBLE_SELECTED_PROFILE"))
            notice("Local Assist skipped: no eligible selected profile")
            return tools
        }
        status(expected.copy(sequence = 1, profileUid = target.uid, model = target.model, admission = if (target.enabled) AssistAdmission.CHECKING else AssistAdmission.INELIGIBLE, execution = if (target.enabled) AssistExecution.NOT_STARTED else AssistExecution.SKIPPED, reason = if (target.enabled) "WAITING_FOR_ELIGIBLE_TASK" else "PROFILE_DISABLED"))
        if (!profileAllowed(target)) {
            status(expected.copy(sequence = 2, profileUid = target.uid, model = target.model, admission = AssistAdmission.INELIGIBLE, execution = AssistExecution.SKIPPED, reason = "HELPER_PROCESSING_PERMISSION_REQUIRED"))
            return tools
        }
        val assist = AssistCoordinator(goal, {
            profileAllowed(target) && eligible(target)
        }, { task -> generate(target, task, if (policy.summarizeRetrievedText || policy.proposeQueries) 1024 else 128) }, notice, policy.validated().copy(optionalWaitSeconds = 0, preparation = AssistPreparation.WARM_ONLY), { stillEnabled() && profileAllowed(target) }, turnId, producerInvocationId, contextTokens, runtimeFingerprint, { status(it.copy(profileUid = target.uid, model = target.model, policySnapshot = policySnapshot)) }, optionalOwner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
        val bound = tools.map { tool -> if (tool.shareableReadOnly) tool.copy(tool = assist.bind(tool.tool, tool.selectionId() + target.uid + dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey(target))) else tool }
        if (!allowPerTurnTool || !policy.perTurnQueryPlanning) return bound
        // An intentional planning tool permits assistance before external search produces evidence.
        // The only source is this turn's authorized user goal, never arbitrary model arguments.
        val planning = AssistTurnPlanningTool(goal)
        val planner = AssistCoordinator(
            goal, { profileAllowed(target) && eligible(target) }, { task -> generate(target, task, 1024) }, notice,
            policy.validated().copy(evidenceSelection = false, summarizeRetrievedText = false, proposeQueries = true),
            { stillEnabled() && profileAllowed(target) }, turnId, producerInvocationId, contextTokens, runtimeFingerprint,
            { status(it.copy(profileUid = target.uid, model = target.model, policySnapshot = policySnapshot)) }
        )
        return bound + ResolvedAgentTool(admitPerTurnTool(planner.bind(planning, "current-turn-plan:${target.uid}")), null, "Local Assist", planning.definition.name, planning.definition.name)
    }
}
