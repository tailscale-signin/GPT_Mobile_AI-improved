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
import androidx.compose.runtime.remember
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
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.designsystem.AppShapes
import dev.chungjungsoo.gptmobile.presentation.designsystem.AppSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvancedSettingsScreen(
    viewModel: SettingViewModelV2,
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocus: String = "",
    title: String = "All advanced controls",
    groupFilter: Set<String>? = null,
    showPrivacy: Boolean = true,
    featureHomeFilter: FeatureHome? = null,
    onAllControls: (() -> Unit)? = null
) {
    val settings by viewModel.featureSettings.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val groups = remember(groupFilter, featureHomeFilter) { AppFeature.entries.filter { featureHomeFilter == null || featureHome(it) == featureHomeFilter }.groupBy(SettingsSearchRegistry::group).filterKeys { groupFilter == null || it in groupFilter } }
    val rows = remember(groups) { groups.values.flatMap { listOf<AppFeature?>(null) + it } }
    LaunchedEffect(initialFocus) {
        val target = if (initialFocus.startsWith("privacy")) 0 else rows.indexOfFirst { it?.name == initialFocus }.takeIf { it >= 0 }?.plus((if (showPrivacy) 1 else 0) + (if (onAllControls != null) 1 else 0))
        if (target != null) listState.scrollToItem(target)
    }
    CompositionLocalProvider(LocalSettingSearchFocus provides initialFocus.substringAfter(':', "")) {
        Scaffold(modifier = modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text(title) }, navigationIcon = {
                IconButton(onClick = onNavigationClick) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.go_back)) }
            })
        }) { padding ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(AppSpacing.large),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.small)
            ) {
                if (showPrivacy) {
                    item(key = "privacy") {
                        SettingsPanel("Privacy & Storage") { PrivacyStoragePanel(embedded = true) }
                    }
                }
                onAllControls?.let { action -> item { androidx.compose.material3.TextButton(onClick = action) { Text("All advanced controls") } } }
                groups.forEach { (group, features) ->
                    item(key = "group.$group") { Text(group, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = AppSpacing.medium)) }
                    features.forEach { feature ->
                        item(key = feature.name) {
                            Card(
                                modifier = Modifier.fillMaxWidth().testTag("setting_${feature.name}"),
                                shape = AppShapes.card,
                                colors = CardDefaults.cardColors(containerColor = if (initialFocus == feature.name) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                            ) {
                                Row(Modifier.padding(AppSpacing.large), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(feature.title, style = MaterialTheme.typography.bodyLarge)
                                        Text(feature.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (feature == AppFeature.PARALLEL_SEARCH) Text("Always on for the current execution policy", style = MaterialTheme.typography.labelMedium)
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
