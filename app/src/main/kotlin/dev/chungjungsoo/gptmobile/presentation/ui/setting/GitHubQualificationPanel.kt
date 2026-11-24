package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.benchmark.GitHubIntelligenceScore
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import java.util.Locale

internal data class GitHubQualificationRead(val score: GitHubIntelligenceScore? = null, val message: String? = null)

@Composable
internal fun GitHubQualificationPanel(profile: PlatformV2, viewModel: ProfileBenchmarkViewModel) {
    key(profile) {
        var request by remember { mutableIntStateOf(0) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("GitHub Intelligence Score · ${profile.name}", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { request++ }) { Text(if (request == 0) "Read saved qualification" else "Refresh saved qualification") }
            if (request > 0) {
                key(request) {
                    val source = remember { viewModel.intelligenceScore(profile) }
                    val read by source.collectAsStateWithLifecycle(initialValue = GitHubQualificationRead())
                    val score = read.score
                    if (score == null && read.message == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        read.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text("Total · ${score?.total?.let { "%.1f / 100".format(Locale.getDefault(), it) } ?: "Unavailable"}", style = MaterialTheme.typography.titleSmall)
                        score?.let { Text("Tested revision ${it.revision.take(12)} · ${it.reportId}", style = MaterialTheme.typography.bodySmall) }
                        for (dimension in score?.dimensions ?: GitHubIntelligenceScore.unmeasured) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(dimension.label, style = MaterialTheme.typography.bodyMedium)
                                    if (dimension.planned > 0) Text("${dimension.observed} of ${dimension.planned} observations", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(dimension.points?.let { "%.1f / %d".format(Locale.getDefault(), it, dimension.weight) } ?: "Unavailable", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
