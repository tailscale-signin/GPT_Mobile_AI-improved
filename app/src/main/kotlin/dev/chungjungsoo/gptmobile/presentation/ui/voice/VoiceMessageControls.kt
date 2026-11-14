package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.database.entity.hasHistoricalRevisionSelected
import dev.chungjungsoo.gptmobile.data.voice.VoiceMessageIdentity
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase

internal data class VoiceMessageControls(
    val active: VoiceMessageIdentity?,
    val phase: VoicePhase,
    val available: Boolean,
    val read: (MessageV2, String) -> Unit,
    val stop: () -> Unit,
    val pauseCapture: () -> Unit
)

internal val LocalVoiceMessageControls = compositionLocalOf<VoiceMessageControls?> { null }

internal fun MessageV2.voiceIdentity() = VoiceMessageIdentity(
    id,
    effectiveRunId().orEmpty(),
    if (hasHistoricalRevisionSelected()) activeRevisionIndex else ACTIVE_REVISION_LATEST
)

/** One message-local action backed by the conversation's sole output owner. */
@Composable
internal fun VoiceMessageSpeaker(message: MessageV2, text: String) {
    val controls = LocalVoiceMessageControls.current ?: return
    val active = controls.active == message.voiceIdentity() && controls.phase in setOf(VoicePhase.THINKING, VoicePhase.PREPARING, VoicePhase.SPEAKING)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { if (active) controls.stop() else controls.read(message, text) },
            enabled = active || controls.available && text.isNotBlank(),
            modifier = Modifier.heightIn(min = 48.dp).testTag("voice_response_speaker_${message.id}")
                .semantics { contentDescription = if (active) "Stop reading AI response" else "Read AI response aloud" }
        ) {
            if (active && controls.phase == VoicePhase.PREPARING) {
                CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
            } else {
                Icon(if (active) Icons.Rounded.Stop else Icons.Rounded.VolumeUp, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.size(8.dp))
            Text(if (active) "Stop" else "Read aloud", color = MaterialTheme.colorScheme.primary)
        }
    }
}
