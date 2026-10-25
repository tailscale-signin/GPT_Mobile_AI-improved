package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class GgufDeviceSample(
    val backend: String,
    val layers: Int,
    val threads: Int,
    val promptThreads: Int,
    val prefill: Double,
    val decode: Double,
    val measuredPromptTokens: Int,
    val outputTokens: Int,
    val benchmark: Boolean,
    val experiment: CpuExperimentTrial? = null,
    val nativeGenerationId: String? = null,
    val engineInstanceId: String? = null,
    val totalPromptTokens: Long? = null,
    val renderedPromptDigest: String? = null,
    val reusedPromptTokens: Long? = null,
    val cpuTimeMs: Long? = null,
    val thermalState: String? = null,
    val terminalKind: String? = null,
    val cancellationRecovered: Boolean = false,
    val observedAtMillis: Long = System.currentTimeMillis()
)

/** Key includes artifact, runtime/app build, firmware, context and batch configuration. */
internal class GgufDevicePlans(
    private val directory: File
) {
    fun read(key: String): List<GgufDeviceSample> =
        runCatching {
            val file = File(directory, "$key.json")
            if (!file.isFile || file.length() > 256 * 1024) return emptyList()
            Json.decodeFromString<List<GgufDeviceSample>>(file.readText())
        }.getOrDefault(emptyList())

    @Synchronized fun record(
        key: String,
        sample: GgufDeviceSample
    ) {
        require(key.matches(Regex("[a-f0-9]{64}")))
        if (!sample.prefill.isFinite() || !sample.decode.isFinite() || sample.outputTokens < 0) return
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "$key.json")
        val temporary = File.createTempFile("plan-", ".tmp", directory)
        try {
            temporary.outputStream().use { output ->
                output.write(Json.encodeToString((read(key) + sample).takeLast(96)).toByteArray())
                output.fd.sync()
            }
            java.nio.file.Files.move(
                temporary.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private fun median(values: List<Double>) =
            values.sorted().let {
                if (it.size % 2 ==
                    0
                ) {
                    (it[it.size / 2 - 1] + it[it.size / 2]) / 2
                } else {
                    it[it.size / 2]
                }
            }

        /** Mixed prompt/cache histories and unacknowledged requested settings never tune Auto. */
        fun threads(
            samples: List<GgufDeviceSample>,
            prompt: Boolean
        ): Int? {
            val trials =
                samples.filter {
                    val experiment = it.experiment
                    it.backend == "cpu" &&
                        it.benchmark &&
                        experiment != null &&
                        it.threads == experiment.threadCount &&
                        it.promptThreads == experiment.threadCount &&
                        it.outputTokens >= 32 &&
                        (it.totalPromptTokens ?: 0) >= 128 &&
                        !it.renderedPromptDigest.isNullOrBlank() &&
                        it.nativeGenerationId != null &&
                        it.cpuTimeMs != null &&
                        it.terminalKind in setOf("eos", "output_limit") &&
                        it.thermalState in setOf("NORMAL", "LIGHT") &&
                        if (experiment.cacheRegime ==
                            "cold"
                        ) {
                            it.reusedPromptTokens == 0L
                        } else {
                            it.reusedPromptTokens == it.totalPromptTokens?.minus(1)
                        }
                }
            val experiments =
                trials.groupBy {
                    checkNotNull(it.experiment).let { trial ->
                        Triple(trial.experimentId, trial.fixtureDigest, it.renderedPromptDigest)
                    }
                }
            val candidates =
                experiments.values.mapNotNull { cohort ->
                    val byRegime = cohort.groupBy { checkNotNull(it.experiment).cacheRegime }
                    if (byRegime.keys != setOf("cold", "cache_hot")) return@mapNotNull null
                    if (byRegime.values.any { cases ->
                            val byThreads = cases.groupBy { it.threads }
                            byThreads.keys != setOf(2, 4, 6, 8) ||
                                byThreads.values.any { values ->
                                    values.map { checkNotNull(it.experiment).repeatIndex }.distinct().size < 3
                                }
                        }
                    ) {
                        return@mapNotNull null
                    }
                    val winners =
                        byRegime.mapNotNull { (regime, cases) ->
                            if (prompt && regime == "cache_hot") return@mapNotNull null
                            val byThreads = cases.groupBy { it.threads }
                            if (byThreads.keys != setOf(2, 4, 6, 8)) return@mapNotNull null
                            if (byThreads.values.any { values ->
                                    values.map { checkNotNull(it.experiment).repeatIndex }.distinct().size < 3
                                }
                            ) {
                                return@mapNotNull null
                            }
                            val rates = byThreads.mapValues { (_, values) -> median(values.map { if (prompt) it.prefill else it.decode }) }
                            if (rates.values.any { !it.isFinite() || it <= 0 }) return@mapNotNull null
                            val best = checkNotNull(rates.maxByOrNull { it.value })
                            val baseline = checkNotNull(rates[4])
                            // A different preset needs a repeatable material benefit over the diagnostic baseline.
                            if (best.key != 4 && best.value < baseline * 1.10) 4 else best.key
                        }
                    val required = if (prompt) 1 else 2
                    winners.takeIf { it.size == required && it.distinct().size == 1 }?.first()?.let {
                        it to
                            cohort.maxOf { sample -> sample.observedAtMillis }
                    }
                }
            return candidates.maxByOrNull { it.second }?.first
        }

        /** Manual offload increases need a completed generation at the previously qualified layer count. */
        fun hasProductiveGpuGeneration(samples: List<GgufDeviceSample>, backend: String, minimumLayers: Int): Boolean =
            samples.any { it.backend == backend && it.layers >= minimumLayers && productiveGpuGeneration(it) }

        private fun productiveGpuGeneration(sample: GgufDeviceSample): Boolean =
            sample.backend in setOf("gpu", "opencl") &&
                sample.layers > 0 &&
                sample.measuredPromptTokens > 0 &&
                sample.outputTokens > 0 &&
                sample.prefill > 0 &&
                sample.decode > 0 &&
                sample.nativeGenerationId != null &&
                sample.engineInstanceId != null &&
                sample.terminalKind in setOf("eos", "output_limit")

        /** Readiness requires actual offload, productive prefill/decode, repetition, and cancellation recovery. */
        fun gpu(samples: List<GgufDeviceSample>): GgufDeviceSample? =
            samples
                .filter(::productiveGpuGeneration)
                .groupBy { it.backend to it.layers }
                .values
                .filter { group ->
                    group.map { it.nativeGenerationId }.distinct().size >= 2 && group.any { it.cancellationRecovered }
                }.flatMap { it }
                .maxByOrNull { it.observedAtMillis }
    }
}
