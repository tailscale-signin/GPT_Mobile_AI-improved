package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatScreen
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel

internal data class VoiceChatDestinations(
    val localModels: () -> Unit,
    val evidenceAccess: () -> Unit,
    val profileSettings: (String) -> Unit,
    val conversation: (Int, Boolean) -> Unit
)

/** The full existing transcript and composer, retaining the same voice owner and room. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoiceTranscriptScreen(
    model: ChatViewModel,
    profileName: String,
    status: String,
    controls: VoiceMessageControls,
    spokenReplies: Boolean,
    changeSpokenReplies: (Boolean) -> Unit,
    showVoice: () -> Unit,
    microphone: () -> Unit,
    audioRoute: () -> Unit,
    settings: () -> Unit,
    end: () -> Unit,
    destinations: VoiceChatDestinations,
    bottomInteractionContent: (@Composable () -> Unit)?
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().testTag("voice_chat_transcript")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = end) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to chats") }
            Column(Modifier.weight(1f)) {
                Text("VOICE CHAT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(profileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = settings) { Icon(Icons.Rounded.Settings, "Voice settings") }
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = showVoice) {
                Icon(Icons.Rounded.GraphicEq, null)
                Text("Voice")
            }
            Text("Chat", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Auto read", style = MaterialTheme.typography.labelMedium)
                Switch(
                    spokenReplies,
                    changeSpokenReplies,
                    modifier = Modifier.testTag("voice_chat_auto_read").semantics {
                        contentDescription = "Speak new AI responses automatically"
                    }
                )
            }
        }
        Box(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalVoiceMessageControls provides controls) {
                ChatScreen(
                    chatViewModel = model,
                    onBackAction = end,
                    showTopBar = false,
                    onNavigateToLocalModels = destinations.localModels,
                    onReviewEvidenceAccess = destinations.evidenceAccess,
                    onProfileSettings = destinations.profileSettings,
                    onOpenConversation = destinations.conversation,
                    bottomInteractionContent = {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(status, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            FilledTonalButton(onClick = microphone, modifier = Modifier.testTag("voice_transcript_microphone")) {
                                val playing = controls.phase in setOf(VoicePhase.PREPARING, VoicePhase.SPEAKING)
                                Icon(if (playing) Icons.Rounded.Stop else Icons.Rounded.Mic, if (playing) "Stop reply" else "Microphone")
                            }
                            IconButton(onClick = audioRoute) { Icon(Icons.Rounded.VolumeUp, "Audio output") }
                        }
                        bottomInteractionContent?.invoke()
                    }
                )
            }
        }
    }
}
