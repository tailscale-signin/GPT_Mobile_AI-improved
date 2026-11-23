package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechText
import dev.chungjungsoo.gptmobile.presentation.ui.chat.rememberPrivacyProtectedText

/** The visible voice answer uses the exact speech projection; richer content stays explicitly available. */
@Composable
internal fun VoiceAnswerPresentation(
    text: String,
    richText: String,
    identity: Any,
    isLoading: Boolean,
    privacyProtectedValues: List<String>,
    enabled: Boolean = true,
    hasAdditionalDetails: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface,
    details: @Composable () -> Unit
) {
    if (LocalVoiceMessageControls.current == null || !enabled) {
        details()
        return
    }
    val speech = remember(text, isLoading) { VoiceSpeechText.project(text, final = !isLoading) }
    val hasDetails = hasAdditionalDetails || richText.isNotBlank() && (richText != speech || VoiceSpeechText.hasDetails(text))
    var expanded by rememberSaveable(identity) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Spoken answer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (speech.isNotBlank()) {
            // A transformation can assemble a new literal from disjoint source runs.
            // Withhold receipt colour unless the displayed text is itself unchanged;
            // the original rich view retains its existing verbatim-source proof.
            val annotated = rememberPrivacyProtectedText(
                AnnotatedString(speech),
                values = privacyProtectedValues,
                sourceText = text
            )
            SelectionContainer {
                Text(annotated, Modifier.testTag("voice_spoken_answer"), style = MaterialTheme.typography.bodyLarge, color = color)
            }
        } else if (!isLoading && hasDetails) {
            Text("This response contains visual details. Open them below.", style = MaterialTheme.typography.bodyMedium)
        } else if (!isLoading) {
            Text("No spoken answer is available for this response.", style = MaterialTheme.typography.bodyMedium)
        }
        if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("voice_answer_generating"))
        if (hasDetails) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.testTag("voice_answer_details_toggle").semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            ) {
                Text(if (expanded) "Hide details" else "Show details")
            }
            if (expanded) details()
        }
    }
}
