package dev.chungjungsoo.gptmobile.data.agent

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.chat.GenerationCompletionStore
import dev.chungjungsoo.gptmobile.data.context.withCombinedOutcome
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunTerminalError
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.resetActiveRevision
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import dev.chungjungsoo.gptmobile.data.localruntime.DeviceHardwareGovernor
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.delegationFor
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.presentation.service.AgentRunForegroundService
import dev.chungjungsoo.gptmobile.util.ApiStateFlowOutcome
import dev.chungjungsoo.gptmobile.util.LOW_POWER_STREAM_PUBLISH_INTERVAL_MILLIS
import dev.chungjungsoo.gptmobile.util.STANDARD_STREAM_PUBLISH_INTERVAL_MILLIS
import dev.chungjungsoo.gptmobile.util.collectApiStateUpdates
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AgentRunRequest(
    val runId: String,
    val chatId: Int,
    val assistantMessage: MessageV2,
    val platform: PlatformV2,
    val userMessages: List<MessageV2>,
    val assistantMessages: List<List<MessageV2>>,
    val chatToolConfig: ChatMcpToolConfig? = null,
    val stage: AgentStageRequest? = null,
    val researchParticipantUids: List<String> = emptyList(),
    val providerWaitAuthority: dev.chungjungsoo.gptmobile.data.context.CapturedProviderAuthority? = null
)

data class ActiveAgentRun(
    val runId: String,
    val chatId: Int,
    val profileUid: String,
    val assistantMessageId: Int? = null,
    val phase: LocalInferencePhase? = null,
    val nativeProgress: dev.chungjungsoo.gptmobile.data.localruntime.GgufNativeProgress? = null,
    val gatewayStage: String? = null,
    val gatewayMessage: String? = null,
    val gatewayCheckpoint: Int? = null,
    val gatewayRound: Int? = null,
    val gatewayToolCalls: Int? = null,
    val gatewayUsefulToolCalls: Int? = null,
    val gatewayRepositoryToolCalls: Int? = null,
    val gatewayNoProgress: Int? = null,
    val gatewayCurrentTool: String? = null,
    val gatewayRoute: String? = null,
    val gatewayResultQuality: String? = null,
    val gatewayWorkflowProfile: String? = null,
    val gatewayFullToolCount: Int? = null,
    val gatewaySelectedToolCount: Int? = null,
    val gatewayRecoveryAttempt: Int? = null,
    val gatewayWorkState: GatewayWorkState = GatewayWorkState.STARTING,
    val gatewayActivity: List<GatewayActivitySample> = emptyList()
)

data class AgentRunNotice(
    val chatId: Int,
    val runId: String,
    val message: String,
    val persistent: Boolean = false
)

@Singleton
class AgentRunCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val gatewayAPI: GatewayAPI,
    private val settingRepository: SettingRepository,
    private val completionStore: GenerationCompletionStore,
    private val synthesisPlanner: CombinedSynthesisPlanner? = null,
    private val researchSessions: dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore? = null,
    private val providerWaits: ProviderWaitStore? = null,
    private val collectionDecisions: CombinedCollectionDecisionStore? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancellationContexts = ConcurrentHashMap<String, dev.chungjungsoo.gptmobile.data.delegation.DelegationCancellationContext>()
    private val combinedRoots = ConcurrentHashMap<String, String>()
    private val researchLifecycles = ConcurrentHashMap<String, dev.chungjungsoo.gptmobile.data.research.ResearchDispatchLifecycle>()
    private val recoveryLock = Mutex()
    private val gatewayRecoveryAttemptAtMs = ConcurrentHashMap<String, Long>()
    private val gatewayRecoveryFailures = ConcurrentHashMap<String, Int>()
    private val chatLocks = ConcurrentHashMap<Int, Mutex>()
    private val interruptingRunIds = ConcurrentHashMap.newKeySet<String>()
    private val excludedRunIds = ConcurrentHashMap.newKeySet<String>()
    private val _streamMessages = MutableStateFlow<Map<Int, MessageV2>>(emptyMap())
    val streamMessages = _streamMessages.asStateFlow()
    private val _activeRuns = MutableStateFlow<Map<String, ActiveAgentRun>>(emptyMap())
    private val _notices = MutableSharedFlow<AgentRunNotice>(extraBufferCapacity = 8)

    internal val isHighMemoryDevice by lazy {
        try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            memInfo.totalMem >= 10L * 1024 * 1024 * 1024
        } catch (_: Exception) {
            false
        }
    }

    internal val publishIntervalMillis: Long
        get() {
            return try {
                val hardwareState = DeviceHardwareGovernor.inspectHardwareState(context)
                val policy = DeviceHardwareGovernor.computeThrottlingPolicy(hardwareState, isHighMemoryDevice)
                policy.streamPublishIntervalMillis.coerceAtLeast(33L)
            } catch (_: Exception) {
                if (isHighMemoryDevice) STANDARD_STREAM_PUBLISH_INTERVAL_MILLIS else LOW_POWER_STREAM_PUBLISH_INTERVAL_MILLIS
            }
        }

    val activeRuns = _activeRuns.asStateFlow()
    val notices = _notices.asSharedFlow()

    fun start(requests: List<AgentRunRequest>) {
        scope.launch {
            withChatGate(requests.map { it.chatId }) {
                startUnlocked(requests)
            }
        }
    }

    suspend fun resumeCombinedCollection(chatId: Int, userId: Int, rootBudgetId: String, trigger: String = "RESTORE_OR_STATE_CHANGE") = withChatGate(chatId) {
        synthesisPlanner?.prepare(chatId, userId, rootBudgetId, trigger)?.let { startUnlocked(listOf(it)) }
    }

    suspend fun removeCombinedProfile(chatId: Int, profileUid: String) {
        val ids = _activeRuns.value.values.filter { it.chatId == chatId && it.profileUid == profileUid }.mapTo(hashSetOf()) { it.runId } + deferredStarts.values.filter { it.chatId == chatId && it.platform.uid == profileUid }.map { it.runId }
        terminalizeAndCancel(ids + providerWaits?.pendingIds(chatId, profileUid).orEmpty() + remoteWaitingIds(chatId, profileUid), false, dev.chungjungsoo.gptmobile.data.delegation.CancellationOrigin.SUPERSEDED_GENERATION)
    }

    suspend fun tryAnotherCombinedWriter(chatId: Int, runId: String): Boolean = withChatGate(chatId) {
        val next = synthesisPlanner?.prepareFallback(chatId, runId, userRequested = true) ?: return@withChatGate false
        startUnlocked(listOf(next))
        true
    }

    private val deferredStarts = ConcurrentHashMap<String, AgentRunRequest>()

    private fun deferUntilForeground(requests: List<AgentRunRequest>) {
        val waiting = requests.filter { deferredStarts.putIfAbsent(it.runId, it) == null }
        if (waiting.isEmpty()) return
        waiting.forEach { _notices.tryEmit(AgentRunNotice(it.chatId, it.runId, "App execution paused. Return to the app to continue; no provider request has been sent.", true)) }
        scope.launch {
            try {
                dev.chungjungsoo.gptmobile.presentation.AppForegroundTracker.visibility.first { it }
                withChatGate(waiting.map { it.chatId }) {
                    val pending = waiting.filter { request ->
                        val run = chatRepository.observeAgentRuns(request.chatId).first().firstOrNull { it.runId == request.runId }
                        run?.status == AgentRunStatus.QUEUED && (request.chatToolConfig?.combined?.let { !chatRepository.isCombinedTurnCancelled(it.rootBudgetId) } ?: true)
                    }
                    waiting.forEach { deferredStarts.remove(it.runId) }
                    startUnlocked(pending)
                }
            } finally {
                waiting.forEach { deferredStarts.remove(it.runId) }
            }
        }
    }

    suspend fun reconcileProviderWaits() {
        providerWaits?.reconcile()
    }

    internal suspend fun resumeProviderWaits(actions: List<ProviderWaitResume>) = withChatGate(actions.map { it.request.chatId }) {
        val requests = actions.mapNotNull { action ->
            val request = action.request
            if (stopExcludedContributor(request)) return@mapNotNull null
            val root = request.chatToolConfig?.combined?.rootBudgetId
            if (root != null && chatRepository.isCombinedTurnCancelled(root)) {
                chatRepository.finishActiveAgentRun(request.runId, AgentRunStatus.CANCELED, currentEpochSeconds(), "TURN_CANCELLED")
                return@mapNotNull null
            }
            try {
                val decision = requireNotNull(action.saved.decision) { "PROVIDER_WAIT_DECISION_UNAVAILABLE" }
                val authority = requireNotNull(decision.authority) { "PROVIDER_WAIT_AUTHORITY_UNAVAILABLE" }
                authority.validate(settingRepository)
                when (action.saved.mode) {
                    ProviderWaitMode.INITIAL -> request
                    ProviderWaitMode.TEXT_CONTINUATION -> providerWaits?.resumeTextContinuation(action)
                    ProviderWaitMode.RESEARCH_STAGE -> providerWaits?.resumeResearch(action)
                    ProviderWaitMode.COMBINED_APPEND -> synthesisPlanner?.prepareAppendResume(request.chatId, request.runId, decision)
                }?.copy(providerWaitAuthority = authority).also { resumed ->
                    if (resumed == null) chatRepository.finishActiveAgentRun(request.runId, AgentRunStatus.INTERRUPTED, currentEpochSeconds(), "PROVIDER_WAIT_REVIEW_REQUIRED")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (changed: Exception) {
                chatRepository.finishActiveAgentRun(request.runId, AgentRunStatus.INTERRUPTED, currentEpochSeconds(), "PROVIDER_WAIT_REVIEW_REQUIRED")
                _notices.tryEmit(AgentRunNotice(request.chatId, request.runId, "Saved work needs review before it can resume. Its evidence and answer are retained.", true))
                null
            }
        }
        startUnlocked(requests)
    }

    suspend fun resumeProviderRequests(requests: List<AgentRunRequest>) = withChatGate(requests.map { it.chatId }) {
        val eligible = requests.filter { request ->
            val root = request.chatToolConfig?.combined?.rootBudgetId
            if (root != null && chatRepository.isCombinedTurnCancelled(root)) {
                chatRepository.finishActiveAgentRun(request.runId, AgentRunStatus.CANCELED, currentEpochSeconds(), "TURN_CANCELLED")
                false
            } else {
                true
            }
        }
        startUnlocked(eligible)
    }

    private suspend fun startUnlocked(incoming: List<AgentRunRequest>) {
        // Persisted quotas and the device identity must be restored before preflight
        // or a worker may calculate a new scope and bypass a previous cooldown.
        dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate.await()
        // All callers hold the chat gate. A due wait may have been claimed before
        // that gate; exclusion must win before preflight can defer or dispatch it.
        val included = incoming.filterNot { stopExcludedContributor(it) }
        val participantsBeforeWait = included.groupBy { rootWorkId(it.userMessages, it.runId) }.mapValues { (_, runs) -> runs.map { it.platform.uid }.distinct() }
        val requests = included.map { it.copy(researchParticipantUids = it.researchParticipantUids.ifEmpty { participantsBeforeWait[rootWorkId(it.userMessages, it.runId)].orEmpty() }) }.filter { request ->
            val cooldown = dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(request.platform)
            val deadline = cooldown?.retryAt
            val deferred = deadline != null && deadline > System.currentTimeMillis() && providerWaits?.defer(request) == true
            !deferred
        }
        if (requests.isNotEmpty() && !AgentRunForegroundService.canStartNow() && runCatching { settingRepository.getFeatureSettings().backgroundGeneration }.getOrDefault(true)) {
            deferUntilForeground(requests)
            return
        }
        val participants = requests.groupBy { rootWorkId(it.userMessages, it.runId) }.mapValues { (_, runs) -> runs.map { it.platform.uid }.distinct() }
        val pending = requests.distinctBy(AgentRunRequest::runId).mapNotNull { original ->
            val request = original.copy(researchParticipantUids = original.researchParticipantUids.ifEmpty { participants[rootWorkId(original.userMessages, original.runId)].orEmpty() })
            val rootMessage = resolveTaskRecovery(request.userMessages)?.sourceMessageId ?: request.userMessages.lastOrNull()?.id
            val cancellation = dev.chungjungsoo.gptmobile.data.delegation.DelegationCancellationContext("${request.chatId}:$rootMessage", request.runId)
            val savedAuthority = request.providerWaitAuthority?.requestGuard(settingRepository) ?: kotlin.coroutines.EmptyCoroutineContext
            val job = scope.launch(context = cancellation + savedAuthority, start = CoroutineStart.LAZY) {
                execute(request)
            }
            if (jobs.putIfAbsent(request.runId, job) == null) {
                cancellationContexts[request.runId] = cancellation
                request.chatToolConfig?.combined?.let { combinedRoots[request.runId] = it.rootBudgetId }
                job.invokeOnCompletionCleanup {
                    runCatching {
                        jobs.remove(request.runId)
                        cancellationContexts.remove(request.runId)
                        combinedRoots.remove(request.runId)
                        researchLifecycles.remove(request.runId)
                        _streamMessages.update { current ->
                            if (current[request.assistantMessage.id]?.currentRunId == request.runId) current - request.assistantMessage.id else current
                        }
                        interruptingRunIds.remove(request.runId)
                        excludedRunIds.remove(request.runId)
                        _activeRuns.update { it - request.runId }
                    }
                }
                request to job
            } else {
                job.cancel()
                null
            }
        }
        if (pending.isEmpty()) return

        _activeRuns.update { active ->
            active + pending.associate { (request, _) ->
                request.runId to ActiveAgentRun(
                    runId = request.runId,
                    chatId = request.chatId,
                    profileUid = request.platform.uid,
                    assistantMessageId = request.assistantMessage.id.takeIf { it > 0 }
                )
            }
        }
        val backgroundGenerationEnabled = runCatching {
            settingRepository.getFeatureSettings().backgroundGeneration
        }.getOrDefault(true)

        if (backgroundGenerationEnabled) {
            try {
                // Service start and stop decisions share the main thread to close the idle-stop race.
                withContext(Dispatchers.Main.immediate) { AgentRunForegroundService.start(context) }
            } catch (error: RuntimeException) {
                pending.forEach { (request, job) ->
                    jobs.remove(request.runId)
                    job.cancel()
                }
                _activeRuns.update { active -> active - pending.map { it.first.runId }.toSet() }
                if (error is android.app.ForegroundServiceStartNotAllowedException && dev.chungjungsoo.gptmobile.presentation.AppForegroundTracker.isBackgrounded) {
                    deferUntilForeground(pending.map { it.first })
                } else {
                    failQueuedStarts(pending.map { it.first }, error)
                }
                return
            }
        }
        pending.forEach { (_, job) -> job.start() }
    }

    /** Called under the chat gate, including after a wait was claimed outside it. */
    private suspend fun stopExcludedContributor(request: AgentRunRequest): Boolean {
        val combined = request.chatToolConfig?.combined ?: return false
        if (combined.role != CombinedRunRole.CONTRIBUTOR || request.platform.uid !in collectionDecisions?.excludedProfiles(combined.rootBudgetId).orEmpty()) return false
        deferredStarts.remove(request.runId)
        chatRepository.finishActiveAgentRun(request.runId, AgentRunStatus.CANCELED, currentEpochSeconds(), "EXCLUDED_FROM_COMBINED_TURN")
        return true
    }

    /**
     * The caller already holds the chat gate. A stale alert may identify the
     * predecessor of a newly claimed successor, so follow the same answer slot.
     * Cancel only local work; an accepted remote request cannot be undone here.
     */
    suspend fun cancelExcludedProviderWait(run: AgentRun, rootBudgetId: String) {
        if (run.profileUid !in collectionDecisions?.excludedProfiles(rootBudgetId).orEmpty()) return
        val current = chatRepository.observeAgentRuns(run.chatId).first().filter { candidate ->
            candidate.assistantMessageId == run.assistantMessageId &&
                candidate.profileUid == run.profileUid &&
                candidate.status in setOf(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING, AgentRunStatus.WAITING_PROVIDER_LIMIT) &&
                CombinedAcceptedTurn.context(candidate.synthesisSnapshot)?.let { it.rootBudgetId == rootBudgetId && it.role == CombinedRunRole.CONTRIBUTOR } == true
        }
        withContext(NonCancellable) {
            current.forEach { candidate ->
                deferredStarts.remove(candidate.runId)
                cancellationContexts[candidate.runId]?.mark(dev.chungjungsoo.gptmobile.data.delegation.CancellationOrigin.SUPERSEDED_GENERATION)
                // Cancellation cleanup publishes retained partial text and does not
                // acquire the chat gate. A queued owner instead fails markRunning.
                excludedRunIds += candidate.runId
                try {
                    cancelAndJoinAgentRun(jobs[candidate.runId]) {
                        chatRepository.finishActiveAgentRun(candidate.runId, AgentRunStatus.CANCELED, currentEpochSeconds(), "EXCLUDED_FROM_COMBINED_TURN")
                    }
                } finally {
                    excludedRunIds.remove(candidate.runId)
                }
            }
        }
    }

    fun cancelChat(chatId: Int) {
        val runIds = _activeRuns.value.values
            .filter { it.chatId == chatId }
            .mapTo(mutableSetOf(), ActiveAgentRun::runId) + deferredStarts.values.filter { it.chatId == chatId }.map { it.runId }
        scope.launch { terminalizeAndCancel(runIds + providerWaits?.pendingIds(chatId).orEmpty() + remoteWaitingIds(chatId), isInterrupted = false) }
    }

    fun cancelRuns(runIds: Set<String>) {
        requestCancellation(runIds, isInterrupted = false)
    }

    fun cancelAll() {
        scope.launch { terminalizeAndCancel(jobs.keys.toSet() + deferredStarts.keys + providerWaits?.pendingIds().orEmpty() + remoteWaitingIds(), isInterrupted = false) }
    }

    fun interruptAll() {
        requestCancellation(jobs.keys.toSet() + deferredStarts.keys, isInterrupted = true)
    }

    suspend fun cancelChatAndJoin(chatId: Int, origin: dev.chungjungsoo.gptmobile.data.delegation.CancellationOrigin = dev.chungjungsoo.gptmobile.data.delegation.CancellationOrigin.USER_WHOLE_RUN) {
        val runIds = _activeRuns.value.values
            .filter { it.chatId == chatId }
            .mapTo(mutableSetOf(), ActiveAgentRun::runId) + deferredStarts.values.filter { it.chatId == chatId }.map { it.runId }
        withContext(NonCancellable) {
            terminalizeAndCancel(runIds + providerWaits?.pendingIds(chatId).orEmpty() + remoteWaitingIds(chatId), isInterrupted = false, origin = origin)
        }
    }

    fun hasActiveRuns(chatId: Int): Boolean = _activeRuns.value.values.any { it.chatId == chatId } || deferredStarts.values.any { it.chatId == chatId }

    suspend fun <T> withChatGate(chatId: Int, block: suspend () -> T): T = withChatGate(listOf(chatId), block)

    suspend fun <T> withChatGate(chatIds: List<Int>, block: suspend () -> T): T {
        val locks = locksFor(chatIds)
        locks.forEach { it.lock() }
        return try {
            block()
        } finally {
            locks.asReversed().forEach { it.unlock() }
        }
    }

    private fun locksFor(chatIds: List<Int>): List<Mutex> = chatIds.distinct().sorted().map { chatLocks.computeIfAbsent(it) { Mutex() } }

    private fun requestCancellation(runIds: Set<String>, isInterrupted: Boolean) {
        if (runIds.isEmpty()) return
        scope.launch {
            terminalizeAndCancel(runIds, isInterrupted)
        }
    }

    private suspend fun remoteWaitingRuns(): List<AgentRun> = try {
        chatRepository.waitingRemoteAgentRuns()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // A broken durable-wait journal must not prevent Stop from cancelling live jobs.
        AppLogRecorder.record("Agent", "REMOTE_WAIT_JOURNAL_READ_FAILED · reason=${failure.javaClass.simpleName}", "W")
        emptyList()
    }

    private suspend fun remoteWaitingIds(chatId: Int? = null, profileUid: String? = null): Set<String> = remoteWaitingRuns()
        .filter { (chatId == null || it.chatId == chatId) && (profileUid == null || it.profileUid == profileUid) }
        .mapTo(linkedSetOf()) { it.runId }

    private suspend fun terminalizeAndCancel(runIds: Set<String>, isInterrupted: Boolean, origin: dev.chungjungsoo.gptmobile.data.delegation.CancellationOrigin = dev.chungjungsoo.gptmobile.data.delegation.CancellationOrigin.USER_WHOLE_RUN) {
        if (runIds.isEmpty()) return
        val waitingRemote = remoteWaitingRuns().filter { it.runId in runIds }.associateBy { it.runId }
        val researchRoots = runIds.mapNotNull { researchLifecycles[it] }.distinctBy { it.rootWorkId }
        val cancelCombined = !isInterrupted &&
            origin in setOf(
                dev.chungjungsoo.gptmobile.data.delegation.CancellationOrigin.USER_WHOLE_RUN,
                dev.chungjungsoo.gptmobile.data.delegation.CancellationOrigin.CHAT_DELETED,
                dev.chungjungsoo.gptmobile.data.delegation.CancellationOrigin.PERMISSION_REVOKED
            )
        cancelCombinedRootsAndRuns(
            roots = if (cancelCombined) runIds.mapNotNull { combinedRoots[it] ?: deferredStarts[it]?.chatToolConfig?.combined?.rootBudgetId ?: CombinedAcceptedTurn.context(waitingRemote[it]?.synthesisSnapshot)?.rootBudgetId } + providerWaits?.combinedRoots(runIds).orEmpty() else emptyList(),
            cancelRoot = chatRepository::cancelCombinedTurn,
            onRootFailure = { root, failure -> AppLogRecorder.record("Combined", "CANCELLATION_JOURNAL_FAILED · root=$root · reason=${failure.javaClass.simpleName}", "W") }
        ) {
            if (isInterrupted) interruptingRunIds += runIds
            val completedAt = currentEpochSeconds()
            runIds.forEach { runId ->
                deferredStarts.remove(runId)
                cancellationContexts[runId]?.mark(if (isInterrupted) dev.chungjungsoo.gptmobile.data.delegation.CancellationOrigin.PROCESS_INTERRUPTION else origin)
                runCatching {
                    cancelAndJoinAgentRun(jobs[runId]) {
                        chatRepository.finishActiveAgentRun(
                            runId = runId,
                            status = if (isInterrupted) AgentRunStatus.INTERRUPTED else AgentRunStatus.CANCELED,
                            completedAt = completedAt,
                            terminalError = when {
                                isInterrupted -> AgentRunTerminalError.SERVICE_STOPPED
                                runId in waitingRemote -> "LOCAL_WAIT_CANCELLED_REMOTE_TERMINATION_UNCONFIRMED"
                                else -> null
                            }
                        )
                    }
                }
            }
            if (!isInterrupted) {
                waitingRemote.values.distinctBy { it.chatId }.forEach { run ->
                    _notices.tryEmit(AgentRunNotice(run.chatId, run.runId, "Stopped waiting locally. The remote provider has not confirmed cancellation; the saved job receipt is retained.", true))
                }
            }
            if (isInterrupted || cancelCombined) {
                researchRoots.forEach { lifecycle ->
                    runCatching {
                        lifecycle.terminal(if (isInterrupted) dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.INTERRUPTED else dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.CANCELLED, origin.name)
                    }.onFailure { AppLogRecorder.record("Research", "TERMINAL_JOURNAL_PENDING · root=${lifecycle.rootWorkId} · reason=${it.javaClass.simpleName}", "W") }
                }
            }
        }
    }

    private fun failQueuedStarts(requests: List<AgentRunRequest>, error: RuntimeException) {
        scope.launch {
            val completedAt = currentEpochSeconds()
            val message = "APP_EXECUTION_SERVICE_START: " + (
                error.message?.takeIf { it.isNotBlank() }
                    ?: "Android did not allow the foreground agent service to start."
                )
            requests.forEach { request ->
                runCatching {
                    chatRepository.commitAgentRun(
                        terminalAgentMessage(request.assistantMessage, message, completedAt),
                        request.runId,
                        AgentRunStatus.FAILED,
                        completedAt,
                        message
                    )
                }
            }
        }
    }

    /**
     * Attempts recovery of interrupted or uncompleted runs that have an associated gateway job.
     */
    suspend fun recoverInterruptedGatewayRuns() = recoveryLock.withLock {
        val profiles = settingRepository.fetchPlatformV2s().associateBy { it.uid }
        val nowSeconds = currentEpochSeconds()
        val nowMillis = System.currentTimeMillis()
        for (run in chatRepository.getRecoverableGatewayRuns()) {
            // A live stream remains the owner of its answer. Recovery only adopts interrupted work.
            if (run.status != AgentRunStatus.INTERRUPTED || jobs.containsKey(run.runId)) continue
            val jobId = run.gatewayJobId ?: continue
            val baseUrl = run.gatewayBaseUrl ?: continue
            val profile = profiles[run.profileUid] ?: continue
            if (!sameGatewayEndpoint(profile.apiUrl, baseUrl)) continue

            AppLogRecorder.record("CombinedRetry", "RECOVERY_INSPECTION · initiator=AUTOMATIC_RECOVERY · runId=${run.runId} · profileUid=${run.profileUid} · action=INSPECT_SAVED_JOB")
            val recoveryAgeSeconds = gatewayRecoveryAgeSeconds(run, nowSeconds)
            if (gatewayRecoveryExpired(run, nowSeconds)) {
                val token = dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
                    .keysForNewRequest(profile.providerConnectionUid ?: profile.uid, profile.token).firstOrNull()
                runCatching { gatewayAPI.cancelJob(jobId, ProviderRequestConfig(baseUrl, token)) }
                if (
                    chatRepository.finishInterruptedAgentRun(
                        run.runId,
                        AgentRunStatus.FAILED,
                        nowSeconds,
                        GATEWAY_RECOVERY_EXPIRED
                    )
                ) {
                    gatewayRecoveryAttemptAtMs.remove(run.runId)
                    gatewayRecoveryFailures.remove(run.runId)
                    AppLogRecorder.record(
                        "Gateway",
                        "Expired stale recovery job · run=${run.runId} · job=$jobId · ageSeconds=$recoveryAgeSeconds · maxAgeSeconds=$GATEWAY_RECOVERY_MAX_AGE_SECONDS",
                        "W"
                    )
                    _notices.tryEmit(
                        AgentRunNotice(
                            run.chatId,
                            run.runId,
                            "The previous Gateway job expired and was abandoned instead of being polled again. Retry the response to submit fresh work.",
                            persistent = true
                        )
                    )
                }
                continue
            }

            val lastAttempt = gatewayRecoveryAttemptAtMs[run.runId]
            if (lastAttempt != null && nowMillis - lastAttempt < GATEWAY_RECOVERY_MIN_INTERVAL_MS) continue
            gatewayRecoveryAttemptAtMs[run.runId] = nowMillis

            try {
                val token = dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
                    .keysForNewRequest(profile.providerConnectionUid ?: profile.uid, profile.token).firstOrNull()
                val config = ProviderRequestConfig(baseUrl, token)
                val capabilities = gatewayAPI.getCapabilities(config)
                if (capabilities == null || !capabilities.supportsResultRecovery) {
                    val failures = gatewayRecoveryFailures.merge(run.runId, 1, Int::plus) ?: 1
                    AppLogRecorder.record(
                        "Gateway",
                        "Recovery health check unavailable · run=${run.runId} · job=$jobId · failures=$failures · ageSeconds=$recoveryAgeSeconds",
                        "W"
                    )
                    continue
                }

                val result = gatewayAPI.getJobResult(jobId, config)
                if (result == null) {
                    gatewayRecoveryFailures.merge(run.runId, 1, Int::plus)
                    continue
                }
                if (result.jobId != jobId) continue
                if (result.status.equals("FAILED", ignoreCase = true) || result.status.equals("CANCELED", ignoreCase = true)) {
                    chatRepository.finishInterruptedAgentRun(
                        run.runId,
                        AgentRunStatus.FAILED,
                        nowSeconds,
                        result.error?.take(500) ?: "GATEWAY_JOB_${result.status.uppercase()}"
                    )
                    gatewayRecoveryAttemptAtMs.remove(run.runId)
                    gatewayRecoveryFailures.remove(run.runId)
                    continue
                }
                if (!result.status.equals("COMPLETED", ignoreCase = true)) continue
                val content = result.content ?: continue
                val completedAt = result.completedAt ?: nowSeconds
                if (chatRepository.restoreGatewayAnswer(run.runId, jobId, content, completedAt)) {
                    gatewayRecoveryAttemptAtMs.remove(run.runId)
                    gatewayRecoveryFailures.remove(run.runId)
                    completionStore.record(run.runId, run.chatId, run.assistantMessageId, completedAt)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                val failures = gatewayRecoveryFailures.merge(run.runId, 1, Int::plus) ?: 1
                AppLogRecorder.record(
                    "Gateway",
                    "Recovery failed · run=${run.runId} · job=$jobId · failures=$failures · ${error.javaClass.simpleName}: ${error.message.orEmpty().take(180)}",
                    "W"
                )
                _notices.tryEmit(AgentRunNotice(run.chatId, run.runId, "Gateway recovery is unavailable. A stale job will be abandoned automatically instead of being resurrected indefinitely."))
            }
        }
    }
    private fun updateGatewayProgress(runId: String, progress: GatewayProgress) {
        _activeRuns.update { runs ->
            val current = runs[runId] ?: return@update runs
            runs + (runId to current.withGatewayProgress(progress))
        }
    }

    private companion object {
        const val GATEWAY_RECOVERY_MAX_AGE_SECONDS = DEFAULT_GATEWAY_RECOVERY_MAX_AGE_SECONDS
        const val GATEWAY_RECOVERY_MIN_INTERVAL_MS = 60_000L
        const val GATEWAY_RECOVERY_EXPIRED = "GATEWAY_RECOVERY_EXPIRED"
    }
    private suspend fun prepareResearchLifecycle(request: AgentRunRequest, ineligibleReason: String? = null): dev.chungjungsoo.gptmobile.data.research.ResearchDispatchLifecycle? {
        val store = researchSessions ?: return null
        val selection = request.chatToolConfig?.research
        val combined = request.chatToolConfig?.combined
        if (selection?.researchToggle != true && combined?.requested?.acceptedResearch == null) return null
        val original = request.userMessages.lastOrNull() ?: return null
        val recovery = resolveTaskRecovery(request.userMessages)
        val root = request.stage?.acceptedTask?.rootWorkId ?: rootWorkId(request.userMessages, request.runId)
        val persistent = chatRepository.researchPersistenceAllowed(request.chatId)
        val features = settingRepository.getFeatureSettings()
        val profiles = settingRepository.fetchPlatformV2s()
        val policy = kotlinx.serialization.json.Json.encodeToString(ChatMcpToolConfig.serializer(), request.chatToolConfig ?: ChatMcpToolConfig())
        val featurePolicy = kotlinx.serialization.json.Json.encodeToString(dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings.serializer(), features)
        val existing = store.execution(root, persistent)
        val participants = existing?.accepted?.participantUids ?: combined?.requested?.participantUids ?: request.researchParticipantUids.ifEmpty { listOf(request.platform.uid) }
        val authority = dev.chungjungsoo.gptmobile.data.research.researchHash(profiles.joinToString { "${it.uid}:${it.compatibleType}:${it.apiUrl}:${it.model}:${it.secretRef}:${it.disableAllTools}:${it.disableRemoteTools}" })
        val research = selection?.applyTo(features.delegationFor(request.platform.uid))?.deepResearch ?: features.delegationFor(request.platform.uid).deepResearch
        store.acceptTask(
            request.chatId, root, request.stage?.acceptedTask?.originatingUserMessageId ?: recovery?.sourceMessageId ?: original.id,
            recovery?.originalRequest ?: original.content, participants, combined?.requested?.preferredOwnerUid ?: request.platform.uid,
            dev.chungjungsoo.gptmobile.data.research.researchHash(featurePolicy + policy), authority, persistent,
            acceptedPolicyJson = kotlinx.serialization.json.JsonObject(mapOf("features" to kotlinx.serialization.json.Json.parseToJsonElement(featurePolicy), "chat" to kotlinx.serialization.json.Json.parseToJsonElement(policy))).toString(),
            sourceScope = combined?.requested?.acceptedResearch?.contentScope ?: selection?.scope?.name ?: research.contentScope.name,
            targetSources = combined?.requested?.acceptedResearch?.sourceGoal?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: research.customSourceTarget ?: dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings.targetForEffort(selection?.depth ?: research.effort),
            requestedModels = combined?.requested?.requestedModels ?: profiles.filter { it.uid in participants }.associate { it.uid to it.model }
        )
        val accepted = store.execution(root, persistent) ?: error("RESEARCH_ACCEPTANCE_UNAVAILABLE")
        val authorityGuard = if (ineligibleReason == null) {
            dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority.capture(settingRepository, request.platform, request.chatId)
        } else {
            dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard("incompatible-role") {
                throw dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptRejected(ineligibleReason)
            }
        }
        val savedFeatures = runCatching {
            val envelope = kotlinx.serialization.json.Json.parseToJsonElement(accepted.accepted.acceptedPolicyJson) as? kotlinx.serialization.json.JsonObject
            envelope?.get("features")?.let { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromJsonElement(dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings.serializer(), it) }
        }.getOrNull()
        val frozenPolicyRevision = savedFeatures?.takeIf { accepted.attempt.generation == 0L }?.let { dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority.policyRevision(it, request.platform.uid, request.chatId) }
        // Claim the row for this attempt before queueing, including recovered answers that
        // need no new physical dispatch. Startup cannot borrow a previous attempt's success.
        store.updateExecution(
            root,
            accepted.attempt,
            dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.ACCEPTED,
            persistent,
            reason = "RUN_ACCEPTED",
            runId = request.runId,
            profileUid = request.platform.uid
        )
        return dev.chungjungsoo.gptmobile.data.research.ResearchDispatchLifecycle(store, root, accepted.attempt, persistent, request.runId, request.platform.uid) {
            authorityGuard.validate()
            check(frozenPolicyRevision == null || frozenPolicyRevision == dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority.policyRevision(settingRepository.getFeatureSettings(), request.platform.uid, request.chatId)) { "RESEARCH_CONFIGURATION_CHANGED: accepted Research settings changed; review and resume the task before dispatch." }
        }
    }

    private suspend fun settleResearchOutcome(
        request: AgentRunRequest,
        lifecycle: dev.chungjungsoo.gptmobile.data.research.ResearchDispatchLifecycle?,
        message: MessageV2,
        status: String,
        reason: String?,
        contributorsExhausted: Boolean = false
    ) {
        val store = researchSessions ?: return
        if (lifecycle == null || !contributorsExhausted && request.chatToolConfig?.combined?.role == CombinedRunRole.CONTRIBUTOR) return
        try {
            val execution = store.execution(lifecycle.rootWorkId, lifecycle.persistent) ?: return
            val accepted = execution.accepted
            val ownedIds = execution.attempt.dispatchedRunIds + execution.receipts.filter { it.attemptId == lifecycle.attempt.attemptId && it.generation == lifecycle.attempt.generation }.mapNotNull { it.runId }
            val turnRuns = chatRepository.observeAgentRuns(request.chatId).first().filter { it.userMessageId == accepted.originatingUserMessageId || it.runId in ownedIds }
            val active = turnRuns.any { it.runId != request.runId && it.status in setOf(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING, AgentRunStatus.WAITING_PROVIDER_LIMIT, AgentRunStatus.WAITING_REMOTE_JOB, "STOPPING") }
            val currentRuns = turnRuns.filter { it.runId in ownedIds }
            val allBlocked = currentRuns.isNotEmpty() &&
                currentRuns.all { run ->
                    run.status == AgentRunStatus.FAILED && run.terminalError?.let { ProfileFailureRecord.classify(it).category in setOf("INCOMPATIBLE_CONFIGURATION", "TOOL_CHOICE_MISMATCH") } == true
                }
            if (active) return
            val retained = store.exportTurn(request.chatId, setOf(lifecycle.rootWorkId))
            val readable = retained.flatMap { it.snapshot.sources }.filter { it.readable }.map { it.equivalenceGroup.ifBlank { it.canonicalUrl.ifBlank { it.id } } }.distinct().size
            val receipt = message.timeline.mapNotNull { it.researchDeliveryReceipt }.lastOrNull { it.matchesRevision(message.content) }
            val combined = message.timeline.mapNotNull { it.combinedQuality }.lastOrNull { it.answerDigest == dev.chungjungsoo.gptmobile.data.research.researchHash(message.content) }
            val checkpoint = receipt?.revisionId ?: combined?.answerDigest
            val state = when {
                status == AgentRunStatus.CANCELED -> dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.CANCELLED
                status == AgentRunStatus.INTERRUPTED -> dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.INTERRUPTED
                !contributorsExhausted && readable > 0 && status == AgentRunStatus.COMPLETED && (receipt?.accepted == true || combined?.let { it.complete && !it.correctionRequired && it.deliveredScopeStatus in setOf("COMPLETED", "COMPLETED_WITH_LIMITS") } == true) -> dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.COMPLETED
                !contributorsExhausted && readable > 0 && checkpoint != null && message.content.isNotBlank() && (receipt?.partial == true || combined != null) -> dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.PARTIAL
                allBlocked -> dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.BLOCKED_CONFIGURATION
                readable > 0 -> dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.FAILED_DELIVERY
                else -> dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.FAILED_NO_EVIDENCE
            }
            lifecycle.terminal(state, if (allBlocked) "ALL_SELECTED_PROFILES_INCOMPATIBLE" else reason, checkpoint, readable)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLogRecorder.record("Research", "TERMINAL_JOURNAL_PENDING · root=${lifecycle.rootWorkId} · reason=${failure.javaClass.simpleName}", "W")
        }
    }

    private suspend fun execute(request: AgentRunRequest) = coroutineScope {
        val eligibilityFailure = if (request.chatToolConfig?.combined?.role == CombinedRunRole.CONTRIBUTOR) {
            CombinedProviderEligibility.unavailableReason(request.platform, request.userMessages.lastOrNull()?.content.orEmpty())
        } else {
            null
        }
        val startedAt = currentEpochSeconds()
        var assistantMessage = request.assistantMessage
        val recoveryMarkers = request.assistantMessage.timeline.filter { it.recovery?.generationId == request.runId || it.combinedTurn != null || it.writerIdentity != null }
        var committedPrefix = request.assistantMessage.content.takeIf { request.runId.startsWith("combined-synthesis:") }.orEmpty()
        var lastCheckpointAt = 0L
        var firstAnswerCheckpoint = false
        var committedForHandoff = false
        var researchLifecycle: dev.chungjungsoo.gptmobile.data.research.ResearchDispatchLifecycle? = null
        suspend fun traceReducedOutput(message: MessageV2) {
            dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.let { journal ->
                withContext(Dispatchers.Default) { journal.traceOutput(request.runId, dev.chungjungsoo.gptmobile.data.diagnostics.OutputTraceBoundary.REDUCER, message.content, fence = message.ownershipFence) }
            }
        }
        fun newCheckpoints() = ConflatedCheckpointWriter<MessageV2>(this) { snapshot ->
            traceReducedOutput(snapshot)
            chatRepository.updateAgentMessage(snapshot)
        }
        suspend fun finishUnavailablePolicy(error: Throwable, status: String = AgentRunStatus.FAILED): Boolean {
            val policyError = generateSequence(error) { it.cause }.filterIsInstance<dev.chungjungsoo.gptmobile.data.database.RunPolicySnapshotException>().firstOrNull() ?: return false
            // The usual message commit hydrates the missing policy and would fail again. The
            // raw terminal update checks current run ownership without replacing any saved text.
            val finished = chatRepository.finishActiveAgentRun(request.runId, status, currentEpochSeconds(), policyError.message)
            if (finished) {
                committedForHandoff = false
                settleResearchOutcome(request, researchLifecycle, assistantMessage.copy(timeline = emptyList()), status, policyError.message, contributorsExhausted = true)
                _notices.tryEmit(AgentRunNotice(request.chatId, request.runId, requireNotNull(policyError.message), true))
            }
            return true
        }
        var checkpoints = newCheckpoints()
        suspend fun pauseForProvider(decision: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision, stage: dev.chungjungsoo.gptmobile.data.research.ResearchDeferredStage? = null): Boolean {
            if (!decision.knownNotAccepted) return false
            val savedStage = stage ?: researchLifecycle?.let { lifecycle ->
                dev.chungjungsoo.gptmobile.data.research.ResearchDeferredStage(lifecycle.rootWorkId, "PRIMARY", dev.chungjungsoo.gptmobile.data.research.researchHash(request.userMessages.lastOrNull()?.content.orEmpty()), decision)
            }
            return withContext(NonCancellable) {
                checkpoints.drain()
                chatRepository.updateAgentMessage(assistantMessage)
                val waiting = providerWaits?.deferRunning(request, decision, savedStage) == true
                if (waiting) {
                    researchLifecycle?.terminal(dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.WAITING_PROVIDER_LIMIT, "PROVIDER_LIMIT:${decision.retryAt}")
                    _notices.tryEmit(AgentRunNotice(request.chatId, request.runId, "Work saved. Waiting for the provider limit to reset at ${java.time.Instant.ofEpochMilli(decision.retryAt)}.", true))
                }
                waiting
            }
        }
        try {
            request.providerWaitAuthority?.validate(settingRepository)
            researchLifecycle = prepareResearchLifecycle(request, eligibilityFailure)
            researchLifecycle?.let { researchLifecycles[request.runId] = it }
            if (eligibilityFailure != null) {
                val completedAt = currentEpochSeconds()
                committedForHandoff = withContext(NonCancellable) {
                    chatRepository.finishQueuedAgentRun(request.runId, AgentRunStatus.FAILED, completedAt, eligibilityFailure)
                }
                if (committedForHandoff) assistantMessage = terminalAgentMessage(assistantMessage, eligibilityFailure, completedAt).withCombinedOutcome(AgentRunStatus.FAILED)
            } else {
                if (!withContext(NonCancellable) { chatRepository.markAgentRunRunning(request.runId, startedAt) }) return@coroutineScope
                val recovered = if (request.chatToolConfig?.combined?.requested?.singlePass == true) {
                    chatRepository.observeAgentRuns(request.chatId).first().firstOrNull { it.runId == request.runId }?.let { CombinedAcceptedTurn.decode(it.synthesisSnapshot)?.recoveredProviderAnswer }
                } else {
                    null
                }
                val response = if (recovered != null) {
                    kotlinx.coroutines.flow.flowOf(dev.chungjungsoo.gptmobile.data.dto.ApiState.Success(recovered), dev.chungjungsoo.gptmobile.data.dto.ApiState.Done)
                } else {
                    chatRepository.completeChat(
                        request.userMessages,
                        request.assistantMessages,
                        request.platform,
                        request.runId,
                        request.chatToolConfig,
                        request.stage
                    )
                }
                val outcome = response.flowOn(researchLifecycle ?: kotlin.coroutines.EmptyCoroutineContext).collectApiStateUpdates(
                    onUpdate = { content, thoughts, timeline ->
                        val retained = if (content.isEmpty() && committedPrefix.isNotEmpty()) committedPrefix else content
                        require(retained.startsWith(committedPrefix)) { "A resumed Combined stream attempted to replace its committed prefix." }
                        assistantMessage = assistantMessage.copy(
                            content = retained,
                            thoughts = thoughts,
                            timeline = recoveryMarkers + timeline
                        )
                        _streamMessages.update { current ->
                            val owner = current[assistantMessage.id]
                            if (owner != null && owner.ownershipFence > assistantMessage.ownershipFence) current else current + (assistantMessage.id to assistantMessage)
                        }
                        val now = SystemClock.elapsedRealtime()
                        val firstAnswer = !firstAnswerCheckpoint && dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection.hasAnswer(retained)
                        if (firstAnswer || now - lastCheckpointAt >= 500L) {
                            dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnostics.journal?.record(request.runId, dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.REDUCER_CHECKPOINT, fence = assistantMessage.ownershipFence, visibleBytes = content.toByteArray(Charsets.UTF_8).size.toLong(), reasoningBytes = thoughts.toByteArray(Charsets.UTF_8).size.toLong())
                            checkpoints.offer(assistantMessage)
                            firstAnswerCheckpoint = firstAnswerCheckpoint || firstAnswer
                            lastCheckpointAt = now
                        }
                    },
                    onNotice = { notice, persistent ->
                        _notices.tryEmit(AgentRunNotice(request.chatId, request.runId, notice, persistent))
                    },
                    onResearchReplacement = { digest, text ->
                        checkpoints.offer(assistantMessage)
                        checkpoints.drain()
                        val replaced = withContext(NonCancellable) { chatRepository.replaceResearchDraft(request.runId, assistantMessage.ownershipFence, digest, text) }
                        checkpoints = newCheckpoints()
                        if (replaced != null) {
                            assistantMessage = replaced
                            _streamMessages.update { it + (replaced.id to replaced) }
                        }
                        replaced != null
                    },
                    onCombinedReplacement = { candidate ->
                        checkpoints.offer(assistantMessage)
                        checkpoints.drain()
                        val replaced = withContext(NonCancellable) {
                            chatRepository.replaceCombinedAnswer(request.runId, assistantMessage.ownershipFence, candidate)
                        }
                        checkpoints = newCheckpoints()
                        if (replaced != null) {
                            assistantMessage = replaced
                            committedPrefix = replaced.content
                            _streamMessages.update { it + (replaced.id to replaced) }
                        }
                        replaced != null
                    },
                    onCombinedAnswer = { content, thoughts, timeline ->
                        require(content.startsWith(committedPrefix)) { "A final Combined bundle attempted to replace its committed prefix." }
                        // Drain drafts before the final transaction so an older conflated
                        // checkpoint cannot subsequently overwrite the attribution bundle.
                        checkpoints.drain()
                        val bundled = assistantMessage.copy(content = content, thoughts = thoughts, timeline = recoveryMarkers + timeline)
                        val committed = withContext(NonCancellable) { chatRepository.checkpointCombinedAnswer(bundled) }
                        checkpoints = newCheckpoints()
                        if (committed) {
                            assistantMessage = bundled
                            firstAnswerCheckpoint = true
                            lastCheckpointAt = SystemClock.elapsedRealtime()
                        }
                        committed
                    },
                    onPhaseChanged = { phase ->
                        _activeRuns.update { runs ->
                            val current = runs[request.runId]
                            if (current != null) {
                                runs + (request.runId to current.copy(phase = phase))
                            } else {
                                runs
                            }
                        }
                    },
                    onNativeProgress = { progress ->
                        _activeRuns.update { runs ->
                            val current = runs[request.runId]
                            if (current == null) runs else runs + (request.runId to current.copy(nativeProgress = progress))
                        }
                    },
                    onGatewayProgress = { progress ->
                        updateGatewayProgress(request.runId, progress)
                    },
                    publishIntervalMillis = publishIntervalMillis
                )
                checkpoints.drain()
                request.providerWaitAuthority?.validate(settingRepository)
                AppLogRecorder.record("Streaming", "CHECKPOINT_WRITER · queueAgeMs=${checkpoints.lastQueueAgeMillis} · writeMs=${checkpoints.lastWriteMillis}")
                val limit = (outcome as? ApiStateFlowOutcome.Failed)?.providerLimit
                if (limit != null && pauseForProvider(limit)) return@coroutineScope
                val terminal = outcome.toTerminalUpdate()
                val completedAt = currentEpochSeconds()
                val waitingRemote = terminal.status == AgentRunStatus.WAITING_REMOTE_JOB
                val terminalMessage = terminalAgentMessage(assistantMessage, terminal.error.takeUnless { waitingRemote }, completedAt).withCombinedOutcome(terminal.status)
                val committed = withContext(NonCancellable) {
                    traceReducedOutput(terminalMessage)
                    chatRepository.commitAgentRun(terminalMessage, request.runId, terminal.status, completedAt, terminal.error)
                }
                if (committed && waitingRemote) {
                    _notices.tryEmit(AgentRunNotice(request.chatId, request.runId, "The remote job is still waiting. Its job ID and any answer are saved; check its status or continue without this profile.", true))
                    return@coroutineScope
                }
                committedForHandoff = committed
                if (committed) settleResearchOutcome(request, researchLifecycle, terminalMessage, terminal.status, terminal.error)
                if (committed && terminal.status == AgentRunStatus.COMPLETED && terminalMessage.id > 0) {
                    completionStore.record(request.runId, request.chatId, terminalMessage.id, completedAt)
                }
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                checkpoints.cancelAndJoin()
                val completedAt = currentEpochSeconds()
                val isInterrupted = interruptingRunIds.remove(request.runId)
                val cancellationReason = when {
                    isInterrupted -> AgentRunTerminalError.SERVICE_STOPPED
                    excludedRunIds.remove(request.runId) -> "EXCLUDED_FROM_COMBINED_TURN"
                    else -> null
                }
                runCatching {
                    traceReducedOutput(assistantMessage)
                    val cancelledMessage = assistantMessage.copy(createdAt = completedAt).resetActiveRevision().withCombinedOutcome(if (isInterrupted) AgentRunStatus.INTERRUPTED else AgentRunStatus.CANCELED)
                    val committed = chatRepository.commitAgentRun(
                        cancelledMessage,
                        request.runId,
                        if (isInterrupted) AgentRunStatus.INTERRUPTED else AgentRunStatus.CANCELED,
                        completedAt,
                        cancellationReason
                    )
                    if (committed) settleResearchOutcome(request, researchLifecycle, cancelledMessage, if (isInterrupted) AgentRunStatus.INTERRUPTED else AgentRunStatus.CANCELED, cancellationReason ?: "USER_CANCELLED")
                }.onFailure { failure -> finishUnavailablePolicy(failure, if (isInterrupted) AgentRunStatus.INTERRUPTED else AgentRunStatus.CANCELED) }
            }
            throw error
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                var terminalFailure = error
                if (error is dev.chungjungsoo.gptmobile.data.research.ResearchProviderDeferred) {
                    try {
                        if (pauseForProvider(error.checkpoint.limit, error.checkpoint)) return@withContext
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (pauseFailure: Exception) {
                        // A stale grant or failed durable pause must still terminalize the
                        // active owner with its retained partial instead of escaping cleanup.
                        terminalFailure = pauseFailure
                    }
                }
                checkpoints.cancelAndJoin()
                val completedAt = currentEpochSeconds()
                val message = terminalFailure.message ?: "Unknown provider error."
                val revokedWait = terminalFailure is dev.chungjungsoo.gptmobile.data.context.ProviderWaitAuthorityChanged
                val status = if (revokedWait) AgentRunStatus.INTERRUPTED else AgentRunStatus.FAILED
                if (finishUnavailablePolicy(terminalFailure, status)) return@withContext
                runCatching {
                    val terminalMessage = terminalAgentMessage(assistantMessage, message, completedAt).withCombinedOutcome(status)
                    traceReducedOutput(terminalMessage)
                    val committed = chatRepository.commitAgentRun(terminalMessage, request.runId, status, completedAt, message)
                    committedForHandoff = committed && !revokedWait
                    if (committed) settleResearchOutcome(request, researchLifecycle, terminalMessage, status, message)
                    if (committed && revokedWait) _notices.tryEmit(AgentRunNotice(request.chatId, request.runId, "Saved work needs review before it can resume. Its evidence and answer are retained.", true))
                }.onFailure { failure -> finishUnavailablePolicy(failure) }
            }
        } finally {
            withContext(NonCancellable) { checkpoints.cancelAndJoin() }
        }
        if (committedForHandoff && request.chatToolConfig?.combined?.role?.finalization == true) {
            try {
                withChatGate(request.chatId) {
                    synthesisPlanner?.prepareFallback(request.chatId, request.runId)?.let { next -> startUnlocked(listOf(next)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLogRecorder.record("Combined", "SUCCESSOR_NOT_STARTED · run=${request.runId} · reason=${failure.javaClass.simpleName}", "W")
            }
        }
        if (committedForHandoff && request.chatToolConfig?.combined?.role == CombinedRunRole.CONTRIBUTOR) {
            val combined = requireNotNull(request.chatToolConfig.combined)
            val userId = request.userMessages.lastOrNull()?.id ?: return@coroutineScope
            try {
                // Keep the final contributor registered and its foreground service alive through
                // durable synthesis preparation. Publish the new writer before releasing this one.
                withChatGate(request.chatId) {
                    synthesisPlanner?.prepare(request.chatId, userId, combined.rootBudgetId, "CONTRIBUTORS_TERMINAL")?.let { next ->
                        withContext(NonCancellable) {
                            if (chatRepository.isCombinedTurnCancelled(combined.rootBudgetId)) {
                                chatRepository.finishActiveAgentRun(next.runId, AgentRunStatus.CANCELED, currentEpochSeconds(), null)
                            } else {
                                startUnlocked(listOf(next))
                            }
                        }
                    }
                    settleResearchOutcome(request, researchLifecycle, assistantMessage, AgentRunStatus.FAILED, "FINAL_DELIVERY_NOT_STARTED", contributorsExhausted = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                chatRepository.markCombinedMergeUnavailable(request.chatId, userId, combined.rootBudgetId)
                _notices.tryEmit(AgentRunNotice(request.chatId, request.runId, "The Combined handoff could not finish. Saved contributions remain available for explicit retry.", true))
                AppLogRecorder.record("Combined", "HANDOFF_FAILED · root=${combined.rootBudgetId} · reason=${failure.javaClass.simpleName}", "W")
            }
        }
    }
}

internal const val DEFAULT_GATEWAY_RECOVERY_MAX_AGE_SECONDS = 10 * 60L

internal fun gatewayRecoveryAgeSeconds(run: dev.chungjungsoo.gptmobile.data.database.entity.AgentRun, nowSeconds: Long): Long =
    (nowSeconds - (run.startedAt ?: run.createdAt)).coerceAtLeast(0)

internal fun gatewayRecoveryExpired(
    run: dev.chungjungsoo.gptmobile.data.database.entity.AgentRun,
    nowSeconds: Long,
    maxAgeSeconds: Long = DEFAULT_GATEWAY_RECOVERY_MAX_AGE_SECONDS
): Boolean = gatewayRecoveryAgeSeconds(run, nowSeconds) >= maxAgeSeconds

internal data class AgentRunTerminalUpdate(val status: String, val error: String?)

internal fun resolvePublishInterval(
    hardwareState: dev.chungjungsoo.gptmobile.data.localruntime.DeviceHardwareState,
    isHighMemoryDevice: Boolean
): Long {
    val policy = DeviceHardwareGovernor.computeThrottlingPolicy(hardwareState, isHighMemoryDevice)
    return policy.streamPublishIntervalMillis.coerceAtLeast(33L)
}

internal fun terminalAgentMessage(message: MessageV2, error: String?, completedAt: Long): MessageV2 {
    if (error == null) return message.copy(createdAt = completedAt).resetActiveRevision()

    return message.copy(
        timeline = message.timeline + dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.NOTICE, runError = error),
        createdAt = completedAt
    ).resetActiveRevision()
}

internal fun ApiStateFlowOutcome.toTerminalUpdate(): AgentRunTerminalUpdate = when (this) {
    ApiStateFlowOutcome.Completed -> AgentRunTerminalUpdate(AgentRunStatus.COMPLETED, null)

    is ApiStateFlowOutcome.Failed -> AgentRunTerminalUpdate(if (errorCode == "QUEUE_JOB_PENDING") AgentRunStatus.WAITING_REMOTE_JOB else AgentRunStatus.FAILED, message)

    ApiStateFlowOutcome.Incomplete -> AgentRunTerminalUpdate(
        AgentRunStatus.FAILED,
        "Provider stream ended without completion."
    )
}

internal suspend fun commitTerminalAgentRun(
    finishRun: suspend () -> Boolean,
    persistMessage: suspend () -> Unit
): Boolean = withContext(NonCancellable) {
    if (!finishRun()) return@withContext false
    persistMessage()
    true
}

internal suspend fun cancelAndJoinAgentRun(job: Job?, finishActiveRun: suspend () -> Unit) {
    job?.cancelAndJoin()
    finishActiveRun()
}

/** A broken budget journal must never prevent the user's Stop action from cancelling provider jobs. */
internal suspend fun cancelCombinedRootsAndRuns(
    roots: Collection<String>,
    cancelRoot: suspend (String) -> Unit,
    onRootFailure: (String, Exception) -> Unit,
    cancelRuns: suspend () -> Unit
) = withContext(NonCancellable) {
    try {
        roots.distinct().forEach { root ->
            try {
                cancelRoot(root)
            } catch (failure: Exception) {
                onRootFailure(root, failure)
            }
        }
    } finally {
        cancelRuns()
    }
}

internal fun Job.invokeOnCompletionCleanup(cleanup: () -> Unit) {
    invokeOnCompletion { cleanup() }
}

private fun currentEpochSeconds(): Long = System.currentTimeMillis() / 1000

/** Never forward a current profile's credentials to an endpoint saved by a different connection. */
internal fun sameGatewayEndpoint(current: String, saved: String): Boolean = runCatching {
    val a = java.net.URI(current.trim().trimEnd('/')).normalize()
    val b = java.net.URI(saved.trim().trimEnd('/')).normalize()
    a.scheme in setOf("http", "https") && a.host != null && a.userInfo == null && a == b
}.getOrDefault(false)
