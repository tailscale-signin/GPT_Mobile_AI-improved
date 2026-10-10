package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
                Text("${value.maxPages} pages · ${value.maxRounds} rounds · ${if (value.allowRemoteReaders) "Phone + connected readers" else "On-device retrieval"}", style = MaterialTheme.typography.bodySmall)
                ResearchToggle("Papers and studies", value.academicSources, enabled) { onChange(value.copy(academicSources = it)) }
                ResearchToggle("Review evidence", value.reviewEvidence, enabled) { onChange(value.copy(reviewEvidence = it)) }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Less" else "Advanced") }
                if (advanced) {
                    ResearchLimit("Pages", value.maxPages, 0..30, enabled) { onChange(value.copy(maxPages = it)) }
                    ResearchLimit("Search rounds", value.maxRounds, 1..3, enabled) { onChange(value.copy(maxRounds = it)) }
                    ResearchLimit("Link levels", value.linkDepth, 0..2, enabled) { onChange(value.copy(linkDepth = it)) }
                    ResearchLimit("Parallel reads", value.concurrency, 1..3, enabled) { onChange(value.copy(concurrency = it)) }
                    ResearchToggle("Follow links to other sites", value.allowExternalLinks, enabled) { onChange(value.copy(allowExternalLinks = it)) }
                    ResearchToggle("Allow connected page readers", value.allowRemoteReaders, enabled) { onChange(value.copy(allowRemoteReaders = it)) }
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
        Switch(checked, change, enabled = enabled)
    }
}

@Composable
private fun ResearchLimit(label: String, value: Int, range: IntRange, enabled: Boolean, change: (Int) -> Unit) {
    Text("$label: $value")
    Slider(value.toFloat(), { change(it.toInt()) }, enabled = enabled, valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0))
}
