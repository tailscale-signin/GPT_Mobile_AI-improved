package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.localruntime.LocalAccelerators
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ApiCredentialRotator
import dev.chungjungsoo.gptmobile.presentation.ui.openrouter.OpenRouterModelPickerDialog

private const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSettingScreen(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit,
    onNavigateToLocalModels: () -> Unit = {},
    onNavigateToMcpTools: () -> Unit = {},
    settingViewModel: PlatformSettingViewModel = hiltViewModel()
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val platformData by settingViewModel.platformData.collectAsStateWithLifecycle()
    val dialogState by settingViewModel.dialogState.collectAsStateWithLifecycle()
    val toolBindingState by settingViewModel.toolBindingState.collectAsStateWithLifecycle()
    val downloadedLocalModels by settingViewModel.downloadedLocalModels.collectAsStateWithLifecycle()
    val acceleratorOptions by settingViewModel.acceleratorOptions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var openMcpToolsAfterPermission by remember { mutableStateOf(false) }

    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (openMcpToolsAfterPermission) {
                openMcpToolsAfterPermission = false
                onNavigateToMcpTools()
            }
        } else {
            openMcpToolsAfterPermission = false
            Toast.makeText(
                context,
                context.getString(R.string.local_network_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        settingViewModel.onComplete.collect { isDeleted ->
            if (isDeleted) {
                onNavigateBack()
            }
        }
    }

    Scaffold(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PlatformTopAppBar(
                title = platformData.name,
                onNavigationClick = onNavigateBack,
                onDeleteClick = settingViewModel::openDeleteDialog,
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            val isLocalPlatform = platformData.compatibleType == ClientType.LOCAL

            PreferenceSwitchWithContainer(
                title = stringResource(R.string.enable_platform),
                isChecked = platformData.enabled,
                onClick = settingViewModel::togglePlatformEnabled
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                SettingCategory(title = stringResource(R.string.basic))
                SettingItem(
                    title = stringResource(R.string.platform_name),
                    description = platformData.name,
                    icon = Icons.AutoMirrored.Filled.Label,
                    onClick = settingViewModel::openPlatformNameDialog,
                    showTrailingIcon = false
                )
                if (!isLocalPlatform) {
                    SettingItem(
                        title = stringResource(R.string.api_url),
                        description = platformData.apiUrl,
                        icon = Icons.Filled.Language,
                        onClick = settingViewModel::openApiUrlDialog,
                        showTrailingIcon = false
                    )
                    SettingItem(
                        title = stringResource(R.string.api_key),
                        description = ApiCredentialRotator.formatDisplayKeys(platformData.token),
                        icon = painterResource(id = R.drawable.ic_key),
                        onClick = settingViewModel::openApiTokenDialog,
                        showTrailingIcon = false
                    )
                }

                SettingCategory(title = stringResource(R.string.model))
                SettingItem(
                    title = stringResource(R.string.api_model),
                    description = if (isLocalPlatform) {
                        downloadedLocalModels.firstOrNull { it.catalogEntry.id == platformData.model }?.catalogEntry?.title
                            ?: platformData.model
                    } else {
                        platformData.model
                    },
                    icon = painterResource(id = R.drawable.ic_model),
                    onClick = settingViewModel::openApiModelDialog,
                    showTrailingIcon = false
                )

                if (isLocalPlatform) {
                    SettingItem(
                        title = stringResource(R.string.accelerator_setting),
                        description = acceleratorLabel(platformData.accelerator),
                        icon = Icons.Outlined.Speed,
                        onClick = settingViewModel::openAcceleratorDialog,
                        showTrailingIcon = false
                    )
                }

                if (platformData.compatibleType == ClientType.OPENROUTER) {
                    SettingItem(
                        title = stringResource(R.string.openrouter_advanced_settings),
                        description = stringResource(R.string.openrouter_advanced_settings_description),
                        icon = Icons.Outlined.Tune,
                        onClick = settingViewModel::openOpenRouterSettingsDialog,
                        showTrailingIcon = false
                    )
                }

                if (platformData.compatibleType == ClientType.OLLAMA) {
                    SettingItem(
                        title = stringResource(R.string.ollama_advanced_options),
                        description = stringResource(R.string.ollama_advanced_options_description),
                        icon = Icons.Outlined.Tune,
                        onClick = settingViewModel::openOllamaAdvancedDialog,
                        showTrailingIcon = false
                    )
                }

                SettingCategory(title = stringResource(R.string.parameters))
                if (!isLocalPlatform) {
                    SettingItem(
                        title = stringResource(R.string.timeout_setting),
                        description = stringResource(R.string.timeout_seconds_value, platformData.timeout),
                        icon = painterResource(id = R.drawable.ic_hourglass),
                        onClick = settingViewModel::openTimeoutDialog,
                        showTrailingIcon = false
                    )
                }
                SettingItem(
                    title = stringResource(R.string.temperature_setting),
                    description = platformData.temperature?.let { "%.1f".format(it) } ?: stringResource(R.string.not_set),
                    icon = painterResource(id = R.drawable.ic_thermometer),
                    onClick = settingViewModel::openTemperatureDialog,
                    showTrailingIcon = false
                )
                SettingItem(
                    title = stringResource(R.string.top_p_setting),
                    description = platformData.topP?.let { "%.1f".format(it) } ?: stringResource(R.string.not_set),
                    icon = painterResource(id = R.drawable.ic_percent),
                    onClick = settingViewModel::openTopPDialog,
                    showTrailingIcon = false
                )
                if (isLocalPlatform) {
                    SettingItem(
                        title = stringResource(R.string.top_k_setting),
                        description = platformData.topK?.toString() ?: stringResource(R.string.not_set),
                        icon = Icons.Outlined.Numbers,
                        onClick = settingViewModel::openTopKDialog,
                        showTrailingIcon = false
                    )
                    SettingItem(
                        title = stringResource(R.string.max_tokens_setting),
                        description = platformData.maxTokens?.toString() ?: stringResource(R.string.not_set),
                        icon = Icons.Filled.Calculate,
                        onClick = settingViewModel::openMaxTokensDialog,
                        showTrailingIcon = false
                    )
                }
                SettingItem(
                    title = stringResource(R.string.system_prompt_setting),
                    description = platformData.systemPrompt ?: stringResource(R.string.not_set),
                    icon = painterResource(id = R.drawable.ic_system_prompt),
                    onClick = settingViewModel::openSystemPromptDialog,
                    showTrailingIcon = false
                )

                if (platformData.compatibleType == ClientType.GEMINI) {
                    SettingCategory(title = stringResource(R.string.gemini_safety_settings))
                    SettingItem(
                        title = stringResource(R.string.gemini_safety_settings),
                        description = stringResource(R.string.gemini_safety_settings_description),
                        icon = painterResource(id = R.drawable.ic_shield),
                        onClick = settingViewModel::openGeminiSafetySettingsDialog,
                        showTrailingIcon = false
                    )
                }

                if (platformData.compatibleType == ClientType.ANTHROPIC) {
                    SettingCategory(title = stringResource(R.string.advanced))
                    ExtendedThinkingSwitch(
                        enabled = platformData.enabled,
                        isChecked = platformData.extendedThinking,
                        onCheckedChange = { settingViewModel.toggleExtendedThinking() }
                    )
                }

                SettingCategory(title = stringResource(R.string.tools))
                PlatformToolEnableSwitch(
                    title = stringResource(R.string.disable_all_tools),
                    description = stringResource(R.string.disable_all_tools_description),
                    isChecked = platformData.disableTools,
                    onCheckedChange = { settingViewModel.toggleDisableTools() }
                )
                PlatformToolEnableSwitch(
                    title = stringResource(R.string.disable_remote_tools),
                    description = stringResource(R.string.disable_remote_tools_description),
                    isChecked = platformData.disableRemoteTools,
                    enabled = !platformData.disableTools,
                    onCheckedChange = { settingViewModel.toggleDisableRemoteTools() }
                )
                PlatformToolEnableSwitch(
                    title = stringResource(R.string.disable_local_tools),
                    description = stringResource(R.string.disable_local_tools_description),
                    isChecked = platformData.disableLocalTools,
                    enabled = !platformData.disableTools,
                    onCheckedChange = { settingViewModel.toggleDisableLocalTools() }
                )

                SettingItem(
                    title = stringResource(R.string.mcp_server),
                    description = stringResource(
                        R.string.active_mcp_tools_count,
                        toolBindingState.enabledMcpToolsCount
                    ),
                    icon = Icons.Filled.Build,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                            ContextCompat.checkSelfPermission(context, PERMISSION_ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
                        ) {
                            openMcpToolsAfterPermission = true
                            localNetworkPermissionLauncher.launch(PERMISSION_ACCESS_LOCAL_NETWORK)
                        } else {
                            onNavigateToMcpTools()
                        }
                    },
                    showTrailingIcon = true,
                    showLeadingIcon = false
                )

                PlatformMaxToolCallsSettingHost(settingViewModel)

                PlatformNameDialog(dialogState, platformData.name, settingViewModel)
                if (!isLocalPlatform) {
                    APIUrlDialog(dialogState, platformData.apiUrl, settingViewModel)
                    APIKeyDialog(dialogState, platformData.token, settingViewModel)
                    if (platformData.compatibleType == ClientType.OPENROUTER && dialogState.isApiModelDialogOpen) {
                        OpenRouterModelPickerDialog(
                            currentModel = platformData.model,
                            onDismiss = settingViewModel::closeApiModelDialog,
                            onModelSelected = settingViewModel::updateApiModel
                        )
                    } else {
                        ModelDialog(dialogState, platformData.model, settingViewModel)
                    }
                    TimeoutDialog(dialogState, platformData.timeout, settingViewModel)
                } else {
                    LocalModelDialog(
                        dialogState = dialogState,
                        selectedCatalogEntryId = platformData.model,
                        models = downloadedLocalModels,
                        onNavigateToLocalModels = onNavigateToLocalModels,
                        settingViewModel = settingViewModel
                    )
                    TopKDialog(dialogState, platformData.topK, settingViewModel)
                    MaxTokensDialog(dialogState, platformData.maxTokens, settingViewModel)
                    AcceleratorDialog(dialogState, platformData.accelerator, acceleratorOptions, settingViewModel)
                }
                TemperatureDialog(dialogState, platformData.temperature, settingViewModel)
                TopPDialog(dialogState, platformData.topP, settingViewModel)
                SystemPromptDialog(dialogState, platformData.systemPrompt ?: "", settingViewModel)
                GeminiSafetySettingsDialog(dialogState, platformData, settingViewModel)
                OpenRouterAdvancedSettingsDialog(dialogState, platformData.openRouterRouting, settingViewModel)
                OllamaAdvancedSettingsDialog(dialogState, platformData.ollamaOptions, settingViewModel)
                DeletePlatformDialog(dialogState, settingViewModel)
                toolBindingState.errorMessage?.let { message ->
                    AlertDialog(
                        title = { Text(stringResource(R.string.error)) },
                        text = { Text(message) },
                        onDismissRequest = settingViewModel::clearToolError,
                        confirmButton = {
                            TextButton(onClick = settingViewModel::clearToolError) {
                                Text(stringResource(R.string.close))
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformTopAppBar(
    title: String,
    onNavigationClick: () -> Unit,
    onDeleteClick: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior
) {
    var expanded by remember { mutableStateOf(false) }

    LargeTopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigationClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.arrow_icon)
                )
            }
        },
        actions = {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.options)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    onClick = {
                        expanded = false
                        onDeleteClick()
                    }
                )
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@Composable
fun PreferenceSwitchWithContainer(
    title: String,
    isChecked: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .toggleable(
                value = isChecked,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Switch,
                onValueChange = { onClick() }
            ),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        },
        trailingContent = {
            Switch(
                checked = isChecked,
                onCheckedChange = null,
                thumbContent = {
                    if (isChecked) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize)
                        )
                    }
                }
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    )
}

@Composable
fun PreferenceListSwitch(
    modifier: Modifier = Modifier,
    title: String,
    description: String? = null,
    icon: ImageVector,
    enabled: Boolean = true,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = isChecked,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        },
        supportingContent = description?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        },
        trailingContent = {
            Switch(
                checked = isChecked,
                onCheckedChange = null,
                enabled = enabled,
                thumbContent = {
                    if (isChecked) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize)
                        )
                    }
                }
            )
        }
    )
}

@Composable
fun ExtendedThinkingSwitch(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    PreferenceListSwitch(
        modifier = modifier,
        title = stringResource(R.string.extended_thinking),
        description = stringResource(R.string.extended_thinking_description),
        icon = ImageVector.vectorResource(id = R.drawable.ic_extended_thinking),
        enabled = enabled,
        isChecked = isChecked,
        onCheckedChange = onCheckedChange
    )
}

@Composable
private fun acceleratorLabel(accelerator: String?): String = when (LocalAccelerators.normalize(accelerator)) {
    LocalAccelerators.GPU -> stringResource(R.string.accelerator_gpu)
    LocalAccelerators.NPU -> stringResource(R.string.accelerator_npu)
    else -> stringResource(R.string.accelerator_cpu)
}
