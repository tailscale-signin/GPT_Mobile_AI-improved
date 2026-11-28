package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import android.os.Build
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class VoiceRecognitionCpuSelection(val threads: Int?, val reason: String?, val decodeBusyMs: Map<Int, Double> = emptyMap())

@Serializable
internal data class VoiceRecognitionCpuReceipt(
    val key: String,
    val observedAtMillis: Long,
    val candidates: List<Int>,
    val selectedThreads: Int,
    val samples: List<VoiceBenchObservation>
)

/** A matched fixed-fixture CPU recommendation never chooses a model, accelerator or battery winner. */
internal object VoiceRecognitionCpuCalibration {
    const val PREFERENCES = "voice_recognition_cpu_calibration_v1"
    const val VERSION = 1
    private const val MAX_RECEIPT_CHARACTERS = 65_536
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1_000
    private val json = Json { ignoreUnknownKeys = false }

    fun supported(pack: VoiceRecognitionModelPack): Boolean = pack.compute == VoiceRecognitionCompute.CPU &&
        pack.model.engine == VoiceRecognitionEngine.SHERPA &&
        pack.model.supportsLanguage(VoiceRecognitionBenchmarkCase.FIXTURE_LANGUAGE)

    fun candidates(processors: Int = VoiceCpuSchedulingPolicy.deviceLimit()): List<Int> =
        listOf(1, 2, 4).filter { it <= VoiceCpuSchedulingPolicy.deviceLimit(processors) }

    /** Current model, runtime, fixture, protocol, OS/ABI and CPU limit must still match the receipt. */
    fun recommendedThreads(context: Context, pack: VoiceRecognitionModelPack): Int? {
        if (!supported(pack)) return null
        return runCatching { store(context).recommendedThreads(pack) }.getOrNull()
    }

    /** Commit a complete cohort atomically. Failed or interrupted attempts cannot replace a receipt. */
    fun save(context: Context, pack: VoiceRecognitionModelPack, counts: List<Int>, observations: List<VoiceBenchObservation>): Boolean {
        if (!supported(pack) || counts != candidates()) return false
        return runCatching { store(context).save(pack, observations) }.getOrDefault(false)
    }

    /** Byte storage is injectable so persistence, rollback and stale-receipt behavior are testable. */
    internal class Store(
        private val read: (String) -> String?,
        private val write: (String, String) -> Boolean,
        private val device: String,
        private val counts: List<Int>,
        private val nowMillis: () -> Long = System::currentTimeMillis
    ) {
        fun recommendedThreads(pack: VoiceRecognitionModelPack): Int? {
            if (!supported(pack)) return null
            return runCatching {
                val key = recommendationKey(pack, device, counts)
                val payload = read(key) ?: return null
                if (payload.length > MAX_RECEIPT_CHARACTERS) return null
                val receipt = json.decodeFromString<VoiceRecognitionCpuReceipt>(payload)
                val elapsed = nowMillis() - receipt.observedAtMillis
                if (receipt.key != key || receipt.candidates != counts || receipt.observedAtMillis < 0 || elapsed !in 0..MAX_AGE_MS) return null
                val selected = select(pack, counts, receipt.samples).threads
                selected?.takeIf { it == receipt.selectedThreads }
            }.getOrNull()
        }

        fun save(pack: VoiceRecognitionModelPack, observations: List<VoiceBenchObservation>): Boolean {
            if (!supported(pack)) return false
            val samples = observations.filter { it.metadata["recognitionWarmup"] == "false" && "recognitionFixtureId" in it.metadata }
            val selected = select(pack, counts, samples).threads ?: return false
            val key = recommendationKey(pack, device, counts)
            val receipt = VoiceRecognitionCpuReceipt(key, nowMillis(), counts, selected, samples)
            val payload = json.encodeToString(receipt)
            if (payload.length > MAX_RECEIPT_CHARACTERS) return false
            return runCatching { write(key, payload) }.getOrDefault(false)
        }
    }

    private fun store(context: Context): Store {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return Store({ preferences.getString(it, null) }, { key, payload -> preferences.edit().putString(key, payload).commit() }, deviceKey(), candidates())
    }

    fun select(pack: VoiceRecognitionModelPack, expectedCandidates: List<Int>, samples: List<VoiceBenchObservation>): VoiceRecognitionCpuSelection {
        fun unavailable(reason: String) = VoiceRecognitionCpuSelection(null, reason)
        if (!supported(pack)) return unavailable("RECOGNITION_CPU_CALIBRATION_UNSUPPORTED")
        if (expectedCandidates.isEmpty() || expectedCandidates.size > 3 || expectedCandidates.distinct() != expectedCandidates || expectedCandidates.any { it !in listOf(1, 2, 4) }) {
            return unavailable("RECOGNITION_CPU_CANDIDATES_INVALID")
        }
        val fixtures = voiceBenchRecordings.keys
        if (samples.size != expectedCandidates.size * fixtures.size) return unavailable("RECOGNITION_CPU_COHORT_INCOMPLETE")
        val cohort = samples.map { it.metadata["recognitionCalibrationCohort"] }.distinct().singleOrNull()
        if (cohort == null || !cohort.matches(Regex("[0-9a-f-]{32,36}"))) return unavailable("RECOGNITION_CPU_COHORT_MISMATCH")
        val grouped = samples.groupBy { it.recognition?.requestedThreads }
        if (grouped.keys != expectedCandidates.toSet()) return unavailable("RECOGNITION_CPU_COHORT_INCOMPLETE")
        val scores = linkedMapOf<Int, Double>()
        for (threads in expectedCandidates) {
            val rows = grouped.getValue(threads)
            if (rows.size != fixtures.size || rows.map { it.metadata["recognitionFixtureId"] }.toSet() != fixtures) return unavailable("RECOGNITION_CPU_FIXTURE_COVERAGE_INCOMPLETE")
            val expectedIdentity = VoiceRecognitionBenchmarkCase.forPack(pack, threads).identity
            var score = 0.0
            for (row in rows) {
                val id = row.metadata.getValue("recognitionFixtureId")
                if (row.recognition != expectedIdentity || row.metadata["recognitionFixtureSha256"] != voiceBenchRecordingHashes[id]) return unavailable("RECOGNITION_CPU_IDENTITY_MISMATCH")
                if (row.status != VoiceBenchStatus.MEASURED || row.reason != null || row.metadata["recognitionCalibrationWarmupComplete"] != "true" || row.metadata["recognitionWarmup"] != "false") {
                    return unavailable("RECOGNITION_CPU_CANDIDATE_INCOMPLETE")
                }
                if (row.metadata["recognitionActualProvider"] != "cpu" || row.metadata["recognitionExecutionVerified"] != "true") return unavailable("RECOGNITION_CPU_EXECUTION_UNVERIFIED")
                if (row.metadata["recognitionRuntime"] != expectedIdentity.runtimeId || row.metadata["recognitionModelIdentity"] != expectedIdentity.modelSha256) return unavailable("RECOGNITION_CPU_IDENTITY_MISMATCH")
                val metrics = row.metrics
                if (metrics["wordErrors"] != 0.0 || metrics["wordErrorRate"] != 0.0 || metrics["referenceWords"] != voiceBenchWords(voiceBenchRecordings.getValue(id)).size.toDouble()) {
                    return unavailable("RECOGNITION_CPU_FIXTURE_ACCURACY_UNQUALIFIED")
                }
                if (metrics["recognitionProtocolVersion"] != VoiceRecognitionBenchmarkProtocol.VERSION.toDouble() ||
                    metrics["realtimePacedInput"] != 1.0 ||
                    metrics["inputSampleRate"] != 16_000.0 ||
                    metrics["inputAudioFrames"] != voiceBenchRecordingFrames.getValue(id).toDouble()
                ) {
                    return unavailable("RECOGNITION_CPU_INPUT_PROTOCOL_MISMATCH")
                }
                val thermalSamples = metrics["thermalSampleCount"]
                if (metrics["thermalBefore"] !in setOf(0.0, 1.0) ||
                    metrics["thermalAfter"] !in setOf(0.0, 1.0) ||
                    metrics["maximumObservedThermalStatus"] !in setOf(0.0, 1.0) ||
                    metrics["unknownThermalSampleCount"] != 0.0 ||
                    thermalSamples == null ||
                    !thermalSamples.isFinite() ||
                    thermalSamples < 2.0
                ) {
                    return unavailable("RECOGNITION_CPU_THERMAL_EVIDENCE_UNQUALIFIED")
                }
                val measured = metrics["decodeBusyMs"] ?: return unavailable("RECOGNITION_CPU_TIMING_UNAVAILABLE")
                if (!measured.isFinite() || measured <= 0) return unavailable("RECOGNITION_CPU_TIMING_INVALID")
                score += measured
                if (!score.isFinite()) return unavailable("RECOGNITION_CPU_TIMING_INVALID")
            }
            scores[threads] = score
        }
        val fastest = scores.values.minOrNull() ?: return unavailable("RECOGNITION_CPU_TIMING_UNAVAILABLE")
        // A five-percent tie uses fewer CPU workers; this does not establish lower battery use.
        val selected = scores.filterValues { it <= fastest * 1.05 }.keys.minOrNull()
        return VoiceRecognitionCpuSelection(selected, null, scores)
    }

    internal fun recommendationKey(pack: VoiceRecognitionModelPack, device: String, counts: List<Int>): String {
        val identity = VoiceRecognitionBenchmarkCase.forPack(pack, 1).identity.copy(requestedThreads = 0)
        return "threads:$VERSION:" + digest("$identity|$device|${counts.joinToString(",")}")
    }

    private fun deviceKey(): String = digest("${Build.FINGERPRINT}|${Build.SUPPORTED_ABIS.joinToString()}|${Build.VERSION.SDK_INT}|${VoiceCpuSchedulingPolicy.deviceLimit()}")
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
