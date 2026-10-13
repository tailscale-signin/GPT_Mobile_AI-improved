package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.DelegationWorkloadPreset
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import dev.chungjungsoo.gptmobile.data.model.withWorkloadPreset
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import kotlin.math.roundToInt

@Composable
fun ModelDelegationSettingsPanel(
    viewModel: LocalToolsViewModel = hiltViewModel(),
    memoryViewModel: FactVaultViewModel = hiltViewModel()
) {
    val config by viewModel.delegation.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val modelScores by viewModel.modelScores.collectAsStateWithLifecycle()
    val vault by memoryViewModel.vault.collectAsStateWithLifecycle()
    val memoryBusy by memoryViewModel.busy.collectAsStateWithLifecycle()
    ModelDelegationSettingsContent(
        config = config,
        profiles = profiles,
        busy = busy,
        error = error,
        onChange = viewModel::update,
        onReset = viewModel::resetDelegationDefaults,
        modelScores = modelScores,
        cloudRecallEnabled = vault.settings.allowCloudRecall,
        cloudRecallBusy = memoryBusy,
        onCloudRecallChange = { enabled ->
            memoryViewModel.updateSettings(vault.settings.copy(allowCloudRecall = enabled))
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelDelegationSettingsContent(
    config: ModelDelegationSettings,
    profiles: List<PlatformV2>,
    busy: Boolean,
    error: String?,
    onChange: ((ModelDelegationSettings) -> ModelDelegationSettings) -> Unit,
    onReset: () -> Unit,
    modelScores: Map<String, Int> = emptyMap(),
    cloudRecallEnabled: Boolean? = null,
    cloudRecallBusy: Boolean = false,
    onCloudRecallChange: (Boolean) -> Unit = {}
) {
    var previewPlan by rememberSaveable { mutableStateOf(false) }
    var resetConfirm by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var settingsTab by rememberSaveable { mutableIntStateOf(0) }
    val eligible = profiles.filter { it.enabled && !it.excludesMemory() && (it.isPrivateDestination() || config.remoteWorkersAllowed()) }
    val configuredReviewer = eligible.firstOrNull { it.uid == config.reviewerProfileUid }
    val helperEligible = eligible.filter { candidate ->
        !config.reviewerEnabled ||
            (
                candidate.uid != configuredReviewer?.uid &&
                    (configuredReviewer == null || !candidate.model.trim().equals(configuredReviewer.model.trim(), ignoreCase = true))
                )
    }
    val selectedDelegate = helperEligible.firstOrNull { it.uid == config.targetProfileUid }
    val reviewerEligible = eligible.filter { candidate ->
        candidate.uid != config.targetProfileUid &&
            (selectedDelegate == null || !candidate.model.trim().equals(selectedDelegate.model.trim(), ignoreCase = true))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Icon(Icons.Rounded.AutoAwesome, "", Modifier.padding(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_model_delegation_7637c), style = MaterialTheme.typography.headlineSmall)
                            SettingsHelpIcon(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_79924cacd3))
                        }
                        Text(if (config.enabled) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_9a1b21a60e, (100 - config.processingOwnership).toString(), (config.strategy).toString()) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_c16c9ec628), style = MaterialTheme.typography.bodyMedium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            if (config.enabled) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_847ced6ca0) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_3bff69bd74),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        androidx.compose.material3.Switch(
                            checked = config.enabled,
                            onCheckedChange = { value -> onChange { it.copy(enabled = value) } },
                            enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = "Enable delegation" }
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(Icons.Rounded.Memory, "Up to ${config.effectiveLocalModelCalls()} delegate calls")
                    StatusPill(Icons.Rounded.Bolt, "Compact evidence brief")
                    if (config.remoteWorkersAllowed()) StatusPill(Icons.Rounded.Cloud, "Remote workers on")
                    if (config.reviewerEnabled) StatusPill(Icons.Rounded.AutoAwesome, "Reviewer on")
                }
            }
        }
        PrimaryTabRow(selectedTabIndex = settingsTab) {
            Tab(
                selected = settingsTab == 0,
                onClick = { settingsTab = 0 },
                text = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_overview_d4b1e)) },
                icon = { Icon(Icons.Rounded.Bolt, null) }
            )
            Tab(
                selected = settingsTab == 1,
                onClick = { settingsTab = 1 },
                text = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_workers_7a1ec)) },
                icon = { Icon(Icons.Rounded.AutoAwesome, null) }
            )
            Tab(selected = settingsTab == 2, onClick = { settingsTab = 2 }, text = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_activity_38da1)) })
        }
        if (settingsTab == 0) {
            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_global_defaults_ordinary_changes_apply_to_the_389ee), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { previewPlan = true }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_preview_plan_11340)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Primary-led" to 25, "Shared" to 50, "Delegate-led" to 100).forEach { (label, amount) ->
                    FilterChip(selected = 100 - config.processingOwnership == amount, onClick = { onChange { it.withDelegationAmount(amount) } }, label = { Text(label) })
                }
            }
            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_preparation_preference_is_not_a_measured_perc_c2449), style = MaterialTheme.typography.bodySmall)
        }
        if (settingsTab == 2) {
            dev.chungjungsoo.gptmobile.presentation.ui.setting.toolstatistics.ToolStatisticsPanel(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.MODEL_DELEGATION, "Model Delegation", config.enabled)
            DelegationActivityPanel()
        }

        if (settingsTab == 1) {
            DelegationTemplatePanel(config, onChange)
            DelegationPlanEditor(config, profiles, onChange)
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e6aeda0ec3), androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_2e14a56341))
                    LocalToolToggle(
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_b1fb8505be),
                        config.remoteWorkersAllowed(),
                        !busy,
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_4aee5d8247)
                    ) { value -> onChange { it.withRemoteWorkersAllowed(value) } }
                    cloudRecallEnabled?.let { enabled ->
                        LocalToolToggle(
                            androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_b2b6eb9b4a),
                            enabled,
                            !busy && !cloudRecallBusy,
                            androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f15774558c)
                        ) { onCloudRecallChange(it) }
                    }
                    DelegateModelDropdown(
                        profiles = profiles,
                        blockedReason = { candidate -> dev.chungjungsoo.gptmobile.data.delegation.DelegationPolicyResolver.reasons(candidate, config.copy(approvedWorkerUids = null), reviewer = configuredReviewer.takeIf { config.reviewerEnabled }).takeIf { it.isNotEmpty() }?.joinToString { it.name.lowercase().replace('_', ' ') } },
                        selectedProfileUid = config.targetProfileUid,
                        enabled = !busy,
                        onSelected = { profile -> onChange { it.copy(targetProfileUid = profile?.uid.orEmpty(), selectionMode = if (profile == null) dev.chungjungsoo.gptmobile.data.delegation.WorkerSelectionMode.AUTOMATIC else dev.chungjungsoo.gptmobile.data.delegation.WorkerSelectionMode.FIXED) } },
                        scores = modelScores
                    )
                    if (selectedDelegate == null) {
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_the_assigned_helper_may_be_missing_or_unavail_a75af), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_fallback_325e8), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        dev.chungjungsoo.gptmobile.data.delegation.DelegationFallback.entries.forEach { policy ->
                            FilterChip(selected = config.fallbackPolicy == policy, onClick = { onChange { it.copy(fallbackPolicy = policy, fallbackToAnotherProfile = policy == dev.chungjungsoo.gptmobile.data.delegation.DelegationFallback.APPROVED_ORDER) } }, label = { Text(policy.name.lowercase().replace('_', ' ')) })
                        }
                    }
                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_approved_worker_pool_693e9), style = MaterialTheme.typography.titleMedium)
                    if (config.approvedWorkerUids == null) Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_legacy_automatic_pool_retained_select_profile_922be), style = MaterialTheme.typography.bodySmall)
                    profiles.sortedBy { it.uid }.forEach { candidate ->
                        val reasons = dev.chungjungsoo.gptmobile.data.delegation.DelegationPolicyResolver.reasons(candidate, config.copy(approvedWorkerUids = null), reviewer = configuredReviewer)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(candidate.name)
                                Text(reasons.joinToString { it.name.lowercase().replace('_', ' ') }.ifBlank { androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_d7cb68b174) }, style = MaterialTheme.typography.bodySmall)
                            }
                            androidx.compose.material3.Checkbox(checked = candidate.uid in config.approvedWorkerUids.orEmpty(), onCheckedChange = { checked -> onChange { current -> current.copy(approvedWorkerUids = if (checked) current.approvedWorkerUids.orEmpty() + candidate.uid else current.approvedWorkerUids.orEmpty() - candidate.uid) } }, enabled = reasons.isEmpty())
                        }
                    }
                    config.approvedWorkerUids.orEmpty().forEachIndexed { index, uid ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(profiles.find { it.uid == uid }?.name ?: androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_1cadbef7c7), Modifier.weight(1f))
                            TextButton(enabled = index > 0, onClick = {
                                onChange { current ->
                                    current.copy(
                                        approvedWorkerUids = current.approvedWorkerUids.orEmpty().toMutableList().apply {
                                            if (index in indices && index > 0) {
                                                removeAt(index)
                                                add(index - 1, uid)
                                            }
                                        }
                                    )
                                }
                            }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_move_up_c66fe)) }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (settingsTab == 1) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_290ed313c7), androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_73e7905add))
                    LocalToolToggle(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f449b952f5), config.reviewerEnabled, !busy && config.processingOwnership != 0) { value ->
                        onChange {
                            it.copy(
                                reviewerEnabled = value,
                                reviewerProfileUid = if (value) it.reviewerProfileUid else ""
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_reviewer_validation_4a1fc), style = MaterialTheme.typography.labelLarge)
                        SettingsHelpIcon(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_25bb8247d7))
                    }
                    if (config.reviewerEnabled) {
                        LocalToolToggle("Require independent review before preparation", config.reviewMode == dev.chungjungsoo.gptmobile.data.delegation.DelegationReviewMode.REQUIRED, !busy) { required ->
                            onChange { it.copy(reviewMode = if (required) dev.chungjungsoo.gptmobile.data.delegation.DelegationReviewMode.REQUIRED else dev.chungjungsoo.gptmobile.data.delegation.DelegationReviewMode.BEST_EFFORT) }
                        }
                        Text("Best effort preserves unverified evidence if review is unavailable. Required review pauses before research.", style = MaterialTheme.typography.bodySmall)
                        DelegateModelDropdown(
                            profiles = profiles,
                            blockedReason = { candidate -> dev.chungjungsoo.gptmobile.data.delegation.DelegationPolicyResolver.reasons(candidate, config.copy(approvedWorkerUids = null), primary = selectedDelegate).takeIf { it.isNotEmpty() }?.joinToString { it.name.lowercase().replace('_', ' ') } },
                            selectedProfileUid = config.reviewerProfileUid,
                            enabled = !busy,
                            onSelected = { profile -> onChange { it.copy(reviewerProfileUid = profile?.uid.orEmpty()) } },
                            roleLabel = "Reviewer Model",
                            scores = modelScores
                        )
                        val selectedReviewer = reviewerEligible.firstOrNull { it.uid == config.reviewerProfileUid }
                        if (selectedReviewer == null) {
                            Text(
                                androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_0301d3746e),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_894a87fbe4), config.reviewTimeoutSeconds, 15..120, 15, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_728ba059a7)) { value -> onChange { it.copy(reviewTimeoutSeconds = value) } }
                        DelegationSlider(
                            androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_5a7df4ed2a),
                            config.reviewerOutputTokens,
                            128..1024,
                            64,
                            !busy,
                            androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_3880f8f654)
                        ) { value -> onChange { it.copy(reviewerOutputTokens = value) } }
                        DelegationSlider(
                            androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_5e32127903),
                            config.reviewerMinimumScore,
                            0..100,
                            5,
                            !busy,
                            androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_c0abd23f65)
                        ) { value -> onChange { it.copy(reviewerMinimumScore = value) } }
                        DelegationSlider(
                            androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_1baadc1488),
                            config.reviewerRetryLimit,
                            0..5,
                            1,
                            !busy,
                            androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e39c1d939f)
                        ) { value -> onChange { it.copy(reviewerRetryLimit = value) } }
                        LocalToolToggle(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_fec97373ab), config.reviewerAutoCorrect, !busy) { value ->
                            onChange { it.copy(reviewerAutoCorrect = value) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_correction_policy_a2ae9), style = MaterialTheme.typography.labelLarge)
                            SettingsHelpIcon(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_610b6c5418))
                        }
                    }
                }
            }
        }
        if (settingsTab == 0) {
            DeepResearchSettingsCard(config.deepResearch, !busy) { value -> onChange { it.copy(deepResearch = value) } }
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_8eaee8cf65), androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_19df04b26e))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DelegationWorkloadPreset.entries.forEach { preset ->
                            FilterChip(selected = config.withWorkloadPreset(preset) == config, enabled = !busy, onClick = { onChange { it.withWorkloadPreset(preset) } }, label = { Text(preset.name.lowercase().replaceFirstChar { it.titlecase() }) })
                        }
                    }
                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_presets_change_research_breadth_your_profile__1164a), style = MaterialTheme.typography.bodySmall)
                    DelegationSlider(
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_561ce4ad98),
                        100 - config.processingOwnership,
                        0..100,
                        5,
                        !busy,
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_5707007edb)
                    ) { value -> onChange { it.withDelegationAmount(value) } }
                    DelegationSlider(
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_7d16b20ef2),
                        config.strategy,
                        0..100,
                        5,
                        !busy,
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_00d21c4540)
                    ) { value -> onChange { it.withStrategy(value) } }
                    Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_4a07d0e944, config.effectiveLocalModelCalls(), config.maxSearchQueries, config.maxPages), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeading(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e9b886ee73), androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_4b60f3436b))
                    LocalToolToggle(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_7541a705ae), config.researchEnabled, !busy) { value -> onChange { it.copy(researchEnabled = value) } }
                    LocalToolToggle(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_73f061a743), config.automaticResearch, !busy && config.researchEnabled) { value -> onChange { it.copy(automaticResearch = value) } }
                    LocalToolToggle(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_b8fc6b08a8), config.compactToolResults, !busy) { value -> onChange { it.copy(compactToolResults = value) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_research_tools_e46bc), style = MaterialTheme.typography.labelLarge)
                        SettingsHelpIcon(
                            androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_1fbe51f74c) +
                                androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_fc940a0661)
                        )
                    }
                    Text(
                        androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_035579bfee) +
                            androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_62c254c614),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { resetConfirm = true }, enabled = !busy) {
                        Icon(Icons.Rounded.RestartAlt, null)
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_restore_recommended_defaults_985dd), Modifier.padding(start = 8.dp))
                    }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { SectionHeading(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f56c26e8d6), androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_4e82c3d1ef)) }
                        IconButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.testTag("delegation_advanced")) { Icon(if (showAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, "Toggle advanced controls") }
                    }
                    if (!showAdvanced) {
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_e45badb7be, config.maxOutputTokens, config.maxConcurrentDelegates), style = MaterialTheme.typography.bodySmall)
                    }
                    if (showAdvanced) {
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_dd2e9780d8), config.maxDelegationDepth, 1..2, 1, !busy) { value -> onChange { it.copy(maxDelegationDepth = value) } }
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_research_limits_c5660), style = MaterialTheme.typography.titleMedium)
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_6dbf4ed9f0), config.maxSearchQueries, 1..20, 1, !busy) { value -> onChange { it.copy(maxSearchQueries = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_4330bf3f64), config.searchResultsPerEngine, 1..10, 1, !busy) { value -> onChange { it.copy(searchResultsPerEngine = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_5cba7d0dc5), config.maxPages, 0..32, 1, !busy) { value -> onChange { it.copy(maxPages = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_50f3b10110), config.crawlDepth, 0..8, 1, !busy) { value -> onChange { it.copy(crawlDepth = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_ed548f5933), config.pageFetchConcurrency, 1..16, 1, !busy) { value -> onChange { it.copy(pageFetchConcurrency = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_05ac90c03b), config.maxPageCharacters, 1000..96000, 1000, !busy) { value -> onChange { it.copy(maxPageCharacters = value) } }
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_handoff_and_helper_workload_ebb1c), style = MaterialTheme.typography.titleMedium)
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_8702a5693e), config.handoffTokens, 128..8192, 128, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_be21cb5e04)) { value -> onChange { it.copy(handoffTokens = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_48e2868cc8), config.maxInputCharacters, 1000..64000, 500, !busy) { value -> onChange { it.copy(maxInputCharacters = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_5d0c55161d), config.maxInputTokensPerDelegate, 1000..12000, 500, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_43740932e1)) { value -> onChange { it.copy(maxInputTokensPerDelegate = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_79a542d781), config.chunkSizeTokens, 1000..12000, 500, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_85f39c2e2b)) { value -> onChange { it.copy(chunkSizeTokens = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_133e52bffa), config.retryChunkSizeTokens, 500..6000, 250, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_0d35008623)) { value -> onChange { it.copy(retryChunkSizeTokens = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_74ab8f9e64), config.maxOutputTokens, 64..4096, 64, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_bf4ee490da)) { value -> onChange { it.copy(maxOutputTokens = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_74138b8527), config.maxLocalModelCalls, 1..48, 1, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_da8ff75cb9)) { value -> onChange { it.copy(maxLocalModelCalls = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_a5fbcb12c8), config.maxConcurrentDelegates, 1..4, 1, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_1bd602d4af)) { value -> onChange { it.copy(maxConcurrentDelegates = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_420dbf5cb5), config.timeoutSeconds, 5..300, 5, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_78f519fc5c)) { value -> onChange { it.copy(timeoutSeconds = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_1a0f1f6814), config.timeToFirstTokenTimeoutSeconds, 5..90, 5, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_b57abf784e)) { value -> onChange { it.copy(timeToFirstTokenTimeoutSeconds = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_9aa30d19f8), config.idleTokenTimeoutSeconds, 5..90, 5, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_f47f1e86b9)) { value -> onChange { it.copy(idleTokenTimeoutSeconds = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_3833af9fe5), config.maxDelegateRuntimeSeconds, 30..120, 5, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_94f050b3fc)) { value -> onChange { it.copy(maxDelegateRuntimeSeconds = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_51ac45f570), config.maxCallsPerTurn, 1..16, 1, !busy) { value -> onChange { it.copy(maxCallsPerTurn = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_1bc312a97a), config.preparationTimeoutSeconds, 30..3600, 30, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_9ca1ae9b7d)) { value -> onChange { it.copy(preparationTimeoutSeconds = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_bb4dbba9e2), config.maxWastedLocalTokensPerTurn, 1000..64000, 1000, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_6f05522f67)) { value -> onChange { it.copy(maxWastedLocalTokensPerTurn = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_47abb318e6), config.evidenceSufficiencyPercent, 50..100, 5, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_0722d488e8)) { value -> onChange { it.copy(evidenceSufficiencyPercent = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_82c8090017), config.compactionThresholdCharacters, 256..48000, 256, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_23d8bb2b38)) { value -> onChange { it.copy(compactionThresholdCharacters = value) } }
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_these_controls_apply_to_delegation_the_main_m_2a70c), style = MaterialTheme.typography.bodySmall)
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_b93239d511), config.localRetryLimit, 0..10, 1, !busy, androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_19bb6ef9e9)) { value -> onChange { it.copy(localRetryLimit = value) } }
                        DelegationSlider(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_bd970cebdc), config.lowBatteryThresholdPercent, 0..50, 1, !busy) { value -> onChange { it.copy(lowBatteryThresholdPercent = value) } }
                        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_requests_for_missing_or_unauthorized_models_s_8f716), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            FilterChip(selected = false, onClick = { resetConfirm = true }, label = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_reset_recommended_defaults_f334a)) }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) }, enabled = !busy)
                        }
                    }
                }
            }
        }
    }
    if (previewPlan) androidx.compose.material3.AlertDialog(onDismissRequest = { previewPlan = false }, title = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_preview_plan_11340)) }, text = { Text(dev.chungjungsoo.gptmobile.data.delegation.DelegationPolicyResolver.preview(config, profiles)) }, confirmButton = { TextButton(onClick = { previewPlan = false }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_close_7d9eb)) } })
    if (resetConfirm) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { resetConfirm = false }, title = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_restore_tuning_defaults_36a10)) }, text = { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_research_and_budget_tuning_will_return_to_rec_c3851)) }, confirmButton = {
            TextButton(onClick = {
                onReset()
                resetConfirm = false
            }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_reset_tuning_e6ee5)) }
        }, dismissButton = { TextButton(onClick = { resetConfirm = false }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_cancel_19766)) } })
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        SettingsHelpIcon(subtitle)
    }
}

@Composable
private fun StatusPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, Modifier.height(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DelegationSlider(label: String, value: Int, range: IntRange, step: Int, enabled: Boolean, hint: String? = null, save: (Int) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$label: ${draft.roundToInt()}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            hint?.let { SettingsHelpIcon(it) }
        }
        var exact by remember(value) { mutableStateOf(value.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(exact, { exact = it }, label = { Text("${range.first}–${range.last}") }, isError = exact.toIntOrNull()?.let { it !in range } != false, enabled = enabled, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(enabled = enabled && exact.toIntOrNull()?.let { it in range && it != value } == true, onClick = { exact.toIntOrNull()?.takeIf { it in range }?.let(save) }) { Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_set_b6f6f)) }
        }
        Slider(
            value = draft,
            onValueChange = { draft = ((it / step).roundToInt() * step).coerceIn(range).toFloat() },
            onValueChangeFinished = { if (draft.roundToInt() != value) save(draft.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label }
        )
    }
}
