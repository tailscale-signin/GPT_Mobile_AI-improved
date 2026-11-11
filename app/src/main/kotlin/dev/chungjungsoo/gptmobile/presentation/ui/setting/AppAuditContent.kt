package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.audit.AppAuditObservationSource
import dev.chungjungsoo.gptmobile.data.audit.AppAuditOutcome
import dev.chungjungsoo.gptmobile.data.audit.AppAuditPlanEntry
import dev.chungjungsoo.gptmobile.data.audit.safeAuditDiagnosticField
import dev.chungjungsoo.gptmobile.data.audit.safeAuditFailureCategory
import dev.chungjungsoo.gptmobile.data.audit.safeAuditFailurePhase
import dev.chungjungsoo.gptmobile.data.audit.safeAuditReason
import dev.chungjungsoo.gptmobile.data.audit.safeAuditRemoteIdentity
import dev.chungjungsoo.gptmobile.data.audit.safeAuditValidationRule
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatchState
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

@Composable
internal fun AppAuditControls(
    ready: Boolean,
    busy: Boolean,
    allowNetworkReads: Boolean,
    onAllowNetworkReads: (Boolean) -> Unit,
    modelProfiles: List<PlatformV2>,
    selectedProfileIds: List<String>,
    onToggleProfile: (String) -> Unit,
    onStart: () -> Unit
) {
    var showTargets by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("app_audit_controls")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Comprehensive app audit", style = MaterialTheme.typography.titleLarge)
            Text("Check app subsystems and configured integrations, then inspect measured results and export their diagnostic evidence. No AI profile is required.")
            Text("The accepted plan keeps unsupported, blocked and untested checks visible. A completed audit does not mean every feature was exercised.", style = MaterialTheme.typography.bodySmall)
            Row(
                Modifier.fillMaxWidth().toggleable(allowNetworkReads, enabled = !busy, role = Role.Checkbox, onValueChange = onAllowNetworkReads).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(allowNetworkReads, onCheckedChange = null)
                Text("Include configured service read checks", Modifier.weight(1f))
            }
            Text("Optional reads use configured connections with bounded requests. This audit does not generate model answers or run tools that change external data.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showTargets = !showTargets }) {
                Text(if (showTargets) "Hide optional model targets" else "Optional model targets (${selectedProfileIds.size} selected)")
            }
            if (showTargets) {
                Text("Select exact saved model profiles to record configuration and generation coverage. Gateway metadata requires network reads. Generation stays untested until you explicitly run a model benchmark. No target is selected automatically.", style = MaterialTheme.typography.bodySmall)
                if (modelProfiles.isEmpty()) Text("No eligible saved model profiles are available. App and plugin checks can still run.", style = MaterialTheme.typography.bodySmall)
                modelProfiles.forEach { profile ->
                    Row(
                        Modifier.fillMaxWidth().toggleable(profile.uid in selectedProfileIds, enabled = !busy, role = Role.Checkbox, onValueChange = { onToggleProfile(profile.uid) }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(profile.uid in selectedProfileIds, onCheckedChange = null)
                        Text(profile.name, Modifier.weight(1f))
                    }
                }
                val unavailable = selectedProfileIds.count { id -> modelProfiles.none { it.uid == id } }
                if (unavailable > 0) Text("$unavailable selected ${if (unavailable == 1) "target is" else "targets are"} no longer available; the audit will record that limitation.", style = MaterialTheme.typography.bodySmall)
                if (selectedProfileIds.isNotEmpty()) {
                    TextButton(onClick = { selectedProfileIds.forEach(onToggleProfile) }, enabled = !busy) { Text("Clear model targets") }
                }
            }
            Button(onClick = onStart, enabled = ready && !busy, modifier = Modifier.fillMaxWidth().testTag("app_audit_start")) {
                Text(if (!ready) "Loading saved results…" else "Run app audit")
            }
            if (busy) Text("A benchmark is already active. Its progress and stop control remain available.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun AppAuditResultCard(
    batch: BenchmarkBatch,
    active: Boolean,
    cancellationRequested: Boolean = false,
    exportEnabled: Boolean = true,
    resumeEnabled: Boolean = true,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onExport: () -> Unit,
    onDetails: (() -> Unit)? = null
) {
    val run = batch.audit
    if (run == null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("App audit evidence unavailable", style = MaterialTheme.typography.titleMedium)
                Text("This saved audit has no readable plan or results. It cannot supply a score or coverage.")
            }
        }
        return
    }
    val presentation = remember(run) { appAuditPresentation(run) }
    val summary = presentation.summary
    val status = when {
        active && cancellationRequested -> "Stopping app audit…"
        active && batch.state == BenchmarkBatchState.QUEUED -> "Preparing app audit"
        active -> "App audit running"
        else -> when (batch.state) {
            BenchmarkBatchState.QUEUED -> "Queued"
            BenchmarkBatchState.RUNNING -> "Awaiting execution recovery"
            BenchmarkBatchState.STOPPED -> "Stopped by request"
            BenchmarkBatchState.INTERRUPTED -> "Interrupted"
            BenchmarkBatchState.STORAGE_PAUSED -> "Paused: results could not be saved"
            BenchmarkBatchState.COMPLETED -> "Finished · recorded check outcomes"
            BenchmarkBatchState.COMPLETED_WITH_FAILURES -> if (summary.failed == 0 && summary.blocked > 0 && summary.canceled == 0) "Completed with blockers" else "Finished with failures"
            BenchmarkBatchState.COMPLETED_WITH_BLOCKERS -> "Completed with blockers"
        }
    }
    Card(Modifier.fillMaxWidth().testTag("app_audit_result_${batch.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App audit · ${benchmarkResultDate(run.startedAtEpochMs)}", style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Column(Modifier.semantics(mergeDescendants = true) {}.testTag("app_audit_score_and_coverage"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(presentation.verifiedText, style = MaterialTheme.typography.titleMedium)
                Text(presentation.coverageSummaryText, style = MaterialTheme.typography.bodyMedium)
            }
            Text(presentation.scoreText, style = MaterialTheme.typography.bodySmall)
            Text(presentation.weightText, style = MaterialTheme.typography.bodySmall)
            Text(presentation.evidenceText, style = MaterialTheme.typography.bodySmall)
            Text(presentation.coverageNotice, style = MaterialTheme.typography.bodySmall)
            Text("The score is 100 × passing weight / measured weight for eligible named checks. Configuration, cached information and untested checks do not earn points. It is separate from model quality and performance scores.", style = MaterialTheme.typography.bodySmall)
            Text(presentation.countsText, style = MaterialTheme.typography.bodySmall)
            appAuditReasonCodeMessage(run.stopReasonCode)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (active && summary.planned > 0) {
                LinearProgressIndicator(progress = { (summary.accounted.toFloat() / summary.planned).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("Recorded ${summary.accounted}/${summary.planned} outcomes; recorded counts include failures and exclusions.", style = MaterialTheme.typography.bodySmall)
                val current = run.plan.firstOrNull { it.id == run.activeProbeId }?.label
                Text("Current check: ${current ?: "Preparing or awaiting the next check"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Text("Source areas associated with exercised checks: ${summary.sourceAreasExercised}/${summary.sourceAreasPlanned}. Source-line coverage is not measured by this audit.", style = MaterialTheme.typography.bodySmall)
            batch.storageWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (active) {
                OutlinedButton(onClick = onCancel, enabled = !cancellationRequested, modifier = Modifier.fillMaxWidth()) {
                    Text(if (cancellationRequested) "Stopping…" else "Stop app audit")
                }
            } else if (batch.canResume) {
                OutlinedButton(onClick = onResume, enabled = resumeEnabled, modifier = Modifier.fillMaxWidth()) { Text("Resume remaining checks") }
            }
            TextButton(onClick = onExport, enabled = exportEnabled) { Text("Export audit evidence") }
            Text("Exports this audit's plan, outcomes, score inputs and safe diagnostic fields. Running or interrupted results retain their partial status.", style = MaterialTheme.typography.bodySmall)
            onDetails?.let { TextButton(onClick = it) { Text("View category and plugin results") } }
        }
    }
}

internal fun LazyListScope.appAuditEvidenceContent(
    batch: BenchmarkBatch,
    currentTargetNames: Map<String, String> = emptyMap(),
    onOpenConnection: ((String) -> Unit)? = null,
    onPrepareModelBenchmark: ((AppAuditPlanEntry) -> Unit)? = null,
    onOpenGatewaySettings: ((AppAuditPlanEntry) -> Unit)? = null,
    onCheckGateway: ((AppAuditPlanEntry) -> Unit)? = null
) {
    val run = batch.audit ?: return
    val presentation = appAuditPresentation(run, currentTargetNames)
    item(key = "audit.${batch.id}.provenance") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Category and plugin results", style = MaterialTheme.typography.titleLarge)
            Text("${run.catalogVersion} · ${run.buildVersion} (${run.buildVariant}) · source ${run.sourceCommit.take(12)}", style = MaterialTheme.typography.labelSmall)
            Text("Indexed source: ${run.sourceFileCount} files · ${run.sourceLineCount} lines. These are inventory counts, not executed lines.", style = MaterialTheme.typography.bodySmall)
            Text("Plan ${run.plan.size} entries · updated ${benchmarkResultDate(run.updatedAtEpochMs)}", style = MaterialTheme.typography.labelSmall)
            Text("Network probe attempts: ${run.networkAttemptBudgetUsed} · confirmed dispatches: ${run.networkProbesConfirmed} · dispatch uncertain: ${run.networkProbesDispatchUnknown}", style = MaterialTheme.typography.bodySmall)
            Text("An attempt consumes the conservative probe budget before a request outcome is known. It does not by itself prove that the server received a request.", style = MaterialTheme.typography.bodySmall)
        }
    }
    presentation.components.forEach { component ->
        item(key = "audit.${batch.id}.component.${component.id}") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(run.sourceAreas.firstOrNull { it.id == component.id }?.label ?: component.id.replace('_', ' ').replace('.', ' '), style = MaterialTheme.typography.titleMedium)
                Text("${component.summary.exercised}/${component.summary.planned} checks exercised · ${component.summary.verified} verified passes · ${component.summary.failed} failed · ${component.summary.blocked} blocked · ${component.summary.unavailable} unavailable · ${component.summary.untested} untested or pending", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(component.entries, key = { "audit.${batch.id}.entry.${it.entry.id}" }) { item ->
            AppAuditEntryCard(item, onPrepareModelBenchmark = onPrepareModelBenchmark, onOpenConnection = onOpenConnection, onOpenGatewaySettings = onOpenGatewaySettings, onCheckGateway = onCheckGateway)
        }
    }
}

@Composable
internal fun AppAuditEntryCard(
    item: AppAuditEntryPresentation,
    onPrepareModelBenchmark: ((AppAuditPlanEntry) -> Unit)? = null,
    onOpenConnection: ((String) -> Unit)? = null,
    onOpenGatewaySettings: ((AppAuditPlanEntry) -> Unit)? = null,
    onCheckGateway: ((AppAuditPlanEntry) -> Unit)? = null
) {
    var expanded by rememberSaveable(item.entry.id) { mutableStateOf(false) }
    val result = item.result
    val status = if (item.active) "Running" else appAuditOutcomeLabel(result?.outcome)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.entry.label, style = MaterialTheme.typography.titleSmall)
            item.targetAlias?.let { Text("Target: $it", style = MaterialTheme.typography.labelMedium) }
            if (item.currentSavedName != null) {
                Text("Current saved name: ${item.currentSavedName}", style = MaterialTheme.typography.bodySmall)
            } else if (item.entry.targetId?.let { it.startsWith("connection:") || it.startsWith("gateway:") || it.startsWith("model:") } == true) {
                Text("Current saved name unavailable; the historical target alias is retained.", style = MaterialTheme.typography.bodySmall)
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = if (result?.outcome == AppAuditOutcome.FAIL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(appAuditReason(item), style = MaterialTheme.typography.bodySmall)
            if (result?.observationSource == AppAuditObservationSource.CACHE) {
                Text("This is a retained observation. Review the saved connection, then explicitly request a bounded fresh discovery check. Authentication, permission and recovery conditions still apply.", style = MaterialTheme.typography.bodySmall)
                result.observation.observedAtEpochMs?.let { time ->
                    Text("Original observation: ${benchmarkResultDate(time)}", style = MaterialTheme.typography.bodySmall)
                } ?: Text("Original observation time: not recorded. This audit's check time does not establish cache freshness.", style = MaterialTheme.typography.bodySmall)
                appAuditCacheAgeText(result)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            appAuditConnectionUid(item)?.takeIf { onOpenConnection != null }?.let { uid ->
                OutlinedButton(onClick = { onOpenConnection?.invoke(uid) }) { Text(appAuditConnectionActionLabel(item)) }
            }
            if (item.entry.sourceId == dev.chungjungsoo.gptmobile.data.audit.AppAuditPluginProbeSource.SOURCE_ID &&
                item.entry.probeId == dev.chungjungsoo.gptmobile.data.audit.AppAuditPluginProbeSource.GATEWAY_CAPABILITIES
            ) {
                onOpenGatewaySettings?.let { open -> OutlinedButton(onClick = { open(item.entry) }, enabled = !item.active) { Text("Open Gateway settings") } }
                onCheckGateway?.let { check -> OutlinedButton(onClick = { check(item.entry) }, enabled = !item.active) { Text("Check readiness") } }
            }
            if (onPrepareModelBenchmark != null && item.currentSavedName != null && appAuditModelProfileUid(item.entry) != null) {
                OutlinedButton(onClick = { onPrepareModelBenchmark(item.entry) }) { Text("Prepare model benchmark") }
                Text("Opens the benchmark queue with this saved profile selected. Review the suite and press Run to generate model answers. Earlier audit results remain unchanged.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Planned evidence: ${item.entry.evidenceKind.auditLabel()}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Observed evidence: ${when (result?.observationSource) {
                    AppAuditObservationSource.CONFIGURATION -> "Configuration only"
                    AppAuditObservationSource.FIXTURE -> "${result.evidenceKind.auditLabel()} · fixture"
                    AppAuditObservationSource.LIVE -> "${result.evidenceKind.auditLabel()} · live observation"
                    AppAuditObservationSource.CACHE -> "Cached information; live behavior not verified"
                    AppAuditObservationSource.SHARED -> "Shared discovery · no fresh audit dispatch"
                    AppAuditObservationSource.NONE, null -> "Not observed"
                }}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(if (item.includedInScore) "Included in measured score · weight ${item.entry.weight}" else "Excluded from measured score", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide evidence details" else "Evidence details") }
            if (expanded) {
                Text("Check: ${item.entry.probeId}", style = MaterialTheme.typography.labelSmall)
                Text("Diagnostic code: ${(result?.reasonCode ?: item.entry.reasonCode)?.let(::safeAuditReason) ?: "Not recorded"}", style = MaterialTheme.typography.bodySmall)
                Text("Dispatch: ${result?.dispatchEvidence?.name?.lowercase()?.replace('_', ' ') ?: "not attempted"}", style = MaterialTheme.typography.bodySmall)
                Text(appAuditRetryabilityText(result?.observation?.retryable), style = MaterialTheme.typography.bodySmall)
                if (result?.outcome in setOf(AppAuditOutcome.FAIL, AppAuditOutcome.BLOCKED)) {
                    Text("Failure phase: ${safeAuditFailurePhase(result?.observation?.failurePhase)?.replace('_', ' ') ?: "Not recorded"}", style = MaterialTheme.typography.bodySmall)
                }
                safeAuditFailureCategory(result?.observation?.failureCategory)?.let { category ->
                    Text("Failure category: ${category.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                }
                safeAuditValidationRule(result?.observation?.validationRule)?.let { rule ->
                    Text("Validation rule: $rule", style = MaterialTheme.typography.bodySmall)
                }
                safeAuditDiagnosticField(result?.observation?.validationField)?.let { field ->
                    Text("Validation field: $field", style = MaterialTheme.typography.bodySmall)
                }
                if (result?.observationSource != AppAuditObservationSource.CACHE) {
                    result?.observation?.observedAtEpochMs?.let { time ->
                        Text("Observed: ${benchmarkResultDate(time)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                result?.observation?.configurationRevision?.let { revision ->
                    Text("Observed configuration revision: ${revision.take(12)}", style = MaterialTheme.typography.bodySmall)
                } ?: if (result?.observationSource == AppAuditObservationSource.CACHE) {
                    Text("Observed configuration revision: not recorded. The saved error cannot establish the health of the current configuration.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Unit
                }
                safeAuditRemoteIdentity(result?.observation?.remoteIdentity)?.let { remote ->
                    Text("Reported gateway identity · capability metadata", style = MaterialTheme.typography.labelMedium)
                    remote.version?.let { Text("Reported service version: $it", style = MaterialTheme.typography.bodySmall) }
                    remote.instanceId?.let { Text("Reported service instance: $it", style = MaterialTheme.typography.bodySmall) }
                    remote.sourceCommit?.let { Text("Reported service source: ${it.take(12)}", style = MaterialTheme.typography.bodySmall) }
                    remote.buildStatus?.let { Text("Reported build: $it", style = MaterialTheme.typography.bodySmall) }
                    Text("These fields are declarations from the remote response. Remote source and execution have not been independently verified.", style = MaterialTheme.typography.bodySmall)
                }
                result?.observation?.gatewayReadiness?.let { gateway ->
                    fun readiness(value: Boolean?, ready: String, blocked: String) = when (value) {
                        true -> ready
                        false -> blocked
                        null -> "Not observed"
                    }
                    Text(if (gateway.savedBinding) "Saved job binding" else "Configured engineering connection", style = MaterialTheme.typography.labelMedium)
                    Text("Engineering service · ${readiness(gateway.engineeringReady, "Ready", "Blocked")}", style = MaterialTheme.typography.bodySmall)
                    Text("Host GitHub connection · ${readiness(gateway.hostGitHubConfigured, "Configured", "Missing")}", style = MaterialTheme.typography.bodySmall)
                    Text("Bound model · ${readiness(gateway.selectedModelReady, "Ready", "Blocked")}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Duration: ${benchmarkMetricText(result?.durationMs?.takeIf { it >= 0 }?.toDouble(), "ms", 0)}", style = MaterialTheme.typography.bodySmall)
                result?.observation?.httpStatus?.let { Text("HTTP status: $it", style = MaterialTheme.typography.bodySmall) }
                if (result?.observation?.configChanged == true) Text("The configuration changed during this check; inspect its outcome before retrying.", style = MaterialTheme.typography.bodySmall)
                result?.observation?.metrics.orEmpty().forEach { metric ->
                    Text("${metric.name.name.lowercase().replace('_', ' ')}: ${benchmarkMetricText(metric.value, metric.unit.name.lowercase(), 1)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
