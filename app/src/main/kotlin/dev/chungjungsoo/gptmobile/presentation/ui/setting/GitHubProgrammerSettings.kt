package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun GitHubProfilePicker(profiles: List<PlatformV2>, selectedUid: String?, onSelect: (String?) -> Unit, global: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(if (global) "Default GitHub AI profile" else "Engineering AI profile", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("github_main_profile")) {
            Text(profiles.firstOrNull { it.uid == selectedUid }?.name?.take(120) ?: if (selectedUid == null) if (global) "Use favorite AI profile" else "Use global default" else "Selected profile unavailable", maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(if (global) "Use favorite AI profile" else "Use global default") }, onClick = {
                expanded = false
                onSelect(null)
            })
            profiles.filter { it.enabled }.forEach { profile ->
                DropdownMenuItem(text = { Text(profile.name.take(120), maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = {
                    expanded = false
                    onSelect(profile.uid)
                })
            }
        }
    }
}

@Composable
internal fun GitHubProgrammerSettings(workspace: GitHubWorkspaceState, state: GitHubProgrammerState, onProfile: (String?) -> Unit, onIndex: (Boolean) -> Unit, onPrepare: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GitHubProfilePicker(state.profiles, state.profileOverrideUid, onProfile)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Branch creation and switching always require confirmation. Commits, pushes and other consequential actions retain their exact approvals.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Prepare code context", style = MaterialTheme.typography.titleSmall)
                Text("Optional incremental indexing. Files remain available on demand.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(state.prepareIndex, onIndex, modifier = Modifier.testTag("github_index_option"))
        }
        workspace.indexStatus?.let { status ->
            val indexed = status["indexed_blobs"]?.jsonPrimitive?.intOrNull ?: 0
            val total = status["total_paths"]?.jsonPrimitive?.intOrNull
            Text(if (total == null) "$indexed files cached. Coverage is checked against the selected revision." else "$indexed of $total files cached. Partial indexing remains visible.", style = MaterialTheme.typography.bodySmall)
        }
        if (state.prepareIndex) TextButton(onClick = onPrepare, enabled = !workspace.busy) { Text("Prepare next batch") }
    }
}
