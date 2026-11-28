package dev.chungjungsoo.gptmobile.data.voice

import dev.melo.kokoro.StreamingAsr
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** Included in the frozen stage plan and every recognition observation, including unavailable rows. */
@Serializable
internal data class VoiceRecognitionBenchmarkIdentity(
    val modelId: String,
    val packId: String,
    val modelSha256: String,
    val runtimeId: String,
    val languageTag: String,
    val requestedCompute: String,
    val requestedThreads: Int,
    val threadControl: String,
    val nativeChunkMillis: Int?,
    val protocolVersion: Int,
    val fixtureHashes: Map<String, String>,
    val referenceHashes: Map<String, String>
)

/** Explicit model cases belong to the full campaign; quick synthesis screening does not load them. */
internal data class VoiceRecognitionBenchmarkCase(
    val configuration: VoiceRecognitionConfiguration,
    val languageTag: String,
    val identity: VoiceRecognitionBenchmarkIdentity,
    val calibrationCandidates: List<Int> = emptyList()
) {
    init {
        require(calibrationCandidates.size <= 3 && calibrationCandidates.distinct() == calibrationCandidates && calibrationCandidates.all { it in setOf(1, 2, 4) })
        require(calibrationCandidates.isEmpty() || configuration.compute == VoiceRecognitionCompute.CPU && configuration.model.engine == VoiceRecognitionEngine.SHERPA)
    }

    fun unavailableReason(pack: VoiceRecognitionModelPack?): String? = when {
        pack == null -> "RECOGNITION_MODEL_PACK_UNAVAILABLE"
        pack.model != configuration.model || pack.compute != configuration.compute -> "RECOGNITION_MODEL_SELECTION_CHANGED"
        identity != identity(pack, configuration.threads, languageTag) -> "RECOGNITION_BENCHMARK_IDENTITY_CHANGED"
        !pack.model.supportsLanguage(languageTag) -> "RECOGNITION_MODEL_LANGUAGE_UNSUPPORTED"
        languageTag != FIXTURE_LANGUAGE -> "FIXED_RECOGNITION_FIXTURE_LANGUAGE_UNAVAILABLE"
        else -> null
    }

    fun qualify(observation: VoiceBenchObservation): VoiceBenchObservation {
        val accelerated = configuration.compute != VoiceRecognitionCompute.CPU
        val actual = observation.metadata["recognitionActualProvider"]
        val matches = when (configuration.compute) {
            VoiceRecognitionCompute.CPU -> actual == "cpu"
            VoiceRecognitionCompute.GPU -> actual == "qnn-gpu" || actual == "qnn-gpu+cpu"
            VoiceRecognitionCompute.NPU -> actual == "qnn-htp-context"
        }
        val verified = observation.metadata["recognitionExecutionVerified"] == "true" &&
            matches &&
            observation.metadata["recognitionRuntime"] == identity.runtimeId &&
            observation.metadata["recognitionModelIdentity"] == identity.modelSha256
        return observation.copy(
            recognition = identity,
            status = if (accelerated && observation.status == VoiceBenchStatus.MEASURED && !verified) VoiceBenchStatus.PARTIAL else observation.status,
            reason = if (accelerated && observation.status == VoiceBenchStatus.MEASURED && !verified) "RECOGNITION_ACCELERATOR_EXECUTION_UNVERIFIED" else observation.reason
        )
    }

    companion object {
        const val FIXTURE_LANGUAGE = "en-US"
        const val MOONSHINE_RUNTIME_ID = "moonshine-voice-0.1.6"

        fun forPack(pack: VoiceRecognitionModelPack, threads: Int): VoiceRecognitionBenchmarkCase {
            val language = if (pack.model.supportsLanguage(FIXTURE_LANGUAGE)) FIXTURE_LANGUAGE else "fr-FR"
            val configuration = VoiceRecognitionConfiguration(pack.model, pack.compute, threads.coerceIn(1, 8))
            return VoiceRecognitionBenchmarkCase(configuration, language, identity(pack, configuration.threads, language))
        }

        private fun identity(pack: VoiceRecognitionModelPack, threads: Int, languageTag: String) = VoiceRecognitionBenchmarkIdentity(
            modelId = pack.model.id,
            packId = pack.id,
            modelSha256 = pack.modelIdentity,
            runtimeId = if (pack.model.engine == VoiceRecognitionEngine.MOONSHINE) MOONSHINE_RUNTIME_ID else StreamingAsr.RUNTIME_ID,
            languageTag = languageTag,
            requestedCompute = pack.compute.name,
            requestedThreads = threads,
            threadControl = if (pack.model.engine == VoiceRecognitionEngine.MOONSHINE) "NATIVE_RUNTIME_DEFAULT_NOT_CONFIGURED" else "EXPLICIT_CPU_OPERATOR_THREADS",
            nativeChunkMillis = pack.chunkMillis,
            protocolVersion = VoiceRecognitionBenchmarkProtocol.VERSION,
            fixtureHashes = if (languageTag == FIXTURE_LANGUAGE) voiceBenchRecordingHashes.toSortedMap() else emptyMap(),
            referenceHashes = if (languageTag == FIXTURE_LANGUAGE) {
                voiceBenchRecordings.toSortedMap().mapValues { (_, text) -> MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } }
            } else {
                emptyMap()
            }
        )
    }
}
