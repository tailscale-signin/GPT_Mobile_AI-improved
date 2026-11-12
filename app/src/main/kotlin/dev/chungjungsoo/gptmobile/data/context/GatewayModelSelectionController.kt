package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A screen owns selection and presentation; the repository owns exact-model load reconciliation. */
class GatewayModelSelectionController(private val repository: GatewayModelPreparationRepository?, private val scope: CoroutineScope) {
    private data class Selection(val profile: PlatformV2, val mode: ReasoningMode)
    private val selected = mutableMapOf<String, Selection>()
    private val jobs = mutableMapOf<String, Job>()
    private val mutable = MutableStateFlow<Map<String, GatewayModelPreparationState>>(emptyMap())
    val states = mutable.asStateFlow()

    fun update(profiles: List<PlatformV2>, modes: Map<String, ReasoningMode> = emptyMap()) {
        val next = profiles.filter { it.enabled && it.compatibleType == ClientType.LLAMA && it.model.isNotBlank() }
            .associate { it.uid to Selection(it, modes[it.uid] ?: ReasoningPolicy.preference(it.reasoning, null)) }
        (selected.keys - next.keys).forEach { uid ->
            selected.remove(uid)
            jobs.remove(uid)?.cancel()
            mutable.update { it - uid }
        }
        next.forEach { (uid, selection) ->
            if (selected[uid] != selection) {
                selected[uid] = selection
                jobs.remove(uid)?.cancel()
                mutable.update { it - uid }
                start(uid, selection, forceRefresh = false)
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
                }
            }
        }
        jobs[uid] = job
        job.start()
    }
}
