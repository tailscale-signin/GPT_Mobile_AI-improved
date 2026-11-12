package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.readiness.ReadinessHints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A screen owns selection and presentation; the repository owns exact-model load reconciliation. */
class GatewayModelSelectionController(
    private val repository: GatewayModelPreparationRepository?,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val networkChanges: StateFlow<Long> = ReadinessHints.networkEpoch
) {
    private data class Selection(val profile: PlatformV2, val mode: ReasoningMode)
    private val selected = mutableMapOf<String, Selection>()
    private val jobs = mutableMapOf<String, Job>()
    private val checkedAt = mutableMapOf<String, Long>()
    private var networkObserver: Job? = null
    private val mutable = MutableStateFlow<Map<String, GatewayModelPreparationState>>(emptyMap())
    val states = mutable.asStateFlow()

    fun update(profiles: List<PlatformV2>, modes: Map<String, ReasoningMode> = emptyMap()) {
        val next = profiles.filter { it.enabled && it.compatibleType == ClientType.LLAMA && it.model.isNotBlank() }
            .associate { it.uid to Selection(it, modes[it.uid] ?: ReasoningPolicy.preference(it.reasoning, null)) }
        (selected.keys - next.keys).forEach { uid ->
            selected.remove(uid)
            jobs.remove(uid)?.cancel()
            checkedAt.remove(uid)
            mutable.update { it - uid }
        }
        next.forEach { (uid, selection) ->
            if (selected[uid] != selection) {
                selected[uid] = selection
                jobs.remove(uid)?.cancel()
                mutable.update { it - uid }
                start(uid, selection, forceRefresh = false)
            } else if (jobs[uid]?.isActive != true && checkedAt[uid]?.let { now() - it in 0 until 20_000L } != true) {
                start(uid, selection, forceRefresh = false)
            }
        }
        if (selected.isEmpty()) {
            networkObserver?.cancel()
            networkObserver = null
        } else if (repository != null && networkObserver?.isActive != true) {
            var epoch = networkChanges.value
            networkObserver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                networkChanges.collect { current ->
                    if (current != epoch) {
                        epoch = current
                        selected.toMap().forEach { (uid, selection) ->
                            // The repository retains any dispatched operation before the
                            // old observer detaches; reconnect reconciles that same load.
                            jobs.remove(uid)?.cancel()
                            start(uid, selection, forceRefresh = true)
                        }
                    }
                }
            }
        }
    }

    fun recheck(profileUid: String) {
        val selection = selected[profileUid] ?: return
        if (jobs[profileUid]?.isActive == true) return
        start(profileUid, selection, forceRefresh = true)
    }

    fun clear() = update(emptyList())

    private fun start(uid: String, selection: Selection, forceRefresh: Boolean) {
        val service = repository ?: return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val context = currentCoroutineContext()
            service.prepareSelected(selection.profile, selection.mode, forceRefresh) { state ->
                if (context.isActive && selected[uid] == selection) {
                    mutable.update { if (state == null) it - uid else it + (uid to state) }
                    if (state?.busy != true) checkedAt[uid] = now()
                }
            }
        }
        jobs[uid] = job
        job.start()
    }
}
