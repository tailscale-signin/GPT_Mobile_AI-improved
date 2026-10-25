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
        onAssistStatus: suspend (AssistTaskState) -> Unit = {},
        retainPartial: (suspend (String, String, String) -> String?)? = null,
        bindProviderLimit: suspend (PlatformV2, dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision) -> dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision? = { _, limit -> limit }
    ): ResearchModelCoordinator {
        val policy = features.effectiveAssistPolicy()
        fun endpoint(target: PlatformV2): ResearchModelEndpoint {
            val local = target.compatibleType == ClientType.LITERT_LM
            val identity = CanonicalModelIdentity.resolve(target).canonical.orEmpty()
            // Request temperature/model overlays are distinct from registered authority.
            val registered = profiles.singleOrNull { it.uid == target.uid }
            fun sameDestination(other: PlatformV2): Boolean =
                other.compatibleType == target.compatibleType &&
                    other.apiUrl == target.apiUrl &&
                    other.token == target.token &&
                    other.secretRef == target.secretRef &&
                    other.providerConnectionUid == target.providerConnectionUid
            val revision = registered?.let(dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority::profileRevision)
            suspend fun admission(): ResearchAdmission {
                if (!stillEnabled()) return ResearchAdmission.PERMISSION_REVOKED
                if (registered == null) return ResearchAdmission.PROFILE_REMOVED
                if (!sameDestination(registered)) return ResearchAdmission.DESTINATION_CHANGED
                val matches = liveProfiles().filter { it.uid == target.uid }
                if (matches.size > 1) return ResearchAdmission.PROFILE_IDENTITY_AMBIGUOUS
                val current = matches.singleOrNull() ?: return ResearchAdmission.PROFILE_REMOVED
                if (!current.enabled) return ResearchAdmission.PROFILE_DISABLED
                if (!sameDestination(current)) return ResearchAdmission.DESTINATION_CHANGED
                if (dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority.profileRevision(current) != revision) return ResearchAdmission.REGISTERED_PROFILE_CHANGED
                if (target.compatibleType == ClientType.FREE && dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.fromApiUrl(target.apiUrl) == dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.HUGGING_FACE) {
                    // The vetted Qwen Space does not serialize a generation ceiling. It cannot
                    // satisfy bounded Research stages until a controlled contract is verified.
                    return ResearchAdmission.OUTPUT_LIMIT_UNSUPPORTED
                }
                dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(current, target.model)?.let { blocked ->
                    return when (blocked.category) {
                        dev.chungjungsoo.gptmobile.data.network.FreeFailureCategory.LOCAL_COOLDOWN_ACTIVE,
                        dev.chungjungsoo.gptmobile.data.network.FreeFailureCategory.PROVIDER_RATE_LIMITED -> ResearchAdmission.PROVIDER_COOLDOWN
                        else -> ResearchAdmission.PROVIDER_UNAVAILABLE
                    }
                }
                val live = liveFeatures()
                if (local && (!live.assistEnabled() || !policy.shareDerivedEvidence || live.effectiveAssistPolicy() != policy || !policy.allowsProcessing(target))) return ResearchAdmission.PERMISSION_REVOKED
                val selectedResearchReviewer = features.researchFeature.reviewerProfileUid == target.uid && live.researchFeature.enabled && live.researchFeature.reviewerProfileUid == target.uid
                if (target.uid != primary.uid && features.researchFeature.reviewerProfileUid == target.uid && !selectedResearchReviewer) return ResearchAdmission.PERMISSION_REVOKED
                if (target.uid != primary.uid && !local && !selectedResearchReviewer && !live.delegationFor(primary.uid).let { it.reviewerEnabled && it.reviewerProfileUid == config.reviewerProfileUid && (target.isPrivateDestination() || it.remoteWorkersAllowed()) }) return ResearchAdmission.PERMISSION_REVOKED
                if (local && !AssistTurnBudget.available(turnId, policy.maxAttempts)) return ResearchAdmission.LOCAL_ALLOWANCE_EXHAUSTED
                return ResearchAdmission.ADMITTED
            }
            suspend fun requireAdmission() {
                val result = admission()
                if (result != ResearchAdmission.ADMITTED) {
                    val limit = if (result == ResearchAdmission.PROVIDER_COOLDOWN) dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(target)?.let { dev.chungjungsoo.gptmobile.data.network.providerLimitDecision(target, it) }?.let { bindProviderLimit(target, it) } else null
                    throw ResearchAdmissionRejected(result, limit)
                }
            }
            return ResearchModelEndpoint(
                target.uid,
                target.model,
                identity,
                local,
                available = { admission() == ResearchAdmission.ADMITTED },
                providerHost = runCatching { java.net.URI(target.apiUrl).host.orEmpty() }.getOrDefault(""),
                admission = { admission() },
                timeoutMillis = if (local) (policy.queueWaitSeconds + policy.loadTimeoutSeconds + policy.taskTimeoutSeconds) * 1000L else null,
                requestBudget = { requested -> ResearchProviderBudget.request(target, requested, features.tokenBudget) },
                providerLimit = {
                    dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(target)?.let {
                        dev.chungjungsoo.gptmobile.data.network.providerLimitDecision(target, it)?.let { limit -> bindProviderLimit(target, limit) }
                    }
                },
                generate = { stage, prompt, cap ->
                    suspend fun execute(): ResearchModelReply {
                        requireAdmission()
                        return generate(target, stage, prompt, cap)
                    }
                    if (local) {
                        LocalAssistQueue.run(policy.queueWaitSeconds * 1000L) {
                            requireAdmission()
                            check(prepareLocal(target, policy)) { AssistReadinessResolver.state.value.reason }
                            dev.chungjungsoo.gptmobile.data.assist.withAssistAttemptDeadline(
                                generationMillis = policy.taskTimeoutSeconds * 1000L,
                                preparationMillis = policy.loadTimeoutSeconds * 1000L,
                                authorize = {
                                    requireAdmission()
                                    if (!AssistTurnBudget.reserve(turnId, policy.maxAttempts)) throw PhysicalAttemptRejected("PHYSICAL_ATTEMPT_ALLOWANCE")
                                }
                            ) { execute() }
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
                policy.shareDerivedEvidence &&
                primary.compatibleType != ClientType.LITERT_LM &&
                policy.allowsProcessing(it) &&
                (policy.profileUid != null && policy.profileUid == it.uid)
        }
        // Research has its own explicit reviewer selection, independent of Combined Delegation.
        // A missing selected profile remains unavailable; never substitute another reviewer.
        val selectedReviewer = features.researchFeature.reviewerProfileUid
        val reviewer = if (selectedReviewer != null) {
            dev.chungjungsoo.gptmobile.data.research.feature.ResearchProfileResolver.dedicatedReviewer(features, profiles, primary)?.takeIf { it.compatibleType != ClientType.LITERT_LM || features.assistEnabled() && policy.allowsProcessing(it) }
        } else {
            reservedReviewer(config, profiles, primary)?.takeIf {
                config.reviewerEnabled &&
                    it.enabled &&
                    !it.excludesMemory() &&
                    (it.isPrivateDestination() || config.remoteWorkersAllowed()) &&
                    (it.compatibleType != ClientType.LITERT_LM || features.assistEnabled() && policy.allowsProcessing(it))
            }
        }
        return ResearchModelCoordinator(
            healthScope = turnId,
            retainPartial = retainPartial,
            workers = listOfNotNull(helper?.let(::endpoint), primary.takeIf { it.compatibleType != ClientType.LITERT_LM }?.let(::endpoint)),
            reviewer = reviewer?.let(::endpoint),
            stillEnabled = stillEnabled,
            onEvent = { event ->
                AppLogRecorder.record("Research", "MODEL_STAGE · incidentId=${event.invocationId ?: event.id} · stage=${event.stage} · profile=${event.profileUid} · local=${event.local} · status=${event.outcome} · durationMs=${event.durationMs} · invocation=${event.invocationId ?: "none"} · reason=${event.reason} · failurePhase=${event.failurePhase.ifBlank { "none" }} · failureCategory=${event.failureCategory.ifBlank { "none" }} · exceptionType=${event.exceptionType.ifBlank { "none" }}")
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
                            // Structured stage validation happens after the raw model reply.
                            delivery = if (event.outcome == "SUCCESS") AssistDelivery.PRODUCED else AssistDelivery.NONE,
                            reason = event.reason.ifBlank { "RESEARCH_${event.stage}" }, taskType = "RESEARCH_${event.stage}",
                            producerInvocationId = event.invocationId, policySnapshot = features.resolveAssistPolicy()
                        )
                    )
                }
            }
        )
    }
}
