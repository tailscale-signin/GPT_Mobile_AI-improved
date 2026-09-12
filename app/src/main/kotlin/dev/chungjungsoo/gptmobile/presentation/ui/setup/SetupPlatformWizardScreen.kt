package dev.chungjungsoo.gptmobile.presentation.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
import dev.chungjungsoo.gptmobile.data.network.ApiKeyValidator
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.LocalModelDownloadDialogHost
import dev.chungjungsoo.gptmobile.presentation.ui.localmodel.rememberLocalModelDownloader
import dev.chungjungsoo.gptmobile.presentation.ui.setting.LocalModelListItem
import dev.chungjungsoo.gptmobile.presentation.ui.setting.OpenRouterModelPickerDialog
import dev.chungjungsoo.gptmobile.presentation.ui.setup.SetupViewModelV2.Companion.WIZARD_STEP_API_KEY
import dev.chungjungsoo.gptmobile.presentation.ui.setup.SetupViewModelV2.Companion.WIZARD_STEP_BASICS
import dev.chungjungsoo.gptmobile.presentation.ui.setup.SetupViewModelV2.Companion.WIZARD_STEP_MODEL
import kotlinx.coroutines.launch

@Composable
fun SetupPlatformWizardScreen(
    modifier: Modifier = Modifier,
    setupViewModel: SetupViewModelV2 = hiltViewModel(),
    onComplete: () -> Unit,
    onBackAction: () -> Unit,
    onNavigateToLocalModels: () -> Unit = {}
) {
    val wizardStep by setupViewModel.wizardStep.collectAsStateWithLifecycle()
    val selectedClientType by setupViewModel.selectedClientType.collectAsStateWithLifecycle()
    val catalogModels by setupViewModel.catalogLocalModels.collectAsStateWithLifecycle()
    val downloadState by setupViewModel.localModelDownloadState.collectAsStateWithLifecycle()
    val canProceed by setupViewModel.canProceed.collectAsStateWithLifecycle()
    val isWaitingForDownload by setupViewModel.isWaitingForDownload.collectAsStateWithLifecycle()
    val requestDownload = rememberLocalModelDownloader { entry ->
        setupViewModel.selectLocalModel(entry.id)
    }

    // Handle back press
    BackHandler {
        if (wizardStep > 0) {
            setupViewModel.previousWizardStep()
        } else {
            setupViewModel.resetWizard()
            onBackAction()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SetupAppBar(
                backAction = {
                    if (wizardStep > 0) {
                        setupViewModel.previousWizardStep()
                    } else {
                        setupViewModel.resetWizard()
                        onBackAction()
                    }
                }
            )
        },
        bottomBar = {
            val totalSteps = setupViewModel.getTotalSteps()
            val isLastStep = wizardStep == totalSteps - 1

            WizardNavigationButtons(
                currentStep = wizardStep,
                canProceed = canProceed,
                onBack = {
                    if (wizardStep > 0) {
                        setupViewModel.previousWizardStep()
                    } else {
                        setupViewModel.resetWizard()
                        onBackAction()
                    }
                },
                onNext = {
                    if (isLastStep) {
                        setupViewModel.savePlatform()
                        onComplete()
                    } else {
                        setupViewModel.nextWizardStep()
                    }
                },
                isLastStep = isLastStep,
                modifier = Modifier.imePadding()
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val totalSteps = setupViewModel.getTotalSteps()
            LinearProgressIndicator(
                progress = { (wizardStep + 1).toFloat() / totalSteps.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )

            AnimatedContent(
                targetState = wizardStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { width -> width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally { width -> -width } + fadeIn() togetherWith
                            slideOutHorizontally { width -> width } + fadeOut()
                    }
                },
                label = "WizardStepAnimation"
            ) { step ->
                when (step) {
                    WIZARD_STEP_BASICS -> {
                        val platformName by setupViewModel.platformName.collectAsStateWithLifecycle()
                        val apiUrl by setupViewModel.apiUrl.collectAsStateWithLifecycle()

                        BasicsStep(
                            platformName = platformName,
                            onPlatformNameChange = setupViewModel::setPlatformName,
                            apiUrl = apiUrl,
                            onApiUrlChange = setupViewModel::setApiUrl,
                            clientType = selectedClientType
                        )
                    }

                    WIZARD_STEP_API_KEY -> {
                        val apiKey by setupViewModel.apiKey.collectAsStateWithLifecycle()
                        val apiUrl by setupViewModel.apiUrl.collectAsStateWithLifecycle()

                        ApiKeyStep(
                            apiKey = apiKey,
                            apiUrl = apiUrl,
                            onApiKeyChange = setupViewModel::setApiKey,
                            clientType = selectedClientType
                        )
                    }

                    WIZARD_STEP_MODEL -> {
                        val model by setupViewModel.model.collectAsStateWithLifecycle()
                        if (selectedClientType == ClientType.LITERT_LM) {
                            val checkingAccessEntryId by setupViewModel.checkingAccessEntryId.collectAsStateWithLifecycle()
                            LocalModelStep(
                                items = catalogModels,
                                selectedCatalogEntryId = model,
                                checkingAccessEntryId = checkingAccessEntryId,
                                showPendingActivationHint = isWaitingForDownload,
                                onModelSelected = { selectedModel ->
                                    setupViewModel.selectLocalModel(selectedModel)
                                },
                                onNavigateToLocalModels = onNavigateToLocalModels
                            )
                        } else {
                            ModelStep(
                                model = model,
                                clientType = selectedClientType,
                                onModelChange = setupViewModel::setModel
                            )
                        }
                    }
                }
            }
        }
    }

    LocalModelDownloadDialogHost(
        state = downloadState,
        onDismiss = setupViewModel::dismissDownloadDialog,
        onConfirmGatedDownload = requestDownload::confirmGatedDownload,
        onDismissGatedDialog = requestDownload::dismissGatedDialog,
        onRetry = requestDownload::retryDownload,
        onCancel = requestDownload::cancelDownload
    )
}

@Composable
private fun BasicsStep(
    platformName: String,
    onPlatformNameChange: (String) -> Unit,
    apiUrl: String,
    onApiUrlChange: (String) -> Unit,
    clientType: ClientType?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.step_basics),
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.basics_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Platform Name
        OutlinedTextField(
            value = platformName,
            onValueChange = onPlatformNameChange,
            label = { Text(stringResource(R.string.platform_name)) },
            placeholder = { Text(stringResource(R.string.platform_name_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = {
                Text(stringResource(R.string.platform_name_supporting))
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // API URL (only show if not local model)
        if (clientType != ClientType.LITERT_LM) {
            OutlinedTextField(
                value = apiUrl,
                onValueChange = onApiUrlChange,
                label = { Text(stringResource(R.string.api_url)) },
                placeholder = { Text(stringResource(R.string.api_url_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text(
                        when (clientType) {
                            ClientType.OPENAI -> stringResource(R.string.api_url_openai_default)
                            ClientType.ANTHROPIC -> stringResource(R.string.api_url_anthropic_default)
                            ClientType.GOOGLE -> stringResource(R.string.api_url_google_default)
                            ClientType.GROQ -> stringResource(R.string.api_url_groq_default)
                            ClientType.OLLAMA -> stringResource(R.string.api_url_ollama_default)
                            ClientType.OPENROUTER -> stringResource(R.string.api_url_openrouter_default)
                            ClientType.CUSTOM -> stringResource(R.string.api_url_custom_hint)
                            null, ClientType.LITERT_LM -> ""
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun ApiKeyStep(
    apiKey: String,
    apiUrl: String,
    onApiKeyChange: (String) -> Unit,
    clientType: ClientType?,
    modifier: Modifier = Modifier
) {
    val initialList = remember(apiKey) {
        val parsed = ApiCredentialRotator.parseKeys(apiKey)
        if (parsed.isEmpty()) listOf("") else parsed
    }
    val tokens = remember {
        mutableStateListOf<String>().apply {
            addAll(initialList)
        }
    }

    var validationResult by remember { mutableStateOf<ApiKeyValidator.ValidationResult>(ApiKeyValidator.ValidationResult.Idle) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.step_api_key),
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.api_key_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (clientType == ClientType.OLLAMA) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.api_key_optional_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.multi_api_keys_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // API Keys list
        tokens.forEachIndexed { index, tokenValue ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = tokenValue,
                    onValueChange = { newValue ->
                        tokens[index] = newValue
                        onApiKeyChange(ApiCredentialRotator.formatKeys(tokens.toList()))
                    },
                    label = {
                        Text(
                            if (tokens.size > 1) {
                                stringResource(R.string.api_key_number, index + 1)
                            } else {
                                stringResource(R.string.api_key)
                            }
                        )
                    },
                    placeholder = { Text(stringResource(R.string.api_key_hint)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                if (tokens.size > 1) {
                    IconButton(
                        onClick = {
                            tokens.removeAt(index)
                            onApiKeyChange(ApiCredentialRotator.formatKeys(tokens.toList()))
                        },
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Validation probe button
            if (clientType != null && clientType != ClientType.LITERT_LM) {
                val activeKey = tokens.firstOrNull { it.isNotBlank() } ?: ""
                OutlinedButton(
                    onClick = {
                        validationResult = ApiKeyValidator.ValidationResult.Validating
                        coroutineScope.launch {
                            validationResult = ApiKeyValidator.validate(clientType, apiUrl, activeKey)
                        }
                    },
                    enabled = activeKey.isNotBlank() && validationResult !is ApiKeyValidator.ValidationResult.Validating
                ) {
                    if (validationResult is ApiKeyValidator.ValidationResult.Validating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(6.dp))
                    }
                    Text("Test Connection")
                }
            } else {
                Spacer(modifier = Modifier.size(1.dp))
            }

            TextButton(
                onClick = {
                    tokens.add("")
                    onApiKeyChange(ApiCredentialRotator.formatKeys(tokens.toList()))
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.add_api_key),
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(stringResource(R.string.add_api_key))
            }
        }

        when (val res = validationResult) {
            is ApiKeyValidator.ValidationResult.Success -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "✓ ${res.message}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            is ApiKeyValidator.ValidationResult.Error -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "✗ ${res.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            else -> {}
        }

        // Help link based on client type
        clientType?.let { type ->
            val helpUrl = getApiHelpUrl(type)
            if (helpUrl != null) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.need_help),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = helpUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun LocalModelStep(
    items: List<LocalModelListItem>,
    selectedCatalogEntryId: String,
    checkingAccessEntryId: String?,
    showPendingActivationHint: Boolean,
    onModelSelected: (String) -> Unit,
    onNavigateToLocalModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.step_model),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        LocalModelCatalogPicker(
            items = items,
            selectedCatalogEntryId = selectedCatalogEntryId,
            checkingAccessEntryId = checkingAccessEntryId,
            showPendingActivationHint = showPendingActivationHint,
            onModelSelected = onModelSelected,
            onNavigateToLocalModels = onNavigateToLocalModels
        )
    }
}

@Composable
private fun ModelStep(
    model: String,
    clientType: ClientType?,
    onModelChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showOpenRouterPicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Text(
            modifier = Modifier.semantics { heading() },
            text = stringResource(R.string.step_model),
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.model_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Model
        OutlinedTextField(
            value = model,
            onValueChange = onModelChange,
            label = { Text(stringResource(R.string.model)) },
            placeholder = { Text(stringResource(R.string.model_name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = {
                Text(stringResource(R.string.model_supporting))
            }
        )

        if (clientType == ClientType.OPENROUTER) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showOpenRouterPicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(stringResource(R.string.openrouter_browse_models))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Examples
        Text(
            text = stringResource(R.string.model_examples),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showOpenRouterPicker) {
        OpenRouterModelPickerDialog(
            currentModel = model,
            onDismiss = { showOpenRouterPicker = false },
            onModelSelected = { selected ->
                onModelChange(selected)
                showOpenRouterPicker = false
            }
        )
    }
}

@Composable
private fun WizardNavigationButtons(
    currentStep: Int,
    canProceed: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    isLastStep: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Back button
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
        ) {
            Text(
                text = if (currentStep == 0) {
                    stringResource(R.string.cancel)
                } else {
                    stringResource(R.string.back)
                }
            )
        }

        // Next/Finish button
        Button(
            onClick = onNext,
            modifier = Modifier
                .weight(1f)
                .height(40.dp),
            enabled = canProceed
        ) {
            Text(
                text = if (isLastStep) {
                    stringResource(R.string.finish)
                } else {
                    stringResource(R.string.next)
                }
            )
        }
    }
}

private fun getApiHelpUrl(clientType: ClientType): String? = when (clientType) {
    ClientType.OPENAI -> "https://platform.openai.com/account/api-keys"
    ClientType.ANTHROPIC -> "https://console.anthropic.com/settings/keys"
    ClientType.GOOGLE -> "https://aistudio.google.com/app/apikey"
    ClientType.GROQ -> "https://console.groq.com/keys"
    ClientType.OLLAMA -> "https://ollama.com/blog/openai-compatibility"
    ClientType.OPENROUTER -> "https://openrouter.ai/keys"
    ClientType.CUSTOM -> null
    ClientType.LITERT_LM -> null
}
