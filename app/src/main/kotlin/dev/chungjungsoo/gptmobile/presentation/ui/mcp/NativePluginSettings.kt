package dev.chungjungsoo.gptmobile.presentation.ui.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplacePackage
import dev.chungjungsoo.gptmobile.data.marketplace.EndpointAuthType
import dev.chungjungsoo.gptmobile.data.marketplace.NativeEndpointConfig
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.marketplace.NativePluginInstallation
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog

/** Required fields are red immediately, including their focused outline. Secrets are never saveable state. */
@Composable
fun NativePluginSettings(
    entry: GitHubMarketplacePackage,
    installation: NativePluginInstallation,
    viewModel: MarketplaceViewModel,
    busy: Boolean = false,
    showEnableSwitch: Boolean = true
) {
    if (entry.provider == "universal-marketplace") {
        var open by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showEnableSwitch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (installation.enabled) "Enabled" else "Disabled", Modifier.weight(1f))
                    Switch(installation.enabled, { viewModel.setEnabled(entry, it) }, enabled = !busy)
                }
            }
            Text("Native search actions for four sites. No listings are retrieved automatically; no key, model or computer is required.")
            TextButton(onClick = { open = true }, enabled = !busy) { Text("Open Marketplace") }
        }
        if (open) {
            dev.chungjungsoo.gptmobile.presentation.common.FadingDialog(
                onDismissRequest = { open = false },
                fullScreenScene = true,
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                dev.chungjungsoo.gptmobile.presentation.ui.universalmarketplace.UniversalMarketplaceScreen(onBack = { open = false })
            }
        }
        return
    }
    var endpoint by remember(entry.id, installation.endpoint) { mutableStateOf(installation.endpoint) }
    var disabledOperations by remember(entry.id, installation.disabledOperations) { mutableStateOf(installation.disabledOperations) }
    var osmEndpoints by remember(entry.id, installation.endpoints) { mutableStateOf(installation.endpoints) }
    var endpointConfigs by remember(entry.id, installation.endpointConfigs, installation.endpoints) {
        mutableStateOf((installation.endpoints.mapValues { NativeEndpointConfig(it.value) } + installation.endpointConfigs))
    }
    var endpointSecrets by remember(entry.id, installation.endpointConfigs) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var key by remember(entry.id, installation.credentialRef) { mutableStateOf("") }
    var clearKey by remember(entry.id, installation.credentialRef) { mutableStateOf(false) }
    var results by remember(entry.id, installation.maxResults) { mutableStateOf(installation.maxResults.toString()) }
    var allowance by remember(entry.id, installation.dailyLimit) { mutableStateOf(installation.dailyLimit.toString()) }
    val needsKey = NativeMarketplaceCatalog.requiresKey(entry)
    val needsEndpoint = NativeMarketplaceCatalog.requiresEndpoint(entry)
    val isOsm = entry.provider == "openstreetmap"
    val enabledOperations = setOf("geocode", "restrooms") - disabledOperations
    val validEndpoint = if (isOsm) {
        enabledOperations.isNotEmpty() &&
            enabledOperations.all { operation ->
                val config = endpointConfigs[operation] ?: NativeEndpointConfig("")
                val saved = installation.endpointConfig(operation)
                NativeMarketplaceCatalog.validEndpoint(config.url, config.allowCustomHttpsPort) &&
                    (
                        config.authType == EndpointAuthType.NONE ||
                            NativeMarketplaceCatalog.validKey(endpointSecrets[operation].orEmpty()) ||
                            (config.authType == saved.authType && config.url.trim() == saved.url && saved.secretRef != null)
                        )
            }
    } else {
        !needsEndpoint || NativeMarketplaceCatalog.validEndpoint(endpoint)
    }
    val validKey = !needsKey || NativeMarketplaceCatalog.validKey(key) || (key.isBlank() && installation.credentialRef != null && !clearKey)
    val validLimits = results.toIntOrNull() in 1..10 && allowance.toIntOrNull() in 1..1000
    val red = MaterialTheme.colorScheme.error
    val fieldColors = OutlinedTextFieldDefaults.colors(errorBorderColor = red, errorLabelColor = red, errorSupportingTextColor = red, errorCursorColor = red)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showEnableSwitch) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (installation.enabled) "Enabled" else "Disabled", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(checked = installation.enabled, enabled = !busy && (installation.enabled || installation.ready(entry)), onCheckedChange = { viewModel.setEnabled(entry, it) })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Connection & usage", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon(
                title = "Plugin availability",
                description = "Save required settings before enabling. Both enable controls update the same registration and service gates. Each AI profile keeps its own permissions. A saved API key does not prove provider access; provider failures and cooldowns are returned when a tool runs."
            )
        }
        val usedToday = if (installation.usageDay == java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()) installation.usageCount else 0
        Text("$usedToday / ${installation.dailyLimit} requests today · resets at 00:00 UTC", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (needsKey) {
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it
                    clearKey = false
                },
                label = { Text(if (installation.credentialRef != null) "API key · saved securely" else "API key required") },
                placeholder = { Text(if (installation.credentialRef != null) "Leave blank to keep the saved key" else entry.credentialVariable) },
                supportingText = { Text(if (validKey) "Used only for this provider; stored in the encrypted vault." else "Required before enabling. Enter your provider API key.") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                isError = !validKey, colors = fieldColors
            )
            if (installation.credentialRef != null) {
                TextButton(enabled = !busy, onClick = {
                    clearKey = true
                    key = ""
                }) { Text("Remove saved API key") }
            }
        } else {
            Text("No API key required", style = MaterialTheme.typography.labelMedium)
        }
        if (isOsm) {
            Text("Geocoding and restroom search are included in this install. Configure at least one capability to enable the service.", style = MaterialTheme.typography.bodySmall)
            listOf("geocode" to "Geocoding · Nominatim", "restrooms" to "Restroom Search · Overpass").forEach { (operation, label) ->
                val operationEnabled = operation !in disabledOperations
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(checked = operationEnabled, enabled = !busy, onCheckedChange = { checked ->
                        disabledOperations = if (checked) disabledOperations - operation else disabledOperations + operation
                    })
                }
                val config = endpointConfigs[operation] ?: NativeEndpointConfig("")
                val value = config.url
                val endpointReady = NativeMarketplaceCatalog.validEndpoint(value, config.allowCustomHttpsPort)
                val savedEndpoint = installation.endpointConfig(operation)
                val credentialReady = config.authType == EndpointAuthType.NONE ||
                    NativeMarketplaceCatalog.validKey(endpointSecrets[operation].orEmpty()) ||
                    (config.authType == savedEndpoint.authType && config.url.trim() == savedEndpoint.url && savedEndpoint.secretRef != null)
                OutlinedTextField(
                    value = value, onValueChange = { endpointConfigs = endpointConfigs + (operation to config.copy(url = it)) },
                    label = { Text("$label endpoint") },
                    supportingText = { Text(if (endpointReady) "Endpoint configured" else "Add a managed/self-hosted HTTPS endpoint to use this capability.") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                    isError = operationEnabled && !endpointReady, colors = fieldColors
                )
                TextButton(enabled = !busy, onClick = {
                    val next = EndpointAuthType.entries[(config.authType.ordinal + 1) % EndpointAuthType.entries.size]
                    endpointConfigs = endpointConfigs + (operation to config.copy(authType = next))
                    endpointSecrets = endpointSecrets - operation
                }) { Text("Authentication: ${config.authType.name.replace('_', ' ')} · change") }
                if (config.authType != EndpointAuthType.NONE) {
                    OutlinedTextField(
                        value = endpointSecrets[operation].orEmpty(),
                        onValueChange = { endpointSecrets = endpointSecrets + (operation to it) },
                        label = { Text("Endpoint credential") },
                        placeholder = { Text("Leave blank to retain the credential for this endpoint") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow custom HTTPS port", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = config.allowCustomHttpsPort, enabled = !busy, onCheckedChange = {
                        endpointConfigs = endpointConfigs + (operation to config.copy(allowCustomHttpsPort = it))
                    })
                }
                Text(
                    if (!operationEnabled) {
                        "$label: Disabled"
                    } else if (endpointReady && credentialReady) {
                        "$label: Ready"
                    } else {
                        "$label: Not configured"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (needsEndpoint) {
            OutlinedTextField(
                value = endpoint, onValueChange = { endpoint = it },
                label = { Text("Managed / self-hosted HTTPS endpoint required") },
                supportingText = { Text("Enter the provider's search/interpreter URL. Shared public OSM servers are not an app backend.") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                isError = !validEndpoint, colors = fieldColors
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                results,
                { results = it.take(2) },
                label = { Text("Results · 1–10") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                isError = results.toIntOrNull() !in 1..10
            )
            OutlinedTextField(
                allowance,
                { allowance = it.take(4) },
                label = { Text("Requests/day · 1–1000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                isError = allowance.toIntOrNull() !in 1..1000
            )
        }
        Text("One request per call; no automatic retries. Failed attempts count. Provider limits and account charges also apply.", style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy && validEndpoint && (validKey || clearKey) && validLimits, onClick = {
            viewModel.configure(entry, endpoint, key, results.toInt(), allowance.toInt(), clearKey, endpoints = osmEndpoints, disabledOperations = disabledOperations, endpointConfigs = endpointConfigs, endpointSecrets = endpointSecrets) {
                key = ""
                endpointSecrets = emptyMap()
                clearKey = false
            }
        }) { Text(if (clearKey) "Remove key & disable" else "Save settings") }
    }
}

@Composable
fun InstalledNativePluginsPanel(search: String = "", viewModel: MarketplaceViewModel = hiltViewModel()) {
    val installed by viewModel.installations.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    installed.forEach { (id, installation) ->
        val entry = GitHubMarketplaceCatalog.find(id) ?: return@forEach
        if (search.isNotBlank() && !"${entry.preset.name} ${entry.preset.description}".contains(search, true)) return@forEach
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(entry.preset.name, style = MaterialTheme.typography.titleMedium)
                NativePluginSettings(entry, installation, viewModel, id in state.removingIds || id in state.changingIds)
                state.errors[id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(enabled = id !in state.removingIds, onClick = { viewModel.requestUninstall(entry) }) { Text("Uninstall") }
            }
        }
    }
    if (state.registryNeedsRepair) TextButton(onClick = viewModel::repairRegistry) { Text("Repair plugin registry") }
    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    NativePluginUninstallDialog(viewModel)
}

@Composable
fun NativePluginUninstallDialog(viewModel: MarketplaceViewModel) {
    val uninstalling by viewModel.uninstallRequest.collectAsStateWithLifecycle()
    uninstalling?.let { entry ->
        var keepSavedData by remember(entry.id) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { viewModel.requestUninstall(null) },
            title = { Text("Uninstall ${entry.preset.name}?") },
            text = {
                Column {
                    Text("Disables the plugin and removes its installation, settings and saved credentials.")
                    if (entry.provider == "universal-marketplace") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(keepSavedData, { keepSavedData = it })
                            Text("Keep my saved searches and bookmarks")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (keepSavedData) viewModel.remove(entry) else viewModel.removeWithSavedData(entry)
                    viewModel.requestUninstall(null)
                }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { viewModel.requestUninstall(null) }) { Text("Cancel") } }
        )
    }
}
