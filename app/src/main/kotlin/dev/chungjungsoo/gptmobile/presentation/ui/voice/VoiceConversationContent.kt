package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStore
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecoveryAction
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu

internal data class VoiceConversationUiState(
    val profileName: String,
    val profiles: List<PlatformV2>,
    val selectedProfileId: String?,
    val captions: Boolean,
    val session: VoiceState,
    val status: String,
    val remaining: Int,
    val localModel: LocalVoiceModelState,
    val language: String,
    val recognitionReady: Boolean,
    val enabled: Boolean,
    val voiceReady: Boolean,
    val profileError: String?,
    val preferencesApplied: Boolean,
    val waitingApproval: Boolean,
    val draft: String
)

internal class VoiceConversationActions(
    val selectProfile: (String) -> Unit,
    val openTranscript: () -> Unit,
    val openSettings: () -> Unit,
    val showSettings: () -> Unit,
    val installLocalModel: () -> Unit,
    val cancelLocalModelInstall: () -> Unit,
    val changeDraft: (String) -> Unit,
    val sendDraft: () -> Unit,
    val listen: () -> Unit,
    val recordAgain: () -> Unit,
    val repair: (VoiceRecoveryAction) -> Unit,
    val openVoiceConfiguration: () -> Unit,
    val replay: () -> Unit,
    val openChat: () -> Unit,
    val toggleMicrophone: () -> Unit,
    val openAudioRoute: () -> Unit,
    val end: () -> Unit
)

@Composable
internal fun VoiceConversationScreen(state: VoiceConversationUiState, actions: VoiceConversationActions) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp).testTag("voice_chat_screen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VoiceConversationHeader(state, actions)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val compact = maxHeight < 420.dp || LocalDensity.current.fontScale > 1.3f
            VoiceConversationStatus(state, actions, compact, Modifier.fillMaxSize())
        }
        VoiceConversationControls(state, actions)
    }
}

@Composable
private fun VoiceConversationHeader(state: VoiceConversationUiState, actions: VoiceConversationActions) {
    Row(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.end) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to chats") }
        VoiceProfilePicker(state, actions.selectProfile, Modifier.weight(1f))
        IconButton(onClick = actions.openTranscript) {
            Icon(Icons.Rounded.Subtitles, "Open full transcript", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = actions.openSettings, enabled = state.preferencesApplied) {
            Icon(Icons.Rounded.Settings, "Voice settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VoiceProfilePicker(state: VoiceConversationUiState, select: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = state.preferencesApplied && !state.session.awaitingReply && state.profiles.isNotEmpty()
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("voice_profile_picker")
                .semantics { contentDescription = "AI profile: ${state.profileName}" },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("VOICE CHAT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(state.profileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(min = 220.dp, max = 320.dp).heightIn(max = 320.dp)) {
            state.profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name.ifBlank { profile.model }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { if (profile.uid == state.selectedProfileId) Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary) },
                    onClick = {
                        expanded = false
                        if (profile.uid != state.selectedProfileId) select(profile.uid)
                    }
                )
            }
        }
    }
}

@Composable
private fun VoiceConversationStatus(state: VoiceConversationUiState, actions: VoiceConversationActions, compact: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        VoiceWave(state.session, Modifier.widthIn(max = 520.dp).fillMaxWidth().height(if (compact) 96.dp else 160.dp).clearAndSetSemantics {})
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .09f)) {
            Text(
                state.status,
                Modifier.padding(horizontal = 20.dp, vertical = 10.dp).semantics { if (state.remaining == 0) liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
        }
        Text(voiceListeningHint(state.session), Modifier.padding(top = 10.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        VoiceConversationSetup(state, actions)
        if (state.captions) VoiceLiveCaptions(state.session, actions.openTranscript, compact, Modifier.padding(top = 16.dp).widthIn(max = 560.dp).fillMaxWidth())
        VoiceConversationRecovery(state, actions)
    }
}

@Composable
private fun VoiceConversationSetup(state: VoiceConversationUiState, actions: VoiceConversationActions) {
    state.profileError?.let { reason ->
        Text(reason, Modifier.padding(top = 16.dp).widthIn(max = 360.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        TextButton(onClick = actions.openSettings) { Text("Choose another AI profile") }
        TextButton(onClick = actions.openChat) { Text("Review profile in chat") }
    }
    if (!state.recognitionReady) {
        Spacer(Modifier.height(16.dp))
        LocalVoiceSetupCard(state.localModel, actions.installLocalModel, actions.cancelLocalModelInstall, {})
        if (!LocalVoiceModelStore.supportsLanguage(state.language)) {
            Text("Moonshine recognizes English. Choose your region in Voice settings.", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = actions.showSettings) { Text("Choose speech language") }
        }
    }
}

@Composable
private fun VoiceConversationRecovery(state: VoiceConversationUiState, actions: VoiceConversationActions) {
    if (state.session.hasDraft && state.session.phase != VoicePhase.REVIEWING) {
        TextButton(onClick = actions.listen, enabled = state.enabled && state.voiceReady && state.preferencesApplied) { Text("Review captured text") }
    }
    if (state.session.phase == VoicePhase.REVIEWING) {
        OutlinedTextField(state.draft, actions.changeDraft, label = { Text("Your message") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), minLines = 2, maxLines = 20)
        Button(onClick = actions.sendDraft, enabled = state.draft.isNotBlank()) { Text("Send") }
        TextButton(onClick = actions.recordAgain) { Text("Record again") }
    }
    state.session.notice?.let {
        Text(it, Modifier.padding(top = 16.dp).widthIn(max = 360.dp), style = MaterialTheme.typography.bodyMedium, color = if (state.remaining > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
    state.session.recoveryAction?.let { action ->
        TextButton(onClick = { actions.repair(action) }) { Text(voiceRepairLabel(action)) }
        TextButton(onClick = actions.openVoiceConfiguration) { Text("Change voice configuration") }
        TextButton(onClick = actions.listen, enabled = state.remaining == 0) { Text("Try microphone after repair") }
    }
    if (state.session.reply.isNotBlank() && state.session.phase in setOf(VoicePhase.PAUSED, VoicePhase.ERROR)) TextButton(onClick = actions.replay) { Text("Replay reply") }
    if (state.waitingApproval || state.session.phase == VoicePhase.ERROR || !state.enabled || !state.recognitionReady) TextButton(onClick = actions.openChat) { Text("Continue in chat") }
}

private fun voiceListeningHint(state: VoiceState): String = when {
    state.phase == VoicePhase.PREPARING -> "Your reply is getting ready to play"
    state.phase == VoicePhase.SPEAKING -> "Listening resumes after the reply"
    state.phase == VoicePhase.LISTENING -> "Go ahead, I’m listening"
    state.phase == VoicePhase.THINKING -> "You can follow the reply in captions"
    state.muted -> "Unmute when you’re ready to speak"
    else -> "Use the microphone when you are ready"
}

@Composable
private fun VoiceConversationControls(state: VoiceConversationUiState, actions: VoiceConversationActions) {
    Surface(
        Modifier.padding(top = 8.dp).widthIn(max = 480.dp).fillMaxWidth(),
        shape = RoundedCornerShape(36.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            VoiceControl("Text chat", Icons.Rounded.ChatBubbleOutline, actions.openChat, false, Modifier.weight(1f))
            VoiceMicrophoneControl(state, actions.toggleMicrophone)
            VoiceControl("Audio", if (state.session.speaker) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, actions.openAudioRoute, !state.session.speaker, Modifier.weight(1f))
        }
    }
}

@Composable
private fun VoiceMicrophoneControl(state: VoiceConversationUiState, toggle: () -> Unit) {
    val listening = state.session.phase == VoicePhase.LISTENING
    val playing = state.session.phase in setOf(VoicePhase.PREPARING, VoicePhase.SPEAKING)
    val colors = MaterialTheme.colorScheme
    val (icon, label) = when {
        playing -> Icons.Rounded.Stop to "Stop reply"
        state.session.muted -> Icons.Rounded.MicOff to "Start listening"
        state.session.phase == VoicePhase.ERROR -> Icons.Rounded.Mic to "Retry microphone"
        state.session.phase == VoicePhase.PAUSED -> Icons.Rounded.Mic to "Start listening"
        else -> Icons.Rounded.Mic to "Turn microphone off"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            if (listening || playing) {
                val level = if (listening && state.session.hasAudioLevel) state.session.level.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f else 0f
                Canvas(Modifier.matchParentSize()) {
                    drawCircle(colors.primary, radius = (52 + level * 3).dp.toPx(), alpha = .2f + level * .2f, style = Stroke(2.dp.toPx()))
                }
            }
            FilledIconButton(
                onClick = toggle,
                enabled = playing || state.enabled && state.voiceReady && state.preferencesApplied && state.recognitionReady,
                modifier = Modifier.size(96.dp).testTag("voice_microphone_toggle"),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (listening || playing) colors.primary else colors.primaryContainer,
                    contentColor = if (listening || playing) colors.onPrimary else colors.onPrimaryContainer
                )
            ) { Icon(icon, label, Modifier.size(36.dp)) }
        }
        Text(if (playing) "Stop reply" else "Microphone", Modifier.widthIn(max = 128.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurface, textAlign = TextAlign.Center)
    }
}

@Composable
private fun VoiceControl(label: String, image: ImageVector, click: () -> Unit, selected: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = click,
            modifier = Modifier.size(52.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
        ) { Icon(image, label, Modifier.size(24.dp)) }
        Text(label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    }
}
