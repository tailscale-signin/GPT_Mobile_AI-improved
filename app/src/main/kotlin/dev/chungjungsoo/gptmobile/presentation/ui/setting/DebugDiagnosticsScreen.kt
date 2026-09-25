package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.localruntime.DiagnosticsTelemetryProvider
import dev.chungjungsoo.gptmobile.data.model.DebugMetric
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugDiagnosticsScreen(
    settingViewModel: SettingViewModelV2,
    onNavigationClick: () -> Unit,
    onStatisticsClick: () -> Unit,
    viewModel: DebugDiagnosticsViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val analytics by viewModel.analytics.collectAsState()
    val settings by settingViewModel.featureSettings.collectAsState()
    val backend by settingViewModel.localRuntimeBackend.collectAsState()
    val debugEnabled by settingViewModel.debugMode.collectAsState()
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }

    val hardware = remember(refreshKey, backend) {
        DiagnosticsTelemetryProvider.getSnapshot(
            context = context,
            backendName = backend.displayName,
            accelerator = if (backend == LocalRuntimeBackend.QUALCOMM_QNN) "Qualcomm Hexagon HTP" else "LiteRT"
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Debug & Diagnostics")
                        Text(
                            "Live runtime health, recent agent runs and tool activity",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigationClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh diagnostics")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                FilledTonalButton(onClick = onStatisticsClick, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
                    Text("Usage statistics")
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text("Diagnostics HUD", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Controls whether selected metrics are surfaced while AI responses are running.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(checked = debugEnabled, onCheckedChange = settingViewModel::updateDebugMode)
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Recent runs", analytics.recentRuns.size.toString(), Modifier.weight(1f))
                    MetricCard("Failed", analytics.failedRuns.toString(), Modifier.weight(1f))
                    MetricCard("Active", analytics.activeRuns.toString(), Modifier.weight(1f))
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Tool calls", analytics.recentToolEvents.size.toString(), Modifier.weight(1f))
                    MetricCard("Tool failures", analytics.failedToolCalls.toString(), Modifier.weight(1f))
                    MetricCard(
                        "Avg tool",
                        analytics.averageToolDurationMs?.let { "${it}ms" } ?: "—",
                        Modifier.weight(1f)
                    )
                }
            }

            item {
                DiagnosticsPanelCard("Model usage", Icons.Default.Speed) {
                    usageBars(analytics.modelUsage)
                }
            }

            item {
                DiagnosticsPanelCard("Provider usage", Icons.Default.Memory) {
                    usageBars(analytics.providerUsage)
                }
            }

            item {
                DiagnosticsPanelCard("AI profile usage", Icons.Default.BugReport) {
                    usageBars(analytics.profileUsage)
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("Tracked tokens", formatTokenCount(analytics.totalTrackedTokens), Modifier.weight(1f))
                    MetricCard(
                        "Runs with usage",
                        analytics.recentRuns.count { it.totalTokens != null }.toString(),
                        Modifier.weight(1f)
                    )
                }
            }

            item {
                DiagnosticsPanelCard("Tokens by model", Icons.Default.Speed) {
                    TokenusageBars(analytics.modelTokenUsage)
                }
            }

            item {
                DiagnosticsPanelCard("Tokens by AI profile", Icons.Default.BugReport) {
                    TokenusageBars(analytics.profileTokenUsage)
                }
            }

            item {
                DiagnosticsPanelCard("Display in Debug Mode", Icons.Default.Terminal) {
                    DebugMetric.entries.forEach { metric ->
                        val checked = when (metric) {
                            DebugMetric.TOOL_CALLS -> settings.debugShowToolCalls
                            DebugMetric.TOTAL_TOKENS -> settings.debugShowTotalTokens
                            DebugMetric.TOKEN_SPEED -> settings.debugShowTokenSpeed
                            DebugMetric.TIME_TO_FIRST_TOKEN -> settings.debugShowTimeToFirstToken
                            DebugMetric.RUNTIME -> settings.debugShowRuntime
                            DebugMetric.HARDWARE -> settings.debugShowHardware
                            DebugMetric.NETWORK -> settings.debugShowNetwork
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(metric.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(metric.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = checked,
                                onCheckedChange = { settingViewModel.updateDebugMetric(metric, it) }
                            )
                        }
                    }
                }
            }

            item {
                DiagnosticsPanelCard("Hardware snapshot", Icons.Default.Memory) {
                    DiagnosticsLine("Backend", hardware.backendName)
                    DiagnosticsLine("Accelerator", hardware.accelerator)
                    DiagnosticsLine("SoC", hardware.socModel)
                    DiagnosticsLine("Available RAM", "${hardware.availableRamMb} MB / ${hardware.totalRamGb} GB")
                    DiagnosticsLine("Thermal", hardware.thermalStatus)
                    DiagnosticsLine("Battery", if (hardware.batteryPct >= 0) "${hardware.batteryPct}%" else "Unknown")
                    DiagnosticsLine("QNN", if (hardware.qnnReady) "Ready" else "Unavailable / fallback")
                    FilledTonalButton(
                        onClick = {
                            val report = buildString {
                                appendLine(DiagnosticsTelemetryProvider.formatDiagnosticsText(hardware, null))
                                appendLine("Recent runs: ${analytics.recentRuns.size}")
                                appendLine("Completed runs: ${analytics.completedRuns}")
                                appendLine("Failed runs: ${analytics.failedRuns}")
                                appendLine("Tool calls: ${analytics.recentToolEvents.size}")
                                appendLine("Failed tool calls: ${analytics.failedToolCalls}")
                            }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("GPT Mobile diagnostics", report))
                            Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Text(" Copy diagnostics")
                    }
                }
            }

            item {
                Text("Recent tool activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            items(analytics.recentToolEvents.take(20), key = { it.eventId }) { event ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = null,
                            tint = if (event.status == ToolEventStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Column(Modifier.weight(1f)) {
                            Text(event.modelToolName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                buildString {
                                    append(event.connectionNameSnapshot ?: "Built-in")
                                    append(" • ")
                                    append(event.status)
                                    val start = event.startedAt
                                    val end = event.completedAt
                                    if (start != null && end != null) append(" • ${(end - start).coerceAtLeast(0)}s")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                            event.error?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiagnosticsPanelCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.fillMaxWidth().padding(top = 10.dp)) { content() }
        }
    }
}

@Composable
private fun DiagnosticsLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TokenusageBars(values: List<Pair<String, Long>>) {
    if (values.isEmpty()) {
        Text("No provider token usage reported yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val max = values.maxOf { it.second }.coerceAtLeast(1L)
    values.forEach { (label, tokens) ->
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(formatTokenCount(tokens), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            androidx.compose.material3.LinearProgressIndicator(
                progress = { tokens.toFloat() / max.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
    }
}

private fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000L -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000L -> "%.1fK".format(tokens / 1_000.0)
    else -> tokens.toString()
}

@Composable
private fun usageBars(values: List<Pair<String, Int>>) {
    if (values.isEmpty()) {
        Text("No usage data yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val max = values.maxOf { it.second }.coerceAtLeast(1)
    values.forEach { (label, count) ->
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(count.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            androidx.compose.material3.LinearProgressIndicator(
                progress = { count.toFloat() / max.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
    }
}
