package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.localruntime.AcceleratorOption
import dev.chungjungsoo.gptmobile.data.localruntime.AcceleratorUnavailableReason
import dev.chungjungsoo.gptmobile.data.localruntime.LocalAccelerators
import dev.chungjungsoo.gptmobile.data.model.GeminiSafetySettings
import dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
import dev.chungjungsoo.gptmobile.data.ollama.OllamaOptions
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterOptions
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterProviderRouting
import dev.chungjungsoo.gptmobile.presentation.common.RadioItem
import dev.chungjungsoo.gptmobile.presentation.ui.setup.DownloadedLocalModelOption
import dev.chungjungsoo.gptmobile.presentation.ui.setup.LocalModelPicker
import dev.chungjungsoo.gptmobile.util.isValidUrl
import kotlin.math.roundToInt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
fun PlatformNameDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    initialValue: String,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isPlatformNameDialogOpen) {
        PlatformNameDialog(
            initialValue = initialValue,
            onDismissRequest = settingViewModel::closePlatformNameDialog,
            onConfirmRequest = { name ->
                settingViewModel.updatePlatformName(name)
            }
        )
    }
}

@Composable
fun APIUrlDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    initialValue: String,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isApiUrlDialogOpen) {
        APIUrlDialog(
            initialValue = initialValue,
            onDismissRequest = settingViewModel::closeApiUrlDialog,
            onConfirmRequest = { apiUrl ->
                settingViewModel.updateApiUrl(apiUrl)
            }
        )
    }
}

@Composable
fun APIKeyDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    initialTokens: String? = null,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isApiTokenDialogOpen) {
        APIKeyDialog(
            initialTokens = initialTokens,
            onDismissRequest = settingViewModel::closeApiTokenDialog
        ) { apiToken ->
            settingViewModel.updateApiToken(apiToken)
        }
    }
}

@Composable
fun ModelDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    model: String,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isApiModelDialogOpen) {
        ModelDialog(
            initModel = model,
            onDismissRequest = settingViewModel::closeApiModelDialog
        ) { m ->
            settingViewModel.updateApiModel(m)
        }
    }
}

@Composable
fun LocalModelDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    selectedCatalogEntryId: String,
    models: List<DownloadedLocalModelOption>,
    onNavigateToLocalModels: () -> Unit,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isApiModelDialogOpen) {
        LocalModelDialog(
            selectedCatalogEntryId = selectedCatalogEntryId,
            models = models,
            onDismissRequest = settingViewModel::closeApiModelDialog,
            onModelSelected = settingViewModel::updateApiModel,
            onNavigateToLocalModels = onNavigateToLocalModels
        )
    }
}

@Composable
fun TopKDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    topK: Int?,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isTopKDialogOpen) {
        TopKDialog(
            topK = topK,
            onDismissRequest = settingViewModel::closeTopKDialog,
            onConfirmRequest = settingViewModel::updateTopK
        )
    }
}

@Composable
fun MaxTokensDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    maxTokens: Int?,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isMaxTokensDialogOpen) {
        MaxTokensDialog(
            maxTokens = maxTokens,
            maxTokensCap = settingViewModel.maxTokensCap(),
            onDismissRequest = settingViewModel::closeMaxTokensDialog,
            onConfirmRequest = settingViewModel::updateMaxTokens
        )
    }
}

@Composable
fun AcceleratorDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    accelerator: String?,
    options: List<AcceleratorOption>,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isAcceleratorDialogOpen) {
        AcceleratorDialog(
            accelerator = accelerator,
            options = options,
            onDismissRequest = settingViewModel::closeAcceleratorDialog,
            onConfirmRequest = settingViewModel::updateAccelerator
        )
    }
}

@Composable
fun TemperatureDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    temperature: Float?,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isTemperatureDialogOpen) {
        TemperatureDialog(
            temperature = temperature,
            onDismissRequest = settingViewModel::closeTemperatureDialog,
            onConfirmRequest = settingViewModel::updateTemperature
        )
    }
}

@Composable
fun TopPDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    topP: Float?,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isTopPDialogOpen) {
        TopPDialog(
            topP = topP,
            onDismissRequest = settingViewModel::closeTopPDialog,
            onConfirmRequest = settingViewModel::updateTopP
        )
    }
}

@Composable
fun SystemPromptDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    prompt: String,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isSystemPromptDialogOpen) {
        SystemPromptDialog(
            prompt = prompt,
            onDismissRequest = settingViewModel::closeSystemPromptDialog,
            onConfirmRequest = settingViewModel::updateSystemPrompt
        )
    }
}

@Composable
fun TimeoutDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    timeout: Int,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isTimeoutDialogOpen) {
        TimeoutDialog(
            initialValue = timeout,
            onDismissRequest = settingViewModel::closeTimeoutDialog,
            onConfirmRequest = settingViewModel::updateTimeout
        )
    }
}

@Composable
fun GeminiSafetySettingsDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    platform: PlatformV2,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isGeminiSafetyDialogOpen) {
        GeminiSafetySettingsDialog(
            platform = platform,
            onDismissRequest = settingViewModel::closeGeminiSafetyDialog,
            onConfirmRequest = settingViewModel::updateGeminiSafetySettings
        )
    }
}

@Composable
fun OpenRouterAdvancedSettingsDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    routingJson: String?,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isOpenRouterSettingsDialogOpen) {
        OpenRouterAdvancedSettingsDialog(
            initialRoutingJson = routingJson,
            onDismissRequest = settingViewModel::closeOpenRouterSettingsDialog,
            onConfirmRequest = settingViewModel::updateOpenRouterRouting
        )
    }
}

@Composable
fun OllamaAdvancedSettingsDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    ollamaOptionsJson: String?,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isOllamaAdvancedDialogOpen) {
        OllamaAdvancedSettingsDialog(
            initialOptionsJson = ollamaOptionsJson,
            onDismissRequest = settingViewModel::closeOllamaAdvancedDialog,
            onConfirmRequest = settingViewModel::updateOllamaOptions
        )
    }
}

@Composable
fun DeletePlatformDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isDeleteDialogOpen) {
        DeletePlatformDialog(
            onDismissRequest = settingViewModel::closeDeleteDialog,
            onConfirmRequest = settingViewModel::deletePlatform
        )
    }
}

@Composable
private fun PlatformNameDialog(
    initialValue: String,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (name: String) -> Unit
) {
    var platformName by remember { mutableStateOf(initialValue) }
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.platform_name)) },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = platformName,
                onValueChange = { platformName = it },
                label = { Text(stringResource(R.string.platform_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                supportingText = {
                    Text(stringResource(R.string.platform_name_supporting))
                }
            )
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = platformName.isNotBlank(),
                onClick = { onConfirmRequest(platformName) }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun APIUrlDialog(
    initialValue: String,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (url: String) -> Unit
) {
    var apiUrl by remember { mutableStateOf(initialValue) }
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.api_url)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.api_url_cautions)
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    value = apiUrl,
                    singleLine = true,
                    isError = apiUrl.isValidUrl().not(),
                    onValueChange = { apiUrl = it },
                    label = {
                        Text(stringResource(R.string.api_url))
                    },
                    supportingText = {
                        if (apiUrl.isValidUrl().not()) {
                            Text(text = stringResource(R.string.invalid_api_url))
                        }
                    }
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = apiUrl.isNotBlank() && apiUrl.isValidUrl() && apiUrl.endsWith("/"),
                onClick = { onConfirmRequest(apiUrl) }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun APIKeyDialog(
    initialTokens: String? = null,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (token: String) -> Unit
) {
    val initialList = remember(initialTokens) {
        val parsed = ApiCredentialRotator.parseKeys(initialTokens)
        if (parsed.isEmpty()) listOf("") else parsed
    }
    val tokens = remember(initialTokens) {
        mutableStateListOf<String>().apply {
            addAll(initialList)
        }
    }
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.api_key)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.multi_api_keys_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                tokens.forEachIndexed { index, tokenValue ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = tokenValue,
                            onValueChange = { tokens[index] = it },
                            label = {
                                Text(
                                    if (tokens.size > 1) {
                                        stringResource(R.string.api_key_number, index + 1)
                                    } else {
                                        stringResource(R.string.api_key)
                                    }
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                        )
                        if (tokens.size > 1) {
                            IconButton(
                                onClick = { tokens.removeAt(index) },
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
                        onClick = { tokens.add("") }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.add_api_key),
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text(stringResource(R.string.add_api_key))
                    }
                }
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    val combined = ApiCredentialRotator.formatKeys(tokens.toList())
                    onConfirmRequest(combined)
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun TimeoutDialog(
    initialValue: Int,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (timeoutSeconds: Int) -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    var timeoutSeconds by remember { mutableStateOf(initialValue.toString()) }
    val parsedTimeout = timeoutSeconds.toIntOrNull()
    val isValidTimeout = parsedTimeout != null && parsedTimeout >= 0

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.timeout)) },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = timeoutSeconds,
                onValueChange = { timeoutSeconds = it },
                label = { Text(stringResource(R.string.timeout_seconds_label)) },
                singleLine = true,
                isError = !isValidTimeout,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                supportingText = {
                    Text(
                        text = if (isValidTimeout) {
                            stringResource(R.string.timeout_setting_description)
                        } else {
                            stringResource(R.string.timeout_invalid)
                        }
                    )
                }
            )
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = isValidTimeout,
                onClick = { onConfirmRequest(parsedTimeout!!) }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ModelDialog(
    initModel: String,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (model: String) -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    var model by remember { mutableStateOf(initModel) }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.api_model)) },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.model_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                supportingText = {
                    Text(stringResource(R.string.model_supporting))
                }
            )
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = model.isNotBlank(),
                onClick = { onConfirmRequest(model) }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun LocalModelDialog(
    selectedCatalogEntryId: String,
    models: List<DownloadedLocalModelOption>,
    onDismissRequest: () -> Unit,
    onModelSelected: (String) -> Unit,
    onNavigateToLocalModels: () -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.api_model)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                LocalModelPicker(
                    models = models,
                    selectedCatalogEntryId = selectedCatalogEntryId,
                    onModelSelected = onModelSelected,
                    onNavigateToLocalModels = onNavigateToLocalModels
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun TopKDialog(
    topK: Int?,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (topK: Int?) -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    var textFieldTopK by remember { mutableStateOf(topK?.toString() ?: "") }
    val parsedTopK = textFieldTopK.toIntOrNull()
    val isUnset = textFieldTopK.isBlank()
    val isValid = isUnset || (parsedTopK != null && parsedTopK in PlatformSettingViewModel.MIN_TOP_K..PlatformSettingViewModel.MAX_TOP_K)

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.top_k_setting)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.top_k_setting_description))
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    value = textFieldTopK,
                    onValueChange = { textFieldTopK = it },
                    label = { Text(stringResource(R.string.top_k)) },
                    singleLine = true,
                    isError = !isValid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    placeholder = { Text(stringResource(R.string.not_set)) },
                    supportingText = {
                        if (!isValid) {
                            Text(stringResource(R.string.top_k_invalid))
                        }
                    }
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { onConfirmRequest(if (isUnset) null else parsedTopK) }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun MaxTokensDialog(
    maxTokens: Int?,
    maxTokensCap: Int = PlatformSettingViewModel.DEFAULT_MAX_TOKENS_CAP,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (maxTokens: Int?) -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    var textFieldMaxTokens by remember { mutableStateOf(maxTokens?.toString() ?: "") }
    val parsedMaxTokens = textFieldMaxTokens.toIntOrNull()
    val isUnset = textFieldMaxTokens.isBlank()
    val isValid = isUnset ||
        (
            parsedMaxTokens != null &&
                parsedMaxTokens in PlatformSettingViewModel.MIN_MAX_TOKENS..maxTokensCap
            )

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.max_tokens_setting)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.max_tokens_setting_description))
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    value = textFieldMaxTokens,
                    onValueChange = { textFieldMaxTokens = it },
                    label = { Text(stringResource(R.string.max_tokens)) },
                    singleLine = true,
                    isError = !isValid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    placeholder = { Text(stringResource(R.string.not_set)) },
                    supportingText = {
                        if (!isValid) {
                            Text(
                                stringResource(
                                    R.string.max_tokens_invalid,
                                    maxTokensCap
                                )
                            )
                        } else if (maxTokensCap < PlatformSettingViewModel.DEFAULT_MAX_TOKENS_CAP) {
                            Text(
                                stringResource(
                                    R.string.max_tokens_hardware_cap_hint,
                                    maxTokensCap
                                )
                            )
                        } else {
                            Text(
                                stringResource(
                                    R.string.max_tokens_standard_hint,
                                    maxTokensCap
                                )
                            )
                        }
                    }
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { onConfirmRequest(if (isUnset) null else parsedMaxTokens) }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun AcceleratorDialog(
    accelerator: String?,
    options: List<AcceleratorOption>,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (String) -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.accelerator_setting)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.accelerator_setting_description))
                options.forEach { option ->
                    RadioItem(
                        title = acceleratorTitle(option.accelerator),
                        description = acceleratorUnavailableReason(option),
                        value = option.accelerator,
                        selected = LocalAccelerators.normalize(accelerator) == option.accelerator,
                        enabled = option.enabled
                    ) {
                        if (option.enabled) {
                            onConfirmRequest(option.accelerator)
                        }
                    }
                }
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun acceleratorTitle(accelerator: String): String = when (accelerator) {
    LocalAccelerators.GPU -> stringResource(R.string.accelerator_gpu)
    LocalAccelerators.NPU -> stringResource(R.string.accelerator_npu)
    else -> stringResource(R.string.accelerator_cpu)
}

@Composable
private fun acceleratorUnavailableReason(option: AcceleratorOption): String? {
    if (option.enabled) return null
    return when (option.unavailableReason) {
        AcceleratorUnavailableReason.DEVICE_NOT_SUPPORTED -> stringResource(R.string.accelerator_unavailable_device_npu)

        AcceleratorUnavailableReason.MODEL_HAS_NO_BUILD -> when (option.accelerator) {
            LocalAccelerators.GPU -> stringResource(R.string.accelerator_unavailable_model_no_gpu_build)
            LocalAccelerators.NPU -> stringResource(R.string.accelerator_unavailable_model_no_npu_build)
            else -> stringResource(R.string.accelerator_unavailable_model_no_cpu_build)
        }

        null -> null
    }
}

@Composable
private fun TemperatureDialog(
    temperature: Float?,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (temp: Float?) -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    var textFieldTemperature by remember { mutableStateOf(temperature?.let { "%.1f".format(it) } ?: "") }
    var sliderTemperature by remember { mutableFloatStateOf(temperature ?: 1F) }
    var isUnset by remember { mutableStateOf(temperature == null) }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.temperature_setting)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.temperature_setting_description))
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    value = textFieldTemperature,
                    onValueChange = { t ->
                        textFieldTemperature = t
                        if (t.isBlank()) {
                            isUnset = true
                        } else {
                            val converted = t.toFloatOrNull()
                            converted?.let {
                                sliderTemperature = it.coerceIn(0F, 2F)
                                isUnset = false
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = {
                        Text(stringResource(R.string.temperature))
                    },
                    placeholder = {
                        Text(stringResource(R.string.not_set))
                    }
                )
                Slider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    value = sliderTemperature,
                    valueRange = 0F..2F,
                    steps = 19,
                    enabled = !isUnset,
                    onValueChange = { t ->
                        val rounded = (t * 10).roundToInt() / 10F
                        sliderTemperature = rounded
                        textFieldTemperature = "%.1f".format(rounded)
                        isUnset = false
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            textFieldTemperature = ""
                            isUnset = true
                        }
                    ) {
                        Text(stringResource(R.string.reset))
                    }
                }
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = { onConfirmRequest(if (isUnset) null else sliderTemperature) }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun TopPDialog(
    topP: Float?,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (topP: Float?) -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    var textFieldTopP by remember { mutableStateOf(topP?.let { "%.1f".format(it) } ?: "") }
    var sliderTopP by remember { mutableFloatStateOf(topP ?: 1F) }
    var isUnset by remember { mutableStateOf(topP == null) }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.top_p_setting)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.top_p_setting_description))
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    value = textFieldTopP,
                    onValueChange = { p ->
                        textFieldTopP = p
                        if (p.isBlank()) {
                            isUnset = true
                        } else {
                            p.toFloatOrNull()?.let {
                                val rounded = (it.coerceIn(0.1F, 1F) * 10).roundToInt() / 10F
                                sliderTopP = rounded
                                isUnset = false
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = {
                        Text(stringResource(R.string.top_p))
                    },
                    placeholder = {
                        Text(stringResource(R.string.not_set))
                    }
                )
                Slider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    value = sliderTopP,
                    valueRange = 0.1F..1F,
                    steps = 8,
                    enabled = !isUnset,
                    onValueChange = { t ->
                        val rounded = (t * 10).roundToInt() / 10F
                        sliderTopP = rounded
                        textFieldTopP = "%.1f".format(rounded)
                        isUnset = false
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            textFieldTopP = ""
                            isUnset = true
                        }
                    ) {
                        Text(stringResource(R.string.reset))
                    }
                }
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = { onConfirmRequest(if (isUnset) null else sliderTopP) }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun SystemPromptDialog(
    prompt: String,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (text: String) -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    var textFieldPrompt by remember { mutableStateOf(prompt) }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.system_prompt_setting)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.system_prompt_description))
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    value = textFieldPrompt,
                    onValueChange = { textFieldPrompt = it },
                    label = {
                        Text(stringResource(R.string.system_prompt))
                    }
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = { onConfirmRequest(textFieldPrompt) }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun GeminiSafetySettingsDialog(
    platform: PlatformV2,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (String, String, String, String) -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    var harassment by remember { mutableStateOf(GeminiSafetySettings.normalizeThreshold(platform.harassmentSafetyThreshold)) }
    var hateSpeech by remember { mutableStateOf(GeminiSafetySettings.normalizeThreshold(platform.hateSpeechSafetyThreshold)) }
    var sexuallyExplicit by remember { mutableStateOf(GeminiSafetySettings.normalizeThreshold(platform.sexuallyExplicitSafetyThreshold)) }
    var dangerousContent by remember { mutableStateOf(GeminiSafetySettings.normalizeThreshold(platform.dangerousContentSafetyThreshold)) }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.gemini_safety_settings)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                SafetyThresholdDropdown(
                    label = stringResource(R.string.gemini_safety_harassment),
                    selectedThreshold = harassment,
                    onThresholdSelected = { harassment = it }
                )
                SafetyThresholdDropdown(
                    label = stringResource(R.string.gemini_safety_hate_speech),
                    selectedThreshold = hateSpeech,
                    onThresholdSelected = { hateSpeech = it }
                )
                SafetyThresholdDropdown(
                    label = stringResource(R.string.gemini_safety_sexually_explicit),
                    selectedThreshold = sexuallyExplicit,
                    onThresholdSelected = { sexuallyExplicit = it }
                )
                SafetyThresholdDropdown(
                    label = stringResource(R.string.gemini_safety_dangerous_content),
                    selectedThreshold = dangerousContent,
                    onThresholdSelected = { dangerousContent = it }
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = { onConfirmRequest(harassment, hateSpeech, sexuallyExplicit, dangerousContent) }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SafetyThresholdDropdown(
    label: String,
    selectedThreshold: String,
    onThresholdSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            value = stringResource(GeminiSafetySettings.labelResFor(selectedThreshold)),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            GeminiSafetySettings.supportedThresholds.forEach { threshold ->
                DropdownMenuItem(
                    text = { Text(stringResource(GeminiSafetySettings.labelResFor(threshold))) },
                    onClick = {
                        onThresholdSelected(threshold)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DeletePlatformDialog(
    onDismissRequest: () -> Unit,
    onConfirmRequest: () -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.delete_platform)) },
        text = {
            Text(stringResource(R.string.delete_platform_confirmation))
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onConfirmRequest) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OllamaAdvancedSettingsDialog(
    initialOptionsJson: String?,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (String?) -> Unit
) {
    val jsonSerializer = remember {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
    val initialParsed = remember(initialOptionsJson) {
        if (!initialOptionsJson.isNullOrBlank()) {
            runCatching { jsonSerializer.decodeFromString<OllamaOptions>(initialOptionsJson) }.getOrNull()
        } else {
            OllamaOptions.createDefault()
        } ?: OllamaOptions.createDefault()
    }

    var numGpuText by remember { mutableStateOf((initialParsed.numGpu ?: OllamaOptions.DEFAULT_NUM_GPU).toString()) }
    var numCtxText by remember { mutableStateOf((initialParsed.numCtx ?: OllamaOptions.DEFAULT_NUM_CTX).toString()) }
    var numBatchText by remember { mutableStateOf((initialParsed.numBatch ?: OllamaOptions.DEFAULT_NUM_BATCH).toString()) }
    var numThreadText by remember { mutableStateOf((initialParsed.numThread ?: OllamaOptions.DEFAULT_NUM_THREAD).toString()) }
    var temperatureText by remember { mutableStateOf((initialParsed.temperature ?: OllamaOptions.DEFAULT_TEMPERATURE).toString()) }
    var topPText by remember { mutableStateOf((initialParsed.topP ?: OllamaOptions.DEFAULT_TOP_P).toString()) }
    var topKText by remember { mutableStateOf((initialParsed.topK ?: OllamaOptions.DEFAULT_TOP_K).toString()) }
    var repeatPenaltyText by remember { mutableStateOf((initialParsed.repeatPenalty ?: OllamaOptions.DEFAULT_REPEAT_PENALTY).toString()) }
    var seedText by remember { mutableStateOf((initialParsed.seed ?: OllamaOptions.DEFAULT_SEED).toString()) }
    var stopTokensText by remember { mutableStateOf((initialParsed.stop ?: OllamaOptions.DEFAULT_STOP).joinToString(", ")) }

    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.ollama_advanced_options)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.ollama_advanced_options_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = numGpuText,
                    onValueChange = { numGpuText = it },
                    label = { Text(stringResource(R.string.ollama_num_gpu)) },
                    placeholder = { Text(stringResource(R.string.ollama_num_gpu_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = numCtxText,
                    onValueChange = { numCtxText = it },
                    label = { Text(stringResource(R.string.ollama_num_ctx)) },
                    placeholder = { Text(stringResource(R.string.ollama_num_ctx_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = numBatchText,
                    onValueChange = { numBatchText = it },
                    label = { Text(stringResource(R.string.ollama_num_batch)) },
                    placeholder = { Text(stringResource(R.string.ollama_num_batch_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = numThreadText,
                    onValueChange = { numThreadText = it },
                    label = { Text(stringResource(R.string.ollama_num_thread)) },
                    placeholder = { Text(stringResource(R.string.ollama_num_thread_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = temperatureText,
                    onValueChange = { temperatureText = it },
                    label = { Text(stringResource(R.string.temperature)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = topPText,
                    onValueChange = { topPText = it },
                    label = { Text(stringResource(R.string.top_p)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = topKText,
                    onValueChange = { topKText = it },
                    label = { Text(stringResource(R.string.top_k)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = repeatPenaltyText,
                    onValueChange = { repeatPenaltyText = it },
                    label = { Text(stringResource(R.string.ollama_repeat_penalty)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = seedText,
                    onValueChange = { seedText = it },
                    label = { Text(stringResource(R.string.ollama_seed)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = stopTokensText,
                    onValueChange = { stopTokensText = it },
                    label = { Text(stringResource(R.string.ollama_stop)) },
                    placeholder = { Text("```end, delimiter, You") },
                    singleLine = true
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    val stopList = stopTokensText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
                    val options = OllamaOptions(
                        numGpu = numGpuText.toIntOrNull() ?: OllamaOptions.DEFAULT_NUM_GPU,
                        numCtx = numCtxText.toIntOrNull() ?: OllamaOptions.DEFAULT_NUM_CTX,
                        numBatch = numBatchText.toIntOrNull() ?: OllamaOptions.DEFAULT_NUM_BATCH,
                        numThread = numThreadText.toIntOrNull() ?: OllamaOptions.DEFAULT_NUM_THREAD,
                        temperature = temperatureText.toFloatOrNull() ?: OllamaOptions.DEFAULT_TEMPERATURE,
                        topP = topPText.toFloatOrNull() ?: OllamaOptions.DEFAULT_TOP_P,
                        topK = topKText.toIntOrNull() ?: OllamaOptions.DEFAULT_TOP_K,
                        repeatPenalty = repeatPenaltyText.toFloatOrNull() ?: OllamaOptions.DEFAULT_REPEAT_PENALTY,
                        seed = seedText.toIntOrNull() ?: OllamaOptions.DEFAULT_SEED,
                        stop = stopList ?: OllamaOptions.DEFAULT_STOP
                    )
                    val resultJson = jsonSerializer.encodeToString(options)
                    onConfirmRequest(resultJson)
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onConfirmRequest(null)
                }
            ) {
                Text(stringResource(R.string.reset))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenRouterAdvancedSettingsDialog(
    initialRoutingJson: String?,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (String?) -> Unit
) {
    val jsonSerializer = remember {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
    val initialParsed = remember(initialRoutingJson) {
        if (!initialRoutingJson.isNullOrBlank()) {
            runCatching { jsonSerializer.decodeFromString<OpenRouterOptions>(initialRoutingJson) }.getOrNull()
                ?: runCatching {
                    val routing = jsonSerializer.decodeFromString<OpenRouterProviderRouting>(initialRoutingJson)
                    OpenRouterOptions(provider = routing)
                }.getOrNull()
        } else {
            OpenRouterOptions.createDefault()
        } ?: OpenRouterOptions.createDefault()
    }

    var streamEnabled by remember { mutableStateOf(initialParsed.stream ?: OpenRouterOptions.DEFAULT_STREAM) }
    var maxTokensText by remember { mutableStateOf((initialParsed.maxTokens ?: OpenRouterOptions.DEFAULT_MAX_TOKENS).toString()) }
    var temperatureText by remember { mutableStateOf((initialParsed.temperature ?: OpenRouterOptions.DEFAULT_TEMPERATURE).toString()) }
    var topPText by remember { mutableStateOf((initialParsed.topP ?: OpenRouterOptions.DEFAULT_TOP_P).toString()) }
    var topKText by remember { mutableStateOf((initialParsed.topK ?: OpenRouterOptions.DEFAULT_TOP_K).toString()) }
    var freqPenaltyText by remember { mutableStateOf((initialParsed.frequencyPenalty ?: OpenRouterOptions.DEFAULT_FREQUENCY_PENALTY).toString()) }
    var presPenaltyText by remember { mutableStateOf((initialParsed.presencePenalty ?: OpenRouterOptions.DEFAULT_PRESENCE_PENALTY).toString()) }
    var repPenaltyText by remember { mutableStateOf((initialParsed.repetitionPenalty ?: OpenRouterOptions.DEFAULT_REPETITION_PENALTY).toString()) }
    var seedText by remember { mutableStateOf((initialParsed.seed ?: OpenRouterOptions.DEFAULT_SEED).toString()) }

    var allowFallbacks by remember {
        mutableStateOf(initialParsed.provider?.allowFallbacks ?: OpenRouterOptions.DEFAULT_PROVIDER_ALLOW_FALLBACKS)
    }
    var sortStrategy by remember {
        mutableStateOf(initialParsed.provider?.sort ?: OpenRouterOptions.DEFAULT_PROVIDER_SORT)
    }
    var skipText by remember {
        mutableStateOf(initialParsed.provider?.skip?.joinToString(", ") ?: OpenRouterOptions.DEFAULT_PROVIDER_SKIP.joinToString(", "))
    }
    var orderText by remember {
        mutableStateOf(initialParsed.provider?.order?.joinToString(", ") ?: "")
    }

    var sortExpanded by remember { mutableStateOf(false) }

    val sortOptions = listOf("price-asc", "price", "throughput", "latency")

    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.openrouter_advanced_settings)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.openrouter_advanced_settings_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = stringResource(R.string.openrouter_stream))
                    Switch(
                        checked = streamEnabled,
                        onCheckedChange = { streamEnabled = it }
                    )
                }

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = maxTokensText,
                    onValueChange = { maxTokensText = it },
                    label = { Text(stringResource(R.string.max_tokens)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = temperatureText,
                    onValueChange = { temperatureText = it },
                    label = { Text(stringResource(R.string.temperature)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = topPText,
                    onValueChange = { topPText = it },
                    label = { Text(stringResource(R.string.top_p)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = topKText,
                    onValueChange = { topKText = it },
                    label = { Text(stringResource(R.string.top_k)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = freqPenaltyText,
                    onValueChange = { freqPenaltyText = it },
                    label = { Text(stringResource(R.string.openrouter_frequency_penalty)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = presPenaltyText,
                    onValueChange = { presPenaltyText = it },
                    label = { Text(stringResource(R.string.openrouter_presence_penalty)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = repPenaltyText,
                    onValueChange = { repPenaltyText = it },
                    label = { Text(stringResource(R.string.openrouter_repetition_penalty)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = seedText,
                    onValueChange = { seedText = it },
                    label = { Text(stringResource(R.string.openrouter_seed)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = stringResource(R.string.openrouter_allow_fallbacks))
                    Switch(
                        checked = allowFallbacks,
                        onCheckedChange = { allowFallbacks = it }
                    )
                }

                ExposedDropdownMenuBox(
                    expanded = sortExpanded,
                    onExpandedChange = { sortExpanded = it }
                ) {
                    OutlinedTextField(
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                            .fillMaxWidth(),
                        value = sortStrategy,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.openrouter_provider_sort)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortExpanded)
                        }
                    )
                    ExposedDropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false }
                    ) {
                        sortOptions.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt) },
                                onClick = {
                                    sortStrategy = opt
                                    sortExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = skipText,
                    onValueChange = { skipText = it },
                    label = { Text(stringResource(R.string.openrouter_provider_skip)) },
                    placeholder = { Text("Mancer") },
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = orderText,
                    onValueChange = { orderText = it },
                    label = { Text(stringResource(R.string.openrouter_provider_order)) },
                    placeholder = { Text(stringResource(R.string.openrouter_provider_order_hint)) },
                    singleLine = true
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    val orderList = orderText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
                    val skipList = skipText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }

                    val routing = OpenRouterProviderRouting(
                        sort = sortStrategy.trim().takeIf { it.isNotEmpty() } ?: OpenRouterOptions.DEFAULT_PROVIDER_SORT,
                        allowFallbacks = allowFallbacks,
                        skip = skipList ?: OpenRouterOptions.DEFAULT_PROVIDER_SKIP,
                        order = orderList
                    )

                    val options = OpenRouterOptions(
                        stream = streamEnabled,
                        maxTokens = maxTokensText.toIntOrNull() ?: OpenRouterOptions.DEFAULT_MAX_TOKENS,
                        temperature = temperatureText.toFloatOrNull() ?: OpenRouterOptions.DEFAULT_TEMPERATURE,
                        topP = topPText.toFloatOrNull() ?: OpenRouterOptions.DEFAULT_TOP_P,
                        topK = topKText.toIntOrNull() ?: OpenRouterOptions.DEFAULT_TOP_K,
                        frequencyPenalty = freqPenaltyText.toFloatOrNull() ?: OpenRouterOptions.DEFAULT_FREQUENCY_PENALTY,
                        presencePenalty = presPenaltyText.toFloatOrNull() ?: OpenRouterOptions.DEFAULT_PRESENCE_PENALTY,
                        repetitionPenalty = repPenaltyText.toFloatOrNull() ?: OpenRouterOptions.DEFAULT_REPETITION_PENALTY,
                        seed = seedText.toIntOrNull() ?: OpenRouterOptions.DEFAULT_SEED,
                        provider = routing
                    )

                    val resultJson = jsonSerializer.encodeToString(options)
                    onConfirmRequest(resultJson)
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onConfirmRequest(null)
                }
            ) {
                Text(stringResource(R.string.reset))
            }
        }
    )
}
