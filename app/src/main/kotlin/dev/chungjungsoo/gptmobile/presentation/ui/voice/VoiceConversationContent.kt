package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.SpeechLanguage
import dev.chungjungsoo.gptmobile.data.voice.SpeechLanguages
import dev.chungjungsoo.gptmobile.data.voice.VoiceInterruptionStatus
import dev.chungjungsoo.gptmobile.data.voice.VoiceMicrophoneAction
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionConfiguration
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionTranscript
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecoveryAction
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationPair
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationPrompt
import dev.chungjungsoo.gptmobile.data.voice.microphoneAction
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
    val draft: String,
    val outputLevel: kotlinx.coroutines.flow.StateFlow<Float?>? = null,
    val inputLevel: kotlinx.coroutines.flow.StateFlow<dev.chungjungsoo.gptmobile.data.voice.VoiceInputLevel>? = null,
    val captionCursor: kotlinx.coroutines.flow.StateFlow<dev.chungjungsoo.gptmobile.data.voice.VoiceCaptionCursor>? = null,
    val handsFreeInterruption: Boolean = false,
    val interruptionState: kotlinx.coroutines.flow.StateFlow<VoiceInterruptionStatus>? = null,
    val recognitionBackend: VoiceRecognitionBackend = SpeechLanguages.recognitionBackend(language),
    val recognitionConfiguration: VoiceRecognitionConfiguration = VoiceRecognitionConfiguration(),
    val replyLanguage: String = SpeechLanguages.default.tag,
    val translationMode: Boolean = false,
    val replyLanguageProfiles: List<SpeechLanguage> = SpeechLanguages.synthesis,
    val translationPair: VoiceTranslationPair = VoiceTranslationPair(),
    val translationModel: LocalVoiceModelState? = null,
    val translationSetupNotice: String? = null
)

internal class VoiceConversationActions(
    val selectProfile: (String) -> Unit,
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
    val changeReplyLanguage: (String) -> Unit,
    val changeTranslationMode: (Boolean) -> Unit,
    val end: () -> Unit,
    val changeTranslationPair: (VoiceTranslationPair) -> Unit = {},
    val installTranslationModel: () -> Unit = {},
    val cancelTranslationModel: () -> Unit = {}
)

@Composable
internal fun VoiceConversationScreen(state: VoiceConversationUiState, actions: VoiceConversationActions, roomId: Int = 0) = key(roomId) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(colors.background, colors.primary.copy(alpha = .045f).compositeOver(colors.background), colors.background)
            )
        ).safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp).testTag("voice_chat_screen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VoiceConversationHeader(state, actions)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            val compact = maxHeight < 420.dp || density.fontScale > 1.3f
            val typography = MaterialTheme.typography
            // The scrollable prompt must initially show its label and at least one line of dictation.
            val firstSourceLine = with(density) { typography.labelMedium.lineHeight.toDp() + typography.bodyLarge.lineHeight.toDp() } + 8.dp
            val sourceHeight = maxOf(maxHeight * .30f, firstSourceLine.coerceAtMost(maxHeight))
            val replyHeight = maxHeight * .48f
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                VoiceTranslationSource(state.session.translationPrompt.takeIf { state.translationMode }, state.session.recognitionTranscript, Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = sourceHeight))
                VoiceConversationStatus(state, actions, compact, Modifier.weight(1f).fillMaxWidth())
                if (state.translationMode) {
                    VoiceTranslationReply(state, compact, Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = replyHeight))
                }
            }
        }
        VoiceConversationControls(state, actions)
    }
}

@Composable
private fun VoiceTranslationSource(prompt: VoiceTranslationPrompt?, timing: VoiceRecognitionTranscript?, modifier: Modifier) {
    val latest by rememberUpdatedState(prompt)
    val latestTiming by rememberUpdatedState(timing)
    var timingSnapshot by remember { mutableStateOf<VoiceRecognitionTranscript?>(null) }
    timingSnapshot?.let { VoiceRecognitionTimingsDialog(it) { timingSnapshot = null } }
    val target = remember { MutableTransitionState<Long?>(null) }
    target.targetState = prompt?.captureId
    val transition = rememberTransition(target, label = "Translation source")
    transition.AnimatedContent(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
        transitionSpec = { (fadeIn(tween(750)) togetherWith fadeOut(tween(250))).using(null) }
    ) { captureId ->
        if (captureId != null) {
            var lastWords by remember(captureId) { mutableStateOf("") }
            val words = latest?.takeIf { it.captureId == captureId }?.text ?: lastWords
            SideEffect { lastWords = words }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("You", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(words, Modifier.testTag("voice_translation_source"), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
                val observed = latestTiming?.takeIf { latest?.captureId == captureId && it.text == words && it.timings.isNotEmpty() }
                if (observed != null) TextButton(onClick = { timingSnapshot = observed }) { Text("Timing") }
            }
        }
    }
}

@Composable
private fun VoiceTranslationReply(state: VoiceConversationUiState, compact: Boolean, modifier: Modifier) {
    val current = state.session
    var previous by remember { mutableStateOf<VoiceState?>(null) }
    if (current.reply.isNotBlank() && (previous?.reply != current.reply || previous?.readingDetails != current.readingDetails)) {
        SideEffect {
            previous = VoiceState(reply = current.reply, readingDetails = current.readingDetails, replyComplete = true, captionPresentationId = current.captionPresentationId)
        }
    }
    val display = current.takeIf { it.reply.isNotBlank() } ?: previous ?: return
    VoiceLiveCaptions(display, compact, modifier, state.captionCursor.takeIf { display === current }, replyOnly = true)
}

@Composable
private fun VoiceConversationHeader(state: VoiceConversationUiState, actions: VoiceConversationActions) {
    Row(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.end) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to chats", tint = MaterialTheme.colorScheme.onSurface) }
        VoiceProfilePicker(state, actions.selectProfile, Modifier.weight(1f))
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
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .2f))
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
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val landscape = maxWidth >= 600.dp && maxWidth > maxHeight
        val status: @Composable () -> Unit = {
            VoiceStatusChip(state)
            Text(
                voiceListeningHint(state.session, state.handsFreeInterruption),
                Modifier.padding(top = 6.dp, bottom = 10.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            VoiceWave(state.session, Modifier.widthIn(max = 420.dp).fillMaxWidth().height(if (compact) 64.dp else 104.dp).clearAndSetSemantics {}, state.outputLevel, state.inputLevel)
        }
        val content: @Composable () -> Unit = {
            if (state.captions && !state.translationMode) VoiceLiveCaptions(state.session, compact, Modifier.widthIn(max = 560.dp).fillMaxWidth(), state.captionCursor)
            VoiceConversationSetup(state, actions)
            VoiceConversationRecovery(state, actions)
        }
        if (landscape) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(.8f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { status() }
                Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { content() }
            }
        } else {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
            ) {
                status()
                content()
            }
        }
    }
}

@Composable
private fun VoiceStatusChip(state: VoiceConversationUiState) {
    val colors = MaterialTheme.colorScheme
    val accent = if (state.session.phase == VoicePhase.ERROR) colors.error else colors.primary
    Surface(shape = CircleShape, color = accent.copy(alpha = .08f), border = BorderStroke(1.dp, accent.copy(alpha = .12f))) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).background(accent, CircleShape))
            Text(
                state.status,
                Modifier.semantics { if (state.remaining == 0) liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleSmall,
                color = accent,
                textAlign = TextAlign.Center
            )
        }
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
        if (state.recognitionBackend == VoiceRecognitionBackend.LOCAL_MOONSHINE) {
            LocalVoiceSetupCard(state.localModel, actions.installLocalModel, actions.cancelLocalModelInstall, {}, model = state.recognitionConfiguration.model, compute = state.recognitionConfiguration.compute)
            if (!state.recognitionConfiguration.model.supportsLanguage(state.language)) {
                TextButton(onClick = actions.showSettings) { Text("Choose a supported recognition language") }
            }
        } else {
            Text("Android offline recognition is unavailable on this phone. Choose a local model that supports your recognition language.", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = actions.showSettings) { Text("Choose speech language") }
        }
    }
}

@Composable
private fun VoiceConversationRecovery(state: VoiceConversationUiState, actions: VoiceConversationActions) {
    val interruption = state.interruptionState?.collectAsStateWithLifecycle()?.value
    if (state.handsFreeInterruption && state.session.phase == VoicePhase.SPEAKING && interruption == VoiceInterruptionStatus.UNAVAILABLE) {
        Text("Hands-free interruption needs local speech and echo cancellation on this audio route. Tap Stop to end the reply.", Modifier.testTag("voice_interruption_unavailable"), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
    if (state.session.hasDraft && state.session.phase != VoicePhase.REVIEWING) {
        TextButton(onClick = actions.listen, enabled = state.enabled && state.voiceReady && state.preferencesApplied) { Text("Review captured text") }
    }
    if (state.session.phase == VoicePhase.REVIEWING) {
        OutlinedTextField(state.draft, actions.changeDraft, label = { Text("Heard text · editable") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), minLines = 2, maxLines = 20)
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

private fun voiceListeningHint(state: VoiceState, handsFreeInterruption: Boolean): String = when {
    state.phase == VoicePhase.PREPARING -> "Your reply is getting ready to play"
    state.phase == VoicePhase.SPEAKING -> if (handsFreeInterruption) "Speak to interrupt when echo cancellation is available" else "Tap Stop to end the reply"
    state.phase == VoicePhase.LISTENING -> "Go ahead, I’m listening."
    state.phase == VoicePhase.THINKING -> "Preparing your reply"
    state.muted -> "Tap the microphone when you’re ready to speak"
    else -> "Use the microphone when you are ready"
}

@Composable
private fun VoiceConversationControls(state: VoiceConversationUiState, actions: VoiceConversationActions) {
    var choosingLanguage by rememberSaveable { mutableStateOf(false) }
    val action = state.session.microphoneAction
    val canSelect = state.preferencesApplied && action == VoiceMicrophoneAction.LISTEN
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        VoiceMicrophoneButton(
            action = action,
            onClick = actions.toggleMicrophone,
            enabled = action != VoiceMicrophoneAction.LISTEN || state.enabled && state.voiceReady && state.preferencesApplied && state.recognitionReady && state.session.phase != VoicePhase.REVIEWING,
            modifier = Modifier.size(88.dp).testTag("voice_microphone_toggle"),
            iconSize = 44.dp
        )
        Row(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Spacer(Modifier.weight(1f))
            VoiceControl("Text chat", Icons.Rounded.ChatBubbleOutline, actions.openChat, Modifier.weight(1f))
            val language = if (state.translationMode) {
                val pair = state.translationPair.normalized()
                "${java.util.Locale.forLanguageTag(pair.baseLanguage).displayLanguage} ↔ ${java.util.Locale.forLanguageTag(pair.translationLanguage).displayLanguage}"
            } else {
                state.replyLanguageProfiles.firstOrNull { it.tag == state.replyLanguage }?.label ?: java.util.Locale.forLanguageTag(state.replyLanguage).displayName
            }
            VoiceControl(
                language,
                Icons.Rounded.Language,
                { choosingLanguage = true },
                Modifier.weight(1f).testTag("voice_reply_language").semantics {
                    contentDescription = if (state.translationMode) "Translation languages: $language" else "Reply language: $language"
                },
                canSelect
            )
        }
    }
    if (choosingLanguage && canSelect) {
        VoiceReplyLanguageDialog(
            state.replyLanguage, state.translationMode, actions.changeTranslationMode, select = {
                actions.changeReplyLanguage(it)
                choosingLanguage = false
            }, dismiss = { choosingLanguage = false }, profiles = state.replyLanguageProfiles,
            translationPair = state.translationPair,
            changeTranslationPair = actions.changeTranslationPair,
            translationModel = state.translationModel,
            installTranslationModel = actions.installTranslationModel,
            cancelTranslationModel = actions.cancelTranslationModel,
            translationSetupNotice = state.translationSetupNotice
        )
    } else if (choosingLanguage) {
        androidx.compose.runtime.LaunchedEffect(Unit) { choosingLanguage = false }
    }
}

@Composable
private fun VoiceControl(label: String, image: ImageVector, click: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = click, enabled = enabled, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(image, null, Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
