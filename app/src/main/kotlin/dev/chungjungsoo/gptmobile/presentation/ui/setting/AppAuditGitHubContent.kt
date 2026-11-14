package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.audit.AppAuditGitHubSection
import dev.chungjungsoo.gptmobile.data.audit.AppAuditRun
import dev.chungjungsoo.gptmobile.data.audit.appAuditGitHubSummary

@Composable
internal fun AppAuditGitHubContent(run: AppAuditRun, showCategories: Boolean = false) {
    val github = remember(run) { appAuditGitHubSummary(run) } ?: return
    Card(Modifier.fillMaxWidth().testTag("app_audit_github")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GitHub integration", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("Live GitHub read score: ${benchmarkMetricText(github.overall.liveScore.score, "/ 100", 1)}", style = MaterialTheme.typography.titleSmall)
            Text("Local GitHub contract score: ${benchmarkMetricText(github.overall.localScore.score, "/ 100", 1)}", style = MaterialTheme.typography.bodyMedium)
            Text(githubAuditCounts(github.overall), style = MaterialTheme.typography.bodySmall)
            Text("Each score compares passing weight with measured pass-or-fail weight in that evidence class. Local checks do not establish live GitHub behavior. Blocked, unavailable, skipped and untested checks earn no points.", style = MaterialTheme.typography.bodySmall)
            Text("Live checks read at most two saved connections and each connection's selected workspace repository/ref: six checks per connection, up to three requests per check, at most 36 GitHub requests. The accepted network budget and deadlines can stop earlier. Lists cover only the first ten entries.", style = MaterialTheme.typography.bodySmall)
            Text("A successful read verifies that named response. Complete repository coverage, CI success, writes, merges, downloads and device execution need their own evidence.", style = MaterialTheme.typography.bodySmall)
            if (showCategories) {
                github.categories.forEach { (category, section) ->
                    Column(Modifier.testTag("app_audit_${category.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(category.title, style = MaterialTheme.typography.titleSmall)
                        Text("Live reads: ${benchmarkMetricText(section.liveScore.score, "/ 100", 1)} · Local contracts: ${benchmarkMetricText(section.localScore.score, "/ 100", 1)}", style = MaterialTheme.typography.bodySmall)
                        Text(githubAuditCounts(section), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

internal fun githubAuditCounts(section: AppAuditGitHubSection): String = with(section.summary) {
    "${section.executed}/$planned executed · $exercised measured · $verified verified passes · $failed failed · " +
        "$blocked blocked · $unavailable unavailable · $untested untested or pending · $skipped skipped · $canceled canceled"
}
