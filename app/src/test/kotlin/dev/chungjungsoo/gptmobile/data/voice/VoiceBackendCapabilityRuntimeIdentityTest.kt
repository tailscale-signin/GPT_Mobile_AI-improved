package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceBackendCapabilityRuntimeIdentityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_800_000_000_000L
    private val cpu = VoiceSpeechBackend.CPU
    private val gpu = VoiceSpeechBackend.QNN_GPU
    private val npu = VoiceSpeechBackend.QNN_HTP

    @Before fun clear() {
        context.getSharedPreferences("voice_backend_capabilities_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun previousRuntimeSuccessReceiptsCannotRankTheRepairedRuntime() {
        val store = VoiceBackendCapabilityStore(context)
        val oldScope = previousRuntimeScope(store)
        val oldCpu = measured(oldScope, cpu, 100)
        val oldGpu = measured(oldScope, gpu, 70)
        store.save(oldCpu)
        store.save(oldGpu)

        val reopened = VoiceBackendCapabilityStore(context)
        val retained = listOfNotNull(reopened.read(oldCpu.key, now), reopened.read(oldGpu.key, now))
        assertEquals(listOf(oldCpu, oldGpu), retained)
        assertEquals(gpu, VoiceMeasuredBackendPolicy.choose(retained, now))
        assertNull(reopened.read(reopened.key(cpu, "us", 2), now))
        assertNull(reopened.read(reopened.key(gpu, "us", 2), now))
        assertEquals(cpu, selected(reopened))
    }

    @Test fun previousRuntimeFailureCannotDisableTheRepairedBackend() {
        val store = VoiceBackendCapabilityStore(context)
        val old = failed(previousRuntimeScope(store))
        store.save(old)

        val reopened = VoiceBackendCapabilityStore(context)
        assertEquals(old, reopened.read(old.key, now))
        assertNotNull(reopened.read(old.key, now)?.failure)
        assertNull(reopened.read(reopened.key(npu, "us", 2), now))
    }

    @Test fun currentRuntimeReceiptsSurviveStoreRecreationAndStillSelectAccelerator() {
        val store = VoiceBackendCapabilityStore(context)
        val scope = store.comparisonScope("us", 2)
        val measuredCpu = measured(scope, cpu, 100)
        val measuredGpu = measured(scope, gpu, 70)
        store.save(measuredCpu)
        store.save(measuredGpu)

        val reopened = VoiceBackendCapabilityStore(context)
        assertEquals(measuredCpu, reopened.read(reopened.key(cpu, "us", 2), now))
        assertEquals(measuredGpu, reopened.read(reopened.key(gpu, "us", 2), now))
        assertEquals(gpu, selected(reopened))
    }

    @Test fun profileTwoMeasurementsCannotQualifyOrDisableProgressRuntime() {
        val store = VoiceBackendCapabilityStore(context)
        val oldScope = previousRuntimeScope(store, "ort1.28.2-qnn2.7-qairt2.51-convex2-profile2")
        store.save(measured(oldScope, cpu, 100))
        store.save(measured(oldScope, gpu, 60))
        store.save(failed(oldScope))
        val reopened = VoiceBackendCapabilityStore(context)
        for (backend in listOf(cpu, gpu, npu)) assertNull(reopened.read(reopened.key(backend, "us", 2), now))
        assertEquals(cpu, selected(reopened))
    }

    @Test fun currentRuntimeFailurePreservesItsOriginalProvenance() {
        val store = VoiceBackendCapabilityStore(context)
        val failure = failed(store.comparisonScope("us", 2))
        store.save(failure)

        val reopened = VoiceBackendCapabilityStore(context)
        assertEquals(failure, reopened.read(reopened.key(npu, "us", 2), now))
    }

    private fun selected(store: VoiceBackendCapabilityStore) = VoiceMeasuredBackendPolicy.choose(
        listOf(cpu, gpu, npu).mapNotNull { store.read(store.key(it, "us", 2), now) },
        now
    )

    private fun measured(scope: String, backend: VoiceSpeechBackend, milliseconds: Long) = VoiceBackendCapability(
        digest("$scope|${backend.name}"), backend, now,
        List(VoiceMeasuredBackendPolicy.FIXTURE_COUNT) { milliseconds }, backend != cpu,
        comparisonScope = scope, speakerId = KokoroVoice.HEART.speakerId, speechRate = 1f,
        thermalStatuses = List(VoiceMeasuredBackendPolicy.thermalSampleCount) { 0 }
    )

    private fun failed(scope: String) = VoiceBackendCapability(
        digest("$scope|${npu.name}"), npu, now,
        failure = VoiceSpeechFailureCode.EXECUTION_FAILED, failureStage = VoiceSpeechStage.SYNTHESIS,
        nativeCode = 11, tokenCount = 17, comparisonScope = scope,
        speakerId = KokoroVoice.HEART.speakerId, speechRate = 1f
    )

    private fun previousRuntimeScope(store: VoiceBackendCapabilityStore, previousRuntime: String = "ort1.28.2-qnn2.7-qairt2.51-convex2"): String {
        // Hold the workload, device and settings constant to isolate the native
        // runtime migration. This historical ID must never track RUNTIME_ID.
        val fixtureHash = VoiceSpeechWorkload.fixtureHash(VoiceSpeechBenchmarkWorkload.UTTERANCE)
        return digest("${VoiceMeasuredBackendPolicy.COMPARISON_PROTOCOL}|$fixtureHash|$previousRuntime|${KokoroModelPack.MODEL_SHA256}|${store.deviceKey}|us|2|HEART|3|${1f.toBits()}")
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
