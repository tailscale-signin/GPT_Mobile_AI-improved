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
    val goal = if (value.enabled && value.effort > 0) value.customSourceTarget ?: DeepResearchSettings.targetForEffort(value.effort) else 0
    var advanced by rememberSaveable { mutableStateOf(true) }
    var customTarget by rememberSaveable { mutableStateOf(value.customSourceTarget?.toString().orEmpty()) }
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
                Text("${value.effort}% effort · $goal-source goal · No additional cap", style = MaterialTheme.typography.bodySmall)
                val limits = listOfNotNull(
                    value.userLimits.maxRequests?.let { "$it HTTP requests" },
                    value.userLimits.maxDownloadedBytes?.let { "$it downloaded bytes" },
                    value.userLimits.maxActiveMillis?.let { "$it active milliseconds" }
                )
                if (limits.isNotEmpty()) Text("User-selected limits: ${limits.joinToString()}", style = MaterialTheme.typography.bodySmall)
                if (value.migrationNotice.isNotBlank()) Text(value.migrationNotice, style = MaterialTheme.typography.bodySmall)
                ResearchToggle("Papers and studies", value.academicSources, enabled) { onChange(value.copy(academicSources = it)) }
                ResearchToggle("Review evidence", value.reviewEvidence, enabled) { onChange(value.copy(reviewEvidence = it)) }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Less" else "Advanced") }
                if (advanced) {
                    Text("Overview & defaults", style = MaterialTheme.typography.titleSmall)
                    ResearchLimit("Research effort", value.effort, 0..100, enabled) { onChange(value.forEffort(it)) }
                    OutlinedTextField(customTarget, {
                        customTarget = it.filter(Char::isDigit).take(10)
                        val parsed = customTarget.toIntOrNull()
                        onChange(value.copy(customSourceTarget = if (customTarget.isEmpty()) null else parsed ?: 0).forEffort(value.effort))
                    }, label = { Text("Custom source goal · blank for effort preset") }, isError = customTarget.isNotEmpty() && (customTarget.toIntOrNull() ?: 0) <= 0, enabled = enabled, modifier = Modifier.fillMaxWidth())
                    Text("Engines & health", style = MaterialTheme.typography.titleSmall)
                    dev.chungjungsoo.gptmobile.presentation.ui.setting.ResearchEngineHealthPanel(value.disabledEngineIds) { id, selected -> onChange(value.copy(disabledEngineIds = if (selected) value.disabledEngineIds - id else value.disabledEngineIds + id)) }
                    Text("Research quality", style = MaterialTheme.typography.titleSmall)
                    Text("Discovery continues until the goal or an explicit stop reason; query batches are not lifetime limits.", style = MaterialTheme.typography.bodySmall)
                    ResearchLimit("Link levels", value.linkDepth, 0..2, enabled) { onChange(value.copy(linkDepth = it)) }
                    ResearchLimit("Parallel reads", value.concurrency, 1..6, enabled) { onChange(value.copy(concurrency = it)) }
                    Text("Performance", style = MaterialTheme.typography.titleSmall)
                    ResearchLimit("Per-page timeout (seconds)", value.pageTimeoutSeconds, 5..30, enabled) { onChange(value.copy(pageTimeoutSeconds = it)) }
                    Text("No automatic request, download or whole-run time ceiling. Individual attempts, provider quotas and device resources remain bounded.", style = MaterialTheme.typography.bodySmall)
                    Text("Optional user limits · blank means no limit", style = MaterialTheme.typography.titleSmall)
                    ResearchUserLimit("Maximum HTTP requests", value.userLimits.maxRequests, enabled) { onChange(value.copy(userLimits = value.userLimits.copy(maxRequests = it))) }
                    ResearchUserLimit("Maximum downloaded bytes", value.userLimits.maxDownloadedBytes, enabled) { onChange(value.copy(userLimits = value.userLimits.copy(maxDownloadedBytes = it))) }
                    ResearchUserLimit("Maximum active milliseconds", value.userLimits.maxActiveMillis, enabled) { onChange(value.copy(userLimits = value.userLimits.copy(maxActiveMillis = it))) }
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
            "Allow connected page readers" -> "Connected readers may receive source URLs and content. Enabling research alone never grants remote-reader consent."
            "Follow links to other sites" -> "Allows relevant public links within the configured depth. Domain exclusions and public-address checks remain enforced."
            "Parallel reads" -> "Sets reader concurrency. Provider quota limits can lower concurrency; more requests do not override cooldowns."
            "Per-page timeout (seconds)" -> "Bounds a single source attempt. Eligible transient failures have at most three attempts; authentication and access denials are not retried."
            else -> "Sets the research plan. Existing domain restrictions, consent, quotas and storage safeguards still apply."
        }
        AlertDialog(onDismissRequest = { visible = false }, title = { Text(label) }, text = { Text(explanation) }, confirmButton = { TextButton(onClick = { visible = false }) { Text("Close") } })
    }
}

@Composable
private fun ResearchUserLimit(label: String, value: Long?, enabled: Boolean, onChange: (Long?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(text, {
        text = it.filter(Char::isDigit).take(19)
        onChange(if (text.isEmpty()) null else text.toLongOrNull() ?: 0)
    }, label = { Text(label) }, isError = text.isNotEmpty() && (text.toLongOrNull() ?: 0) <= 0, enabled = enabled, modifier = Modifier.fillMaxWidth())
}
