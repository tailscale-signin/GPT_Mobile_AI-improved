package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Debug observations are local and transient, and require an explicit debug-mode choice. */
object LocalDiagnosticsPolicy {
    private val debugEnabled = MutableStateFlow(false)
    val state = debugEnabled.asStateFlow()
    val enabled: Boolean get() = debugEnabled.value

    fun setEnabled(enabled: Boolean) {
        debugEnabled.value = enabled
    }
}
