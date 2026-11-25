package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
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
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.readiness.ReadinessHints
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelVariant
import dev.chungjungsoo.gptmobile.data.voice.KokoroOutput
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStore
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeKokoroBucket
import dev.chungjungsoo.gptmobile.data.voice.VoiceAdmissionStage
import dev.chungjungsoo.gptmobile.data.voice.VoiceMessageIdentity
import dev.chungjungsoo.gptmobile.data.voice.VoicePauseDuration
import dev.chungjungsoo.gptmobile.data.voice.VoicePerformanceMode
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoicePreparation
import dev.chungjungsoo.gptmobile.data.voice.VoicePreparationPolicy
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecoveryAction
import dev.chungjungsoo.gptmobile.data.voice.VoiceSearchOpening
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import dev.chungjungsoo.gptmobile.data.voice.shouldCheckpointDraft
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel
import dev.chungjungsoo.gptmobile.presentation.ui.chat.resolvePlatformModel
import dev.chungjungsoo.gptmobile.presentation.ui.home.NewChatRevealPanel
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

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
    showInstallation: Boolean = false,
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
                Text(if (modelsReady) "Voice chat is ready" else "Talk with your AI", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                if (!state.loaded || state.creating) {
                    CircularProgressIndicator()
                } else if (!modelsReady) {
                    Column(
                        Modifier.widthIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Speak your message and listen to the reply.", textAlign = TextAlign.Center)
                        setup()
                    }
                } else {
                    Column(Modifier.widthIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        VoiceChoice("AI profile", state.selected, state.profiles.map { it.uid to it.name.ifBlank { it.model } }, select)
                    }
                    modelPreparation?.let { preparation ->
                        dev.chungjungsoo.gptmobile.presentation.ui.setting.GatewayModelPreparationContent(preparation, recheckModel)
                    }
                    if (state.profiles.isEmpty()) {
                        Button(onClick = settings) { Text("Set up an AI profile") }
                    } else {
                        Button(onClick = start, enabled = state.selected.isNotBlank(), modifier = Modifier.padding(top = 16.dp).testTag("voice_enable_start")) { Text("Start voice chat") }
                    }
                    if (showInstallation) setup()
                }
                state.error?.let { Text(it, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            }
            Text("Speech recognition runs on this phone. Spoken replies use your voice settings. Your selected AI profile receives the transcript.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun VoiceChatScreen(
    onEnd: () -> Unit,
    model: ChatViewModel = hiltViewModel(),
    voiceSettings: VoicePreferencesViewModel = hiltViewModel(),
    initialChatView: Boolean = false,
    onNavigateToLocalModels: () -> Unit = {},
    onReviewEvidenceAccess: () -> Unit = {},
    onProfileSettings: (String) -> Unit = {},
    onOpenConversation: (Int, Boolean) -> Unit = { _, _ -> },
    bottomInteractionContent: (@Composable () -> Unit)? = null,
    presentation: VoiceRoomPresentationViewModel = hiltViewModel()
) {
    val room by model.chatRoom.collectAsStateWithLifecycle()
    dev.chungjungsoo.gptmobile.presentation.common.PrivateConversationScreenProtection(room.id, room.privacyModeLocked)
    LaunchedEffect(room.id, room.createdAt, room.isTemporary) {
        presentation.rememberVoiceRoom(VoiceRoomIdentity.persistent(room.id, room.createdAt, room.isTemporary))
    }
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
    VoiceChatSessionScreen(
        onEnd,
        model,
        voiceSettings,
        stores,
        initialChatView,
        VoiceChatDestinations(onNavigateToLocalModels, onReviewEvidenceAccess, onProfileSettings, onOpenConversation),
        bottomInteractionContent
    )
}

@Composable
private fun VoiceChatSessionScreen(
    onEnd: () -> Unit,
    model: ChatViewModel,
    voiceSettings: VoicePreferencesViewModel,
    stores: VoiceModelStores,
    initialChatView: Boolean,
    destinations: VoiceChatDestinations,
    bottomInteractionContent: (@Composable () -> Unit)?,
    runtimeOwner: VoiceRuntimeViewModel = hiltViewModel(),
    backendOwner: VoiceBackendViewModel = hiltViewModel(),
    benchmarkOwner: VoiceSpeechBenchmarkViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val settingsScope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val backend by backendOwner.state.collectAsStateWithLifecycle()
    // A canceled worker can outlive both its settings subtree and its navigation owner.
    val benchmarkOwnership = VoiceSpeechBenchmarkViewModel.audioOwnership
    val benchmarkLease by benchmarkOwnership.collectAsStateWithLifecycle()
    val benchmarkRunning = benchmarkLease != null
    val loaded by model.isLoaded.collectAsStateWithLifecycle()
    val roomReady by model.voiceRoomReady.collectAsStateWithLifecycle()
    val admission by model.voiceAdmission.collectAsStateWithLifecycle()
    val saveError by voiceSettings.saveError.collectAsStateWithLifecycle()
    val features by model.featureSettings.collectAsStateWithLifecycle()
    val messages by model.groupedMessages.collectAsStateWithLifecycle()
    val runs by model.agentRunsById.collectAsStateWithLifecycle()
    val toolEvents by model.toolEventsByRun.collectAsStateWithLifecycle()
    val profiles by model.platformsInApp.collectAsStateWithLifecycle()
    val activeProfiles by model.activePlatformUids.collectAsStateWithLifecycle()
    val room by model.chatRoom.collectAsStateWithLifecycle()
    val chatModels by model.chatPlatformModels.collectAsStateWithLifecycle()
    var retainedMessageId by rememberSaveable { mutableIntStateOf(0) }
    var retainedRunId by rememberSaveable { mutableStateOf("") }
    var retainedRevision by rememberSaveable { mutableIntStateOf(-1) }
    fun retainMessage(identity: VoiceMessageIdentity) {
        retainedMessageId = identity.messageId
        retainedRunId = identity.runId
        retainedRevision = identity.revision
    }
    var chatView by rememberSaveable { mutableStateOf(initialChatView) }
    var listeningEnabled by rememberSaveable { mutableStateOf(!initialChatView) }
    val selectedProfile = activeProfiles.singleOrNull()?.let { uid -> profiles.singleOrNull { it.uid == uid && it.enabled } }
        ?.let { resolvePlatformModel(it, chatModels) }
    var profilePreflightReady by remember(model, room.id, room.privacyModeLocked, selectedProfile) { mutableStateOf(false) }
    var profilePreflightError by remember(model, room.id, room.privacyModeLocked, selectedProfile) { mutableStateOf<String?>(null) }
    val approvals by model.pendingToolApprovals.collectAsStateWithLifecycle(emptyList())
    val inputs by model.pendingMcpInput.collectAsStateWithLifecycle()
    val consent by model.pendingFreeToolConsent.collectAsStateWithLifecycle()
    var retainedTranscript by rememberSaveable { mutableStateOf("") }
    var retainedReply by rememberSaveable { mutableStateOf("") }
    var retainedReview by rememberSaveable { mutableStateOf(false) }
    var retainedSubmittedRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var retainedMuted by rememberSaveable { mutableStateOf(false) }
    val runtime = remember(model, stores, runtimeOwner) {
        runtimeOwner.acquire(model, stores) { session ->
            session.restore(retainedTranscript, retainedReply, retainedReview, pending = false, pendingRequestId = retainedSubmittedRequestId)
            if (retainedMuted) session.mute()
        }
    }
    val systemAudio = runtime.system
    val localRecognition = runtime.recognition
    val kokoroOutput = runtime.kokoro
    LaunchedEffect(kokoroOutput, backend.factory) { kokoroOutput?.configureEngine(backend.factory) }
    val audio = runtime.audio
    val session = runtime.session
    SideEffect { session.holdAudio(benchmarkOwnership.value != null) }
    val tuningConfiguration = runtime.tuningConfiguration
    val playback = runtime.playback
    val replyProjection = runtime.replyProjection
    var playbackPrimed by remember(runtime) { mutableStateOf(playback.initialized) }
    var playbackTick by remember(runtime) { mutableIntStateOf(0) }
    var activeMessage by runtime.activeMessage
    var manualMessage by runtime.manualMessage
    var refreshMutedBaseline by runtime.refreshMutedBaseline
    val voiceReady = loaded && roomReady && backend.loaded && playbackPrimed && selectedProfile != null && profilePreflightReady
    val localModel by stores.moonshine.state.collectAsStateWithLifecycle()
    val kokoroModel by stores.kokoro.state.collectAsStateWithLifecycle()
    val state by session.presentationStates.collectAsStateWithLifecycle()
    SideEffect {
        if (state.phase != VoicePhase.CLOSED) {
            retainedTranscript = state.transcript
            retainedReply = state.reply
            retainedReview = state.hasDraft
            retainedSubmittedRequestId = state.submittedDraftRequestId
            retainedMuted = state.muted
        }
    }
    val enabled = features.isToolPluginEnabled(ToolPluginId.VOICE_CHAT)
    val savedPreferences by voiceSettings.preferences.collectAsStateWithLifecycle()
    var preferencesApplied by remember(runtime) { mutableStateOf(false) }
    val currentEnabled by rememberUpdatedState(enabled && preferencesApplied && loaded && roomReady && backend.loaded)
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var started by remember { mutableStateOf(false) }
    var resumeWhenReady by remember { mutableStateOf(false) }
    var captions by rememberSaveable { mutableStateOf(true) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf(java.util.Locale.getDefault().toLanguageTag()) }
    val recognitionBackend = VoiceRecognitionBackend.LOCAL_MOONSHINE
    var kokoroVoice by rememberSaveable { mutableStateOf<KokoroVoice?>(null) }
    var kokoroThreads by rememberSaveable { mutableIntStateOf(2) }
    var editBeforeSend by rememberSaveable { mutableStateOf(false) }
    var spokenReplies by rememberSaveable { mutableStateOf(true) }
    var buttonSounds by rememberSaveable { mutableStateOf(false) }
    val feedback = remember(context) { VoiceButtonFeedback(context.applicationContext) }
    DisposableEffect(feedback) { onDispose { feedback.close() } }
    SideEffect { localRecognition.onManualCaptureReady = if (buttonSounds) feedback::start else null }
    var rate by rememberSaveable { androidx.compose.runtime.mutableFloatStateOf(STANDARD_SPEECH_RATE) }
    var pause by rememberSaveable { mutableStateOf(VoicePauseDuration.NATURAL) }
    var performance by rememberSaveable { mutableStateOf(VoicePerformanceMode.AUTO) }
    var speakWhileGenerating by rememberSaveable { mutableStateOf(true) }
    var kokoroBucket by rememberSaveable { mutableStateOf(MediaPipeKokoroBucket.AUTO) }
    var incrementalSpeech by rememberSaveable { mutableStateOf(true) }
    var nativeModelVariant by rememberSaveable { mutableStateOf(stores.kokoroVariant) }
    var modelChangeRunning by remember { mutableStateOf(false) }
    var modelChangeError by remember { mutableStateOf<String?>(null) }
    var tuning by remember { mutableStateOf(VoiceTuningPreferences()) }
    var preferencesInitialized by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(model, enabled, roomReady, room.id, room.privacyModeLocked, selectedProfile, lifecycleState, settingsOpen) {
        profilePreflightReady = false
        profilePreflightError = null
        val profile = selectedProfile ?: return@LaunchedEffect
        if (!enabled || !roomReady || settingsOpen || lifecycleState != Lifecycle.State.RESUMED) return@LaunchedEffect
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
    LaunchedEffect(savedPreferences, runtime) {
        val saved = savedPreferences ?: return@LaunchedEffect
        if (!preferencesApplied) {
            tuning = saved.tuning
            if (!preferencesInitialized) {
                language = saved.language.takeIf { LocalVoiceModelStore.supportsLanguage(it) } ?: "en-US"
                captions = saved.captions
                editBeforeSend = saved.review
                spokenReplies = saved.spokenReplies
                buttonSounds = saved.buttonSounds
                rate = saved.rate
                pause = saved.pause
                performance = saved.performance
                speakWhileGenerating = saved.speakWhileGenerating
                kokoroBucket = saved.kokoroBucket
                incrementalSpeech = saved.incrementalSpeech
                nativeModelVariant = saved.nativeModelVariant
                kokoroVoice = saved.kokoroVoice ?: KokoroVoice.HEART
                kokoroThreads = saved.kokoroThreads
                preferencesInitialized = true
            }
            audio.recognitionBackend = recognitionBackend
            localRecognition.languageTag = language
            tuningConfiguration.apply(tuning)
            audio.kokoroVoice = kokoroVoice
            kokoroOutput?.configureThreads(kokoroThreads)
            kokoroOutput?.configurePacing(pause, performance)
            session.performanceMode = performance
            session.speakWhileGenerating = speakWhileGenerating
            if (session.states.value.speaker != spokenReplies) session.speaker()
            // Configure before permitting the first listen, including after restoring the screen.
            systemAudio.languageTag = language
            systemAudio.allowOnlineSpeech = false
            systemAudio.speechRate = rate
            session.editBeforeSend = editBeforeSend
            session.autoContinue = listeningEnabled && !playback.hasPending
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
        tuningConfiguration.apply(tuning)
        systemAudio.languageTag = language
        systemAudio.allowOnlineSpeech = onlineAllowed
        systemAudio.speechRate = rate
        audio.kokoroVoice = kokoroVoice
        kokoroOutput?.configureThreads(kokoroThreads)
        kokoroOutput?.configurePacing(pause, performance)
        session.performanceMode = performance
        session.speakWhileGenerating = speakWhileGenerating
        session.editBeforeSend = editBeforeSend
        session.autoContinue = listeningEnabled && !playback.hasPending
    }
    SideEffect {
        if (preferencesApplied) applyAudioConfiguration()
    }
    LaunchedEffect(preferencesApplied, kokoroVoice, rate, kokoroBucket, incrementalSpeech, nativeModelVariant) {
        if (preferencesApplied) backendOwner.configureSpeech(kokoroVoice, rate, kokoroBucket, incrementalSpeech, nativeModelVariant)
    }
    var route by remember { mutableStateOf(systemAudio.routeName()) }
    var routeOpen by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(0) }
    DisposableEffect(audio, session) {
        systemAudio.routeChanged = {
            session.pause("audio_route_changed")
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
    val foregroundVoice = enabled && roomReady && lifecycleState == Lifecycle.State.RESUMED && !settingsOpen && !routeOpen && !benchmarkRunning
    val installOnVoiceLaunch = foregroundVoice && loaded && room.id > 0 && !chatView
    LaunchedEffect(voiceSettings, installOnVoiceLaunch) {
        if (installOnVoiceLaunch) voiceSettings.onVoiceLaunch()
    }
    val prepareProfile = foregroundVoice && room.id > 0 && !chatView && profilePreflightReady && features.readiness.enabled && !state.awaitingReply
    val networkEpoch by ReadinessHints.networkEpoch.collectAsStateWithLifecycle()
    LaunchedEffect(prepareProfile, selectedProfile, features.readiness, room.id, room.privacyModeLocked, networkEpoch) {
        val profile = selectedProfile ?: return@LaunchedEffect
        if (prepareProfile) runtimeOwner.prepareProfile(profile, features.readiness, room.id, room.privacyModeLocked)
    }
    LaunchedEffect(session, state.reply, state.awaitingReply, performance, speakWhileGenerating, foregroundVoice) {
        if (!foregroundVoice) return@LaunchedEffect
        while (true) {
            val remainingMs = session.nextSpeechFlushDelayMs() ?: break
            delay(remainingMs)
            session.flushSpeech()
            // An oversized word needs another provider token before the buffer can advance.
            if (session.nextSpeechFlushDelayMs() == 0L) break
        }
    }
    val localSpeech = backend.backend != VoiceSpeechBackend.GATEWAY_CUDA
    val prepareSpeech = foregroundVoice &&
        preferencesApplied &&
        spokenReplies &&
        selectedProfile != null &&
        backend.loaded &&
        backend.speechModelReady(kokoroModel) &&
        LocalVoiceModelStore.supportsLanguage(language) &&
        (localSpeech || voiceReady) &&
        (!chatView || state.awaitingReply)
    val preparation = if (prepareSpeech) {
        VoicePreparationPolicy.stage(
            state.phase,
            state.awaitingReply,
            state.hasDraft,
            localSpeech,
            admission?.stage == VoiceAdmissionStage.ACCEPTED,
            openingMicrophone = listeningEnabled && !state.muted && (recognitionReady || localModel.status == LocalVoiceModelStatus.CHECKING)
        )
    } else {
        VoicePreparation.NONE
    }
    // STARTING may cancel an earlier output as the microphone takes ownership. Re-arm the
    // asset stage there, then queue native preparation once capture owns the exclusive gate.
    LaunchedEffect(kokoroOutput, preparation, state.phase, kokoroVoice, rate, kokoroThreads, performance, backend.factory, enabled, loaded, roomReady, language) {
        val voice = kokoroVoice
        if (preparation != VoicePreparation.NONE && voice != null && benchmarkOwnership.value == null) {
            kokoroOutput?.prewarm(voice, assetsOnly = preparation == VoicePreparation.ASSETS)
        } else {
            kokoroOutput?.cancelPrewarm()
            if (!enabled || !roomReady || !LocalVoiceModelStore.supportsLanguage(language)) kokoroOutput?.releaseWarmModel()
        }
    }
    LaunchedEffect(settingsOpen, routeOpen, recognitionReady, preferencesApplied, listeningEnabled, benchmarkRunning) {
        if (!preferencesApplied || benchmarkOwnership.value != null) return@LaunchedEffect
        applyAudioConfiguration()
        if (voiceReady && recognitionReady && !settingsOpen && !routeOpen && listeningEnabled && started && !state.muted && state.recoveryAction == null && lifecycleState == Lifecycle.State.RESUMED) {
            if (state.phase == VoicePhase.PAUSED) session.resume()
        }
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = granted
        session.access(currentEnabled, granted, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        resumeWhenReady = granted
        if (!granted) session.permissionDenied()
    }
    fun listen(manual: Boolean = false) {
        session.holdAudio(benchmarkOwnership.value != null)
        if (benchmarkOwnership.value != null || !enabled || !voiceReady || !preferencesApplied || !recognitionReady && !state.hasDraft) return
        applyAudioConfiguration()
        permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        session.access(enabled && preferencesApplied && loaded && roomReady && backend.loaded, permission, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        if (permission) {
            if (manual && state.phase in setOf(VoicePhase.PAUSED, VoicePhase.ERROR) && !state.hasDraft) localRecognition.armManualCaptureFeedback()
            session.resume()
            localRecognition.clearManualCaptureFeedback()
        } else {
            permissionRequest.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    fun savePreferences() {
        voiceSettings.save(VoicePreferences(language = language, captions = captions, review = editBeforeSend, rate = rate, kokoroVoice = kokoroVoice, spokenReplies = spokenReplies, kokoroThreads = kokoroThreads, buttonSounds = buttonSounds, pause = pause, performance = performance, speakWhileGenerating = speakWhileGenerating, kokoroBucket = kokoroBucket, incrementalSpeech = incrementalSpeech, nativeModelVariant = nativeModelVariant, tuning = tuning))
    }
    fun previewSpeech(selected: KokoroVoice, report: (String?) -> Unit) {
        audio.stop()
        backendOwner.configureSpeech(selected, rate)
        val selectedBackend = backendOwner.state.value
        if (!selectedBackend.loaded) {
            report("Speech settings are updating. Try the preview again shortly.")
        } else if (kokoroOutput == null) {
            report("Speech playback is unavailable on this device.")
        } else {
            report(null)
            kokoroOutput.configureEngine(selectedBackend.factory)
            playKokoroPreview(kokoroOutput, selected, ::applyAudioConfiguration) { message, _ -> report(message) }
        }
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
    fun leaveTranscript() {
        if (preferencesApplied) savePreferences()
        session.close()
        model.leaveConversation(onEnd)
    }
    fun pauseCapture() {
        listeningEnabled = false
        session.autoContinue = false
        if (state.phase in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING)) session.pause("transcript_opened")
    }
    fun openChat() {
        if (preferencesApplied) savePreferences()
        pauseCapture()
        chatView = true
    }
    fun closeSettings() {
        benchmarkOwner.cancel()
        audio.stop()
        savePreferences()
        settingsOpen = false
    }
    BackHandler {
        if (settingsOpen) {
            closeSettings()
        } else if (chatView) {
            leaveTranscript()
        } else {
            end()
        }
    }
    DisposableEffect(lifecycle, session, benchmarkOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    runtimeOwner.resume()
                    permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    session.holdAudio(benchmarkOwnership.value != null)
                    session.access(currentEnabled, permission, true)
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    if (event == Lifecycle.Event.ON_STOP) benchmarkOwner.cancel()
                    refreshMutedBaseline = true
                    kokoroOutput?.cancelPrewarm()
                    session.access(currentEnabled, permission, false)
                    systemAudio.releaseRecognition()
                    runtimeOwner.pause(runtime)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            benchmarkOwner.cancel()
            refreshMutedBaseline = true
            kokoroOutput?.cancelPrewarm()
            session.access(currentEnabled, permission, false)
            systemAudio.releaseRecognition()
            runtimeOwner.pause(runtime)
        }
    }
    LaunchedEffect(enabled, voiceReady, preferencesApplied, lifecycleState, resumeWhenReady, recognitionReady, recognitionBackend, settingsOpen, listeningEnabled, benchmarkRunning) {
        session.holdAudio(benchmarkOwnership.value != null)
        session.access(enabled && preferencesApplied && loaded && roomReady && backend.loaded, permission, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        if (benchmarkOwnership.value == null && enabled && voiceReady && preferencesApplied && recognitionReady && listeningEnabled && !settingsOpen && !routeOpen && state.recoveryAction == null && lifecycleState == Lifecycle.State.RESUMED && (!started || resumeWhenReady || !state.muted && state.phase == VoicePhase.PAUSED)) {
            started = true
            resumeWhenReady = false
            listen()
        }
    }
    LaunchedEffect(admission) {
        val accepted = admission ?: return@LaunchedEffect
        session.admission(accepted.stage, accepted.reason, accepted.requestId)
    }
    LaunchedEffect(loaded, roomReady, messages, runs, admission, toolEvents, enabled, backend.loaded, spokenReplies, speakWhileGenerating, preferencesApplied, lifecycleState, state.phase, state.awaitingReply, settingsOpen, routeOpen, refreshMutedBaseline, playbackTick, benchmarkRunning, approvals.isEmpty(), inputs.isEmpty(), consent, chatView) {
        if (!loaded || !roomReady || benchmarkOwnership.value != null) return@LaunchedEffect
        // Restoration publishes messages before loaded=true. Read the current flow snapshot so
        // separate Compose collectors cannot accidentally prime from an empty history.
        val messageSnapshot = model.groupedMessages.value
        val runSnapshot = model.agentRunsById.value
        val replies = replyProjection.update(messageSnapshot.assistantMessages, runSnapshot) { runSnapshot[it]?.status }
        val foregroundOutput = enabled && preferencesApplied && backend.loaded && lifecycleState == Lifecycle.State.RESUMED && !settingsOpen && !routeOpen
        playback.observe(replies, autoplay = spokenReplies && foregroundOutput && !refreshMutedBaseline)
        playbackPrimed = playback.initialized
        if (!foregroundOutput) return@LaunchedEffect
        refreshMutedBaseline = false
        playback.retireSuperseded()?.let { identity ->
            session.supersedeMessage(identity.key)
            activeMessage = null
            manualMessage = false
        }
        val currentSession = session.states.value
        if (manualMessage) {
            if (currentSession.awaitingReply || currentSession.phase in setOf(VoicePhase.PREPARING, VoicePhase.SPEAKING)) return@LaunchedEffect
            manualMessage = false
            activeMessage = null
            playback.finish()
        }
        if (playback.active != null && !currentSession.awaitingReply && currentSession.phase !in setOf(VoicePhase.PREPARING, VoicePhase.SPEAKING)) {
            playback.finish()
            activeMessage = null
        }
        if (playback.active == null) {
            val accepted = model.voiceAdmission.value
            val run = accepted?.takeIf {
                it.stage == VoiceAdmissionStage.ACCEPTED && it.chatId == room.id && it.profileUid == selectedProfile?.uid
            }?.run(runSnapshot)
            val lookup = if (run != null &&
                run.status == AgentRunStatus.RUNNING &&
                currentSession.awaitingReply &&
                currentSession.phase == VoicePhase.THINKING &&
                spokenReplies &&
                speakWhileGenerating &&
                !chatView &&
                !room.privacyModeLocked &&
                approvals.isEmpty() &&
                inputs.isEmpty() &&
                consent == null
            ) {
                val prompt = messageSnapshot.userMessages.lastOrNull { it.id == run.userMessageId && it.chatId == run.chatId }?.content
                prompt?.takeIf { it == currentSession.transcript }?.let {
                    VoiceSearchOpening.line(it, run.runId, model.toolEventsByRun.value[run.runId].orEmpty())
                }
            } else {
                null
            }
            val identity = run?.takeIf { lookup != null }?.let { VoiceMessageIdentity(it.assistantMessageId, it.runId) }
            val next = playback.next(identity) ?: return@LaunchedEffect
            if (!session.beginMessage(next.identity.key, diagnosticRunId = next.identity.runId, readAloud = next.readAloud)) {
                playback.finish()
                return@LaunchedEffect
            }
            activeMessage = next.identity.takeIf { next.readAloud }
            retainMessage(next.identity)
            if (lookup != null && next.identity == identity && next.text.isBlank() && !next.complete && next.readAloud) {
                session.offerSearchOpening(next.identity.key, lookup)
            }
        }
        session.autoContinue = listeningEnabled && !playback.hasPending
        val pending = playback.active
        if (pending?.complete == true) {
            val observedMessage = replyProjection.message(pending.identity.messageId) ?: return@LaunchedEffect
            val speakerAtRead = session.states.value.speaker
            val reportReadFailure = session.terminalReadFailureReporter(pending.identity.key)
            val terminal = try {
                model.terminalVoiceReply(pending.identity.messageId, pending.identity.runId, observedMessage.ownershipFence)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (playback.active != pending || manualMessage) return@LaunchedEffect
                reportReadFailure(error)
                return@LaunchedEffect
            } ?: return@LaunchedEffect
            currentCoroutineContext().ensureActive()
            val currentMessage = model.groupedMessages.value.assistantMessages.flatten().firstOrNull { it.id == pending.identity.messageId }
            if (!enabled ||
                lifecycle.currentState != Lifecycle.State.RESUMED ||
                settingsOpen ||
                routeOpen ||
                benchmarkOwnership.value != null ||
                manualMessage ||
                session.states.value.speaker != speakerAtRead ||
                currentMessage == null ||
                currentMessage.currentRunId != pending.identity.runId ||
                currentMessage.ownershipFence != observedMessage.ownershipFence
            ) {
                return@LaunchedEffect
            }
            // Separate Room collectors may still expose an old blank/prefix row with a
            // new terminal status. Only the matching durable publication is delivered.
            playback.terminalUpdate(
                pending,
                dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject.withoutMetadata(terminal.message.content),
                failed = terminal.run.status != AgentRunStatus.COMPLETED
            )?.let { next ->
                val failure = terminal.message.timeline.lastOrNull { it.profileFailure != null }?.profileFailure
                    ?: terminal.run.terminalError?.let { dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.classify(it) }
                session.reply(next.identity.key, next.text, next.failed, failure, terminalStatus = terminal.run.status)
            }
        } else {
            playback.update()?.let { next -> session.streamReply(next.identity.key, next.text) }
        }
        val deliveredState = session.states.value
        if (!deliveredState.awaitingReply && deliveredState.phase !in setOf(VoicePhase.PREPARING, VoicePhase.SPEAKING)) {
            playback.finish()
            activeMessage = null
            // Silent completions may begin and end PAUSED in one main-thread turn. Advance
            // queued responses explicitly instead of relying on a transient Compose phase.
            if (playback.hasPending) playbackTick++
        }
    }
    val waitingApproval = approvals.isNotEmpty() || inputs.isNotEmpty() || consent != null
    val profileName = profiles.firstOrNull { it.uid in activeProfiles }?.let { it.name.ifBlank { it.model } } ?: "Your AI profile"
    val status = when {
        !enabled -> "Voice chat is turned off"
        state.phase == VoicePhase.PREPARING -> "Preparing voice"
        state.phase == VoicePhase.SPEAKING -> "Speaking"
        profilePreflightError != null -> "AI profile needs attention"
        !voiceReady || !preferencesApplied -> "Getting ready"
        waitingApproval -> "Your attention is needed"
        state.phase == VoicePhase.REVIEWING -> "Review your message"
        !recognitionReady -> "Set up local speech"
        remaining > 0 -> "Retry in $remaining seconds"
        state.recoveryAction != null -> "Speech setup needs attention"
        state.phase == VoicePhase.STARTING -> "Starting microphone"
        state.phase == VoicePhase.FINALIZING -> "Finishing your words"
        state.phase == VoicePhase.THINKING && admission?.stage == VoiceAdmissionStage.CHECKING -> "Checking your AI profile"
        state.phase == VoicePhase.THINKING && admission?.stage == VoiceAdmissionStage.QUEUED -> "Message queued"
        state.phase == VoicePhase.THINKING -> "Thinking"
        state.muted -> "Microphone muted"
        state.phase == VoicePhase.LISTENING -> "Listening"
        state.phase == VoicePhase.ERROR -> "Let’s try again"
        else -> "Ready when you are"
    }
    fun stopMessage() {
        session.stopSpeaking()
        activeMessage = null
    }
    fun toggleMicrophone() {
        session.holdAudio(benchmarkOwnership.value != null)
        if (benchmarkOwnership.value != null) return
        if (state.phase in setOf(VoicePhase.PREPARING, VoicePhase.SPEAKING)) {
            stopMessage()
            if (buttonSounds) feedback.stop()
        } else if (state.phase in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING)) {
            listeningEnabled = false
            session.mute()
            if (buttonSounds) feedback.stop()
        } else {
            listeningEnabled = true
            session.autoContinue = true
            if (state.muted) session.mute()
            listen(manual = true)
        }
    }
    fun readResponse(identity: VoiceMessageIdentity, text: String, includeDetails: Boolean = false) {
        session.holdAudio(benchmarkOwnership.value != null)
        if (benchmarkOwnership.value != null || text.isBlank()) return
        pauseCapture()
        session.access(enabled && preferencesApplied && loaded && roomReady && backend.loaded, permission, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        if (session.beginMessage(identity.key, manual = true, diagnosticRunId = identity.runId.ifBlank { identity.key }, includeDetails = includeDetails)) {
            playback.manual(identity)
            manualMessage = true
            activeMessage = identity
            retainMessage(identity)
            session.reply(identity.key, text)
        }
    }
    val latestRead = rememberUpdatedState<(dev.chungjungsoo.gptmobile.data.database.entity.MessageV2, String) -> Unit> { message, text ->
        readResponse(message.voiceIdentity(), text)
    }
    val latestReadDetails = rememberUpdatedState<(dev.chungjungsoo.gptmobile.data.database.entity.MessageV2, String) -> Unit> { message, text ->
        readResponse(message.voiceIdentity(), text, includeDetails = true)
    }
    val latestStop = rememberUpdatedState(::stopMessage)
    val latestPauseCapture = rememberUpdatedState(::pauseCapture)
    val outputAvailable = !benchmarkRunning && enabled && preferencesApplied && loaded && roomReady && backend.speechModelReady(kokoroModel)
    // Meter ticks do not replace controls or invalidate every transcript message.
    val messageControls = remember(activeMessage, state.phase, outputAvailable) {
        VoiceMessageControls(
            active = activeMessage,
            phase = state.phase,
            available = outputAvailable,
            read = { message, text -> latestRead.value(message, text) },
            stop = { latestStop.value() },
            pauseCapture = { latestPauseCapture.value() },
            readDetails = { message, text -> latestReadDetails.value(message, text) }
        )
    }
    VoiceHealthObservation(session.performanceHistory, sampleMemory = settingsOpen)
    VoiceChatSessionPresentation(
        settingsOpen = settingsOpen,
        chatView = chatView,
        routeOpen = routeOpen,
        settings = {
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
                    buttonSounds = buttonSounds,
                    kokoroModel = kokoroModel,
                    kokoroThreads = kokoroThreads,
                    rate = rate,
                    pause = pause,
                    performance = performance,
                    speakWhileGenerating = speakWhileGenerating,
                    kokoroBucket = kokoroBucket,
                    incrementalSpeech = incrementalSpeech,
                    nativeModelVariant = nativeModelVariant,
                    modelChangeRunning = modelChangeRunning,
                    modelChangeError = modelChangeError,
                    benchmarkRunning = benchmarkRunning,
                    speechBackend = backend.backend
                ),
                actions = VoiceSettingsActions(
                    done = ::closeSettings,
                    selectProfile = { uid ->
                        session.pause("profile_changed")
                        model.selectVoiceProfile(uid) { changed -> if (changed) closeSettings() }
                    },
                    installLocalModel = voiceSettings::installLocalModel,
                    cancelLocalModelInstall = voiceSettings::cancelLocalModelInstall,
                    removeLocalModel = {
                        session.pause("model_removed")
                        localRecognition.releaseWarmModel(voiceSettings::removeLocalModel)
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
                    changeButtonSounds = {
                        buttonSounds = it
                        savePreferences()
                    },
                    selectKokoroVoice = {
                        audio.stop()
                        kokoroVoice = it
                        audio.kokoroVoice = it
                        backendOwner.configureSpeech(it, rate)
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
                    selectNativeModelVariant = { selected ->
                        if (selected != nativeModelVariant && !modelChangeRunning) {
                            modelChangeRunning = true
                            modelChangeError = null
                            settingsScope.launch {
                                try {
                                    audio.stop()
                                    if (runtimeOwner.retireForModelChange()) {
                                        nativeModelVariant = selected
                                        backendOwner.configureSpeech(kokoroVoice, rate, kokoroBucket, incrementalSpeech, selected)
                                        savePreferences()
                                        voiceSettings.selectKokoroVariant(selected)
                                    } else {
                                        modelChangeError = "The previous speech engine has not retired. Wait and retry, or restart the app before changing models."
                                    }
                                } finally {
                                    modelChangeRunning = false
                                }
                            }
                        }
                    },
                    previewKokoro = ::previewSpeech,
                    changeRate = {
                        audio.stop()
                        rate = it
                        backendOwner.configureSpeech(kokoroVoice, rate, kokoroBucket, incrementalSpeech, nativeModelVariant)
                        savePreferences()
                    },
                    changePause = {
                        pause = it
                        savePreferences()
                    },
                    changePerformance = {
                        performance = it
                        savePreferences()
                    },
                    useConversationalFast = {
                        audio.stop()
                        rate = 1.25f
                        backendOwner.configureSpeech(kokoroVoice, rate, kokoroBucket, incrementalSpeech, nativeModelVariant)
                        pause = VoicePauseDuration.SHORT
                        performance = VoicePerformanceMode.FAST
                        speakWhileGenerating = true
                        savePreferences()
                    },
                    useNaturalSpeech = {
                        audio.stop()
                        rate = STANDARD_SPEECH_RATE
                        backendOwner.configureSpeech(kokoroVoice, rate, kokoroBucket, incrementalSpeech, nativeModelVariant)
                        pause = VoicePauseDuration.NATURAL
                        performance = VoicePerformanceMode.AUTO
                        speakWhileGenerating = true
                        savePreferences()
                    },
                    changeSpeakWhileGenerating = {
                        speakWhileGenerating = it
                        savePreferences()
                    },
                    changeKokoroBucket = {
                        audio.stop()
                        kokoroBucket = it
                        backendOwner.configureSpeech(kokoroVoice, rate, kokoroBucket, incrementalSpeech, nativeModelVariant)
                        savePreferences()
                    },
                    changeIncrementalSpeech = {
                        audio.stop()
                        incrementalSpeech = it
                        backendOwner.configureSpeech(kokoroVoice, rate, kokoroBucket, incrementalSpeech, nativeModelVariant)
                        savePreferences()
                    }
                ),
                engineContent = {
                    VoiceSpeechBackendSettings(
                        value = backend,
                        selectBackend = {
                            audio.stop()
                            kokoroOutput?.releaseWarmModel()
                            backendOwner.selectBackend(it)
                        },
                        selectGateway = backendOwner::selectGateway,
                        recheckBackend = backendOwner::recheckBackend,
                        enabled = !benchmarkRunning && state.phase !in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING, VoicePhase.PREPARING, VoicePhase.SPEAKING)
                    )
                    backend.backend.streamingEngine()?.let { engine ->
                        var streamingPreviewError by remember(engine) { mutableStateOf<String?>(null) }
                        StreamingVoiceModelCard(
                            engine = engine,
                            state = backend.streamingModels[engine],
                            voice = kokoroVoice,
                            enabled = backend.loaded && !benchmarkRunning && !state.awaitingReply && state.phase !in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING, VoicePhase.PREPARING, VoicePhase.SPEAKING),
                            selectVoice = {
                                audio.stop()
                                kokoroVoice = it
                                audio.kokoroVoice = it
                                backendOwner.configureSpeech(it, rate)
                                savePreferences()
                            },
                            install = backendOwner::installStreamingModel,
                            importCompiled = backendOwner::importSm8750Model,
                            cancel = backendOwner::cancelStreamingInstall,
                            remove = {
                                audio.stop()
                                kokoroOutput?.releaseWarmModel()
                                backendOwner.removeStreamingModel()
                            },
                            preview = {
                                previewSpeech(kokoroVoice ?: KokoroVoice.HEART) { streamingPreviewError = it }
                            },
                            previewError = streamingPreviewError
                        )
                    }
                },
                tuningContent = {
                    VoiceSpeechBenchmarkSettings(
                        voice = kokoroVoice,
                        threads = kokoroThreads,
                        speechRate = rate,
                        enabled = !benchmarkRunning && !state.awaitingReply && state.phase !in setOf(VoicePhase.STARTING, VoicePhase.LISTENING, VoicePhase.FINALIZING, VoicePhase.PREPARING, VoicePhase.SPEAKING),
                        beforeStart = {
                            session.pause("speech_benchmark_started")
                            localRecognition.releaseWarmModel()
                            kokoroOutput?.releaseWarmModel()
                        },
                        captionOffsetMs = tuning.captionOffsetMs,
                        viewModel = benchmarkOwner
                    )
                    if (!benchmarkRunning) {
                        VoiceTuningSettings(
                            tuning,
                            {
                                tuning = it
                                savePreferences()
                            },
                            session.performanceHistory,
                            kokoroOutput?.synthesisFeedback,
                            if (backend.backend.supportsCpuThreadCalibration && kokoroOutput != null && kokoroVoice != null && kokoroModel.status == LocalVoiceModelStatus.READY) {
                                { completed -> kokoroOutput.benchmark(requireNotNull(kokoroVoice), completed) }
                            } else {
                                null
                            },
                            adjustCaptionOffset = { milliseconds ->
                                kokoroOutput?.configureCaptionOffset(milliseconds)
                                tuning = tuning.copy(captionOffsetMs = milliseconds)
                                savePreferences()
                            },
                            supportsCpuThreads = !backend.backend.isStreamingLocal
                        )
                    }
                }
            )
        },
        transcript = {
            VoiceTranscriptScreen(
                model = model,
                profileName = profileName,
                status = status,
                session = state,
                captions = captions,
                controls = messageControls,
                spokenReplies = spokenReplies,
                changeSpokenReplies = ::setSpokenReplies,
                showVoice = {
                    chatView = false
                    listeningEnabled = true
                    listen()
                },
                microphone = ::toggleMicrophone,
                audioRoute = {
                    session.pause("audio_route_opened")
                    routeOpen = true
                },
                settings = {
                    session.pause("settings_opened")
                    settingsOpen = true
                },
                end = ::leaveTranscript,
                destinations = destinations,
                bottomInteractionContent = bottomInteractionContent,
                captionCursor = session.captionCursors,
                doneSpeaking = session::finishListening
            )
        },
        conversation = {
            VoiceConversationScreen(
                state = VoiceConversationUiState(
                    profileName = profileName,
                    profiles = profiles.filter { it.enabled },
                    selectedProfileId = selectedProfile?.uid,
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
                    draft = draft,
                    outputLevel = kokoroOutput?.outputLevel,
                    inputLevel = session.inputLevels,
                    captionCursor = session.captionCursors,
                    handsFreeInterruption = tuning.handsFreeInterruption,
                    interruptionState = audio.interruptionState
                ),
                actions = VoiceConversationActions(
                    selectProfile = { uid ->
                        session.pause("profile_changed")
                        model.selectVoiceProfile(uid) {}
                    },
                    openTranscript = { chatView = true },
                    openSettings = {
                        val currentState = session.states.value
                        val currentAdmission = model.voiceAdmission.value
                        if (currentState.shouldCheckpointDraft(currentAdmission?.requestId, currentAdmission?.stage)) model.checkpointVoiceDraft(currentState.transcript)
                        session.pause("settings_opened")
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
                    listen = { listen() },
                    recordAgain = {
                        session.discardDraft()
                        listen()
                    },
                    repair = { action ->
                        session.pause("repair_settings_opened")
                        if (action == VoiceRecoveryAction.LOCAL_MODEL) settingsOpen = true else openVoiceRepairSettings(context, action)
                    },
                    openVoiceConfiguration = {
                        session.pause("settings_opened")
                        settingsOpen = true
                    },
                    replay = { readResponse(VoiceMessageIdentity(retainedMessageId, retainedRunId, retainedRevision), state.reply) },
                    openChat = ::openChat,
                    toggleMicrophone = ::toggleMicrophone,
                    toggleCaptions = {
                        if (preferencesApplied) {
                            captions = !captions
                            savePreferences()
                        }
                    },
                    openAudioRoute = {
                        session.pause("audio_route_opened")
                        if (buttonSounds) feedback.click()
                        routeOpen = true
                    },
                    end = ::end,
                    doneSpeaking = session::finishListening
                )
            )
        },
        audioRoute = {
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
    )
}

@Composable
private fun VoiceChatSessionPresentation(
    settingsOpen: Boolean,
    chatView: Boolean,
    routeOpen: Boolean,
    settings: @Composable () -> Unit,
    transcript: @Composable () -> Unit,
    conversation: @Composable () -> Unit,
    audioRoute: @Composable () -> Unit
) {
    if (settingsOpen) {
        settings()
        return
    }
    if (chatView) {
        transcript()
    } else {
        conversation()
    }
    if (routeOpen) audioRoute()
}

internal fun playKokoroPreview(output: KokoroOutput, voice: KokoroVoice, configure: () -> Unit = {}, error: (String, Boolean) -> Unit) {
    // A settings click can precede Compose's next SideEffect. Apply its rate before the
    // deferred output is initialized or compared with an SDK task's immutable rate.
    configure()
    // Settings previews do not belong to the previous conversation reply.
    output.bindDiagnostics(null)
    output.bindReply("")
    output.speak("Hello. This is your selected voice. I am ready when you are.", voice, {}, error)
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
    val buttonSounds: Boolean,
    val kokoroModel: LocalVoiceModelState,
    val kokoroThreads: Int,
    val rate: Float,
    val pause: VoicePauseDuration,
    val performance: VoicePerformanceMode,
    val speakWhileGenerating: Boolean,
    val kokoroBucket: MediaPipeKokoroBucket,
    val incrementalSpeech: Boolean,
    val nativeModelVariant: KokoroModelVariant,
    val modelChangeRunning: Boolean,
    val modelChangeError: String?,
    val benchmarkRunning: Boolean = false,
    val speechBackend: VoiceSpeechBackend = VoiceSpeechBackend.DEFAULT
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
    val changeButtonSounds: (Boolean) -> Unit,
    val selectKokoroVoice: (KokoroVoice?) -> Unit,
    val selectKokoroThreads: (Int) -> Unit,
    val installKokoroModel: () -> Unit,
    val cancelKokoroInstall: () -> Unit,
    val removeKokoroModel: () -> Unit,
    val selectNativeModelVariant: (KokoroModelVariant) -> Unit,
    val previewKokoro: (KokoroVoice, (String?) -> Unit) -> Unit,
    val changeRate: (Float) -> Unit,
    val changePause: (VoicePauseDuration) -> Unit,
    val changePerformance: (VoicePerformanceMode) -> Unit,
    val useConversationalFast: () -> Unit,
    val useNaturalSpeech: () -> Unit,
    val changeSpeakWhileGenerating: (Boolean) -> Unit,
    val changeKokoroBucket: (MediaPipeKokoroBucket) -> Unit,
    val changeIncrementalSpeech: (Boolean) -> Unit
)

@Composable
private fun VoiceSettingsScreen(
    state: VoiceSettingsUiState,
    actions: VoiceSettingsActions,
    engineContent: @Composable () -> Unit,
    tuningContent: @Composable () -> Unit
) {
    var previewError by remember { mutableStateOf<String?>(null) }
    VoiceSettingsPage(actions.done) {
        if (state.benchmarkRunning) {
            Text("Speech benchmark is running. Playback and voice settings are paused until it finishes or you stop it.", style = MaterialTheme.typography.bodyMedium)
        } else if (state.modelChangeRunning) {
            Text("Finishing the previous speech engine before changing models…", style = MaterialTheme.typography.bodyMedium)
            CircularProgressIndicator()
        } else {
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
            state.modelChangeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            VoiceSettingsSection("Speech recognition")
            LocalVoiceSetupCard(state.localModel, actions.installLocalModel, actions.cancelLocalModelInstall, actions.removeLocalModel, showRemove = true)
            val regions = remember(state.language) {
                (Locale.getISOCountries().map { "en-$it" } + state.language).distinct().sortedBy {
                    Locale.forLanguageTag(it).displayCountry
                }.map { it to Locale.forLanguageTag(it).displayCountry.ifBlank { "English (default)" } }
            }
            VoiceChoice("Country or region", state.language, regions, actions.changeLanguage)
            VoiceSettingsSection("Conversation")
            Text("Replies begin with a short spoken answer. Longer details stay available in chat.", style = MaterialTheme.typography.bodySmall)
            VoiceSettingSwitch("Review before sending", state.editBeforeSend, actions.changeReview)
            VoiceSettingSwitch("Live captions", state.captions, actions.changeCaptions)
            VoiceSettingSwitch("Spoken replies", state.spokenReplies, actions.changeSpokenReplies)
            VoiceSettingSwitch("Button sounds", state.buttonSounds, actions.changeButtonSounds)
            VoiceSettingsSection("Playback")
            engineContent()
            if (!state.speechBackend.isStreamingLocal) {
                if (state.speechBackend != VoiceSpeechBackend.GATEWAY_CUDA) {
                    VoiceChoice("Native Kokoro model", state.nativeModelVariant.name, KokoroModelVariant.entries.map { it.name to it.displayName }, { actions.selectNativeModelVariant(KokoroModelVariant.valueOf(it)) })
                    Text("Each model is installed separately. Original FP32 uses more storage and memory; compare CPU, GPU and NPU with the same selected model before choosing an accelerator.", style = MaterialTheme.typography.bodySmall)
                }
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
                    preview = { selected -> actions.previewKokoro(selected) { previewError = it } },
                    showThreads = state.speechBackend.supportsCpuThreadCalibration
                )
            }
            if (state.speechBackend.isStreamingLocal) {
                VoiceSettingSwitch("Keep one speech stream for each reply", state.incrementalSpeech, actions.changeIncrementalSpeech)
                Text("New text joins the current stream as it arrives. Turn this off to compare separate phrases in the speech benchmark.", style = MaterialTheme.typography.bodySmall)
                if (state.speechBackend.streamingEngine()?.isKokoro == true) {
                    VoiceChoice("Kokoro phrase capacity · experiment", state.kokoroBucket.name, MediaPipeKokoroBucket.entries.map { it.name to it.label }, { actions.changeKokoroBucket(MediaPipeKokoroBucket.valueOf(it)) })
                    Text("Compare capacities with the same voice and text. Model default lets the runtime choose; a smaller capacity may help short phrases. The complete reply remains queued.", style = MaterialTheme.typography.bodySmall)
                }
            }
            var sliderRate by remember(state.rate) { androidx.compose.runtime.mutableFloatStateOf(state.rate) }
            Text("Speech rate · ${String.format(LocalLocale.current.platformLocale, "%.2f", sliderRate)}×")
            Text("Speech rate changes how quickly the voice talks. Performance mode changes how soon speech starts.", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = sliderRate,
                onValueChange = { sliderRate = it },
                valueRange = .5f..2f,
                steps = 29,
                onValueChangeFinished = {
                    val snapped = Math.round(sliderRate * 20) / 20f
                    sliderRate = snapped
                    if (snapped != state.rate) actions.changeRate(snapped)
                }
            )
            OutlinedButton(onClick = { actions.changeRate(STANDARD_SPEECH_RATE) }, enabled = state.rate != STANDARD_SPEECH_RATE) { Text("Reset to 1×") }
            VoiceChoice("Pause duration", state.pause.name, VoicePauseDuration.entries.map { it.name to it.label }, { actions.changePause(VoicePauseDuration.valueOf(it)) })
            VoiceChoice("Performance mode", state.performance.name, VoicePerformanceMode.entries.map { it.name to it.label }, { actions.changePerformance(VoicePerformanceMode.valueOf(it)) })
            VoiceSettingSwitch("Speak while the reply is generating", state.speakWhileGenerating, actions.changeSpeakWhileGenerating)
            OutlinedButton(onClick = actions.useNaturalSpeech) { Text("Use Natural Speech") }
            OutlinedButton(onClick = actions.useConversationalFast) { Text("Use Conversational Fast") }
        }
        tuningContent()
    }
}

@Composable
internal fun VoiceChoice(
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
            DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
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
internal fun VoiceSettingSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Switch(checked, change)
    }
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
internal fun VoiceSettingsSection(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
