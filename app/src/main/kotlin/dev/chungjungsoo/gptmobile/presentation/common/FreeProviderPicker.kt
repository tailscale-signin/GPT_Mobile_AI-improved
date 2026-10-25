package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeProviderPicker(
    apiUrl: String,
    onProviderSelected: (FreeAiProvider) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    model: String? = null,
    onModelSelected: ((String) -> Unit)? = null,
    token: String? = null,
    onTokenSaved: ((String) -> Unit)? = null
) {
    val selected = FreeAiProvider.fromApiUrl(apiUrl)
    val health by dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.status.collectAsState()
    val now by freeProviderClock()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var tokenDraft by remember(apiUrl, token) { mutableStateOf(token.orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var testStatus by remember(apiUrl, tokenDraft) { mutableStateOf<dev.chungjungsoo.gptmobile.data.network.ApiKeyValidator.ValidationResult?>(null) }
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column {
                    Text(stringResource(R.string.free_ai), style = MaterialTheme.typography.titleLarge)
                    Text(selected?.let { dev.chungjungsoo.gptmobile.data.free.FreeModelCatalog.details(it).authentication } ?: "Free services · access requirements vary", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled && !testing) expanded = it }) {
                OutlinedTextField(
                    value = selected?.displayName.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled,
                    label = { Text(stringResource(R.string.free_ai_provider)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    singleLine = true
                )
                ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                    FreeAiProvider.active.forEach { provider ->
                        if (provider.isDemo) Text("Demo / Experimental", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                        DropdownMenuItem(
                            enabled = provider.isAvailable && !testing,
                            text = {
                                Column {
                                    Text(provider.displayName)
                                    Text(
                                        if (provider.isAvailable) freeProviderAvailability(provider, if (provider == selected) model ?: provider.model else provider.model, health[provider], now, token.takeIf { provider == selected }) ?: dev.chungjungsoo.gptmobile.data.free.FreeModelCatalog.details(provider).limits else provider.unavailableReason.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                onProviderSelected(provider)
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
            if (selected?.unavailableReason != null) {
                androidx.compose.material3.OutlinedButton(enabled = !testing, onClick = { expanded = true }) { Text("Choose another provider") }
            }
            selected?.let { provider ->
                if (provider.acceptsUserToken && token.isNullOrBlank()) Text("Anonymous access is enabled. You can optionally add your own free API token.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                freeProviderAvailability(provider, model ?: provider.model, health[provider], now, token.takeIf { provider == selected })?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                if (provider.acceptsUserToken && onTokenSaved != null) {
                    OutlinedTextField(
                        value = tokenDraft,
                        onValueChange = { tokenDraft = it },
                        label = { Text(if (provider.requiresUserToken) "LLM7 free API token · required" else "LLM7 free API token · optional") },
                        singleLine = true,
                        enabled = enabled && !testing,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Leave blank to use anonymous access. If you add a token, use a free account’s token: LLM7 may bill topped-up accounts even for models eligible for the free allowance.", style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.TextButton(enabled = enabled && !testing, onClick = { uriHandler.openUri("https://dash.llm7.io") }) { Text("Get a free LLM7 token") }
                    androidx.compose.material3.OutlinedButton(enabled = enabled && !testing && tokenDraft != token.orEmpty(), onClick = { onTokenSaved(tokenDraft.trim()) }) { Text(if (tokenDraft.isBlank()) "Remove token" else "Save token") }
                }
                if (provider.isDemo) Text("Demo / Experimental · transport completion does not verify answer quality. Use evaluation prompts in Benchmarks to assess usefulness.", style = MaterialTheme.typography.bodySmall)
                if (model != null && onModelSelected != null) {
                    FreeModelPicker(provider, model, onModelSelected, enabled, token)
                }
                Text(
                    if (provider.isAvailable) dev.chungjungsoo.gptmobile.data.free.FreeModelCatalog.details(provider).privacy else provider.unavailableReason.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (provider.isAvailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
                Text(stringResource(R.string.free_ai_model, model ?: provider.model), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            androidx.compose.material3.TextButton(enabled = enabled && !testing && selected?.isAvailable == true, onClick = {
                val testedUrl = apiUrl
                val testedToken = tokenDraft
                testing = true
                testStatus = null
                scope.launch {
                    try {
                        testStatus = dev.chungjungsoo.gptmobile.data.network.ApiKeyValidator.validate(dev.chungjungsoo.gptmobile.data.model.ClientType.FREE, testedUrl, testedToken)
                    } finally {
                        testing = false
                    }
                }
            }) { Text(if (testing) "Testing provider…" else "Test provider") }
            when (val result = testStatus) {
                is dev.chungjungsoo.gptmobile.data.network.ApiKeyValidator.ValidationResult.Success -> Text(result.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                is dev.chungjungsoo.gptmobile.data.network.ApiKeyValidator.ValidationResult.Error -> Text(result.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else -> Unit
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.free_ai_memory_off), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(stringResource(R.string.free_ai_memory_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            Text(stringResource(R.string.free_ai_public_prompts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FreeModelPicker(provider: FreeAiProvider, selected: String, onSelected: (String) -> Unit, enabled: Boolean, token: String?) {
    val catalog by dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.catalog.collectAsState()
    val now by freeProviderClock()
    var expanded by remember { mutableStateOf(false) }
    val models = remember(provider, catalog) { dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.models(provider) }
    ExposedDropdownMenuBox(expanded, { if (enabled) expanded = it }) {
        OutlinedTextField(selected, {}, readOnly = true, label = { Text("Model") }, modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
        ExposedDropdownMenu(expanded, { expanded = false }) {
            models.forEach { model ->
                val blocked = remember(provider, model.id, token, now) { dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(provider, model.id, token) }
                DropdownMenuItem(enabled = enabled && blocked == null, text = {
                    Column {
                        Text(model.label)
                        blocked?.let { Text(freeProviderAvailability(provider, model.id, null, now, token) ?: it.message, style = MaterialTheme.typography.labelSmall) }
                    }
                }, onClick = {
                    if (dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(provider, model.id, token) == null) onSelected(model.id)
                    expanded = false
                })
            }
        }
    }
}
