package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Conservative components; the pinned engine pads context allocation to 256 tokens. */
internal fun ggufMemoryComponents(modelBytes: Long, contextTokens: Int, kvBytesPerToken: Long, batch: Int = 256, microBatch: Int = 128, threads: Int = 1, accelerator: String = "cpu", measured: NativeAllocationObservation? = null): Map<String, Long> {
    require(modelBytes >= 0 && contextTokens in 1..32768 && batch in 1..4096 && microBatch in 1..batch && threads in 1..64)
    val aligned = ((contextTokens.toLong() + 255) / 256) * 256
    val mib = 1024L * 1024
    return linkedMapOf(
        "weights" to maxOf(modelBytes, measured?.let { it.hostModelBytes + it.deviceModelBytes } ?: 0),
        "kvCache" to maxOf(Math.multiplyExact(aligned, maxOf(256L * 1024, kvBytesPerToken)), measured?.let { it.hostContextBytes + it.deviceContextBytes } ?: 0),
        "contextMetadata" to 32 * mib,
        "computeWorkspace" to maxOf(96 * mib + batch.toLong() * microBatch * 64 + threads * mib, measured?.let { it.hostComputeBytes + it.deviceComputeBytes } ?: 0),
        "backendOverlap" to if (accelerator == LocalAccelerators.GPU) modelBytes else 0
    )
}

@Serializable
data class NativeAllocationObservation(val schemaVersion: Int = 1, val contextTokens: Int, val batchTokens: Int, val microBatchTokens: Int, val hostModelBytes: Long, val hostContextBytes: Long, val hostComputeBytes: Long, val deviceModelBytes: Long, val deviceContextBytes: Long, val deviceComputeBytes: Long, val modelTensorBytes: Long, val processPssBytes: Long?, val observedAtMillis: Long = System.currentTimeMillis()) {
    fun validated() = apply {
        require(schemaVersion == 1 && contextTokens in 1..32768 && batchTokens > 0 && microBatchTokens in 1..batchTokens)
        require(listOf(hostModelBytes, hostContextBytes, hostComputeBytes, deviceModelBytes, deviceContextBytes, deviceComputeBytes, modelTensorBytes).all { it in 0..(256L * 1024 * 1024 * 1024) })
    }
}

/** Exact artifact/build/device/plan key. Atomic rename gives cross-process readers complete receipts. */
internal class LocalMemoryCalibrationStore(private val directory: File) {
    fun read(key: String): NativeAllocationObservation? = runCatching {
        val path = File(directory, "$key.json")
        if (!path.isFile || path.length() !in 1..16384) return null
        Json.decodeFromString<NativeAllocationObservation>(path.readText()).validated()
    }.getOrNull()
    fun write(key: String, value: NativeAllocationObservation) {
        require(key.matches(Regex("[a-f0-9]{64}")))
        value.validated()
        val old = read(key)
        val retained = if (old == null) {
            value
        } else {
            value.copy(
                hostModelBytes = maxOf(old.hostModelBytes, value.hostModelBytes),
                hostContextBytes = maxOf(old.hostContextBytes, value.hostContextBytes),
                hostComputeBytes = maxOf(old.hostComputeBytes, value.hostComputeBytes),
                deviceModelBytes = maxOf(old.deviceModelBytes, value.deviceModelBytes),
                deviceContextBytes = maxOf(old.deviceContextBytes, value.deviceContextBytes),
                deviceComputeBytes = maxOf(old.deviceComputeBytes, value.deviceComputeBytes),
                processPssBytes = listOfNotNull(old.processPssBytes, value.processPssBytes).maxOrNull()
            )
        }
        check(directory.mkdirs() || directory.isDirectory)
        val pending = File.createTempFile("calibration-", ".pending", directory)
        try {
            pending.writeText(Json.encodeToString(retained))
            java.nio.file.Files.move(pending.toPath(), File(directory, "$key.json").toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            pending.delete()
        }
    }
    companion object {
        fun key(artifact: String, spec: LocalEngineSpec): String = dev.chungjungsoo.gptmobile.data.research.researchHash("$artifact|${android.os.Build.FINGERPRINT}|${dev.chungjungsoo.gptmobile.BuildConfig.APP_COMMIT}|${dev.melo.gguf.GgufNative.REVISION}|${spec.accelerator}|${spec.maxTokens}|${spec.ggufBatchTokens}|${spec.ggufMicroBatchTokens}|${spec.cpuThreads}|${spec.ggufPromptThreads}")
    }
}

internal fun Map<String, Long>.totalMemoryBytes(): Long = values.fold(0L, Math::addExact)
