package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Context allocation is padded to 256 tokens in this pinned runtime. */
internal fun ggufMemoryComponents(
    modelBytes: Long,
    contextTokens: Int,
    kvBytesPerToken: Long,
    batch: Int = 256,
    microBatch: Int = 128,
    threads: Int = 1,
    accelerator: String = "cpu",
    measured: NativeAllocationObservation? = null,
    geometryKnown: Boolean = false
): Map<String, Long> {
    require(modelBytes >= 0 && contextTokens in 1..32768 && batch in 1..4096 && microBatch in 1..batch && threads in 1..64)
    val aligned = ((contextTokens.toLong() + 255) / 256) * 256
    val mib = 1024L * 1024
    val exact =
        measured?.takeIf {
            it.schemaVersion == 2 &&
                it.contextTokens.toLong() == aligned &&
                it.batchTokens == batch &&
                it.microBatchTokens == microBatch
        }
    val estimatedKv =
        Math.multiplyExact(
            aligned,
            if (geometryKnown &&
                kvBytesPerToken > 0
            ) {
                kvBytesPerToken
            } else {
                maxOf(256L * 1024, kvBytesPerToken)
            }
        )
    // Correctly keyed native ownership evidence may replace the old fallback floor.
    // Legacy is_host-based observations are retained only as conservative upward evidence.
    val kv =
        if (exact != null && exact.contextOwnedBytes > 0) {
            maxOf(if (geometryKnown) estimatedKv else 0, exact.contextOwnedBytes)
        } else {
            maxOf(estimatedKv, measured?.contextOwnedBytes ?: 0)
        }
    val workspaceFloor = 96 * mib + batch.toLong() * microBatch * 64 + threads * mib
    val workspace =
        if (exact?.generationObserved == true &&
            exact.computeOwnedBytes > 0
        ) {
            exact.computeOwnedBytes
        } else {
            maxOf(workspaceFloor, measured?.computeOwnedBytes ?: 0)
        }
    // File bytes include the tokenizer, template and tensor directory. Exact native
    // ownership counts resident/repacked tensors directly; do not impose the file
    // length as another floor after that evidence has been collected.
    val weights =
        if (exact != null && exact.modelOwnedBytes > 0 && exact.modelTensorBytes > 0) {
            maxOf(exact.modelTensorBytes, exact.modelOwnedBytes)
        } else {
            maxOf(modelBytes, measured?.modelOwnedBytes ?: 0)
        }
    val overlap = if (LocalAccelerators.isGpu(accelerator) && exact == null) modelBytes else 0L
    val uncertainty = maxOf(64 * mib, (kv + workspace) / 16)
    return linkedMapOf(
        "weights" to weights,
        "kvCache" to kv,
        "contextMetadata" to 32 * mib,
        "computeWorkspace" to workspace,
        "backendOverlap" to overlap,
        "uncertaintyMargin" to uncertainty
    )
}

/**
 * Schema 1 uses legacy host accessibility buckets. Schema 2's host/device aliases
 * mean CPU/dedicated GPU ownership; shared and unknown buckets are disjoint.
 * Host-accessible bytes and OS PSS are overlapping observations and are never summed.
 */
@Serializable
data class NativeAllocationObservation(
    val schemaVersion: Int = 1,
    val contextTokens: Int,
    val batchTokens: Int,
    val microBatchTokens: Int,
    val hostModelBytes: Long,
    val hostContextBytes: Long,
    val hostComputeBytes: Long,
    val deviceModelBytes: Long,
    val deviceContextBytes: Long,
    val deviceComputeBytes: Long,
    val modelTensorBytes: Long,
    val processPssBytes: Long?,
    val observedAtMillis: Long = System.currentTimeMillis(),
    val sharedModelBytes: Long = 0,
    val sharedContextBytes: Long = 0,
    val sharedComputeBytes: Long = 0,
    val unknownModelBytes: Long = 0,
    val unknownContextBytes: Long = 0,
    val unknownComputeBytes: Long = 0,
    val hostAccessibleModelBytes: Long? = null,
    val hostAccessibleContextBytes: Long? = null,
    val hostAccessibleComputeBytes: Long? = null,
    val generationObserved: Boolean = false
) {
    val modelOwnedBytes get() = hostModelBytes + deviceModelBytes + sharedModelBytes + unknownModelBytes
    val contextOwnedBytes get() = hostContextBytes + deviceContextBytes + sharedContextBytes + unknownContextBytes
    val computeOwnedBytes get() = hostComputeBytes + deviceComputeBytes + sharedComputeBytes + unknownComputeBytes
    val cpuOwnedBytes: Long? get() = if (schemaVersion == 2) hostModelBytes + hostContextBytes + hostComputeBytes else null
    val gpuOwnedBytes: Long? get() = if (schemaVersion == 2) deviceModelBytes + deviceContextBytes + deviceComputeBytes else null
    val sharedOwnedBytes: Long? get() = if (schemaVersion == 2) sharedModelBytes + sharedContextBytes + sharedComputeBytes else null
    val unknownOwnedBytes: Long? get() = if (schemaVersion == 2) unknownModelBytes + unknownContextBytes + unknownComputeBytes else null

    fun validated() =
        apply {
            require(schemaVersion in 1..2 && contextTokens in 1..32768 && batchTokens > 0 && microBatchTokens in 1..batchTokens)
            require(
                listOf(
                    hostModelBytes,
                    hostContextBytes,
                    hostComputeBytes,
                    deviceModelBytes,
                    deviceContextBytes,
                    deviceComputeBytes,
                    modelTensorBytes,
                    sharedModelBytes,
                    sharedContextBytes,
                    sharedComputeBytes,
                    unknownModelBytes,
                    unknownContextBytes,
                    unknownComputeBytes
                ).all {
                    it in
                        0..(256L * 1024 * 1024 * 1024)
                }
            )
            require(
                listOfNotNull(hostAccessibleModelBytes, hostAccessibleContextBytes, hostAccessibleComputeBytes).all {
                    it in
                        0..(256L * 1024 * 1024 * 1024)
                }
            )
            require(
                schemaVersion == 2 ||
                    sharedModelBytes + sharedContextBytes + sharedComputeBytes + unknownModelBytes + unknownContextBytes +
                    unknownComputeBytes ==
                    0L
            )
        }
}

/** Exact ownership schema/artifact/build/device/shape key; legacy ownership is never mixed into schema 2. */
internal class LocalMemoryCalibrationStore(
    private val directory: File
) {
    fun read(key: String): NativeAllocationObservation? =
        runCatching {
            val path = File(directory, "$key.json")
            if (!path.isFile || path.length() !in 1..16384) return null
            Json.decodeFromString<NativeAllocationObservation>(path.readText()).validated()
        }.getOrNull()

    fun write(
        key: String,
        value: NativeAllocationObservation
    ) {
        require(key.matches(Regex("[a-f0-9]{64}")))
        value.validated()
        val old =
            read(key)?.takeIf {
                it.schemaVersion == value.schemaVersion &&
                    it.contextTokens == value.contextTokens &&
                    it.batchTokens == value.batchTokens &&
                    it.microBatchTokens == value.microBatchTokens
            }
        val retained =
            if (old == null) {
                value
            } else {
                value.copy(
                    hostModelBytes = maxOf(old.hostModelBytes, value.hostModelBytes),
                    hostContextBytes = maxOf(old.hostContextBytes, value.hostContextBytes),
                    hostComputeBytes = maxOf(old.hostComputeBytes, value.hostComputeBytes),
                    deviceModelBytes = maxOf(old.deviceModelBytes, value.deviceModelBytes),
                    deviceContextBytes = maxOf(old.deviceContextBytes, value.deviceContextBytes),
                    deviceComputeBytes = maxOf(old.deviceComputeBytes, value.deviceComputeBytes),
                    sharedModelBytes = maxOf(old.sharedModelBytes, value.sharedModelBytes),
                    sharedContextBytes = maxOf(old.sharedContextBytes, value.sharedContextBytes),
                    sharedComputeBytes = maxOf(old.sharedComputeBytes, value.sharedComputeBytes),
                    unknownModelBytes = maxOf(old.unknownModelBytes, value.unknownModelBytes),
                    unknownContextBytes = maxOf(old.unknownContextBytes, value.unknownContextBytes),
                    unknownComputeBytes = maxOf(old.unknownComputeBytes, value.unknownComputeBytes),
                    hostAccessibleModelBytes = listOfNotNull(old.hostAccessibleModelBytes, value.hostAccessibleModelBytes).maxOrNull(),
                    hostAccessibleContextBytes =
                    listOfNotNull(
                        old.hostAccessibleContextBytes,
                        value.hostAccessibleContextBytes
                    ).maxOrNull(),
                    hostAccessibleComputeBytes =
                    listOfNotNull(
                        old.hostAccessibleComputeBytes,
                        value.hostAccessibleComputeBytes
                    ).maxOrNull(),
                    processPssBytes = listOfNotNull(old.processPssBytes, value.processPssBytes).maxOrNull(),
                    generationObserved =
                    old.generationObserved || value.generationObserved
                )
            }
        check(directory.mkdirs() || directory.isDirectory)
        val pending = File.createTempFile("calibration-", ".pending", directory)
        try {
            pending.writeText(Json.encodeToString(retained))
            java.nio.file.Files.move(
                pending.toPath(),
                File(directory, "$key.json").toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            pending.delete()
        }
    }

    companion object {
        fun key(
            artifact: String,
            spec: LocalEngineSpec
        ): String {
            val processors = Runtime.getRuntime().availableProcessors()
            val cpu = GgufCpuThreads.resolve(spec.cpuThreads, processors)
            val prompt = GgufCpuThreads.resolve(spec.ggufPromptThreads ?: spec.cpuThreads, processors)
            return dev.chungjungsoo.gptmobile.data.research.researchHash(
                "ownership-v2|$artifact|${android.os.Build.FINGERPRINT}|${dev.chungjungsoo.gptmobile.BuildConfig.APP_COMMIT}|${dev.melo.gguf.GgufNative.REVISION}|${spec.accelerator}|${spec.maxTokens}|${spec.ggufBatchTokens}|${spec.ggufMicroBatchTokens}|$cpu|$prompt|${spec.ggufGpuLayers}"
            )
        }
    }
}

internal fun Map<String, Long>.totalMemoryBytes(): Long = values.fold(0L, Math::addExact)
