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
        workflowOwnsAssistance: Boolean = false
    ): List<ResolvedAgentTool> {
        val expected = AssistTaskState(taskId = "expected:$turnId", profileUid = policy.profileUid, policySnapshot = policySnapshot)
        if (!enabled) {
            status(expected.copy(admission = AssistAdmission.OFF, execution = AssistExecution.SKIPPED, reason = "ASSIST_OFF"))
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
        val target = candidates.firstOrNull { it.uid == policy.profileUid || policy.profileUid == null && profileAllowed(it) } ?: run {
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
            val ready = profileAllowed(target) && eligible(target)
            if (!ready) notice(AssistReadinessResolver.state.value.reason)
            ready
        }, { task -> generate(target, task, if (policy.summarizeRetrievedText || policy.proposeQueries) 1024 else 128) }, notice, policy.validated(), { stillEnabled() && profileAllowed(target) }, turnId, producerInvocationId, contextTokens, runtimeFingerprint, { status(it.copy(profileUid = target.uid, model = target.model, policySnapshot = policySnapshot)) })
        return tools.map { tool -> if (tool.shareableReadOnly) tool.copy(tool = assist.bind(tool.tool, tool.selectionId() + target.uid + dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey(target))) else tool }
    }
}
