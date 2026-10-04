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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import dev.chungjungsoo.gptmobile.presentation.common.SettingsHelpIcon
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
                        Icon(Icons.Default.AutoAwesome, "", Modifier.padding(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Model Delegation", style = MaterialTheme.typography.headlineSmall)
                            SettingsHelpIcon("A delegate can research, read pages, and compress tool results so the main model receives a focused answer with evidence.")
                        }
                        Text(if (config.enabled) "${100 - config.processingOwnership} Delegation Amount · ${config.strategy} Research Depth" else "Turn On To Give Tasks To A Delegate", style = MaterialTheme.typography.bodyMedium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            if (config.enabled) "Enabled" else "Disabled",
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
                    StatusPill(Icons.Default.Memory, "Up to ${config.effectiveLocalModelCalls()} delegate calls")
                    StatusPill(Icons.Default.Bolt, "Compact evidence brief")
                    if (config.remoteWorkersAllowed()) StatusPill(Icons.Default.Cloud, "Remote workers on")
                    if (config.reviewerEnabled) StatusPill(Icons.Default.AutoAwesome, "Reviewer on")
                }
            }
        }
        PrimaryTabRow(selectedTabIndex = settingsTab) {
            Tab(
                selected = settingsTab == 0,
                onClick = { settingsTab = 0 },
                text = { Text("Delegation") },
                icon = { Icon(Icons.Default.Bolt, null) }
            )
            Tab(
                selected = settingsTab == 1,
                onClick = { settingsTab = 1 },
                text = { Text("Reviewer") },
                icon = { Icon(Icons.Default.AutoAwesome, null) }
            )
        }

        if (settingsTab == 0) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading("Delegate Model", "Choose an AI profile to handle delegated work.")
                    LocalToolToggle(
                        "Allow Cloud Helpers",
                        config.remoteWorkersAllowed(),
                        !busy,
                        "Cloud delegates receive delegated content and use their provider's tokens. Turn this off to use only this device or a private server."
                    ) { value -> onChange { it.withRemoteWorkersAllowed(value) } }
                    cloudRecallEnabled?.let { enabled ->
                        LocalToolToggle(
                            "Allow Recall In Cloud Requests",
                            enabled,
                            !busy && !cloudRecallBusy,
                            "When enabled, relevant saved local memories can be included as reference context in cloud AI requests."
                        ) { onCloudRecallChange(it) }
                    }
                    DelegateModelDropdown(
                        profiles = helperEligible,
                        selectedProfileUid = config.targetProfileUid,
                        enabled = !busy,
                        onSelected = { profile -> onChange { it.copy(targetProfileUid = profile?.uid.orEmpty()) } },
                        scores = modelScores
                    )
                    if (selectedDelegate == null) {
                        Text("Select an enabled profile with a working model and connection in AI profiles. If it becomes unavailable, another eligible delegate may be used.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (settingsTab == 1) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading("Reviewer", "Use a second, different model to fact-check the delegate before the handoff reaches the primary model.")
                    LocalToolToggle("Enable Reviewer", config.reviewerEnabled, !busy) { value ->
                        onChange {
                            it.copy(
                                reviewerEnabled = value,
                                reviewerProfileUid = if (value) it.reviewerProfileUid else ""
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Reviewer Validation", style = MaterialTheme.typography.labelLarge)
                        SettingsHelpIcon("The reviewer receives the original delegated task and the delegate's final context, returns a 0–100 Reviewer Score, and can correct unsupported or contradictory details. It cannot be the delegate, primary model, or another profile using the same model.")
                    }
                    if (config.reviewerEnabled) {
                        DelegateModelDropdown(
                            profiles = reviewerEligible,
                            selectedProfileUid = config.reviewerProfileUid,
                            enabled = !busy,
                            onSelected = { profile -> onChange { it.copy(reviewerProfileUid = profile?.uid.orEmpty()) } },
                            roleLabel = "Reviewer Model",
                            scores = modelScores
                        )
                        val selectedReviewer = reviewerEligible.firstOrNull { it.uid == config.reviewerProfileUid }
                        if (selectedReviewer == null) {
                            Text(
                                "Choose a reviewer profile that uses a different model from the delegate. If no reviewer is available, the delegate context is marked unverified and receives a Reviewer Score of 0.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        DelegationSlider("Review Time Budget", config.reviewTimeoutSeconds, 15..120, 15, !busy, "Total time for review and evidence correction. Repairs do not reset this deadline.") { value -> onChange { it.copy(reviewTimeoutSeconds = value) } }
                        DelegationSlider(
                            "Reviewer Output Tokens",
                            config.reviewerOutputTokens,
                            128..1024,
                            64,
                            !busy,
                            "Budget for the score, findings, and optional corrected context."
                        ) { value -> onChange { it.copy(reviewerOutputTokens = value) } }
                        DelegationSlider(
                            "Minimum Reviewer Score",
                            config.reviewerMinimumScore,
                            0..100,
                            5,
                            !busy,
                            "Rejected evidence is corrected and reviewed again. If it still fails, the primary model recovers independently."
                        ) { value -> onChange { it.copy(reviewerMinimumScore = value) } }
                        DelegationSlider(
                            "Reviewer Retry Attempts",
                            config.reviewerRetryLimit,
                            0..5,
                            1,
                            !busy,
                            "Retries rejected evidence through delegate correction and another independent review. Also retries malformed or unavailable assessments."
                        ) { value -> onChange { it.copy(reviewerRetryLimit = value) } }
                        LocalToolToggle("Allow Reviewer Corrections", config.reviewerAutoCorrect, !busy) { value ->
                            onChange { it.copy(reviewerAutoCorrect = value) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Correction Policy", style = MaterialTheme.typography.labelLarge)
                            SettingsHelpIcon("Controls whether accepted reviewer corrections replace the draft. Rejected drafts always require correction or independent primary recovery.")
                        }
                    }
                }
            }
        }
        if (settingsTab == 0) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading("Workload", "Adjust how much the delegate does and how widely it researches.")
                    DelegationSlider(
                        "Delegation Amount",
                        100 - config.processingOwnership,
                        0..100,
                        5,
                        !busy,
                        "Less → More. Recommended: 80. Higher values favor the delegate for research and tool results; the main model writes the final answer. This is a routing preference, not a guaranteed token percentage."
                    ) { value -> onChange { it.withDelegationAmount(value) } }
                    DelegationSlider(
                        "Research Depth",
                        config.strategy,
                        0..100,
                        5,
                        !busy,
                        "Focused → Broad. Recommended: 70. More coverage reads more sources while keeping each helper request bounded."
                    ) { value -> onChange { it.withStrategy(value) } }
                    Text("Up to ${config.effectiveLocalModelCalls()} delegate calls · ${config.maxSearchQueries} searches · ${config.maxPages} pages per research pass", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeading("Automatic Assistance", "Recommended on for substantial delegation.")
                    LocalToolToggle("Delegate Web Research", config.researchEnabled, !busy) { value -> onChange { it.copy(researchEnabled = value) } }
                    LocalToolToggle("Research Before Answering", config.automaticResearch, !busy && config.researchEnabled) { value -> onChange { it.copy(automaticResearch = value) } }
                    LocalToolToggle("Compress Large Tool Results", config.compactToolResults, !busy) { value -> onChange { it.copy(compactToolResults = value) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Research Tools", style = MaterialTheme.typography.labelLarge)
                        SettingsHelpIcon(
                            "Uses the conversation's enabled tools and permissions. Delegated research shares the active AI profile's Maximum Tool Calls limit. " +
                                "Multi-engine web search counts each enabled engine and page read as a tool execution. Research sends sources and a compact brief to the main model."
                        )
                    }
                    Text(
                        "Research shares the active AI profile's Maximum Tool Calls limit. Multi-engine searches can consume several calls per query. " +
                            "A separate context-derived tool-result byte budget also applies; if it is reached, chat shows the exact byte limit and preserves any completed delegate brief.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { onReset() }, enabled = !busy) {
                        Icon(Icons.Default.RestartAlt, null)
                        Text("Restore Recommended Defaults", Modifier.padding(start = 8.dp))
                    }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { SectionHeading("Advanced Controls", "Fine-tune budgets, timeouts, and research breadth.") }
                        IconButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.testTag("delegation_advanced")) { Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Toggle advanced controls") }
                    }
                    if (!showAdvanced) {
                        Text("${config.maxOutputTokens} target output tokens per helper call · ${config.maxConcurrentDelegates} concurrent workers. Expand to customize limits.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (showAdvanced) {
                        DelegationSlider("Maximum Worker Delegation Depth", config.maxDelegationDepth, 1..2, 1, !busy) { value -> onChange { it.copy(maxDelegationDepth = value) } }
                        Text("Research Limits", style = MaterialTheme.typography.titleMedium)
                        DelegationSlider("Search Queries", config.maxSearchQueries, 1..20, 1, !busy) { value -> onChange { it.copy(maxSearchQueries = value) } }
                        DelegationSlider("Results Per Search Engine", config.searchResultsPerEngine, 1..10, 1, !busy) { value -> onChange { it.copy(searchResultsPerEngine = value) } }
                        DelegationSlider("Pages To Read", config.maxPages, 0..32, 1, !busy) { value -> onChange { it.copy(maxPages = value) } }
                        DelegationSlider("Crawl Depth", config.crawlDepth, 0..8, 1, !busy) { value -> onChange { it.copy(crawlDepth = value) } }
                        DelegationSlider("Parallel Page Requests", config.pageFetchConcurrency, 1..16, 1, !busy) { value -> onChange { it.copy(pageFetchConcurrency = value) } }
                        DelegationSlider("Page Characters To Process", config.maxPageCharacters, 1000..96000, 1000, !busy) { value -> onChange { it.copy(maxPageCharacters = value) } }
                        Text("Handoff And Helper Workload", style = MaterialTheme.typography.titleMedium)
                        DelegationSlider("Remote Brief Token Budget", config.handoffTokens, 128..8192, 128, !busy, "A byte-based estimate. Lower values reduce remote context; sources and limitations remain attached.") { value -> onChange { it.copy(handoffTokens = value) } }
                        DelegationSlider("Delegate Input Characters Per Step", config.maxInputCharacters, 1000..64000, 500, !busy) { value -> onChange { it.copy(maxInputCharacters = value) } }
                        DelegationSlider("Max Input Tokens Per Delegate", config.maxInputTokensPerDelegate, 1000..12000, 500, !busy, "Hard preflight cap including the worker prompt and retained tool schemas. Oversized tasks are chunked before inference.") { value -> onChange { it.copy(maxInputTokensPerDelegate = value) } }
                        DelegationSlider("Chunk Size", config.chunkSizeTokens, 1000..12000, 500, !busy, "Large delegated payloads are split near this token size instead of truncating one giant request.") { value -> onChange { it.copy(chunkSizeTokens = value) } }
                        DelegationSlider("Retry Chunk Size", config.retryChunkSizeTokens, 500..6000, 250, !busy, "A failed chunk is retried only as smaller pieces; the original oversized payload is never replayed.") { value -> onChange { it.copy(retryChunkSizeTokens = value) } }
                        DelegationSlider("Delegate Output Tokens Per Step", config.maxOutputTokens, 64..4096, 64, !busy, "Reasoning models reserve at least 2,048 tokens; a repair may use up to 8,192. The model profile limit remains the hard ceiling.") { value -> onChange { it.copy(maxOutputTokens = value) } }
                        DelegationSlider("Delegate Model Calls Per Turn", config.maxLocalModelCalls, 1..48, 1, !busy, "Shared by planning, page summaries and tool-result processing.") { value -> onChange { it.copy(maxLocalModelCalls = value) } }
                        DelegationSlider("Maximum Concurrent Delegates", config.maxConcurrentDelegates, 1..4, 1, !busy, "One is safest for on-device inference. Increase only when the selected backend can run independent workers safely.") { value -> onChange { it.copy(maxConcurrentDelegates = value) } }
                        DelegationSlider("Research Timeout In Seconds", config.timeoutSeconds, 5..300, 5, !busy, "Legacy ceiling. Per-worker adaptive deadlines are also limited by the maximum delegate runtime below.") { value -> onChange { it.copy(timeoutSeconds = value) } }
                        DelegationSlider("Time-To-First-Token Timeout", config.timeToFirstTokenTimeoutSeconds, 5..90, 5, !busy, "Cancels a worker that never produces output or tool activity.") { value -> onChange { it.copy(timeToFirstTokenTimeoutSeconds = value) } }
                        DelegationSlider("Idle-Token Timeout", config.idleTokenTimeoutSeconds, 5..90, 5, !busy, "Cancels a worker that started but stops making output/tool progress.") { value -> onChange { it.copy(idleTokenTimeoutSeconds = value) } }
                        DelegationSlider("Maximum Delegate Runtime", config.maxDelegateRuntimeSeconds, 30..120, 5, !busy, "Absolute hard ceiling; adaptive small and medium jobs finish earlier.") { value -> onChange { it.copy(maxDelegateRuntimeSeconds = value) } }
                        DelegationSlider("Delegations Per Turn", config.maxCallsPerTurn, 1..16, 1, !busy) { value -> onChange { it.copy(maxCallsPerTurn = value) } }
                        DelegationSlider("Preparation Time Budget", config.preparationTimeoutSeconds, 30..300, 30, !busy, "Whole-turn research and review deadline, including retries.") { value -> onChange { it.copy(preparationTimeoutSeconds = value) } }
                        DelegationSlider("Maximum Wasted Local Tokens Per Turn", config.maxWastedLocalTokensPerTurn, 1000..64000, 1000, !busy, "Stops new workers after failed or canceled work consumes this estimated token budget.") { value -> onChange { it.copy(maxWastedLocalTokensPerTurn = value) } }
                        DelegationSlider("Stop When Evidence Sufficient", config.evidenceSufficiencyPercent, 50..100, 5, !busy, "Higher values gather more evidence before stopping; lower values reduce marginal delegate work.") { value -> onChange { it.copy(evidenceSufficiencyPercent = value) } }
                        DelegationSlider("Process Tool Results Above Characters", config.compactionThresholdCharacters, 256..48000, 256, !busy, "Small results pass through to avoid unnecessary local inference.") { value -> onChange { it.copy(compactionThresholdCharacters = value) } }
                        Text("These controls apply to delegation. The main model's output limit is unchanged. When the local budget runs out, the brief identifies omitted evidence.", style = MaterialTheme.typography.bodySmall)
                        DelegationSlider("Same-Delegate Retries Before Failover", config.localRetryLimit, 5..10, 1, !busy, "At least five retries are always attempted. Retries are spaced one second apart before another delegate can be selected.") { value -> onChange { it.copy(localRetryLimit = value) } }
                        DelegationSlider("Pause Threshold For Low Battery", config.lowBatteryThresholdPercent, 0..50, 1, !busy) { value -> onChange { it.copy(lowBatteryThresholdPercent = value) } }
                        Text("Requests for missing or unauthorized models stop immediately. Final answers use the main profile's output limit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            FilterChip(selected = false, onClick = { onReset() }, label = { Text("Reset Recommended Defaults") }, leadingIcon = { Icon(Icons.Default.RestartAlt, null) }, enabled = !busy)
                        }
                    }
                }
            }
        }
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
