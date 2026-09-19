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
private fun LlamaAdvancedSettingsDialog(
    initialSettings: dev.chungjungsoo.gptmobile.llama.AdvancedSettings,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (dev.chungjungsoo.gptmobile.llama.AdvancedSettings) -> Unit,
    onResetRequest: () -> Unit
) {
    var serverUrl by remember { mutableStateOf(initialSettings.serverUrl) }
    var apiTimeoutText by remember { mutableStateOf(initialSettings.apiTimeout.toString()) }
    var routerModeEnabled by remember { mutableStateOf(initialSettings.routerModeEnabled) }
    var selectedModel by remember { mutableStateOf(initialSettings.selectedModel) }
    var nGpuLayersText by remember { mutableStateOf(initialSettings.nGpuLayers.toString()) }
    var nThreadsText by remember { mutableStateOf(initialSettings.nThreads.toString()) }
    var nBatchText by remember { mutableStateOf(initialSettings.nBatch.toString()) }
    var nCtxText by remember { mutableStateOf(initialSettings.nCtx.toString()) }
    var nParallelText by remember { mutableStateOf(initialSettings.nParallel.toString()) }
    var kvUnified by remember { mutableStateOf(initialSettings.kvUnified) }
    var temperatureText by remember { mutableStateOf(initialSettings.temperature.toString()) }
    var topPText by remember { mutableStateOf(initialSettings.topP.toString()) }
    var topKText by remember { mutableStateOf(initialSettings.topK.toString()) }
    var repeatPenaltyText by remember { mutableStateOf(initialSettings.repeatPenalty.toString()) }
    var seedText by remember { mutableStateOf(initialSettings.seed.toString()) }
    var stopTokensText by remember { mutableStateOf(initialSettings.stop) }
    var useMmap by remember { mutableStateOf(initialSettings.useMmap) }
    var useMlock by remember { mutableStateOf(initialSettings.useMlock) }
    var useFlashAttn by remember { mutableStateOf(initialSettings.useFlashAttn) }
    var verbose by remember { mutableStateOf(initialSettings.verbose) }

    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.llama_advanced_settings)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.llama_advanced_settings_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Router Mode",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Enable multi-model routing via router endpoint",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = routerModeEnabled,
                        onCheckedChange = { routerModeEnabled = it }
                    )
                }

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = selectedModel,
                    onValueChange = { selectedModel = it },
                    label = { Text("Model Name / Alias") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = nGpuLayersText,
                    onValueChange = { nGpuLayersText = it },
                    label = { Text("GPU Layers (n_gpu_layers)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = nThreadsText,
                    onValueChange = { nThreadsText = it },
                    label = { Text("Threads (n_threads)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = nCtxText,
                    onValueChange = { nCtxText = it },
                    label = { Text("Context Size (n_ctx)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = nBatchText,
                    onValueChange = { nBatchText = it },
                    label = { Text("Batch Size (n_batch)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = nParallelText,
                    onValueChange = { nParallelText = it },
                    label = { Text("Parallel Slots (n_parallel)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = apiTimeoutText,
                    onValueChange = { apiTimeoutText = it },
                    label = { Text("API Timeout (ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Unified KV Cache")
                    Switch(
                        checked = kvUnified,
                        onCheckedChange = { kvUnified = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Flash Attention")
                    Switch(
                        checked = useFlashAttn,
                        onCheckedChange = { useFlashAttn = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Memory Map (mmap)")
                    Switch(
                        checked = useMmap,
                        onCheckedChange = { useMmap = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Memory Lock (mlock)")
                    Switch(
                        checked = useMlock,
                        onCheckedChange = { useMlock = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Verbose Logging")
                    Switch(
                        checked = verbose,
                        onCheckedChange = { verbose = it }
                    )
                }

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
                    label = { Text("Repeat Penalty") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = seedText,
                    onValueChange = { seedText = it },
                    label = { Text("Seed (-1 for random)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = stopTokensText,
                    onValueChange = { stopTokensText = it },
                    label = { Text("Stop Tokens") },
                    placeholder = { Text("stop1, stop2") },
                    singleLine = true
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    val updated = initialSettings.copy(
                        serverUrl = serverUrl.trim().ifEmpty { initialSettings.serverUrl },
                        apiTimeout = apiTimeoutText.toIntOrNull() ?: initialSettings.apiTimeout,
                        routerModeEnabled = routerModeEnabled,
                        selectedModel = selectedModel.trim().ifEmpty { initialSettings.selectedModel },
                        nGpuLayers = nGpuLayersText.toIntOrNull() ?: initialSettings.nGpuLayers,
                        nThreads = nThreadsText.toIntOrNull() ?: initialSettings.nThreads,
                        nBatch = nBatchText.toIntOrNull() ?: initialSettings.nBatch,
                        nCtx = nCtxText.toIntOrNull() ?: initialSettings.nCtx,
                        nParallel = nParallelText.toIntOrNull() ?: initialSettings.nParallel,
                        kvUnified = kvUnified,
                        temperature = temperatureText.toFloatOrNull() ?: initialSettings.temperature,
                        topP = topPText.toFloatOrNull() ?: initialSettings.topP,
                        topK = topKText.toIntOrNull() ?: initialSettings.topK,
                        repeatPenalty = repeatPenaltyText.toFloatOrNull() ?: initialSettings.repeatPenalty,
                        seed = seedText.toLongOrNull() ?: initialSettings.seed,
                        stop = stopTokensText.trim(),
                        useMmap = useMmap,
                        useMlock = useMlock,
                        useFlashAttn = useFlashAttn,
                        verbose = verbose
                    )
                    onConfirmRequest(updated)
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onResetRequest) {
                Text(stringResource(R.string.reset))
            }
        }
    )
}
