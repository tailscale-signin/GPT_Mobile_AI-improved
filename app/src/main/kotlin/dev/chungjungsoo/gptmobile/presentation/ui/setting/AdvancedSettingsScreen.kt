package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.AppFeature
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    viewModel: SettingViewModelV2,
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.featureSettings.collectAsState()
    val backend by viewModel.localRuntimeBackend.collectAsState()
    val runtime by viewModel.localRuntimeState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Advanced Settings")
                        Text(
                            "Background behavior, automation and experimental integrations",
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
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                AdvancedGroupCard(
                    title = "Background & notifications",
                    subtitle = "Control work that can continue outside the foreground.",
                    icon = Icons.Default.Schedule
                ) {
                    FeatureSwitch(
                        feature = AppFeature.BACKGROUND_GENERATION,
                        enabled = settings.backgroundGeneration,
                        icon = Icons.Default.Schedule,
                        onChange = viewModel::updateFeature
                    )
                    FeatureSwitch(
                        feature = AppFeature.RESPONSE_NOTIFICATIONS,
                        enabled = settings.responseNotifications,
                        icon = Icons.Default.Notifications,
                        onChange = viewModel::updateFeature
                    )
                }
            }
            item {
                AdvancedGroupCard(
                    title = "Conversation intelligence",
                    subtitle = "Automatic organization and response assistance.",
                    icon = Icons.Default.AutoAwesome
                ) {
                    FeatureSwitch(AppFeature.AUTOMATIC_TITLES, settings.automaticConversationTitles, Icons.Default.AutoAwesome, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.ARCHIVE_OLDER_REPLIES, settings.archiveOlderAssistantReplies, Icons.Default.Storage, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.SMART_SUGGESTIONS, settings.smartSuggestions, Icons.Default.SettingsSuggest, viewModel::updateFeature)
                }
            }
            item {
                AdvancedGroupCard(
                    title = "Tools & discovery",
                    subtitle = "Network-connected tools and provider metadata.",
                    icon = Icons.Default.Cloud
                ) {
                    FeatureSwitch(AppFeature.REMOTE_MCP, settings.remoteMcpConnections, Icons.Default.Cloud, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.SHARED_TOOL_CALLS, settings.sharedReadOnlyToolCalls, Icons.Default.Tune, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.DEVICE_LOCATION, settings.deviceLocationTool, Icons.Default.LocationOn, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.MODEL_DISCOVERY, settings.providerModelDiscovery, Icons.Default.Tune, viewModel::updateFeature)
                }
            }
            item {
                AdvancedGroupCard(
                    title = "Runtime & diagnostics",
                    subtitle = "Fallback behavior and optional telemetry collection.",
                    icon = Icons.Default.Memory
                ) {
                    Text("Local inference engine", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LocalRuntimeBackend.entries.forEach { option ->
                            FilterChip(
                                selected = backend == option,
                                onClick = { viewModel.updateLocalRuntimeBackend(option) },
                                label = { Text(option.displayName) }
                            )
                        }
                    }
                    Text(
                        runtime.engineSpec?.let { "Active: ${runtime.backend?.displayName} · ${it.accelerator.uppercase()} · ${it.maxTokens} context tokens" }
                            ?: "Engine idle. Changes apply to the next local response.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                    runtime.fallbackReason?.let { Text("Fallback: $it", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
                    FeatureSwitch(AppFeature.QNN_AUTO_FALLBACK, settings.qnnAutomaticFallback, Icons.Default.Memory, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.DIAGNOSTICS, settings.diagnosticsCollection, Icons.Default.QueryStats, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.OPENROUTER_BATCH, settings.openRouterBatchProcessing, Icons.Default.Cloud, viewModel::updateFeature)
                }
            }
        }
    }
}

@Composable
private fun AdvancedGroupCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
private fun FeatureSwitch(
    feature: AppFeature,
    enabled: Boolean,
    icon: ImageVector,
    onChange: (AppFeature, Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(feature.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(feature.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = enabled, onCheckedChange = { onChange(feature, it) })
    }
}
