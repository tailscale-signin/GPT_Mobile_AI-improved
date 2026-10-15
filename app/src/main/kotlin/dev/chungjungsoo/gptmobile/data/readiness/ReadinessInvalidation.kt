package dev.chungjungsoo.gptmobile.data.readiness

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Advance synchronously before credential/grant/restore mutation; cleanup can happen later. */
object ReadinessInvalidation {
    private val generation = MutableStateFlow(0L)
    val changes = generation.asStateFlow()
    val epoch: Long get() = generation.value

    @Synchronized
    fun advance() {
        generation.value++
    }
}
