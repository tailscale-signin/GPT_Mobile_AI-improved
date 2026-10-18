package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.model.AppFeature
import dev.chungjungsoo.gptmobile.presentation.common.LocalSettingSearchFocus
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.designsystem.AppShapes
import dev.chungjungsoo.gptmobile.presentation.designsystem.AppSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    viewModel: SettingViewModelV2,
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocus: String = ""
) {
    val settings by viewModel.featureSettings.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val groups = remember { AppFeature.entries.filterNot { it == AppFeature.OPENROUTER_BATCH }.groupBy(SettingsSearchRegistry::group) }
    val categories = remember { listOf("Privacy & Storage", "Performance", "New Chat") + groups.keys }
    var category by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Performance") }
    LaunchedEffect(initialFocus) {
        category = when {
            initialFocus.startsWith("privacy") -> "Privacy & Storage"
            initialFocus == "smart-readiness" -> "Performance"
            else -> AppFeature.entries.firstOrNull { it.name == initialFocus }?.let(SettingsSearchRegistry::group) ?: category
        }
    }
    LaunchedEffect(category, initialFocus) {
        val index = groups[category].orEmpty().indexOfFirst { it.name == initialFocus }.coerceAtLeast(0)
        listState.scrollToItem(index)
    }
    CompositionLocalProvider(LocalSettingSearchFocus provides initialFocus.substringAfter(':', "")) {
        Scaffold(modifier = modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_advanced)) }, navigationIcon = {
                IconButton(onClick = onNavigationClick) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.go_back)) }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.padding(horizontal = AppSpacing.large, vertical = AppSpacing.small)) {
                    Text("Fine-tune your experience", style = MaterialTheme.typography.titleLarge)
                    Text("Choose a category. Changes save automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingsTabs(categories, categories.indexOf(category).coerceAtLeast(0)) { category = categories[it] }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(AppSpacing.large),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
                ) {
                    when (category) {
                        "Privacy & Storage" -> item(key = "privacy") { SettingsPanel("Privacy & Storage") { PrivacyStoragePanel(embedded = true) } }
                        "Performance" -> item(key = "smart-readiness") { SmartReadinessPanel(settings.readiness, viewModel) }
                        "New Chat" -> item(key = "price-actions") { SettingsPanel("Pricing & Discovery") { NewChatInsightsSettingsPanel() } }
                        else -> groups[category].orEmpty().forEach { feature ->
                            item(key = feature.name) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().testTag("setting_${feature.name}"),
                                    shape = AppShapes.card,
                                    colors = CardDefaults.cardColors(containerColor = if (initialFocus == feature.name) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                                ) {
                                    Row(Modifier.padding(AppSpacing.large), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                                        Column(Modifier.weight(1f)) {
                                            Text(feature.title, style = MaterialTheme.typography.bodyLarge)
                                            Text(feature.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(
                                            checked = settings.withFeature(feature, true) == settings,
                                            onCheckedChange = { viewModel.updateFeature(feature, it) },
                                            enabled = feature != AppFeature.PARALLEL_SEARCH,
                                            modifier = Modifier.semantics { contentDescription = feature.title }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartReadinessPanel(settings: dev.chungjungsoo.gptmobile.data.readiness.ReadinessSettings, viewModel: SettingViewModelV2) {
    var expanded by remember { mutableStateOf(false) }
    SettingsPanel("Smart Readiness") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Prepare when typing", Modifier.weight(1f))
            SettingsHelpIcon("Prepares selected provider connections, tool metadata and memory infrastructure before Send. Draft text is never sent. Optional preparation cannot hold up your message. Metered-network preference is respected.", title = "Smart Readiness")
            Switch(settings.enabled, { enabled -> viewModel.updateReadiness { it.copy(enabled = enabled) } })
        }
        androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide options" else "Options") }
        if (expanded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Predict memory while typing", Modifier.weight(1f))
                SettingsHelpIcon("Read-only, on-device lexical preview. Drafts are not sent to a model or saved by readiness. Existing memory privacy still applies.", title = "Memory preview")
                Switch(settings.predictMemory, { enabled -> viewModel.updateReadiness { it.copy(predictMemory = enabled) } }, enabled = settings.enabled)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Warm on metered networks", Modifier.weight(1f))
                Switch(settings.warmMeteredNetworks, { enabled -> viewModel.updateReadiness { it.copy(warmMeteredNetworks = enabled) } }, enabled = settings.enabled)
            }
            dev.chungjungsoo.gptmobile.data.readiness.PreparationStrategy.entries.forEach { strategy ->
                androidx.compose.material3.FilterChip(settings.strategy == strategy, { viewModel.updateReadiness { it.copy(strategy = strategy) } }, label = { Text(if (strategy == dev.chungjungsoo.gptmobile.data.readiness.PreparationStrategy.BALANCED) "Balanced" else "Minimal resources") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Discover remote tools when needed", Modifier.weight(1f))
                Switch(settings.lazyCatalogues, { enabled -> viewModel.updateReadiness { it.copy(lazyCatalogues = enabled) } })
            }
            androidx.compose.material3.TextButton(onClick = { viewModel.updateReadiness { it.copy(cacheGeneration = it.cacheGeneration + 1) } }) { Text("Clear readiness cache") }
        }
    }
}
