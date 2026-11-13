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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.material3.OutlinedButton
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
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.voice.AndroidVoiceAudio
import dev.chungjungsoo.gptmobile.data.voice.KokoroOutput
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStore
import dev.chungjungsoo.gptmobile.data.voice.MoonshineVoiceRecognizer
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

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
internal fun VoiceEntryScreen(
    state: VoiceEntryState,
    origin: Offset,
    select: (String) -> Unit,
    start: () -> Unit,
    back: () -> Unit,
    settings: () -> Unit,
    modelPreparation: dev.chungjungsoo.gptmobile.data.context.GatewayModelPreparationState? = null,
    recheckModel: () -> Unit = {},
    modelsReady: Boolean = true,
    setup: @Composable () -> Unit = {}
) {
    NewChatRevealPanel(origin) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Voice chat", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = back) { Icon(Icons.Rounded.Close, "Close voice chat", tint = MaterialTheme.colorScheme.onSurface) }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                VoiceWave(VoiceState(), Modifier.fillMaxWidth().height(100.dp))
                Text(if (modelsReady) "Let’s talk" else "Set up voice chat", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                if (!state.loaded || state.creating) {
                    CircularProgressIndicator()
                } else {
                    Column(Modifier.widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        setup()
                        VoiceChoice("AI profile", state.selected, state.profiles.map { it.uid to it.name.ifBlank { it.model } }, select)
                    }
                    modelPreparation?.let { preparation ->
                        dev.chungjungsoo.gptmobile.presentation.ui.setting.GatewayModelPreparationContent(preparation, recheckModel)
                    }
                    if (state.profiles.isEmpty()) {
                        Button(onClick = settings) { Text("Set up an AI profile") }
                    } else {
                        Button(onClick = start, enabled = modelsReady && state.selected.isNotBlank(), modifier = Modifier.padding(top = 16.dp).testTag("voice_enable_start")) { Text("Start voice chat") }
                    }
                }
                state.error?.let { Text(it, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            }
            Text("Speech is processed on this phone. Your selected AI profile receives the transcript.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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
    var profilePreflightReady by remember(model, selectedProfile) { mutableStateOf(false) }
    var profilePreflightError by remember(model, selectedProfile) { mutableStateOf<String?>(null) }
    val voiceReady = loaded && roomReady && selectedProfile != null && profilePreflightReady
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
    val recognitionBackend = VoiceRecognitionBackend.LOCAL_MOONSHINE
    var kokoroVoice by rememberSaveable { mutableStateOf<KokoroVoice?>(null) }
    var kokoroThreads by rememberSaveable { mutableIntStateOf(2) }
    var editBeforeSend by rememberSaveable { mutableStateOf(false) }
    var spokenReplies by rememberSaveable { mutableStateOf(true) }
    var rate by rememberSaveable { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var preferencesInitialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(model, loaded, roomReady, selectedProfile, lifecycleState, settingsOpen) {
        profilePreflightReady = false
        profilePreflightError = null
        val profile = selectedProfile ?: return@LaunchedEffect
        if (!loaded || !roomReady || settingsOpen || lifecycleState != Lifecycle.State.RESUMED) return@LaunchedEffect
        try {
            // This checks the same retained account/policy used at dispatch. A
            // changed selection cancels the old check before it can open capture.
            model.preflightVoiceProfile(profile)
            currentCoroutineContext().ensureActive()
            profilePreflightReady = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalStateException) {
            currentCoroutineContext().ensureActive()
            profilePreflightError = failure.message?.removePrefix("POLICY_RESTRICTED:")?.trim()
                ?: "This AI profile needs attention. Choose another profile or review its settings in chat."
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            profilePreflightError = "Could not check this AI profile. Open chat to review its connection, or choose another profile."
        }
    }
    LaunchedEffect(savedPreferences) {
        val saved = savedPreferences ?: return@LaunchedEffect
        if (!preferencesApplied) {
            if (!preferencesInitialized) {
                language = saved.language.takeIf { LocalVoiceModelStore.supportsLanguage(it) } ?: "en-US"
                captions = saved.captions
                editBeforeSend = saved.review
                spokenReplies = saved.spokenReplies
                rate = saved.rate
                kokoroVoice = saved.kokoroVoice ?: KokoroVoice.HEART
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
            systemAudio.allowOnlineSpeech = false
            systemAudio.speechRate = rate
            session.editBeforeSend = editBeforeSend
            session.autoContinue = true
            preferencesApplied = true
        }
    }
    val recognitionReady = localModel.status == LocalVoiceModelStatus.READY &&
        LocalVoiceModelStore.supportsLanguage(language)
    fun applyAudioConfiguration() {
        val onlineAllowed = false
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
        systemAudio.speechRate = rate
        audio.kokoroVoice = kokoroVoice
        kokoroOutput?.configureThreads(kokoroThreads)
        session.editBeforeSend = editBeforeSend
        session.autoContinue = true
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
    LaunchedEffect(settingsOpen, routeOpen, transcriptOpen, recognitionReady, preferencesApplied) {
        if (!preferencesApplied) return@LaunchedEffect
        applyAudioConfiguration()
        if (voiceReady && recognitionReady && !settingsOpen && !routeOpen && !transcriptOpen && started && !state.muted && state.recoveryAction == null && lifecycleState == Lifecycle.State.RESUMED) {
            if (state.phase == VoicePhase.PAUSED) session.resume()
        }
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
        voiceSettings.save(VoicePreferences(language = language, captions = captions, review = editBeforeSend, rate = rate, kokoroVoice = kokoroVoice, spokenReplies = spokenReplies, kokoroThreads = kokoroThreads))
    }
    fun setSpokenReplies(value: Boolean) {
        spokenReplies = value
        if (session.states.value.speaker != value) session.speaker()
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
        if (enabled && voiceReady && preferencesApplied && recognitionReady && !settingsOpen && !routeOpen && !transcriptOpen && state.recoveryAction == null && lifecycleState == Lifecycle.State.RESUMED && (!started || resumeWhenReady || !state.muted && state.phase == VoicePhase.PAUSED)) {
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
            val failure = reply.timeline.lastOrNull { it.profileFailure != null }?.profileFailure
                ?: run.terminalError?.let { dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.classify(it) }
            session.reply(run.runId, text, run.status != AgentRunStatus.COMPLETED, failure, terminalStatus = run.status)
        } else {
            session.streamReply(run.runId, text)
        }
    }
    val waitingApproval = approvals.isNotEmpty() || inputs.isNotEmpty() || consent != null
    val profileName = profiles.firstOrNull { it.uid in activeProfiles }?.let { it.name.ifBlank { it.model } } ?: "Your AI profile"
    val status = when {
        !enabled -> "Voice chat is turned off"
        profilePreflightError != null -> "AI profile needs attention"
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
                localModel = localModel,
                language = language,
                kokoroVoice = kokoroVoice,
                editBeforeSend = editBeforeSend,
                captions = captions,
                spokenReplies = spokenReplies,
                kokoroModel = kokoroModel,
                kokoroThreads = kokoroThreads,
                rate = rate
            ),
            actions = VoiceSettingsActions(
                done = ::closeSettings,
                selectProfile = { uid ->
                    session.pause()
                    model.selectVoiceProfile(uid) { changed -> if (changed) closeSettings() }
                },
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
                changeReview = {
                    editBeforeSend = it
                    savePreferences()
                },
                changeCaptions = {
                    captions = it
                    savePreferences()
                },
                changeSpokenReplies = ::setSpokenReplies,
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
                        playKokoroPreview(kokoroOutput, selected) { message, _ -> report(message) }
                    }
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
            captions = captions,
            session = state,
            status = status,
            remaining = remaining,
            localModel = localModel,
            language = language,
            recognitionReady = recognitionReady,
            enabled = enabled,
            voiceReady = voiceReady,
            profileError = profilePreflightError,
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
                if (state.phase == VoicePhase.SPEAKING) {
                    session.stopSpeaking()
                } else if (state.muted) {
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

internal fun playKokoroPreview(output: KokoroOutput, voice: KokoroVoice, error: (String, Boolean) -> Unit) {
    // Settings previews do not belong to the previous conversation reply.
    output.bindDiagnostics(null)
    output.bindReply("")
    output.speak("Hello. This is your Kokoro voice. I am ready when you are.", voice, {}, error)
}

private data class VoiceSettingsUiState(
    val profiles: List<PlatformV2>,
    val activeProfiles: List<String>,
    val session: VoiceState,
    val saveError: String?,
    val localModel: LocalVoiceModelState,
    val language: String,
    val kokoroVoice: KokoroVoice?,
    val editBeforeSend: Boolean,
    val captions: Boolean,
    val spokenReplies: Boolean,
    val kokoroModel: LocalVoiceModelState,
    val kokoroThreads: Int,
    val rate: Float
)

private class VoiceSettingsActions(
    val done: () -> Unit,
    val selectProfile: (String) -> Unit,
    val installLocalModel: () -> Unit,
    val cancelLocalModelInstall: () -> Unit,
    val removeLocalModel: () -> Unit,
    val changeLanguage: (String) -> Unit,
    val changeReview: (Boolean) -> Unit,
    val changeCaptions: (Boolean) -> Unit,
    val changeSpokenReplies: (Boolean) -> Unit,
    val selectKokoroVoice: (KokoroVoice?) -> Unit,
    val selectKokoroThreads: (Int) -> Unit,
    val installKokoroModel: () -> Unit,
    val cancelKokoroInstall: () -> Unit,
    val removeKokoroModel: () -> Unit,
    val previewKokoro: (KokoroVoice, (String?) -> Unit) -> Unit,
    val changeRate: (Float) -> Unit
)

@Composable
private fun VoiceSettingsScreen(
    state: VoiceSettingsUiState,
    actions: VoiceSettingsActions
) {
    var previewError by remember { mutableStateOf<String?>(null) }
    VoiceSettingsPage(actions.done) {
        VoiceChoice(
            "AI profile",
            state.activeProfiles.singleOrNull().orEmpty(),
            state.profiles.filter { it.enabled }.map {
                it.uid to it.name.ifBlank { it.model }
            },
            actions.selectProfile,
            enabled = !state.session.awaitingReply
        )
        state.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        VoiceSettingsSection("Speech recognition")
        LocalVoiceSetupCard(state.localModel, actions.installLocalModel, actions.cancelLocalModelInstall, actions.removeLocalModel, showRemove = true)
        val regions = remember(state.language) {
            (Locale.getISOCountries().map { "en-$it" } + state.language).distinct().sortedBy {
                Locale.forLanguageTag(it).displayCountry
            }.map { it to Locale.forLanguageTag(it).displayCountry.ifBlank { "English (default)" } }
        }
        VoiceChoice("Country or region", state.language, regions, actions.changeLanguage)
        VoiceSettingsSection("Conversation")
        VoiceSettingSwitch("Review before sending", state.editBeforeSend, actions.changeReview)
        VoiceSettingSwitch("Live captions", state.captions, actions.changeCaptions)
        VoiceSettingSwitch("Spoken replies", state.spokenReplies, actions.changeSpokenReplies)
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
        Text("Speech rate · ${String.format(LocalLocale.current.platformLocale, "%.1f", state.rate)}×")
        Slider(state.rate, actions.changeRate, valueRange = .5f..2f)
    }
}

@Composable
private fun VoiceChoice(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled && options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "Select $label", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                options.forEach { (key, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        expanded = false
                        onSelect(key)
                    })
                }
            }
        }
    }
}

@Composable
private fun VoiceSettingSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Switch(checked, change)
    }
}

private data class VoiceConversationUiState(
    val profileName: String,
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

private class VoiceConversationActions(
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
private fun VoiceConversationScreen(state: VoiceConversationUiState, actions: VoiceConversationActions) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp).testTag("voice_chat_screen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VoiceConversationHeader(state, actions)
        VoiceConversationStatus(state, actions, Modifier.weight(1f).fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            VoiceControl("Text chat", Icons.Rounded.ChatBubbleOutline, actions.openChat, false)
            VoiceMicrophoneControl(state, actions.toggleMicrophone)
            VoiceControl("Audio", if (state.session.speaker) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, actions.openAudioRoute, !state.session.speaker)
        }
    }
}

@Composable
private fun VoiceConversationHeader(state: VoiceConversationUiState, actions: VoiceConversationActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.end) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to chats") }
        Column(Modifier.weight(1f)) {
            Text("Voice chat", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(state.profileName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val listening = state.session.phase == VoicePhase.LISTENING
    val speaking = state.session.phase == VoicePhase.SPEAKING
    val colors = MaterialTheme.colorScheme
    val fill = if (listening) {
        val pulse by rememberInfiniteTransition(label = "Microphone pulse").animateFloat(
            initialValue = 0f,
            targetValue = 0.32f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "Microphone fill"
        )
        androidx.compose.ui.graphics.lerp(colors.primary, colors.primaryContainer, pulse)
    } else if (speaking) {
        colors.tertiaryContainer
    } else {
        colors.surfaceContainerHigh
    }
    val (icon, label) = when {
        speaking -> Icons.Rounded.Stop to "Stop speaking"
        state.session.muted -> Icons.Rounded.MicOff to "Start listening"
        state.session.phase == VoicePhase.ERROR -> Icons.Rounded.Mic to "Retry microphone"
        state.session.phase == VoicePhase.PAUSED -> Icons.Rounded.Mic to "Start listening"
        else -> Icons.Rounded.Mic to "Turn microphone off"
    }
    FilledIconButton(
        onClick = toggle,
        enabled = speaking || state.enabled && state.voiceReady && state.preferencesApplied && state.recognitionReady,
        modifier = Modifier.size(96.dp).testTag("voice_microphone_toggle"),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = fill,
            contentColor = when {
                listening -> colors.onPrimary
                speaking -> colors.onTertiaryContainer
                else -> colors.onSurface
            }
        )
    ) { Icon(icon, label, Modifier.size(40.dp)) }
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
