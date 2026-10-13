package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterModelItem
import dev.chungjungsoo.gptmobile.data.openrouter.account.perMillion
import dev.chungjungsoo.gptmobile.data.openrouter.account.usd
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun OpenRouterModelPickerDialog(
    currentModel: String,
    connectionUid: String? = null,
    catalogViewModel: dev.chungjungsoo.gptmobile.presentation.viewmodel.OpenRouterCatalogViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(),
    onDismiss: () -> Unit,
    onModelSelected: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember(connectionUid) { mutableStateOf(false) }
    var errorMessage by remember(connectionUid) { mutableStateOf<String?>(null) }
    var models by remember(connectionUid) { mutableStateOf<List<OpenRouterModelItem>>(emptyList()) }
    var selectedModelId by remember(connectionUid, currentModel) { mutableStateOf(currentModel) }
    var loadJob by remember(connectionUid) { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val loadError = stringResource(R.string.openrouter_load_error)

    fun loadModels(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        isLoading = true
        errorMessage = null
        loadJob = coroutineScope.launch {
            val result = catalogViewModel.load(connectionUid, forceRefresh)
            isLoading = false
            result.onSuccess {
                models = it
            }.onFailure {
                errorMessage = loadError
            }
        }
    }

    LaunchedEffect(connectionUid) {
        loadModels(forceRefresh = false)
    }
    DisposableEffect(connectionUid) {
        onDispose { loadJob?.cancel() }
    }

    val filteredModels = remember(searchQuery, models) {
        val query = searchQuery.trim().lowercase()
        if (query.isEmpty()) {
            models
        } else {
            models.filter { model ->
                model.id.lowercase().contains(query) ||
                    (model.name?.lowercase()?.contains(query) == true) ||
                    (model.description?.lowercase()?.contains(query) == true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.openrouter_select_model),
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = { loadModels(forceRefresh = true) }, enabled = !isLoading) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.fetch_models)
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.search_models)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.search)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.clear)
                                )
                            }
                        }
                    },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    errorMessage != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = errorMessage ?: stringResource(R.string.openrouter_load_error),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(onClick = { loadModels(forceRefresh = true) }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                    filteredModels.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.openrouter_no_models_found),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 350.dp)
                        ) {
                            items(filteredModels, key = { it.id }) { item ->
                                val isSelected = item.id == selectedModelId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedModelId = item.id }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name ?: item.id,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = item.id,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (item.contextLength != null) {
                                            Text(
                                                text = stringResource(R.string.openrouter_context_length, item.contextLength),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        val priceColor = accessiblePriceColor(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        Text(
                                            text = stringResource(R.string.orw_input) + ": " + usd(perMillion(item.pricing?.prompt)) + " / 1M",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = priceColor
                                        )
                                        Text(
                                            text = stringResource(R.string.orw_output) + ": " + usd(perMillion(item.pricing?.completion)) + " / 1M",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = priceColor
                                        )
                                        Text(stringResource(R.string.orw_catalog_price), style = MaterialTheme.typography.labelSmall)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            listOf(
                                                "tools" to R.string.orw_capability_tools,
                                                "reasoning" to R.string.orw_capability_reasoning,
                                                "structured_outputs" to R.string.orw_capability_structured
                                            ).filter { (parameter, _) -> item.supportedParameters?.contains(parameter) == true }.forEach { (_, label) ->
                                                Text(
                                                    stringResource(label),
                                                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 4.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                                )
                                            }
                                        }
                                    }
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedModelId.isNotBlank(),
                onClick = {
                    onModelSelected(selectedModelId.trim())
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
