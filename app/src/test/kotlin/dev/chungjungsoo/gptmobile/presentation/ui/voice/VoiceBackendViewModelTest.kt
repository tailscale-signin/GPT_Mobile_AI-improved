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
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeKokoroBucket
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechCatalog
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechEngine
import dev.chungjungsoo.gptmobile.data.voice.MediaPipeVoicePhraseEngineFactory
import dev.chungjungsoo.gptmobile.data.voice.VoiceBackendCapabilityStore
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
            runCurrent()
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
            assertEquals("bf_emma", KokoroVoice.EMMA.streamingVoiceId())
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
            model.selectBackend(VoiceSpeechBackend.PHONON_CPU)
            runCurrent()
            assertTrue(model.state.value.factory.identity.contains(MediaPipeSpeechCatalog.model(MediaPipeSpeechEngine.PHONON).sha256))
            assertFalse(model.state.value.factory.requiresKokoroAssets)
            assertFalse(model.state.value.backend.supportsCpuThreadCalibration)
        } finally {
            owners.clear()
            applicationScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun streamingEnginesHaveExplicitBackendAndVoiceMappings() {
        assertEquals(MediaPipeSpeechEngine.KOKORO, VoiceSpeechBackend.LITERT_KOKORO_CPU.streamingEngine())
        assertEquals(MediaPipeSpeechEngine.KOKORO, VoiceSpeechBackend.LITERT_KOKORO_GPU.streamingEngine())
        assertEquals(MediaPipeSpeechEngine.PHONON, VoiceSpeechBackend.PHONON_CPU.streamingEngine())
        assertEquals(MediaPipeSpeechEngine.POCKET, VoiceSpeechBackend.POCKET_CPU.streamingEngine())
        assertEquals(null, VoiceSpeechBackend.CPU.streamingEngine())
        assertEquals(null, VoiceSpeechBackend.GATEWAY_CUDA.streamingEngine())
        assertEquals("af_heart", KokoroVoice.HEART.streamingVoiceId())
        assertEquals("am_adam", KokoroVoice.ADAM.streamingVoiceId())
    }

    @Test fun voiceAndRateChangesReplaceOnlyTheMatchingQualificationIdentity() = runTest {
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

    @Test fun initialPreferenceAndCatalogFailuresShowErrorsWithoutPermanentlyGatingCpuSpeech() = runTest {
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
            assertEquals(VoiceSpeechBackend.CPU, model.state.value.backend)
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
