package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechCatalog
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechEngine
import dev.chungjungsoo.gptmobile.data.voice.VoicePluginInstall
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionCompute
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionConfiguration
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionModel
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import dev.chungjungsoo.gptmobile.presentation.common.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal fun NavGraphBuilder.voiceNavigation(navController: NavHostController, onReportDownload: () -> Unit = {}, origin: () -> Offset) {
    composable(Route.VOICE_ENTRY, enterTransition = { fadeIn(tween(150)) }, exitTransition = { fadeOut(tween(150)) }) {
        val model: VoiceEntryViewModel = hiltViewModel()
        val state by model.state.collectAsStateWithLifecycle()
        val modelPreparation by model.gatewayModelPreparation.collectAsStateWithLifecycle()
        LaunchedEffect(state.route) {
            state.route?.let { route -> navController.navigate(route) { popUpTo(Route.VOICE_ENTRY) { inclusive = true } } }
        }
        val voiceSettings: VoicePreferencesViewModel = hiltViewModel()
        val backendOwner: VoiceBackendViewModel = hiltViewModel()
        val backend by backendOwner.state.collectAsStateWithLifecycle()
        val preferences by voiceSettings.preferences.collectAsStateWithLifecycle()
        LaunchedEffect(backendOwner, preferences) {
            preferences?.let { backendOwner.configureSpeech(it.synthesis.nativeVoice(it.kokoroVoice), it.rate, it.kokoroBucket, it.incrementalSpeech, it.nativeModelVariant, it.synthesis) }
        }
        val stores by voiceSettings.modelStores.collectAsStateWithLifecycle()
        val moonshine = stores?.moonshine?.state?.collectAsStateWithLifecycle()?.value
        val recognitionConfiguration = preferences?.recognitionConfiguration ?: VoiceRecognitionConfiguration()
        val recognitionStore = stores?.recognition?.store(recognitionConfiguration)
        val recognition = recognitionStore?.state?.collectAsStateWithLifecycle()?.value
        LaunchedEffect(recognitionStore) { recognitionStore?.refresh(forceVerification = false) }
        val kokoro = stores?.kokoro?.state?.collectAsStateWithLifecycle()?.value
        val defaultStreaming = stores?.defaultStreaming?.state?.collectAsStateWithLifecycle()?.value
        val context = LocalContext.current
        val androidRecognition = preferences?.recognitionBackend == VoiceRecognitionBackend.ANDROID_SPEECH_SERVICE
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
        val offlineService = androidRecognition &&
            androidx.compose.runtime.remember(context, lifecycleState) {
                android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            }
        val modelsReady = preferences != null &&
            (androidRecognition || recognitionConfiguration.model.supportsLanguage(preferences?.language.orEmpty())) &&
            voiceEntryModelsReady(recognition, kokoro, backend, preferences?.spokenReplies != false, if (androidRecognition) offlineService else null)
        val installation by VoicePluginInstall.state.collectAsStateWithLifecycle()
        LaunchedEffect(voiceSettings, lifecycle, state.loaded, state.route) {
            if (state.loaded && state.route == null) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { voiceSettings.onVoiceLaunch() }
            }
        }
        LaunchedEffect(context, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    VoicePluginInstall.refresh(context)
                    delay(5_000)
                }
            }
        }
        VoiceEntryScreen(
            state,
            origin(),
            model::select,
            { if (modelsReady) model.start(enable = true) },
            { navController.navigateUp() },
            { navController.navigate(Route.AI_PLATFORMS) },
            modelPreparation = modelPreparation[state.selected],
            recheckModel = { model.recheckGatewayModel(state.selected) },
            modelsReady = modelsReady,
            showInstallation = installation.pending || installation.phase in setOf("FAILED", "CANCELLED"),
            setup = {
                preferences?.let { saved ->
                    VoiceRecognitionModelChoices(
                        saved.recognitionModel,
                        saved.recognitionCompute,
                        saved.recognitionThreads,
                        saved.language,
                        { selected -> voiceSettings.save(saved.copy(recognitionModel = selected, recognitionCompute = VoiceRecognitionCompute.CPU, recognitionBackend = VoiceRecognitionBackend.LOCAL_MOONSHINE)) },
                        { selected -> voiceSettings.save(saved.copy(recognitionCompute = selected)) },
                        { selected -> voiceSettings.save(saved.copy(recognitionThreads = selected)) }
                    )
                    RecognitionLanguageChoice(saved.language, saved.recognitionBackend, saved.recognitionModel) { tag, engine ->
                        voiceSettings.save(saved.copy(language = tag, recognitionBackend = engine))
                    }
                    if (androidRecognition && !offlineService) androidx.compose.material3.Text("Android offline recognition is unavailable on this phone. Choose a local model that supports your recognition language.")
                    if (!androidRecognition && saved.recognitionModel != VoiceRecognitionModel.MOONSHINE_TINY && recognition != null) {
                        LocalVoiceSetupCard(
                            recognition,
                            { voiceSettings.installRecognitionModel(recognitionConfiguration) },
                            { voiceSettings.cancelRecognitionModelInstall(recognitionConfiguration) },
                            { voiceSettings.removeRecognitionModel(recognitionConfiguration) },
                            showRemove = true,
                            model = saved.recognitionModel,
                            compute = saved.recognitionCompute
                        )
                    }
                    if (backend.backend.usesKokoroVoices) {
                        SynthesisLanguageChoices(saved.synthesis, backend.backend.usesKokoroVoices) {
                            val native = it.nativeVoice(saved.kokoroVoice)
                            voiceSettings.save(saved.copy(synthesis = it, kokoroVoice = native))
                        }
                    }
                }
                VoiceEntrySpeechSettings(backend, backendOwner::selectBackend, backendOwner::selectGateway)
                if (moonshine != null && kokoro != null && defaultStreaming != null) {
                    val engine = backend.backend.streamingEngine()
                    val bundleSelected = recognitionConfiguration.model == VoiceRecognitionModel.MOONSHINE_TINY && preferences?.spokenReplies != false && !backend.backend.isCompiledPocket && (engine == null || engine == MediaPipeSpeechCatalog.DEFAULT_ENGINE)
                    if (installation.pending || bundleSelected) {
                        VoicePluginSetup(
                            moonshine,
                            kokoro,
                            defaultStreaming,
                            installation,
                            { stores?.let { VoicePluginInstall.start(context, it.kokoroVariant) } },
                            { VoicePluginInstall.cancel(context) }
                        ) {
                            VoicePluginInstall.refresh(context)
                            VoicePluginInstall.captureReportObservation(moonshine.status, kokoro.status, defaultStreaming.status)
                            onReportDownload()
                        }
                    } else if (recognitionConfiguration.model == VoiceRecognitionModel.MOONSHINE_TINY) {
                        LocalVoiceSetupCard(
                            moonshine,
                            voiceSettings::installLocalModel,
                            voiceSettings::cancelLocalModelInstall,
                            voiceSettings::removeLocalModel,
                            compact = true
                        )
                    }
                    if (!bundleSelected && !installation.pending && backend.factory.requiresKokoroAssets && preferences?.spokenReplies != false) {
                        val saved = preferences
                        KokoroPlaybackCard(
                            state = kokoro,
                            voice = saved?.kokoroVoice,
                            threads = saved?.kokoroThreads ?: dev.chungjungsoo.gptmobile.data.voice.VoiceCpuSchedulingPolicy.AUTO,
                            english = saved?.synthesis?.language?.startsWith("en") != false,
                            previewError = null,
                            selectThreads = { count -> saved?.let { voiceSettings.save(it.copy(kokoroThreads = count)) } },
                            install = voiceSettings::installKokoroModel,
                            cancel = voiceSettings::cancelKokoroInstall,
                            remove = voiceSettings::removeKokoroModel,
                            preview = {},
                            setup = true
                        )
                    }
                    if (engine != null && !bundleSelected && preferences?.spokenReplies != false) {
                        StreamingVoiceModelCard(
                            engine = engine,
                            state = backend.streamingModels[engine],
                            standardPack = backend.streamingModels[MediaPipeSpeechEngine.KOKORO],
                            preparingNpuBase = backend.preparingNpuBase,
                            npuBaseError = backend.npuBaseError,
                            voiceId = backend.streamingVoiceId,
                            selectVoice = backendOwner::selectStreamingVoice,
                            enabled = backend.loaded,
                            install = backendOwner::installStreamingModel,
                            importCompiled = backendOwner::importSm8750Model,
                            cancel = backendOwner::cancelStreamingInstall,
                            remove = backendOwner::removeStreamingModel,
                            preview = {},
                            previewError = null,
                            setup = true
                        )
                    }
                    if (backend.backend.isCompiledPocket && preferences?.spokenReplies != false) {
                        PocketCompiledModelCard(
                            state = backend.compiledPocketModel,
                            captionState = backend.pocketCaptionModel,
                            voiceId = backend.streamingVoiceId,
                            selectVoice = backendOwner::selectStreamingVoice,
                            enabled = backend.loaded && !backend.removingCompiledPocket,
                            install = backendOwner::installStreamingModel,
                            cancel = backendOwner::cancelStreamingInstall,
                            remove = backendOwner::removeStreamingModel,
                            setup = true
                        )
                    }
                }
            }
        )
    }
    composable(
        Route.VOICE_CHAT,
        arguments = listOf(navArgument("chatRoomId") { type = NavType.IntType }, navArgument("enabledPlatforms") { defaultValue = "" }),
        enterTransition = { fadeIn(tween(280)) },
        exitTransition = { fadeOut(tween(220)) }
    ) {
        val conversation: dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel = hiltViewModel()
        val room by conversation.chatRoom.collectAsStateWithLifecycle()
        val router: dev.chungjungsoo.gptmobile.presentation.ui.chat.ConversationRoutingViewModel = hiltViewModel()
        val routing by router.state.collectAsStateWithLifecycle()
        val availableConnections = routing?.let { current ->
            current.connections.filter { connection ->
                connection.type == dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType.GITHUB &&
                    current.features.isToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.GITHUB) &&
                    current.features.isToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.connection(connection.connectionUid))
            }.map { it.connectionUid }.toSet()
        }.orEmpty()
        dev.chungjungsoo.gptmobile.presentation.ui.workspace.GitHubConversationApprovalHost(
            chatId = room.id,
            availableConnectionUids = availableConnections,
            requestedTaskId = null,
            onApprovalPendingChanged = conversation::bindGitHubApprovalPending,
            onReviewEvidenceAccess = { navController.navigate(Route.TOOL_CONNECTIONS) }
        ) { approvalContent ->
            VoiceChatScreen(
                model = conversation,
                onEnd = { navController.navigateUp() },
                onNavigateToLocalModels = { navController.navigate(Route.LOCAL_MODELS + "?marketplace=true") },
                onReviewEvidenceAccess = { navController.navigate(Route.TOOL_CONNECTIONS) },
                onProfileSettings = { uid -> navController.navigate(Route.PLATFORM_SETTINGS.replace("{platformUid}", android.net.Uri.encode(uid))) },
                onOpenConversation = { roomId, regenerate -> navController.navigate("chat_room/$roomId?regenerate=$regenerate") },
                bottomInteractionContent = approvalContent
            )
        }
    }
}

/** Model availability follows the selected engine; SDK packs never depend on native Kokoro. */
internal fun VoiceBackendSettings.speechModelReady(nativePlayback: LocalVoiceModelState?): Boolean =
    loaded && factory.isReady() && (!factory.requiresKokoroAssets || nativePlayback?.status == LocalVoiceModelStatus.READY)

internal fun voiceEntryModelsReady(recognition: LocalVoiceModelState?, nativePlayback: LocalVoiceModelState?, backend: VoiceBackendSettings, spokenReplies: Boolean = true, androidRecognitionAvailable: Boolean? = null): Boolean =
    (androidRecognitionAvailable ?: (recognition?.status == LocalVoiceModelStatus.READY)) && (!spokenReplies || backend.speechModelReady(nativePlayback))

/** Entry setup exposes engine selection before any speech model download is required. */
@Composable
internal fun VoiceEntrySpeechSettings(value: VoiceBackendSettings, selectBackend: (VoiceSpeechBackend) -> Unit, selectGateway: (String) -> Unit) {
    VoiceChoice(
        "Speech engine",
        value.backend.name,
        VoiceSpeechBackend.entries.map { it.name to it.label },
        { selectBackend(VoiceSpeechBackend.valueOf(it)) },
        enabled = value.loaded
    )
    if (value.backend == VoiceSpeechBackend.GATEWAY_CUDA) {
        VoiceChoice("Speech Gateway", value.gatewayUid, value.gateways.map { it.uid to it.name }, selectGateway, enabled = value.loaded)
    }
    androidx.compose.material3.Text(value.status, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
}

@androidx.compose.runtime.Composable
internal fun VoicePluginSetup(
    recognition: dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState,
    playback: dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState,
    streaming: dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState,
    installation: dev.chungjungsoo.gptmobile.data.voice.VoiceInstallState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onReportDownload: () -> Unit = {}
) {
    val context = LocalContext.current
    val models = listOf(recognition, streaming, playback)
    val total = models.sumOf { it.totalBytes }
    val received = models.sumOf { it.downloadedBytes }
    val active = models.firstOrNull { it.status == LocalVoiceModelStatus.DOWNLOADING }
    val checking = models.any { it.status == LocalVoiceModelStatus.CHECKING }
    val message = models.firstNotNullOfOrNull { it.message }
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val ready = models.all { it.status == LocalVoiceModelStatus.READY }
    androidx.compose.material3.Text("Moonshine recognition · Pocket streaming · Native Kokoro")
    if (ready) {
        androidx.compose.material3.Text("Voice Chat plugin ready", color = accent)
    } else if (active == null && (installation.pending || checking)) {
        androidx.compose.material3.LinearProgressIndicator(color = accent)
        androidx.compose.material3.Text(
            if (checking) "Checking voice files…" else installation.waitingMessage(),
            color = accent
        )
        installation.elapsedMillis?.takeIf { it >= 30_000 && installation.pending }?.let {
            androidx.compose.material3.Text("${it / 1_000} seconds since this download was requested. Include Voice diagnostics if you report the delay.")
        }
        if (installation.pending) androidx.compose.material3.TextButton(onClick = onCancel) { androidx.compose.material3.Text("Cancel download") }
    } else if (active != null) {
        VoiceTransferProgress(active.transferStage, received, total)
        androidx.compose.material3.Text("${android.text.format.Formatter.formatShortFileSize(context, received)} of ${android.text.format.Formatter.formatShortFileSize(context, total)}")
        androidx.compose.material3.TextButton(onClick = onCancel) { androidx.compose.material3.Text("Cancel download") }
    } else {
        val remaining = models.filter { it.status != LocalVoiceModelStatus.READY }.sumOf { it.totalBytes }
        androidx.compose.material3.Text("Offline English voice bundle · ${android.text.format.Formatter.formatShortFileSize(context, remaining)}", color = accent)
        if (installation.failure != null && message == null) {
            androidx.compose.material3.Text(
                if (installation.failure == "WORK_NOT_FOUND") "Android no longer has this download. Start it again to resume verified files." else "Voice download could not start or finish. Tap to try again.",
                color = androidx.compose.material3.MaterialTheme.colorScheme.error
            )
        }
        message?.let { androidx.compose.material3.Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        androidx.compose.material3.Button(onClick = onStart, enabled = !checking) {
            androidx.compose.material3.Text(if (message == null && installation.failure == null) "Download Voice Chat plugin" else "Resume download")
        }
    }
    if (!ready && (installation.pending || installation.failure != null || active != null || installation.phase == "COMPLETED")) {
        androidx.compose.material3.TextButton(onClick = onReportDownload) { androidx.compose.material3.Text("Report download problem") }
    }
}
