package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.chungjungsoo.gptmobile.data.network.gateway.diagnosticLong
import dev.chungjungsoo.gptmobile.data.network.gateway.diagnosticText
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** A paged diagnostic view; task completion, HTTP observations and incidents remain separate measures. */
@Composable
internal fun GatewayDiagnosticPanel(
    state: GatewayDiagnosticViewState,
    onFilter: (String) -> Unit,
    onTrace: (String?) -> Unit,
    onPage: (Int) -> Unit,
    onRefresh: () -> Unit,
    export: suspend (File) -> File
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember(state.status.sessionId) { mutableStateOf(false) }
    var selected by remember(state.status.sessionId, state.offset) { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().testTag("gateway_diagnostics")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Correlated diagnostics", style = MaterialTheme.typography.titleMedium)
            Text(diagnosticCaptureLabel(state.status.captureState), style = MaterialTheme.typography.bodyMedium)
            Text("${state.status.retained}/5,000 retained events · ${state.status.pending} awaiting acknowledgement", style = MaterialTheme.typography.bodySmall)
            Text("${state.status.evicted} evicted · ${state.status.dropped} lost before delivery · ${state.status.rejected} rejected observations", style = MaterialTheme.typography.bodySmall)
            Text("${state.status.incidents} incident groups · ${state.status.operationCount} correlated operations", style = MaterialTheme.typography.bodySmall)
            Text("Gateway source: ${state.status.sourceAvailability.replace('_', ' ')} · vendor logs unavailable", style = MaterialTheme.typography.labelSmall)
            state.status.storageError?.let { code -> Text(diagnosticRecovery(code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (state.status.captureState == "disabled") Text("Enable Debug mode in Settings to synchronize metadata. Enable log tracking to include app lifecycle, failures and frame summaries. Raw messages and private conversations are excluded.", style = MaterialTheme.typography.bodySmall)
            if (state.status.captureState == "gateway_disabled") Text("Enable diagnostics on the desktop gateway. This connection cannot grant itself administrative logging access.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide evidence" else "Inspect evidence and coverage") }
            if (expanded) {
                Text("API operation success: Not measured", style = MaterialTheme.typography.labelMedium)
                Text("Verified task completion: Not measured", style = MaterialTheme.typography.labelMedium)
                Text("Model invocation completion: Not measured", style = MaterialTheme.typography.labelMedium)
                Text("A transport success or incident count does not certify task completion. Open jobs and model throughput are shown in Gateway above; unknown accelerator and timing measurements remain unmeasured.", style = MaterialTheme.typography.bodySmall)
                Text("Cross-device clock uncertainty is unknown. Durations below use the originating source's monotonic clock; rows follow observation order.", style = MaterialTheme.typography.bodySmall)
                state.status.lastSyncAt?.let { Text("Last synchronization: ${Instant.ofEpochMilli(it)}", style = MaterialTheme.typography.labelSmall) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("failures" to "Failures", "all" to "All events", "performance" to "Performance", "model_tools" to "Models and tools").forEach { (key, label) ->
                        FilterChip(selected = state.filter == key, onClick = { onFilter(key) }, label = { Text(label) })
                    }
                }
                if (state.traceId != null) {
                    Text("Trace ${state.traceId}", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { onTrace(null) }) { Text("Show all traces") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.page.events.isEmpty()) Text(if (state.loading) "Reading bounded evidence…" else "No retained events match this view. Missing evidence is not a success result.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("gateway_diagnostic_events"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.page.events, key = { it.diagnosticText("event_id").orEmpty() }) { event ->
                        val eventId = event.diagnosticText("event_id")
                        Surface(onClick = { selected = if (selected == eventId) null else eventId }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${event.diagnosticText("owner") ?: "unknown"} · ${event.diagnosticText("stage") ?: "unknown"} · ${event.diagnosticText("severity") ?: "info"}", style = MaterialTheme.typography.labelLarge)
                                Text(event.diagnosticText("error_code") ?: "Cause not reported", style = MaterialTheme.typography.bodySmall)
                                if (selected == eventId) {
                                    GatewayDiagnosticEventDetails(event)
                                    event.diagnosticText("trace_id")?.let { trace -> TextButton(onClick = { onTrace(trace) }) { Text("Follow this trace") } }
                                }
                            }
                        }
                    }
                }
                Text("${state.page.total} matching retained events · this page shows ${state.page.events.size}", style = MaterialTheme.typography.labelSmall)
                Row {
                    TextButton(onClick = { onPage(0) }, enabled = !state.loading) { Text("Newest") }
                    if (state.offset > 0) TextButton(onClick = { onPage((state.offset - 32).coerceAtLeast(0)) }, enabled = !state.loading) { Text("Newer") }
                    state.page.nextOffset?.let { offset -> TextButton(onClick = { onPage(offset) }, enabled = !state.loading) { Text("Older") } }
                }
                Row {
                    TextButton(onClick = onRefresh) { Text("Refresh connection") }
                    TextButton(onClick = {
                        exporting = true
                        scope.launch {
                            try {
                                val file = export(context.cacheDir)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "application/zip"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        },
                                        "Share correlated diagnostics"
                                    )
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                Toast.makeText(context, "Unable to export diagnostic evidence. Check capture and storage status.", Toast.LENGTH_LONG).show()
                            } finally {
                                exporting = false
                            }
                        }
                    }, enabled = !exporting && state.status.sessionId != null && state.status.captureState != "private_session") { Text(if (exporting) "Exporting…" else "Export evidence") }
                }
                Text("The support bundle links the device mirror, current saved run states, and the gateway’s committed session when reachable. Each source has checksums and coverage; missing sources and full job-evidence links remain explicit.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun GatewayDiagnosticEventDetails(event: JsonObject) {
    Text("Event ${event.diagnosticText("event_id")}", style = MaterialTheme.typography.labelSmall)
    Text("Dispatch: ${event.diagnosticText("dispatch_state") ?: "unknown"} · retry: ${event.diagnosticText("retry_class") ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
    Text("Source duration: ${event.diagnosticLong("local_duration_ms")?.let { "$it ms" } ?: "Not measured"}", style = MaterialTheme.typography.bodySmall)
    listOf("binding_id" to "Execution binding", "job_id" to "Job", "operation_id" to "Operation", "request_id" to "Request", "run_id" to "Run", "trace_id" to "Trace", "span_id" to "Span", "parent_span_id" to "Parent span").forEach { (field, label) ->
        event.diagnosticText(field)?.let { Text("$label: $it", style = MaterialTheme.typography.labelSmall) }
    }
    val attributes = event["bounded_attributes"] as? JsonObject
    listOf("reason_code" to "Reason", "recovery_condition" to "Recovery condition", "failed_stage" to "Failed stage", "terminal_status" to "Outcome", "completion_disposition" to "Disposition", "dependency" to "Dependency", "dispatch_scope" to "Dispatch evidence scope", "mcp_phase" to "MCP phase", "transport_cause" to "Connection cause", "cancellation_origin" to "Cancellation origin", "correlation_status" to "Correlation", "poll_owner" to "Poll owner").forEach { (field, label) ->
        attributes?.diagnosticText(field)?.let { Text("$label: $it", style = MaterialTheme.typography.bodySmall) }
    }
    attributes?.diagnosticText("recovery_action")?.let { Text(dev.chungjungsoo.gptmobile.presentation.ui.home.gatewayRecoveryLabel(it), style = MaterialTheme.typography.bodyMedium) }
    listOf("frame_count" to "Measured frames", "missed_deadline_count" to "Missed frame deadlines", "dropped_samples" to "Lost measurement samples", "maximum_frame_ns" to "Maximum frame ns", "frame_budget_ns" to "Frame budget ns", "observation_age_ms" to "Observation age at capture ms", "upstream_status" to "Upstream HTTP status", "required_criteria" to "Required criteria", "verified_criteria" to "Verified criteria", "remaining_criteria" to "Remaining criteria", "next_check_delay_ms" to "Next check delay ms", "queue_ms" to "Queue ms", "load_ms" to "Load ms", "prefill_ms" to "Prefill ms", "decode_ms" to "Decode ms", "input_tokens" to "Input tokens", "output_tokens" to "Output tokens", "stalls_over_500_ms" to "Frames over 500 ms", "gc_count" to "GC count (approximate)", "gc_duration_ms" to "GC ms (approximate)", "heap_bytes" to "Java heap bytes", "native_heap_bytes" to "Native heap bytes").forEach { (field, label) ->
        attributes?.diagnosticLong(field)?.let { Text("$label: $it", style = MaterialTheme.typography.bodySmall) }
    }
    Text(diagnosticRecovery(event.diagnosticText("error_code") ?: "UNKNOWN_CAUSE"), style = MaterialTheme.typography.bodySmall)
}

internal fun diagnosticCaptureLabel(state: String): String = when (state) {
    "recording" -> "Recording opted-in metadata"
    "disabled" -> "App debug is disabled"
    "private_session" -> "Capture and synchronization paused for a private conversation"
    "gateway_disabled" -> "Gateway capture is disabled"
    "protocol_unavailable" -> "Bidirectional diagnostics protocol has not been observed"
    "offline_or_blocked" -> "Retaining evidence while synchronization is unavailable"
    "retry_wait" -> "Retaining evidence while waiting to retry synchronization"
    "sync_blocked" -> "Synchronization paused; resolve the connection problem, then refresh"
    "retry_exhausted" -> "Automatic synchronization stopped after repeated failures; refresh to try again"
    "storage_unavailable" -> "Diagnostic storage is unavailable"
    else -> "Diagnostic coverage is not established"
}

internal fun diagnosticRecovery(code: String): String = when (code) {
    "NONE" -> "No failure was reported by this event."
    "DIAGNOSTIC_AUTHENTICATION_REQUIRED", "DIAGNOSTIC_ACCESS_DENIED", "AUTHENTICATION_FAILED", "AUTHORIZATION_FAILED" -> "Check this profile's gateway identity and access in GitHub settings, then refresh the connection."
    "DEBUG_STORAGE_UNAVAILABLE", "STORAGE_UNAVAILABLE" -> "Free storage on the affected device. Inference and independent job receipts continue; exported gaps identify lost diagnostic evidence."
    "DIAGNOSTIC_BACKPRESSURE" -> "The diagnostic writer is busy. Recovery uses bounded retries with the same event identities. Refresh the connection after correcting a persistent failure."
    "DIAGNOSTIC_SOURCE_UNAVAILABLE" -> "This connection does not provide the supported diagnostics service. Check the gateway address and version, then refresh."
    "DIAGNOSTIC_CAPTURE_BLOCKED", "DIAGNOSTIC_CAPTURE_EXPIRED" -> "The gateway cannot accept this capture. Export retained evidence and check capture limits before starting a new capture."
    "DIAGNOSTIC_REQUEST_REJECTED", "DIAGNOSTIC_REDIRECT_REJECTED", "DIAGNOSTIC_PROTOCOL_FAILURE", "DIAGNOSTIC_TLS_FAILURE", "INVALID_DIAGNOSTIC_ACK", "INVALID_DIAGNOSTIC_PAGE", "INVALID_DIAGNOSTIC_CURSOR", "INVALID_DIAGNOSTIC_EVENT", "INVALID_DIAGNOSTIC_GAP", "DIAGNOSTIC_BATCH_LIMIT", "DIAGNOSTIC_RESPONSE_LIMIT" -> "Synchronization stopped after an invalid or unsupported response. Check the selected gateway's configuration and version, preserve the evidence, then refresh."
    "DIAGNOSTIC_TRANSPORT_FAILURE", "TRANSPORT_FAILURE", "GITHUB_TRANSIENT_NETWORK" -> "Check the selected gateway connection and refresh it. Preserve the export if the failure repeats."
    "DISPATCH_UNCERTAIN" -> "Reconcile the operation receipt before repeating a write. This event does not establish whether the remote effect completed."
    "PRECHECK_REJECTED" -> "The blocked action was not dispatched. Earlier run actions may have executed. Inspect the reason and recovery condition."
    "JAVA_HEAP_PRESSURE" -> "Use reduced-memory diagnostic capture and inspect the retained heap and lifecycle observations."
    "MCP_UNAVAILABLE" -> "Inspect this gateway's MCP connection and discovery status; required tools remain unavailable until observed recovery."
    "RENDERER_JANK" -> "The sampled window exceeded the missed-deadline threshold. Inspect its frame count and timing budget."
    "RENDERER_MEASUREMENT_LOSS" -> "Frame-metric samples were lost. This does not establish dropped display frames or a freeze."
    "RENDERER_STALL" -> "Inspect the correlated frame, GC, network and model intervals. Vendor logs are unavailable until separately captured."
    "CANCELLED" -> "The originating operation reported cancellation; inspect its final execution receipt for completion state."
    else -> "The cause is not established. Follow the causal trace and export the retained evidence; this observation is not a confirmed independent defect."
}
