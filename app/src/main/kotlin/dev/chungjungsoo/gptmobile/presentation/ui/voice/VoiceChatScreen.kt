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
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.voice.AndroidVoiceAudio
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceSession
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel
import dev.chungjungsoo.gptmobile.presentation.ui.home.NewChatRevealPanel
import kotlin.math.abs
import kotlin.math.sin

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
                IconButton(onClick = back) { Icon(Icons.Rounded.Close, "Close voice chat") }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                VoiceWave(VoiceState(), Modifier.fillMaxWidth().height(180.dp))
                Text(if (state.enabled) "Let’s talk" else "A more natural conversation", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
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
    val approvals by model.pendingToolApprovals.collectAsStateWithLifecycle(emptyList())
    val inputs by model.pendingMcpInput.collectAsStateWithLifecycle()
    val consent by model.pendingFreeToolConsent.collectAsStateWithLifecycle()
    val providerNotice by model.freeProviderNotice.collectAsStateWithLifecycle()
    val notice by model.attachmentNotice.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf(VoiceState()) }
    var afterMessage by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val session = remember(model) {
        VoiceSession(AndroidVoiceAudio(context.applicationContext), submit = { text ->
            afterMessage = model.groupedMessages.value.assistantMessages.flatten().maxOfOrNull { it.id } ?: 0
            model.submitVoiceTranscript(text)
        }, changed = { state = it })
    }
    val enabled = features.isToolPluginEnabled(ToolPluginId.VOICE_CHAT)
    val currentEnabled by rememberUpdatedState(enabled)
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var started by rememberSaveable { mutableStateOf(false) }
    var resumeWhenReady by remember { mutableStateOf(false) }
    var captions by rememberSaveable { mutableStateOf(true) }
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
        if (reply != null && run != null && run.status in setOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.INTERRUPTED)) {
            session.reply(run.runId, reply.content, run.status != AgentRunStatus.COMPLETED)
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
                Text("Voice chat", style = MaterialTheme.typography.titleLarge)
                Text(profileName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { captions = !captions }) { Icon(Icons.Rounded.Subtitles, if (captions) "Hide captions" else "Show captions", tint = if (captions) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = ::openChat) { Icon(Icons.Rounded.ChatBubbleOutline, "Continue in text chat") }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            VoiceWave(
                state,
                Modifier.fillMaxWidth().height(210.dp).clickable(enabled = enabled && loaded, role = Role.Button, onClickLabel = if (state.phase == VoicePhase.SPEAKING) "Interrupt reply" else "Start or pause listening") {
                    if (state.phase == VoicePhase.LISTENING || (state.phase == VoicePhase.SPEAKING && state.muted)) {
                        session.pause()
                    } else if (state.phase != VoicePhase.THINKING && !state.muted) {
                        listen()
                    }
                }
            )
            AnimatedContent(status, label = "Voice status") { Text(it, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center) }
            Text(
                if (state.phase == VoicePhase.SPEAKING) {
                    "Tap the wave to interrupt"
                } else if (state.phase == VoicePhase.LISTENING) {
                    "Go ahead, I’m listening"
                } else if (state.muted) {
                    "Unmute when you’re ready to speak"
                } else {
                    "Tap the wave when you’re ready"
                },
                Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            if (captions && (state.transcript.isNotBlank() || state.reply.isNotBlank())) {
                Surface(Modifier.padding(top = 24.dp).widthIn(max = 560.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.transcript.isNotBlank()) Text(state.transcript, color = MaterialTheme.colorScheme.primary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        if (state.reply.isNotBlank()) Text(state.reply.take(12000), maxLines = 7, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            state.notice?.let { Text(it, Modifier.padding(top = 20.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            if (waitingApproval || state.phase == VoicePhase.ERROR || !enabled) TextButton(onClick = ::openChat) { Text("Continue in chat") }
        }
        Row(Modifier.padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            VoiceControl(if (state.muted) "Unmute" else "Mute", if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, { session.mute() }, state.muted)
            VoiceControl(if (state.speaker) "Speaker on" else "Speaker off", if (state.speaker) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, { session.speaker() }, !state.speaker)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(onClick = ::end, modifier = Modifier.size(64.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Icon(Icons.Rounded.Close, "End voice chat", Modifier.size(28.dp)) }
                Text("End", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        Text("Transcripts stay in your chat", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VoiceControl(label: String, image: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit, selected: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = click, modifier = Modifier.size(64.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) { Icon(image, label, Modifier.size(26.dp)) }
        Text(label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun VoiceWave(state: VoiceState, modifier: Modifier = Modifier) {
    val active = state.phase in setOf(VoicePhase.LISTENING, VoicePhase.SPEAKING, VoicePhase.THINKING)
    val animated = android.animation.ValueAnimator.areAnimatorsEnabled()
    val transition = rememberInfiniteTransition(label = "Voice wave")
    val phase by transition.animateFloat(0f, 6.2831855f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "Wave phase")
    val level by animateFloatAsState(
        if (state.phase == VoicePhase.LISTENING) {
            state.level
        } else if (active) {
            .45f
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
