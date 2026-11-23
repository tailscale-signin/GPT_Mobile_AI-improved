package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonArm
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonCase
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonFailure
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonLeg
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonLegStatus
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonPercentiles
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonResult
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonRunner
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyComparisonStatus
import java.util.Locale

@Composable
internal fun PrivacyComparisonPanel(viewModel: PrivacyComparisonViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) {
        onDispose { viewModel.cancel() }
    }
    PrivacyComparisonContent(state, viewModel::selectProfile, viewModel::runComparison, viewModel::cancel)
}

/** Opening this section and choosing a profile never starts a model request. */
@Composable
internal fun PrivacyComparisonContent(
    state: PrivacyComparisonUiState,
    onSelectProfile: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var promptsVisible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { expanded = !expanded },
            enabled = !state.running,
            modifier = Modifier.testTag("privacy_comparison_toggle")
        ) { Text(if (expanded || state.running) "Hide AI profile comparison" else "Compare privacy with an AI profile") }
        if (expanded || state.running) {
            Text("Fictional task comparison", style = MaterialTheme.typography.titleSmall)
            Text(
                "Compare privacy off and on using the same saved profile and four fixed fictional prompts. Your chats, saved private terms and custom system instructions are not included. Current privacy categories apply to the protected requests.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Run starts up to two unscored warmups and eight scored generations, alternating which arm goes first. Each request allows up to ${PrivacyComparisonRunner.OUTPUT_TOKENS} output tokens and 60 seconds; the whole run is limited to 10 minutes.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Remote profiles send these fictional inputs over the network and may incur normal provider charges. This comparison does not verify the provider's retention policy or general answer quality. On-device profiles use their local inference route.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ComparisonProfilePicker(state, onSelectProfile)
            Row {
                TextButton(
                    onClick = onRun,
                    enabled = !state.running && state.selectedProfile != null,
                    modifier = Modifier.testTag("privacy_comparison_run")
                ) { Text("Run comparison") }
                if (state.running) {
                    TextButton(
                        onClick = onCancel,
                        enabled = !state.cancelRequested,
                        modifier = Modifier.testTag("privacy_comparison_cancel")
                    ) { Text(if (state.cancelRequested) "Cancelling…" else "Cancel comparison") }
                }
            }
            state.runProfile?.let { profile ->
                Text("Run profile: ${profile.name} · ${profile.model} · ${profile.provider.name}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("privacy_comparison_run_profile"))
            }
            state.result?.let { result ->
                ComparisonProgress(result, state.cancelRequested)
            }
            TextButton(onClick = { promptsVisible = !promptsVisible }, modifier = Modifier.testTag("privacy_comparison_prompts_toggle")) {
                Text(if (promptsVisible) "Hide fictional prompts" else "Preview fictional prompts")
            }
            if (promptsVisible) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("privacy_comparison_prompts")) {
                    Text("Preparation (unscored): ${PrivacyComparisonCase.WARMUP.prompt}", style = MaterialTheme.typography.bodySmall)
                    PrivacyComparisonCase.scored.forEach { case ->
                        Text(case.title, style = MaterialTheme.typography.labelLarge)
                        Text(case.prompt, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            state.result?.let { result ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("privacy_comparison_results")) {
                    Text("Deterministic fixture checks", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "These checks cover the requested format, supplied facts and listed obligations. They are not a general assessment of usefulness. Missing, failed or interrupted responses are not scored as successful. Answer timings include request preparation. Timing and provider-reported usage are shown separately; missing observations stay unknown.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    ComparisonTimingSummary(result)
                    PrivacyComparisonCase.scored.forEach { case ->
                        ComparisonPair(result, case)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonProfilePicker(state: PrivacyComparisonUiState, onSelectProfile: (String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { menuOpen = true },
            enabled = !state.running && state.profiles.isNotEmpty(),
            modifier = Modifier.testTag("privacy_comparison_profile")
        ) {
            Text(state.selectedProfile?.let { "${it.name} · ${it.model}" } ?: "Choose an enabled AI profile")
        }
        DropdownMenu(expanded = menuOpen && !state.running, onDismissRequest = { menuOpen = false }) {
            state.profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text("${profile.name} · ${profile.model} · ${if (profile.onDevice) "On device" else profile.provider.name}") },
                    onClick = {
                        menuOpen = false
                        onSelectProfile(profile.uid)
                    },
                    modifier = Modifier.testTag("privacy_comparison_profile_${profile.uid}")
                )
            }
        }
    }
    if (state.profiles.isEmpty()) Text("Enable or create an AI profile in settings to run this comparison.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ComparisonProgress(result: PrivacyComparisonResult, cancelling: Boolean) {
    val title = when {
        cancelling -> "Cancelling comparison…"
        result.status == PrivacyComparisonStatus.RUNNING -> "Comparison running"
        result.status == PrivacyComparisonStatus.CANCELLED -> "Comparison cancelled; recorded responses retained"
        result.status == PrivacyComparisonStatus.COMPLETE && result.scoredCompleted == result.scoredTotal -> "Comparison finished"
        else -> "Comparison incomplete"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("privacy_comparison_status")) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text("Unscored warmups: ${result.warmupCompleted}/${result.warmupTotal}. Completed scored responses: ${result.scoredCompleted}/${result.scoredTotal}.", style = MaterialTheme.typography.bodySmall)
        if (result.status == PrivacyComparisonStatus.RUNNING && result.activeCaseId != null) {
            val case = PrivacyComparisonCase.fromId(result.activeCaseId)
            Text("Current: ${case?.title ?: "Preparation"} · ${result.activeArm?.label() ?: "Preparing"}", style = MaterialTheme.typography.bodySmall)
        }
        result.failure?.let { Text(it.message(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ComparisonTimingSummary(result: PrivacyComparisonResult) {
    val groups = result.timingSummaries()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("privacy_comparison_timing_summary")) {
        Text("Paired timing samples", style = MaterialTheme.typography.titleSmall)
        Text(
            "Only complete pairs with matching observed runtime and output limits and recorded routes contribute. Warmups, incomplete pairs, configuration changes and unverified strict-route settings are excluded. Completed responses remain in the timing sample even when a fixture criterion is unmet.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Each metric shows its sample count (n), p50 and p95 using nearest-rank sample percentiles. With at most four pairs, p95 is the largest observed value. These are exploratory samples, not a causal estimate of privacy overhead or proof of full provider-settings parity.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (groups.isEmpty()) {
            Text("No eligible pairs for timing summaries yet.", style = MaterialTheme.typography.bodySmall)
        }
        groups.forEachIndexed { index, group ->
            Card(Modifier.fillMaxWidth().testTag("privacy_comparison_timing_group_$index")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${group.pairedCases} scored pairs in this group.", style = MaterialTheme.typography.labelLarge)
                    Text("Reported runtime: ${group.runtime}; output limit: ${group.outputTokens}. Routes: privacy off ${routeLabel(group.offRoute)}, privacy on ${routeLabel(group.onRoute)}.", style = MaterialTheme.typography.bodySmall)
                    group.arms.forEach { arm ->
                        Text(arm.arm.label(), style = MaterialTheme.typography.labelLarge)
                        Text("First answer text: ${percentiles(arm.firstTextMs)}.", style = MaterialTheme.typography.bodySmall)
                        Text("Completed answer: ${percentiles(arm.completionMs)}.", style = MaterialTheme.typography.bodySmall)
                        val unavailable = when {
                            arm.arm == PrivacyComparisonArm.OFF -> "Not applicable (privacy off)"
                            group.onRoute == "LOCAL" -> "Not applicable (on-device route)"
                            else -> "Not observed"
                        }
                        Text("Local masking: ${percentiles(arm.maskingNanos, nanos = true, unavailable = unavailable)}.", style = MaterialTheme.typography.bodySmall)
                        Text("Local restoration: ${percentiles(arm.restorationNanos, nanos = true, unavailable = unavailable)}.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonPair(result: PrivacyComparisonResult, case: PrivacyComparisonCase) {
    var details by remember(result.runId, case.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("privacy_comparison_pair_${case.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(case.title, style = MaterialTheme.typography.titleSmall)
            Text(
                when (result.pairStatus(case)) {
                    "OBSERVED_CONFIGURATION_MATCHES" -> "Reported backend and output limit match."
                    "REQUESTED_CONFIGURATION_MATCHES_OBSERVATION_INCOMPLETE" -> "Same requested configuration; actual backend evidence is incomplete."
                    "OBSERVED_CONFIGURATION_DIFFERS" -> "Observed configuration differs. Treat these as separate results."
                    "CONFIGURATION_NOT_COMPARABLE" -> "The protected route uses different or unverified settings. Treat these as separate results."
                    else -> "Pair incomplete. Both completed responses are required for comparison."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("privacy_comparison_pair_status_${case.id}")
            )
            PrivacyComparisonArm.entries.forEach { arm ->
                val leg = result.legsFor(case).singleOrNull { it.arm == arm }
                ComparisonLeg(leg, arm)
            }
            if (result.legsFor(case).isNotEmpty()) {
                TextButton(onClick = { details = !details }, modifier = Modifier.testTag("privacy_comparison_details_${case.id}")) {
                    Text(if (details) "Hide checks and answers" else "Show checks and answers")
                }
                if (details) {
                    result.legsFor(case).forEach { leg ->
                        Text(leg.arm.label(), style = MaterialTheme.typography.labelLarge)
                        if (leg.status == PrivacyComparisonLegStatus.COMPLETE) {
                            leg.criteria.forEach { Text("${if (it.passed) "Met" else "Unmet"}: ${it.label}", style = MaterialTheme.typography.bodySmall) }
                        }
                        leg.policy?.let { policy ->
                            Text("Policy revision ${policy.revision}; protection revision ${policy.protectionRevision}. Enabled categories: ${policy.categories.sortedBy { it.ordinal }.joinToString { it.title }}.", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Reported runtime: ${leg.actualRuntime ?: "Not observed"}. Effective output limit: ${leg.effectiveOutputTokens?.toString() ?: "Not observed"}.", style = MaterialTheme.typography.bodySmall)
                        Text(leg.answer.ifEmpty { "No answer received." }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonLeg(leg: PrivacyComparisonLeg?, arm: PrivacyComparisonArm) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(arm.label(), style = MaterialTheme.typography.labelLarge)
        if (leg == null) {
            Text("No response recorded.", style = MaterialTheme.typography.bodySmall)
        } else {
            val state = when (leg.status) {
                PrivacyComparisonLegStatus.COMPLETE -> "Completed"
                PrivacyComparisonLegStatus.PARTIAL -> "Partial response"
                PrivacyComparisonLegStatus.FAILED -> "Failed"
                PrivacyComparisonLegStatus.BLOCKED -> "Blocked before completion"
                PrivacyComparisonLegStatus.TIMED_OUT -> "Timed out"
                PrivacyComparisonLegStatus.CANCELLED -> "Cancelled"
            }
            Text(state, style = MaterialTheme.typography.bodySmall)
            val onDevice = leg.measurements.routeKind == "LOCAL"
            Text("Observed request route: ${routeLabel(leg.measurements.routeKind)}.", style = MaterialTheme.typography.bodySmall)
            Text(
                if (leg.status == PrivacyComparisonLegStatus.COMPLETE && leg.criteria.isNotEmpty()) {
                    "Fixture criteria met: ${leg.criteria.count { it.passed }}/${leg.criteria.size}."
                } else {
                    "Fixture criteria: not scored."
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text("First answer text: ${milliseconds(leg.firstTextMs)}. Completed answer: ${milliseconds(leg.completionMs)}. Elapsed: ${milliseconds(leg.elapsedMs)}.", style = MaterialTheme.typography.bodySmall)
            val unavailable = when {
                arm == PrivacyComparisonArm.OFF -> "Not applicable (privacy off)"
                onDevice -> "Not applicable (on-device route)"
                else -> "Not observed"
            }
            Text("Local masking: ${nanoseconds(leg.measurements.maskingNanos, unavailable)}. Local restoration: ${nanoseconds(leg.measurements.restorationNanos, unavailable)}.", style = MaterialTheme.typography.bodySmall)
            Text("Provider-reported tokens: input ${leg.inputTokens?.toString() ?: "unknown"}, output ${leg.outputTokens?.toString() ?: "unknown"}.", style = MaterialTheme.typography.bodySmall)
            if (arm == PrivacyComparisonArm.ON) {
                val detection = leg.detection
                Text(
                    if (detection != null) {
                        "Enabled-category fixture values protected: ${detection.protectedValues}/${detection.expectedValues}. Public phrases preserved: ${detection.preservedPublicPhrases}/${detection.publicPhrases}."
                    } else {
                        "Remote masking coverage: ${if (onDevice) "not applicable to the on-device route" else "not observed"}."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Wire validation: ${leg.measurements.wireOutcome ?: if (onDevice) "not applicable to the on-device route" else "not observed"}.", style = MaterialTheme.typography.bodySmall)
            }
            leg.failure?.let { Text(it.message(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun PrivacyComparisonArm.label(): String = if (this == PrivacyComparisonArm.ON) "Privacy on" else "Privacy off"

private fun milliseconds(value: Long?): String = value?.let { "$it ms" } ?: "Not observed"

private fun nanoseconds(value: Long?, unavailable: String): String = value?.let { String.format(Locale.ROOT, "%.3f ms", it / 1_000_000.0) } ?: unavailable

private fun percentiles(value: PrivacyComparisonPercentiles, nanos: Boolean = false, unavailable: String = "Not observed"): String {
    fun time(measured: Long?) = if (nanos) nanoseconds(measured, unavailable) else milliseconds(measured)
    return "n=${value.sampleCount}; p50 ${time(value.p50)}; p95 ${time(value.p95)}"
}

private fun routeLabel(route: String?): String = when (route) {
    "LOCAL" -> "On device"
    "ORDINARY_TEXT" -> "Ordinary text"
    "MASKED_TEXT" -> "Masked text"
    "STRICT_PRIVATE_TEXT" -> "Strict private text"
    else -> "Not observed"
}

private fun PrivacyComparisonFailure.message(): String = when (this) {
    PrivacyComparisonFailure.PROFILE_CHANGED -> "The selected profile changed or was removed. Start a new comparison with the current profile."
    PrivacyComparisonFailure.POLICY_CHANGED -> "Privacy settings changed or could not be held constant. Start a new comparison after reviewing the settings."
    PrivacyComparisonFailure.UNSUPPORTED_PROFILE -> "This profile cannot provide the required bounded comparison. Choose another enabled profile."
    PrivacyComparisonFailure.INITIALIZATION_FAILED -> "The comparison could not initialize. Check the selected profile before trying again."
    PrivacyComparisonFailure.PROVIDER_FAILED -> "The provider request failed. Recorded responses are retained."
    PrivacyComparisonFailure.INCOMPLETE_STREAM -> "The provider did not finish the response."
    PrivacyComparisonFailure.UNEXPECTED_TOOL -> "The provider attempted tool use during this tool-free comparison."
    PrivacyComparisonFailure.OUTPUT_LIMIT -> "The response reached the comparison's output limit."
    PrivacyComparisonFailure.TIMEOUT -> "The request reached its time limit."
    PrivacyComparisonFailure.OVERALL_TIMEOUT -> "The comparison reached its overall time limit."
    PrivacyComparisonFailure.CANCELLED -> "The comparison was cancelled. A new run requires pressing Run comparison."
    PrivacyComparisonFailure.WARMUP_FAILED -> "An unscored warmup did not complete its instruction. The scored comparison stopped."
}
