package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.accounting.InvocationLedger
import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkUnsupportedConfigurationException
import dev.chungjungsoo.gptmobile.data.benchmark.requireBenchmarkOutputContract
import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.WorkerRequestBudget
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Read the current persisted row and hydrated connection, never the legacy profile cache. */
internal suspend fun privacyComparisonProfileIsCurrent(settings: SettingRepository, frozen: PlatformV2): Boolean =
    frozen.enabled && settings.observePlatformV2ByUid(frozen.uid).first() == frozen

/** Only the compiled fictional corpus can enter this opt-in comparison boundary. */
internal class PrivacyComparisonProviderBinding(
    private val store: PrivacyModeStore,
    private val profileIsCurrent: suspend (PlatformV2) -> Boolean,
    private val openProvider: suspend (List<ConversationTurn>, PlatformV2, RequestConstraints) -> AgentProviderSession,
    private val ledger: InvocationLedger? = null,
    private val providerAuthority: RequestAuthorizationGuard? = null
) {
    private suspend fun validateProviderAuthority() {
        try {
            providerAuthority?.validate?.invoke()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw PrivacyComparisonBlockedException(PrivacyComparisonFailure.POLICY_CHANGED)
        }
    }

    suspend fun open(
        profile: PlatformV2,
        caseId: String,
        protection: Boolean,
        runId: String,
        deadlineMs: Long
    ): PrivacyComparisonSession {
        if (!protection) PrivacyModeStore.requireUnprotectedTransport()
        require(deadlineMs in 1..60_000)
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        if (!profile.enabled || profile.model.isBlank()) throw PrivacyComparisonBlockedException(PrivacyComparisonFailure.UNSUPPORTED_PROFILE)
        validateProviderAuthority()
        if (!profileIsCurrent(profile)) throw PrivacyComparisonBlockedException(PrivacyComparisonFailure.PROFILE_CHANGED)
        validateProviderAuthority()
        try {
            requireBenchmarkOutputContract(profile, "privacy_comparison", PrivacyComparisonRunner.OUTPUT_TOKENS)
        } catch (_: BenchmarkUnsupportedConfigurationException) {
            throw PrivacyComparisonBlockedException(PrivacyComparisonFailure.UNSUPPORTED_PROFILE)
        }
        val input = PrivateSyntheticInput.create(caseId, profile)
        val policy = store.effectiveSnapshot(protection)
        val conversation = ConversationPrivacyContext(null, protection)
        val collected = AtomicBoolean()
        val constraints = RequestConstraints(
            maxOutputTokens = PrivacyComparisonRunner.OUTPUT_TOKENS,
            allowTools = false,
            allowReasoning = false,
            allowGatewayLocalTools = false,
            lockSelectedRoute = true,
            freshResponse = true,
            attemptId = UUID.randomUUID().toString(),
            requestRole = "benchmark"
        )
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                if (!protection) PrivacyModeStore.requireUnprotectedTransport()
                if (tools.isNotEmpty() || exchanges.isNotEmpty()) throw PrivacyComparisonBlockedException(PrivacyComparisonFailure.UNEXPECTED_TOOL)
                check(collected.compareAndSet(false, true)) { "A comparison leg cannot be replayed." }
                val inherited = currentCoroutineContext()[RequestAuthorizationGuard]
                suspend fun validate() {
                    currentCoroutineContext().ensureActive()
                    if (store.effectiveSnapshot(protection) != policy) throw PrivacyComparisonBlockedException(PrivacyComparisonFailure.POLICY_CHANGED)
                    validateProviderAuthority()
                    if (!profileIsCurrent(profile)) throw PrivacyComparisonBlockedException(PrivacyComparisonFailure.PROFILE_CHANGED)
                    try {
                        inherited?.validate?.invoke()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        throw PrivacyComparisonBlockedException(PrivacyComparisonFailure.POLICY_CHANGED)
                    }
                    currentCoroutineContext().ensureActive()
                    if (!profileIsCurrent(profile)) throw PrivacyComparisonBlockedException(PrivacyComparisonFailure.PROFILE_CHANGED)
                    validateProviderAuthority()
                    if (store.effectiveSnapshot(protection) != policy) throw PrivacyComparisonBlockedException(PrivacyComparisonFailure.POLICY_CHANGED)
                    currentCoroutineContext().ensureActive()
                }
                val authority = RequestAuthorizationGuard(
                    providerAuthority?.revision.orEmpty() + ":" + inherited?.revision.orEmpty() + ":privacy-comparison-" + runId,
                    providerAuthority?.authority ?: inherited?.authority,
                    ::validate
                )
                val budget = WorkerRequestBudget(deadlineMs, AtomicInteger(), requestLimit = 1)
                val producer = flow {
                    validate()
                    val turn = PrivateTurn.capture(store)
                    val owned = flow {
                        validate()
                        input.observeDispatch(currentCoroutineContext()[RequestDispatchContext])
                        val local = input.profile.compatibleType == ClientType.LITERT_LM
                        val provider = if (protection && !local) {
                            PrivateLocalToolSession(store, input.profile, input.turns, emptyList()) { turns, target ->
                                input.validate(target, turns)
                                validate()
                                if (policy.requireVerifiedRetention || store.verifier.verify(target).route != null) {
                                    input.route("STRICT_PRIVATE_TEXT")
                                    PrivateInferenceSession.synthetic(store.verifier, store, target, turns, constraints, input) { "" }
                                } else {
                                    input.route("MASKED_TEXT")
                                    maskedSyntheticProviderSession(store, target, turns, input) { masked, maskedProfile ->
                                        openProvider(masked, maskedProfile, constraints)
                                    }
                                }
                            }
                        } else {
                            input.route(if (local) "LOCAL" else "ORDINARY_TEXT")
                            openProvider(input.turns, input.providerProfile, constraints)
                        }
                        validate()
                        val streamStarted = System.nanoTime()
                        provider.streamRound(emptyList(), emptyList()).collect { event ->
                            if ((!protection || local) && event is ProviderEvent.TextDelta && event.text.isNotEmpty()) {
                                input.ordinaryAnswer(System.nanoTime() - streamStarted)
                            }
                            emit(event)
                        }
                    }
                    emitAll(owned.flowOn(turn.authority(authority) + if (protection) turn else EmptyCoroutineContext))
                }.buffer(0).flowOn(conversation + input.protection + budget)
                // The context handoff may suspend. Revalidate the received event
                // before it reaches accounting or the comparison's visible result.
                withTimeout(deadlineMs) {
                    var denied = false
                    var generationCompleted = false
                    producer.transformWhile { event ->
                        if (event == ProviderEvent.Completed) generationCompleted = true
                        val allowed = try {
                            withContext(conversation) { validate() }
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        if (!allowed) {
                            denied = true
                            return@transformWhile false
                        }
                        currentCoroutineContext().ensureActive()
                        emit(event)
                        event != ProviderEvent.Completed && event !is ProviderEvent.Failed
                    }.collect { emit(it) }
                    // Cancellation has unwound the producer. Only this locally
                    // observed terminal fact can inform the denial's accounting.
                    if (denied) emit(comparisonPublicationDenied(generationCompleted))
                }
            }
        }
        val accounted = ledger?.wrap(
            session, runId, runId, input.profile.compatibleType.name, input.profile.model, "benchmark",
            ContextBudgetService.estimate(input.case.prompt), PrivacyComparisonRunner.OUTPUT_TOKENS, Int.MAX_VALUE,
            profileUid = input.profile.uid, forceAccounting = true
        ) ?: session
        return PrivacyComparisonSession(
            accounted,
            policy.takeIf { protection },
            input::measurements,
            authorityRevision = providerAuthority?.let { researchHash(it.revision + "|" + it.authority?.permissionRevision.orEmpty()) }
        )
    }
}

private fun comparisonPublicationDenied(generationCompleted: Boolean) = ProviderEvent.Failed(
    "The comparison profile or privacy permissions changed. The response was stopped; start a new comparison with the current settings.",
    errorCode = "DISPATCH_POLICY_CHANGED",
    taskOutcome = "PARTIAL",
    failureDetails = buildJsonObject {
        put("failed_stage", "generation_permission")
        put("generation_completed", generationCompleted)
        put("request_replay_allowed", false)
        put("logical_continuation_allowed", false)
    }
)
