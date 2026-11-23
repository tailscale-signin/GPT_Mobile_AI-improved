package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Debug observations are local and transient, and require an explicit debug-mode choice. */
object LocalDiagnosticsPolicy {
    private val debugEnabled = MutableStateFlow(false)
    val state = debugEnabled.asStateFlow()
    val enabled: Boolean get() = debugEnabled.value

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        debugEnabled.value = enabled
        // Fence in-flight captures before a later enable can be observed. The
        // application preference collector may conflate a rapid off/on pair.
        if (!enabled) {
            ReportDiagnosticSnapshots.clear()
            dev.chungjungsoo.gptmobile.presentation.ui.chat.clearChatRenderingCaches()
        }
    }
}
