package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.chungjungsoo.gptmobile.data.model.ProfileColorIdentity
import dev.chungjungsoo.gptmobile.presentation.common.AppGlassSurface
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import dev.chungjungsoo.gptmobile.presentation.common.appOverlayTokens
import dev.chungjungsoo.gptmobile.presentation.common.freeProviderClock

/** Dismissal acknowledges alerts; only Continue or an exclusion changes turn membership. */
@Composable
internal fun CombinedFailuresDialog(
    failures: List<CombinedProfileFailure>,
    identities: Map<String, ProfileColorIdentity>,
    onRetry: (Int, String) -> Unit,
    onRemove: (String) -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    canContinue: Boolean,
    onExclude: (Int, String) -> Unit = { _, _ -> },
    onCheckQueuedJob: (String) -> Unit = {}
) {
    val now by freeProviderClock()
    val density = LocalDensity.current
    val safeInsets = WindowInsets.safeDrawing
    val safeHeight = LocalConfiguration.current.screenHeightDp.dp - with(density) { (safeInsets.getTop(density) + safeInsets.getBottom(density)).toDp() }
    val maxHeight = safeHeight * 0.8f
    val waiting = failures.any { it.category in setOf("RATE_LIMIT", "QUEUED_JOB") }
    FadingDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false), fadeMillis = 200) {
        AppGlassSurface(
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maxHeight).border(1.dp, appOverlayTokens().border, RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (failures.size == 1) "1 profile needs attention" else "${failures.size} profiles need attention", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Close, "Close profile alerts")
                    }
                }
                Text(
                    when {
                        failures.any { it.category == "SAVED_POLICY_UNAVAILABLE" } -> "Restore the complete backup or start a new request. Other replies are saved."
                        waiting && !canContinue -> "No replies are ready to combine yet. Keep waiting leaves these profiles included and their saved replies available."
                        waiting -> "Continue uses available replies. Waiting profiles are excluded from this turn; their saved replies remain."
                        else -> "Other replies are saved."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    failures.forEach { failure ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            identities[failure.profileUid]?.let { identity ->
                                Text(
                                    identity.marker.toString(),
                                    Modifier.padding(end = 12.dp),
                                    color = ProfileAttributionPalette.foreground(identity, MaterialTheme.colorScheme.surfaceContainerHigh),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                                Text(failure.profileName, style = MaterialTheme.typography.titleSmall)
                                Text(failure.shortStatus(now), style = MaterialTheme.typography.bodySmall)
                                if (failure.category in setOf("QUEUED_JOB", "CACHED_UNUSABLE_RESPONSE", "STREAM_DEGRADED")) Text(failure.reason, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(
                                onClick = { if (failure.category == "QUEUED_JOB") failure.attemptId?.let(onCheckQueuedJob) else onRetry(failure.userMessageId, failure.profileUid) },
                                enabled = (failure.category == "QUEUED_JOB" && failure.attemptId != null || failure.retryAllowed) && !failure.retrying && (failure.retryAfterMillis ?: 0) <= now,
                                modifier = Modifier.size(48.dp)
                            ) {
                                if (failure.retrying) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Rounded.Refresh, if (failure.category == "QUEUED_JOB") "Check queued job for ${failure.profileName}" else "Retry ${failure.profileName}")
                                }
                            }
                            IconButton(
                                onClick = { onRemove(failure.profileUid) },
                                enabled = !failure.retrying && !failure.applicationFailure,
                                modifier = Modifier.size(48.dp)
                            ) { Icon(Icons.Rounded.RemoveCircleOutline, "Remove ${failure.profileName} from this chat") }
                        }
                        TextButton(
                            onClick = { onExclude(failure.userMessageId, failure.profileUid) },
                            enabled = !failure.retrying && !failure.applicationFailure
                        ) { Text(if (failure.category in setOf("QUEUED_JOB", "RATE_LIMIT")) "Continue without ${failure.profileName}" else "Exclude ${failure.profileName} from this turn") }
                    }
                }
                Button(onClick = onContinue, enabled = canContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(if (waiting) "Keep waiting" else "Close") }
            }
        }
    }
}
