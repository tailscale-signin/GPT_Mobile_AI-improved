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
            timeout = timeout,
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
