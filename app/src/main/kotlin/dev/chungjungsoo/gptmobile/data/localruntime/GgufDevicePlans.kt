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
    val benchmark: Boolean
)

/** Key includes artifact, runtime/app build, firmware, driver, context and batch configuration. */
internal class GgufDevicePlans(private val directory: File) {
    fun read(key: String): List<GgufDeviceSample> = runCatching {
        val file = File(directory, "$key.json")
        if (!file.isFile || file.length() > 128 * 1024) return emptyList()
        Json.decodeFromString<List<GgufDeviceSample>>(file.readText())
    }.getOrDefault(emptyList())

    @Synchronized fun record(key: String, sample: GgufDeviceSample) {
        require(key.matches(Regex("[a-f0-9]{64}")))
        if (!sample.prefill.isFinite() || !sample.decode.isFinite() || sample.outputTokens <= 0) return
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "$key.json")
        val temporary = File(directory, "$key.tmp")
        temporary.outputStream().use { output ->
            output.write(Json.encodeToString((read(key) + sample).takeLast(96)).toByteArray())
            output.fd.sync()
        }
        java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
    companion object {
        fun threads(samples: List<GgufDeviceSample>, prompt: Boolean): Int? {
            val trials = samples.filter { it.backend == "cpu" && it.benchmark && it.outputTokens >= 32 && it.measuredPromptTokens >= 128 }
            fun median(values: List<Double>) = values.sorted()[values.size / 2]
            return trials.groupBy { if (prompt) it.promptThreads else it.threads }
                .filterValues { it.size >= 2 }
                .maxByOrNull { (_, trials) -> median(trials.map { if (prompt) it.prefill else it.decode }) }?.key
        }
        fun gpu(samples: List<GgufDeviceSample>): GgufDeviceSample? = samples.lastOrNull { it.backend in setOf("gpu", "opencl") && it.layers > 0 && it.outputTokens > 0 }
    }
}
