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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalLocale
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
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.containsLocalOnlyMemory
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import dev.chungjungsoo.gptmobile.data.voice.AndroidVoiceAudio
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStore
import dev.chungjungsoo.gptmobile.data.voice.MoonshineVoiceRecognizer
import dev.chungjungsoo.gptmobile.data.voice.SpeechVoiceCatalogState
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoicePluginAudio
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecoveryAction
import dev.chungjungsoo.gptmobile.data.voice.VoiceSession
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import dev.chungjungsoo.gptmobile.data.voice.createKokoroVoiceOutput
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
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
                        Button(onClick = start, modifier = Modifier.testTag("voice_enable_start")) { Text(if (state.enabled) "Start voice chat" else "Enable & start") }
                    }
                }
                state.error?.let { Text(it, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            }
            Text("Download a local speech model for voice recognition on this phone, or choose Android speech in Voice settings. Your selected AI profile receives the transcript. Microphone permission is requested when you start; the app does not save audio recordings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun VoiceChatScreen(onEnd: () -> Unit, onOpenChat: () -> Unit, model: ChatViewModel = hiltViewModel(), voiceSettings: VoicePreferencesViewModel = hiltViewModel()) {
    val roomError by model.roomLoadError.collectAsStateWithLifecycle()
    if (roomError != null) {
        BackHandler(onBack = onEnd)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Conversation unavailable", style = MaterialTheme.typography.headlineSmall)
            Text(roomError.orEmpty(), Modifier.padding(vertical = 16.dp), textAlign = TextAlign.Center)
            Button(onClick = onEnd, modifier = Modifier.testTag("voice_missing_room_back")) { Text("Back to chats") }
        }
        return
    }
    val modelStores by voiceSettings.modelStores.collectAsStateWithLifecycle()
    val stores = modelStores
    if (stores == null) {
        BackHandler(onBack = onEnd)
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    VoiceChatSessionScreen(onEnd, onOpenChat, model, voiceSettings, stores)
}

@Composable
private fun VoiceChatSessionScreen(
    onEnd: () -> Unit,
    onOpenChat: () -> Unit,
    model: ChatViewModel,
    voiceSettings: VoicePreferencesViewModel,
    stores: VoiceModelStores
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val loaded by model.isLoaded.collectAsStateWithLifecycle()
    val roomReady by model.voiceRoomReady.collectAsStateWithLifecycle()
    val admission by model.voiceAdmission.collectAsStateWithLifecycle()
    val saveError by voiceSettings.saveError.collectAsStateWithLifecycle()
    val features by model.featureSettings.collectAsStateWithLifecycle()
    val messages by model.groupedMessages.collectAsStateWithLifecycle()
    val runs by model.agentRunsById.collectAsStateWithLifecycle()
    val profiles by model.platformsInApp.collectAsStateWithLifecycle()
    val activeProfiles by model.activePlatformUids.collectAsStateWithLifecycle()
    val selectedProfile = activeProfiles.singleOrNull()?.let { uid -> profiles.singleOrNull { it.uid == uid && it.enabled } }
    val voiceReady = loaded && roomReady && selectedProfile != null
    val privateSpeech = remember(voiceReady, selectedProfile, messages) {
        !voiceReady ||
            selectedProfile?.isPrivateDestination() != false ||
            (messages.userMessages.asSequence() + messages.assistantMessages.asSequence().flatten()).any {
                it.containsLocalOnlyMemory() || it.timeline.any { item -> item.personalData.any { provenance -> provenance.localOnly } }
            }
    }
    val approvals by model.pendingToolApprovals.collectAsStateWithLifecycle(emptyList())
    val inputs by model.pendingMcpInput.collectAsStateWithLifecycle()
    val consent by model.pendingFreeToolConsent.collectAsStateWithLifecycle()
    var retainedTranscript by rememberSaveable { mutableStateOf("") }
    var retainedReply by rememberSaveable { mutableStateOf("") }
    var retainedReview by rememberSaveable { mutableStateOf(false) }
    var retainedPending by rememberSaveable { mutableStateOf(false) }
    var retainedMuted by rememberSaveable { mutableStateOf(false) }
    val systemAudio = remember(model) { AndroidVoiceAudio(context.applicationContext) }
    val localRecognition = remember(model, stores) { MoonshineVoiceRecognizer(context.applicationContext, stores.moonshine) }
    val kokoroOutput = remember(systemAudio, stores) {
        createKokoroVoiceOutput(context.applicationContext, stores.kokoro, systemAudio::playbackAttributes) { systemAudio.speechRate }
    }
    val audio = remember(systemAudio, localRecognition, kokoroOutput) {
        VoicePluginAudio(systemAudio, localRecognition, kokoroOutput, systemAudio::speakOffline, systemAudio::releaseRecognition)
    }
    val localModel by stores.moonshine.state.collectAsStateWithLifecycle()
    val kokoroModel by stores.kokoro.state.collectAsStateWithLifecycle()
    val session = remember(model, audio) {
        VoiceSession(audio, submit = model::submitVoiceTranscript, awaitsAdmission = true, changed = {
            if (it.hasDraft) model.checkpointVoiceDraft(it.transcript)
            // Disposal closes audio; preserve the paused draft for rotation and text handoff.
            if (it.phase != VoicePhase.CLOSED) {
                retainedTranscript = it.transcript
                retainedReply = it.reply
                retainedReview = it.hasDraft
                retainedPending = it.awaitingReply
                retainedMuted = it.muted
            }
        }).also {
            val wasMuted = retainedMuted
            it.restore(retainedTranscript, retainedReply, retainedReview, retainedPending)
            if (wasMuted) it.mute()
        }
    }
    val state by session.states.collectAsStateWithLifecycle()
    val enabled = features.isToolPluginEnabled(ToolPluginId.VOICE_CHAT)
    val savedPreferences by voiceSettings.preferences.collectAsStateWithLifecycle()
    var preferencesApplied by remember { mutableStateOf(false) }
    val currentEnabled by rememberUpdatedState(enabled && preferencesApplied && voiceReady)
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var started by remember { mutableStateOf(false) }
    var resumeWhenReady by remember { mutableStateOf(false) }
    var captions by rememberSaveable { mutableStateOf(true) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var transcriptOpen by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf(java.util.Locale.getDefault().toLanguageTag()) }
    var recognitionBackend by rememberSaveable { mutableStateOf(VoiceRecognitionBackend.LOCAL_MOONSHINE) }
    var allowOnline by rememberSaveable { mutableStateOf(false) }
    var voiceName by rememberSaveable { mutableStateOf("") }
    var kokoroVoice by rememberSaveable { mutableStateOf<KokoroVoice?>(null) }
    var kokoroThreads by rememberSaveable { mutableIntStateOf(2) }
    var editBeforeSend by rememberSaveable { mutableStateOf(false) }
    var automatic by rememberSaveable { mutableStateOf(true) }
    var spokenReplies by rememberSaveable { mutableStateOf(true) }
    var rate by rememberSaveable { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var preferencesInitialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(savedPreferences) {
        val saved = savedPreferences ?: return@LaunchedEffect
        if (!preferencesApplied) {
            if (!preferencesInitialized) {
                language = saved.language
                recognitionBackend = saved.recognitionBackend
                allowOnline = saved.online
                captions = saved.captions
                editBeforeSend = saved.review
                automatic = saved.automatic
                spokenReplies = saved.spokenReplies
                rate = saved.rate
                voiceName = saved.voice
                kokoroVoice = saved.kokoroVoice
                kokoroThreads = saved.kokoroThreads
                preferencesInitialized = true
            }
            audio.recognitionBackend = recognitionBackend
            localRecognition.languageTag = language
            audio.kokoroVoice = kokoroVoice
            kokoroOutput?.configureThreads(kokoroThreads)
            if (session.states.value.speaker != spokenReplies) session.speaker()
            // Configure before permitting the first listen, including after restoring the screen.
            systemAudio.languageTag = language
            systemAudio.allowOnlineSpeech = allowOnline && !privateSpeech
            systemAudio.selectedVoice = voiceName.takeIf(String::isNotBlank)
            systemAudio.speechRate = rate
            session.editBeforeSend = editBeforeSend
            session.autoContinue = automatic
            preferencesApplied = true
        }
    }
    val recognitionReady = recognitionBackend == VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE ||
        localModel.status == LocalVoiceModelStatus.READY &&
        LocalVoiceModelStore.supportsLanguage(language)
    fun applyAudioConfiguration() {
        val onlineAllowed = allowOnline && !privateSpeech
        if (
            audio.recognitionBackend != recognitionBackend ||
            localRecognition.languageTag != language ||
            systemAudio.languageTag != language ||
            systemAudio.allowOnlineSpeech != onlineAllowed
        ) {
            session.recognitionConfigurationChanged()
        }
        audio.recognitionBackend = recognitionBackend
        localRecognition.languageTag = language
        systemAudio.languageTag = language
        systemAudio.allowOnlineSpeech = onlineAllowed
        systemAudio.selectedVoice = voiceName.takeIf(String::isNotBlank)
        systemAudio.speechRate = rate
        audio.kokoroVoice = kokoroVoice
        kokoroOutput?.configureThreads(kokoroThreads)
        session.editBeforeSend = editBeforeSend
        session.autoContinue = automatic
    }
    SideEffect {
        if (preferencesApplied) applyAudioConfiguration()
    }
    var route by remember { mutableStateOf(systemAudio.routeName()) }
    var routeOpen by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(0) }
    DisposableEffect(audio, session) {
        systemAudio.routeChanged = {
            session.pause()
            route = systemAudio.routeName()
        }
        onDispose { systemAudio.routeChanged = null }
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
    LaunchedEffect(settingsOpen, preferencesApplied, kokoroVoice != null) {
        if (settingsOpen || preferencesApplied && kokoroVoice != null && kokoroModel.status == LocalVoiceModelStatus.CHECKING) stores.kokoro.refresh()
    }
    LaunchedEffect(preferencesApplied, enabled, voiceReady, spokenReplies, language, kokoroVoice, kokoroThreads, kokoroModel.status, lifecycleState, state.phase, state.awaitingReply, admission?.stage) {
        if (!preferencesApplied) return@LaunchedEffect
        if (enabled && voiceReady && spokenReplies && LocalVoiceModelStore.supportsLanguage(language) && lifecycleState == Lifecycle.State.RESUMED) {
            // Opening the route only starts recognition. Native warmup begins after decoder release,
            // while an accepted reply is generating; the worker gate also fences preview/retry races.
            if (state.phase == VoicePhase.THINKING && state.awaitingReply && admission?.stage == dev.chungjungsoo.gptmobile.data.voice.VoiceAdmissionStage.ACCEPTED) kokoroVoice?.let { kokoroOutput?.prewarm(it) }
        } else {
            kokoroOutput?.releaseWarmModel()
        }
    }
    LaunchedEffect(settingsOpen, routeOpen, transcriptOpen, language, allowOnline, privateSpeech, recognitionBackend, recognitionReady, preferencesApplied) {
        if (!preferencesApplied) return@LaunchedEffect
        // Apply privacy and engine changes before a retained recognizer can start again.
        applyAudioConfiguration()
        // Do not bind Android TTS while the voice route is opening. Speech initializes on first
        // playback; the catalog is only needed while its settings are visible.
        if (settingsOpen) systemAudio.prepareVoices()
        if (automatic && voiceReady && recognitionReady && !settingsOpen && !routeOpen && !transcriptOpen && started && !state.muted && state.recoveryAction == null && lifecycleState == Lifecycle.State.RESUMED) {
            if (state.phase == VoicePhase.PAUSED) {
                audio.stop()
                session.resume()
            }
        }
    }
    LaunchedEffect(language, allowOnline, editBeforeSend, automatic, rate, privateSpeech, voiceName, recognitionBackend, kokoroVoice, preferencesApplied) {
        if (!preferencesApplied) return@LaunchedEffect
        applyAudioConfiguration()
        if (settingsOpen) systemAudio.prepareVoices()
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = granted
        session.access(currentEnabled, granted, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        resumeWhenReady = granted
        if (!granted) session.permissionDenied()
    }
    fun listen() {
        if (!enabled || !voiceReady || !preferencesApplied || !recognitionReady && !state.hasDraft) return
        applyAudioConfiguration()
        permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        session.access(enabled && preferencesApplied && voiceReady, permission, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        if (permission) session.resume() else permissionRequest.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun savePreferences() {
        voiceSettings.save(VoicePreferences(language, allowOnline, captions, editBeforeSend, automatic, rate, voiceName, recognitionBackend, kokoroVoice, spokenReplies, kokoroThreads))
    }
    fun setSpokenReplies(value: Boolean) {
        spokenReplies = value
        if (session.states.value.speaker != value) session.speaker()
        savePreferences()
    }
    fun selectRecognitionBackend(backend: VoiceRecognitionBackend) {
        if (backend == recognitionBackend) return
        session.recognitionConfigurationChanged()
        recognitionBackend = backend
        audio.recognitionBackend = backend
        savePreferences()
    }
    fun end() {
        if (preferencesApplied) savePreferences()
        session.close()
        model.cancelActiveRuns()
        onEnd()
    }
    fun openChat() {
        if (preferencesApplied) savePreferences()
        session.close()
        onOpenChat()
    }
    fun closeSettings() {
        audio.stop()
        savePreferences()
        settingsOpen = false
    }
    BackHandler { if (settingsOpen) closeSettings() else end() }
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    session.access(currentEnabled, permission, true)
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    session.access(currentEnabled, permission, false)
                    systemAudio.releaseRecognition()
                    kokoroOutput?.releaseWarmModel()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session.close()
        }
    }
    LaunchedEffect(enabled, voiceReady, preferencesApplied, lifecycleState, resumeWhenReady, recognitionReady, recognitionBackend, settingsOpen) {
        session.access(enabled && preferencesApplied && voiceReady, permission, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        if (enabled && voiceReady && preferencesApplied && recognitionReady && !settingsOpen && !routeOpen && !transcriptOpen && state.recoveryAction == null && lifecycleState == Lifecycle.State.RESUMED && (!started || resumeWhenReady || automatic && !state.muted && state.phase == VoicePhase.PAUSED)) {
            started = true
            resumeWhenReady = false
            listen()
        }
    }
    LaunchedEffect(admission, messages, runs) {
        val accepted = admission ?: return@LaunchedEffect
        session.admission(accepted.stage, accepted.reason)
        val (run, reply) = accepted.reply(messages.assistantMessages.flatten(), runs) ?: return@LaunchedEffect
        val text = dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject.withoutMetadata(reply.content)
        if (run.status in AgentRunStatus.terminal) {
            session.reply(run.runId, text, run.status != AgentRunStatus.COMPLETED)
        } else {
            session.streamReply(run.runId, text)
        }
    }
    val waitingApproval = approvals.isNotEmpty() || inputs.isNotEmpty() || consent != null
    val profileName = profiles.firstOrNull { it.uid in activeProfiles }?.let { it.name.ifBlank { it.model } } ?: "Your AI profile"
    val status = when {
        !enabled -> "Voice chat is turned off"
        !voiceReady || !preferencesApplied -> "Getting ready"
        waitingApproval -> "Your attention is needed"
        state.phase == VoicePhase.REVIEWING -> "Review your message"
        !recognitionReady -> "Set up local speech"
        remaining > 0 -> "Retry in $remaining seconds"
        state.recoveryAction != null -> "Speech setup needs attention"
        state.phase == VoicePhase.STARTING -> "Starting microphone"
        state.phase == VoicePhase.FINALIZING -> "Finishing your words"
        state.phase == VoicePhase.THINKING && admission?.stage == dev.chungjungsoo.gptmobile.data.voice.VoiceAdmissionStage.CHECKING -> "Checking your AI profile"
        state.phase == VoicePhase.THINKING && admission?.stage == dev.chungjungsoo.gptmobile.data.voice.VoiceAdmissionStage.QUEUED -> "Message queued"
        state.phase == VoicePhase.THINKING -> "Thinking"
        state.phase == VoicePhase.SPEAKING -> "Speaking"
        state.muted -> "Microphone muted"
        state.phase == VoicePhase.LISTENING -> "Listening"
        state.phase == VoicePhase.ERROR -> "Let’s try again"
        else -> "Ready when you are"
    }
    if (settingsOpen) {
        VoiceSettingsScreen(
            state = VoiceSettingsUiState(
                profiles = profiles,
                activeProfiles = activeProfiles,
                session = state,
                saveError = saveError,
                recognitionBackend = recognitionBackend,
                localModel = localModel,
                language = language,
                allowOnline = allowOnline,
                privateSpeech = privateSpeech,
                kokoroVoice = kokoroVoice,
                editBeforeSend = editBeforeSend,
                captions = captions,
                spokenReplies = spokenReplies,
                automatic = automatic,
                kokoroModel = kokoroModel,
                kokoroThreads = kokoroThreads,
                voiceName = voiceName,
                rate = rate
            ),
            systemAudio = systemAudio,
            actions = VoiceSettingsActions(
                done = ::closeSettings,
                selectProfile = { uid ->
                    session.pause()
                    model.selectVoiceProfile(uid) { changed -> if (changed) closeSettings() }
                },
                selectRecognitionBackend = ::selectRecognitionBackend,
                installLocalModel = voiceSettings::installLocalModel,
                cancelLocalModelInstall = voiceSettings::cancelLocalModelInstall,
                removeLocalModel = {
                    session.pause()
                    voiceSettings.removeLocalModel()
                },
                changeLanguage = {
                    session.recognitionConfigurationChanged()
                    language = it.take(35)
                    savePreferences()
                },
                changeAllowOnline = {
                    session.recognitionConfigurationChanged()
                    allowOnline = it
                    savePreferences()
                },
                changeReview = {
                    editBeforeSend = it
                    savePreferences()
                },
                changeCaptions = {
                    captions = it
                    savePreferences()
                },
                changeSpokenReplies = ::setSpokenReplies,
                changeAutomatic = {
                    automatic = it
                    savePreferences()
                },
                selectKokoroVoice = {
                    kokoroVoice = it
                    audio.kokoroVoice = it
                    savePreferences()
                },
                selectKokoroThreads = {
                    audio.stop()
                    kokoroThreads = it
                    kokoroOutput?.configureThreads(it)
                    savePreferences()
                },
                installKokoroModel = voiceSettings::installKokoroModel,
                cancelKokoroInstall = voiceSettings::cancelKokoroInstall,
                removeKokoroModel = {
                    audio.stop()
                    kokoroOutput?.releaseWarmModel()
                    voiceSettings.removeKokoroModel()
                },
                previewKokoro = { selected, report ->
                    audio.stop()
                    if (kokoroOutput == null) {
                        report("Kokoro playback is unavailable on this device.")
                    } else {
                        report(null)
                        kokoroOutput.speak("Hello. This is your Kokoro voice. I am ready when you are.", selected, {}, { message, _ -> report(message) })
                    }
                },
                selectAndroidVoice = {
                    voiceName = it
                    savePreferences()
                },
                previewAndroidVoice = {
                    systemAudio.selectedVoice = it
                    audio.speak("Hello. I am ready to help. We can talk naturally, at your pace.", {}, { })
                },
                changeRate = {
                    rate = it
                    savePreferences()
                }
            )
        )
        return
    }
    VoiceConversationScreen(
        state = VoiceConversationUiState(
            profileName = profileName,
            recognitionBackend = recognitionBackend,
            captions = captions,
            session = state,
            status = status,
            remaining = remaining,
            localModel = localModel,
            language = language,
            recognitionReady = recognitionReady,
            enabled = enabled,
            voiceReady = voiceReady,
            preferencesApplied = preferencesApplied,
            waitingApproval = waitingApproval,
            draft = draft
        ),
        actions = VoiceConversationActions(
            openTranscript = { transcriptOpen = true },
            openSettings = {
                if (state.hasDraft) model.checkpointVoiceDraft(draft)
                session.pause()
                settingsOpen = true
            },
            showSettings = { settingsOpen = true },
            installLocalModel = voiceSettings::installLocalModel,
            cancelLocalModelInstall = voiceSettings::cancelLocalModelInstall,
            selectRecognitionBackend = ::selectRecognitionBackend,
            changeDraft = {
                draft = it.take(8000)
                session.editDraft(draft)
            },
            sendDraft = { session.sendDraft(draft) },
            listen = ::listen,
            recordAgain = {
                session.discardDraft()
                listen()
            },
            repair = { action ->
                session.pause()
                if (action == VoiceRecoveryAction.LOCAL_MODEL) settingsOpen = true else openVoiceRepairSettings(context, action)
            },
            openVoiceConfiguration = {
                session.pause()
                settingsOpen = true
            },
            replay = session::replay,
            openChat = ::openChat,
            toggleMicrophone = {
                if (state.muted) {
                    session.mute()
                    listen()
                } else if (state.phase in setOf(VoicePhase.PAUSED, VoicePhase.ERROR)) {
                    listen()
                } else {
                    session.mute()
                }
            },
            openAudioRoute = {
                session.pause()
                routeOpen = true
            },
            end = ::end
        )
    )
    if (transcriptOpen) {
        VoiceTranscriptDialog(state.transcript, state.reply, { transcriptOpen = false }, ::openChat)
    }
    if (routeOpen) {
        VoiceRouteDialog(
            route = route,
            routes = systemAudio.routes(),
            speaker = state.speaker,
            dismiss = { routeOpen = false },
            selectRoute = { id ->
                if (systemAudio.selectRoute(id)) route = systemAudio.routeName()
                routeOpen = false
            },
            toggleSpokenReplies = { setSpokenReplies(!spokenReplies) }
        )
    }
}

private data class VoiceSettingsUiState(
    val profiles: List<PlatformV2>,
    val activeProfiles: List<String>,
    val session: VoiceState,
    val saveError: String?,
    val recognitionBackend: VoiceRecognitionBackend,
    val localModel: LocalVoiceModelState,
    val language: String,
    val allowOnline: Boolean,
    val privateSpeech: Boolean,
    val kokoroVoice: KokoroVoice?,
    val editBeforeSend: Boolean,
    val captions: Boolean,
    val spokenReplies: Boolean,
    val automatic: Boolean,
    val kokoroModel: LocalVoiceModelState,
    val kokoroThreads: Int,
    val voiceName: String,
    val rate: Float
)

private class VoiceSettingsActions(
    val done: () -> Unit,
    val selectProfile: (String) -> Unit,
    val selectRecognitionBackend: (VoiceRecognitionBackend) -> Unit,
    val installLocalModel: () -> Unit,
    val cancelLocalModelInstall: () -> Unit,
    val removeLocalModel: () -> Unit,
    val changeLanguage: (String) -> Unit,
    val changeAllowOnline: (Boolean) -> Unit,
    val changeReview: (Boolean) -> Unit,
    val changeCaptions: (Boolean) -> Unit,
    val changeSpokenReplies: (Boolean) -> Unit,
    val changeAutomatic: (Boolean) -> Unit,
    val selectKokoroVoice: (KokoroVoice?) -> Unit,
    val selectKokoroThreads: (Int) -> Unit,
    val installKokoroModel: () -> Unit,
    val cancelKokoroInstall: () -> Unit,
    val removeKokoroModel: () -> Unit,
    val previewKokoro: (KokoroVoice, (String?) -> Unit) -> Unit,
    val selectAndroidVoice: (String) -> Unit,
    val previewAndroidVoice: (String) -> Unit,
    val changeRate: (Float) -> Unit
)

@Composable
private fun VoiceSettingsScreen(
    state: VoiceSettingsUiState,
    systemAudio: AndroidVoiceAudio,
    actions: VoiceSettingsActions
) {
    val context = LocalContext.current
    var previewError by remember { mutableStateOf<String?>(null) }
    VoiceSettingsPage(actions.done) {
        Text("AI profile", style = MaterialTheme.typography.titleSmall)
        state.profiles.filter { it.enabled }.forEach { profile ->
            TextButton(
                onClick = { actions.selectProfile(profile.uid) },
                enabled = !state.session.awaitingReply
            ) {
                Text((if (profile.uid in state.activeProfiles) "Selected · " else "") + profile.name)
            }
        }
        state.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        VoiceSettingsSection("Speech recognition")
        VoiceRecognitionBackend.entries.forEach { backend ->
            TextButton(onClick = { actions.selectRecognitionBackend(backend) }) {
                Text((if (state.recognitionBackend == backend) "Selected · " else "") + backend.title)
            }
        }
        if (state.recognitionBackend == VoiceRecognitionBackend.LOCAL_MOONSHINE) {
            LocalVoiceSetupCard(
                state.localModel,
                actions.installLocalModel,
                actions.cancelLocalModelInstall,
                actions.removeLocalModel,
                showRemove = true
            )
        }
        OutlinedTextField(
            state.language,
            actions.changeLanguage,
            label = { Text("Language tag") },
            supportingText = {
                Text(
                    if (state.recognitionBackend == VoiceRecognitionBackend.LOCAL_MOONSHINE) {
                        "This local model supports English: en-CA, en-US or en-GB."
                    } else {
                        "For example en-CA or fr-CA"
                    }
                )
            }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Allow online Android speech", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            val androidSpeech = state.kokoroVoice == null || state.recognitionBackend == VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE
            Switch(
                state.allowOnline && !state.privateSpeech && androidSpeech,
                actions.changeAllowOnline,
                enabled = !state.privateSpeech && androidSpeech
            )
        }
        Text(
            voicePrivacyDescription(state),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        VoiceSettingsSection("Conversation")
        VoiceSettingSwitch("Review before sending", state.editBeforeSend, actions.changeReview)
        VoiceSettingSwitch("Live captions", state.captions, actions.changeCaptions)
        VoiceSettingSwitch("Spoken replies", state.spokenReplies, actions.changeSpokenReplies)
        VoiceSettingSwitch("Listen again after replies", state.automatic, actions.changeAutomatic)
        VoiceSettingsSection("Playback")
        KokoroPlaybackCard(
            state = state.kokoroModel,
            voice = state.kokoroVoice,
            threads = state.kokoroThreads,
            english = LocalVoiceModelStore.supportsLanguage(state.language),
            previewError = previewError,
            select = {
                previewError = null
                actions.selectKokoroVoice(it)
            },
            selectThreads = actions.selectKokoroThreads,
            install = actions.installKokoroModel,
            cancel = actions.cancelKokoroInstall,
            remove = {
                previewError = null
                actions.removeKokoroModel()
            },
            preview = { selected -> actions.previewKokoro(selected) { previewError = it } }
        )
        AndroidVoiceSettings(state, systemAudio, actions)
        TextButton(onClick = {
            runCatching { context.startActivity(android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                .onFailure { runCatching { context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS")) } }
        }) { Text("Install or manage voices") }
        Text("Speech rate · ${String.format(LocalLocale.current.platformLocale, "%.1f", state.rate)}×")
        Slider(state.rate, actions.changeRate, valueRange = .5f..2f)
    }
}

private fun voicePrivacyDescription(state: VoiceSettingsUiState): String =
    if (state.privateSpeech) {
        "This private chat requires on device speech. Change the chat destination explicitly to use online speech."
    } else if (state.kokoroVoice != null) {
        if (state.recognitionBackend == VoiceRecognitionBackend.LOCAL_MOONSHINE) {
            "Moonshine recognition and Kokoro playback work offline."
        } else {
            "This switch controls Android speech recognition. Kokoro and its fallback play offline."
        }
    } else if (state.recognitionBackend == VoiceRecognitionBackend.LOCAL_MOONSHINE) {
        "Moonshine recognition always stays on this phone. This switch controls whether Android spoken replies can use the network."
    } else {
        "Off uses Android on device recognition and an offline TTS voice. No silent network fallback."
    }

@Composable
private fun VoiceSettingSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Switch(checked, change)
    }
}

@Composable
private fun AndroidVoiceSettings(
    state: VoiceSettingsUiState,
    systemAudio: AndroidVoiceAudio,
    actions: VoiceSettingsActions
) {
    val voices by systemAudio.voiceChoices.collectAsStateWithLifecycle()
    val catalog by systemAudio.voiceCatalogState.collectAsStateWithLifecycle()
    if (state.kokoroVoice != null) return
    Text("Android voices", style = MaterialTheme.typography.titleSmall)
    Text("Best installed voices appear first. Preview a voice to hear its character and pacing.", style = MaterialTheme.typography.bodySmall)
    voices.forEach { (name, label) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { actions.selectAndroidVoice(name) }, modifier = Modifier.weight(1f)) {
                Text((if (state.voiceName == name) "Selected · " else "") + label)
            }
            TextButton(onClick = { actions.previewAndroidVoice(name) }) { Text("Preview") }
        }
    }
    when (catalog) {
        SpeechVoiceCatalogState.CHECKING -> Text("Checking the speech engine…", style = MaterialTheme.typography.bodySmall)
        SpeechVoiceCatalogState.UNAVAILABLE -> {
            Text("The speech engine is disconnected or unavailable. Review Android speech settings, then recheck.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = systemAudio::prepareVoices) { Text("Recheck speech engine") }
        }
        SpeechVoiceCatalogState.EMPTY -> Text("The speech engine returned an empty voice catalog. Install a voice for the selected language.", style = MaterialTheme.typography.bodySmall)
        else -> if (voices.size < 3) Text("Install additional voices for this language to choose from at least three distinct voices.", style = MaterialTheme.typography.bodySmall)
    }
}

private data class VoiceConversationUiState(
    val profileName: String,
    val recognitionBackend: VoiceRecognitionBackend,
    val captions: Boolean,
    val session: VoiceState,
    val status: String,
    val remaining: Int,
    val localModel: LocalVoiceModelState,
    val language: String,
    val recognitionReady: Boolean,
    val enabled: Boolean,
    val voiceReady: Boolean,
    val preferencesApplied: Boolean,
    val waitingApproval: Boolean,
    val draft: String
)

private class VoiceConversationActions(
    val openTranscript: () -> Unit,
    val openSettings: () -> Unit,
    val showSettings: () -> Unit,
    val installLocalModel: () -> Unit,
    val cancelLocalModelInstall: () -> Unit,
    val selectRecognitionBackend: (VoiceRecognitionBackend) -> Unit,
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
private fun VoiceConversationScreen(state: VoiceConversationUiState, actions: VoiceConversationActions) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp).testTag("voice_chat_screen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VoiceConversationHeader(state, actions)
        VoiceConversationStatus(state, actions, Modifier.weight(1f).fillMaxWidth())
        VoiceMicrophoneControl(state, actions.toggleMicrophone)
        Row(Modifier.padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            VoiceControl("Text chat", Icons.Rounded.ChatBubbleOutline, actions.openChat, false)
            VoiceControl("Audio", if (state.session.speaker) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, actions.openAudioRoute, !state.session.speaker)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(onClick = actions.end, modifier = Modifier.size(64.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                    Icon(Icons.Rounded.Close, "End voice chat", Modifier.size(28.dp))
                }
                Text("End", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun VoiceConversationHeader(state: VoiceConversationUiState, actions: VoiceConversationActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Voice chat", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(state.profileName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.recognitionBackend.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = actions.openTranscript) {
            Icon(Icons.Rounded.Subtitles, "Open transcript", tint = if (state.captions) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = actions.openSettings, enabled = state.preferencesApplied) {
            Icon(Icons.Rounded.Settings, "Voice settings", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun VoiceConversationStatus(
    state: VoiceConversationUiState,
    actions: VoiceConversationActions,
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        VoiceWave(state.session, Modifier.fillMaxWidth().height(160.dp).clearAndSetSemantics { })
        AnimatedContent(state.status, label = "Voice status") {
            Text(it, modifier = Modifier.semantics { if (state.remaining == 0) liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        }
        Text(voiceListeningHint(state.session), Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (state.recognitionBackend == VoiceRecognitionBackend.LOCAL_MOONSHINE && !state.recognitionReady) {
            Spacer(Modifier.height(16.dp))
            LocalVoiceSetupCard(state.localModel, actions.installLocalModel, actions.cancelLocalModelInstall, {})
            if (!LocalVoiceModelStore.supportsLanguage(state.language)) {
                Text("This installed model recognizes English. Select an English language tag or choose Android speech in Voice settings.", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = actions.showSettings) { Text("Choose speech language") }
            }
            TextButton(onClick = { actions.selectRecognitionBackend(VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE) }) { Text("Use Android speech instead") }
        }
        VoiceTranscriptPreview(state)
        if (state.session.hasDraft && state.session.phase != VoicePhase.REVIEWING) {
            TextButton(onClick = actions.listen, enabled = state.enabled && state.voiceReady && state.preferencesApplied) { Text("Review captured text") }
        }
        if (state.session.phase == VoicePhase.REVIEWING) {
            OutlinedTextField(state.draft, actions.changeDraft, label = { Text("Your message") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), minLines = 2, maxLines = 20)
            Button(onClick = actions.sendDraft, enabled = state.draft.isNotBlank()) { Text("Send") }
            TextButton(onClick = actions.recordAgain) { Text("Record again") }
        }
        state.session.notice?.let {
            Text(it, Modifier.padding(top = 16.dp).widthIn(max = 320.dp), style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.sp), color = if (state.remaining > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        if (state.recognitionBackend == VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE && state.session.recoveryAction != null) {
            Button(onClick = { actions.selectRecognitionBackend(VoiceRecognitionBackend.LOCAL_MOONSHINE) }) {
                Text(if (state.localModel.status == LocalVoiceModelStatus.READY) "Use local speech" else "Set up local speech")
            }
        }
        state.session.recoveryAction?.let { action ->
            TextButton(onClick = { actions.repair(action) }) { Text(voiceRepairLabel(action)) }
            TextButton(onClick = actions.openVoiceConfiguration) { Text("Change voice configuration") }
            TextButton(onClick = actions.listen, enabled = state.remaining == 0) { Text("Try microphone after repair") }
        }
        if (state.session.reply.isNotBlank() && state.session.phase in setOf(VoicePhase.PAUSED, VoicePhase.ERROR)) TextButton(onClick = actions.replay) { Text("Replay reply") }
        if (state.waitingApproval || state.session.phase == VoicePhase.ERROR || !state.enabled || !state.recognitionReady) TextButton(onClick = actions.openChat) { Text("Continue in chat") }
    }
}

private fun voiceListeningHint(state: VoiceState): String = when {
    state.phase == VoicePhase.SPEAKING -> "Listening resumes after the reply"
    state.phase == VoicePhase.LISTENING -> "Go ahead, I’m listening"
    state.muted -> "Unmute when you’re ready to speak"
    else -> "Use the microphone button when you are ready"
}

@Composable
private fun VoiceTranscriptPreview(state: VoiceConversationUiState) {
    if (!state.captions || state.session.transcript.isBlank() && state.session.reply.isBlank()) return
    Surface(Modifier.padding(top = 24.dp).widthIn(max = 560.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.session.transcript.isNotBlank()) Text(state.session.transcript, color = MaterialTheme.colorScheme.primary, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (state.session.reply.isNotBlank()) Text(state.session.reply.take(12000), color = MaterialTheme.colorScheme.onSurface, maxLines = 7, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun VoiceMicrophoneControl(state: VoiceConversationUiState, toggle: () -> Unit) {
    FilledIconButton(
        onClick = toggle,
        enabled = state.enabled && state.voiceReady && state.preferencesApplied && state.recognitionReady,
        modifier = Modifier.size(128.dp).testTag("voice_microphone_toggle"),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (!state.session.muted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (!state.session.muted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
    ) {
        val label = when {
            state.session.muted -> "Turn microphone on"
            state.session.phase == VoicePhase.ERROR -> "Retry microphone"
            state.session.phase == VoicePhase.PAUSED -> "Start listening"
            else -> "Turn microphone off"
        }
        Icon(if (state.session.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, label, Modifier.size(52.dp))
    }
    Text(
        when {
            !state.recognitionReady || state.session.muted -> "Microphone off"
            state.session.phase in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING) -> "Microphone on"
            else -> "Microphone ready"
        },
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.labelMedium
    )
}

@Composable
private fun VoiceTranscriptDialog(transcript: String, reply: String, dismiss: () -> Unit, openChat: () -> Unit) {
    dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Transcript", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(transcript.ifBlank { "No speech captured yet" }, color = MaterialTheme.colorScheme.primary)
                    Text(reply, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = openChat) { Text("Full chat") } }
    )
}

@Composable
private fun VoiceRouteDialog(
    route: String,
    routes: List<Pair<Int, String>>,
    speaker: Boolean,
    dismiss: () -> Unit,
    selectRoute: (Int) -> Unit,
    toggleSpokenReplies: () -> Unit
) {
    val context = LocalContext.current
    dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Audio output", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(route, color = MaterialTheme.colorScheme.onSurfaceVariant)
                routes.forEach { (id, name) ->
                    TextButton(onClick = { selectRoute(id) }) { Text(name) }
                }
                TextButton(onClick = toggleSpokenReplies) { Text(if (speaker) "Turn spoken replies off" else "Turn spoken replies on") }
                TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)) }) { Text("Android sound settings") }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } }
    )
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

@Composable
internal fun VoiceSettingsPage(onDone: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().testTag("voice_settings_page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to voice chat") }
            Text("Voice settings", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onDone) { Text("Done") }
        }
        HorizontalDivider()
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun VoiceSettingsSection(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
