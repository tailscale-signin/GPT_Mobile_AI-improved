package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.presentation.common.CommonTopAppBar
import dev.chungjungsoo.gptmobile.presentation.common.SettingCategory
import dev.chungjungsoo.gptmobile.presentation.common.SettingItem
import dev.chungjungsoo.gptmobile.presentation.common.SettingItemValue
import dev.chungjungsoo.gptmobile.presentation.common.SettingSwitchItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSettingScreen(
    modifier: Modifier = Modifier,
    settingViewModel: PlatformSettingViewModel = hiltViewModel(),
    onNavigationClick: () -> Unit = {}
) {
    val platformData by settingViewModel.platform.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val dialogState by settingViewModel.dialogState.collectAsStateWithLifecycle()
    val isLocalPlatform = platformData.compatibleType == ClientType.LITERT_LM
    var showOpenRouterPicker by remember { mutableStateOf(false) }

    val modelDescription = if (isLocalPlatform) {
        settingViewModel.currentLocalModelName(platformData.model)
    } else {
        platformData.model
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CommonTopAppBar(
                title = platformData.name,
                scrollBehavior = scrollBehavior,
                onNavigationClick = onNavigationClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingCategory(title = stringResource(R.string.general))
            SettingSwitchItem(
                modifier = Modifier.height(64.dp),
                title = stringResource(R.string.enable),
                checked = platformData.enabled,
                onCheckedChange = settingViewModel::updateEnabled
            )

            SettingCategory(title = stringResource(R.string.platform_settings))
            SettingItem(
                modifier = Modifier.height(64.dp),
                title = stringResource(R.string.platform_name),
                description = platformData.name,
                enabled = platformData.enabled,
                onItemClick = settingViewModel::openPlatformNameDialog
            )
            SettingItem(
                modifier = Modifier.height(64.dp),
                title = stringResource(R.string.platform_type),
                description = platformData.compatibleType.name,
                enabled = false,
                onItemClick = {}
            )
            if (!isLocalPlatform) {
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.api_url),
                    description = platformData.apiUrl,
                    enabled = platformData.enabled,
                    onItemClick = settingViewModel::openApiUrlDialog
                )
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.api_key),
                    description = if (platformData.token.isNullOrEmpty()) stringResource(R.string.not_set) else "••••••••",
                    enabled = platformData.enabled,
                    onItemClick = settingViewModel::openApiKeyDialog
                )
            }
            SettingItem(
                modifier = Modifier.height(64.dp),
                title = stringResource(R.string.api_model),
                description = modelDescription,
                enabled = platformData.enabled,
                onItemClick = {
                    if (platformData.compatibleType == ClientType.OPENROUTER) {
                        showOpenRouterPicker = true
                    } else {
                        settingViewModel.openApiModelDialog()
                    }
                }
            )

            if (!isLocalPlatform) {
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.timeout),
                    description = "${platformData.timeout} s",
                    enabled = platformData.enabled,
                    onItemClick = settingViewModel::openTimeoutDialog
                )
            }

            SettingCategory(title = stringResource(R.string.parameter_settings))
            SettingItem(
                modifier = Modifier.height(64.dp),
                title = stringResource(R.string.system_prompt),
                description = platformData.systemPrompt,
                enabled = platformData.enabled,
                onItemClick = settingViewModel::openSystemPromptDialog
            )
            SettingItemValue(
                modifier = Modifier.height(64.dp),
                title = stringResource(R.string.temperature),
                value = platformData.temperature,
                enabled = platformData.enabled,
                onItemClick = settingViewModel::openTemperatureDialog
            )
            SettingItemValue(
                modifier = Modifier.height(64.dp),
                title = stringResource(R.string.top_p),
                value = platformData.topP,
                enabled = platformData.enabled,
                onItemClick = settingViewModel::openTopPDialog
            )
            SettingItemValue(
                modifier = Modifier.height(64.dp),
                title = stringResource(R.string.top_k),
                value = platformData.topK?.toFloat() ?: 0f,
                enabled = platformData.enabled,
                onItemClick = settingViewModel::openTopKDialog
            )
            SettingItemValue(
                modifier = Modifier.height(64.dp),
                title = stringResource(R.string.max_output_tokens),
                value = platformData.maxTokens?.toFloat() ?: 0f,
                enabled = platformData.enabled,
                onItemClick = settingViewModel::openMaxTokensDialog
            )
            if (isLocalPlatform) {
                SettingItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.accelerator),
                    description = platformData.accelerator?.name ?: stringResource(R.string.default_label),
                    enabled = platformData.enabled,
                    onItemClick = settingViewModel::openAcceleratorDialog
                )
            }
            if (!isLocalPlatform) {
                SettingSwitchItem(
                    modifier = Modifier.height(64.dp),
                    title = stringResource(R.string.extended_thinking),
                    checked = platformData.reasoning,
                    enabled = platformData.enabled,
                    onCheckedChange = settingViewModel::updateReasoning
                )
            }

            SettingCategory(title = stringResource(R.string.danger_zone))
            SettingItem(
                modifier = Modifier.height(64.dp),
                title = stringResource(R.string.delete_platform),
                description = stringResource(R.string.delete_platform_warning),
                textColor = MaterialTheme.colorScheme.error,
                onItemClick = settingViewModel::openDeletePlatformDialog
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        PlatformNameDialog(dialogState, platformData.name, settingViewModel)
        if (!isLocalPlatform) {
            APIUrlDialog(dialogState, platformData.apiUrl, settingViewModel)
            APIKeyDialog(dialogState, settingViewModel)
            // ModelDialog is used for non-OpenRouter platforms
            if (platformData.compatibleType != ClientType.OPENROUTER) {
                ModelDialog(dialogState, platformData.model, settingViewModel)
            }
            TimeoutDialog(dialogState, platformData.timeout, settingViewModel)
        } else {
            LocalModelDialog(dialogState, platformData.model, settingViewModel)
            AcceleratorDialog(dialogState, platformData.accelerator, settingViewModel)
        }

        if (showOpenRouterPicker && platformData.compatibleType == ClientType.OPENROUTER) {
            OpenRouterModelPickerDialog(
                currentModel = platformData.model,
                onDismiss = { showOpenRouterPicker = false },
                onModelSelected = { newModel ->
                    settingViewModel.updateModel(newModel)
                }
            )
        }

        SystemPromptDialog(dialogState, platformData.systemPrompt, settingViewModel)
        TemperatureDialog(dialogState, platformData.temperature, settingViewModel)
        TopPDialog(dialogState, platformData.topP, settingViewModel)
        TopKDialog(dialogState, platformData.topK, settingViewModel)
        MaxTokensDialog(dialogState, platformData.maxTokens, settingViewModel)
        DeletePlatformDialog(
            dialogState = dialogState,
            onDismiss = settingViewModel::dismissDeletePlatformDialog,
            onConfirm = {
                settingViewModel.deletePlatform(platformData)
                onNavigationClick()
            }
        )
    }
}
