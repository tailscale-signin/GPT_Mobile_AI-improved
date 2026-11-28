package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.voice.KokoroVoice
import dev.chungjungsoo.gptmobile.data.voice.VoiceBenchStatus
import dev.chungjungsoo.gptmobile.data.voice.VoicePerformanceSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceFullBenchmarkViewModelTest {
    private fun context(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext() = this
            override fun getNoBackupFilesDir() = root
        }
    }

    @Test fun stoppedHandoffKeepsOwnershipUntilRetirementAndNeverCreatesACampaign() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val owners = ViewModelStore()
        val retired = CompletableDeferred<Unit>()
        try {
            withTimeout(30_000) {
                val model = VoiceSpeechBenchmarkViewModel(context())
                owners.put("handoff", model)
                model.state.first { it.loaded }
                model.startFull(KokoroVoice.HEART, 0, 1f, 0, null, VoicePerformanceSnapshot(), null, null, beforeStart = { retired.await() })
                assertTrue(model.state.value.running)
                assertNull(model.state.value.suite)
                assertTrue(VoiceSpeechBenchmarkViewModel.audioOwnership.value != null)
                model.cancel()
                assertTrue(model.state.value.cancelling)
                assertTrue(VoiceSpeechBenchmarkViewModel.audioOwnership.value != null)
                retired.complete(Unit)
                val state = model.state.first { !it.running }
                assertNull(state.suite)
                assertFalse(state.nativeCleanupPending)
                assertNull(VoiceSpeechBenchmarkViewModel.audioOwnership.value)
            }
        } finally {
            owners.clear()
            retired.complete(Unit)
            Dispatchers.resetMain()
        }
    }

    @Test fun clearingTheViewModelDuringHandoffCannotReleaseAudioBeforeRetirement() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val owners = ViewModelStore()
        val retired = CompletableDeferred<Unit>()
        try {
            withTimeout(30_000) {
                val model = VoiceSpeechBenchmarkViewModel(context())
                owners.put("handoff", model)
                model.state.first { it.loaded }
                model.startFull(KokoroVoice.HEART, 0, 1f, 0, null, VoicePerformanceSnapshot(), null, null, beforeStart = { retired.await() })
                assertTrue(model.state.value.running)
                val lease = requireNotNull(VoiceSpeechBenchmarkViewModel.audioOwnership.value)
                owners.clear()
                assertEquals(lease, VoiceSpeechBenchmarkViewModel.audioOwnership.value)
                assertNull(model.state.value.suite)
                retired.complete(Unit)
                VoiceSpeechBenchmarkViewModel.audioOwnership.first { it == null }
                assertNull(model.state.value.suite)
                assertFalse(model.state.value.nativeCleanupPending)
            }
        } finally {
            owners.clear()
            retired.complete(Unit)
            Dispatchers.resetMain()
        }
    }

    @Test fun normalStartFinishesWithoutPacksAndTheSameExportReopensInAnotherViewModel() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val owners = ViewModelStore()
        try {
            withTimeout(30_000) {
                val context = context()
                val model = VoiceSpeechBenchmarkViewModel(context)
                owners.put("run", model)
                model.state.first { it.loaded }
                model.startFull(KokoroVoice.HEART, 0, 1f, 100, "ff_siwis", VoicePerformanceSnapshot(), null, null)
                val state = model.state.first { !it.running && it.suite != null }
                val suite = requireNotNull(state.suite)
                assertEquals("FINISHED", suite.state)
                assertEquals(suite.results.size, suite.completed)
                assertTrue(suite.results.any { it.reason == "MODEL_NOT_INSTALLED" })
                assertTrue(suite.results.any { it.label.contains("thinking and search") && it.status == VoiceBenchStatus.MEASURED })
                assertNull(VoiceSpeechBenchmarkViewModel.audioOwnership.value)
                val next = VoiceSpeechBenchmarkViewModel(context)
                owners.put("restore", next)
                val restored = requireNotNull(next.state.first { it.loaded }.suite)
                assertEquals(suite, restored)
                val output = ByteArrayOutputStream()
                next.exportSuite(restored, output)
                val raw = output.toString("UTF-8")
                assertEquals(suite.results.size, Json.parseToJsonElement(raw).jsonObject.getValue("stageEvidence").jsonArray.size)
                assertTrue(raw.contains("dictionary-fr"))
                assertFalse(raw.contains("localDeviceKey"))
                assertFalse(raw.contains("Please check the weather"))
            }
        } finally {
            owners.clear()
            Dispatchers.resetMain()
        }
    }
}
