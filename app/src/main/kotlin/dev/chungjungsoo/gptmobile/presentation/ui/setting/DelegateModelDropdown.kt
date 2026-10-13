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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
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
    automaticLabel: String = stringResource(R.string.v0950_picker_automatic),
    roleLabel: String = stringResource(R.string.v0950_picker_delegate),
    scores: Map<String, Int> = emptyMap(),
    testTag: String = "delegate_model_dropdown",
    blockedReason: ((PlatformV2) -> String?)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf(0) }
    val selected = profiles.firstOrNull { it.uid == selectedProfileUid }
    val scoreLabel = stringResource(R.string.v0950_picker_score)
    Column {
        Text(roleLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag(testTag)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(selected?.name ?: if (selectedProfileUid.isBlank()) automaticLabel else stringResource(R.string.v0950_picker_unavailable))
                    selected?.let {
                        Text(
                            buildString {
                                append(it.model)
                                scores[it.uid]?.let { score -> append(" · ").append(scoreLabel.format(score)) }
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Icon(Icons.Rounded.ArrowDropDown, stringResource(R.string.v0950_picker_choose, roleLabel))
            }
            if (expanded && enabled) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { expanded = false },
                    title = { Text(stringResource(R.string.v0950_picker_choose, roleLabel)) },
                    text = {
                        Column {
                            androidx.compose.material3.OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.v0950_picker_search)) }, singleLine = true)
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                                listOf(R.string.all, R.string.v0950_picker_on_device, R.string.v0950_picker_private, R.string.v0950_picker_cloud).forEachIndexed { index, label ->
                                    androidx.compose.material3.FilterChip(destination == index, { destination = index }, label = { Text(stringResource(label)) })
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
                                            1 -> profile.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM
                                            2 -> profile.isPrivateDestination() && profile.compatibleType != dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM
                                            3 -> !profile.isPrivateDestination()
                                            else -> true
                                        }
                                }
                                items(candidates.size, key = { candidates[it].uid }) { index ->
                                    val profile = candidates[index]
                                    val reason = if (blockedReason != null) {
                                        blockedReason(profile)
                                    } else if (profile.enabled) {
                                        null
                                    } else {
                                        stringResource(R.string.v0950_picker_disabled)
                                    }
                                    DropdownMenuItem(
                                        enabled = reason == null,
                                        text = {
                                            Column {
                                                Text(profile.name)
                                                Text(profile.model, style = MaterialTheme.typography.bodySmall)
                                                if (reason != null) Text(reason, style = MaterialTheme.typography.bodySmall)
                                                scores[profile.uid]?.let { Text(stringResource(R.string.v0950_picker_recorded_score, it), style = MaterialTheme.typography.bodySmall) }
                                            }
                                        },
                                        leadingIcon = { Icon(if (profile.isPrivateDestination()) Icons.Rounded.Memory else Icons.Rounded.Cloud, stringResource(if (profile.isPrivateDestination()) R.string.v0950_picker_private_model else R.string.v0950_picker_cloud_model)) },
                                        onClick = {
                                            expanded = false
                                            onSelected(profile)
                                        }
                                    )
                                }
                                if (candidates.isEmpty()) item { Text(stringResource(R.string.v0950_picker_no_matches)) }
                            }
                        }
                    },
                    confirmButton = { androidx.compose.material3.TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.close)) } }
                )
            }
        }
        if (profiles.isEmpty()) {
            Text(stringResource(R.string.v0950_picker_add_profile), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (selected != null && !selected.isPrivateDestination()) {
            Text(stringResource(R.string.v0950_picker_cloud_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
