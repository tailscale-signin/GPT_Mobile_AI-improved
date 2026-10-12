package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DelegateModelDropdown(
    profiles: List<PlatformV2>,
    selectedProfileUid: String,
    enabled: Boolean,
    onSelected: (PlatformV2?) -> Unit,
    automaticLabel: String = "Automatic",
    roleLabel: String = "Delegate model",
    scores: Map<String, Int> = emptyMap(),
    blockedReason: (PlatformV2) -> String? = { if (it.enabled) null else "Profile disabled" }
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("All") }
    val selected = profiles.firstOrNull { it.uid == selectedProfileUid }
    Column {
        Text(roleLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("delegate_model_dropdown")
            ) {
                Column(Modifier.weight(1f)) {
                    Text(selected?.name ?: if (selectedProfileUid.isBlank()) automaticLabel else "Selected model unavailable")
                    selected?.let {
                        Text(
                            buildString {
                                append(it.model)
                                scores[it.uid]?.let { score -> append(" · Score $score/100") }
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Icon(Icons.Rounded.ArrowDropDown, "Choose $roleLabel")
            }
            if (expanded && enabled) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { expanded = false },
                    title = { Text("Choose $roleLabel") },
                    text = {
                        Column {
                            androidx.compose.material3.OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search profiles or models") }, singleLine = true)
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                                listOf("All", "On device", "Private", "Cloud").forEach { label ->
                                    androidx.compose.material3.FilterChip(destination == label, { destination = label }, label = { Text(label) })
                                }
                            }
                            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                                item {
                                    DropdownMenuItem(text = { Text(automaticLabel) }, onClick = {
                                        expanded = false
                                        onSelected(null)
                                    })
                                }
                                val candidates = profiles.filter { profile ->
                                    (query.isBlank() || "${profile.name} ${profile.model}".contains(query.trim(), true)) &&
                                        when (destination) {
                                            "On device" -> profile.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM
                                            "Private" -> profile.isPrivateDestination() && profile.compatibleType != dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM
                                            "Cloud" -> !profile.isPrivateDestination()
                                            else -> true
                                        }
                                }
                                items(candidates.size, key = { candidates[it].uid }) { index ->
                                    val profile = candidates[index]
                                    val reason = blockedReason(profile)
                                    DropdownMenuItem(
                                        enabled = reason == null,
                                        text = {
                                            Column {
                                                Text(profile.name)
                                                Text(profile.model, style = MaterialTheme.typography.bodySmall)
                                                if (reason != null) Text(reason, style = MaterialTheme.typography.bodySmall)
                                                scores[profile.uid]?.let { Text("Recorded score $it/100", style = MaterialTheme.typography.bodySmall) }
                                            }
                                        },
                                        leadingIcon = { Icon(if (profile.isPrivateDestination()) Icons.Rounded.Memory else Icons.Rounded.Cloud, if (profile.isPrivateDestination()) "Local or private model" else "Cloud model") },
                                        onClick = {
                                            expanded = false
                                            onSelected(profile)
                                        }
                                    )
                                }
                                if (candidates.isEmpty()) item { Text("No matching profiles") }
                            }
                        }
                    },
                    confirmButton = { androidx.compose.material3.TextButton(onClick = { expanded = false }) { Text("Close") } }
                )
            }
        }
        if (profiles.isEmpty()) {
            Text("Add an enabled AI profile in Settings to choose a helper.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (selected != null && !selected.isPrivateDestination()) {
            Text("This online model receives the tasks and tool results you delegate to it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
