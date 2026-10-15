package dev.chungjungsoo.gptmobile.data.readiness

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI touches IDs and booleans only; it never resolves the heavy Hilt graph on a frame. */
object ReadinessHints {
    private val network = MutableStateFlow(0L)
    val networkEpoch = network.asStateFlow()

    @Synchronized fun networkChanged() {
        network.value++
    }
    private val ready = MutableStateFlow(false)
    private val selected = MutableStateFlow<List<String>>(emptyList())
    val uiReady = ready.asStateFlow()
    val profiles = selected.asStateFlow()
    fun usableUi() {
        ready.value = true
    }
    fun selectProfiles(ids: List<String>) {
        selected.value = ids.distinct().take(2)
    }
}
