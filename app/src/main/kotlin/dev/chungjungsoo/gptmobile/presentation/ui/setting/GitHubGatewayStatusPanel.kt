package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardActivity
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardDay
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardPhase
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardState
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle

@Composable
internal fun GitHubGatewayStatusPanel(profile: PlatformV2, now: Long, model: GitHubGatewayStatusViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val diagnostics by model.diagnostics.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(profile.uid, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observe(profile.uid) }
    }
    if (state.target?.selectedProfileUid == profile.uid) {
        GitHubGatewayStatusContent(state, maxOf(now, System.currentTimeMillis()), model::refresh)
        if (state.phase != GatewayDashboardPhase.HIDDEN) GatewayDiagnosticPanel(diagnostics, model::diagnosticFilter, model::diagnosticTrace, model::loadDiagnosticPage, model::refresh, model::exportDiagnostics)
    }
}

/** Pure content also renders in compact, large-text, and theme regression tests. */
@Composable
internal fun GitHubGatewayStatusContent(state: GatewayDashboardState, now: Long, onRefresh: () -> Unit) {
    if (state.phase == GatewayDashboardPhase.HIDDEN) return
    val locale = LocalConfiguration.current.locales[0]
    val fresh = state.isFresh(now)
    val snapshot = state.observation?.snapshot
    var allDevices by remember(state.target?.identity) { mutableStateOf(false) }
    var allActivity by remember(state.target?.identity) { mutableStateOf(false) }
    var details by remember(state.target?.identity) { mutableStateOf(false) }
    var selectedDevice by remember(state.target?.identity) { mutableStateOf<String?>(null) }
    var selectedActivity by remember(state.target?.identity) { mutableStateOf<String?>(null) }
    val badge = when {
        fresh && snapshot?.draining == true -> "Pausing"
        fresh -> "Live"
        snapshot != null -> "Saved"
        state.phase == GatewayDashboardPhase.CONNECTING -> "Connecting"
        else -> "Unavailable"
    }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .18f)), modifier = Modifier.fillMaxWidth().testTag("github_gateway_status")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {
                    Icon(Icons.Rounded.Hub, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text("Gateway", style = MaterialTheme.typography.titleMedium)
                    Text(state.target?.name.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Refresh gateway", Modifier.size(20.dp)) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.target?.host.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                GitHubStatusBadge(badge, if (fresh) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (snapshot == null) {
                Text(state.message ?: "Connecting…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            GitHubStatusMetricPair(
                { GitHubStatusMetric("Total tokens", measuredCount(snapshot.outputTokens), MaterialTheme.colorScheme.primary, it) },
                { GitHubStatusMetric("Today", measuredCount(snapshot.days.lastOrNull()?.outputTokens), MaterialTheme.colorScheme.primary, it) }
            )
            GitHubStatusMetricPair(
                { GitHubStatusMetric("Speed", snapshot.tokensPerSecond?.let { speed -> String.format(locale, "%.1f tok/s", speed) } ?: "Not measured", MaterialTheme.colorScheme.secondary, it) },
                { GitHubStatusMetric("Open jobs", measuredCount(state.openJobs(now)), MaterialTheme.colorScheme.secondary, it) }
            )
            if (snapshot.days.isNotEmpty()) GatewayTokenHistory(snapshot.days)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Gateway totals", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (snapshot.activeWorkers != null && fresh) Text("${snapshot.activeWorkers}${snapshot.maxWorkers?.let { "/$it" }.orEmpty()} workers", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!fresh) Text("Saved measurements", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Devices", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(state.recentDevices(now)?.let { "$it recent" } ?: "Not measured", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val devices = snapshot.devices
            if (devices.isNullOrEmpty()) Text(if (devices == null) "Device list unavailable" else "No devices seen yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (if (allDevices) devices.orEmpty() else devices.orEmpty().take(3)).forEach { device ->
                Surface(onClick = { selectedDevice = if (selectedDevice == device.address) null else device.address }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.Devices, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(device.agent.ifBlank { "Device" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(device.address.ifBlank { "Address unavailable" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                if (!fresh) {
                                    "Last seen"
                                } else if (device.activeRequests?.let { it > 0 } == true) {
                                    "Active"
                                } else if (device.state == "recent") {
                                    "Recent"
                                } else if (device.state == "idle") {
                                    "Idle"
                                } else {
                                    "Unknown"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (selectedDevice == device.address) Text("${measuredCount(device.requests)} requests · ${gatewayObservationAge(device.lastSeen, snapshot.capturedAt)}\nObserved connection", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (devices.orEmpty().size > 3) TextButton(onClick = { allDevices = !allDevices }) { Text(if (allDevices) "Fewer devices" else "All ${devices.orEmpty().size} devices") }
            Text("Recent activity", style = MaterialTheme.typography.titleSmall)
            val activity = snapshot.activities
            if (activity.isNullOrEmpty()) Text(if (activity == null) "Activity unavailable" else "No recent activity", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (if (allActivity) activity.orEmpty().take(30) else activity.orEmpty().take(3)).forEach { item ->
                GatewayActivityRow(item, fresh, selectedActivity == item.id, snapshot.capturedAt) { selectedActivity = if (selectedActivity == item.id) null else item.id }
            }
            if (activity.orEmpty().size > 3) TextButton(onClick = { allActivity = !allActivity }) { Text(if (allActivity) "Less activity" else "More activity") }
            GitHubStatusDisclosure("Gateway details", details, { details = !details })
            if (details) {
                Text(state.target?.host.orEmpty(), style = MaterialTheme.typography.bodySmall)
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Usage and workers: this gateway. Devices and activity: this connection's authorized scope.", style = MaterialTheme.typography.bodySmall)
                Text("Unmeasured rounds: ${measuredCount(snapshot.unmeasuredRounds)}", style = MaterialTheme.typography.bodySmall)
                Text("Requests: ${measuredCount(snapshot.requests)} · errors: ${measuredCount(snapshot.errors)}", style = MaterialTheme.typography.bodySmall)
                Text("Received: ${measuredCount(snapshot.receivedBytes)} B · sent: ${measuredCount(snapshot.sentBytes)} B", style = MaterialTheme.typography.bodySmall)
                Text(if (snapshot.historyAvailable) "Recent saved jobs included" else "Saved job history unavailable", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GatewayTokenHistory(days: List<GatewayDashboardDay>) {
    val locale = LocalConfiguration.current.locales[0]
    Text("Tokens · last 7 days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val maximum = days.mapNotNull { it.outputTokens }.maxOrNull()?.coerceAtLeast(1) ?: 1
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { day ->
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "${day.day}: ${measuredCount(day.outputTokens)} tokens" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.BottomCenter) {
                    val height = day.outputTokens?.let { (it.toDouble() / maximum * 42).coerceAtLeast(2.0).toFloat().dp } ?: 2.dp
                    Box(Modifier.fillMaxWidth(.6f).height(height).background(if (day.outputTokens == null) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary.copy(alpha = .72f), RoundedCornerShape(5.dp)))
                }
                Text(LocalDate.parse(day.day).dayOfWeek.getDisplayName(TextStyle.NARROW, locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GatewayActivityRow(item: GatewayDashboardActivity, fresh: Boolean, expanded: Boolean, capturedAt: Double?, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    when (item.kind) {
                        "github" -> Icons.Rounded.AccountTree
                        "search" -> Icons.Rounded.Search
                        else -> Icons.Rounded.ChatBubbleOutline
                    },
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Text(item.title.ifBlank { "Gateway task" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(gatewayActivityLabel(item.status, fresh), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodySmall)
                if (item.branch.isNotBlank()) Text(item.branch, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(gatewayObservationAge(item.updated, capturedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun measuredCount(value: Long?): String = value?.let { NumberFormat.getIntegerInstance().format(it) } ?: "Not measured"

internal fun gatewayActivityLabel(status: String, fresh: Boolean): String = when (status.lowercase()) {
    "completed", "success", "merged" -> "Done"
    "failed", "interrupted", "error" -> "Failed"
    "cancelled", "canceled" -> "Stopped"
    "needs_input", "merge_ready", "blocked" -> if (fresh) "Needs review" else "Last seen"
    "running", "queued", "pending", "read", "plan", "implement", "verify", "ci", "review", "repair", "merge", "new" -> if (fresh) "Running" else "Last seen"
    else -> "Unknown"
}

private fun gatewayObservationAge(stamp: Double?, capturedAt: Double?): String {
    if (stamp == null || capturedAt == null) return "Time not measured"
    val elapsed = (capturedAt - stamp).coerceAtLeast(0.0).toLong()
    return when {
        elapsed < 60 -> "${elapsed}s ago"
        elapsed < 3600 -> "${elapsed / 60}m ago"
        else -> "${elapsed / 3600}h ago"
    }
}
