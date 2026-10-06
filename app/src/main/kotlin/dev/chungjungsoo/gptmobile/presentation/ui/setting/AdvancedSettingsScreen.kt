package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.AppFeature
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    viewModel: SettingViewModelV2,
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.featureSettings.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Advanced Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigationClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
                    title = "Privacy & Storage",
                    subtitle = "Device unlock, screenshot protection, local storage size, and preview cleanup.",
                    icon = Icons.Rounded.Storage
                ) {
                    PrivacyStoragePanel(embedded = true)
                }
            }
            item {
                AdvancedGroupCard("Reading & Motion", "", Icons.Rounded.Tune) {
                    FeatureSwitch(AppFeature.SMOOTH_STREAMING, settings.smoothStreaming, Icons.Rounded.Tune, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.CENTER_UNREAD, settings.centerUnread, Icons.Rounded.Tune, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.RESPONSE_ANIMATION, settings.responseAnimation, Icons.Rounded.Tune, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.EDGE_FADES, settings.edgeFades, Icons.Rounded.Tune, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.MESSAGE_TIMESTAMPS, settings.messageTimestamps, Icons.Rounded.Tune, viewModel::updateFeature)
                }
            }
            item {
                AdvancedGroupCard("Research & Efficiency", "", Icons.Rounded.Tune) {
                    FeatureSwitch(AppFeature.QUEUED_FOLLOW_UPS, settings.queuedFollowUps, Icons.Rounded.Tune, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.PARALLEL_SEARCH, settings.parallelSearch, Icons.Rounded.Tune, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.DEDUPLICATE_SEARCH, settings.deduplicateSearch, Icons.Rounded.Tune, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.GITHUB_CONDITIONAL_READS, settings.githubConditionalReads, Icons.Rounded.Tune, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.GITHUB_BLOB_CACHE, settings.githubBlobCache, Icons.Rounded.Tune, viewModel::updateFeature)
                }
            }
            item {
                AdvancedGroupCard(
                    title = "Background & Notifications",
                    subtitle = "Control work that can continue outside the foreground.",
                    icon = Icons.Rounded.Schedule
                ) {
                    FeatureSwitch(
                        feature = AppFeature.BACKGROUND_GENERATION,
                        enabled = settings.backgroundGeneration,
                        icon = Icons.Rounded.Schedule,
                        onChange = viewModel::updateFeature
                    )
                    FeatureSwitch(
                        feature = AppFeature.RESPONSE_NOTIFICATIONS,
                        enabled = settings.responseNotifications,
                        icon = Icons.Rounded.Notifications,
                        onChange = viewModel::updateFeature
                    )
                }
            }
            item {
                AdvancedGroupCard(
                    title = "Conversation Intelligence",
                    subtitle = "Automatic organization and response assistance.",
                    icon = Icons.Rounded.AutoAwesome
                ) {
                    FeatureSwitch(AppFeature.AUTOMATIC_TITLES, settings.automaticConversationTitles, Icons.Rounded.AutoAwesome, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.ARCHIVE_OLDER_REPLIES, settings.archiveOlderAssistantReplies, Icons.Rounded.Storage, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.SHOW_REASONING, settings.showReasoning, Icons.Rounded.AutoAwesome, viewModel::updateFeature)
                    FeatureSwitch(AppFeature.SMART_SUGGESTIONS, settings.smartSuggestions, Icons.Rounded.SettingsSuggest, viewModel::updateFeature)
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
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (subtitle.isNotBlank()) SettingsHelpIcon(subtitle)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Collapse $title" else "Expand $title", tint = MaterialTheme.colorScheme.primary)
            }
            AnimatedVisibility(expanded) { Column { content() } }
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
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(feature.title.split(" ").joinToString(" ") { word -> word.replaceFirstChar { char -> char.uppercase() } }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            SettingsHelpIcon(feature.description)
        }
        Switch(checked = enabled, onCheckedChange = { onChange(feature, it) })
    }
}
