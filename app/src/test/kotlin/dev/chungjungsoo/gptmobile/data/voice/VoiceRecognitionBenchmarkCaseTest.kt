package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.MockMakers
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings

class VoiceRecognitionBenchmarkCaseTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun everyVerifiedModelRouteHasAFullCampaignCaseAfterEarlyOutputScreening() {
        val stages = fullVoiceBenchPlan(2, 1f, 8)
        val cases = stages.mapNotNull { it.recognition }
        assertEquals(VoiceRecognitionModelCatalog.packs.map { it.model to it.compute }.toSet(), cases.map { it.configuration.model to it.configuration.compute }.toSet())
        val lastQuickOutput = stages.indexOfLast { it.label.endsWith("quick synthesis screening") }
        assertTrue(stages.withIndex().filter { it.value.recognition?.configuration?.model?.let { model -> model != VoiceRecognitionModel.MOONSHINE_TINY } == true }.all { it.index > lastQuickOutput })
        assertTrue(stages.filter { it.mode == VoiceSpeechBenchmarkMode.QUICK }.all { it.recognition == null })
        assertEquals(VoiceListeningStyle.entries.size, cases.count { it.configuration.model == VoiceRecognitionModel.MOONSHINE_TINY })
    }

    @Test
    fun modelRuntimeThreadsProtocolAndFixtureChangesInvalidateTheFrozenCase() {
        val pack = pack(VoiceRecognitionCompute.CPU)
        val original = VoiceRecognitionBenchmarkCase.forPack(pack, 3)
        assertNull(original.unavailableReason(pack))
        for (identity in listOf(
            original.identity.copy(modelSha256 = "a".repeat(64)),
            original.identity.copy(runtimeId = "different-runtime"),
            original.identity.copy(requestedThreads = 7),
            original.identity.copy(protocolVersion = original.identity.protocolVersion + 1),
            original.identity.copy(fixtureHashes = original.identity.fixtureHashes + ("ready" to "b".repeat(64)))
        )) {
            val changed = original.copy(identity = identity)
            assertEquals("RECOGNITION_BENCHMARK_IDENTITY_CHANGED", changed.unavailableReason(pack))
            assertNotEquals(original.toString(), changed.toString())
        }
        assertEquals("RECOGNITION_MODEL_SELECTION_CHANGED", original.unavailableReason(pack(VoiceRecognitionCompute.GPU)))
    }

    @Test
    fun nonEnglishModelCannotBeScoredAgainstEnglishFixtures() {
        val pack = requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.ZIPFORMER_FR))
        val case = VoiceRecognitionBenchmarkCase.forPack(pack, 2)
        assertEquals("fr-FR", case.languageTag)
        assertTrue(case.identity.fixtureHashes.isEmpty())
        assertEquals("FIXED_RECOGNITION_FIXTURE_LANGUAGE_UNAVAILABLE", case.unavailableReason(pack))
    }

    @Test
    fun routeAndThreadIdentitiesDoNotImplyIdenticalModelsOrConfiguredMoonshineThreads() {
        val cases = VoiceRecognitionCompute.entries.map { VoiceRecognitionBenchmarkCase.forPack(pack(it), 4) }
        assertEquals(3, cases.map { it.identity.modelSha256 }.distinct().size)
        assertEquals(listOf(560, 560, 320), cases.map { it.identity.nativeChunkMillis })
        val moonshine = VoiceRecognitionBenchmarkCase.forPack(requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.MOONSHINE_SMALL)), 4)
        assertEquals("NATIVE_RUNTIME_DEFAULT_NOT_CONFIGURED", moonshine.identity.threadControl)
        assertEquals("EXPLICIT_CPU_OPERATOR_THREADS", cases.first().identity.threadControl)
    }

    @Test
    fun acceleratorTimingRequiresObservedExecutionOnTheRequestedProvider() {
        for (compute in listOf(VoiceRecognitionCompute.GPU, VoiceRecognitionCompute.NPU)) {
            val case = VoiceRecognitionBenchmarkCase.forPack(pack(compute), 2)
            val expected = if (compute == VoiceRecognitionCompute.GPU) "qnn-gpu+cpu" else "qnn-htp-context"
            val measurement = VoiceBenchObservation("ready", VoiceBenchStatus.MEASURED, metrics = mapOf("decodeBusyMs" to 42.0))
            val identity = mapOf("recognitionRuntime" to case.identity.runtimeId, "recognitionModelIdentity" to case.identity.modelSha256)
            for (metadata in listOf(emptyMap(), identity + mapOf("recognitionActualProvider" to expected), identity + mapOf("recognitionActualProvider" to "cpu", "recognitionExecutionVerified" to "true"), mapOf("recognitionActualProvider" to expected, "recognitionExecutionVerified" to "true"))) {
                val unverified = case.qualify(measurement.copy(metadata = metadata))
                assertEquals(VoiceBenchStatus.PARTIAL, unverified.status)
                assertEquals("RECOGNITION_ACCELERATOR_EXECUTION_UNVERIFIED", unverified.reason)
                assertEquals(case.identity, unverified.recognition)
            }
            assertEquals(VoiceBenchStatus.MEASURED, case.qualify(measurement.copy(metadata = identity + mapOf("recognitionActualProvider" to expected, "recognitionExecutionVerified" to "true"))).status)
            assertEquals(VoiceBenchStatus.FAILED, case.qualify(measurement.copy(status = VoiceBenchStatus.FAILED, reason = "ASR_PROVIDER_LOAD")).status)
        }
    }

    @Test
    fun missingRecognizerPackNeverCreatesANativeEngineOrStartsADownload() = runBlocking {
        val directory = temp.newFolder("missing-recognition")
        val tiny = LocalVoiceModelStore(directory) { throw AssertionError("A benchmark cannot start a download") }
        val registry = RecognitionModelRegistry(directory, tiny)
        val case = VoiceRecognitionBenchmarkCase.forPack(pack(VoiceRecognitionCompute.GPU), 2)
        val context = mock(Context::class.java, withSettings().mockMaker(MockMakers.SUBCLASS))
        val rows = voiceBenchRecognition(context, registry, case, VoiceListeningStyle.NATURAL, { true }, AtomicReference(null), { throw AssertionError("No native owner exists") }, { })

        assertEquals(VoiceBenchStatus.UNAVAILABLE, rows.single().status)
        assertEquals("RECOGNITION_MODEL_NOT_INSTALLED_OR_NOT_VERIFIED", rows.single().reason)
        assertEquals(case.identity, rows.single().recognition)
        assertTrue(rows.single().metrics.isEmpty())
        assertFalse(directory.walkTopDown().any { it.isFile })
    }

    @Test
    fun nativeRetirementPrecedesLeaseReleaseAndCancellationKeepsOwnershipUntilClose() {
        val events = mutableListOf<String>()
        val decoder = Decoder(events)
        val interrupt = AtomicReference<(() -> Unit)?>(null)
        val answer = withVoiceRecognitionBenchmarkDecoder(AutoCloseable { events += "lease" }, interrupt, { throw AssertionError("Unexpected quarantine") }, { decoder }) {
            events += "run"
            interrupt.get()!!.invoke()
            assertEquals(listOf("run", "cancel"), events)
            "answer"
        }

        assertEquals("answer", answer)
        assertEquals(listOf("run", "cancel", "close", "lease"), events)
        assertNull(interrupt.get())
    }

    @Test
    fun failedCloseQuarantinesTheDecoderAndLeaseWithoutReleasingMappedWeights() {
        val events = mutableListOf<String>()
        val decoder = Decoder(events, failClose = true)
        val lease = AutoCloseable { events += "lease" }
        val interrupt = AtomicReference<(() -> Unit)?>(null)
        var retained: Any? = null

        assertThrows(IllegalStateException::class.java) {
            withVoiceRecognitionBenchmarkDecoder(lease, interrupt, { retained = it }, { decoder }) { events += "run" }
        }
        assertEquals(listOf("run", "close"), events)
        assertEquals(listOf(decoder, lease), retained)
        assertNull(interrupt.get())
    }

    @Test
    fun factoryAndWorkloadFailuresRetireOnlyTheirAcquiredResources() {
        val events = mutableListOf<String>()
        val interrupt = AtomicReference<(() -> Unit)?>(null)
        assertThrows(IllegalStateException::class.java) {
            withVoiceRecognitionBenchmarkDecoder(AutoCloseable { events += "lease" }, interrupt, { throw AssertionError() }, { error("initialization failed") }) { }
        }
        assertEquals(listOf("lease"), events)
        events.clear()
        assertThrows(CancellationException::class.java) {
            withVoiceRecognitionBenchmarkDecoder(AutoCloseable { events += "lease" }, interrupt, { throw AssertionError() }, { Decoder(events) }) { throw CancellationException() }
        }
        assertEquals(listOf("close", "lease"), events)
        assertNull(interrupt.get())
    }

    @Test
    fun wholeDeviceCounterObservationsNeverBecomeRecognizerPowerOrUnknownZeroes() {
        val identity = VoiceRecognitionBenchmarkCase.forPack(pack(VoiceRecognitionCompute.CPU), 2).identity
        val missing = voiceRecognitionBenchmarkHealth(null, null, null, identity)
        assertEquals(VoiceBenchStatus.UNAVAILABLE, missing.status)
        assertTrue(missing.metrics.isEmpty())
        assertFalse("observedCounterDecreaseNanowattHours" in missing.metadata)
        val battery = VoiceSpeechBenchmarkEnergyObservation(
            VoiceSpeechBackend.CPU,
            VoiceSpeechEnergyEndpoint(100, VoiceSpeechEnergyChargeState.DISCHARGING, false),
            VoiceSpeechEnergyEndpoint(90, VoiceSpeechEnergyChargeState.DISCHARGING, false),
            elapsedMs = 5_000,
            monitorRegistered = true,
            monitoringConfirmed = true
        )
        val measured = voiceRecognitionBenchmarkHealth(0, 1, battery, identity)
        assertEquals("10", measured.metadata["observedCounterDecreaseNanowattHours"])
        assertEquals("NOT_ATTRIBUTABLE_TO_RECOGNIZER", measured.metadata["batteryRanking"])
        assertTrue(measured.measurementScope!!.contains("NOT_RECOGNIZER_ENERGY_POWER_OR_BATTERY_LIFE"))
        val powered = voiceRecognitionBenchmarkHealth(0, 1, battery.copy(externalPowerObserved = true), identity)
        assertEquals("EXTERNAL_POWER_OBSERVED", powered.metadata["batteryCounterStatus"])
        assertFalse("observedCounterDecreaseNanowattHours" in powered.metadata)
    }

    @Test
    fun recognitionExportRetainsExactModelAndRuntimeIdentitiesWithoutHardwareClaims() {
        val identity = VoiceRecognitionBenchmarkCase.forPack(pack(VoiceRecognitionCompute.NPU), 2).identity
        val row = VoiceBenchObservation("ready", VoiceBenchStatus.UNAVAILABLE, "MODEL_MISSING", recognition = identity)
        val json = Json.parseToJsonElement(Json.encodeToString(row)).jsonObject
        assertEquals(identity.modelSha256, json.getValue("recognition").jsonObject.getValue("modelSha256").jsonPrimitive.content)
        assertEquals(identity.runtimeId, json.getValue("recognition").jsonObject.getValue("runtimeId").jsonPrimitive.content)
        assertEquals(VoiceBenchStatus.UNAVAILABLE.name, json.getValue("status").jsonPrimitive.content)
        assertFalse("executionVerified" in json)
    }

    private fun pack(compute: VoiceRecognitionCompute) = requireNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.NEMOTRON_3_5, compute))

    private class Decoder(private val events: MutableList<String>, private val failClose: Boolean = false) : LocalSpeechDecoder {
        override fun start(directory: File, events: (LocalSpeechEvent) -> Unit) = Unit
        override fun accept(samples: FloatArray) = Unit
        override fun finish() = Unit
        override fun cancel() {
            events += "cancel"
        }
        override fun close() {
            events += "close"
            if (failClose) error("retirement unconfirmed")
        }
    }
}
