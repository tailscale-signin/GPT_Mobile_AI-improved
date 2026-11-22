package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.melo.kokoro.TimedKokoro
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        store.save(VoiceBackendCapability(cpu, VoiceSpeechBackend.CPU, now, listOf(30, 40, 50)))
        store.save(
            VoiceBackendCapability(
                gpu,
                VoiceSpeechBackend.QNN_GPU,
                now + 1,
                failure = VoiceSpeechFailureCode.GRAPH_PARTITION,
                failureStage = VoiceSpeechStage.PREPARING,
                nativeCode = 11,
                tokenCount = 17
            )
        )
        store.save(VoiceBackendCapability(npu, VoiceSpeechBackend.QNN_HTP, now + 2, listOf(20, 30, 40), true))
        assertEquals(11, store.read(gpu, now + 2)?.nativeCode)
        assertEquals(17, store.read(gpu, now + 2)?.tokenCount)
        store.retest(VoiceSpeechBackend.QNN_GPU)
        assertNull(store.read(gpu, now + 2))
        assertEquals(2, listOf(cpu, npu).count { store.read(it, now + 2) != null })
        store.retest(VoiceSpeechBackend.AUTO)
        assertNull(store.read(cpu, now + 2))
        assertNull(store.read(npu, now + 2))
    }

    @Test fun receiptsRemainBoundedAndMalformedStorageCannotManufactureSupport() {
        val store = VoiceBackendCapabilityStore(context)
        val keys = (0 until 30).map { store.key(VoiceSpeechBackend.QNN_GPU, "lexicon-$it", 2) }
        keys.forEachIndexed { index, key -> store.save(VoiceBackendCapability(key, VoiceSpeechBackend.QNN_GPU, now + index, listOf(20, 30, 40), true)) }
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
            store.save(VoiceBackendCapability(legacyKey, backend, now, listOf(10, 10), backend != VoiceSpeechBackend.CPU))
            assertNotEquals(legacyKey, currentKey)
            assertNull(store.read(currentKey, now))
        }
        for (backend in backends) {
            val samples = if (backend == VoiceSpeechBackend.CPU) listOf(100L, 100L, 100L) else listOf(80L, 80L, 80L)
            val record = VoiceBackendCapability(store.key(backend, "us", 2), backend, now, samples, backend != VoiceSpeechBackend.CPU)
            store.save(record)
            assertEquals(record, store.read(record.key, now))
        }
        assertEquals(
            VoiceSpeechBackend.QNN_GPU,
            VoiceMeasuredBackendPolicy.choose(backends.mapNotNull { store.read(store.key(it, "us", 2), now) }, now)
        )
    }
}
