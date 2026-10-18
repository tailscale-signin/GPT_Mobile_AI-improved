package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
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
