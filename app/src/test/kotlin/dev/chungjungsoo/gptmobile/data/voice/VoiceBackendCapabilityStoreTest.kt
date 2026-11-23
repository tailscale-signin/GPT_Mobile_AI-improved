package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.melo.kokoro.TimedKokoro
import java.security.MessageDigest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceBackendCapabilityStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_800_000_000_000L
    private fun record(
        key: String,
        backend: VoiceSpeechBackend,
        at: Long,
        samples: List<Long> = emptyList(),
        verified: Boolean = false,
        failure: VoiceSpeechFailureCode? = null,
        failureStage: VoiceSpeechStage? = null,
        nativeCode: Int? = null,
        tokenCount: Int? = null
    ) = VoiceBackendCapability(
        key, backend, at, samples, verified, failure, failureStage, nativeCode, tokenCount,
        comparisonScope = "a".repeat(64), speakerId = 3, speechRate = 1f,
        thermalStatuses = List(VoiceMeasuredBackendPolicy.thermalSampleCount) { 0 }
    )

    @Before fun clear() {
        context.getSharedPreferences("voice_backend_capabilities_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun residentIdentityIsStableUntilExplicitRecheck() {
        fun identity() = QualcommVoicePhraseEngineFactory(context, VoiceSpeechBackend.QNN_GPU, {}).identity
        val first = identity()
        assertEquals(first, identity())
        QualcommVoicePhraseEngineFactory.requestRetest(context, VoiceSpeechBackend.QNN_GPU)
        assertNotEquals(first, identity())
        val store = VoiceBackendCapabilityStore(context)
        assertTrue(store.deviceKey.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(store.key(VoiceSpeechBackend.QNN_GPU, "us", 2), store.key(VoiceSpeechBackend.QNN_HTP, "us", 2))
        assertNotEquals(store.key(VoiceSpeechBackend.QNN_GPU, "us", 2), store.key(VoiceSpeechBackend.QNN_GPU, "gb", 2))
        assertNotEquals(store.key(VoiceSpeechBackend.QNN_GPU, "us", 2), store.key(VoiceSpeechBackend.QNN_GPU, "us", 3))
    }

    @Test fun recheckInvalidatesOnlyTheSelectedBackendAndPreservesFailureProvenance() {
        val store = VoiceBackendCapabilityStore(context)
        val cpu = store.key(VoiceSpeechBackend.CPU, "us", 2)
        val gpu = store.key(VoiceSpeechBackend.QNN_GPU, "us", 2)
        val npu = store.key(VoiceSpeechBackend.QNN_HTP, "us", 2)
        store.save(record(cpu, VoiceSpeechBackend.CPU, now, listOf(30, 40, 50, 30, 40, 50)))
        store.save(
            record(
                gpu,
                VoiceSpeechBackend.QNN_GPU,
                now + 1,
                failure = VoiceSpeechFailureCode.GRAPH_PARTITION,
                failureStage = VoiceSpeechStage.PREPARING,
                nativeCode = 11,
                tokenCount = 17
            )
        )
        store.save(record(npu, VoiceSpeechBackend.QNN_HTP, now + 2, listOf(20, 30, 40, 20, 30, 40), true))
        assertEquals(11, store.read(gpu, now + 2)?.nativeCode)
        assertEquals(17, store.read(gpu, now + 2)?.tokenCount)
        store.retest(VoiceSpeechBackend.QNN_GPU)
        assertNull(store.read(gpu, now + 2))
        assertEquals(2, listOf(cpu, npu).count { store.read(it, now + 2) != null })
        store.retest(VoiceSpeechBackend.AUTO)
        assertNull(store.read(cpu, now + 2))
        assertNull(store.read(npu, now + 2))
    }

    @Test fun legacyTwoShapeReceiptsCannotQualifyAgainstTheCurrentWorkload() {
        val store = VoiceBackendCapabilityStore(context)
        fun legacyKey(backend: VoiceSpeechBackend) = MessageDigest.getInstance("SHA-256")
            .digest("${TimedKokoro.RUNTIME_ID}|${KokoroModelPack.MODEL_SHA256}|${store.deviceKey}|${backend.name}|us|2".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val legacyCpu = legacyKey(VoiceSpeechBackend.CPU)
        val legacyGpu = legacyKey(VoiceSpeechBackend.QNN_GPU)
        store.save(record(legacyCpu, VoiceSpeechBackend.CPU, now, listOf(100, 100)))
        store.save(record(legacyGpu, VoiceSpeechBackend.QNN_GPU, now, listOf(10, 10), true))
        val cpu = store.key(VoiceSpeechBackend.CPU, "us", 2)
        val gpu = store.key(VoiceSpeechBackend.QNN_GPU, "us", 2)
        store.save(record(cpu, VoiceSpeechBackend.CPU, now, listOf(100, 100, 1000, 100, 100, 1000)))
        fun selected() = VoiceMeasuredBackendPolicy.choose(listOfNotNull(store.read(cpu, now), store.read(gpu, now)), now)
        assertEquals(VoiceSpeechBackend.CPU, selected())
        assertNotEquals(legacyCpu, cpu)
        assertNotEquals(legacyGpu, gpu)
        assertNotNull(store.read(legacyCpu, now))
        assertNotNull(store.read(legacyGpu, now))
        store.save(record(gpu, VoiceSpeechBackend.QNN_GPU, now, listOf(50, 50, 50, 50, 50, 50), true))
        assertEquals(VoiceSpeechBackend.QNN_GPU, selected())
        store.retest(VoiceSpeechBackend.QNN_GPU)
        assertNull(store.read(gpu, now))
        assertNull(store.read(legacyGpu, now))
        assertNotNull(store.read(cpu, now))
        assertNotNull(store.read(legacyCpu, now))
        assertEquals(VoiceSpeechBackend.CPU, selected())
    }

    @Test fun receiptsRemainBoundedAndMalformedStorageCannotManufactureSupport() {
        val store = VoiceBackendCapabilityStore(context)
        val keys = (0 until 30).map { store.key(VoiceSpeechBackend.QNN_GPU, "lexicon-$it", 2) }
        keys.forEachIndexed { index, key -> store.save(record(key, VoiceSpeechBackend.QNN_GPU, now + index, listOf(20, 30, 40, 20, 30, 40), true)) }
        assertEquals(24, keys.count { store.read(it, now + 30) != null })
        assertNull(store.read(keys.last(), now - 1))
        context.getSharedPreferences("voice_backend_capabilities_v1", Context.MODE_PRIVATE).edit().putString("receipts", "[malformed").commit()
        assertNull(store.read(keys.last(), now + 30))
    }

    @Test fun comparisonProtocolSeparatesLegacyCacheAndPreservesCurrentPairedMeasurements() {
        val store = VoiceBackendCapabilityStore(context)
        val backends = listOf(VoiceSpeechBackend.CPU, VoiceSpeechBackend.QNN_GPU)
        for (backend in backends) {
            val legacyIdentity = "${TimedKokoro.RUNTIME_ID}|${KokoroModelPack.MODEL_SHA256}|${store.deviceKey}|${backend.name}|us|2"
            val legacyKey = MessageDigest.getInstance("SHA-256").digest(legacyIdentity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val currentKey = store.key(backend, "us", 2)
            store.save(record(legacyKey, backend, now, listOf(10, 10), backend != VoiceSpeechBackend.CPU))
            assertNotEquals(legacyKey, currentKey)
            assertNull(store.read(currentKey, now))
        }
        for (backend in backends) {
            val samples = if (backend == VoiceSpeechBackend.CPU) List(VoiceMeasuredBackendPolicy.FIXTURE_COUNT) { 100L } else List(VoiceMeasuredBackendPolicy.FIXTURE_COUNT) { 80L }
            val record = record(store.key(backend, "us", 2), backend, now, samples, backend != VoiceSpeechBackend.CPU)
            store.save(record)
            assertEquals(record, store.read(record.key, now))
        }
        assertEquals(
            VoiceSpeechBackend.QNN_GPU,
            VoiceMeasuredBackendPolicy.choose(backends.mapNotNull { store.read(store.key(it, "us", 2), now) }, now)
        )
    }

    @Test fun activeVoiceAndRateChangeBothFactoryIdentityAndComparisonScope() {
        val store = VoiceBackendCapabilityStore(context)
        val baseline = store.key(VoiceSpeechBackend.QNN_HTP, "us", 2, KokoroVoice.HEART, 1f)
        assertNotEquals(baseline, store.key(VoiceSpeechBackend.QNN_HTP, "us", 2, KokoroVoice.ADAM, 1f))
        assertNotEquals(baseline, store.key(VoiceSpeechBackend.QNN_HTP, "us", 2, KokoroVoice.HEART, 1.25f))
        val factory = QualcommVoicePhraseEngineFactory(context, VoiceSpeechBackend.AUTO, {}, voice = KokoroVoice.HEART, speechRate = 1f)
        assertNotEquals(factory.identity, QualcommVoicePhraseEngineFactory(context, VoiceSpeechBackend.AUTO, {}, voice = KokoroVoice.ADAM, speechRate = 1f).identity)
        assertNotEquals(factory.identity, QualcommVoicePhraseEngineFactory(context, VoiceSpeechBackend.AUTO, {}, voice = KokoroVoice.HEART, speechRate = 1.25f).identity)
    }

    @Test fun invalidProbeCannotEraseWorkingHardwareReceiptAndSupportCannotRefreshRanking() {
        val store = VoiceBackendCapabilityStore(context)
        val key = store.key(VoiceSpeechBackend.QNN_HTP, "us", 2)
        val measured = record(key, VoiceSpeechBackend.QNN_HTP, now, List(VoiceMeasuredBackendPolicy.FIXTURE_COUNT) { 100L }, true)
        store.save(measured)
        for (invalid in listOf(
            measured.copy(observedAtMillis = now + 1, failure = VoiceSpeechFailureCode.FRONTEND_INPUT_TOO_LONG),
            measured.copy(observedAtMillis = now + 1, failure = VoiceSpeechFailureCode.EXECUTION_FAILED, failureStage = VoiceSpeechStage.FRONTEND)
        )) {
            store.save(invalid)
            assertEquals(measured, store.read(key, now + 1))
        }
        val support = measured.copy(observedAtMillis = now + 2, sampleMs = emptyList())
        store.save(support)
        assertEquals(measured, store.read(key, now + 2))
        store.retest(VoiceSpeechBackend.QNN_HTP)
        store.save(support)
        assertTrue(store.read(key, now + 2)?.verifiedAccelerator == true)
        assertNull(store.read(key, now + 2)?.scoreMs)
    }

    @Test fun oldOversizedProbeNegativeReceiptIsInvalidatedByProtocol() {
        val store = VoiceBackendCapabilityStore(context)
        val key = store.key(VoiceSpeechBackend.QNN_HTP, "us", 2)
        val old = record(key, VoiceSpeechBackend.QNN_HTP, now, failure = VoiceSpeechFailureCode.EXECUTION_FAILED)
            .copy(protocol = "fixed-three-phrases-v1-mean-complete-cost-v1")
        store.save(old)
        assertNull(store.read(key, now))
        val current = record(key, VoiceSpeechBackend.QNN_HTP, now + 1, failure = VoiceSpeechFailureCode.DEVICE_UNAVAILABLE, failureStage = VoiceSpeechStage.PREPARING)
            .copy(inputBytes = 40, inputByteLimit = 128, tokenLimit = 510, discoverySummary = "stage=no_gpu;type=1")
        store.save(current)
        assertEquals(current, store.read(key, now + 1))
    }

    @Test fun malformedStoredTimingOrBooleanCannotBeCoercedIntoQualification() {
        val store = VoiceBackendCapabilityStore(context)
        val key = store.key(VoiceSpeechBackend.QNN_HTP, "us", 2)
        val valid = record(key, VoiceSpeechBackend.QNN_HTP, now, List(VoiceMeasuredBackendPolicy.FIXTURE_COUNT) { 80L }, true)
        val preferences = context.getSharedPreferences("voice_backend_capabilities_v1", Context.MODE_PRIVATE)
        for (mutation in listOf<(org.json.JSONObject) -> Unit>(
            { it.getJSONArray("samples").put(0, 80.5) },
            { it.getJSONArray("samples").put(0, "80") },
            { it.put("accelerated", "true") }
        )) {
            store.save(valid)
            val raw = JSONArray(preferences.getString("receipts", null))
            mutation(raw.getJSONObject(0))
            preferences.edit().putString("receipts", raw.toString()).commit()
            assertNull(store.read(key, now))
        }
    }
}
