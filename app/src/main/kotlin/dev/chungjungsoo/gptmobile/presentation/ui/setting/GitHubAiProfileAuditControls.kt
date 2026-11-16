package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.benchmark.GitHubProfileAuditSuite
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2

@Composable
internal fun GitHubAiProfileAuditControls(
    profiles: List<PlatformV2>,
    ready: Boolean,
    busy: Boolean,
    onStart: (String, Boolean) -> Unit
) {
    var profileUid by rememberSaveable { mutableStateOf<String?>(null) }
    var allActions by rememberSaveable { mutableStateOf(false) }
    val selected = profiles.singleOrNull { it.uid == profileUid }
    Card(Modifier.fillMaxWidth().testTag("github_ai_audit_controls")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("GitHub tools · Selected AI profile", style = MaterialTheme.typography.titleMedium)
            Text(
                "Checks the chosen model's real tool-call and answer behavior using the app's GitHub tool schemas. " +
                    "Tool execution is an isolated offline fixture; no GitHub account is used, and no repository changes are made.",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Select exactly one saved AI profile:", style = MaterialTheme.typography.bodyMedium)
            if (profiles.isEmpty()) Text(
                "No enabled AI profiles are available. Configure an AI profile before running this check.",
                style = MaterialTheme.typography.bodySmall
            )
            profiles.forEach { profile ->
                Row(
                    Modifier.fillMaxWidth().selectable(profile.uid == profileUid, enabled = !busy, role = Role.RadioButton) {
                        profileUid = profile.uid
                    }.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = profile.uid == profileUid, onClick = null, enabled = !busy)
                    Text(profile.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(
                Modifier.fillMaxWidth().toggleable(allActions, enabled = !busy, role = Role.Checkbox) {
                    allActions = it
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = allActions, onCheckedChange = null, enabled = !busy)
                Text("Full action-by-action coverage", Modifier.weight(1f))
            }
            val count = if (allActions) GitHubProfileAuditSuite.actionCount() else GitHubProfileAuditSuite.familyCount()
            Text(
                "$count isolated scenarios · " + if (allActions) "every advertised GitHub tool action" else "one check per GitHub tool family",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "The chosen profile sends provider requests, which may incur usage charges. Failures, skipped tools and incomplete answers are retained. " +
                    "This does not prove real GitHub authorization, reads, writes, branch cleanup, CI or merges. " +
                    "Run Comprehensive App audit with service reads enabled above for real, bounded GitHub read evidence.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = { selected?.let { onStart(it.uid, allActions) } },
                enabled = ready && !busy && selected != null,
                modifier = Modifier.fillMaxWidth().testTag("github_ai_audit_start")
            ) {
                Text(if (busy) "Benchmark running" else "Run GitHub AI tool audit")
            }
        }
    }
}
