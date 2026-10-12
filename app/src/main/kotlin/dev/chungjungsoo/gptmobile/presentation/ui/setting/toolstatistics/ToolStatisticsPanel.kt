package dev.chungjungsoo.gptmobile.presentation.ui.setting.toolstatistics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.tool.McpClientManager
import dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsRepository
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ToolStatisticsViewModel @Inject constructor(val repository: ToolStatisticsRepository, val mcp: McpClientManager) : ViewModel()

/** Reused inside configuration; live records never own or replace the form's draft. */
@Composable
fun ToolStatisticsPanel(plugin: String, name: String, enabled: Boolean = true, viewModel: ToolStatisticsViewModel = hiltViewModel()) {
    val summaryResult by remember(plugin) { viewModel.repository.summaries(plugin) }.collectAsStateWithLifecycle(dev.chungjungsoo.gptmobile.data.telemetry.tool.StatisticsRead<List<dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolRollup>>())
    val summary = summaryResult.value.orEmpty()
    val subjects by remember(plugin) { viewModel.repository.subjects(plugin) }.collectAsStateWithLifecycle(emptyList())
    val gap by viewModel.repository.coverageGap.collectAsStateWithLifecycle()
    val preferences by viewModel.repository.preferences.collectAsStateWithLifecycle()
    val health by viewModel.mcp.health.collectAsStateWithLifecycle()
    var details by rememberSaveable(plugin) { mutableStateOf(false) }
    val clock by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(30000)
            value = System.currentTimeMillis()
        }
    }
    val persistedHealth by remember(plugin) { viewModel.repository.health(plugin.removePrefix("connection:")) }.collectAsStateWithLifecycle(null)
    val connection = health[plugin.removePrefix("connection:")]
    val lastSeen = connection?.lastSuccessAtMs
    val status = when {
        !enabled -> "Disabled"
        connection?.lastFailureAtMs != null && connection.lastFailureAtMs >= (lastSeen ?: 0L) -> connection.state.name.lowercase().replace('_', ' ')
        plugin.startsWith("connection:") && lastSeen != null && clock - lastSeen <= 300000 -> "Server responded · ${connection.state.name.lowercase()}"
        plugin.startsWith("connection:") && lastSeen != null -> "Last seen · ${timestamp(lastSeen)}"
        plugin.startsWith("connection:") && persistedHealth?.source == "CONFIG_CHANGED" -> "Not checked · Connection changed"
        plugin.startsWith("connection:") && persistedHealth != null -> "Last observation · ${persistedHealth!!.state.lowercase().replace('_', ' ')} · ${timestamp(persistedHealth!!.observedAt)}"
        plugin.startsWith("connection:") -> "Not checked"
        plugin in setOf("calculator", "current_date") -> "Available locally · No network required"
        else -> "Local readiness depends on permissions and dependencies"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.QueryStats, null)
                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_usage_availability_6f403), style = MaterialTheme.typography.titleMedium)
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (summaryResult.value == null) {
                Text(if (summaryResult.unavailable) "Statistics unavailable" else "Loading statistics…")
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Total", summary.sumOf { it.total })
                    Metric("Successful", summary.sumOf { it.success })
                    Metric("Failed", summary.sumOf { it.failure })
                }
            }
            val since = subjects.minOfOrNull { it.since }
            Text((if (preferences.includesImportedHistory) "Retained lifetime · Includes imported history · All profiles" else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_lifetime_this_device_all_profiles_a16ac)) + (since?.let { " · Since ${timestamp(it)}" } ?: ""), style = MaterialTheme.typography.labelSmall)
            if (gap) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_statistics_may_be_incomplete_b6fb3), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { details = true }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_view_statistics_2f47c)) }
        }
    }
    if (details) ToolStatisticsDetails(plugin, name, { details = false }, viewModel)
}

@Composable
private fun Metric(label: String, count: Long) {
    Column {
        Text("$count", style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
private fun timestamp(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolStatisticsDetails(plugin: String, name: String, onDismiss: () -> Unit, model: ToolStatisticsViewModel) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var limit by rememberSaveable { mutableStateOf(50) }
    var outcome by rememberSaveable { mutableStateOf("All") }
    var clear by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var child by remember { mutableStateOf<Pair<String, String>?>(null) }
    val records by remember(plugin, limit) { model.repository.activity(plugin, limit) }.collectAsStateWithLifecycle(emptyList())
    val totalsResult by remember(plugin) { model.repository.summaries(plugin) }.collectAsStateWithLifecycle(dev.chungjungsoo.gptmobile.data.telemetry.tool.StatisticsRead<List<dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolRollup>>())
    val totals = totalsResult.value.orEmpty()
    val subjects by remember(plugin) { model.repository.subjects(plugin) }.collectAsStateWithLifecycle(emptyList())
    val prefs by model.repository.preferences.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun operation(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                error = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Could not update statistics. Please try again."
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = model.repository.export()
                    withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) } ?: error("Could not open destination") }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    error = "Could not export statistics."
                }
            }
        }
    }
    FadingDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { TopAppBar(title = { Text(name) }, navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                ScrollableTabRow(tab, edgePadding = 8.dp) { listOf("Overview", "Activity", "Diagnostics", "Configuration").forEachIndexed { i, title -> Tab(tab == i, { tab = i }, text = { Text(title) }) } }
                LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    if (tab == 0) {
                        item { ToolUsageChart(plugin, model.repository) }
                        item {
                            if (totalsResult.value == null) {
                                Text(if (totalsResult.unavailable) "Statistics unavailable" else "Loading statistics…")
                            } else {
                                val determinate = totals.sumOf { it.determinate }
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_lifetime_outcome_summary_80f1d), style = MaterialTheme.typography.titleLarge)
                                Text(if (determinate == 0L) "Success rate — · No determinate executions" else "Success rate %.1f%% · %d determinate executions".format(totals.sumOf { it.success } * 100.0 / determinate, determinate))
                                Text("${totals.sumOf { it.partial }} partial · ${totals.sumOf { it.canceled }} canceled · ${totals.sumOf { it.unknown }} unknown · ${totals.sumOf { it.inProgress }} in progress")
                                Text("${totals.sumOf { it.shared }} shared-result receipts")
                                val samples = totals.sumOf { it.liveSamples }
                                if (samples > 0) Text("Mean successful live response: ${totals.sumOf { it.liveDurationMs } / samples} ms · $samples samples")
                                val p95 = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolRollup.p95UpperBound(totals)
                                Text(p95?.let { "p95 successful live response ≤ $it ms · Histogram estimate" } ?: "p95 — · No latency samples")
                                if (samples in 1..19) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_low_sample_10261))
                            }
                        }
                        items(subjects, key = { it.key }) { subject ->
                            Card(onClick = { child = subject.key to subject.capability }) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text(subject.capability)
                                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_view_this_tool_s_activity_47aac))
                                }
                            }
                        }
                    }
                    if (tab == 1) {
                        item { Row { listOf("All", "SUCCESS", "FAILURE", "UNKNOWN").forEach { value -> TextButton(onClick = { outcome = value }) { Text(value.lowercase()) } } } }
                        if (records.isEmpty()) item { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_no_executions_recorded_opening_settings_never_d5617)) }
                        items(records.filter { outcome == "All" || it.outcome == outcome }, key = { it.id }) { row ->
                            Card {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(row.outcome.lowercase().replace('_', ' '), style = MaterialTheme.typography.titleMedium)
                                    Text("${timestamp(row.startedAt)} · ${row.durationMs?.let { "$it ms" } ?: "Duration unknown"} · ${row.owner}")
                                    Text("Delivery: ${row.delivery.lowercase()}${row.reason?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item { TextButton(onClick = { limit = (limit + 50).coerceAtMost(1000) }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_load_more_ac899)) } }
                    }
                    if (tab == 2) {
                        item {
                            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_measurement_coverage_53769), style = MaterialTheme.typography.titleLarge)
                            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_logical_uses_are_recorded_after_admission_pri_6db0e))
                            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_mcp_upstream_attempts_are_correlated_when_obs_759db))
                            Text("History retention: ${prefs.retentionDays} days. Lifetime aggregates survive history pruning and chat deletion.")
                        }
                    }
                    if (tab == 3) {
                        item {
                            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_local_statistics_preferences_704a6), style = MaterialTheme.typography.titleLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_collect_local_statistics_87b1b), Modifier.weight(1f))
                                Switch(prefs.collect, { checked -> operation { model.repository.preferences { it.copy(collect = checked) } } })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_keep_activity_history_13f69), Modifier.weight(1f))
                                Switch(prefs.history, { checked -> operation { model.repository.preferences { it.copy(history = checked) } } })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(7, 30, 90).forEach { days -> FilterChip(prefs.retentionDays == days, { operation { model.repository.preferences { it.copy(retentionDays = days) } } }, label = { Text("$days days") }) } }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_include_in_statistics_backups_82caf), Modifier.weight(1f))
                                Switch(prefs.includeInBackup, { checked -> operation { model.repository.preferences { it.copy(includeInBackup = checked) } } })
                            }
                            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_these_preferences_apply_to_all_tools_on_this__d85d3))
                            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_no_prompts_tool_arguments_result_content_cred_c2925))
                            TextButton(onClick = { export.launch("tool-statistics-v1.json") }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_export_all_local_statistics_a5d58)) }
                            TextButton(onClick = { clear = true }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_clear_statistics_for_this_item_dbc10)) }
                        }
                    }
                }
            }
        }
    }
    if (clear) {
        AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear statistics for $name?") }, text = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_usage_history_and_totals_will_be_removed_conf_e93f7)) }, confirmButton = {
            TextButton(onClick = {
                operation { model.repository.clear(plugin) }
                clear = false
            }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_clear_83b12)) }
        }, dismissButton = { TextButton(onClick = { clear = false }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_cancel_19766)) } })
    }
    child?.let { ToolStatisticsDetails(it.first, it.second, { child = null }, model) }
}

@Composable
fun ToolStatisticsLink(subject: String, name: String, model: ToolStatisticsViewModel = hiltViewModel()) {
    var open by rememberSaveable(subject) { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Statistics · $name") }
    if (open) ToolStatisticsDetails(subject, name, { open = false }, model)
}
