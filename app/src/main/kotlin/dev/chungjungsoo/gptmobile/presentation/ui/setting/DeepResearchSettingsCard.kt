package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
    var advanced by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf(0) }
    var customTarget by rememberSaveable(value.customSourceTarget) { mutableStateOf(value.customSourceTarget?.toString().orEmpty()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Research engine", style = MaterialTheme.typography.titleLarge)
                    Text("Find sources. Compare evidence. Build a clear report.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(value.enabled, { onChange(value.copy(enabled = it)) }, enabled = enabled)
            }
            if (value.enabled) {
                SettingsTabs(listOf("Fast", "Balanced", "Thorough"), ResearchIntensity.entries.indexOf(value.intensity)) { onChange(value.preset(ResearchIntensity.entries[it]).copy(customSourceTarget = null)) }
                Text(
                    when (value.intensity) {
                        ResearchIntensity.QUICK -> "A focused starting point with fewer research rounds."
                        ResearchIntensity.RESEARCH -> "Broader discovery, connected details and evidence review."
                        ResearchIntensity.EXHAUSTIVE -> "Explore deeply, follow leads and compare competing findings."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("$goal source goal · ${value.effort}% depth", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("Report style", style = MaterialTheme.typography.titleSmall)
                val details = dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail.entries
                SettingsTabs(details.map { it.label }, details.indexOf(value.reportDetail)) { onChange(value.copy(reportDetail = details[it])) }
                ResearchToggle("Papers and studies", value.academicSources, enabled) { onChange(value.copy(academicSources = it)) }
                ResearchToggle("Review evidence", value.reviewEvidence, enabled) { onChange(value.copy(reviewEvidence = it)) }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Close engine controls" else "Engine controls") }
                if (advanced) {
                    SettingsTabs(listOf("Sources", "Engines", "Performance", "Access"), category) { category = it }
                    when (category) {
                        0 -> {
                            ResearchLimit("Research effort", value.effort, 0..100, enabled) { onChange(value.forEffort(it)) }
                            OutlinedTextField(customTarget, {
                                customTarget = it.filter(Char::isDigit).take(6)
                                onChange(value.copy(customSourceTarget = if (customTarget.isBlank()) null else customTarget.toIntOrNull() ?: 0).forEffort(value.effort))
                            }, label = { Text("Source goal · blank uses the preset") }, isError = customTarget.isNotBlank() && (customTarget.toIntOrNull() ?: 0) <= 0, enabled = enabled, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value.includeDomains, { onChange(value.copy(includeDomains = it.take(1000))) }, label = { Text("Prefer these domains") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value.excludeDomains, { onChange(value.copy(excludeDomains = it.take(1000))) }, label = { Text("Exclude these domains") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(if (value.recencyDays == 0) "" else value.recencyDays.toString(), { onChange(value.copy(recencyDays = it.filter(Char::isDigit).take(3).toIntOrNull()?.coerceIn(0, 365) ?: 0)) }, label = { Text("Past days · blank for any time") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                        }
                        1 -> ResearchEngineHealthPanel(value.disabledEngineIds) { id, selected -> if (enabled) onChange(value.copy(disabledEngineIds = if (selected) value.disabledEngineIds - id else value.disabledEngineIds + id)) }
                        2 -> {
                            ResearchLimit("Follow-up rounds", value.maxRounds, 1..8, enabled) { onChange(value.copy(maxRounds = it)) }
                            ResearchLimit("Link levels", value.linkDepth, 0..2, enabled) { onChange(value.copy(linkDepth = it)) }
                            ResearchLimit("Parallel reads", value.concurrency, 1..6, enabled) { onChange(value.copy(concurrency = it)) }
                            ResearchLimit("Per-page timeout (seconds)", value.pageTimeoutSeconds, 5..30, enabled) { onChange(value.copy(pageTimeoutSeconds = it)) }
                            Text("Optional run limits", style = MaterialTheme.typography.titleSmall)
                            ResearchUserLimit("Maximum HTTP requests", value.userLimits.maxRequests, enabled) { onChange(value.copy(userLimits = value.userLimits.copy(maxRequests = it))) }
                            ResearchUserLimit("Maximum downloaded bytes", value.userLimits.maxDownloadedBytes, enabled) { onChange(value.copy(userLimits = value.userLimits.copy(maxDownloadedBytes = it))) }
                            ResearchUserLimit("Maximum active milliseconds", value.userLimits.maxActiveMillis, enabled) { onChange(value.copy(userLimits = value.userLimits.copy(maxActiveMillis = it))) }
                        }
                        else -> {
                            ResearchToggle("Follow links to other sites", value.allowExternalLinks, enabled) { onChange(value.copy(allowExternalLinks = it)) }
                            ResearchToggle("Allow connected page readers", value.allowRemoteReaders, enabled) { onChange(value.copy(allowRemoteReaders = it)) }
                            Text("Connected readers receive source URLs and page content when allowed. Existing AI and tool permissions still apply.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
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
