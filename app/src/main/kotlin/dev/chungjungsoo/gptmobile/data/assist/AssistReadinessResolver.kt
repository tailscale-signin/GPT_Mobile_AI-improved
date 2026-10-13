package dev.chungjungsoo.gptmobile.data.assist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class AssistReadiness { OFF, CHECKING, READY, PREPARING, RUNNING, SUCCEEDED, SKIPPED, UNAVAILABLE }
internal data class AssistReadinessResult(val state: AssistReadiness, val reason: String, val profileUid: String? = null)

/** Observable status only. Engine ownership remains in LocalRuntime. */
internal object AssistReadinessResolver {
    private val mutable = MutableStateFlow(AssistReadinessResult(AssistReadiness.OFF, "Local Assist is off"))
    val state = mutable.asStateFlow()
    fun publish(state: AssistReadiness, reason: String, profileUid: String? = null): Boolean {
        mutable.value = AssistReadinessResult(state, reason, profileUid)
        return state == AssistReadiness.READY
    }
}

@kotlinx.serialization.Serializable
enum class AssistPreparation { WARM_ONLY, PREPARE_AUTOMATICALLY }
