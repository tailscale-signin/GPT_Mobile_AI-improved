package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.voice.KokoroModelVariant
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStore
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeKokoroBucket
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechCatalog
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechEngine
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechModelStore
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeVoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.PocketCaptionModelStore
import dev.chungjungsoo.gptmobile.data.voice.PocketCompiledSpeechModelStore
import dev.chungjungsoo.gptmobile.data.voice.VoiceBackendCapabilityStore
import dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngine
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import dev.chungjungsoo.gptmobile.data.voice.VoiceSynthesisPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceBackendViewModelTest {
    @Test fun translationFactoriesKeepBothVoicesWithoutReplacingTheOrdinaryPlaybackFactory() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val owners = ViewModelStore()
        try {
            val saved = context().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
            saved.edit().putString("backend", VoiceSpeechBackend.POCKET_CPU.name)
                .putStringSet("streaming_voices", setOf("POCKET=marius")).commit()
            val preferences = VoiceBackendPreferenceStore({ saved }, applicationScope)
            val settings = mockk<SettingRepository>()
            every { settings.observeProviderConnections() } returns MutableStateFlow(emptyList())
            val models = mockk<MediaPipeSpeechModelStore>(relaxed = true)
            val multilingual = mockk<LocalVoiceModelStore>(relaxed = true)
            every { models.state } returns MutableStateFlow(emptyMap())
            every { models.localStore(MediaPipeSpeechEngine.POCKET_MULTILINGUAL) } returns multilingual
            every { multilingual.state } returns MutableStateFlow(LocalVoiceModelState(LocalVoiceModelStatus.READY))
            every { models.isReady(MediaPipeSpeechEngine.POCKET_MULTILINGUAL) } returns true
            every { models.acceleratorEnvironment } returns "translation-test"
            val model = VoiceBackendViewModel(context(), settings, preferences, dispatcher, createStreamingStore = { _, _ -> models })
            owners.put("voice", model)
            runCurrent()
            val original = model.state.value.factory
            val originalPreferences = saved.all.toMap()
            val english = model.translationFactory("en-GB", "azelma")
            val french = model.translationFactory("fr-CA", "estelle")
            assertEquals(VoiceSpeechBackend.POCKET_MULTILINGUAL_CPU, english.selectedBackend)
            assertEquals(VoiceSpeechBackend.POCKET_MULTILINGUAL_CPU, french.selectedBackend)
            assertEquals(english.residentIdentity, french.residentIdentity)
            assertNotEquals(english.identity, french.identity)
            assertTrue(english.identity.endsWith("voice=azelma"))
            assertTrue(french.identity.endsWith("voice=estelle"))
            val idleEngine = mockk<VoicePhraseEngine>(relaxed = true)
            english.prepareForPlayback(idleEngine)
            french.prepareForPlayback(idleEngine)
            verify(exactly = 1) { idleEngine.selectVoice("azelma") }
            verify(exactly = 1) { idleEngine.selectVoice("estelle") }
            assertTrue(runCatching { model.translationFactory("fr-FR", "azelma") }.exceptionOrNull() is IllegalArgumentException)
            assertSame(original, model.state.value.factory)
            assertEquals(VoiceSpeechBackend.POCKET_CPU, model.state.value.backend)
            assertEquals("marius", model.state.value.streamingVoiceId)
            assertEquals(originalPreferences, saved.all)
            coVerify(exactly = 0) { models.install(any()) }
        } finally {
            owners.clear()
            applicationScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun translationRejectsTheEnglishOnlyPackAndInstallsMultilingualOnlyAfterAnExplicitAction() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val owners = ViewModelStore()
        try {
            val saved = context().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
            val preferences = VoiceBackendPreferenceStore({ saved }, applicationScope)
            val settings = mockk<SettingRepository>()
            every { settings.observeProviderConnections() } returns MutableStateFlow(emptyList())
            val models = mockk<MediaPipeSpeechModelStore>(relaxed = true)
            val multilingual = mockk<LocalVoiceModelStore>(relaxed = true)
            every { models.state } returns MutableStateFlow(emptyMap())
            every { models.localStore(MediaPipeSpeechEngine.POCKET_MULTILINGUAL) } returns multilingual
            every { multilingual.state } returns MutableStateFlow(LocalVoiceModelState(LocalVoiceModelStatus.NOT_INSTALLED))
            every { models.isReady(MediaPipeSpeechEngine.POCKET) } returns true
            every { models.isReady(MediaPipeSpeechEngine.POCKET_MULTILINGUAL) } returns false
            val model = VoiceBackendViewModel(context(), settings, preferences, dispatcher, createStreamingStore = { _, _ -> models })
            owners.put("voice", model)
            runCurrent()
            val original = model.state.value.factory
            val failure = runCatching { model.translationFactory("fr-FR", "estelle") }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertTrue(failure?.message.orEmpty().contains("Install the Pocket Multilingual voice pack"))
            model.refreshTranslationModel()
            runCurrent()
            coVerify(exactly = 0) { models.install(any()) }
            model.installTranslationModel()
            runCurrent()
            coVerify(exactly = 1) { models.install(MediaPipeSpeechEngine.POCKET_MULTILINGUAL) }
            coVerify(exactly = 0) { models.install(MediaPipeSpeechEngine.POCKET) }
            model.cancelTranslationModel()
            verify(exactly = 1) { models.cancelInstall(MediaPipeSpeechEngine.POCKET_MULTILINGUAL) }
            assertSame(original, model.state.value.factory)
            assertEquals(VoiceSpeechBackend.POCKET_CPU, model.state.value.backend)
        } finally {
            owners.clear()
            applicationScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun compiledPocketRemovalRetiresTheCombinedInstallBeforeEitherPackIsDeleted() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val owners = ViewModelStore()
        val allowInstallReturn = CompletableDeferred<Unit>()
        try {
            val saved = context().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
            saved.edit().putString("backend", VoiceSpeechBackend.POCKET_GPU.name).commit()
            val preferences = VoiceBackendPreferenceStore({ saved }, applicationScope)
            val settings = mockk<SettingRepository>()
            every { settings.observeProviderConnections() } returns MutableStateFlow(emptyList())
            val speech = mockk<PocketCompiledSpeechModelStore>(relaxed = true)
            val captions = mockk<PocketCaptionModelStore>(relaxed = true)
            every { speech.state } returns MutableStateFlow(LocalVoiceModelState())
            every { captions.state } returns MutableStateFlow(LocalVoiceModelState())
            every { speech.isReady() } returns true
            coEvery { speech.install() } coAnswers { withContext(NonCancellable) { allowInstallReturn.await() } }
            val model = VoiceBackendViewModel(context(), settings, preferences, dispatcher, createCompiledPocketStore = { speech }, createPocketCaptionStore = { captions })
            owners.put("voice", model)
            runCurrent()
            model.installStreamingModel()
            runCurrent()
            coVerify(exactly = 1) { speech.install() }
            model.removeStreamingModel()
            runCurrent()
            assertTrue(model.state.value.removingCompiledPocket)
            coVerify(exactly = 0) { speech.remove() }
            coVerify(exactly = 0) { captions.remove() }
            model.installStreamingModel()
            runCurrent()
            coVerify(exactly = 1) { speech.install() }
            allowInstallReturn.complete(Unit)
            runCurrent()
            assertFalse(model.state.value.removingCompiledPocket)
            coVerify(exactly = 0) { captions.install() }
            coVerify(exactly = 1) { speech.remove() }
            coVerify(exactly = 1) { captions.remove() }
            verify(exactly = 1) { speech.cancelInstall() }
            verify(exactly = 1) { captions.cancelInstall() }
        } finally {
            allowInstallReturn.complete(Unit)
            owners.clear()
            applicationScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun compiledPocketSharesItsVerifiedPackAndKeepsItsOwnVoiceAcrossBackendChanges() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val owners = ViewModelStore()
        try {
            val saved = context().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
            val preferences = VoiceBackendPreferenceStore({ saved }, applicationScope)
            val settings = mockk<SettingRepository>()
            every { settings.observeProviderConnections() } returns MutableStateFlow(emptyList())
            val store = mockk<PocketCompiledSpeechModelStore>(relaxed = true)
            every { store.state } returns MutableStateFlow(LocalVoiceModelState())
            every { store.isReady() } returns false
            val captions = mockk<PocketCaptionModelStore>(relaxed = true)
            every { captions.state } returns MutableStateFlow(LocalVoiceModelState())
            every { captions.isReady() } returns false
            val model = VoiceBackendViewModel(context(), settings, preferences, dispatcher, createCompiledPocketStore = { store }, createPocketCaptionStore = { captions })
            owners.put("voice", model)
            runCurrent()
            assertEquals(VoiceSpeechBackend.POCKET_CPU, model.state.value.backend)
            model.selectBackend(VoiceSpeechBackend.POCKET_GPU)
            runCurrent()
            assertEquals(VoiceSpeechBackend.POCKET_GPU, model.state.value.factory.selectedBackend)
            assertFalse(model.state.value.factory.requiresKokoroAssets)
            assertFalse(model.state.value.factory.isReady())
            assertFalse(model.state.value.backend.usesKokoroVoices)
            model.selectStreamingVoice("mary")
            runCurrent()
            assertEquals("mary", model.state.value.streamingVoiceId)
            model.configureSpeech(KokoroVoice.HEART, 1.25f, synthesis = VoiceSynthesisPreferences("fr"))
            runCurrent()
            assertEquals("en-US", model.state.value.synthesisProfile(VoiceSynthesisPreferences("fr")).tag)
            assertEquals(VoiceSpeechBackend.POCKET_GPU, model.state.value.backend)
            model.selectBackend(VoiceSpeechBackend.POCKET_CPU)
            runCurrent()
            assertEquals("alba", model.state.value.streamingVoiceId)
            model.selectBackend(VoiceSpeechBackend.POCKET_NPU)
            runCurrent()
            assertEquals("mary", model.state.value.streamingVoiceId)
            model.selectStreamingVoice("estelle")
            runCurrent()
            assertEquals("mary", model.state.value.streamingVoiceId)
            coVerify(exactly = 1) { store.refresh() }
            coVerify(exactly = 1) { captions.refresh() }
            coVerify(exactly = 0) { store.install() }
            coVerify(exactly = 0) { captions.install() }
            model.installStreamingModel()
            runCurrent()
            coVerify(exactly = 1) { store.install() }
            coVerify(exactly = 0) { captions.install() }
            every { store.isReady() } returns true
            model.installStreamingModel()
            runCurrent()
            coVerify(exactly = 2) { store.install() }
            coVerify(exactly = 1) { captions.install() }
            model.cancelStreamingInstall()
            verify(exactly = 1) { store.cancelInstall() }
            verify(exactly = 1) { captions.cancelInstall() }
            model.removeStreamingModel()
            runCurrent()
            coVerify(exactly = 1) { store.remove() }
            coVerify(exactly = 1) { captions.remove() }
            val restored = VoiceBackendPreferenceStore({ saved }, applicationScope)
            runCurrent()
            assertEquals(VoiceSpeechBackend.POCKET_NPU, restored.state.value?.value?.backend)
            assertEquals("mary", restored.state.value?.value?.compiledPocketVoiceId)
        } finally {
            owners.clear()
            applicationScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun pocketDefaultDoesNotReplaceAnExplicitSavedEngine() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            for (backend in listOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.LITERT_KOKORO_CPU, VoiceSpeechBackend.POCKET_GPU)) {
                val saved = context().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
                saved.edit().putString("backend", backend.name).putString("compiled_pocket_voice", "invalid").commit()
                val preferences = VoiceBackendPreferenceStore({ saved }, scope)
                runCurrent()
                assertEquals(backend, preferences.state.value?.value?.backend)
                assertEquals("alba", preferences.state.value?.value?.compiledPocketVoiceId)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun streamingSelectionKeepsItsModelAndRateIdentityWithoutNativeKokoroAssets() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val owners = ViewModelStore()
        try {
            val saved = context().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
            val preferences = VoiceBackendPreferenceStore({ saved }, applicationScope)
            val settings = mockk<SettingRepository>()
            every { settings.observeProviderConnections() } returns MutableStateFlow(emptyList())
            val model = VoiceBackendViewModel(context(), settings, preferences, dispatcher)
            owners.put("voice", model)
            assertFalse(model.state.value.factory.isReady())
            runCurrent()
            val defaultFactory = model.state.value.factory
            assertEquals(VoiceSpeechBackend.POCKET_CPU, model.state.value.backend)
            assertEquals(VoiceSpeechBackend.POCKET_CPU, defaultFactory.selectedBackend)
            assertFalse(defaultFactory.requiresKokoroAssets)
            model.configureSpeech(KokoroVoice.HEART, 1f)
            runCurrent()
            assertSame(defaultFactory, model.state.value.factory)
            model.selectBackend(VoiceSpeechBackend.LITERT_KOKORO_GPU)
            runCurrent()
            val original = model.state.value.factory
            assertTrue(model.state.value.loaded)
            assertFalse(original.requiresKokoroAssets)
            assertEquals(VoiceSpeechBackend.LITERT_KOKORO_GPU, original.selectedBackend)
            assertTrue(original.identity.contains(MediaPipeSpeechCatalog.model(MediaPipeSpeechEngine.KOKORO).sha256))
            assertTrue(model.state.value.status.contains("does not report"))
            model.configureSpeech(KokoroVoice.EMMA, 1.25f)
            runCurrent()
            assertNotEquals(original.identity, model.state.value.factory.identity)
            assertEquals("bf_emma", KokoroVoice.EMMA.streamingVoiceId)
            val emma = model.state.value.factory
            model.configureSpeech(KokoroVoice.HEART, 1.25f)
            runCurrent()
            assertEquals(emma.residentIdentity, model.state.value.factory.residentIdentity)
            assertNotEquals(emma.identity, model.state.value.factory.identity)
            model.configureSpeech(KokoroVoice.HEART, 1.25f, MediaPipeKokoroBucket.TOKENS_64, false)
            runCurrent()
            val experiment = model.state.value.factory as MediaPipeVoicePhraseEngineFactory
            assertEquals(64, experiment.effectiveTargetBucket)
            assertFalse(experiment.incrementalInput)
            assertEquals(VoiceSpeechBackend.LITERT_KOKORO_GPU, preferences.state.value?.value?.backend)
            model.selectBackend(VoiceSpeechBackend.LITERT_KOKORO_NPU)
            runCurrent()
            val npuFactory = model.state.value.factory
            assertEquals(VoiceSpeechBackend.LITERT_KOKORO_NPU, npuFactory.selectedBackend)
            assertTrue(npuFactory.identity.contains(MediaPipeSpeechCatalog.model(MediaPipeSpeechEngine.KOKORO).sha256))
            assertFalse(npuFactory.requiresKokoroAssets)
            model.selectBackend(VoiceSpeechBackend.PHONON_CPU)
            runCurrent()
            assertTrue(model.state.value.factory.identity.contains(MediaPipeSpeechCatalog.model(MediaPipeSpeechEngine.PHONON).sha256))
            assertFalse(model.state.value.factory.requiresKokoroAssets)
            assertFalse(model.state.value.backend.supportsCpuThreadCalibration)
            val japanese = VoiceSynthesisPreferences("ja").selectVoice("jf_nezumi")
            model.configureSpeech(KokoroVoice.HEART, 1f, synthesis = japanese)
            runCurrent()
            assertEquals(VoiceSpeechBackend.PHONON_CPU, model.state.value.backend)
            assertTrue(model.state.value.factory.identity.endsWith("voice=freya"))
            assertEquals("en-US", model.state.value.synthesisProfile(japanese).tag)
            assertEquals(VoiceSpeechBackend.PHONON_CPU, preferences.state.value?.value?.backend)
            model.configureSpeech(KokoroVoice.HEART, 1f, synthesis = japanese.selectLanguage("en-US"))
            runCurrent()
            assertEquals(VoiceSpeechBackend.PHONON_CPU, model.state.value.backend)
        } finally {
            owners.clear()
            applicationScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun npuPreparationWaitsForTheSharedPackAndCancelsOnlyItsOwnRequest() = runTest {
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "SOC_MODEL", "SM8750")
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val owners = ViewModelStore()
        try {
            val saved = context().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
            saved.edit().putString("backend", VoiceSpeechBackend.LITERT_KOKORO_SM8750.name).commit()
            val preferences = VoiceBackendPreferenceStore({ saved }, scope)
            val settings = mockk<SettingRepository>()
            every { settings.observeProviderConnections() } returns MutableStateFlow(emptyList())

            val npu = MediaPipeSpeechEngine.KOKORO_SM8750
            val standard = MediaPipeSpeechEngine.KOKORO
            val base = mockk<LocalVoiceModelStore>()
            val transfer = MutableStateFlow(LocalVoiceModelState(LocalVoiceModelStatus.DOWNLOADING))
            val modelStore = mockk<MediaPipeSpeechModelStore>(relaxed = true)
            every { modelStore.state } returns MutableStateFlow(mapOf(standard to transfer.value))
            every { modelStore.localStore(standard) } returns base
            every { base.state } returns transfer
            coEvery { base.install(waitForOwner = true, sourceOverride = any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }
            val model = VoiceBackendViewModel(context(), settings, preferences, dispatcher) { _, _ -> modelStore }
            owners.put("voice", model)
            runCurrent()
            assertFalse(model.state.value.preparingNpuBase)
            model.installStreamingModel()
            runCurrent()
            assertTrue(model.state.value.preparingNpuBase)
            model.cancelStreamingInstall()
            runCurrent()
            assertFalse(model.state.value.preparingNpuBase)
            verify(exactly = 0) { modelStore.cancelInstall(any()) }
            verify(exactly = 0) { base.cancelInstall() }
            coVerify(exactly = 0) { modelStore.install(npu) }

            coEvery { base.install(waitForOwner = true, sourceOverride = any()) } returns Unit
            transfer.value = LocalVoiceModelState(LocalVoiceModelStatus.FAILED, message = "No free space")
            model.installStreamingModel()
            runCurrent()
            assertEquals("No free space", model.state.value.npuBaseError)
            coVerify(exactly = 0) { modelStore.install(npu) }

            transfer.value = LocalVoiceModelState(LocalVoiceModelStatus.READY)
            model.installStreamingModel()
            runCurrent()
            assertEquals(null, model.state.value.npuBaseError)
            coVerify(exactly = 1) { modelStore.install(npu) }
        } finally {
            owners.clear()
            scope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun nativeMultilingualFallbackRetainsStreamingPacksAndTheirOwnVoiceLocales() {
        val japanese = VoiceSynthesisPreferences("ja").selectVoice("jf_nezumi")
        assertEquals(VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.QNN_GPU.forSynthesis(japanese))
        assertEquals(VoiceSpeechBackend.LITERT_KOKORO_NPU, VoiceSpeechBackend.QNN_HTP.forSynthesis(japanese.selectLanguage("fr-CA")))
        assertEquals(VoiceSpeechBackend.PHONON_CPU, VoiceSpeechBackend.PHONON_CPU.forSynthesis(japanese))
        assertEquals(VoiceSpeechBackend.POCKET_CPU, VoiceSpeechBackend.POCKET_CPU.forSynthesis(japanese))
        assertEquals("en-US", VoiceBackendSettings(backend = VoiceSpeechBackend.POCKET_CPU).synthesisProfile(japanese).tag)
        assertEquals(VoiceSpeechBackend.LITERT_KOKORO_CPU, VoiceSpeechBackend.GATEWAY_CUDA.forSynthesis(japanese))
        assertEquals(VoiceSpeechBackend.LITERT_KOKORO_GPU, VoiceSpeechBackend.LITERT_KOKORO_GPU.forSynthesis(japanese))
        assertEquals(VoiceSpeechBackend.AUTO, VoiceSpeechBackend.AUTO.forSynthesis(japanese.selectLanguage("en-US")))
    }

    @Test fun streamingEnginesHaveExplicitBackendAndVoiceMappings() {
        assertEquals(MediaPipeSpeechEngine.KOKORO, VoiceSpeechBackend.LITERT_KOKORO_CPU.streamingEngine())
        assertEquals(MediaPipeSpeechEngine.KOKORO, VoiceSpeechBackend.LITERT_KOKORO_GPU.streamingEngine())
        assertEquals(MediaPipeSpeechEngine.PHONON, VoiceSpeechBackend.PHONON_CPU.streamingEngine())
        assertEquals(MediaPipeSpeechEngine.POCKET, VoiceSpeechBackend.POCKET_CPU.streamingEngine())
        assertEquals(MediaPipeSpeechEngine.POCKET_MULTILINGUAL, VoiceSpeechBackend.POCKET_MULTILINGUAL_CPU.streamingEngine())
        assertEquals(null, VoiceSpeechBackend.CPU.streamingEngine())
        assertEquals(null, VoiceSpeechBackend.GATEWAY_CUDA.streamingEngine())
        assertEquals("af_heart", KokoroVoice.HEART.streamingVoiceId)
        assertEquals("am_adam", KokoroVoice.ADAM.streamingVoiceId)
    }

    @Test fun streamingVoiceSelectionSurvivesEngineSwitchAndPreferenceReload() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val owners = ViewModelStore()
        try {
            val saved = context().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
            val preferences = VoiceBackendPreferenceStore({ saved }, applicationScope)
            val settings = mockk<SettingRepository>()
            every { settings.observeProviderConnections() } returns MutableStateFlow(emptyList())
            val model = VoiceBackendViewModel(context(), settings, preferences, dispatcher)
            owners.put("voice", model)
            runCurrent()
            model.selectBackend(VoiceSpeechBackend.POCKET_CPU)
            runCurrent()
            val alba = model.state.value.factory
            model.selectStreamingVoice("marius")
            runCurrent()
            assertEquals("marius", model.state.value.streamingVoiceId)
            assertTrue(model.state.value.factory.identity.endsWith("voice=marius"))
            assertEquals(alba.residentIdentity, model.state.value.factory.residentIdentity)
            model.selectStreamingVoice("rafael")
            runCurrent()
            assertEquals("marius", model.state.value.streamingVoiceId)
            model.selectBackend(VoiceSpeechBackend.POCKET_MULTILINGUAL_CPU)
            runCurrent()
            model.selectStreamingVoice("rafael")
            runCurrent()
            assertEquals("pt-BR", model.state.value.synthesisProfile(VoiceSynthesisPreferences()).tag)
            assertTrue(model.state.value.factory.identity.endsWith("voice=rafael"))
            model.selectBackend(VoiceSpeechBackend.POCKET_CPU)
            runCurrent()
            assertEquals("marius", model.state.value.streamingVoiceId)
            val restored = VoiceBackendPreferenceStore({ saved }, applicationScope)
            runCurrent()
            assertEquals("marius", restored.state.value?.value?.streamingVoice(MediaPipeSpeechEngine.POCKET))
            assertEquals("rafael", restored.state.value?.value?.streamingVoice(MediaPipeSpeechEngine.POCKET_MULTILINGUAL))
        } finally {
            owners.clear()
            applicationScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun voiceAndRateChangesReplaceOnlyTheMatchingQualificationIdentity() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val owners = ViewModelStore()
        try {
            val saved = context().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
            saved.edit().putString("backend", VoiceSpeechBackend.CPU.name).commit()
            val preferences = VoiceBackendPreferenceStore({ saved }, applicationScope)
            val settings = mockk<SettingRepository>()
            every { settings.observeProviderConnections() } returns MutableStateFlow(emptyList())
            val model = VoiceBackendViewModel(context(), settings, preferences, dispatcher)
            owners.put("voice", model)
            runCurrent()
            assertEquals(VoiceSpeechBackend.CPU, model.state.value.backend)
            val original = model.state.value.factory
            model.configureSpeech(KokoroVoice.HEART, 1.25f)
            runCurrent()
            val rate = model.state.value.factory
            assertNotEquals(original.identity, rate.identity)
            model.configureSpeech(KokoroVoice.HEART, 1.25f)
            runCurrent()
            assertSame(rate, model.state.value.factory)
            model.configureSpeech(KokoroVoice.EMMA, 1.25f)
            runCurrent()
            assertNotEquals(rate.identity, model.state.value.factory.identity)
            assertTrue(model.state.value.loaded)
            val compact = model.state.value.factory
            model.configureSpeech(KokoroVoice.EMMA, 1.25f, modelVariant = KokoroModelVariant.ORIGINAL_FP32)
            runCurrent()
            assertNotEquals(compact.identity, model.state.value.factory.identity)
            assertTrue(model.state.value.factory.identity.contains(KokoroModelVariant.ORIGINAL_FP32.modelSha256))
        } finally {
            owners.clear()
            applicationScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun initialPreferenceAndCatalogFailuresShowErrorsWithoutPermanentlyGatingStreamingSpeech() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val owners = ViewModelStore()
        try {
            val preferences = VoiceBackendPreferenceStore({ error("Read failed") }, applicationScope)
            val settings = mockk<SettingRepository>()
            every { settings.observeProviderConnections() } returns flow { error("Database unavailable") }
            val model = VoiceBackendViewModel(context(), settings, preferences, dispatcher)
            owners.put("voice", model)
            runCurrent()
            assertTrue(model.state.value.loaded)
            assertEquals(VoiceSpeechBackend.POCKET_CPU, model.state.value.backend)
            assertTrue(model.state.value.status.contains("could not be read"))
            assertTrue(model.state.value.status.contains("connections could not be loaded"))
        } finally {
            owners.clear()
            applicationScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun retiredGatewayLookupCannotDisableOrReplaceTheNewConnection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val owners = ViewModelStore()
        val oldLookup = CompletableDeferred<ProviderConnection>()
        try {
            val saved = context().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
            saved.edit().putString("backend", "GATEWAY_CUDA").putString("gateway_uid", "pc").commit()
            val preferences = VoiceBackendPreferenceStore({ saved }, applicationScope)
            val old = ProviderConnection(uid = "pc", name = "Old PC", compatibleType = ClientType.LLAMA, apiUrl = "https://old.example/v1")
            val replacement = old.copy(name = "New PC", apiUrl = "https://new.example/v1")
            val connections = MutableStateFlow(listOf(old))
            val settings = mockk<SettingRepository>()
            every { settings.observeProviderConnections() } returns connections
            var lookups = 0
            coEvery { settings.getProviderConnection("pc") } coAnswers {
                if (++lookups == 1) withContext(NonCancellable) { oldLookup.await() } else replacement
            }
            coEvery { settings.getProviderCredentials("pc") } returns null
            val model = VoiceBackendViewModel(context(), settings, preferences, dispatcher)
            owners.put("voice", model)
            runCurrent()
            assertFalse(model.state.value.loaded)
            connections.value = listOf(replacement)
            runCurrent()
            assertTrue(model.state.value.loaded)
            val factory = model.state.value.factory
            assertTrue(model.state.value.status.contains("New PC"))
            oldLookup.complete(old)
            runCurrent()
            assertTrue(model.state.value.loaded)
            assertSame(factory, model.state.value.factory)
            assertTrue(model.state.value.status.contains("New PC"))
            connections.value = listOf(replacement, old.copy(uid = "unrelated"))
            runCurrent()
            assertSame(factory, model.state.value.factory)
        } finally {
            oldLookup.complete(ProviderConnection(name = "Retired", compatibleType = ClientType.LLAMA))
            owners.clear()
            applicationScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun selectingBackendPersistsEvenWhenNavigationClosesBeforeTheWriterRuns() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val applicationScope = CoroutineScope(SupervisorJob() + dispatcher)
        val owners = ViewModelStore()
        try {
            val saved = context().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
            val preferences = VoiceBackendPreferenceStore({ saved }, applicationScope)
            val settings = mockk<SettingRepository>()
            every { settings.observeProviderConnections() } returns MutableStateFlow(emptyList())
            coEvery { settings.getProviderConnection(any()) } returns null
            val model = VoiceBackendViewModel(context(), settings, preferences, dispatcher)
            owners.put("voice", model)
            runCurrent()
            assertTrue(model.state.value.loaded)
            model.selectBackend(VoiceSpeechBackend.GATEWAY_CUDA)
            owners.clear()
            runCurrent()
            assertEquals("GATEWAY_CUDA", saved.getString("backend", null))
            assertEquals(VoiceSpeechBackend.GATEWAY_CUDA, preferences.state.value?.value?.backend)
        } finally {
            owners.clear()
            applicationScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun selectingAutoReusesMeasurementsAndOnlyCompareRequestsCalibration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val owners = ViewModelStore()
        try {
            val context = context()
            val preferences = context.getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
            val settings = mockk<SettingRepository>()
            every { settings.observeProviderConnections() } returns MutableStateFlow(emptyList())
            val model = VoiceBackendViewModel(context, settings, VoiceBackendPreferenceStore({ preferences }, scope), dispatcher)
            owners.put("voice", model)
            runCurrent()
            val capabilities = VoiceBackendCapabilityStore(context)
            val revision = capabilities.revision()

            model.selectBackend(VoiceSpeechBackend.AUTO)
            runCurrent()
            assertEquals(revision, capabilities.revision())
            assertEquals(30_000L, model.state.value.factory.preparationTimeoutMs)

            model.recheckBackend()
            runCurrent()
            assertEquals(revision + 1, capabilities.revision())
            assertEquals(360_000L, model.state.value.factory.preparationTimeoutMs)
        } finally {
            owners.clear()
            scope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun context() = ApplicationProvider.getApplicationContext<Application>()
}
