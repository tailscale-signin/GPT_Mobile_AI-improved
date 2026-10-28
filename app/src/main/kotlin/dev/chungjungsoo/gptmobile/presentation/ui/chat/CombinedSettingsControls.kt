package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.CombinedChatSettingsDraft
import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json

internal val CombinedChatSettingsDraftSaver = Saver<CombinedChatSettingsDraft, String>(
    save = { Json.encodeToString(CombinedChatSettingsDraft.serializer(), it) },
    restore = { runCatching { Json.decodeFromString(CombinedChatSettingsDraft.serializer(), it) }.getOrNull() }
)

@Composable
fun CombinedSettingsControls(
    settings: ConversationCombinedSettings,
    onChange: (ConversationCombinedSettings) -> Unit,
    enabled: Boolean = true
) {
    val normalized = settings.normalized()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!normalized.isSupported) {
            Text("These Combined settings were saved by a newer app. Update the app to edit them; the saved values are preserved.", style = MaterialTheme.typography.bodySmall)
        }
        CombinedPolicySlider(
            title = "Merge style",
            value = normalized.collectionBias,
            labels = listOf("Evidence-based", "Evidence-led", "Balanced", "Broad", "Collection"),
            helpers = listOf(
                "Prioritize the strongest support and material uncertainty.",
                "Prioritize evidence and retain useful differences.",
                "Combine a clear answer with meaningful alternatives.",
                "Preserve more relevant ideas and competing interpretations.",
                "Keep distinct contributions and clearly mark uncertainty."
            ),
            tag = "combined_merge_style",
            enabled = enabled && normalized.isSupported,
            onChange = { onChange(normalized.copy(collectionBias = it)) }
        )
        CombinedPolicySlider(
            title = "Response effort",
            value = normalized.effort,
            labels = listOf("Quick", "Efficient", "Balanced", "Thorough", "Full scale"),
            helpers = listOf(
                "Use compact drafts and only the checking needed for your request.",
                "Use targeted checks and useful detail with little extra work.",
                "Balance research, response detail, and resource use.",
                "Allow deeper checks, broader research, and more explanation.",
                "Continue useful research and detail until the task is complete or an actual limit is reached."
            ).map { "$it Your requested limits still apply." },
            tag = "combined_response_effort",
            enabled = enabled && normalized.isSupported,
            onChange = { onChange(normalized.copy(effort = it)) }
        )
        val preferences = dev.chungjungsoo.gptmobile.data.agent.CombinedSynthesisPreferences.from(normalized)
        Text("Synthesis preferences · your explicit instructions take precedence", style = MaterialTheme.typography.titleSmall)
        CombinedPolicySlider(
            "Completeness",
            preferences.completeness,
            listOf("Essential", "Focused", "Balanced", "Broad", "All unique claims"),
            List(5) { "Preserve relevant source content within your requested scope. Original contributions remain available." },
            "combined_completeness",
            enabled && normalized.isSupported
        ) { onChange(preferences.copy(completeness = it).applyTo(normalized)) }
        CombinedPolicySlider(
            "Verbosity",
            preferences.verbosity,
            listOf("Very concise", "Concise", "Adaptive", "Detailed", "Comprehensive"),
            List(5) { "Explicit length requirements and actual provider limits still apply." },
            "combined_verbosity",
            enabled && normalized.isSupported
        ) { onChange(preferences.copy(verbosity = it).applyTo(normalized)) }
        CombinedPolicySlider(
            "Source diversity",
            preferences.sourceDiversity,
            listOf("Focused", "Selective", "Balanced", "Broad", "Every contributor"),
            List(5) { "Retain relevant unique and dissenting views with their source attribution." },
            "combined_diversity",
            enabled && normalized.isSupported
        ) { onChange(preferences.copy(sourceDiversity = it).applyTo(normalized)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Check retained evidence", style = MaterialTheme.typography.titleSmall)
                Text("Separate supported, disputed and unverified claims. Uses existing evidence and grants.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(preferences.checkRetainedEvidence, { onChange(preferences.copy(checkRetainedEvidence = it).applyTo(normalized)) }, enabled = enabled && normalized.isSupported)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Inspect large contributions in batches", style = MaterialTheme.typography.titleSmall)
                Text("Save source inspections and publish one final answer. Extra requests use the accepted budget.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(preferences.incrementalSynthesis, { onChange(preferences.copy(incrementalSynthesis = it).applyTo(normalized)) }, enabled = enabled && normalized.isSupported)
        }
        if (dev.chungjungsoo.gptmobile.BuildConfig.COMBINED_APPEND_CONTINUATIONS) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Continue long answers", style = MaterialTheme.typography.titleSmall)
                    Text("Uses additional requests to finish the same answer.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = normalized.continueLongAnswers,
                    onCheckedChange = { onChange(normalized.copy(continueLongAnswers = it)) },
                    enabled = enabled && normalized.isSupported,
                    modifier = Modifier.testTag("combined_continue_long_answers").semantics { contentDescription = "Continue long answers" }
                )
            }
        }
        Text("After saving, changes on this tab apply to new requests. Running and queued requests keep their original Combined setup.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CombinedPolicySlider(
    title: String,
    value: Int,
    labels: List<String>,
    helpers: List<String>,
    tag: String,
    enabled: Boolean,
    onChange: (Int) -> Unit
) {
    val stop = ConversationCombinedSettings.nearestStop(value)
    val index = ConversationCombinedSettings.STOPS.indexOf(stop)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$title · ${labels[index]}", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = stop.toFloat(),
            onValueChange = { onChange(ConversationCombinedSettings.nearestStop(it.roundToInt())) },
            valueRange = 0f..100f,
            steps = 3,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).semantics {
                contentDescription = title
                stateDescription = "${labels[index]}, ${index + 1} of 5"
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(labels.first(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(labels.last(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
        }
        Text(helpers[index], style = MaterialTheme.typography.bodySmall)
    }
}
