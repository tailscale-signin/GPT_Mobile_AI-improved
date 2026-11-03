package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.containsLocalOnlyMemory
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import dev.chungjungsoo.gptmobile.data.voice.AndroidVoiceAudio
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceSession
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel
import dev.chungjungsoo.gptmobile.presentation.ui.home.NewChatRevealPanel
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay

@Composable
internal fun VoiceChatButton(onClick: (Offset) -> Unit) {
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    FloatingActionButton(
        onClick = { onClick(origin) },
        modifier = Modifier.size(60.dp).onGloballyPositioned { origin = it.localToRoot(Offset(it.size.width / 2f, it.size.height / 2f)) }.testTag("home_voice_chat_icon"),
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.primary
    ) { Icon(Icons.Rounded.GraphicEq, "Voice chat", Modifier.size(30.dp)) }
}

@Composable
internal fun VoiceEntryScreen(state: VoiceEntryState, origin: Offset, select: (String) -> Unit, start: () -> Unit, back: () -> Unit, settings: () -> Unit) {
    NewChatRevealPanel(origin) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Voice chat", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = back) { Icon(Icons.Rounded.Close, "Close voice chat", tint = MaterialTheme.colorScheme.onSurface) }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                VoiceWave(VoiceState(), Modifier.fillMaxWidth().height(180.dp))
                Text(if (state.enabled) "Let’s talk" else "A more natural conversation", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                Text("Speak, listen, and keep the conversation flowing.", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                if (!state.loaded || state.creating) {
                    CircularProgressIndicator()
                } else {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }, enabled = state.profiles.isNotEmpty()) {
                            Text(state.profiles.firstOrNull { it.uid == state.selected }?.let { it.name.ifBlank { it.model } } ?: "Choose an AI profile")
                        }
                        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                            state.profiles.forEach { profile ->
                                DropdownMenuItem(text = { Text(profile.name.ifBlank { profile.model }) }, onClick = {
                                    select(profile.uid)
                                    expanded = false
                                })
                            }
                        }
                    }
                    if (state.profiles.isEmpty()) {
                        Button(onClick = settings) { Text("Set up an AI profile") }
                    } else {
                        Button(onClick = start, modifier = Modifier.testTag("voice_enable_start")) { Text(if (state.enabled) "Start voice chat" else "Enable voice chat") }
                    }
                }
                state.error?.let { Text(it, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            }
            Text("Off by default. Microphone permission is requested when you start. Android speech services may process audio online. Transcripts are saved in your chat; the app does not save audio recordings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun VoiceChatScreen(onEnd: () -> Unit, onOpenChat: () -> Unit, model: ChatViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val loaded by model.isLoaded.collectAsStateWithLifecycle()
    val features by model.featureSettings.collectAsStateWithLifecycle()
    val messages by model.groupedMessages.collectAsStateWithLifecycle()
    val runs by model.agentRunsById.collectAsStateWithLifecycle()
    val profiles by model.platformsInApp.collectAsStateWithLifecycle()
    val privateSpeech = profiles.firstOrNull { it.uid in model.enabledPlatformsInChat }?.isPrivateDestination() == true ||
        (messages.userMessages + messages.assistantMessages.flatten()).any { it.containsLocalOnlyMemory() || it.timeline.any { item -> item.personalData.any { provenance -> provenance.localOnly } } }
    val approvals by model.pendingToolApprovals.collectAsStateWithLifecycle(emptyList())
    val inputs by model.pendingMcpInput.collectAsStateWithLifecycle()
    val consent by model.pendingFreeToolConsent.collectAsStateWithLifecycle()
    val providerNotice by model.freeProviderNotice.collectAsStateWithLifecycle()
    val notice by model.attachmentNotice.collectAsStateWithLifecycle()
    var priorPhase by remember { mutableStateOf(VoicePhase.PAUSED) }
    var afterMessage by rememberSaveable { mutableIntStateOf(Int.MAX_VALUE) }
    var retainedTranscript by rememberSaveable { mutableStateOf("") }
    var retainedReply by rememberSaveable { mutableStateOf("") }
    var retainedReview by rememberSaveable { mutableStateOf(false) }
    var retainedPending by rememberSaveable { mutableStateOf(false) }
    val audio = remember(model) { AndroidVoiceAudio(context.applicationContext) }
    val session = remember(model) {
        VoiceSession(audio, submit = { text ->
            afterMessage = model.groupedMessages.value.assistantMessages.flatten().maxOfOrNull { it.id } ?: 0
            model.submitVoiceTranscript(text)
        }, changed = {
            if (it.hasDraft || it.phase == VoicePhase.ERROR) model.checkpointVoiceDraft(it.transcript)
            if (it.phase == VoicePhase.THINKING && priorPhase != VoicePhase.THINKING && it.awaitingReply) model.checkpointVoiceDraft("")
            priorPhase = it.phase
            // Disposal closes audio; preserve the paused draft for rotation and text handoff.
            if (it.phase != VoicePhase.CLOSED) {
                retainedTranscript = it.transcript
                retainedReply = it.reply
                retainedReview = it.hasDraft
                retainedPending = it.awaitingReply
            }
        }).also { it.restore(retainedTranscript, retainedReply, retainedReview, retainedPending) }
    }
    val state by session.states.collectAsStateWithLifecycle()
    val enabled = features.isToolPluginEnabled(ToolPluginId.VOICE_CHAT)
    val currentEnabled by rememberUpdatedState(enabled)
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var started by rememberSaveable { mutableStateOf(false) }
    var resumeWhenReady by remember { mutableStateOf(false) }
    val preferences = remember { context.getSharedPreferences("voice_presentation", android.content.Context.MODE_PRIVATE) }
    var captions by rememberSaveable { mutableStateOf(preferences.getBoolean("captions", true)) }
    var settingsOpen by remember { mutableStateOf(false) }
    var transcriptOpen by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf(preferences.getString("language", java.util.Locale.getDefault().toLanguageTag()).orEmpty()) }
    var allowOnline by rememberSaveable { mutableStateOf(preferences.getBoolean("online", false)) }
    var voiceName by rememberSaveable { mutableStateOf(preferences.getString("voice", "").orEmpty()) }
    var editBeforeSend by rememberSaveable { mutableStateOf(preferences.getBoolean("review", false)) }
    var automatic by rememberSaveable { mutableStateOf(preferences.getBoolean("automatic", false)) }
    var rate by remember { androidx.compose.runtime.mutableFloatStateOf(preferences.getFloat("rate", 1f)) }
    var route by remember { mutableStateOf(audio.routeName()) }
    var routeOpen by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(0) }
    DisposableEffect(audio, session) {
        audio.routeChanged = {
            session.pause()
            route = audio.routeName()
        }
        onDispose { audio.routeChanged = null }
    }
    LaunchedEffect(state.retryAtMillis, lifecycleState) {
        while (state.retryAtMillis > 0 && lifecycleState == Lifecycle.State.RESUMED) {
            remaining = ((state.retryAtMillis - System.nanoTime() / 1_000_000 + 999) / 1000).coerceAtLeast(0).toInt()
            session.tick()
            delay(250)
        }
        remaining = 0
    }
    LaunchedEffect(state.transcript) { draft = state.transcript }
    LaunchedEffect(language, allowOnline, editBeforeSend, automatic, rate, privateSpeech, voiceName) {
        audio.languageTag = language
        if (privateSpeech && audio.allowOnlineSpeech) session.pause()
        audio.allowOnlineSpeech = allowOnline && !privateSpeech
        audio.selectedVoice = voiceName.takeIf(String::isNotBlank)
        audio.speechRate = rate
        session.editBeforeSend = editBeforeSend
        session.autoContinue = automatic
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = granted
        session.access(currentEnabled, granted, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        resumeWhenReady = granted
        if (!granted) session.fail("Microphone access is off. Allow it in Android app permissions to use voice chat.")
    }
    fun listen() {
        if (!enabled || !loaded) return
        permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        session.access(enabled, permission, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        if (permission) session.resume() else permissionRequest.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun end() {
        session.close()
        model.cancelActiveRuns()
        onEnd()
    }
    fun openChat() {
        session.close()
        onOpenChat()
    }
    BackHandler { end() }
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    session.access(currentEnabled, permission, true)
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> session.access(currentEnabled, permission, false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session.close()
        }
    }
    LaunchedEffect(enabled, loaded, lifecycleState, resumeWhenReady) {
        session.access(enabled, permission, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        if (enabled && loaded && lifecycleState == Lifecycle.State.RESUMED && (!started || resumeWhenReady)) {
            started = true
            resumeWhenReady = false
            listen()
        }
    }
    LaunchedEffect(messages, runs) {
        val reply = messages.assistantMessages.flatten().filter { it.id > afterMessage }.maxByOrNull { it.id }
        val run = reply?.currentRunId?.let(runs::get)
        if (reply != null && run != null && run.status !in setOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.INTERRUPTED)) session.streamReply(run.runId, dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject.withoutMetadata(reply.content))
        if (reply != null && run != null && run.status in setOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.INTERRUPTED)) {
            session.reply(run.runId, dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject.withoutMetadata(reply.content), run.status != AgentRunStatus.COMPLETED)
        }
    }
    LaunchedEffect(providerNotice, notice) {
        if (state.phase == VoicePhase.THINKING) (providerNotice ?: notice)?.let(session::fail)
    }
    val waitingApproval = approvals.isNotEmpty() || inputs.isNotEmpty() || consent != null
    val profileName = profiles.firstOrNull { it.uid in model.enabledPlatformsInChat }?.let { it.name.ifBlank { it.model } } ?: "Your AI profile"
    val status = when {
        !enabled -> "Voice chat is turned off"
        !loaded -> "Getting ready"
        waitingApproval -> "Your attention is needed"
        remaining > 0 -> "Retry in $remaining seconds"
        state.phase == VoicePhase.STARTING -> "Starting microphone"
        state.phase == VoicePhase.FINALIZING -> "Finishing your words"
        state.phase == VoicePhase.REVIEWING -> "Review your message"
        state.phase == VoicePhase.THINKING -> "Thinking"
        state.phase == VoicePhase.SPEAKING -> "Speaking"
        state.muted -> "Microphone muted"
        state.phase == VoicePhase.LISTENING -> "Listening"
        state.phase == VoicePhase.ERROR -> "Let’s try again"
        else -> "Ready when you are"
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp).testTag("voice_chat_screen"), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Voice chat", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(profileName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { transcriptOpen = true }) { Icon(Icons.Rounded.Subtitles, "Open transcript", tint = if (captions) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = {
                if (state.hasDraft) model.checkpointVoiceDraft(draft)
                session.pause()
                settingsOpen = true
            }) { Icon(Icons.Rounded.GraphicEq, "Voice settings", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = ::openChat) { Icon(Icons.Rounded.ChatBubbleOutline, "Continue in text chat", tint = MaterialTheme.colorScheme.onSurface) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            VoiceWave(
                state,
                Modifier.fillMaxWidth().height(160.dp).clearAndSetSemantics { }
            )
            AnimatedContent(status, label = "Voice status") {
                Text(it, modifier = Modifier.semantics { if (remaining == 0) liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            }
            Text(
                if (state.phase == VoicePhase.SPEAKING) {
                    "Tap Interrupt to speak"
                } else if (state.phase == VoicePhase.LISTENING) {
                    "Go ahead, I’m listening"
                } else if (state.muted) {
                    "Unmute when you’re ready to speak"
                } else {
                    "Use the microphone button when you are ready"
                },
                Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            if (captions && (state.transcript.isNotBlank() || state.reply.isNotBlank())) {
                Surface(Modifier.padding(top = 24.dp).widthIn(max = 560.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.transcript.isNotBlank()) Text(state.transcript, color = MaterialTheme.colorScheme.primary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        if (state.reply.isNotBlank()) Text(state.reply.take(12000), color = MaterialTheme.colorScheme.onSurface, maxLines = 7, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (state.phase == VoicePhase.REVIEWING) {
                OutlinedTextField(draft, {
                    draft = it.take(8000)
                    session.editDraft(draft)
                }, label = { Text("Your message") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), minLines = 2, maxLines = 4)
                Button(onClick = { session.sendDraft(draft) }, enabled = draft.isNotBlank()) { Text("Send") }
                TextButton(onClick = {
                    session.discardDraft()
                    listen()
                }) { Text("Record again") }
            }
            state.notice?.let { Text(it, Modifier.padding(top = 16.dp).widthIn(max = 320.dp), style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.sp), color = if (remaining > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            if (state.phase !in setOf(VoicePhase.THINKING, VoicePhase.REVIEWING, VoicePhase.CLOSED)) {
                Button(onClick = {
                    if (state.phase in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING)) {
                        session.pause()
                    } else if (state.phase == VoicePhase.SPEAKING) {
                        model.cancelActiveRuns()
                        session.interrupt()
                    } else {
                        listen()
                    }
                }, enabled = enabled && loaded && !state.muted && remaining == 0) {
                    Text(
                        if (remaining > 0) {
                            "Retry in ${remaining}s"
                        } else {
                            when (state.phase) {
                                VoicePhase.SPEAKING -> "Interrupt"
                                VoicePhase.LISTENING, VoicePhase.STARTING, VoicePhase.FINALIZING -> "Pause microphone"
                                VoicePhase.ERROR -> if (state.hasDraft) "Review message" else "Retry microphone"
                                else -> "Resume microphone"
                            }
                        }
                    )
                }
            }
            if (state.reply.isNotBlank() && state.phase in setOf(VoicePhase.PAUSED, VoicePhase.ERROR)) TextButton(onClick = session::replay) { Text("Replay reply") }
            if (waitingApproval || state.phase == VoicePhase.ERROR || !enabled) TextButton(onClick = ::openChat) { Text("Continue in chat") }
        }
        Row(Modifier.padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            VoiceControl(if (state.muted) "Unmute" else "Mute", if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, { session.mute() }, state.muted)
            VoiceControl("Audio", if (state.speaker) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, {
                session.pause()
                routeOpen = true
            }, !state.speaker)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(onClick = ::end, modifier = Modifier.size(64.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Icon(Icons.Rounded.Close, "End voice chat", Modifier.size(28.dp)) }
                Text("End", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Text("${if (allowOnline && !privateSpeech) "System speech may use the network" else "On device speech only"} · $route", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (settingsOpen) {
        dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("Voice settings", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(language, { language = it.take(35) }, label = { Text("Language tag") }, supportingText = { Text("For example en-CA or fr-CA") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Allow online speech", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                        Switch(allowOnline && !privateSpeech, { allowOnline = it }, enabled = !privateSpeech)
                    }
                    Text(if (privateSpeech) "This private chat requires on device speech. Change the chat destination explicitly to use online speech." else "Off uses on device recognition and an offline TTS voice. No silent network fallback.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Review before sending", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                        Switch(editBeforeSend, { editBeforeSend = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Continue after each reply", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                        Switch(automatic, { automatic = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Live captions", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                        Switch(captions, { captions = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Spoken replies", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                        Switch(state.speaker, { session.speaker() })
                    }
                    val voices = audio.voices()
                    Text("Voice", color = MaterialTheme.colorScheme.onSurface)
                    if (voices.isEmpty()) Text("System voice · installed voices appear after the first spoken reply", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    voices.forEach { (name, label) -> TextButton(onClick = { voiceName = name }) { Text((if (voiceName == name) "Selected · " else "") + label) } }
                    Text("Speech rate", color = MaterialTheme.colorScheme.onSurface)
                    Slider(rate, { rate = it }, valueRange = .5f..2f)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    preferences.edit().putString("language", language).putBoolean("online", allowOnline).putBoolean("captions", captions).putBoolean("review", editBeforeSend).putBoolean("automatic", automatic).putFloat("rate", rate).putString("voice", voiceName).apply()
                    settingsOpen = false
                }) { Text("Done") }
            }
        )
    }
    if (transcriptOpen) {
        dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog(onDismissRequest = { transcriptOpen = false }, title = { Text("Transcript", color = MaterialTheme.colorScheme.onSurface) }, text = {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.transcript.ifBlank { "No speech captured yet" }, color = MaterialTheme.colorScheme.primary)
                    Text(state.reply, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }, confirmButton = { TextButton(onClick = { transcriptOpen = false }) { Text("Done") } }, dismissButton = { TextButton(onClick = ::openChat) { Text("Full chat") } })
    }
    if (routeOpen) {
        dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog(onDismissRequest = { routeOpen = false }, title = { Text("Audio output", color = MaterialTheme.colorScheme.onSurface) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(route, color = MaterialTheme.colorScheme.onSurfaceVariant)
                audio.routes().forEach { (id, name) ->
                    TextButton(onClick = {
                        if (audio.selectRoute(id)) route = audio.routeName()
                        routeOpen = false
                    }) { Text(name) }
                }
                TextButton(onClick = { session.speaker() }) { Text(if (state.speaker) "Turn spoken replies off" else "Turn spoken replies on") }
                TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)) }) { Text("Android sound settings") }
            }
        }, confirmButton = { TextButton(onClick = { routeOpen = false }) { Text("Done") } })
    }
}

@Composable
private fun VoiceControl(label: String, image: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit, selected: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = click, modifier = Modifier.size(64.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)) { Icon(image, label, Modifier.size(26.dp)) }
        Text(label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun VoiceWave(state: VoiceState, modifier: Modifier = Modifier) {
    val active = state.phase in setOf(VoicePhase.LISTENING, VoicePhase.SPEAKING, VoicePhase.THINKING)
    val animated = android.animation.ValueAnimator.areAnimatorsEnabled()
    val transition = rememberInfiniteTransition(label = "Voice wave")
    val phase = if (active && animated) transition.animateFloat(0f, 6.2831855f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "Wave phase").value else 0f
    val level by animateFloatAsState(
        if (state.phase == VoicePhase.LISTENING) {
            if (state.hasAudioLevel) state.level else .08f
        } else if (state.phase == VoicePhase.SPEAKING) {
            .30f
        } else if (active) {
            .06f
        } else {
            0f
        },
        tween(140),
        label = "Microphone level"
    )
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Canvas(modifier.semantics { contentDescription = "Voice activity" }) {
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(Brush.radialGradient(listOf(primary.copy(alpha = .09f), primary.copy(alpha = 0f)), center, size.width * .48f), size.width * .48f, center)
        val count = 33
        val gap = size.width * .78f / count
        val width = gap * .53f
        val time = if (active && animated) phase else 0f
        repeat(count) { index ->
            val envelope = sin(Math.PI * (index + 1) / (count + 1)).toFloat()
            val pulse = .35f + .65f * abs(sin(time + index * .48f))
            val height = 7.dp.toPx() + size.height * .58f * level * envelope * pulse
            val x = size.width * .11f + index * gap
            drawRoundRect(Brush.verticalGradient(listOf(primary, secondary)), Offset(x, center.y - height / 2), Size(width, height), CornerRadius(width / 2), alpha = if (active) 1f else .45f)
        }
    }
}
