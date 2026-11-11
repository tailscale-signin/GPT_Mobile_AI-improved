package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.github.GitHubCreativityMode
import dev.chungjungsoo.gptmobile.data.github.GitHubSamplingSettings
import kotlin.math.roundToInt

@Composable
internal fun GitHubCreativitySettings(value: GitHubSamplingSettings, enabled: Boolean, onChange: (GitHubSamplingSettings) -> Unit) {
    val adaptive = value.mode == GitHubCreativityMode.ADAPTIVE
    OutlinedCard(Modifier.fillMaxWidth().testTag("github_creativity_settings")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Adaptive creativity", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("Precise execution, exploratory design", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GitHubCreativityMode.entries.forEach { mode ->
                    FilterChip(
                        selected = value.mode == mode,
                        onClick = { onChange(value.copy(mode = mode)) },
                        label = { Text(if (mode == GitHubCreativityMode.ADAPTIVE) "Adaptive · Recommended" else "Manual") },
                        enabled = enabled,
                        modifier = Modifier.testTag("github_creativity_${mode.name.lowercase()}")
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text(if (adaptive) "Design creativity" else "Creativity", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("${(value.creativity * 100).roundToInt()}%", color = MaterialTheme.colorScheme.primary)
            }
            Slider(value = value.creativity, onValueChange = { onChange(value.copy(creativity = it)) }, enabled = enabled, modifier = Modifier.testTag("github_design_creativity"))
            Row(Modifier.fillMaxWidth()) {
                Text("Precise", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                Text("Exploratory", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                if (adaptive) {
                    "The slider guides design and brainstorming. Coding, verification and GitHub operations use precise settings. Options are explored before an evidence-based selection."
                } else {
                    "Use this creativity level across GitHub requests. Approval, tool validation and evidence checks still apply."
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text("Your selected model and saved AI profile stay unchanged. Models without sampling controls use stage instructions. Reasoning is configured separately.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
