package dev.chungjungsoo.gptmobile.data.delegation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** Only ephemeral controls. Restart recovery belongs to the persisted research journal. */
object DelegationRunControls {
    enum class Mode { RUNNING, PAUSED, PRIMARY_ONLY }
    private val mutable = MutableStateFlow<Map<String, Mode>>(emptyMap())
    val modes = mutable.asStateFlow()
    fun register(run: String) {
        mutable.update { it + (run to Mode.RUNNING) }
    }
    fun pause(run: String) {
        mutable.update { if (it[run] == Mode.RUNNING) it + (run to Mode.PAUSED) else it }
    }
    fun resume(run: String) {
        mutable.update { if (it[run] == Mode.PAUSED) it + (run to Mode.RUNNING) else it }
    }
    fun primaryOnly(run: String) {
        mutable.update { if (run in it) it + (run to Mode.PRIMARY_ONLY) else it }
    }
    fun remove(run: String) {
        mutable.update { it - run }
    }
    suspend fun awaitDispatch(run: String): Boolean {
        val state = modes.first { it[run] != Mode.PAUSED }
        return state[run] == Mode.RUNNING
    }
}
