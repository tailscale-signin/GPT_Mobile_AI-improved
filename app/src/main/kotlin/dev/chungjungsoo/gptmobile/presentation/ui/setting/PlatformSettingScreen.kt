/*
 * Copyright (C) 2024-2026 Melo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.material.icons.filled.AllInbox
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.presentation.component.SettingCategory
import dev.chungjungsoo.gptmobile.presentation.component.SettingItem
import dev.chungjungsoo.gptmobile.presentation.ui.setting.PlatformSettingViewModel.Companion.DEFAULT_MAX_TOKENS_CAP
import dev.chungjungsoo.gptmobile.presentation.ui.setting.PlatformSettingViewModel.Companion.MAX_TOP_K
import dev.chungjungsoo.gptmobile.presentation.ui.setting.PlatformSettingViewModel.Companion.MIN_MAX_TOKENS
import dev.chungjungsoo.gptmobile.presentation.ui.setting.PlatformSettingViewModel.Companion.MIN_TOP_K

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSettingScreen(
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier,
    settingViewModel: PlatformSettingViewModel = hiltViewModel()
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val platform by settingViewModel.platformState.collectAsStateWithLifecycle()
    val dialogState by settingViewModel.dialogState.collectAsStateWithLifecycle()
    val isDeleted by settingViewModel.isDeleted.collectAsStateWithLifecycle()
    val toolBindingState by settingViewModel.toolBindingState.collectAsStateWithLifecycle()
    val downloadedLocalModels by settingViewModel.downloadedLocalModels.collectAsStateWithLifecycle()
    val acceleratorOptions by settingViewModel.acceleratorOptions.collectAsStateWithLifecycle()
    val userMessage by settingViewModel.userMessage.collectAsStateWithLifecycle()
    val openRouterCreditsState by settingViewModel.openRouterCreditsState.collectAsStateWithLifecycle()
    val ollamaServerState by settingViewModel.ollamaServerState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var openMcpToolsAfterPermission by remember { mutableStateOf(false) }
    var showBatchUrlDialog by remember { mutableStateOf(false) }
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && openMcpToolsAfterPermission) {
            settingViewModel.openMcpToolsDialog()
        } else if (!granted) {
            Toast.makeText(context, R.string.local_network_permission_required, Toast.LENGTH_SHORT).show()
        }
        openMcpToolsAfterPermission = false
    }

    LaunchedEffect(isDeleted) {
        if (isDeleted) {
            onNavigationClick()
        }
    }

    LaunchedEffect(userMessage) {
        userMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            settingViewModel.consumeUserMessage()
        }
    }

    platform?.let { platformData ->
        Scaffold(
            modifier = modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = {
                        Text(text = platformData.name)
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigationClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = settingViewModel::openDeleteDialog) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.delete)
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        ) { innerPadding ->
            val isLocalPlatform = platformData.compatibleType == ClientType.LITERT_LM
            val isOllamaPlatform = platformData.compatibleType == ClientType.OLLAMA
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
            ) {
                PreferenceListSwitch(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.enable_platform),
                    description = stringResource(R.string.enable_platform_description),
                    icon = Icons.Filled.Refresh,
                    isChecked = platformData.enabled,
                    onCheckedChange = { settingViewModel.toggleEnabled() }
                )

                SettingCategory(
                    text = stringResource(R.string.general)
                )

                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.platform_name),
                    description = platformData.name,
                    enabled = platformData.enabled,
                    onItemClick = settingViewModel::openPlatformNameDialog,
                    showTrailingIcon = false,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Label,
                            contentDescription = stringResource(R.string.platform_name)
                        )
                    }
                )
                if (!isLocalPlatform) {
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.api_url),
                        description = platformData.apiUrl,
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openApiUrlDialog,
                        showTrailingIcon = false,
                        showLeadingIcon = true,
                        leadingIcon = {
                            Icon(
                                ImageVector.vectorResource(id = R.drawable.ic_link),
                                contentDescription = stringResource(R.string.api_url)
                            )
                        }
                    )
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.api_key),
                        description = if (platformData.token.isNullOrEmpty()) {
                            stringResource(R.string.not_set)
                        } else {
                            "••••••••"
                        },
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openApiTokenDialog,
                        showTrailingIcon = false,
                        showLeadingIcon = true,
                        leadingIcon = {
                            Icon(
                                ImageVector.vectorResource(id = R.drawable.ic_key),
                                contentDescription = stringResource(R.string.api_key)
                            )
                        }
                    )
                }

                if (isOllamaPlatform) {
                    OllamaServerCard(
                        serverUrl = platformData.apiUrl.orEmpty(),
                        uiState = ollamaServerState,
                        currentModel = platformData.model,
                        onTestConnection = settingViewModel::checkOllamaServer,
                        onSelectModel = settingViewModel::updateApiModel
                    )
                }

                if (platformData.compatibleType == ClientType.OPENROUTER && !platformData.token.isNullOrBlank()) {
                    FancyOpenRouterCreditsCard(
                        uiState = openRouterCreditsState,
                        onRefresh = { settingViewModel.refreshOpenRouterCredits(forceRefresh = true) }
                    )
                }

                val modelDescription = downloadedLocalModels
                    .firstOrNull { it.catalogEntryId == platformData.model }
                    ?.displayName ?: platformData.model
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.api_model),
                    description = modelDescription,
                    enabled = platformData.enabled,
                    onItemClick = settingViewModel::openApiModelDialog,
                    showTrailingIcon = false,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            ImageVector.vectorResource(id = R.drawable.ic_model),
                            contentDescription = stringResource(R.string.api_model)
                        )
                    }
                )
                val isReasoningDisabled = platformData.compatibleType == ClientType.OPENAI && platformData.reasoning
                val notSetText = stringResource(R.string.not_set)
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.temperature),
                    description = platformData.temperature?.toString() ?: notSetText,
                    enabled = platformData.enabled && !isReasoningDisabled,
                    onItemClick = settingViewModel::openTemperatureDialog,
                    showTrailingIcon = false,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            ImageVector.vectorResource(id = R.drawable.ic_temperature),
                            contentDescription = stringResource(R.string.temperature)
                        )
                    }
                )
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.top_p),
                    description = platformData.topP?.toString() ?: notSetText,
                    enabled = platformData.enabled,
                    onItemClick = settingViewModel::openTopPDialog,
                    showTrailingIcon = false,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            ImageVector.vectorResource(id = R.drawable.ic_top_p),
                            contentDescription = stringResource(R.string.top_p)
                        )
                    }
                )
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.top_k),
                    description = platformData.topK?.toString() ?: notSetText,
                    enabled = platformData.enabled,
                    onItemClick = settingViewModel::openTopKDialog,
                    showTrailingIcon = false,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            ImageVector.vectorResource(id = R.drawable.ic_top_p),
                            contentDescription = stringResource(R.string.top_k)
                        )
                    }
                )
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.max_tokens),
                    description = platformData.maxTokens?.toString() ?: notSetText,
                    enabled = platformData.enabled,
                    onItemClick = settingViewModel::openMaxTokensDialog,
                    showTrailingIcon = false,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Calculate,
                            contentDescription = stringResource(R.string.max_tokens)
                        )
                    }
                )
                if (isLocalPlatform) {
                    val currentAccelerator = platformData.accelerator
                    val acceleratorLabel = acceleratorOptions
                        .firstOrNull { it.id == currentAccelerator }
                        ?.label ?: currentAccelerator
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.accelerator),
                        description = acceleratorLabel,
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openAcceleratorDialog,
                        showTrailingIcon = false,
                        showLeadingIcon = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Speed,
                                contentDescription = stringResource(R.string.accelerator)
                            )
                        }
                    )
                }
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.system_prompt),
                    description = if (platformData.systemPrompt.isNullOrEmpty()) {
                        stringResource(R.string.not_set)
                    } else {
                        platformData.systemPrompt
                    },
                    enabled = platformData.enabled,
                    onItemClick = settingViewModel::openSystemPromptDialog,
                    showTrailingIcon = false,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_system_prompt),
                            contentDescription = stringResource(R.string.system_prompt)
                        )
                    }
                )
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.timeout),
                    description = "${platformData.timeout / 1000}s",
                    enabled = platformData.enabled,
                    onItemClick = settingViewModel::openTimeoutDialog,
                    showTrailingIcon = false,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_timer),
                            contentDescription = stringResource(R.string.timeout)
                        )
                    }
                )
                if (platformData.compatibleType == ClientType.GEMINI) {
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.safety_settings),
                        description = if (platformData.geminiSafety.isNullOrBlank()) {
                            stringResource(R.string.default_label)
                        } else {
                            stringResource(R.string.custom)
                        },
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openGeminiSafetyDialog,
                        showTrailingIcon = true,
                        showLeadingIcon = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Security,
                                contentDescription = stringResource(R.string.safety_settings)
                            )
                        }
                    )
                }
                if (platformData.compatibleType == ClientType.OPENROUTER) {
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.openrouter_advanced_settings),
                        description = if (platformData.openRouterRouting.isNullOrBlank()) {
                            stringResource(R.string.default_label)
                        } else {
                            stringResource(R.string.custom)
                        },
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openOpenRouterSettingsDialog,
                        showTrailingIcon = true,
                        showLeadingIcon = true,
                        leadingIcon = {
                            Icon(
                                ImageVector.vectorResource(id = R.drawable.ic_instructions),
                                contentDescription = stringResource(R.string.openrouter_advanced_settings)
                            )
                        }
                    )
                }
                if (platformData.compatibleType == ClientType.OLLAMA) {
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.ollama_advanced_options),
                        description = if (platformData.ollamaOptions.isNullOrBlank()) {
                            stringResource(R.string.default_label)
                        } else {
                            stringResource(R.string.custom)
                        },
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openOllamaAdvancedDialog,
                        showTrailingIcon = true,
                        showLeadingIcon = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = stringResource(R.string.ollama_advanced_options)
                            )
                        }
                    )
                }
                if (!isLocalPlatform) {
                    ExtendedThinkingSwitch(
                        modifier = Modifier.height(64.dp),
                        enabled = platformData.enabled,
                        isChecked = platformData.reasoning,
                        onCheckedChange = { settingViewModel.toggleReasoning() }
                    )

                    // Batch API Mode for OpenAI & Anthropic
                    if (platformData.compatibleType == ClientType.OPENAI || platformData.compatibleType == ClientType.ANTHROPIC) {
                        PreferenceListSwitch(
                            modifier = Modifier.height(64.dp),
                            title = stringResource(R.string.batch_mode),
                            description = stringResource(R.string.batch_mode_description),
                            icon = Icons.Default.AllInbox,
                            enabled = platformData.enabled,
                            isChecked = platformData.batchMode,
                            onCheckedChange = {
                                settingViewModel.updatePlatform(platformData.copy(batchMode = it))
                            }
                        )
                        if (platformData.batchMode) {
                            SettingItem(
                                modifier = Modifier.height(64.dp),
                                title = stringResource(R.string.batch_api_url),
                                description = platformData.batchApiUrl ?: stringResource(R.string.not_set),
                                enabled = platformData.enabled,
                                onItemClick = { showBatchUrlDialog = true },
                                showTrailingIcon = false,
                                showLeadingIcon = true,
                                leadingIcon = {
                                    Icon(
                                        ImageVector.vectorResource(id = R.drawable.ic_link),
                                        contentDescription = stringResource(R.string.batch_api_url)
                                    )
                                }
                            )
                        }
                    }

                    PreferenceListSwitch(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.stream_response),
                        description = stringResource(R.string.stream_response_description),
                        icon = Icons.Filled.Memory,
                        enabled = platformData.enabled,
                        isChecked = platformData.stream,
                        onCheckedChange = { settingViewModel.toggleStream() }
                    )
                }

                SettingCategory(
                    text = stringResource(R.string.tool_connections_title)
                )

                PreferenceListSwitch(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.disable_all_tools),
                    description = stringResource(R.string.disable_all_tools_desc),
                    icon = Icons.Filled.Build,
                    enabled = platformData.enabled,
                    isChecked = platformData.disableAllTools,
                    onCheckedChange = { settingViewModel.toggleDisableAllTools() }
                )

                PreferenceListSwitch(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.disable_remote_tools),
                    description = stringResource(R.string.disable_remote_tools_desc),
                    icon = Icons.Filled.Build,
                    enabled = platformData.enabled && !platformData.disableAllTools,
                    isChecked = platformData.disableRemoteTools,
                    onCheckedChange = { settingViewModel.toggleDisableRemoteTools() }
                )

                PreferenceListSwitch(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.disable_local_tools),
                    description = stringResource(R.string.disable_local_tools_desc),
                    icon = Icons.Filled.Build,
                    enabled = platformData.enabled && !platformData.disableAllTools,
                    isChecked = platformData.disableLocalTools,
                    onCheckedChange = { settingViewModel.toggleDisableLocalTools() }
                )

                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.tool_connection_web_search),
                    description = toolBindingState.selectedSearchConnectionName ?: stringResource(R.string.none_label),
                    enabled = platformData.enabled && !platformData.disableAllTools && !platformData.disableRemoteTools,
                    onItemClick = settingViewModel::openSearchBackendDialog,
                    showTrailingIcon = false,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_globe),
                            contentDescription = stringResource(R.string.tool_connection_web_search)
                        )
                    }
                )

                PreferenceListSwitch(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.builtin_tool_read_url),
                    description = stringResource(R.string.builtin_tool_read_url_desc),
                    icon = Icons.Filled.Build,
                    enabled = platformData.enabled && !platformData.disableAllTools && !platformData.disableRemoteTools,
                    isChecked = toolBindingState.readUrlEnabled,
                    onCheckedChange = settingViewModel::setReadUrlEnabled
                )

                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.tool_binding_mcp_servers),
                    description = "${toolBindingState.selectedMcpTools.size} tools selected",
                    enabled = platformData.enabled && !platformData.disableAllTools && !platformData.disableRemoteTools,
                    onItemClick = {
                        val permissionNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.NEARBY_WIFI_DEVICES
                            ) != PackageManager.PERMISSION_GRANTED

                        if (permissionNeeded) {
                            openMcpToolsAfterPermission = true
                            localNetworkPermissionLauncher.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                        } else {
                            settingViewModel.openMcpToolsDialog()
                        }
                    },
                    showTrailingIcon = false,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_bolt),
                            contentDescription = stringResource(R.string.tool_binding_mcp_servers)
                        )
                    }
                )

                PlatformMaxToolCallsSettingHost(
                    platform = platformData,
                    onMaxToolCallsChange = { maxCalls ->
                        settingViewModel.updatePlatform(platformData.copy(maxToolCalls = maxCalls))
                    },
                    enabled = platformData.enabled
                )
            }
        }
    }

    if (dialogState.isPlatformNameDialogOpen) {
        ChangeNameDialog(
            currentName = platform?.name ?: "",
            onDismiss = settingViewModel::closePlatformNameDialog,
            onConfirm = settingViewModel::updatePlatformName
        )
    }

    if (dialogState.isApiUrlDialogOpen) {
        ChangeUrlDialog(
            currentUrl = platform?.apiUrl ?: "",
            onDismiss = settingViewModel::closeApiUrlDialog,
            onConfirm = settingViewModel::updateApiUrl
        )
    }

    if (dialogState.isApiTokenDialogOpen) {
        ChangeTokenDialog(
            currentToken = platform?.token ?: "",
            onDismiss = settingViewModel::closeApiTokenDialog,
            onConfirm = settingViewModel::updateApiToken
        )
    }

    if (dialogState.isApiModelDialogOpen) {
        ChangeModelDialog(
            currentModel = platform?.model ?: "",
            clientType = platform?.compatibleType ?: ClientType.OPENAI,
            onDismiss = settingViewModel::closeApiModelDialog,
            onConfirm = settingViewModel::updateApiModel
        )
    }

    if (dialogState.isTemperatureDialogOpen) {
        ChangeTemperatureDialog(
            currentTemperature = platform?.temperature ?: 1.0f,
            onDismiss = settingViewModel::closeTemperatureDialog,
            onConfirm = settingViewModel::updateTemperature
        )
    }

    if (dialogState.isTopPDialogOpen) {
        ChangeTopPDialog(
            currentTopP = platform?.topP ?: 1.0f,
            onDismiss = settingViewModel::closeTopPDialog,
            onConfirm = settingViewModel::updateTopP
        )
    }

    if (dialogState.isTopKDialogOpen) {
        val defaultTopK = if (platform?.compatibleType == ClientType.LITERT_LM) {
            localSamplingDefaults().topK
        } else {
            MIN_TOP_K
        }
        val currentTopK = platform?.topK ?: defaultTopK
        ChangeTopKDialog(
            currentTopK = currentTopK.coerceIn(MIN_TOP_K, MAX_TOP_K),
            onDismiss = settingViewModel::closeTopKDialog,
            onConfirm = settingViewModel::updateTopK
        )
    }

    if (dialogState.isMaxTokensDialogOpen) {
        val maxTokensCap = settingViewModel.maxTokensCap()
        val defaultTokens = if (platform?.compatibleType == ClientType.LITERT_LM) {
            localSamplingDefaults().maxTokens.coerceAtMost(maxTokensCap)
        } else {
            maxTokensCap
        }
        val currentTokens = platform?.maxTokens ?: defaultTokens
        ChangeMaxTokensDialog(
            currentMaxTokens = currentTokens.coerceIn(MIN_MAX_TOKENS, maxTokensCap),
            maxTokensCap = maxTokensCap,
            onDismiss = settingViewModel::closeMaxTokensDialog,
            onConfirm = settingViewModel::updateMaxTokens
        )
    }

    if (dialogState.isAcceleratorDialogOpen) {
        ChangeAcceleratorDialog(
            currentAccelerator = platform?.accelerator ?: "auto",
            options = acceleratorOptions,
            onDismiss = settingViewModel::closeAcceleratorDialog,
            onConfirm = settingViewModel::updateAccelerator
        )
    }

    if (dialogState.isSystemPromptDialogOpen) {
        ChangeSystemPromptDialog(
            currentPrompt = platform?.systemPrompt ?: "",
            onDismiss = settingViewModel::closeSystemPromptDialog,
            onConfirm = settingViewModel::updateSystemPrompt
        )
    }

    if (dialogState.isTimeoutDialogOpen) {
        ChangeTimeoutDialog(
            currentTimeout = (platform?.timeout ?: 30000) / 1000,
            onDismiss = settingViewModel::closeTimeoutDialog,
            onConfirm = { settingViewModel.updateTimeout(it * 1000) }
        )
    }

    if (dialogState.isGeminiSafetyDialogOpen) {
        GeminiSafetySettingsDialog(
            currentSettingsJson = platform?.geminiSafety,
            onDismiss = settingViewModel::closeGeminiSafetyDialog,
            onConfirm = settingViewModel::updateGeminiSafety
        )
    }

    if (dialogState.isOpenRouterSettingsDialogOpen) {
        OpenRouterAdvancedSettingsDialog(
            currentRoutingJson = platform?.openRouterRouting,
            onDismiss = settingViewModel::closeOpenRouterSettingsDialog,
            onConfirm = settingViewModel::updateOpenRouterRouting
        )
    }

    if (dialogState.isOllamaAdvancedDialogOpen) {
        OllamaAdvancedSettingsDialog(
            currentOptionsJson = platform?.ollamaOptions,
            onDismiss = settingViewModel::closeOllamaAdvancedDialog,
            onConfirm = settingViewModel::updateOllamaOptions
        )
    }

    if (dialogState.isDeleteDialogOpen) {
        DeletePlatformDialog(
            onDismiss = settingViewModel::closeDeleteDialog,
            onConfirm = settingViewModel::deletePlatform
        )
    }

    if (toolBindingState.isSearchBackendDialogOpen) {
        SearchBackendSelectionDialog(
            connections = toolBindingState.searchConnections,
            selectedConnectionUid = toolBindingState.selectedSearchConnectionUid,
            onDismiss = settingViewModel::closeSearchBackendDialog,
            onSelect = settingViewModel::selectSearchBackend
        )
    }

    if (toolBindingState.isMcpToolsDialogOpen) {
        McpToolSelectionDialog(
            options = toolBindingState.mcpToolOptions,
            selectedTools = toolBindingState.pendingMcpTools,
            isLoading = toolBindingState.isMcpToolsLoading,
            errorMessage = toolBindingState.errorMessage,
            onDismiss = settingViewModel::closeMcpToolsDialog,
            onToggleTool = settingViewModel::toggleMcpTool,
            onSave = settingViewModel::saveMcpTools
        )
    }

    if (showBatchUrlDialog) {
        ChangeBatchUrlDialog(
            currentUrl = platform?.batchApiUrl.orEmpty(),
            onDismiss = { showBatchUrlDialog = false },
            onConfirm = { newUrl ->
                platform?.let {
                    settingViewModel.updatePlatform(it.copy(batchApiUrl = newUrl.ifBlank { null }))
                }
                showBatchUrlDialog = false
            }
        )
    }
}

@Composable
private fun PreferenceListSwitch(
    title: String,
    description: String,
    icon: ImageVector,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = isChecked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        }
        Switch(
            checked = isChecked,
            onCheckedChange = null,
            enabled = enabled
        )
    }
}

@Composable
private fun ExtendedThinkingSwitch(
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    PreferenceListSwitch(
        modifier = modifier,
        title = stringResource(R.string.extended_thinking),
        description = stringResource(R.string.extended_thinking_description),
        icon = ImageVector.vectorResource(id = R.drawable.ic_brain),
        enabled = enabled,
        isChecked = isChecked,
        onCheckedChange = onCheckedChange
    )
}
