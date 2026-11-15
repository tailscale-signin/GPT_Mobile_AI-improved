package dev.chungjungsoo.gptmobile.presentation.ui.voice

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
import androidx.compose.material3.Surface
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
    VoiceTranscriptFrame(profileName, spokenReplies, changeSpokenReplies, showVoice, settings, end) {
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
                    VoiceTranscriptOutputControls(status, controls.phase, microphone, audioRoute)
                    bottomInteractionContent?.invoke()
                }
            )
        }
    }
}

/** The production transcript shell also supplies content colors when navigation has no Surface. */
@Composable
internal fun VoiceTranscriptFrame(
    profileName: String,
    spokenReplies: Boolean,
    changeSpokenReplies: (Boolean) -> Unit,
    showVoice: () -> Unit,
    settings: () -> Unit,
    end: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.safeDrawingPadding().testTag("voice_chat_transcript")) {
            VoiceTranscriptToolbar(profileName, spokenReplies, changeSpokenReplies, showVoice, settings, end)
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceTranscriptToolbar(
    profileName: String,
    spokenReplies: Boolean,
    changeSpokenReplies: (Boolean) -> Unit,
    showVoice: () -> Unit,
    settings: () -> Unit,
    end: () -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = end, modifier = Modifier.testTag("voice_transcript_back")) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to chats") }
            Column(Modifier.weight(1f)) {
                Text("VOICE CHAT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(profileName, modifier = Modifier.testTag("voice_transcript_profile"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = settings, modifier = Modifier.testTag("voice_transcript_settings")) { Icon(Icons.Rounded.Settings, "Voice settings") }
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = showVoice, modifier = Modifier.testTag("voice_transcript_show_voice")) {
                Icon(Icons.Rounded.GraphicEq, null)
                Text("Voice")
            }
            Text("Chat", modifier = Modifier.align(Alignment.CenterVertically).testTag("voice_transcript_chat"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Auto read", modifier = Modifier.testTag("voice_transcript_auto_read_label"), style = MaterialTheme.typography.labelMedium)
                Switch(
                    spokenReplies,
                    changeSpokenReplies,
                    modifier = Modifier.testTag("voice_chat_auto_read").semantics {
                        contentDescription = "Speak new AI responses automatically"
                    }
                )
            }
        }
    }
}

@Composable
internal fun VoiceTranscriptOutputControls(status: String, phase: VoicePhase, microphone: () -> Unit, audioRoute: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(status, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        FilledTonalButton(onClick = microphone, modifier = Modifier.testTag("voice_transcript_microphone")) {
            val playing = phase in setOf(VoicePhase.PREPARING, VoicePhase.SPEAKING)
            Icon(if (playing) Icons.Rounded.Stop else Icons.Rounded.Mic, if (playing) "Stop reply" else "Microphone")
        }
        IconButton(onClick = audioRoute, modifier = Modifier.testTag("voice_transcript_audio_output")) { Icon(Icons.Rounded.VolumeUp, "Audio output") }
    }
}
