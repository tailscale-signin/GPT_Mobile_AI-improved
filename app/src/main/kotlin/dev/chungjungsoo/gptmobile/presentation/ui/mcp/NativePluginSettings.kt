package dev.chungjungsoo.gptmobile.presentation.ui.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplacePackage
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.marketplace.NativePluginInstallation

/** Required fields are red immediately, including their focused outline. Secrets are never saveable state. */
@Composable
fun NativePluginSettings(
    entry: GitHubMarketplacePackage,
    installation: NativePluginInstallation,
    viewModel: MarketplaceViewModel,
    busy: Boolean = false
) {
    var endpoint by remember(entry.id, installation.endpoint) { mutableStateOf(installation.endpoint) }
    var key by remember(entry.id, installation.credentialRef) { mutableStateOf("") }
    var clearKey by remember(entry.id, installation.credentialRef) { mutableStateOf(false) }
    var results by remember(entry.id, installation.maxResults) { mutableStateOf(installation.maxResults.toString()) }
    var allowance by remember(entry.id, installation.dailyLimit) { mutableStateOf(installation.dailyLimit.toString()) }
    val needsKey = NativeMarketplaceCatalog.requiresKey(entry)
    val needsEndpoint = NativeMarketplaceCatalog.requiresEndpoint(entry)
    val validEndpoint = !needsEndpoint || NativeMarketplaceCatalog.validEndpoint(endpoint)
    val validKey = !needsKey || NativeMarketplaceCatalog.validKey(key) || (key.isBlank() && installation.credentialRef != null && !clearKey)
    val validLimits = results.toIntOrNull() in 1..10 && allowance.toIntOrNull() in 1..1000
    val red = Color(0xFFFF5252)
    val fieldColors = OutlinedTextFieldDefaults.colors(errorBorderColor = red, errorLabelColor = red, errorSupportingTextColor = red, errorCursorColor = red)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (installation.enabled) "Enabled" else "Disabled", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = installation.enabled, enabled = !busy && (installation.enabled || installation.ready(entry)), onCheckedChange = { viewModel.setEnabled(entry, it) })
        }
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
            viewModel.configure(entry, endpoint, key, results.toInt(), allowance.toInt(), clearKey) {
                key = ""
                clearKey = false
            }
        }) { Text(if (clearKey) "Remove key & disable" else "Save settings") }
    }
}

@Composable
fun InstalledNativePluginsPanel(search: String = "", viewModel: MarketplaceViewModel = hiltViewModel()) {
    val installed by viewModel.installations.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var uninstalling by remember { mutableStateOf<GitHubMarketplacePackage?>(null) }
    installed.forEach { (id, installation) ->
        val entry = GitHubMarketplaceCatalog.find(id) ?: return@forEach
        if (search.isNotBlank() && !"${entry.preset.name} ${entry.preset.description}".contains(search, true)) return@forEach
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(entry.preset.name, style = MaterialTheme.typography.titleMedium)
                NativePluginSettings(entry, installation, viewModel, id in state.removingIds)
                state.errors[id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(enabled = id !in state.removingIds, onClick = { uninstalling = entry }) { Text("Uninstall") }
            }
        }
    }
    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    uninstalling?.let { entry ->
        AlertDialog(
            onDismissRequest = { uninstalling = null },
            title = { Text("Uninstall ${entry.preset.name}?") },
            text = { Text("Disables the plugin and removes its files, settings, credentials and associated MCP connection and tool assignments.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(entry)
                    uninstalling = null
                }) { Text("Uninstall") }
            },
            dismissButton = { TextButton(onClick = { uninstalling = null }) { Text("Cancel") } }
        )
    }
}
