package dev.chungjungsoo.gptmobile.data.queue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FollowUpPhase { COUNTDOWN, MERGING }

data class FollowUpProgress(val prompt: PendingPrompt, val phase: FollowUpPhase, val deadlineMs: Long? = null)

/** Transient animation state only; accepted input and history remain owned by Room. */
object FollowUpProgressStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutable = MutableStateFlow<Map<String, FollowUpProgress>>(emptyMap())
    val state = mutable.asStateFlow()

    fun update(prompt: PendingPrompt, phase: FollowUpPhase?, deadlineMs: Long? = null) {
        mutable.update { entries ->
            if (phase == null && entries[prompt.id]?.phase == FollowUpPhase.MERGING) {
                entries
            } else if (phase == null) {
                entries - prompt.id
            } else {
                entries + (prompt.id to FollowUpProgress(prompt, phase, deadlineMs))
            }
        }
        if (phase == FollowUpPhase.MERGING) {
            scope.launch {
                delay(450)
                finish(prompt.id)
            }
        }
    }

    fun canChange(id: String): Boolean {
        val entry = mutable.value[id] ?: return true
        return entry.phase != FollowUpPhase.MERGING &&
            (entry.deadlineMs == null || System.nanoTime() / 1_000_000L < entry.deadlineMs)
    }

    fun finish(id: String) {
        mutable.update { it - id }
    }
}
