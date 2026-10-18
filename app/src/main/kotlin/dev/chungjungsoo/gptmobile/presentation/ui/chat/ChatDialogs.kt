package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveThoughts
import dev.chungjungsoo.gptmobile.data.model.AvailableChatTool
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.CombinedChatSettingsDraft
import dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSaveUiState
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.repository.ProfileModelOption
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.ui.setting.ConversationDelegationCard
import dev.chungjungsoo.gptmobile.presentation.ui.setting.LlamaModelPickerDialog
import dev.chungjungsoo.gptmobile.presentation.ui.setup.DownloadedLocalModelOption
import dev.chungjungsoo.gptmobile.presentation.ui.setup.LocalModelPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatModelDialog(
    platformOrder: List<String>,
    activePlatformUids: Set<String>,
    initialModels: Map<String, String>,
    platformNames: Map<String, String>,
    platformClientTypes: Map<String, ClientType> = emptyMap(),
    platformApiUrls: Map<String, String> = emptyMap(),
    downloadedLocalModels: List<DownloadedLocalModelOption> = emptyList(),
    ollamaModels: List<UnifiedModelOption.Ollama> = emptyList(),
    initialSelectedProfile: String = "",
    onSelectedProfileChanged: (String) -> Unit = {},
    initialCreativity: Float = 0.5f,
    locationToolsEnabled: Boolean = false,
    webSearchToolsEnabled: Boolean = false,
    locationToolsAvailable: Boolean = true,
    webSearchToolsAvailable: Boolean = true,
    disabledPlatformUids: Set<String> = emptySet(),
    mcpTools: List<AvailableChatTool> = emptyList(),
    isToolEnabled: (String) -> Boolean = { false },
    onToolChanged: (String, Boolean) -> Unit = { _, _ -> },
    loadModels: suspend (String) -> List<ProfileModelOption> = { emptyList() },
    onPlatformActiveChanged: (String, Boolean) -> Unit = { _, _ -> },
    onLocationToolsChanged: (Boolean) -> Unit = {},
    onWebSearchToolsChanged: (Boolean) -> Unit = {},
    onNavigateToLocalModels: () -> Unit = {},
    delegationSettings: ModelDelegationSettings = ModelDelegationSettings(),
    delegationProfiles: List<PlatformV2> = emptyList(),
    usesDefaultDelegation: Boolean = true,
    researchSelection: dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection = dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection(),
    researchUnlockReason: String? = "Research is off",
    academicGateways: Map<String, String> = emptyMap(),
    onResearchChanged: (dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection) -> Unit = {},
    onAcademicConsent: () -> Unit = {},
    onSaveResearchDefault: () -> Unit = {},
    initialReasoning: Boolean = false,
    onReasoningChanged: (Boolean) -> Unit = {},
    localAssist: Boolean = false,
    onLocalAssistChanged: (Boolean) -> Unit = {},
    onDelegationChanged: (ConversationDelegationSettings?) -> Unit = {},
    isTemporary: Boolean = false,
    onTemporaryChanged: (Boolean) -> Unit = {},
    onForgetMemories: () -> Unit = {},
    parentChatId: Int? = null,
    onOpenParent: (Int) -> Unit = {},
    conversationMode: String = ConversationMode.STANDARD,
    conversationIdentity: String = "legacy",
    chatId: Int = 0,
    combinedSettings: ConversationCombinedSettings = ConversationCombinedSettings(),
    combinedSaveState: CombinedSettingsSaveUiState = CombinedSettingsSaveUiState(),
    onConfirmCombined: (CombinedChatSettingsDraft) -> Unit = {},
    onDismissRequest: () -> Unit,
    onConfirmRequest: (Map<String, String>, Float) -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    val combinedMode = conversationMode == ConversationMode.COMBINED
    val firstSection = if (combinedMode) "Combined" else "Models"
    var combinedDraft by rememberSaveable(conversationIdentity, stateSaver = CombinedChatSettingsDraftSaver) {
        mutableStateOf(
            CombinedChatSettingsDraft(
                conversationIdentity = conversationIdentity,
                chatId = chatId,
                baseRevision = combinedSettings.revision,
                settings = combinedSettings.normalized(),
                participantUids = platformOrder.filter { it in activePlatformUids },
                models = platformOrder.associateWith { uid -> initialModels[uid].orEmpty() },
                creativity = combinedSettings.creativity ?: initialCreativity.coerceIn(0f, 2f)
            )
        )
    }
    var legacyModels by rememberSaveable(conversationIdentity, platformOrder) {
        mutableStateOf(platformOrder.associateWith { uid -> initialModels[uid].orEmpty() })
    }
    val models = if (combinedMode) combinedDraft.models else legacyModels
    val updateModels: (Map<String, String>) -> Unit = { next ->
        if (!combinedSaveState.isCommitting) {
            if (combinedMode) combinedDraft = combinedDraft.copy(models = next) else legacyModels = next
        }
    }
    val draftParticipants = if (combinedMode) combinedDraft.participantUids.toSet() else activePlatformUids
    val saving = combinedMode && combinedSaveState.isCommitting

    var activeUnifiedPickerPlatformUid by remember { mutableStateOf<String?>(null) }
    var activeCloudPickerUid by remember { mutableStateOf<String?>(null) }
    var activeLlamaPickerPlatformUid by remember { mutableStateOf<String?>(null) }
    var section by rememberSaveable(conversationIdentity) { mutableStateOf(firstSection) }
    var selectedProfile by rememberSaveable(conversationIdentity, platformOrder) { mutableStateOf(initialSelectedProfile.takeIf { it in platformOrder } ?: platformOrder.firstOrNull { it in activePlatformUids && it !in disabledPlatformUids } ?: platformOrder.firstOrNull().orEmpty()) }
    LaunchedEffect(selectedProfile, activePlatformUids) {
        // Immediate tabs only address saved identities, never draft-only members.
        onSelectedProfileChanged(if (combinedMode) selectedProfile.takeIf { it in activePlatformUids } ?: platformOrder.firstOrNull { it in activePlatformUids }.orEmpty() else selectedProfile)
    }
    var profileMenuOpen by remember { mutableStateOf(false) }
    var legacyCreativity by rememberSaveable(conversationIdentity, initialCreativity) { mutableStateOf(initialCreativity.coerceIn(0f, 2f)) }
    val creativity = if (combinedMode) combinedDraft.creativity else legacyCreativity

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !saving, dismissOnClickOutside = !saving),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        icon = { Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Conversation Settings", style = MaterialTheme.typography.headlineSmall)
                Text("${draftParticipants.size} AI profiles · Your conversation, your controls", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        text = {
            Column {
                if (combinedMode && combinedSaveState.error != null) Text(combinedSaveState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                val sections = listOf(firstSection, "Tools", "Privacy")
                TabRow(selectedTabIndex = sections.indexOf(section).coerceAtLeast(0), containerColor = MaterialTheme.colorScheme.surface) {
                    sections.forEach { label ->
                        Tab(
                            selected = section == label,
                            onClick = { section = label },
                            icon = {
                                Icon(
                                    when (label) {
                                        "Models", "Combined" -> Icons.Rounded.Memory
                                        "Tools" -> Icons.Rounded.Hub
                                        else -> Icons.Rounded.Shield
                                    },
                                    null
                                )
                            },
                            text = { Text(label) }
                        )
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (section == firstSection) {
                        if (combinedMode) {
                            CombinedSettingsControls(combinedDraft.settings, { combinedDraft = combinedDraft.copy(settings = it) }, enabled = !saving)
                        } else {
                            ConversationDelegationCard(
                                settings = delegationSettings,
                                profiles = delegationProfiles,
                                usesDefaults = usesDefaultDelegation,
                                onChange = onDelegationChanged
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        val filteredProfiles = platformOrder
                        val displayedProfile = selectedProfile.takeIf { it in filteredProfiles } ?: filteredProfiles.firstOrNull()
                        Box(Modifier.fillMaxWidth()) {
                            FilledTonalButton(onClick = { profileMenuOpen = true }, enabled = !saving && filteredProfiles.isNotEmpty(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                                Icon(Icons.Rounded.Memory, null)
                                Spacer(Modifier.width(8.dp))
                                Text(displayedProfile?.let { platformNames[it] }.orEmpty().ifBlank { "No matching profiles" }, Modifier.weight(1f))
                                Icon(Icons.Rounded.ArrowDropDown, "Select AI profile")
                            }
                            DropdownMenu(expanded = profileMenuOpen, onDismissRequest = { profileMenuOpen = false }) {
                                filteredProfiles.forEach { uid ->
                                    DropdownMenuItem(
                                        text = { Text("${platformNames[uid].orEmpty()} · ${if (uid in draftParticipants) "Added" else "Available"}") },
                                        onClick = {
                                            selectedProfile = uid
                                            profileMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                        listOfNotNull(displayedProfile).forEach { platformUid ->
                            val platformName = platformNames[platformUid] ?: stringResource(R.string.unknown)
                            val clientType = platformClientTypes[platformUid]
                            val isMember = platformUid in draftParticipants
                            val isTemporarilyEnabled = platformUid !in disabledPlatformUids
                            val onMembershipChanged: (Boolean) -> Unit = { active ->
                                if (combinedMode) {
                                    combinedDraft = combinedDraft.copy(
                                        participantUids = if (active) {
                                            (combinedDraft.participantUids + platformUid).distinct()
                                        } else {
                                            combinedDraft.participantUids - platformUid
                                        }
                                    )
                                } else {
                                    onPlatformActiveChanged(platformUid, active)
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !saving) { onMembershipChanged(!isMember) }
                                    .testTag("combined_member_row_$platformUid")
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(platformName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                Text(
                                    if (!isTemporarilyEnabled) {
                                        "Paused"
                                    } else if (isMember) {
                                        "Added"
                                    } else {
                                        "Available"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    modifier = Modifier.testTag("combined_member_$platformUid"),
                                    checked = isMember,
                                    enabled = !saving,
                                    onCheckedChange = onMembershipChanged
                                )
                            }

                            if (clientType == ClientType.LITERT_LM) {
                                Text(
                                    text = stringResource(R.string.chat_model_for_platform, platformName),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                                LocalModelPicker(
                                    models = downloadedLocalModels,
                                    selectedCatalogEntryId = models[platformUid].orEmpty(),
                                    onModelSelected = { value ->
                                        updateModels(models + (platformUid to value))
                                    },
                                    onNavigateToLocalModels = onNavigateToLocalModels,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                )
                            } else if (clientType == ClientType.OLLAMA) {
                                // Ollama platform: support both direct text entry and unified picker selection
                                OutlinedTextField(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    value = models[platformUid].orEmpty(),
                                    onValueChange = { value ->
                                        updateModels(models + (platformUid to value))
                                    },
                                    singleLine = true,
                                    enabled = !saving,
                                    label = { Text(text = stringResource(R.string.chat_model_for_platform, platformName)) },
                                    trailingIcon = {
                                        IconButton(enabled = !saving, onClick = { activeCloudPickerUid = platformUid }) {
                                            Icon(
                                                imageVector = Icons.Rounded.ArrowDropDown,
                                                contentDescription = stringResource(R.string.unified_model_picker)
                                            )
                                        }
                                    }
                                )
                            } else if (clientType == ClientType.LLAMA) {
                                // Llama platform: support both direct text entry and Llama router model picker selection
                                OutlinedTextField(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    value = models[platformUid].orEmpty(),
                                    onValueChange = { value ->
                                        updateModels(models + (platformUid to value))
                                    },
                                    singleLine = true,
                                    enabled = !saving,
                                    label = { Text(text = stringResource(R.string.chat_model_for_platform, platformName)) },
                                    trailingIcon = {
                                        IconButton(enabled = !saving, onClick = { activeLlamaPickerPlatformUid = platformUid }) {
                                            Icon(
                                                imageVector = Icons.Rounded.ArrowDropDown,
                                                contentDescription = stringResource(R.string.llama_select_router_model)
                                            )
                                        }
                                    }
                                )
                            } else {
                                OutlinedTextField(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    value = models[platformUid].orEmpty(),
                                    onValueChange = { value ->
                                        updateModels(models + (platformUid to value))
                                    },
                                    singleLine = true,
                                    enabled = !saving,
                                    label = { Text(text = stringResource(R.string.chat_model_for_platform, platformName)) },
                                    trailingIcon = {
                                        IconButton(enabled = !saving, onClick = { activeCloudPickerUid = platformUid }) {
                                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Browse provider models")
                                        }
                                    }
                                )
                            }
                        }
                    }
                    if (section == "Tools") {
                        Text("Changes on this tab save immediately.", style = MaterialTheme.typography.bodySmall)
                        if (combinedMode) Text("Tools use saved participants and model choices. Save first-tab changes before configuring new participants here.", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Psychology, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("Reasoning", Modifier.weight(1f))
                            Switch(initialReasoning, onReasoningChanged)
                        }
                        Text("Applies immediately to this conversation", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("Location Tools", modifier = Modifier.weight(1f))
                            Switch(
                                checked = locationToolsEnabled,
                                enabled = locationToolsAvailable,
                                onCheckedChange = onLocationToolsChanged
                            )
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Web, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("Web Search", modifier = Modifier.weight(1f))
                            Switch(
                                checked = webSearchToolsEnabled,
                                enabled = webSearchToolsAvailable,
                                onCheckedChange = onWebSearchToolsChanged
                            )
                        }
                        MultiSearchResearchPanel(researchSelection, delegationSettings, researchUnlockReason, academicGateways, onResearchChanged, onAcademicConsent, onSaveResearchDefault)
                        var showConnectedTools by rememberSaveable { mutableStateOf(false) }
                        FilledTonalButton(onClick = { showConnectedTools = !showConnectedTools }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Rounded.Hub, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Connected Tools · ${mcpTools.size}", Modifier.weight(1f))
                            Icon(Icons.Rounded.ArrowDropDown, null)
                        }
                        if (showConnectedTools) {
                            mcpTools.forEach { tool ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(toolActivityIcon(tool.name), null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(tool.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = isToolEnabled(tool.id), enabled = tool.isEnabled, onCheckedChange = { onToolChanged(tool.id, it) })
                                }
                            }
                        }
                        Text(if (combinedMode) "Enabled connections are available for research. Response effort controls how broadly they are used." else "Web search queries all enabled search connections and combines their results. Individual switches control which engines participate.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (section == firstSection) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("Creativity · %.2f".format(creativity), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        Slider(
                            value = creativity,
                            onValueChange = { if (combinedMode) combinedDraft = combinedDraft.copy(creativity = it) else legacyCreativity = it },
                            enabled = !saving,
                            valueRange = 0f..2f,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("combined_creativity")
                        )
                        Text("Focused answers at the left; more varied ideas at the right. Model and creativity changes apply when saved.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (section == "Privacy") {
                        Text("Changes on this tab save immediately.", style = MaterialTheme.typography.bodySmall)
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(20.dp)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text("Temporary Conversation", style = MaterialTheme.typography.titleSmall)
                                    if (isTemporary) Text("No memory · Removed when closed", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(isTemporary, onTemporaryChanged)
                            }
                        }
                        OutlinedButton(onClick = onForgetMemories, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Rounded.DeleteSweep, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Forget Learned Memories")
                        }
                        parentChatId?.let { id ->
                            FilledTonalButton(onClick = { onOpenParent(id) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.CallSplit, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Open Original Branch")
                            }
                        }
                    }
                }
            }
        },
        onDismissRequest = { if (!saving) onDismissRequest() },
        confirmButton = {
            val hasBlank = draftParticipants.isEmpty() ||
                draftParticipants.any { uid ->
                    models[uid].orEmpty().trim().isBlank() || downloadedLocalModels.any { it.catalogEntryId == models[uid] && it.compatibilityIssue != null }
                }
            Button(
                modifier = Modifier.testTag("combined_settings_save"),
                enabled = !saving && !hasBlank && (!combinedMode || combinedDraft.settings.isSupported),
                onClick = {
                    if (combinedMode) {
                        val requested = combinedDraft.copy(chatId = chatId, operationId = java.util.UUID.randomUUID().toString())
                        combinedDraft = requested
                        onConfirmCombined(requested)
                    } else {
                        onConfirmRequest(
                            models.mapValues { (_, model) -> model.trim() },
                            creativity
                        )
                    }
                }
            ) {
                Icon(Icons.Rounded.Done, null)
                Spacer(Modifier.width(8.dp))
                Text(if (saving) "Saving…" else "Save Changes")
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismissRequest, modifier = Modifier.testTag("combined_settings_cancel")) {
                Text(stringResource(R.string.cancel))
            }
        }
    )

    activeCloudPickerUid?.let { uid ->
        CloudModelPickerDialog(
            profileUid = uid,
            loadModels = loadModels,
            onSelect = { model -> updateModels(models + (uid to model)) },
            onDismiss = { activeCloudPickerUid = null }
        )
    }

    // Show Unified Model Picker Dialog if active
    activeUnifiedPickerPlatformUid?.let { platformUid ->
        val localOptions = downloadedLocalModels.map {
            UnifiedModelOption.Local(
                id = it.catalogEntryId,
                displayName = it.displayName
            )
        }
        val currentModel = models[platformUid].orEmpty()
        val platformName = platformNames[platformUid] ?: ""

        UnifiedModelPickerDialog(
            title = stringResource(R.string.chat_model_for_platform, platformName),
            localModels = localOptions,
            ollamaModels = ollamaModels,
            selectedModelId = currentModel,
            onModelSelected = { selected ->
                updateModels(models + (platformUid to selected))
            },
            onNavigateToLocalModels = onNavigateToLocalModels,
            onDismissRequest = { activeUnifiedPickerPlatformUid = null }
        )
    }

    // Show Llama Model Picker Dialog if active
    activeLlamaPickerPlatformUid?.let { platformUid ->
        val currentModel = models[platformUid].orEmpty()
        val baseUrl = platformApiUrls[platformUid].orEmpty()

        LlamaModelPickerDialog(
            baseUrl = baseUrl,
            currentModel = currentModel,
            onDismiss = { activeLlamaPickerPlatformUid = null },
            onModelSelected = { selected ->
                updateModels(models + (platformUid to selected))
            }
        )
    }
}

@Composable
fun ChatTitleDialog(
    initialTitle: String,
    onDefaultTitleMode: () -> String?,
    onConfirmRequest: (title: String) -> Unit,
    onDismissRequest: () -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    val untitledChat = stringResource(R.string.untitled_chat)

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.chat_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 16.dp),
                    value = title,
                    singleLine = true,
                    isError = title.length > 50,
                    supportingText = {
                        if (title.length > 50) {
                            Text(stringResource(R.string.title_length_limit, title.length))
                        }
                    },
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.chat_title)) }
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && title != initialTitle,
                onClick = {
                    onConfirmRequest(title)
                    onDismissRequest()
                }
            ) {
                Text(stringResource(R.string.update))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { title = onDefaultTitleMode.invoke() ?: untitledChat }
            ) {
                Text(text = stringResource(R.string.default_mode))
            }
            TextButton(
                onClick = onDismissRequest
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun UserMessageEditDialog(
    initialQuestion: MessageV2,
    attachments: List<ChatAttachmentDraft>,
    onFileSelected: (String) -> Unit,
    onCopyFailed: () -> Unit,
    onFileRemoved: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (MessageV2) -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf(initialQuestion.content) }
    val questionFieldMaxLines = 8
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                val filePath = withContext(Dispatchers.IO) {
                    copyFileToAppDirectory(context, it)
                }
                if (filePath != null) {
                    onFileSelected(filePath)
                } else {
                    onCopyFailed()
                }
            }
        }
    }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.edit_question)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    value = question,
                    onValueChange = { question = it },
                    minLines = 3,
                    maxLines = questionFieldMaxLines,
                    label = { Text(stringResource(R.string.user_message)) }
                )
                AttachmentEditorSection(
                    attachments = attachments,
                    onAttachFileClick = { filePickerLauncher.launch("*/*") },
                    onFileRemoved = onFileRemoved
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            val hasPendingOrFailedAttachments = attachments.any { it.status != ChatAttachmentDraft.Status.Ready }
            TextButton(
                enabled = !hasPendingOrFailedAttachments &&
                    (question.isNotBlank() || attachments.isNotEmpty()) &&
                    (question != initialQuestion.content || attachments.mapNotNull { it.attachment } != initialQuestion.attachments),
                onClick = { onConfirmRequest(initialQuestion.copy(content = question)) }
            ) {
                Text("Branch & send")
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
fun AssistantMessageEditDialog(
    initialMessage: MessageV2,
    attachments: List<ChatAttachmentDraft>,
    onFileSelected: (String) -> Unit,
    onCopyFailed: () -> Unit,
    onFileRemoved: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirmRequest: (MessageV2, String) -> Unit
) {
    val configuration = LocalWindowInfo.current
    val screenWidth = with(LocalDensity.current) { configuration.containerSize.width.toDp() }
    val screenHeight = with(LocalDensity.current) { configuration.containerSize.height.toDp() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var responseText by remember { mutableStateOf(initialMessage.effectiveContent()) }
    var thoughtsText by remember { mutableStateOf(initialMessage.effectiveThoughts()) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                val filePath = withContext(Dispatchers.IO) {
                    copyFileToAppDirectory(context, it)
                }
                if (filePath != null) {
                    onFileSelected(filePath)
                } else {
                    onCopyFailed()
                }
            }
        }
    }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .widthIn(max = screenWidth - 40.dp)
            .heightIn(max = screenHeight - 80.dp),
        title = { Text(text = stringResource(R.string.edit_assistant_message)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    value = responseText,
                    onValueChange = { responseText = it },
                    minLines = 3,
                    maxLines = 8,
                    label = { Text(stringResource(R.string.assistant_message)) }
                )
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    value = thoughtsText,
                    onValueChange = { thoughtsText = it },
                    minLines = 2,
                    maxLines = 8,
                    label = { Text(stringResource(R.string.assistant_thoughts)) }
                )
                AttachmentEditorSection(
                    attachments = attachments,
                    onAttachFileClick = { filePickerLauncher.launch("image/*") },
                    onFileRemoved = onFileRemoved
                )
            }
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            val hasPendingOrFailedAttachments = attachments.any { it.status != ChatAttachmentDraft.Status.Ready }
            TextButton(
                enabled = !hasPendingOrFailedAttachments &&
                    (responseText.isNotBlank() || thoughtsText.isNotBlank() || attachments.isNotEmpty()) &&
                    (
                        responseText != initialMessage.effectiveContent() ||
                            thoughtsText != initialMessage.effectiveThoughts() ||
                            attachments.mapNotNull { it.attachment } != initialMessage.attachments
                        ),
                onClick = {
                    onConfirmRequest(
                        initialMessage.copy(content = responseText),
                        thoughtsText
                    )
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
private fun AttachmentEditorSection(
    attachments: List<ChatAttachmentDraft>,
    onAttachFileClick: () -> Unit,
    onFileRemoved: (String) -> Unit
) {
    if (attachments.isNotEmpty()) {
        FileThumbnailRow(
            selectedAttachments = attachments,
            onFileRemoved = onFileRemoved
        )
    }
    TextButton(
        modifier = Modifier.padding(horizontal = 12.dp),
        onClick = onAttachFileClick
    ) {
        Text(text = stringResource(R.string.attach_file))
    }
}
