package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local debug observations require an explicit debug-mode choice; retained statistics are portable. */
object LocalDiagnosticsPolicy {
    private val debugEnabled = MutableStateFlow(false)
    val state = debugEnabled.asStateFlow()
    val enabled: Boolean get() = debugEnabled.value

    fun setEnabled(enabled: Boolean) {
        debugEnabled.value = enabled
    }
}
