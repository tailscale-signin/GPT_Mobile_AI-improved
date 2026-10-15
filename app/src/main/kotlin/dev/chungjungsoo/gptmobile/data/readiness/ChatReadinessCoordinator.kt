package dev.chungjungsoo.gptmobile.data.readiness

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.agent.tool.McpClientManager
import dev.chungjungsoo.gptmobile.data.assist.AssistPreparation
import dev.chungjungsoo.gptmobile.data.assist.LocalAssistPreparation
import dev.chungjungsoo.gptmobile.data.assist.allowsProcessing
import dev.chungjungsoo.gptmobile.data.assist.resolveAssistPolicy
import dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.repository.OpenRouterModelRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.presentation.AppForegroundTracker
import dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Lifecycle owner calls leaves only; never constructs ChatRepository or AgentRunCoordinator. */
@Singleton
class ChatReadinessCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingRepository,
    private val tools: AgentToolResolver,
    private val mcp: McpClientManager,
    private val models: OpenRouterModelRepository,
    private val helper: LocalAssistPreparation,
    private val runtime: LocalRuntime,
    private val memory: MemoryPreviewCoordinator,
    private val scheduler: ReadinessScheduler,
    @dev.chungjungsoo.gptmobile.di.ReadinessScope private val scope: CoroutineScope
) {
    private val hardwareAllowed = MutableStateFlow(false)
    private val started = AtomicBoolean()
    private val uiReady = ReadinessHints.uiReady
    private val selected = ReadinessHints.profiles.combine(ReadinessHints.networkEpoch) { profiles, _ -> profiles }
    fun usableUi() {
        ReadinessHints.usableUi()
    }
    fun hintProfiles(ids: List<String>) {
        ReadinessHints.selectProfiles(ids)
    }
    fun trim() {
        scheduler.cancelSpeculation()
        mcp.clearReadinessCache(cancelProducers = false)
        memory.clear()
    }
    fun clear() {
        ReadinessInvalidation.advance()
        trim()
    }
    private fun idle(): Boolean = AppForegroundTracker.visibility.value && uiReady.value && ReadinessWorkloadSignals.active.value == 0 && !InferenceAdmission.isExclusiveBusy && !runtime.getHardwareState().isModeratePressure && !runtime.getHardwareState().isThrottlingRequired
    private fun networkAllowed(metered: Boolean): Boolean = metered || (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered == false

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            AppForegroundTracker.visibility.collectLatest { visible ->
                if (!visible) {
                    hardwareAllowed.value = false
                    return@collectLatest
                }
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val hardware = runtime.getHardwareState()
                    hardwareAllowed.value = !hardware.isModeratePressure && !hardware.isThrottlingRequired
                    delay(1_000)
                }
            }
        }
        scope.launch {
            StartupRecoveryGate.await()
            combine(settings.observeFeatureSettings(), settings.observePlatformV2s(), selected) { features, profiles, hints -> Triple(features, profiles, hints) }
                .combine(combine(AppForegroundTracker.visibility, uiReady, ReadinessWorkloadSignals.active, InferenceAdmission.availability, ReadinessInvalidation.changes) { visible, ready, active, _, epoch -> Triple(visible && ready && active == 0, epoch, active) }.combine(hardwareAllowed) { available, hardware -> available.copy(first = available.first && hardware) }) { state, availability -> state to availability }
                .collectLatest { (state, availability) ->
                    scheduler.cancelSpeculation()
                    memory.clear()
                    val (features, profiles, hints) = state
                    if (!availability.first) return@collectLatest
                    delay(500)
                    if (!idle()) return@collectLatest
                    val targets = (hints + features.lastChatProfileUids).distinct().mapNotNull { uid -> profiles.firstOrNull { it.uid == uid && it.enabled } }.take(if (features.readiness.strategy == PreparationStrategy.MINIMAL_RESOURCES) 1 else 2)
                    if (features.readiness.enabled) scheduler.request("memory-metadata", ::idle) { memory.warmMetadata() }
                    if (features.readiness.enabled && networkAllowed(features.readiness.warmMeteredNetworks)) {
                        targets.forEach { profile ->
                            if (features.remoteMcpConnections) scheduler.request("mcp:${profile.uid}", ::idle) { tools.warmSelectedCatalogues(profile.uid, if (features.readiness.strategy == PreparationStrategy.MINIMAL_RESOURCES) 1 else 4) }
                        }
                        if (features.providerModelDiscovery && targets.any { it.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.OPENROUTER && dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCatalogLimits.officialEndpoint(it.apiUrl) }) scheduler.request("public-models", ::idle) { models.fetchModels() }
                    }
                    val effective = features.resolveAssistPolicy()
                    val requested = features.localAssistPolicy
                    // Explicit preload is separate from per-task cold loading. Never auto-select/download weights.
                    val target = profiles.firstOrNull { it.uid == requested.profileUid && effective.effective.allowsProcessing(it) }
                    if (effective.enabled && requested.preloadInForeground && requested.preparation == AssistPreparation.PREPARE_AUTOMATICALLY && target != null) {
                        scheduler.request("helper:${target.uid}", ::idle) {
                            if (!helper.eligible(target, effective.effective.copy(preparation = requested.preparation))) {
                                helper.recoveryRetryAtMillis?.let { until ->
                                    delay((until - android.os.SystemClock.elapsedRealtime()).coerceIn(1, 30_000))
                                    if (idle()) helper.eligible(target, effective.effective.copy(preparation = requested.preparation))
                                }
                            }
                        }
                    }
                }
        }
        scope.launch {
            ReadinessInvalidation.changes.collectLatest {
                mcp.clearReadinessCache()
                memory.clear()
            }
        }
    }
}
