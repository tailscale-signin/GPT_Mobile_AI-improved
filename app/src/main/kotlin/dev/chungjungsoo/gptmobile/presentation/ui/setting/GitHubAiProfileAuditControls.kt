package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.benchmark.GitHubProfileAuditSuite
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

@Composable
internal fun GitHubAiProfileAuditControls(
    profiles: List<PlatformV2>,
    ready: Boolean,
    busy: Boolean,
    onStart: (String, Boolean, Boolean) -> Unit
) {
    var profileUid by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var allActions by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var strictDeterministic by rememberSaveable { mutableStateOf(false) }
    val eligible = remember(profiles) { profiles.filter { it.enabled && it.model.isNotBlank() } }
    val selected = eligible.singleOrNull { it.uid == profileUid }
    val visible = remember(eligible, query) {
        eligible.filter { query.isBlank() || listOf(it.name, it.model, it.compatibleType.name).any { value -> value.contains(query.trim(), ignoreCase = true) } }
    }
    val familyCount = remember { GitHubProfileAuditSuite.familyCount() }
    val actionCount = remember { GitHubProfileAuditSuite.actionCount() }
    Card(Modifier.fillMaxWidth().testTag("github_ai_audit_controls")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.github_ai_audit_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.github_ai_audit_scope), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.github_ai_audit_profile), style = MaterialTheme.typography.labelLarge)
            if (eligible.size > 4 || query.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    enabled = !busy,
                    label = { Text(stringResource(R.string.github_ai_audit_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("github_ai_audit_search")
                )
            }
            if (eligible.isEmpty()) {
                Text(stringResource(R.string.github_ai_audit_no_profiles), style = MaterialTheme.typography.bodySmall)
            } else if (visible.isEmpty()) {
                Text(stringResource(R.string.github_ai_audit_no_matches), style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.selectableGroup()) {
                visible.forEach { profile ->
                    key(profile.uid) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("github_ai_audit_profile_${profile.uid}")
                                .selectable(profile.uid == selected?.uid, enabled = !busy, role = Role.RadioButton) { profileUid = profile.uid }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(selected = profile.uid == selected?.uid, onClick = null, enabled = !busy)
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                                Text("${profile.compatibleType.name} · ${profile.model}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            selected?.let {
                Text(stringResource(R.string.github_ai_audit_selected, it.name, it.model), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
            Text(stringResource(R.string.github_ai_audit_coverage), style = MaterialTheme.typography.labelLarge)
            Column(Modifier.selectableGroup()) {
                listOf(false, true).forEach { full ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag(if (full) "github_ai_audit_full" else "github_ai_audit_quick")
                            .selectable(allActions == full, enabled = !busy, role = Role.RadioButton) { allActions = full }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RadioButton(selected = allActions == full, onClick = null, enabled = !busy)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(if (full) R.string.github_ai_audit_full else R.string.github_ai_audit_quick), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(if (full) R.string.github_ai_audit_full_detail else R.string.github_ai_audit_quick_detail, if (full) actionCount else familyCount), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("github_ai_audit_advanced")) {
                Text(stringResource(if (advanced) R.string.github_ai_audit_hide_advanced else R.string.github_ai_audit_show_advanced))
            }
            if (advanced) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("github_ai_audit_strict")
                        .toggleable(strictDeterministic, enabled = !busy, role = Role.Checkbox) { strictDeterministic = it },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(checked = strictDeterministic, onCheckedChange = null, enabled = !busy)
                    Text(stringResource(R.string.github_ai_audit_strict), Modifier.weight(1f))
                }
            }
            Text(stringResource(if (strictDeterministic) R.string.github_ai_audit_strict_detail else R.string.github_ai_audit_saved_settings), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.github_ai_audit_usage), style = MaterialTheme.typography.bodySmall)
            val blocked = when {
                busy -> R.string.github_ai_audit_busy
                !ready -> R.string.github_ai_audit_loading
                selected == null -> R.string.github_ai_audit_choose_profile
                else -> null
            }
            blocked?.let { Text(stringResource(it), style = MaterialTheme.typography.labelMedium) }
            Button(
                onClick = { selected?.let { onStart(it.uid, allActions, strictDeterministic) } },
                enabled = ready && !busy && selected != null,
                modifier = Modifier.fillMaxWidth().testTag("github_ai_audit_start")
            ) { Text(stringResource(R.string.github_ai_audit_start, if (allActions) actionCount else familyCount)) }
        }
    }
}
