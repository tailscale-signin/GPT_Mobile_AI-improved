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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.localruntime.LocalAccelerators
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.presentation.common.RadioItem
import dev.chungjungsoo.gptmobile.presentation.common.SettingItem
import dev.chungjungsoo.gptmobile.util.PERMISSION_ACCESS_LOCAL_NETWORK
import dev.chungjungsoo.gptmobile.util.formatPlatformTimeout
import dev.chungjungsoo.gptmobile.util.pinnedExitUntilCollapsedScrollBehavior
import dev.chungjungsoo.gptmobile.util.requiresLocalNetworkAccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSettingScreen(
    modifier: Modifier = Modifier,
    settingViewModel: PlatformSettingViewModel = hiltViewModel(),
    onNavigationClick: () -> Unit = {},
    onNavigateToLocalModels: () -> Unit = {},
    onNavigateToMcpTools: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val scrollBehavior = pinnedExitUntilCollapsedScrollBehavior(
        canScroll = { scrollState.canScrollForward || scrollState.canScrollBackward }
    )
    val platform by settingViewModel.platformState.collectAsStateWithLifecycle()
    val dialogState by settingViewModel.dialogState.collectAsStateWithLifecycle()
    val isDeleted by settingViewModel.isDeleted.collectAsStateWithLifecycle()
    val toolBindingState by settingViewModel.toolBindingState.collectAsStateWithLifecycle()
    val downloadedLocalModels by settingViewModel.downloadedLocalModels.collectAsStateWithLifecycle()
    val acceleratorOptions by settingViewModel.acceleratorOptions.collectAsStateWithLifecycle()
    val userMessage by settingViewModel.userMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var openMcpToolsAfterPermission by remember { mutableStateOf(false) }
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
                PlatformTopAppBar(
                    title = platformData.name,
                    onNavigationClick = onNavigationClick,
                    onDeleteClick = settingViewModel::openDeleteDialog,
                    scrollBehavior = scrollBehavior
                )
            }
        ) { innerPadding ->
            Column(
                Modifier
                    .padding(innerPadding)
                    .verticalScroll(scrollState)
            ) {
                val isLocalPlatform = platformData.compatibleType == ClientType.LITERT_LM
                PreferenceSwitchWithContainer(
                    title = stringResource(if (isLocalPlatform) R.string.enable_platform else R.string.enable),
                    isChecked = platformData.enabled
                ) { settingViewModel.toggleEnabled() }
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
                    enabled = platformData.enabled && !isReasoningDisabled,
                    onItemClick = settingViewModel::openTopPDialog,
                    showTrailingIcon = false,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            ImageVector.vectorResource(id = R.drawable.ic_chart),
                            contentDescription = stringResource(R.string.top_p)
                        )
                    }
                )
                if (isLocalPlatform) {
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
                                ImageVector.vectorResource(id = R.drawable.ic_chart),
                                contentDescription = stringResource(R.string.top_k)
                            )
                        }
                    )
                    val maxTokensDescription = platformData.maxTokens?.let { tokens ->
                        val cap = settingViewModel.maxTokensCap()
                        if (cap < PlatformSettingViewModel.DEFAULT_MAX_TOKENS_CAP) {
                            stringResource(R.string.max_tokens_with_cap, tokens, cap)
                        } else {
                            tokens.toString()
                        }
                    } ?: notSetText
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.max_tokens),
                        description = maxTokensDescription,
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openMaxTokensDialog,
                        showTrailingIcon = false,
                        showLeadingIcon = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Numbers,
                                contentDescription = stringResource(R.string.max_tokens)
                            )
                        }
                    )
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.accelerator),
                        description = acceleratorLabel(platformData.accelerator),
                        enabled = platformData.enabled && acceleratorOptions.isNotEmpty(),
                        onItemClick = settingViewModel::openAcceleratorDialog,
                        showTrailingIcon = false,
                        showLeadingIcon = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Speed,
                                contentDescription = stringResource(R.string.accelerator)
                            )
                        }
                    )
                }
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.system_prompt),
                    description = platformData.systemPrompt,
                    enabled = platformData.enabled,
                    onItemClick = settingViewModel::openSystemPromptDialog,
                    showTrailingIcon = false,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            ImageVector.vectorResource(id = R.drawable.ic_instructions),
                            contentDescription = stringResource(R.string.system_prompt)
                        )
                    }
                )
                if (!isLocalPlatform) {
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.timeout),
                        description = formatPlatformTimeout(platformData.timeout, stringResource(R.string.not_set)),
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openTimeoutDialog,
                        showTrailingIcon = false,
                        showLeadingIcon = true,
                        leadingIcon = {
                            Icon(
                                ImageVector.vectorResource(id = R.drawable.ic_info),
                                contentDescription = stringResource(R.string.timeout)
                            )
                        }
                    )
                }
                if (platformData.compatibleType == ClientType.GOOGLE) {
                    SettingItem(
                        modifier = Modifier.height(64.dp),
                        title = stringResource(R.string.gemini_safety_settings),
                        description = stringResource(R.string.gemini_safety_settings),
                        enabled = platformData.enabled,
                        onItemClick = settingViewModel::openGeminiSafetyDialog,
                        showTrailingIcon = false,
                        showLeadingIcon = true,
                        leadingIcon = {
                            Icon(
                                ImageVector.vectorResource(id = R.drawable.ic_info),
                                contentDescription = stringResource(R.string.gemini_safety_settings)
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
                }

                // Global Master Tool Disablement
                PreferenceListSwitch(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.disable_all_tools),
                    description = stringResource(R.string.disable_all_tools_description),
                    icon = Icons.Default.Build,
                    enabled = platformData.enabled,
                    isChecked = platformData.disableAllTools,
                    onCheckedChange = { settingViewModel.toggleDisableAllTools() }
                )

                // Granular Remote vs Local Tool Disablement
                PreferenceListSwitch(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.disable_remote_tools),
                    description = stringResource(R.string.disable_remote_tools_description),
                    icon = Icons.Default.Language,
                    enabled = platformData.enabled && !platformData.disableAllTools,
                    isChecked = platformData.disableRemoteTools,
                    onCheckedChange = { settingViewModel.toggleDisableRemoteTools() }
                )

                PreferenceListSwitch(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.disable_local_tools),
                    description = stringResource(R.string.disable_local_tools_description),
                    icon = Icons.Default.Calculate,
                    enabled = platformData.enabled && !platformData.disableAllTools,
                    isChecked = platformData.disableLocalTools,
                    onCheckedChange = { settingViewModel.toggleDisableLocalTools() }
                )

                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.web_search),
                    description = toolBindingState.searchConnections.firstOrNull {
                        it.connectionUid == toolBindingState.selectedSearchConnectionUid
                    }?.name ?: stringResource(R.string.not_set),
                    enabled = platformData.enabled && !platformData.disableAllTools && !platformData.disableRemoteTools,
                    onItemClick = settingViewModel::openSearchBackendDialog,
                    showTrailingIcon = true,
                    showLeadingIcon = false
                )
                PreferenceListSwitch(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.tool_trace_tool),
                    icon = ImageVector.vectorResource(id = R.drawable.ic_link),
                    enabled = !platformData.disableAllTools && !platformData.disableRemoteTools,
                    isChecked = toolBindingState.readUrlEnabled,
                    onCheckedChange = settingViewModel::toggleReadUrl
                )
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.mcp_server),
                    description = "${toolBindingState.selectedMcpTools.size} assigned",
                    enabled = platformData.enabled && !platformData.disableAllTools && !platformData.disableRemoteTools,
                    onItemClick = {
                        val needsPermission = toolBindingState.mcpConnections.any { connection ->
                            connection.endpointUrl?.let(::requiresLocalNetworkAccess) == true
                        }
                        if (needsPermission &&
                            Build.VERSION.SDK_INT >= 37 &&
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

                // Advanced Settings: Maximum Tool Calls
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
                SearchBackendDialog(toolBindingState, settingViewModel)
                LegacyMcpToolsDialog(toolBindingState, settingViewModel)
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

@Composable
private fun SearchBackendDialog(
    toolBindingState: PlatformSettingViewModel.ToolBindingState,
    settingViewModel: PlatformSettingViewModel
) {
    if (toolBindingState.isSearchBackendDialogOpen) {
        AlertDialog(
            title = { Text(stringResource(R.string.search_backend)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RadioItem(
                        modifier = Modifier.semantics { contentDescription = "None" },
                        title = stringResource(R.string.none),
                        description = null,
                        value = "",
                        selected = toolBindingState.selectedSearchConnectionUid == null
                    ) {
                        settingViewModel.selectSearchBackend(null)
                    }
                    toolBindingState.searchConnections.forEach { connection ->
                        RadioItem(
                            modifier = Modifier.semantics { contentDescription = connection.name },
                            title = connection.name,
                            description = connection.alias,
                            value = connection.connectionUid,
                            selected = toolBindingState.selectedSearchConnectionUid == connection.connectionUid
                        ) {
                            settingViewModel.selectSearchBackend(connection.connectionUid)
                        }
                    }
                }
            },
            onDismissRequest = settingViewModel::closeSearchBackendDialog,
            confirmButton = {
                TextButton(onClick = settingViewModel::closeSearchBackendDialog) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

@Composable
private fun LegacyMcpToolsDialog(
    toolBindingState: PlatformSettingViewModel.ToolBindingState,
    settingViewModel: PlatformSettingViewModel
) {
    if (!toolBindingState.isMcpToolsDialogOpen) return
    AlertDialog(
        title = { Text(stringResource(R.string.mcp_server)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    toolBindingState.mcpConnections.isEmpty() -> Text(stringResource(R.string.no_tool_connections))

                    toolBindingState.isMcpToolsLoading -> CircularProgressIndicator(
                        modifier = Modifier
                            .padding(16.dp)
                            .semantics { contentDescription = "Discovering MCP tools" }
                    )

                    toolBindingState.mcpToolOptions.isEmpty() -> Text(stringResource(R.string.no_tool_connections))

                    else -> toolBindingState.mcpToolOptions.forEach { option ->
                        val selected = toolBindingState.pendingMcpTools.any {
                            it.connectionUid == option.connectionUid && it.toolName == option.toolName
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = selected,
                                    onValueChange = { settingViewModel.toggleMcpTool(option.connectionUid, option.toolName) }
                                )
                                .semantics { contentDescription = "${option.connectionName} ${option.toolName}" }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = selected, onCheckedChange = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(
                                    text = option.toolName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "${option.connectionName} • ${option.modelToolName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                option.description?.takeIf(String::isNotBlank)?.let { description ->
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        onDismissRequest = settingViewModel::closeMcpToolsDialog,
        confirmButton = {
            TextButton(
                onClick = settingViewModel::saveMcpTools,
                enabled = !toolBindingState.isMcpToolsLoading
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = settingViewModel::closeMcpToolsDialog) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
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
