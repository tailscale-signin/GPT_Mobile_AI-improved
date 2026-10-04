package dev.chungjungsoo.gptmobile.data.queue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FollowUpPhase { SEARCHING, READY, MERGING }

data class FollowUpProgress(val prompt: PendingPrompt, val phase: FollowUpPhase)

/** Transient animation state only; accepted input and history remain owned by Room. */
object FollowUpProgressStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutable = MutableStateFlow<Map<String, FollowUpProgress>>(emptyMap())
    val state = mutable.asStateFlow()

    fun update(prompt: PendingPrompt, phase: FollowUpPhase?) {
        mutable.update { entries ->
            if (phase == null && entries[prompt.id]?.phase == FollowUpPhase.MERGING) {
                entries
            } else if (phase == null) {
                entries - prompt.id
            } else {
                entries + (prompt.id to FollowUpProgress(prompt, phase))
            }
        }
        if (phase == FollowUpPhase.MERGING) {
            scope.launch {
                delay(3500)
                finish(prompt.id)
            }
        }
    }

    fun finish(id: String) {
        mutable.update { it - id }
    }
}
