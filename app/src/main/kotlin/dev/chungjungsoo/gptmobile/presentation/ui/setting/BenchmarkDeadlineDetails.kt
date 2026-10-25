package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSample

/** Called only inside the selected case's expanded configuration evidence. */
@Composable
internal fun BenchmarkDeadlineDetails(run: BenchmarkRun, sample: BenchmarkSample) {
    Text("Recorded deadlines", style = MaterialTheme.typography.titleSmall)
    val request = sample.deadlineReceipt
    Text("Run deadline policy: ${run.deadlinePolicy ?: "Not recorded"}", style = MaterialTheme.typography.labelSmall)
    if (request == null) {
        Text("Client total request deadline: Not recorded", style = MaterialTheme.typography.bodySmall)
        Text("Client preflight / cleanup limits: Not recorded", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Client total request deadline: ${deadlineMs(request.totalRequestMs)}", style = MaterialTheme.typography.bodySmall)
        Text("Client preflight limit: ${deadlineMs(request.preflightLimitMs)} · cleanup watchdog: ${deadlineMs(request.cleanupWatchdogMs)}", style = MaterialTheme.typography.bodySmall)
        Text("Admission wait: ${if (request.admissionWaitExcluded) "Outside the client request deadline" else "Included in the client request deadline"}", style = MaterialTheme.typography.bodySmall)
        Text("Request policy: ${request.policy} · suite ${request.suiteVersion} · workload ${request.workload}", style = MaterialTheme.typography.labelSmall)
        Text(request.phasePolicyScope, style = MaterialTheme.typography.labelSmall)
    }
    if (!run.local) return
    val loads = sample.runtimeExecutions.mapNotNull { it.engineLoad }.distinctBy { it.receiptId }
    if (loads.isEmpty()) Text("Native load deadline: Not recorded", style = MaterialTheme.typography.bodySmall)
    loads.forEach { load ->
        val policy = load.loadDeadlinePolicy
        if (policy == null) {
            Text("Native load deadline: Not recorded · ${load.receiptId}", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Native load absolute deadline: ${deadlineMs(policy.absoluteMs)}", style = MaterialTheme.typography.bodySmall)
            Text("IPC load supervisor deadline: ${deadlineMs(policy.supervisorAbsoluteMs)}", style = MaterialTheme.typography.bodySmall)
            policy.stageBudgetsMs.toSortedMap().forEach { (stage, duration) ->
                Text("Load phase ${stage.lowercase().replace('_', ' ')}: ${deadlineMs(duration)}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Load policy: ${policy.policy} · clock ${policy.clock}\nLoad receipt: ${load.receiptId} · engine ${load.engineInstanceId}", style = MaterialTheme.typography.labelSmall)
            Text("Load limits belong to this engine-load receipt. Worker exit confirmation remains a separate cleanup fact.", style = MaterialTheme.typography.labelSmall)
        }
    }
    if (sample.runtimeExecutions.isEmpty()) {
        Text("Process supervision deadlines: Not recorded", style = MaterialTheme.typography.bodySmall)
        Text("Native generation deadlines: Not recorded", style = MaterialTheme.typography.bodySmall)
    }
    sample.runtimeExecutions.forEachIndexed { index, execution ->
        val supervisor = execution.processDeadlinePolicy
        if (supervisor == null) {
            Text("Process supervision ${index + 1} deadlines: Not recorded", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Process supervision ${index + 1} absolute deadline: ${deadlineMs(supervisor.absoluteMs)}", style = MaterialTheme.typography.bodySmall)
            Text("Process prefill deadline: ${deadlineMs(supervisor.prefillMs)} · decode idle deadline: ${deadlineMs(supervisor.decodeStallMs)}", style = MaterialTheme.typography.bodySmall)
            Text("Terminal-delivery deadline: ${deadlineMs(supervisor.terminalDeliveryMs)} · tool-callback deadline: ${deadlineMs(supervisor.toolCallbackMs)}", style = MaterialTheme.typography.bodySmall)
            Text("Prefill progress resets the idle window: ${if (supervisor.prefillProgressResetsIdle) "Yes" else "No"} · observed tool waits excluded from idle clocks: ${if (supervisor.excludeToolWaitFromIdle) "Yes" else "No"}", style = MaterialTheme.typography.bodySmall)
            Text("Native-monitor grace: ${deadlineMs(supervisor.nativeMonitorGraceMs)} · backend ${supervisor.backend ?: "Unknown"}", style = MaterialTheme.typography.bodySmall)
            Text("Process scope: ${supervisor.scope} · clock ${supervisor.clock} · schema ${supervisor.schemaVersion}", style = MaterialTheme.typography.labelSmall)
        }
        val policy = execution.configuration?.deadlinePolicy
        if (policy == null) {
            Text("Native generation ${index + 1} deadlines: Not recorded", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Native generation ${index + 1} absolute deadline: ${deadlineMs(policy.absoluteMs)}", style = MaterialTheme.typography.bodySmall)
            Text("Prefill stall deadline: ${deadlineMs(policy.prefillStallMs)} · decode stall deadline: ${deadlineMs(policy.decodeStallMs)}", style = MaterialTheme.typography.bodySmall)
            Text("Minimum prefill throughput: ${benchmarkMetricText(policy.minimumPrefillTokensPerSecond, "tokens/s")} · throughput window: ${deadlineMs(policy.throughputWindowMs)}", style = MaterialTheme.typography.bodySmall)
            Text("Generation deadline clock: ${policy.clock} · schema ${policy.schemaVersion}\nPrefill budget basis: ${policy.prefillBudgetBasis}\nGeneration ${execution.nativeGenerationId ?: execution.configuration?.generationId ?: "Unknown"} · operation ${execution.nativeOperationId ?: "Unknown"}", style = MaterialTheme.typography.labelSmall)
        }
        Text("Recorded deadline termination: ${execution.deadlineKind ?: "Not recorded"}", style = MaterialTheme.typography.labelSmall)
    }
}

private fun deadlineMs(value: Long?): String = value?.let { benchmarkMetricText(it.toDouble(), "ms", 0) } ?: "Not recorded"
