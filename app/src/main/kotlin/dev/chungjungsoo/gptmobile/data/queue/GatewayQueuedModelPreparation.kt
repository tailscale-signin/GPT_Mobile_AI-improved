package dev.chungjungsoo.gptmobile.data.queue

import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationPhase
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationRepository
import dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationState
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Accepted Send owns loading; passive availability observations never start it themselves. */
internal class GatewayQueuedModelPreparation(
    private val repository: GatewayModelPreparationRepository,
    private val scope: CoroutineScope,
    private val now: () -> Long
) {
    internal data class Request(val owner: String, val profile: PlatformV2, val mode: ReasoningMode, val credentialRevision: String, val checkReadyModel: Boolean = false)

    private class Entry(val request: Request) {
        var job: Job? = null
        var state: GatewayModelPreparationState? = null
        var finishedAt: Long? = null
    }

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private val mutableChanges = MutableStateFlow(0L)
    val changes = mutableChanges.asStateFlow()

    fun retain(owners: Set<String>) = synchronized(lock) {
        entries.keys.filter { it !in owners }.forEach { owner -> entries.remove(owner)?.job?.cancel() }
    }

    fun cancel(owner: String) = synchronized(lock) { entries.remove(owner)?.job?.cancel() }

    /** Null leaves the existing fresh availability gate in charge of dispatch. */
    fun admission(request: Request, observed: GatewayAvailability?, onRejected: suspend () -> Unit = {}, isCurrent: suspend () -> Boolean): GatewayQueuedChatState? = synchronized(lock) {
        var entry = entries[request.owner]
        if (entry != null && entry.request != request) {
            entries.remove(request.owner)
            entry.job?.cancel()
            entry = null
        }
        // A ready model owns its slot until the queued turn takes its lease.
        // Loading another model in this gap can evict the one just prepared.
        if (entries.values.any { it !== entry && (it.finishedAt == null || it.state?.phase == GatewayModelPreparationPhase.READY) }) {
            return@synchronized waiting("Waiting for the earlier selected-model preparation and queued message to start.")
        }
        if (entry == null) {
            val needsPreparation = request.profile.compatibleType == ClientType.LLAMA &&
                observed?.conversationSyncSupported == true &&
                observed.freshnessRemainingMillis(now()) > 0 &&
                (observed.failureCode in MODEL_LOAD_CODES || observed.readinessFailureCode in MODEL_LOAD_CODES || request.checkReadyModel && observed.isOnline)
            if (!needsPreparation) return@synchronized null
            entry = Entry(request)
            entries[request.owner] = entry
            start(entry, isCurrent, onRejected)
        } else if (entry.state?.phase == GatewayModelPreparationPhase.BLOCKED &&
            entry.finishedAt != null &&
            observed?.isOnline == true &&
            observed.conversationSyncSupported &&
            observed.freshnessRemainingMillis(now()) > 0 &&
            observed.observedAtElapsedMillis?.let { it > requireNotNull(entry.finishedAt) } == true
        ) {
            // A later reachable/online probe can qualify the existing exact model,
            // but cannot itself authorize another load after a failed attempt.
            entry.finishedAt = null
            start(entry, isCurrent, onRejected, allowLoad = false)
        }
        val state = entry.state
        when {
            entry.finishedAt == null -> waiting(state?.message ?: "Checking the saved Gateway model before this message starts.")
            state?.phase != GatewayModelPreparationPhase.READY || state.observation?.permitsRequest != true ->
                GatewayQueuedChatState(GatewayQueuedState.BLOCKED, message = state?.message ?: "The selected model could not be prepared. The queued message is kept; review its original Gateway and model.")
            observed?.isOnline == true &&
                observed.conversationSyncSupported &&
                observed.freshnessRemainingMillis(now()) > 0 &&
                observed.observedAtElapsedMillis?.let { it >= requireNotNull(entry.finishedAt) } == true -> null
            else -> waiting("The selected model is prepared. Checking fresh Gateway readiness before this message starts.")
        }
    }

    private fun start(entry: Entry, isCurrent: suspend () -> Boolean, onRejected: suspend () -> Unit, allowLoad: Boolean = true) {
        val request = entry.request
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var completed = false
            suspend fun validate() {
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) throw CancellationException("The accepted Gateway Send owner changed during model preparation.")
            }
            try {
                validate()
                val result = repository.prepareSelectedWhile(request.profile, request.mode, forceRefresh = true, validateSelection = ::validate, allowLoad = allowLoad, onRejected = onRejected) { state ->
                    synchronized(lock) {
                        if (entries[request.owner] === entry) {
                            entry.state = state
                            mutableChanges.update { it + 1 }
                        }
                    }
                }
                validate()
                synchronized(lock) {
                    if (entries[request.owner] === entry) {
                        entry.state = result
                        completed = true
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Admission remains blocked; the repository retains any dispatched
                // load identity for an explicit recheck of the accepted selection.
            } finally {
                synchronized(lock) {
                    if (entries[request.owner] === entry) {
                        entry.finishedAt = now()
                        if (!completed || entry.state?.phase in setOf(null, GatewayModelPreparationPhase.CHECKING, GatewayModelPreparationPhase.LOADING)) {
                            entry.state = GatewayModelPreparationState(request.profile.uid, request.profile.model, GatewayModelPreparationPhase.BLOCKED, "Model preparation stopped before readiness was confirmed. The saved message and original load identity are kept.")
                        }
                        mutableChanges.update { it + 1 }
                    }
                }
            }
        }
        entry.job = job
        job.start()
    }

    private fun waiting(message: String) = GatewayQueuedChatState(GatewayQueuedState.WAITING_FOR_GATEWAY, message = message)

    private companion object {
        val MODEL_LOAD_CODES = setOf("SELECTED_MODEL_NOT_LOADED", "SELECTED_MODEL_LOADING")
    }
}
