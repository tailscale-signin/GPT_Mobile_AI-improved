package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.ProfileColorIdentity

/** Fixed under the shared composer. Choosing a response never changes broadcast recipients. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatProfileDock(
    profileUids: List<String>,
    names: Map<String, String>,
    selectedUid: String?,
    pausedUids: Set<String>,
    generatingUids: Set<String>,
    combined: Boolean,
    combinedGenerating: Boolean,
    onSelect: (String?) -> Unit,
    onPause: (String) -> Unit,
    failedUids: Set<String> = emptySet(),
    combinedFailed: Boolean = false,
    profileColorIdentities: Map<String, ProfileColorIdentity> = emptyMap()
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth().testTag("chat_profile_dock")
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val entries = (if (combined) listOf<String?>(null) else emptyList()) + profileUids
            entries.forEach { uid ->
                val active = selectedUid == uid
                val paused = uid != null && uid in pausedUids
                val running = if (uid == null) combinedGenerating else uid in generatingUids
                val failed = if (uid == null) combinedFailed else uid in failedUids
                val label = if (uid == null) "Combined" else names[uid] ?: "AI"
                val itemColor = if (failed) {
                    colors.errorContainer
                } else if (active) {
                    colors.primaryContainer
                } else {
                    colors.secondaryContainer
                }
                val labelColor = uid?.let(profileColorIdentities::get)?.let { ProfileAttributionPalette.foreground(it, itemColor) }
                    ?: if (failed) {
                        colors.onErrorContainer
                    } else if (active) {
                        colors.onPrimaryContainer
                    } else {
                        colors.onSecondaryContainer
                    }
                val status = if (paused) {
                    "Paused"
                } else if (running) {
                    "Responding"
                } else if (failed) {
                    "Needs attention"
                } else {
                    "Ready"
                }
                Surface(
                    color = itemColor,
                    contentColor = if (failed) {
                        colors.onErrorContainer
                    } else if (active) {
                        colors.onPrimaryContainer
                    } else {
                        colors.onSecondaryContainer
                    },
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(if (active) 2.dp else 1.dp, if (active) colors.primary else colors.secondary.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .testTag("dock_profile_" + (uid ?: "combined"))
                        .semantics {
                            selected = active
                            stateDescription = status
                        }
                        .combinedClickable(role = Role.Tab, onClick = { onSelect(uid) }, onLongClick = uid?.let { { onPause(it) } })
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.primary)
                        Text(label, style = MaterialTheme.typography.labelLarge, color = labelColor)
                        Text(status, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
