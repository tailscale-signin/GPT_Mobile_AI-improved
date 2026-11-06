package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardClient
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardMonitor
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardState
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDiagnosticPage
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDiagnosticStatus
import dev.chungjungsoo.gptmobile.data.network.gateway.gatewayDashboardTarget
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@HiltViewModel
internal class GitHubGatewayStatusViewModel @Inject constructor(private val settings: SettingRepository, private val client: GatewayDashboardClient) : ViewModel() {
    private val monitor = GatewayDashboardMonitor(client)
    private val mutable = MutableStateFlow(GatewayDashboardState())
    val state = mutable.asStateFlow()
    private val mutableDiagnostics = MutableStateFlow(GatewayDiagnosticViewState())
    val diagnostics = mutableDiagnostics.asStateFlow()
    private var rowsJob: Job? = null

    init {
        viewModelScope.launch {
            combine(GatewayClientDiagnostics.diagnosticStatus, state) { _, gateway -> gateway.target?.config }.collectLatest { config ->
                val observation = config?.let { GatewayClientDiagnostics.diagnosticStatusFor(it) } ?: GatewayDiagnosticStatus()
                val previous = mutableDiagnostics.value
                val changed = previous.status.routeDigest != observation.routeDigest || previous.status.sessionId != observation.sessionId
                mutableDiagnostics.value = if (changed || observation.captureState == "private_session") {
                    GatewayDiagnosticViewState(status = observation)
                } else {
                    previous.copy(status = observation)
                }
                if (changed && observation.sessionId != null && observation.captureState != "private_session") loadDiagnosticPage(0)
            }
        }
    }

    fun refresh() {
        client.refresh()
        monitor.refresh()
        loadDiagnosticPage(0)
    }

    fun diagnosticFilter(filter: String) {
        if (filter !in setOf("failures", "all", "performance", "model_tools")) return
        mutableDiagnostics.value = mutableDiagnostics.value.copy(filter = filter, traceId = null)
        loadDiagnosticPage(0)
    }

    fun diagnosticTrace(trace: String?) {
        if (trace != null && !Regex("[a-f0-9]{32}").matches(trace)) return
        mutableDiagnostics.value = mutableDiagnostics.value.copy(traceId = trace, filter = "all")
        loadDiagnosticPage(0)
    }

    fun loadDiagnosticPage(offset: Int) {
        val config = mutable.value.target?.config ?: return
        val identity = mutable.value.target?.identity
        val selected = mutableDiagnostics.value
        rowsJob?.cancel()
        rowsJob = viewModelScope.launch {
            mutableDiagnostics.value = mutableDiagnostics.value.copy(loading = true, error = null)
            try {
                val stages = when (selected.filter) {
                    "performance" -> setOf("renderer", "network", "model_queue", "model_load", "prefill", "decode")
                    "model_tools" -> setOf("model_queue", "model_load", "prefill", "decode", "mcp_discovery", "mcp_call", "operation_dispatch", "operation_reconciliation", "terminal_delivery")
                    else -> emptySet()
                }
                val page = GatewayClientDiagnostics.diagnosticPage(config, offset, selected.filter == "failures", selected.traceId, stages)
                if (mutable.value.target?.identity == identity) mutableDiagnostics.value = mutableDiagnostics.value.copy(page = page, offset = offset, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (mutable.value.target?.identity == identity) mutableDiagnostics.value = mutableDiagnostics.value.copy(loading = false, error = "Diagnostic evidence is unavailable. Check capture and storage status.")
            }
        }
    }

    suspend fun exportDiagnostics(cacheDirectory: File): File {
        val config = mutable.value.target?.config ?: throw java.io.IOException("DIAGNOSTIC_SOURCE_UNAVAILABLE")
        return dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticExportGate.run {
            val target = File(cacheDirectory, "diagnostics/gateway-session-${java.util.UUID.randomUUID()}.zip")
            val result = GatewayClientDiagnostics.exportDiagnostics(target, config)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                target.parentFile?.listFiles()?.filter { it.name.startsWith("gateway-session-") && it.extension == "zip" }
                    ?.sortedByDescending(File::lastModified)?.drop(3)?.forEach(File::delete)
            }
            result
        }
    }

    suspend fun observe(profileUid: String) {
        combine(settings.observePlatformV2s(), settings.observeFeatureSettings()) { profiles, features ->
            gatewayDashboardTarget(profileUid, profiles, features.githubProfileOverrides[profileUid]?.engineeringGatewayProfileUid)
        }.distinctUntilChanged().collectLatest { target ->
            monitor.observe(target, mutable.value) { mutable.value = it }
        }
    }
}

internal data class GatewayDiagnosticViewState(
    val status: GatewayDiagnosticStatus = GatewayDiagnosticStatus(),
    val page: GatewayDiagnosticPage = GatewayDiagnosticPage(emptyList(), null, 0),
    val filter: String = "failures",
    val traceId: String? = null,
    val offset: Int = 0,
    val loading: Boolean = false,
    val error: String? = null
)
