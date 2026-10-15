package dev.chungjungsoo.gptmobile.data.assist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class AssistReadiness { OFF, CHECKING, READY, PREPARING, RUNNING, SUCCEEDED, SKIPPED, UNAVAILABLE }
internal data class AssistReadinessResult(val state: AssistReadiness, val reason: String, val profileUid: String? = null, val modelLoaded: Boolean? = null, val requestedAccelerator: String? = null, val observedAccelerator: String? = null, val observedBackend: String? = null)

/** Observable status only. Engine ownership remains in LocalRuntime. */
internal object AssistReadinessResolver {
    private val mutable = MutableStateFlow(AssistReadinessResult(AssistReadiness.OFF, "Local Assist is off"))
    val state = mutable.asStateFlow()
    fun publish(state: AssistReadiness, reason: String, profileUid: String? = null, modelLoaded: Boolean? = null, requestedAccelerator: String? = null, observedAccelerator: String? = null, observedBackend: String? = null): Boolean {
        mutable.value = AssistReadinessResult(state, reason, profileUid, modelLoaded, requestedAccelerator, observedAccelerator, observedBackend)
        return state == AssistReadiness.READY
    }
}

@kotlinx.serialization.Serializable
enum class AssistPreparation { WARM_ONLY, PREPARE_AUTOMATICALLY }
