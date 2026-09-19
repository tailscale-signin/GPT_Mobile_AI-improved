package com.example.gptmobileai.debug

import android.content.Context
import com.example.gptmobileai.data.model.HardwareDiagnostics
import com.example.gptmobileai.data.model.NetworkDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveDiagnosticsState(
    val hardwareDiagnostics: HardwareDiagnostics?,
    val networkHealth: List<NetworkDiagnostics>,
    val tokenMetrics: TokenMetricsCollector.LiveTokenMetrics?,
    val toolMetrics: Map<String, ToolMetricsCollector.LiveToolMetrics>
)

class DiagnosticsTelemetryProvider(
    private val context: Context,
    private val eventBus: TelemetryCollector,
    private val config: AetherionMaxConfig = AetherionMaxConfig
) {
    private val hardwareProvider = HardwareDiagnosticsProvider(context)
    private val networkProvider = NetworkDiagnosticsProvider(eventBus, config)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private var lastHardwareSnapshot: HardwareDiagnostics? = null

    private val _liveState = MutableStateFlow(
        LiveDiagnosticsState(
            hardwareDiagnostics = null,
            networkHealth = emptyList(),
            tokenMetrics = null,
            toolMetrics = emptyMap()
        )
    )
    val liveState: StateFlow<LiveDiagnosticsState> = _liveState.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                lastHardwareSnapshot = hardwareProvider.getSnapshot()
                eventBus.emit(TelemetryEvent.HardwareSnapshot(lastHardwareSnapshot!!))

                _liveState.value = _liveState.value.copy(
                    hardwareDiagnostics = lastHardwareSnapshot
                )
                delay(config.HARDWARE_SAMPLING_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun updateTokenMetrics(metrics: TokenMetricsCollector.LiveTokenMetrics?) {
        _liveState.value = _liveState.value.copy(tokenMetrics = metrics)
    }

    fun updateToolMetrics(toolMetrics: Map<String, ToolMetricsCollector.LiveToolMetrics>) {
        _liveState.value = _liveState.value.copy(toolMetrics = toolMetrics)
    }
}
