package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.ModelConstants
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
import dev.chungjungsoo.gptmobile.data.ollama.OllamaOptions
import dev.chungjungsoo.gptmobile.presentation.common.DestinationCard
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.LocalModelDownloadDialogHost
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.rememberLocalModelDownloader
import dev.chungjungsoo.gptmobile.presentation.ui.setup.LocalModelCatalogPicker
import dev.chungjungsoo.gptmobile.util.pinnedExitUntilCollapsedScrollBehavior
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private enum class AddPlatformStep { API_TYPE, DETAILS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPlatformScreen(
    modifier: Modifier = Modifier,
    viewModel: AddPlatformViewModel = hiltViewModel(),
    onNavigationClick: () -> Unit,
    onSave: (PlatformV2) -> Unit,
    onNavigateToLocalModels: () -> Unit = {}
) {
    var step by remember { mutableStateOf(AddPlatformStep.API_TYPE) }
    var selectedClientType by remember { mutableStateOf<ClientType?>(null) }
    var platformName by remember { mutableStateOf("") }
    var apiUrl by remember { mutableStateOf("") }
    val apiTokens = remember { mutableStateListOf("") }
    var model by remember { mutableStateOf("") }
    var isReasoningEnabled by remember { mutableStateOf(false) }
    var showOpenRouterPicker by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val scrollBehavior = pinnedExitUntilCollapsedScrollBehavior(
        canScroll = { scrollState.canScrollForward || scrollState.canScrollBackward }
    )
    val catalogModels by viewModel.catalogLocalModels.collectAsStateWithLifecycle()
    val downloadState by viewModel.localModelDownloadState.collectAsStateWithLifecycle()
    val selectedLocalModelId by viewModel.selectedCatalogEntryId.collectAsStateWithLifecycle()
    val canSave by viewModel.canSave.collectAsStateWithLifecycle()
    val isWaitingForDownload by viewModel.isWaitingForDownload.collectAsStateWithLifecycle()
    val requestDownload = rememberLocalModelDownloader { entry ->
        viewModel.selectLocalModel(entry.id)
    }
    val isLocalPlatform = selectedClientType == ClientType.LITERT_LM
    val title = stringResource(if (step == AddPlatformStep.API_TYPE) R.string.choose_platform_type else R.string.platform_details)
    val isSaveEnabled = platformName.isNotBlank() &&
        if (isLocalPlatform) {
            canSave
        } else {
            model.isNotBlank() && apiUrl.isNotBlank()
        }
    val navigateBack = { if (step == AddPlatformStep.DETAILS) step = AddPlatformStep.API_TYPE else onNavigationClick() }
    BackHandler(enabled = step == AddPlatformStep.DETAILS) { step = AddPlatformStep.API_TYPE }

    Scaffold(
        modifier = modifier,
        topBar = {
            AddPlatformTopBar(
                title = title,
                scrollBehavior = scrollBehavior,
                actionLabel = if (step == AddPlatformStep.DETAILS) stringResource(R.string.save) else null,
                isActionEnabled = isSaveEnabled,
                onNavigationClick = navigateBack,
                onActionClick = {
                    val clientType = selectedClientType ?: return@AddPlatformTopBar
                    val selectedModel = if (clientType == ClientType.LITERT_LM) {
                        selectedLocalModelId.trim()
                    } else {
                        model.trim()
                    }
                    if (clientType == ClientType.LITERT_LM && !viewModel.canSaveLocalModel()) return@AddPlatformTopBar
                    val defaults = if (clientType == ClientType.LITERT_LM) {
                        viewModel.defaultsFor(selectedModel)
                    } else {
                        null
                    }
                    val formattedApiKey = ApiCredentialRotator.formatKeys(apiTokens.toList())
                    val defaultOllamaOptions = if (clientType == ClientType.OLLAMA) {
                        Json.encodeToString(OllamaOptions.createDefault())
                    } else {
                        null
                    }
                    val platform = PlatformV2(
                        name = platformName.trim(),
                        compatibleType = clientType,
                        enabled = if (clientType == ClientType.LITERT_LM) {
                            viewModel.shouldEnableLocalPlatform()
                        } else {
                            true
                        },
                        apiUrl = if (clientType == ClientType.LITERT_LM) "" else apiUrl.trim(),
                        token = formattedApiKey.takeIf { it.isNotEmpty() && clientType != ClientType.LITERT_LM },
                        model = selectedModel,
                        temperature = defaults?.temperature ?: 1.0f,
                        topP = defaults?.topP ?: 1.0f,
                        topK = defaults?.topK,
                        maxTokens = defaults?.maxTokens,
                        accelerator = defaults?.accelerator,
                        systemPrompt = ModelConstants.DEFAULT_PROMPT,
                        stream = true,
                        reasoning = isReasoningEnabled && clientType != ClientType.LITERT_LM,
                        timeout = 30,
                        ollamaOptions = defaultOllamaOptions
                    )
                    apiTokens.clear()
                    apiTokens.add("")
                    onSave(platform)
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .imePadding()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            if (step == AddPlatformStep.API_TYPE) {
                Text(
                    text = stringResource(R.string.choose_platform_type_step_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                ClientType.entries.forEach { clientType ->
                    DestinationCard(
                        title = getClientTypeName(clientType),
                        description = getClientTypeDescription(clientType),
                        onClick = {
                            selectedClientType = clientType
                            platformName = ModelConstants.defaultPlatformName(clientType)
                            apiUrl = ModelConstants.defaultApiUrl(clientType)
                            model = ModelConstants.defaultModel(clientType)
                            apiTokens.clear()
                            apiTokens.add("")
                            isReasoningEnabled = false
                            step = AddPlatformStep.DETAILS
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            } else {
                val clientType = selectedClientType ?: ClientType.OPENAI
                Text(
                    text = stringResource(R.string.platform_details_description, getClientTypeName(clientType)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = platformName,
                    onValueChange = { platformName = it },
                    label = { Text(stringResource(R.string.platform_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text(stringResource(R.string.platform_name_supporting)) }
                )
                if (clientType != ClientType.LITERT_LM) {
                    OutlinedTextField(
                        value = apiUrl,
                        onValueChange = { apiUrl = it },
                        label = { Text(stringResource(R.string.api_url)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.multi_api_keys_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    apiTokens.forEachIndexed { index, tokenValue ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                modifier = Modifier.weight(1f),
                                value = tokenValue,
                                onValueChange = { apiTokens[index] = it },
                                label = {
                                    Text(
                                        if (apiTokens.size > 1) {
                                            stringResource(R.string.api_key_number, index + 1)
                                        } else {
                                            stringResource(R.string.api_key)
                                        }
                                    )
                                },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                supportingText = if (index == apiTokens.lastIndex && apiTokens.size == 1) {
                                    { Text(stringResource(R.string.api_key_supporting)) }
                                } else {
                                    null
                                }
                            )
                            if (apiTokens.size > 1) {
                                IconButton(
                                    onClick = { apiTokens.removeAt(index) },
                                    modifier = Modifier.padding(start = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.remove_api_key),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { apiTokens.add("") }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.add_api_key),
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(stringResource(R.string.add_api_key))
                        }
                    }

                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text(stringResource(R.string.model)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.model_supporting)) }
                    )

                    // Exclusively enable OpenRouter model picker for OpenRouter API
                    if (clientType == ClientType.OPENROUTER) {
                        OutlinedButton(
                            onClick = { showOpenRouterPicker = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.List,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(text = stringResource(R.string.openrouter_browse_models))
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = stringResource(R.string.extended_thinking), style = MaterialTheme.typography.bodyLarge)
                            Text(text = stringResource(R.string.extended_thinking_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = isReasoningEnabled, onCheckedChange = { isReasoningEnabled = it })
                    }
                } else {
                    Spacer(modifier = Modifier.height(16.dp))
                    LocalModelCatalogPicker(
                        items = catalogModels,
                        selectedCatalogEntryId = selectedLocalModelId,
                        checkingAccessEntryId = downloadState.checkingAccessEntryId,
                        showPendingActivationHint = isWaitingForDownload,
                        onModelSelected = { catalogEntryId ->
                            val entry = catalogModels.firstOrNull { it.entry.id == catalogEntryId }?.entry
                            if (entry != null) {
                                requestDownload(entry)
                            } else {
                                viewModel.selectLocalModel(catalogEntryId)
                            }
                        },
                        onNavigateToLocalModels = onNavigateToLocalModels
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showOpenRouterPicker) {
        OpenRouterModelPickerDialog(
            currentModel = model,
            onDismiss = { showOpenRouterPicker = false },
            onModelSelected = { selectedModel ->
                model = selectedModel
            }
        )
    }

    LocalModelDownloadDialogHost(
        dialog = downloadState.dialog,
        onConfirmRamWarning = viewModel::confirmRamWarning,
        onConfirmMeteredDownload = viewModel::confirmMeteredDownload,
        onDismissDialog = viewModel::dismissDownloadDialog,
        onConfirmHighSpeedDownload = viewModel::confirmHighSpeedDownload,
        onConfirmCellularMeteredDownload = viewModel::confirmCellularMeteredDownload
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPlatformTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    actionLabel: String?,
    isActionEnabled: Boolean,
    onNavigationClick: () -> Unit,
    onActionClick: () -> Unit
) {
    LargeTopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onNavigationClick,
                modifier = Modifier.semantics {
                    contentDescription = "Navigate back"
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null
                )
            }
        },
        actions = {
            if (actionLabel != null) {
                TextButton(
                    onClick = onActionClick,
                    enabled = isActionEnabled
                ) {
                    Text(text = actionLabel)
                }
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.largeTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}

@Composable
private fun getClientTypeName(clientType: ClientType): String = when (clientType) {
    ClientType.OPENAI -> stringResource(R.string.openai)
    ClientType.ANTHROPIC -> stringResource(R.string.anthropic)
    ClientType.GOOGLE -> stringResource(R.string.google)
    ClientType.GROQ -> stringResource(R.string.groq)
    ClientType.OLLAMA -> stringResource(R.string.ollama)
    ClientType.OPENROUTER -> stringResource(R.string.openrouter)
    ClientType.CUSTOM -> stringResource(R.string.custom)
    ClientType.LITERT_LM -> stringResource(R.string.local_model)
}

@Composable
private fun getClientTypeDescription(clientType: ClientType): String = when (clientType) {
    ClientType.OPENAI -> stringResource(R.string.openai_description)
    ClientType.ANTHROPIC -> stringResource(R.string.anthropic_description)
    ClientType.GOOGLE -> stringResource(R.string.google_description)
    ClientType.GROQ -> stringResource(R.string.groq_description)
    ClientType.OLLAMA -> stringResource(R.string.ollama_description)
    ClientType.OPENROUTER -> stringResource(R.string.openrouter_description)
    ClientType.CUSTOM -> stringResource(R.string.custom_description)
    ClientType.LITERT_LM -> stringResource(R.string.local_model_description)
}
