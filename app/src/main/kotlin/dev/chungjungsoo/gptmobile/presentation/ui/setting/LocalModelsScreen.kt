package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.catalog.ModelCatalogParser
import dev.chungjungsoo.gptmobile.data.huggingface.HuggingFaceUrls
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.LocalModelDownloadDialogHost
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.LocalModelDownloadStatus
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.LocalModelRequirements
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.rememberLocalModelDownloader

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LocalMarketplaceScreen(
    startInMarketplace: Boolean = false,
    startInDelegation: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: LocalModelsViewModel = hiltViewModel(),
    runtimeViewModel: LocalRuntimeSettingsViewModel = hiltViewModel(),
    onOpenProfile: (String) -> Unit = {},
    onNavigationClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val catalogStatus by viewModel.catalogStatus.collectAsStateWithLifecycle()
    val profiles by runtimeViewModel.profiles.collectAsStateWithLifecycle()
    val runtimeSettings by runtimeViewModel.settings.collectAsStateWithLifecycle()
    val runtimeBusy by runtimeViewModel.busy.collectAsStateWithLifecycle()
    val runtimeStatus by runtimeViewModel.status.collectAsStateWithLifecycle()
    val requestDownload = rememberLocalModelDownloader(viewModel::onDownloadClick)
    val context = LocalContext.current
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var runtime by rememberSaveable { mutableStateOf("All") }
    var processor by rememberSaveable { mutableStateOf("Any") }
    var capability by rememberSaveable { mutableStateOf("Any") }
    var everyModel by rememberSaveable { mutableStateOf(false) }
    var family by rememberSaveable { mutableStateOf<String?>(null) }
    var why by rememberSaveable { mutableStateOf(false) }
    val geniex = dev.chungjungsoo.gptmobile.BuildConfig.GENIEX_ENABLED
    fun support(item: LocalModelListItem) = dev.chungjungsoo.gptmobile.data.catalog.LocalCatalogCompatibilityResolver.resolve(item.entry, geniex, runtimeViewModel.soc, item.status == LocalModelItemStatus.READY)
    fun isGenieX(item: LocalModelListItem) = item.entry.variantLabel.startsWith("GENIEX_") || item.entry.runtimeRequirement.contains("geniex", true) || item.entry.downloadUrl.substringBefore('?').endsWith(".gguf", true)
    val recommended = remember(uiState.allItems, geniex) { dev.chungjungsoo.gptmobile.data.catalog.LocalCatalogRecommendationPolicy.recommend(uiState.allItems.map { it.entry }, geniex, runtimeViewModel.soc, runtimeViewModel.ramGb).map { it.id }.toSet() }
    val visible = uiState.items.filter { item ->
        val compatible = item.status == LocalModelItemStatus.READY ||
            everyModel ||
            (support(item).reasons.isEmpty() && (runtimeViewModel.ramGb == null || item.entry.minRamGb <= runtimeViewModel.ramGb!!))
        compatible &&
            (runtime == "All" || (runtime == "GenieX") == isGenieX(item)) &&
            (processor == "Any" || item.entry.supportedAccelerators.any { it.equals(processor, true) }) &&
            when (capability) {
                "Images" -> item.entry.capabilities.vision
                "Tools" -> item.entry.capabilities.tools
                "Thinking" -> item.entry.capabilities.thinking
                "MTP" -> item.entry.supportsMtp
                else -> true
            }
    }
    val families = visible.groupBy { it.entry.familyId.ifBlank { it.entry.id } }.entries.sortedBy { (_, variants) -> if (variants.any { it.entry.id in recommended }) 0 else 1 }.associate { it.key to it.value }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.importCustomModel(context.contentResolver, uri) }
    Scaffold(modifier = modifier, topBar = {
        androidx.compose.material3.TopAppBar(title = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_local_models_8e4bf)) }, navigationIcon = { IconButton(onClick = onNavigationClick) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }, actions = {
            IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.Add, "Import a local model") }
            IconButton(onClick = viewModel::refreshCatalog) { Icon(Icons.Rounded.Refresh, "Refresh catalog") }
        })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            OutlinedTextField(uiState.searchQuery, viewModel::updateSearchQuery, label = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_search_models_10421)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, trailingIcon = { TextButton(onClick = { filtersOpen = true }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_filters_546eb)) } })
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                catalogStatus?.let { snapshot ->
                    item {
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_4ddf1cb511, snapshot.source, snapshot.revision, snapshot.entries.size), style = MaterialTheme.typography.labelSmall)
                        snapshot.upstream?.let { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_dd3b98874b, it.artifactCount, it.familyCount, it.retrievedAt), style = MaterialTheme.typography.labelSmall) }
                        snapshot.refreshError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_show_every_listed_model_355b0), Modifier.weight(1f))
                        androidx.compose.material3.Switch(everyModel, { everyModel = it })
                    }
                }
                item { Row { LocalModelSource.entries.forEach { source -> FilterChip(uiState.source == source, { viewModel.updateModelSource(source) }, label = { Text(source.title) }) } } }
                if (uiState.source == LocalModelSource.HUGGING_FACE) {
                    item {
                        HuggingFaceAccountSection(hasToken = uiState.hasHuggingFaceToken, onAddToken = viewModel::openAccessTokenDialog, onRemoveToken = viewModel::removeHuggingFaceAccessToken)
                        uiState.huggingFaceSearchError?.let { Text(it) }
                    }
                }

                val active = uiState.allItems.count { it.status == LocalModelItemStatus.DOWNLOADING }
                if (active > 0) {
                    item {
                        TextButton(onClick = {
                            viewModel.updateFilter(LocalModelFilter.DOWNLOADING)
                        }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_002601a45f, active)) }
                    }
                }

                item {
                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_6e1ee292ad, ModelCatalogParser.formatDownloadSize(uiState.totalStorageBytes)))
                    androidx.compose.foundation.layout.FlowRow { LocalModelFilter.entries.forEach { value -> TextButton(onClick = { viewModel.updateFilter(value) }) { Text(value.title) } } }
                }

                if (uiState.isLoading) item { CircularProgressIndicator() }
                if (!uiState.isLoading && families.isEmpty()) {
                    item {
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_no_models_match_these_filters_c5178))
                        TextButton(onClick = {
                            viewModel.updateFilter(LocalModelFilter.ALL)
                            everyModel = true
                            runtime = "All"
                            processor = "Any"
                            capability = "Any"
                        }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_show_the_full_catalog_46ecc)) }
                    }
                }
                items(families.entries.toList(), key = { it.key }) { (key, variants) ->
                    val firstRecommended = families.entries.firstOrNull { (_, values) -> values.any { it.entry.id in recommended } }?.key
                    val firstOther = families.entries.firstOrNull { (_, values) -> values.none { it.entry.id in recommended } }?.key
                    if (key == firstRecommended) Text(stringResource(R.string.local_ai_recommended), style = MaterialTheme.typography.titleLarge)
                    if (key == firstOther) Text(stringResource(if (everyModel) R.string.local_ai_discovery else R.string.local_ai_other), style = MaterialTheme.typography.titleLarge)
                    val item = variants.firstOrNull { it.entry.id in recommended } ?: variants.minWith(compareBy<LocalModelListItem> { support(it).reasons.size }.thenBy { it.entry.minRamGb }.thenBy { if (it.downloadSizeBytes > 0) it.downloadSizeBytes else Long.MAX_VALUE })
                    Card(onClick = { family = key }, modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.entry.displayName, style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (item.entry.capabilities.vision) {
                                    androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_9b98c5a243)
                                } else if (item.entry.capabilities.thinking) {
                                    androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_d7cb5ae9a2)
                                } else {
                                    androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_17e90fc73d)
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text("${if (isGenieX(item)) "GenieX" else "LiteRT-LM"} · ${item.entry.variantLabel.ifBlank { item.entry.precision }} · ${if (item.downloadSizeBytes > 0) ModelCatalogParser.formatDownloadSize(item.downloadSizeBytes) else "Size unknown"}", style = MaterialTheme.typography.labelMedium)
                            Text(support(item).reasons.firstOrNull() ?: if (item.status == LocalModelItemStatus.READY) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_dd89b29224) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f1321c7b23), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { family = key }) { Text(if (variants.size > 1) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_38fb1198d8, (variants.size).toString()) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_cd3cd14621)) }
                        }
                    }
                }
            }
        }
    }
    family?.let { selectedFamily ->
        val variants = (uiState.allItems + uiState.huggingFaceItems).distinctBy { it.entry.id }.filter { it.entry.familyId.ifBlank { it.entry.id } == selectedFamily }
        dev.chungjungsoo.gptmobile.presentation.common.FadingDialog(onDismissRequest = { family = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Scaffold(topBar = { androidx.compose.material3.TopAppBar(title = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_model_details_5ddbc)) }, navigationIcon = { IconButton(onClick = { family = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) }) { insets ->
                LazyColumn(Modifier.padding(insets), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_variants_63d26), style = MaterialTheme.typography.titleLarge)
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_download_size_is_separate_from_working_memory_f3202))
                    }
                    runtimeStatus?.let { item { Text(it) } }
                    items(variants, key = { it.entry.id }) { item ->
                        val decision = support(item)
                        Text("${item.entry.variantLabel.ifBlank { item.entry.displayName }} · ${item.entry.supportedAccelerators.joinToString()} · ${item.entry.precision.ifBlank { "Precision unknown" }}")
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_2ffd1f3e99, item.entry.maxContextTokens.takeIf { it > 0 } ?: androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_14c4849191), item.entry.minRamGb.takeIf { it > 0 }?.let { androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e5f04740a4, (it).toString()) } ?: androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_14c4849191)))
                        if (decision.reasons.isNotEmpty()) {
                            decision.reasons.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (item.status == LocalModelItemStatus.READY && item.entry.supportedAccelerators.isEmpty()) {
                                TextButton(onClick = { runtimeViewModel.createProfile(item.entry, onOpenProfile) }) { Text(stringResource(R.string.local_ai_needs_verification)) }
                                TextButton(onClick = { viewModel.onDeleteClick(item.entry) }) { Text(stringResource(R.string.delete)) }
                            }
                            if (decision.action == dev.chungjungsoo.gptmobile.data.catalog.CatalogAction.VIEW_BUILD) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_the_geniex_apk_is_a_separate_installation_its_dedfc))
                        } else {
                            LocalModelItem(
                                item,
                                uiState.source,
                                uiState.checkingAccessEntryId == item.entry.id,
                                { if (decision.canDownload) requestDownload(item.entry) },
                                { viewModel.cancelDownload(item.entry) },
                                { viewModel.onDeleteClick(item.entry) },
                                { runtimeViewModel.createProfile(item.entry, onOpenProfile) },
                                profiles.any { it.model == item.entry.id && it.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM }
                            )
                        }
                        if (item.entry.sourceUrl.startsWith("https://")) androidx.compose.foundation.text.BasicText(item.entry.sourceUrl, modifier = Modifier.padding(vertical = 8.dp))
                        if (item.entry.licenseUrl.isNotBlank()) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_3e8818e968, item.entry.licenseUrl))
                        if (item.entry.redistributionRestricted) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_6604b1934b))
                        item.entry.discoveryAssets.forEach { asset -> Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_0391683a30, asset.role, asset.path, asset.sizeInBytes, asset.sha256.ifBlank { androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f068c5abb9) }), style = MaterialTheme.typography.bodySmall) }
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_3133ea6d87, item.entry.id, item.entry.upstreamRevision.ifBlank { androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f068c5abb9) }, item.entry.sha256.ifBlank { androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f068c5abb9) }), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (filtersOpen) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { filtersOpen = false }, title = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_model_filters_d4bb9)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_runtime_10931))
                androidx.compose.foundation.layout.FlowRow { listOf("All", "LiteRT-LM", "GenieX").forEach { value -> TextButton(onClick = { runtime = value }) { Text(if (runtime == value) "✓ $value" else value) } } }
                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_processor_54aac))
                Row {
                    listOf("Any", "CPU", "GPU", "NPU").forEach { value ->
                        TextButton(onClick = {
                            processor = value
                            viewModel.setNpuOnly(value == "NPU")
                        }) { Text(if (processor == value) "✓ $value" else value) }
                    }
                }
                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_capabilities_advertised_not_execution_proof_6761c))
                Column { listOf("Any", "Images", "Tools", "Thinking", "MTP").forEach { value -> TextButton(onClick = { capability = value }) { Text(if (capability == value) "✓ $value" else value) } } }
            }
        }, confirmButton = { TextButton(onClick = { filtersOpen = false }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_done_11a67)) } }, dismissButton = {
            TextButton(onClick = {
                runtime = "All"
                processor = "Any"
                capability = "Any"
                viewModel.setNpuOnly(false)
            }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_reset_filters_10afa)) }
        })
    }
    if (why) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { why = false }, title = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_why_these_models_6954d)) }, text = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_these_are_curated_evaluation_candidates_with__5dd8b)) }, confirmButton = {
            TextButton(onClick = {
                why = false
                tab = 1
            }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_all_models_0f589)) }
        })
    }
    LocalModelDownloadDialogHost(
        dialog = uiState.dialog,
        onConfirmRamWarning = viewModel::confirmRamWarning,
        onConfirmMeteredDownload = viewModel::confirmMeteredDownload,
        onConfirmDelete = { viewModel.confirmDeleteWith(runtimeViewModel::deleteModelSafely) },
        onDismissDialog = viewModel::dismissDialog,
        onStartSignIn = viewModel::startHuggingFaceSignIn,
        onAuthActivityResult = viewModel::onAuthActivityResult,
        onLicenseTabClosed = viewModel::onLicenseTabClosed,
        onRetryAfterLicense = viewModel::retryAfterLicense,
        onEnterAccessToken = viewModel::openAccessTokenDialog,
        onSaveAccessToken = viewModel::saveHuggingFaceAccessToken
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LocalModelsTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    onNavigationClick: () -> Unit,
    marketplace: Boolean,
    onMarketplace: () -> Unit
) {
    LargeTopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        ),
        title = {
            Text(
                modifier = Modifier.padding(4.dp),
                text = if (marketplace) "Model marketplace" else "Local models",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(
                modifier = Modifier.padding(4.dp),
                onClick = onNavigationClick
            ) {
                Icon(imageVector = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.go_back))
            }
        },
        actions = { if (!marketplace) IconButton(onClick = onMarketplace) { Icon(Icons.Rounded.Storefront, "Open model marketplace", tint = MaterialTheme.colorScheme.primary) } },
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun LocalModelsOverviewCard(state: LocalModelsUiState) {
    val downloaded = state.allItems.count { it.status == LocalModelItemStatus.READY }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_ai_on_your_device_2feb5), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_ef1c0588e3),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LocalModelStat(state.totalItemCount.toString(), "indexed", Modifier.weight(1f))
                LocalModelStat(downloaded.toString(), "installed", Modifier.weight(1f))
                LocalModelStat(
                    ModelCatalogParser.formatDownloadSize(state.totalStorageBytes),
                    "storage",
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun LocalModelStat(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModelCatalogSearch(
    query: String,
    selectedFilter: LocalModelFilter,
    selectedSource: LocalModelSource,
    isSearchingHuggingFace: Boolean,
    huggingFaceSearchError: String?,
    huggingFaceSearchNotice: String?,
    onQueryChange: (String) -> Unit,
    onFilterChange: (LocalModelFilter) -> Unit,
    onSourceChange: (LocalModelSource) -> Unit,
    onRefreshHuggingFace: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LocalModelSource.entries.forEach { source ->
                FilterChip(
                    selected = selectedSource == source,
                    onClick = { onSourceChange(source) },
                    label = {
                        Text(
                            if (source == LocalModelSource.HUGGING_FACE) {
                                androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f58e641ce2)
                            } else {
                                androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_da4893b1ee)
                            }
                        )
                    }
                )
            }
            if (selectedSource == LocalModelSource.HUGGING_FACE) {
                IconButton(onClick = onRefreshHuggingFace, enabled = !isSearchingHuggingFace) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh Hugging Face search")
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = {
                Text(
                    if (selectedSource == LocalModelSource.HUGGING_FACE) {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_6fd281e2ee)
                    } else {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_8c4a300a01)
                    }
                )
            },
            placeholder = {
                Text(
                    if (selectedSource == LocalModelSource.HUGGING_FACE) {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_3544ca9546)
                    } else {
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_0ba072a838)
                    }
                )
            },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LocalModelFilter.entries.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(filter.title) }
                )
            }
        }
        if (isSearchingHuggingFace) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }

        huggingFaceSearchNotice?.takeIf { selectedSource == LocalModelSource.HUGGING_FACE }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        huggingFaceSearchError?.takeIf { selectedSource == LocalModelSource.HUGGING_FACE }?.let { error ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onRefreshHuggingFace) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_retry_94208)) }
            }
        }

        Text(
            if (selectedSource == LocalModelSource.HUGGING_FACE) {
                androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_5f9fd04d6f)
            } else {
                androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_c08d94e2d8)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
internal fun HuggingFaceAccountSection(
    hasToken: Boolean,
    onAddToken: () -> Unit,
    onRemoveToken: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.huggingface_account),
            style = MaterialTheme.typography.titleMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (hasToken) {
                    stringResource(R.string.huggingface_token_saved)
                } else {
                    stringResource(R.string.huggingface_add_access_token)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (hasToken) {
                TextButton(onClick = onRemoveToken) {
                    Text(stringResource(R.string.huggingface_remove_token))
                }
            } else {
                TextButton(onClick = onAddToken) {
                    Text(stringResource(R.string.huggingface_add_access_token))
                }
            }
        }
    }
}

@Composable
private fun CustomModelImportSection(
    onImportClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.custom_local_models),
            style = MaterialTheme.typography.titleMedium
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.custom_local_models_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onImportClick) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(stringResource(R.string.import_model))
            }
        }
    }
}

@Composable
private fun LocalModelItem(
    item: LocalModelListItem,
    source: LocalModelSource,
    isCheckingAccess: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onCreateProfile: () -> Unit,
    hasProfile: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            val hasNpu = dev.chungjungsoo.gptmobile.data.localruntime.LocalAccelerators.isNpuEligible(item.entry.supportedAccelerators, item.entry.socToModelFiles, android.os.Build.SOC_MODEL.orEmpty())
            val hasLiteRt = item.entry.supportedAccelerators.any { it.equals("cpu", true) || it.equals("gpu", true) }
            Text(
                if (hasNpu) {
                    if (hasLiteRt) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_1ef31aa2db) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_df3029a26b)
                } else if (hasLiteRt) {
                    androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_d7f0218cc1)
                } else {
                    androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_160f06f214)
                },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(9.dp).size(22.dp)
                    )
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.entry.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        item.benchmarkScore?.let { score ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    "$score",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    val hubModelId = HuggingFaceUrls.modelId(item.entry.downloadUrl)
                    Text(
                        text = when {
                            source == LocalModelSource.HUGGING_FACE && hubModelId != null ->
                                "$hubModelId • ${item.entry.downloadUrl.substringBefore('?').substringAfterLast('/')}"
                            hubModelId != null -> hubModelId
                            else -> item.entry.id
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.entry.sha256.isNotBlank()) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_eafb6716b4) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_6a3f83e347),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon(
                    title = androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_2bee9e37c6),
                    description = "A publisher checksum verifies downloaded bytes against the catalog. Without it, size and container checks identify the file but do not prove publisher authenticity. Installation does not verify model capabilities or runtime compatibility. NPU packages need an exact supported chip and runtime. RAM is an advisory total-memory threshold; available memory and context size also matter. Context includes prompts, tools and output."
                )
            }
            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_a6af91a3fe, item.entry.maxContextTokens.takeIf { it > 0 }?.let { androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_c9d27ffc37, (it).toString()) } ?: androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_0af6b1cbae), item.entry.defaultConfig.maxTokens), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            if (item.entry.variantLabel.isNotBlank()) {
                Text(item.entry.variantLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (item.entry.precision.isNotBlank()) {
                Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_7e1b41296b, item.entry.precision), style = MaterialTheme.typography.bodySmall)
            }
            val blockedReason = dev.chungjungsoo.gptmobile.data.localmodel.LocalModelCompatibility.installedPackageIssue(item.entry.downloadUrl, item.entry.downloadUrl.substringBefore('?'))
            if (blockedReason != null) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f84ea6b137, blockedReason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            LocalModelRequirements(item = item)
            LocalModelDownloadStatus(
                item = item,
                isCheckingAccess = isCheckingAccess,
                onDownload = onDownload,
                onCancel = onCancel,
                onDelete = onDelete
            )
            if (item.status == LocalModelItemStatus.READY) {
                Button(onClick = onCreateProfile, enabled = blockedReason == null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(if (hasProfile) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_96ad4bd0f7) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_cc7f657498)) }
            }
        }
    }
}
