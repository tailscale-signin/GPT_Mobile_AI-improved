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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay

/** One turn-level decision. Closing has the same durable acknowledgement as Continue. */
@Composable
internal fun CombinedFailuresDialog(
    failures: List<CombinedProfileFailure>,
    identities: Map<String, ProfileColorIdentity>,
    onRetry: (Int, String) -> Unit,
    onRemove: (String) -> Unit,
    onContinue: () -> Unit,
    onExclude: (Int, String) -> Unit = { _, _ -> }
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(failures.map { it.retryAfterMillis }) {
        failures.mapNotNull { it.retryAfterMillis }.filter { it > now }.distinct().sorted().forEach { deadline ->
            delay((deadline - System.currentTimeMillis()).coerceAtLeast(0))
            now = System.currentTimeMillis()
        }
    }
    val density = LocalDensity.current
    val safeInsets = WindowInsets.safeDrawing
    val safeHeight = LocalConfiguration.current.screenHeightDp.dp - with(density) { (safeInsets.getTop(density) + safeInsets.getBottom(density)).toDp() }
    val maxHeight = safeHeight * 0.8f
    FadingDialog(onDismissRequest = onContinue, properties = DialogProperties(usePlatformDefaultWidth = false), fadeMillis = 200) {
        AppGlassSurface(
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maxHeight).border(1.dp, appOverlayTokens().border, RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (failures.size == 1) "1 profile needs attention" else "${failures.size} profiles need attention", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onContinue, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Close, "Continue with available replies")
                    }
                }
                Text(
                    if (failures.any { it.category == "SAVED_POLICY_UNAVAILABLE" }) "Restore the complete backup or start a new request. Other replies are saved." else "Other replies are saved.",
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
                            }
                            IconButton(
                                onClick = { onRetry(failure.userMessageId, failure.profileUid) },
                                enabled = failure.retryAllowed && !failure.retrying && (failure.retryAfterMillis ?: 0) <= now,
                                modifier = Modifier.size(48.dp)
                            ) {
                                if (failure.retrying) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Rounded.Refresh, "Retry ${failure.profileName}")
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
                        ) { Text("Exclude ${failure.profileName} from this turn") }
                    }
                }
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            }
        }
    }
}
