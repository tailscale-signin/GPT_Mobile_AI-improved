package dev.chungjungsoo.gptmobile.presentation.ui.health

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.chungjungsoo.gptmobile.data.health.HealthConnectTools
import dev.chungjungsoo.gptmobile.data.health.HealthMetric
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import dev.chungjungsoo.gptmobile.presentation.ui.main.ProtectedActivity
import java.text.NumberFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

@AndroidEntryPoint
class HealthConnectActivity : ProtectedActivity() {
    private val model: HealthConnectViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val theme by model.theme.collectAsStateWithLifecycle()
            GPTMobileTheme(theme.dynamicTheme, theme.themeMode, theme.customPrimaryArgb, theme.customPalette) {
                HealthConnectScreen(model, ::finish, ::openSystemHealth, ::showPrivacy)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        model.refreshAccess()
    }

    override fun onStop() {
        model.clearPreview()
        super.onStop()
    }

    private fun showPrivacy() = startActivity(Intent(this, HealthConnectRationaleActivity::class.java))

    private fun openSystemHealth() {
        val intent = if (model.state.value.availability == "install_or_update_required") {
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
        } else {
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            model.showError("Open your phone's Settings and search for Health Connect. On older Android versions, install or update Health Connect from Google Play.")
        } catch (_: SecurityException) {
            model.showError("Open Health Connect from your phone's Settings to manage access.")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthConnectScreen(model: HealthConnectViewModel, onBack: () -> Unit, openSystem: () -> Unit, showPrivacy: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val features by model.features.collectAsStateWithLifecycle()
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val aiAllowed by model.aiAllowed.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }
    var days by remember { mutableStateOf(1) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmAi by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { model.refreshAccess() }
    LaunchedEffect(state.permissions) { selected = state.permissions.intersect(HealthMetric.entries.map { it.permission }.toSet()) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Smartwatch Health") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = { IconButton(onClick = showPrivacy) { Icon(Icons.Rounded.Info, "Health data privacy and help") } }
        )
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            TabRow(tab) {
                listOf("Overview", "Access", "AI Tools").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    HealthCard("Health Connect · ${friendly(state.availability)}") {
                        Text("Read-only · On this phone · Synced history", color = MaterialTheme.colorScheme.primary)
                        Text("${state.permissions.intersect(HealthMetric.entries.map { it.permission }.toSet()).size} of ${HealthMetric.entries.size} record permissions granted.")
                        if (state.availability != "available") Button(onClick = openSystem) { Text("Install, update or open Health Connect") }
                    }
                }
                state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                when (tab) {
                    0 -> {
                        item {
                            HealthCard("Your statistics") {
                                Text("Sync your watch in its companion app and enable that app's Health Connect sharing first. Available data varies by watch and companion app.")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(1, 7, 30).forEach { period ->
                                        FilterChip(selected = days == period, onClick = {
                                            days = period
                                            model.clearPreview()
                                        }, label = { Text(if (period == 1) "Today" else "$period days") })
                                    }
                                }
                                Button(onClick = { model.preview(days) }, enabled = state.availability == "available" && !state.loading && !state.busy) { Text("Refresh local statistics") }
                                Text("This preview does not contact an AI. It is cleared when you leave this screen. Refresh time is not watch sync time.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        val preview = state.preview
                        if (preview != null) {
                            item {
                                Text("${friendly((preview["status"] as? JsonPrimitive)?.content.orEmpty())} · ${(preview["time_zone"] as? JsonPrimitive)?.content.orEmpty()}", style = MaterialTheme.typography.labelLarge)
                                (preview["queried_at"] as? JsonPrimitive)?.content?.let { Text("Read at $it", style = MaterialTheme.typography.bodySmall) }
                                (preview["action"] as? JsonPrimitive)?.content?.let { Text(it) }
                            }
                            val metrics = (preview["metrics"] as? JsonObject).orEmpty()
                            items(metrics.entries.toList(), key = { it.key }) { (_, element) ->
                                (element as? JsonObject)?.let { HealthMetricCard(it) }
                            }
                        }
                        item { Text("Wellness information, not a diagnosis or live monitoring. Missing data is never presented as zero.", style = MaterialTheme.typography.bodySmall) }
                    }
                    1 -> {
                        item {
                            HealthCard("1 · Choose Android read permissions") {
                                Text("Select the categories to request. No write permissions are requested. To remove an existing grant, use Health Connect settings below.")
                            }
                        }
                        items(HealthMetric.entries, key = { it.id }) { metric ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = metric.permission in selected, onCheckedChange = { checked -> selected = if (checked) selected + metric.permission else selected - metric.permission })
                                Column(Modifier.weight(1f)) {
                                    Text(metric.label)
                                    Text(if (metric.permission in state.permissions) "Granted" else "Not granted", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item {
                            Button(onClick = {
                                try {
                                    permissionLauncher.launch(selected)
                                } catch (_: Exception) {
                                    model.showError("Could not open the permission request. Install or update Health Connect, then retry.")
                                }
                            }, enabled = state.availability == "available" && selected.isNotEmpty() && !state.busy) { Text("Request selected permissions") }
                            TextButton(onClick = openSystem) { Text("Open Health Connect permissions & sources") }
                        }
                        item {
                            HealthCard("2 · Allow AI conversation use") {
                                HealthSwitch("Share permitted health results with AI", aiAllowed, !state.busy) { allowed -> if (allowed) confirmAi = true else model.setAiAllowed(false) }
                                Text("Off by default. Android permission alone never authorizes AI sharing. Local previews do not require this switch.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item {
                            OutlinedButton(onClick = { confirmRevoke = true }, enabled = !state.busy) { Text("Disconnect & revoke all health access") }
                            TextButton(onClick = showPrivacy) { Text("Privacy, retention & limitations") }
                        }
                    }
                    2 -> {
                        item {
                            HealthCard("3 · Enable the plugin") {
                                HealthSwitch("Smartwatch Health Connect", features.isToolPluginEnabled(ToolPluginId.HEALTH_CONNECT), !state.busy, model::setEnabled)
                                Text("The tools run on this phone, not on your PC gateway. Keep the app visible and your phone unlocked for a health read.")
                                Text(if (aiAllowed) "AI-sharing consent is enabled on this installation." else "AI-sharing consent is off. Configure it in Access before requesting measurements.", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        item { Text("4 · Select AI profiles", style = MaterialTheme.typography.titleMedium) }
                        if (profiles.isEmpty()) item { Text("Create an AI profile in Settings first.") }
                        items(profiles, key = { it.uid }) { profile ->
                            HealthCard(profile.name) {
                                val available = profile.enabled && !profile.disableAllTools && !profile.disableLocalTools
                                HealthSwitch("Allow health tools", features.isToolPluginSelected(profile.uid, ToolPluginId.HEALTH_CONNECT), !state.busy && available) { model.setProfileEnabled(profile.uid, it) }
                                Text(if (available) "${profile.model} · Explicit opt-in for this profile" else "Enable the profile and its local tools to use this plugin.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item { Text("Available AI tools", style = MaterialTheme.typography.titleMedium) }
                        items(HealthConnectTools.operations.entries.toList(), key = { it.key }) { (operation, metrics) ->
                            HealthCard(HealthConnectTools.PREFIX + operation) {
                                Text(if (metrics.isEmpty()) "Availability, capabilities and permissions; no measurements." else metrics.joinToString { it.label })
                                if (metrics.isNotEmpty()) Text("Today by default; up to 30 days. Optional source filter. At most 100 recent samples or sessions per record type.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmAi) {
        AlertDialog(
            onDismissRequest = { confirmAi = false },
            title = { Text("Allow health data in AI conversations?") },
            text = { Text(HealthConnectPrivacy.AI_DISCLOSURE) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAi = false
                    model.setAiAllowed(true)
                }) { Text("Allow AI use") }
            },
            dismissButton = { TextButton(onClick = { confirmAi = false }) { Text("Keep private") } }
        )
    }
    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text("Disconnect Health Connect?") },
            text = { Text("Disables this plugin, turns off AI sharing and revokes this app's Health Connect permissions. Watch records and existing conversations are not deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevoke = false
                    model.revoke()
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HealthCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun HealthSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun HealthMetricCard(metric: JsonObject) {
    val name = (metric["label"] as? JsonPrimitive)?.content.orEmpty()
    val unit = (metric["unit"] as? JsonPrimitive)?.content.orEmpty()
    val status = (metric["status"] as? JsonPrimitive)?.content.orEmpty()
    val formatter = remember { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 } }
    HealthCard(name) {
        Text(friendly(status), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        (metric["values"] as? JsonObject)?.forEach { (key, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(friendly(key))
                val number = (value as? JsonPrimitive)?.doubleOrNull
                Text(if (value == JsonNull || number == null) "Not available" else "${formatter.format(number)} ${if (key == "measurements") "samples" else unit}")
            }
        }
        if (status != "available") Text("No value returned. Review permissions, companion-app syncing and the selected time range.", style = MaterialTheme.typography.bodySmall)
        if (metric["records_complete"] == JsonPrimitive(false) || metric["sessions_complete"] == JsonPrimitive(false)) Text("Recent-record sample is limited; it is not the full history.", style = MaterialTheme.typography.bodySmall)
        val sources = (metric["source_packages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        if (sources.isNotEmpty()) {
            HorizontalDivider()
            Text("Sources: ${sources.joinToString()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun friendly(value: String): String = value.replace('_', ' ').replaceFirstChar { it.titlecase() }
