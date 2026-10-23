package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.LocalModelDownloadDialogHost

internal enum class LocalAiTab { MODELS, MARKETPLACE, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelsScreen(
    startInMarketplace: Boolean = false,
    startInDelegation: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: LocalModelsViewModel = hiltViewModel(),
    runtimeViewModel: LocalRuntimeSettingsViewModel = hiltViewModel(),
    onOpenProfile: (String) -> Unit = {},
    onUseProfile: (String) -> Unit = {},
    onNavigationClick: () -> Unit
) {
    var tab by rememberSaveable {
        mutableStateOf(
            if (startInMarketplace) {
                LocalAiTab.MARKETPLACE
            } else if (startInDelegation) {
                LocalAiTab.SETTINGS
            } else {
                LocalAiTab.MODELS
            }
        )
    }

    var query by rememberSaveable { mutableStateOf("") }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val importActive by viewModel.importActive.collectAsStateWithLifecycle()
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFolder by rememberSaveable { mutableStateOf(false) }
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()
    val importedModelId by viewModel.importedModelId.collectAsStateWithLifecycle()
    val runtimeStatus by runtimeViewModel.status.collectAsStateWithLifecycle()
    val activeRuntime by runtimeViewModel.active.collectAsStateWithLifecycle()
    val profiles by runtimeViewModel.profiles.collectAsStateWithLifecycle()
    val settings by runtimeViewModel.settings.collectAsStateWithLifecycle()
    val local = profiles.filter { it.compatibleType == ClientType.LITERT_LM }.sortedWith(compareByDescending<dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2> { it.isFavorite }.thenBy { it.id })
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingFolder = false
            pendingUri = uri.toString()
        }
    }
    val folderImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            pendingFolder = true
            pendingUri = uri.toString()
        }
    }
    if (pendingUri != null) {
        val source = dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSource(android.net.Uri.parse(requireNotNull(pendingUri)), if (pendingFolder) dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSource.Kind.FOLDER else dev.chungjungsoo.gptmobile.data.localmodel.LocalModelSource.Kind.FILE)
        var inspectionFailure by remember(pendingUri) { mutableStateOf<Exception?>(null) }
        val preview by produceState<dev.chungjungsoo.gptmobile.data.localmodel.LocalImportPreview?>(null, pendingUri) {
            value = null
            try {
                value = viewModel.inspectImport(source)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                inspectionFailure = failure
            }
        }
        val inspectionError = inspectionFailure?.let { it.message ?: stringResource(R.string.local_ai_inspect_failed) }
        AlertDialog(
            onDismissRequest = { pendingUri = null },
            title = { Text(stringResource(R.string.local_ai_import_review)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    inspectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (preview == null && inspectionError == null) CircularProgressIndicator()
                    preview?.let { details ->
                        Text(details.name, style = MaterialTheme.typography.titleMedium)
                        Text(details.runtime)
                        details.sourceBytes?.let { Text(stringResource(R.string.local_ai_source_size, dev.chungjungsoo.gptmobile.data.catalog.ModelCatalogParser.formatDownloadSize(it))) }
                        details.installedBytes?.let { Text(stringResource(R.string.local_ai_installed_size, dev.chungjungsoo.gptmobile.data.catalog.ModelCatalogParser.formatDownloadSize(it))) }
                        if (details.backends.isNotEmpty()) Text(stringResource(R.string.local_ai_declared_processors, details.backends.joinToString().uppercase()))
                        details.contextTokens?.let { Text(stringResource(R.string.local_ai_import_context, it, details.assets)) }
                        Text(stringResource(R.string.local_ai_file_review))
                        Text(stringResource(R.string.local_ai_import_verification), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = preview != null && !importActive, onClick = {
                    viewModel.importCustomModel(context.contentResolver, source)
                    pendingUri = null
                }) { Text(stringResource(R.string.local_ai_import)) }
            },
            dismissButton = { TextButton(onClick = { pendingUri = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    val listState = rememberLazyListState()
    val navigateBack = { if (tab != LocalAiTab.MODELS) tab = LocalAiTab.MODELS else onNavigationClick() }
    BackHandler(tab != LocalAiTab.MODELS) { navigateBack() }
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.local_ai_title)) }, navigationIcon = { IconButton(onClick = navigateBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.go_back)) } }, actions = {
            IconButton(onClick = { tab = LocalAiTab.SETTINGS }) { Icon(Icons.Rounded.MoreVert, "Local model settings") }
            SettingsHelpIcon(stringResource(R.string.local_ai_help), title = stringResource(R.string.local_ai_title))
        })
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            TabRow(tab.ordinal) {
                LocalAiTab.entries.forEach { value ->
                    Tab(tab == value, onClick = { tab = value }, text = {
                        Text(
                            stringResource(
                                when (value) {
                                    LocalAiTab.MODELS -> R.string.audit_my_models
                                    LocalAiTab.MARKETPLACE -> R.string.audit_marketplace
                                    LocalAiTab.SETTINGS -> R.string.audit_settings
                                }
                            )
                        )
                    }, modifier = Modifier.testTag("local_ai_${value.name}"))
                }
            }
            when (tab) {
                LocalAiTab.MARKETPLACE -> LocalMarketplaceScreen(viewModel = viewModel, runtimeViewModel = runtimeViewModel, onOpenProfile = onOpenProfile, embedded = true, onLinkFile = { importer.launch(arrayOf("*/*")) }, onLinkFolder = { folderImporter.launch(null) }, onNavigationClick = navigateBack)
                LocalAiTab.SETTINGS -> LocalAiSettingsScreen(viewModel, runtimeViewModel, onDownloads = {
                    tab = LocalAiTab.MARKETPLACE
                    viewModel.updateFilter(LocalModelFilter.DOWNLOADING)
                })
                LocalAiTab.MODELS -> LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "import") {
                        LocalAiActionCard(Icons.Rounded.FileOpen, stringResource(R.string.audit_link_file), stringResource(R.string.local_ai_file_review), { importer.launch(arrayOf("*/*")) })
                        TextButton(onClick = { folderImporter.launch(null) }) { Text(stringResource(R.string.audit_link_folder)) }
                    }
                    runtimeStatus?.let { status -> item(key = "runtime-status") { Text(status) } }
                    activeRuntime.fallbackReason?.takeIf { activeRuntime.lifecycle == "FAILED" }?.let { reason ->
                        item(key = "runtime-recovery") {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Local model needs attention", style = MaterialTheme.typography.titleSmall)
                                    Text(reason, style = MaterialTheme.typography.bodySmall)
                                    Text("Check the model edition and backend in its profile. After replacing or correcting it, choose Verify load from the profile menu.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    importStatus?.let { status ->
                        item(key = "import-status") {
                            Text(status)
                            if (importActive) TextButton(onClick = viewModel::cancelImport) { Text(stringResource(R.string.cancel)) }
                            importedModelId?.let { id ->
                                val item = ui.allItems.firstOrNull { it.entry.id == id }
                                TextButton(enabled = item != null, onClick = { item?.let { runtimeViewModel.createProfile(it.entry, onOpenProfile) } }) { Text(stringResource(R.string.local_ai_create)) }
                            }
                        }
                    }
                    item(key = "local-assist") {
                        TextButton(onClick = { tab = LocalAiTab.SETTINGS }) { Text("Local Assist and tools") }
                    }
                    item(key = "profiles-title") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.local_ai_profiles), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            IconButton(onClick = {
                                tab = LocalAiTab.MARKETPLACE
                                viewModel.updateFilter(LocalModelFilter.READY)
                            }) { Icon(Icons.Rounded.Add, stringResource(R.string.local_ai_create)) }
                        }
                        if (local.size >= 6) OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.local_ai_search_profiles)) }, modifier = Modifier.fillMaxWidth())
                    }
                    if (local.isEmpty()) {
                        item {
                            Text(stringResource(R.string.local_ai_empty))
                            TextButton(onClick = {
                                tab = LocalAiTab.MARKETPLACE
                                viewModel.updateFilter(LocalModelFilter.READY)
                            }) { Text(stringResource(R.string.local_ai_create)) }
                        }
                    }
                    items(local.filter { query.isBlank() || (it.name + " " + it.model).contains(query, true) }, key = { it.uid }) { profile ->
                        val artifact = ui.allItems.firstOrNull { it.entry.id == profile.model }
                        val installed = artifact?.status == LocalModelItemStatus.READY
                        var menu by remember { mutableStateOf(false) }
                        var delete by remember { mutableStateOf(false) }
                        Card(onClick = { onOpenProfile(profile.uid) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.primary)
                                    Text(profile.name, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.local_ai_profile_actions)) }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.local_ai_edit)) }, onClick = {
                                            menu = false
                                            onOpenProfile(profile.uid)
                                        })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.local_ai_duplicate)) }, onClick = {
                                            menu = false
                                            runtimeViewModel.duplicateProfile(profile)
                                        })
                                        DropdownMenuItem(text = { Text("Verify load") }, onClick = {
                                            menu = false
                                            runtimeViewModel.verifyProfile(profile.uid)
                                        })
                                        DropdownMenuItem(text = { Text(stringResource(if (profile.isFavorite) R.string.local_ai_unpin else R.string.local_ai_pin)) }, onClick = {
                                            menu = false
                                            runtimeViewModel.pinProfile(profile)
                                        })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.local_ai_assign)) }, onClick = {
                                            menu = false
                                            runtimeViewModel.updateAssist { it.copy(profileUid = profile.uid) }
                                            tab = LocalAiTab.SETTINGS
                                        })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                                            menu = false
                                            delete = true
                                        })
                                    }
                                }
                                Text(artifact?.entry?.displayName ?: profile.model, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(
                                        if (ui.isLoading) {
                                            R.string.local_ai_checking
                                        } else if (installed) {
                                            R.string.local_ai_installed
                                        } else {
                                            R.string.local_ai_missing
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                val loadedPath = activeRuntime.engineSpec?.modelPath.orEmpty().replace('\\', '/')
                                if (loadedPath.contains("/models/${profile.model}/")) Text(stringResource(R.string.local_ai_loaded), color = MaterialTheme.colorScheme.primary)
                                if (profile.uid in settings.lastChatProfileUids) Text(stringResource(R.string.local_ai_last_selected), style = MaterialTheme.typography.bodySmall)
                                if (settings.localAssistPolicy.profileUid == profile.uid) Text(stringResource(R.string.local_ai_assigned), color = MaterialTheme.colorScheme.primary)
                                Button(onClick = {
                                    if (installed) {
                                        onUseProfile(profile.uid)
                                    } else {
                                        tab = LocalAiTab.MARKETPLACE
                                        viewModel.updateSearchQuery(profile.model)
                                    }
                                }, enabled = !ui.isLoading && (profile.enabled || !installed)) { Text(stringResource(if (installed) R.string.local_ai_use else R.string.local_ai_repair)) }
                            }
                        }
                        if (delete) {
                            AlertDialog(onDismissRequest = { delete = false }, title = { Text(stringResource(R.string.delete)) }, text = { Text(stringResource(R.string.local_ai_delete_help)) }, confirmButton = {
                                TextButton(onClick = {
                                    delete = false
                                    runtimeViewModel.deleteProfile(profile)
                                }) { Text(stringResource(R.string.delete)) }
                            }, dismissButton = { TextButton(onClick = { delete = false }) { Text(stringResource(R.string.cancel)) } })
                        }
                    }
                    val active = ui.allItems.count { it.status == LocalModelItemStatus.DOWNLOADING }
                    if (active > 0) {
                        item {
                            LocalAiActionCard(Icons.Rounded.Download, stringResource(R.string.local_ai_downloads), "$active", {
                                tab = LocalAiTab.MARKETPLACE
                                viewModel.updateFilter(LocalModelFilter.DOWNLOADING)
                            })
                        }
                    }
                }
            }
        }
    }

    LocalModelDownloadDialogHost(dialog = ui.dialog, onConfirmRamWarning = viewModel::confirmRamWarning, onConfirmMeteredDownload = viewModel::confirmMeteredDownload, onConfirmDelete = { viewModel.confirmDeleteWith(runtimeViewModel::deleteModelSafely) }, onDismissDialog = viewModel::dismissDialog, onStartSignIn = viewModel::startHuggingFaceSignIn, onAuthActivityResult = viewModel::onAuthActivityResult, onLicenseTabClosed = viewModel::onLicenseTabClosed, onRetryAfterLicense = viewModel::retryAfterLicense, onEnterAccessToken = viewModel::openAccessTokenDialog, onSaveAccessToken = viewModel::saveHuggingFaceAccessToken)
}

@Composable
internal fun LocalAiActionCard(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
