package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.agent.tool.reservedReviewer
import dev.chungjungsoo.gptmobile.data.assist.AssistAdmission
import dev.chungjungsoo.gptmobile.data.assist.AssistDelivery
import dev.chungjungsoo.gptmobile.data.assist.AssistExecution
import dev.chungjungsoo.gptmobile.data.assist.AssistReadinessResolver
import dev.chungjungsoo.gptmobile.data.assist.AssistTaskState
import dev.chungjungsoo.gptmobile.data.assist.AssistTurnBudget
import dev.chungjungsoo.gptmobile.data.assist.LocalAssistPolicy
import dev.chungjungsoo.gptmobile.data.assist.LocalAssistQueue
import dev.chungjungsoo.gptmobile.data.assist.allowsProcessing
import dev.chungjungsoo.gptmobile.data.assist.resolveAssistPolicy
import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary
import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptRejected
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.delegation.CanonicalModelIdentity
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.delegationFor
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import dev.chungjungsoo.gptmobile.data.orchestration.assistEnabled
import dev.chungjungsoo.gptmobile.data.orchestration.effectiveAssistPolicy
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** All destinations are pinned at run admission. A setting change can revoke, never grant access. */
internal object DirectResearchModelFactory {
    fun create(
        primary: PlatformV2,
        profiles: List<PlatformV2>,
        features: AppFeatureSettings,
        config: ModelDelegationSettings,
        turnId: String,
        liveProfiles: suspend () -> List<PlatformV2>,
        liveFeatures: suspend () -> AppFeatureSettings,
        stillEnabled: suspend () -> Boolean,
        prepareLocal: suspend (PlatformV2, LocalAssistPolicy) -> Boolean,
        generate: suspend (PlatformV2, ResearchModelStage, String, Int) -> ResearchModelReply,
        onAssistStatus: suspend (AssistTaskState) -> Unit = {}
    ): ResearchModelCoordinator {
        val policy = features.effectiveAssistPolicy()
        fun endpoint(target: PlatformV2): ResearchModelEndpoint {
            val local = target.compatibleType == ClientType.LITERT_LM
            val identity = CanonicalModelIdentity.resolve(target).canonical.orEmpty()
            suspend fun permitted(): Boolean = stillEnabled() &&
                liveProfiles().any { it == target && it.enabled } &&
                (!local || liveFeatures().let { live -> live.assistEnabled() && live.effectiveAssistPolicy() == policy && policy.allowsProcessing(target) }) &&
                (target.uid == primary.uid || local || liveFeatures().delegationFor(primary.uid).let { live -> live.reviewerEnabled && live.reviewerProfileUid == config.reviewerProfileUid && (target.isPrivateDestination() || live.remoteWorkersAllowed()) })
            return ResearchModelEndpoint(
                target.uid,
                target.model,
                identity,
                local,
                available = { permitted() && (!local || AssistTurnBudget.available(turnId, policy.maxAttempts)) },
                timeoutMillis = if (local) (policy.queueWaitSeconds + policy.loadTimeoutSeconds + policy.taskTimeoutSeconds) * 1000L else null,
                generate = { stage, prompt, cap ->
                    suspend fun execute(): ResearchModelReply {
                        check(permitted()) { "Research permission or destination changed" }
                        return generate(target, stage, prompt, cap)
                    }
                    if (local) {
                        LocalAssistQueue.run(policy.queueWaitSeconds * 1000L) {
                            check(permitted()) { "Research permission or destination changed" }
                            check(prepareLocal(target, policy)) { AssistReadinessResolver.state.value.reason }
                            withContext(
                                PhysicalAttemptBoundary {
                                    if (!permitted()) throw PhysicalAttemptRejected("PERMISSION_OR_RUNTIME_CHANGED")
                                    if (!AssistTurnBudget.reserve(turnId, policy.maxAttempts)) throw PhysicalAttemptRejected("PHYSICAL_ATTEMPT_ALLOWANCE")
                                }
                            ) { withTimeoutOrNull(policy.taskTimeoutSeconds * 1000L) { execute() } }
                        } ?: error("Local Assist queue or task deadline; primary research remains available")
                    } else {
                        execute()
                    }
                }
            )
        }
        val helper = profiles.firstOrNull {
            features.assistEnabled() &&
                policy.researchPreparation &&
                primary.compatibleType != ClientType.LITERT_LM &&
                policy.allowsProcessing(it) &&
                (policy.profileUid == null || policy.profileUid == it.uid)
        }
        val reviewer = reservedReviewer(config, profiles, primary)?.takeIf {
            config.reviewerEnabled &&
                it.enabled &&
                !it.excludesMemory() &&
                (it.isPrivateDestination() || config.remoteWorkersAllowed()) &&
                (it.compatibleType != ClientType.LITERT_LM || features.assistEnabled() && policy.allowsProcessing(it))
        }
        return ResearchModelCoordinator(
            workers = listOfNotNull(helper?.let(::endpoint), primary.takeIf { it.compatibleType != ClientType.LITERT_LM }?.let(::endpoint)),
            reviewer = reviewer?.let(::endpoint),
            stillEnabled = stillEnabled,
            onEvent = { event ->
                AppLogRecorder.record("Research", "MODEL_STAGE · stage=${event.stage} · profile=${event.profileUid} · local=${event.local} · status=${event.outcome} · durationMs=${event.durationMs} · invocation=${event.invocationId ?: "none"} · reason=${event.reason}")
                if (event.local) {
                    onAssistStatus(
                        AssistTaskState(
                            taskId = event.id, sequence = if (event.outcome == "RUNNING") 1 else 2,
                            profileUid = event.profileUid, model = event.model,
                            admission = if (event.outcome == "SKIPPED") AssistAdmission.INELIGIBLE else AssistAdmission.ELIGIBLE,
                            execution = when (event.outcome) {
                                "RUNNING" -> AssistExecution.RUNNING
                                "SUCCESS" -> AssistExecution.COMPLETED
                                "TIMED_OUT" -> AssistExecution.TIMED_OUT
                                "CANCELLED" -> AssistExecution.CANCELLED
                                "SKIPPED" -> AssistExecution.SKIPPED
                                else -> AssistExecution.FAILED
                            },
                            delivery = if (event.outcome == "SUCCESS") AssistDelivery.VALIDATED else AssistDelivery.NONE,
                            reason = event.reason.ifBlank { "RESEARCH_${event.stage}" }, taskType = "RESEARCH_${event.stage}",
                            producerInvocationId = event.invocationId, policySnapshot = features.resolveAssistPolicy()
                        )
                    )
                }
            }
        )
    }
}
