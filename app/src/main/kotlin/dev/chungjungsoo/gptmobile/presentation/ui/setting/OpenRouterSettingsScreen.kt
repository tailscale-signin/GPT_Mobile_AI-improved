package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.presentation.viewmodel.OpenRouterSettingsViewModel
import java.text.NumberFormat
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenRouterSettingsScreen(
    viewModel: OpenRouterSettingsViewModel,
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.uiState.collectAsStateWithLifecycle()
    val providerStatus by viewModel.providerStatus.collectAsStateWithLifecycle()
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OpenRouter Provider")
                        Text(
                            "Connection, credits, routing, cache and batch services",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigationClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                OpenRouterProviderOverview(
                    baseUrl = settings.baseUrl,
                    credits = providerStatus.credits,
                    loading = providerStatus.isLoadingCredits,
                    error = providerStatus.creditsError,
                    onRefresh = viewModel::refreshCredits
                )
            }

            item {
                ProviderSettingsCard(
                    title = "Connection",
                    subtitle = "Shared provider connection used by OpenRouter AI profiles.",
                    icon = Icons.Default.Link
                ) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            viewModel.updateSettings(settings.copy(apiKey = it))
                        },
                        label = { Text("API key") },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = {
                            baseUrl = it
                            viewModel.updateSettings(settings.copy(baseUrl = it))
                        },
                        label = { Text("API HTTP base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text(
                        "Model choice, sampling, system prompt and tool permissions remain inside each child AI profile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                ProviderSettingsCard(
                    title = "Prompt cache & sticky routing",
                    subtitle = "Keep multi-turn chats on a consistent provider so supported prompt caches stay warm.",
                    icon = Icons.Default.Route
                ) {
                    ProviderToggle(
                        title = "Sticky session routing",
                        description = "Send a stable session_id for each chat. OpenRouter can reuse the same provider and concrete routed model across turns.",
                        checked = settings.stickySessionRoutingEnabled,
                        onCheckedChange = {
                            viewModel.updateSettings(settings.copy(stickySessionRoutingEnabled = it))
                        }
                    )
                    HorizontalDivider()
                    ProviderToggle(
                        title = "Identical response caching",
                        description = "Send X-OpenRouter-Cache for identical requests. This is separate from provider prompt caching and is off by default.",
                        checked = settings.responseCachingEnabled,
                        onCheckedChange = {
                            viewModel.updateSettings(settings.copy(responseCachingEnabled = it))
                        }
                    )
                }
            }

            item {
                ProviderSettingsCard(
                    title = "Batch API",
                    subtitle = "Queue asynchronous workloads through OpenRouter's production /api/v1/batches endpoint.",
                    icon = Icons.Default.CloudQueue
                ) {
                    ProviderToggle(
                        title = "Batch processing",
                        description = "Use the background queue for workloads that do not require an immediate interactive response.",
                        checked = settings.batchingEnabled,
                        onCheckedChange = {
                            viewModel.updateSettings(settings.copy(batchingEnabled = it))
                        }
                    )
                    if (settings.batchingEnabled) {
                        HorizontalDivider()
                        SettingSlider(
                            label = "Batch size",
                            valueLabel = settings.batchSize.toString(),
                            value = settings.batchSize.toFloat(),
                            range = 1f..50f,
                            steps = 48
                        ) { value ->
                            viewModel.updateSettings(settings.copy(batchSize = value.roundToInt()))
                        }
                        SettingSlider(
                            label = "Flush timeout",
                            valueLabel = "${settings.flushTimeoutMs} ms",
                            value = settings.flushTimeoutMs.toFloat(),
                            range = 1_000f..30_000f,
                            steps = 28
                        ) { value ->
                            viewModel.updateSettings(settings.copy(flushTimeoutMs = value.toLong()))
                        }
                        SettingSlider(
                            label = "Max retries",
                            valueLabel = settings.maxRetries.toString(),
                            value = settings.maxRetries.toFloat(),
                            range = 0f..10f,
                            steps = 9
                        ) { value ->
                            viewModel.updateSettings(settings.copy(maxRetries = value.roundToInt()))
                        }
                    }
                }
            }

            item {
                ProviderSettingsCard(
                    title = "Local queue cache",
                    subtitle = "App-side cache for repeated batch/queue work. This is not OpenRouter prompt caching.",
                    icon = Icons.Default.Cached
                ) {
                    ProviderToggle(
                        title = "Local identical-request cache",
                        description = "Reuse locally stored queue results when the request hash matches within the configured TTL.",
                        checked = settings.cacheEnabled,
                        onCheckedChange = {
                            viewModel.updateSettings(settings.copy(cacheEnabled = it))
                        }
                    )
                    if (settings.cacheEnabled) {
                        HorizontalDivider()
                        SettingSlider(
                            label = "Local cache TTL",
                            valueLabel = "${settings.cacheTtlSeconds}s",
                            value = settings.cacheTtlSeconds.toFloat(),
                            range = 60f..3_600f,
                            steps = 58
                        ) { value ->
                            viewModel.updateSettings(settings.copy(cacheTtlSeconds = value.roundToInt()))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenRouterProviderOverview(
    baseUrl: String,
    credits: dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCreditsData?,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit
) {
    val currency = remember { NumberFormat.getCurrencyInstance() }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Savings, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Provider overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        baseUrl.ifBlank { "https://openrouter.ai/api/v1" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                IconButton(onClick = onRefresh, enabled = !loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh credits")
                }
            }

            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                credits != null -> {
                    val progress = credits.usagePercentage / 100f
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(
                                currency.format(credits.remaining),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text("credits remaining", style = MaterialTheme.typography.labelSmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(currency.format(credits.totalUsage), fontWeight = FontWeight.SemiBold)
                            Text("used of ${currency.format(credits.totalCredits)}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${credits.usagePercentage.roundToInt()}% of available credits used",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                !error.isNullOrBlank() -> {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    Text(
                        "Add a compatible key and refresh to load credit usage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderSettingsCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun ProviderToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}
