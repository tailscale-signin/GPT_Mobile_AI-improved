package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.LocalModelDownloadDialogHost
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.LocalModelDownloadStatus
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.LocalModelRequirements
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.rememberLocalModelDownloader
import dev.chungjungsoo.gptmobile.util.pinnedExitUntilCollapsedScrollBehavior

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelsScreen(
    modifier: Modifier = Modifier,
    viewModel: LocalModelsViewModel = hiltViewModel(),
    runtimeViewModel: LocalRuntimeSettingsViewModel = hiltViewModel(),
    onOpenProfile: (String) -> Unit = {},
    onNavigationClick: () -> Unit
) {
    val scrollState = rememberLazyListState()
    val scrollBehavior = pinnedExitUntilCollapsedScrollBehavior(
        canScroll = { scrollState.canScrollForward || scrollState.canScrollBackward }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val requestDownload = rememberLocalModelDownloader(viewModel::onDownloadClick)
    val context = LocalContext.current

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importCustomModel(context.contentResolver, uri)
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LocalModelsTopBar(
                scrollBehavior = scrollBehavior,
                onNavigationClick = onNavigationClick
            )
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            else -> {
                LazyColumn(Modifier.padding(innerPadding), state = scrollState) {
                    item(key = "overview") { LocalModelsOverviewCard(uiState) }
                    item(key = "runtime") { LocalRuntimeSettingsCard(runtimeViewModel) }
                    item(key = "search") {
                        ModelCatalogSearch(
                            query = uiState.searchQuery,
                            selectedFilter = uiState.filter,
                            selectedSource = uiState.source,
                            isSearchingHuggingFace = uiState.isSearchingHuggingFace,
                            huggingFaceSearchError = uiState.huggingFaceSearchError,
                            onQueryChange = viewModel::updateSearchQuery,
                            onFilterChange = viewModel::updateFilter,
                            onSourceChange = viewModel::updateModelSource,
                            onRefreshHuggingFace = viewModel::refreshHuggingFaceSearch
                        )
                    }
                    item(key = "account") {
                        HuggingFaceAccountSection(
                            hasToken = uiState.hasHuggingFaceToken,
                            onAddToken = viewModel::openAccessTokenDialog,
                            onRemoveToken = viewModel::removeHuggingFaceAccessToken
                        )
                    }
                    item(key = "import") {
                        CustomModelImportSection(
                            onImportClick = {
                                openDocumentLauncher.launch(arrayOf("*/*"))
                            }
                        )
                    }
                    if (uiState.items.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = stringResource(R.string.local_models_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                            )
                        }
                    } else {
                        item(key = "storage") {
                            Text(
                                text = stringResource(
                                    R.string.local_model_storage_used,
                                    ModelCatalogParser.formatDownloadSize(uiState.totalStorageBytes)
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                            )
                        }
                        items(uiState.items, key = { it.entry.id }, contentType = { "model" }) { item ->
                            LocalModelItem(
                                item = item,
                                source = uiState.source,
                                isCheckingAccess = uiState.checkingAccessEntryId == item.entry.id,
                                onDownload = { requestDownload(item.entry) },
                                onCancel = { viewModel.cancelDownload(item.entry) },
                                onDelete = { viewModel.onDeleteClick(item.entry) },
                                onCreateProfile = { runtimeViewModel.createProfile(item.entry, onOpenProfile) }
                            )
                        }
                    }
                }
            }
        }
    }

    LocalModelDownloadDialogHost(
        dialog = uiState.dialog,
        onConfirmRamWarning = viewModel::confirmRamWarning,
        onConfirmMeteredDownload = viewModel::confirmMeteredDownload,
        onConfirmDelete = viewModel::confirmDelete,
        onDismissDialog = viewModel::dismissDialog,
        onStartSignIn = viewModel::startHuggingFaceSignIn,
        onAuthActivityResult = viewModel::onAuthActivityResult,
        onLicenseTabClosed = viewModel::onLicenseTabClosed,
        onRetryAfterLicense = viewModel::retryAfterLicense,
        onEnterAccessToken = viewModel::openAccessTokenDialog,
        onSaveAccessToken = viewModel::saveHuggingFaceAccessToken
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalModelsTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    onNavigationClick: () -> Unit
) {
    LargeTopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        ),
        title = {
            Text(
                modifier = Modifier.padding(4.dp),
                text = stringResource(R.string.local_models),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(
                modifier = Modifier.padding(4.dp),
                onClick = onNavigationClick
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.go_back))
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun LocalModelsOverviewCard(state: LocalModelsUiState) {
    val downloaded = state.items.count { it.status == LocalModelItemStatus.READY }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("Local AI model library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Search compatible indexed models, download optimized variants, or import a validated local model file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LocalModelStat(state.totalItemCount.toString(), "indexed", Modifier.weight(1f))
                LocalModelStat(downloaded.toString(), "shown downloaded", Modifier.weight(1f))
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
                                "Hugging Face Hub"
                            } else {
                                "Curated catalog"
                            }
                        )
                    }
                )
            }
            if (selectedSource == LocalModelSource.HUGGING_FACE) {
                IconButton(onClick = onRefreshHuggingFace, enabled = !isSearchingHuggingFace) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Hugging Face search")
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = {
                Text(
                    if (selectedSource == LocalModelSource.HUGGING_FACE) {
                        "Search Hugging Face LiteRT-LM"
                    } else {
                        "Search compatible models"
                    }
                )
            },
            placeholder = {
                Text(
                    if (selectedSource == LocalModelSource.HUGGING_FACE) {
                        "Gemma, Qwen, Llama, litert-community…"
                    } else {
                        "Model, Hugging Face ID, accelerator…"
                    }
                )
            },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
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
                TextButton(onClick = onRefreshHuggingFace) { Text("Retry") }
            }
        }

        Text(
            if (selectedSource == LocalModelSource.HUGGING_FACE) {
                "Live Hub results are filtered to repositories exposing .litertlm packages that this LiteRT-LM runtime can load."
            } else {
                "Curated downloads include app-tested models and device-specific variants. Use Import model for other validated local files."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun HuggingFaceAccountSection(
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
                    imageVector = Icons.Default.Add,
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
    onCreateProfile: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(9.dp).size(22.dp)
                    )
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text = item.entry.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
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
            LocalModelRequirements(item = item)
            LocalModelDownloadStatus(
                item = item,
                isCheckingAccess = isCheckingAccess,
                onDownload = onDownload,
                onCancel = onCancel,
                onDelete = onDelete
            )
            if (item.status == LocalModelItemStatus.READY) {
                Button(onClick = onCreateProfile, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Create AI profile") }
            }
        }
    }
}
