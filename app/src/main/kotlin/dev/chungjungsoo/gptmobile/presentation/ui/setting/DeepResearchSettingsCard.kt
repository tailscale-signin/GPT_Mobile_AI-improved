package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ResearchIntensity

@Composable
internal fun DeepResearchSettingsCard(value: DeepResearchSettings, enabled: Boolean, onChange: (DeepResearchSettings) -> Unit) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Deep Research", style = MaterialTheme.typography.titleMedium)
                Switch(checked = value.enabled, onCheckedChange = { onChange(value.copy(enabled = it)) }, enabled = enabled)
            }
            if (value.enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResearchIntensity.entries.forEach { intensity ->
                        FilterChip(selected = value.intensity == intensity, onClick = { onChange(value.preset(intensity)) }, enabled = enabled, label = { Text(intensity.name.lowercase().replaceFirstChar(Char::uppercase)) })
                    }
                }
                Text("${value.forEffort(value.effort).maxPages} source target · ${value.maxRounds} rounds · ${if (value.allowRemoteReaders) "Phone + connected readers" else "On-device retrieval"}", style = MaterialTheme.typography.bodySmall)
                if (value.schemaVersion < 2) Text("Research effort now sets the source target. Review the source safety cap when migrating older settings.", style = MaterialTheme.typography.bodySmall)
                ResearchToggle("Papers and studies", value.academicSources, enabled) { onChange(value.copy(academicSources = it)) }
                ResearchToggle("Review evidence", value.reviewEvidence, enabled) { onChange(value.copy(reviewEvidence = it)) }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Less" else "Advanced") }
                if (advanced) {
                    Text("Overview & defaults", style = MaterialTheme.typography.titleSmall)
                    ResearchLimit("Research effort", value.effort, 0..100, enabled) { onChange(value.forEffort(it)) }
                    ResearchToggle("Use a smaller source safety cap", value.sourceSafetyCap != null, enabled) { onChange(value.copy(sourceSafetyCap = if (it) 150 else null).forEffort(value.effort)) }
                    value.sourceSafetyCap?.let { cap -> ResearchLimit("Source safety cap", cap, 1..300, enabled) { onChange(value.copy(sourceSafetyCap = it).forEffort(value.effort)) } }
                    Text("Engines & health", style = MaterialTheme.typography.titleSmall)
                    dev.chungjungsoo.gptmobile.presentation.ui.setting.ResearchEngineHealthPanel()
                    Text("Research quality", style = MaterialTheme.typography.titleSmall)
                    ResearchLimit("Search rounds", value.maxRounds, 1..8, enabled) { onChange(value.copy(maxRounds = it)) }
                    ResearchLimit("Link levels", value.linkDepth, 0..2, enabled) { onChange(value.copy(linkDepth = it)) }
                    ResearchLimit("Parallel reads", value.concurrency, 1..6, enabled) { onChange(value.copy(concurrency = it)) }
                    Text("Performance", style = MaterialTheme.typography.titleSmall)
                    ResearchLimit("Per-page timeout (seconds)", value.pageTimeoutSeconds, 5..30, enabled) { onChange(value.copy(pageTimeoutSeconds = it)) }
                    Text("${value.requestCeiling} request ceiling · ${value.downloadLimitMiB} MiB download ceiling · ${value.activeRunSeconds / 60} minutes active limit", style = MaterialTheme.typography.bodySmall)
                    Text("Models & privacy", style = MaterialTheme.typography.titleSmall)
                    Text("Retrieved documents remain outside model context. Remote readers require consent; changing a model never unlocks tools.", style = MaterialTheme.typography.bodySmall)
                    ResearchToggle("Follow links to other sites", value.allowExternalLinks, enabled) { onChange(value.copy(allowExternalLinks = it)) }
                    ResearchToggle("Allow connected page readers", value.allowRemoteReaders, enabled) { onChange(value.copy(allowRemoteReaders = it)) }
                    Text("Storage & diagnostics", style = MaterialTheme.typography.titleSmall)
                    Text("Versioned research manifests and source files are included in conversation backups. Evidence is retained when stopped. Targets count substantive bodies, not search snippets or abstracts.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value.includeDomains, { onChange(value.copy(includeDomains = it.take(1000))) }, label = { Text("Include domains") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value.excludeDomains, { onChange(value.copy(excludeDomains = it.take(1000))) }, label = { Text("Exclude domains") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(if (value.recencyDays == 0) "" else value.recencyDays.toString(), { onChange(value.copy(recencyDays = it.filter(Char::isDigit).take(3).toIntOrNull()?.coerceIn(0, 365) ?: 0)) }, label = { Text("Past days · blank for any time") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ResearchToggle(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(top = 12.dp))
        ResearchSettingHelp(label)
        Switch(checked, change, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun ResearchLimit(label: String, value: Int, range: IntRange, enabled: Boolean, change: (Int) -> Unit) {
    Row {
        Text("$label: $value", Modifier.weight(1f))
        ResearchSettingHelp(label)
    }
    Slider(value.toFloat(), { change(it.toInt()) }, enabled = enabled, valueRange = range.first.toFloat()..range.last.toFloat(), steps = if (range.last - range.first <= 100) (range.last - range.first - 1).coerceAtLeast(0) else 0)
}

@Composable
private fun ResearchSettingHelp(label: String) {
    var visible by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { visible = true }, modifier = Modifier.semantics { contentDescription = "Help for $label" }) { Text("?") }
    if (visible) {
        val explanation = when (label) {
            "Papers and studies" -> "Allows public academic discovery for this profile. Metadata and abstracts remain leads until substantive text is retrieved."
            "Review evidence" -> "Checks captured passages. Unavailable reviewers leave an explicit unreviewed status; a quote check does not establish scientific truth."
            "Research effort" -> "Effort controls a source target from zero to 300. Progress separately measures actual retained documents. Quotas, available evidence and configured caps can produce partial coverage."
            "Use a smaller source safety cap", "Source safety cap" -> "An explicit cap lowers the source target even at 100% effort. The interface shows the effective target."
            "Allow connected page readers" -> "Connected readers may receive source URLs and content. Enabling research alone never grants remote-reader consent."
            "Follow links to other sites" -> "Allows relevant public links within the configured depth. Domain exclusions and public-address checks remain enforced."
            "Parallel reads" -> "Sets reader concurrency. Provider quota limits can lower concurrency; more requests do not override cooldowns."
            "Per-page timeout (seconds)" -> "Bounds a single source attempt. Eligible transient failures have at most three attempts; authentication and access denials are not retried."
            else -> "Sets this profile’s research plan. Existing domain restrictions, consent, quotas and storage safeguards still apply."
        }
        AlertDialog(onDismissRequest = { visible = false }, title = { Text(label) }, text = { Text(explanation) }, confirmButton = { TextButton(onClick = { visible = false }) { Text("Close") } })
    }
}
