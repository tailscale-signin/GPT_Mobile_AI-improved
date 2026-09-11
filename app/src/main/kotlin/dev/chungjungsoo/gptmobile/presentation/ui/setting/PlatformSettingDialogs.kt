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
            onDismissRequest = settingViewModel::closeTopKDialog
        ) { value ->
            settingViewModel.updateTopK(value)
        }
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
            onDismissRequest = settingViewModel::closeMaxTokensDialog
        ) { value ->
            settingViewModel.updateMaxTokens(value)
        }
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
            onDismissRequest = settingViewModel::closeTemperatureDialog
        ) { temp ->
            settingViewModel.updateTemperature(temp)
        }
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
            onDismissRequest = settingViewModel::closeTopPDialog
        ) { p ->
            settingViewModel.updateTopP(p)
        }
    }
}

@Composable
fun SystemPromptDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    systemPrompt: String,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isSystemPromptDialogOpen) {
        SystemPromptDialog(
            prompt = systemPrompt,
            onDismissRequest = settingViewModel::closeSystemPromptDialog
        ) {
            settingViewModel.updateSystemPrompt(it)
        }
    }
}

@Composable
fun TimeoutDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    timeoutSeconds: Int,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isTimeoutDialogOpen) {
        TimeoutDialog(
            initialValue = timeoutSeconds,
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = numCtxText,
                    onValueChange = { numCtxText = it },
                    label = { Text(stringResource(R.string.ollama_num_ctx)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = numBatchText,
                    onValueChange = { numBatchText = it },
                    label = { Text(stringResource(R.string.ollama_num_batch)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = numThreadText,
                    onValueChange = { numThreadText = it },
                    label = { Text(stringResource(R.string.ollama_num_thread)) },
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    Text(
                        text = stringResource(R.string.openrouter_stream),
                        style = MaterialTheme.typography.bodyMedium
                    )
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

                ExposedDropdownMenuBox(
                    expanded = sortExpanded,
                    onExpandedChange = { sortExpanded = it }
                ) {
                    OutlinedTextField(
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                            .fillMaxWidth(),
                        value = if (sortStrategy.isBlank()) stringResource(R.string.default_label) else sortStrategy,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.openrouter_sort_strategy)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortExpanded) }
                    )
                    ExposedDropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false }
                    ) {
                        sortOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(if (option.isBlank()) stringResource(R.string.default_label) else option) },
                                onClick = {
                                    sortStrategy = option
                                    sortExpanded = false
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.openrouter_allow_fallbacks),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = allowFallbacks,
                        onCheckedChange = { allowFallbacks = it }
                    )
                }

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = skipText,
                    onValueChange = { skipText = it },
                    label = { Text(stringResource(R.string.openrouter_skip_providers)) },
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

@Composable
fun DeletePlatformDialog(
    dialogState: PlatformSettingViewModel.DialogState,
    settingViewModel: PlatformSettingViewModel
) {
    if (dialogState.isDeleteDialogOpen) {
        AlertDialog(
            title = { Text(text = stringResource(R.string.delete_platform_dialog_title)) },
            text = { Text(text = stringResource(R.string.delete_platform_confirmation)) },
            onDismissRequest = settingViewModel::closeDeleteDialog,
            confirmButton = {
                TextButton(onClick = settingViewModel::deletePlatform) {
                    Text(text = stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = settingViewModel::closeDeleteDialog) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun SearchBackendDialog(
    toolBindingState: PlatformSettingViewModel.ToolBindingState,
    settingViewModel: PlatformSettingViewModel
) {
    if (toolBindingState.isSearchBackendDialogOpen) {
        val options = listOf(
            RadioItem(
                title = stringResource(R.string.none_group),
                description = null,
                selected = toolBindingState.selectedSearchConnectionUid == null,
                onClick = { settingViewModel.selectSearchBackend(null) }
            )
        ) + toolBindingState.searchConnections.map { conn ->
            RadioItem(
                title = conn.name,
                description = conn.connectionType.name,
                selected = toolBindingState.selectedSearchConnectionUid == conn.connectionUid,
                onClick = { settingViewModel.selectSearchBackend(conn.connectionUid) }
            )
        }
        SingleChoiceDialog(
            title = stringResource(R.string.web_search),
            options = options,
            onDismissRequest = settingViewModel::closeSearchBackendDialog
        )
    }
}

@Composable
fun LegacyMcpToolsDialog(
    toolBindingState: PlatformSettingViewModel.ToolBindingState,
    settingViewModel: PlatformSettingViewModel
) {
    if (toolBindingState.isMcpToolsDialogOpen) {
        AlertDialog(
            title = { Text(text = stringResource(R.string.mcp_server)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    toolBindingState.mcpToolOptions.forEach { tool ->
                        val isChecked = toolBindingState.pendingMcpTools.any {
                            it.connectionUid == tool.connectionUid && it.toolName == tool.toolName
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = tool.modelToolName, style = MaterialTheme.typography.bodyMedium)
                                tool.description?.let {
                                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Switch(
                                checked = isChecked,
                                onCheckedChange = {
                                    settingViewModel.toggleMcpTool(tool.connectionUid, tool.toolName)
                                }
                            )
                        }
                    }
                }
            },
            onDismissRequest = settingViewModel::closeMcpToolsDialog,
            confirmButton = {
                TextButton(onClick = settingViewModel::saveMcpTools) {
                    Text(text = stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = settingViewModel::closeMcpToolsDialog) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun PlatformNameDialog(
    initialValue: String,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialValue) }
    AlertDialog(
        title = { Text(stringResource(R.string.platform_name)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.platform_name)) },
                singleLine = true
            )
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = { onConfirmRequest(name) },
                enabled = name.isNotBlank()
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
    onConfirmRequest: (String) -> Unit
) {
    var url by remember { mutableStateOf(initialValue) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        title = { Text(stringResource(R.string.api_url)) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        isError = !isValidUrl(it)
                    },
                    isError = isError,
                    label = { Text(stringResource(R.string.api_url)) },
                    singleLine = true,
                    supportingText = {
                        if (isError) {
                            Text(stringResource(R.string.invalid_api_url))
                        }
                    }
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    if (isValidUrl(url)) {
                        onConfirmRequest(url)
                    } else {
                        isError = true
                    }
                }
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
    onConfirmRequest: (String) -> Unit
) {
    val tokens = remember {
        mutableStateListOf<String>().apply {
            if (initialTokens != null) {
                addAll(ApiCredentialRotator.unpackKeys(initialTokens))
            }
            if (isEmpty()) {
                add("")
            }
        }
    }

    AlertDialog(
        title = { Text(stringResource(R.string.api_key)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.multi_api_keys_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                tokens.forEachIndexed { index, token ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = token,
                            onValueChange = { tokens[index] = it },
                            label = { Text(stringResource(R.string.api_key_number, index + 1)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        if (tokens.size > 1) {
                            IconButton(onClick = { tokens.removeAt(index) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.remove_api_key))
                            }
                        }
                    }
                }
                TextButton(
                    onClick = { tokens.add("") },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.add_api_key))
                }
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    val packed = ApiCredentialRotator.packKeys(tokens.filter { it.isNotBlank() })
                    onConfirmRequest(packed)
                }
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
    onConfirmRequest: (String) -> Unit
) {
    var model by remember { mutableStateOf(initModel) }
    AlertDialog(
        title = { Text(stringResource(R.string.model)) },
        text = {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.model)) },
                singleLine = true
            )
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = { onConfirmRequest(model) },
                enabled = model.isNotBlank()
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
private fun LocalModelDialog(
    selectedCatalogEntryId: String,
    models: List<DownloadedLocalModelOption>,
    onDismissRequest: () -> Unit,
    onModelSelected: (String) -> Unit,
    onNavigateToLocalModels: () -> Unit
) {
    AlertDialog(
        title = { Text(stringResource(R.string.local_platform_select_model)) },
        text = {
            LocalModelPicker(
                selectedCatalogEntryId = selectedCatalogEntryId,
                models = models,
                onModelSelected = onModelSelected,
                onNavigateToLocalModels = {
                    onDismissRequest()
                    onNavigateToLocalModels()
                }
            )
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}

@Composable
private fun TopKDialog(
    topK: Int?,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (Int?) -> Unit
) {
    var valueText by remember { mutableStateOf(topK?.toString() ?: "") }
    AlertDialog(
        title = { Text(stringResource(R.string.top_k_setting)) },
        text = {
            OutlinedTextField(
                value = valueText,
                onValueChange = { valueText = it },
                label = { Text(stringResource(R.string.top_k_setting)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = valueText.toIntOrNull()
                    onConfirmRequest(parsed)
                }
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
    maxTokensCap: Int,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (Int?) -> Unit
) {
    var valueText by remember { mutableStateOf(maxTokens?.toString() ?: "") }
    AlertDialog(
        title = { Text(stringResource(R.string.max_tokens_setting)) },
        text = {
            Column {
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    label = { Text(stringResource(R.string.max_tokens_setting)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    supportingText = {
                        Text(stringResource(R.string.max_tokens_hardware_cap_hint, maxTokensCap))
                    }
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = valueText.toIntOrNull()
                    onConfirmRequest(parsed)
                }
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
    val items = options.map { opt ->
        RadioItem(
            title = opt.accelerator,
            description = opt.unavailableReason?.let { reason ->
                when (reason) {
                    AcceleratorUnavailableReason.DEVICE_NPU_UNSUPPORTED -> stringResource(R.string.accelerator_unavailable_device_npu)
                    AcceleratorUnavailableReason.MODEL_NO_GPU_BUILD -> stringResource(R.string.accelerator_unavailable_model_no_gpu_build)
                    AcceleratorUnavailableReason.MODEL_NO_NPU_BUILD -> stringResource(R.string.accelerator_unavailable_model_no_npu_build)
                    AcceleratorUnavailableReason.MODEL_NO_CPU_BUILD -> stringResource(R.string.accelerator_unavailable_model_no_cpu_build)
                }
            },
            selected = opt.accelerator == LocalAccelerators.normalize(accelerator),
            enabled = opt.enabled,
            onClick = { onConfirmRequest(opt.accelerator) }
        )
    }
    SingleChoiceDialog(
        title = stringResource(R.string.accelerator_setting),
        options = items,
        onDismissRequest = onDismissRequest
    )
}

@Composable
private fun TemperatureDialog(
    temperature: Float?,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (Float?) -> Unit
) {
    var temp by remember { mutableFloatStateOf(temperature ?: 1.0f) }
    AlertDialog(
        title = { Text(stringResource(R.string.temperature_setting)) },
        text = {
            Column {
                Text(text = "%.2f".format(temp))
                Slider(
                    value = temp,
                    onValueChange = { temp = it },
                    valueRange = 0.0f..2.0f,
                    steps = 19
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = { onConfirmRequest(temp) }) {
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
private fun TopPDialog(
    topP: Float?,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (Float?) -> Unit
) {
    var p by remember { mutableFloatStateOf(topP ?: 1.0f) }
    AlertDialog(
        title = { Text(stringResource(R.string.top_p_setting)) },
        text = {
            Column {
                Text(text = "%.2f".format(p))
                Slider(
                    value = p,
                    onValueChange = { p = it },
                    valueRange = 0.0f..1.0f,
                    steps = 19
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = { onConfirmRequest(p) }) {
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
private fun SystemPromptDialog(
    prompt: String,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (String) -> Unit
) {
    var value by remember { mutableStateOf(prompt) }
    AlertDialog(
        title = { Text(stringResource(R.string.system_prompt_dialog_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.system_prompt)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 240.dp),
                maxLines = 8
            )
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = { onConfirmRequest(value) }) {
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
private fun TimeoutDialog(
    initialValue: Int,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (Int) -> Unit
) {
    var text by remember { mutableStateOf(initialValue.toString()) }
    AlertDialog(
        title = { Text(stringResource(R.string.timeout)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.timeout_seconds_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    text.toIntOrNull()?.let { onConfirmRequest(it) }
                }
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
private fun GeminiSafetySettingsDialog(
    platform: PlatformV2,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (String, String, String, String) -> Unit
) {
    var harassment by remember { mutableStateOf(platform.harassmentSafetyThreshold) }
    var hateSpeech by remember { mutableStateOf(platform.hateSpeechSafetyThreshold) }
    var sexuallyExplicit by remember { mutableStateOf(platform.sexuallyExplicitSafetyThreshold) }
    var dangerousContent by remember { mutableStateOf(platform.dangerousContentSafetyThreshold) }

    val options = listOf(
        GeminiSafetySettings.BLOCK_NONE,
        GeminiSafetySettings.BLOCK_ONLY_HIGH,
        GeminiSafetySettings.BLOCK_MEDIUM_AND_ABOVE,
        GeminiSafetySettings.BLOCK_LOW_AND_ABOVE
    )

    AlertDialog(
        title = { Text(stringResource(R.string.gemini_safety_settings)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = stringResource(R.string.gemini_safety_harassment), style = MaterialTheme.typography.labelMedium)
                options.forEach { opt ->
                    RadioItem(
                        title = opt,
                        description = null,
                        selected = harassment == opt,
                        onClick = { harassment = opt }
                    )
                }
                Text(text = stringResource(R.string.gemini_safety_hate_speech), style = MaterialTheme.typography.labelMedium)
                options.forEach { opt ->
                    RadioItem(
                        title = opt,
                        description = null,
                        selected = hateSpeech == opt,
                        onClick = { hateSpeech = opt }
                    )
                }
                Text(text = stringResource(R.string.gemini_safety_sexually_explicit), style = MaterialTheme.typography.labelMedium)
                options.forEach { opt ->
                    RadioItem(
                        title = opt,
                        description = null,
                        selected = sexuallyExplicit == opt,
                        onClick = { sexuallyExplicit = opt }
                    )
                }
                Text(text = stringResource(R.string.gemini_safety_dangerous_content), style = MaterialTheme.typography.labelMedium)
                options.forEach { opt ->
                    RadioItem(
                        title = opt,
                        description = null,
                        selected = dangerousContent == opt,
                        onClick = { dangerousContent = opt }
                    )
                }
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = { onConfirmRequest(harassment, hateSpeech, sexuallyExplicit, dangerousContent) }) {
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
private fun SingleChoiceDialog(
    title: String,
    options: List<RadioItem>,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                options.forEach { option ->
                    RadioItem(
                        title = option.title,
                        description = option.description,
                        selected = option.selected,
                        enabled = option.enabled,
                        onClick = {
                            option.onClick()
                            onDismissRequest()
                        }
                    )
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
