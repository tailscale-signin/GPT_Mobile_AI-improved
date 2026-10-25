package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import android.os.Build
import android.os.SystemClock
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The ModelInfo call declares all supported backends; it does not execute a selected one. */
internal data class LiteRtInspectionEnvironment(
    val runtimeIdentity: String,
    val deviceIdentity: String,
    val firmwareDigest: String
) {
    companion object {
        fun current(context: Context): LiteRtInspectionEnvironment {
            val install = context.packageManager.getPackageInfo(context.packageName, 0)
            return LiteRtInspectionEnvironment(
                runtimeIdentity = "litert-lm:${BuildConfig.LITERT_LM_VERSION}|qairt:${BuildConfig.QAIRT_VERSION}|app:${install.longVersionCode}:${install.lastUpdateTime}|inspection-schema:1",
                deviceIdentity = PackageDigest.sha256("${Build.MANUFACTURER}|${Build.MODEL}|${Build.SOC_MODEL}|${Build.SUPPORTED_ABIS.joinToString()}".toByteArray()),
                firmwareDigest = PackageDigest.sha256(Build.FINGERPRINT.orEmpty().toByteArray())
            )
        }
    }
}

/**
 * Only immutable parsed declarations are persisted. A backend/configuration-specific result
 * is produced after fresh file validation and compatibility checking on every request.
 * Strong inode/change-time identity is mandatory for reuse of either digest or metadata.
 */
internal class LiteRtInspectionCache(
    private val directory: File,
    private val identityDirectory: File,
    private val environment: LiteRtInspectionEnvironment,
    private val readMetadata: (String) -> LocalModelCapabilities?,
    private val validateFile: (File) -> Unit = { file ->
        val result = LocalModelValidator.validate(file.path)
        require(result is ModelValidationResult.Valid) { "Model integrity preflight failed: $result" }
    },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val beforeNative: (String) -> Unit = {},
    private val afterNative: () -> Unit = {}
) {
    @Serializable
    private data class Stored(val declarationKey: String, val capabilities: LocalModelCapabilities, val capabilitiesDigest: String, val schemaVersion: Int = 1)

    private val json = Json { ignoreUnknownKeys = true }
    private val memory = LinkedHashMap<String, LocalModelCapabilities>()

    @Volatile var latestReceipt: RuntimeInspectionReceipt? = null
        private set

    @Synchronized
    fun allowVerification(file: File, allowNative: (String) -> Unit) {
        validateFile(file)
        val identity = PackageDigest.validateInstalledObserved(file, identityDirectory)
        val declarationKey = PackageDigest.sha256("${environment.runtimeIdentity}|${environment.deviceIdentity}|${environment.firmwareDigest}|${identity.digest}".toByteArray())
        allowNative("inspection|$declarationKey")
        memory.remove(declarationKey)
        File(directory, "$declarationKey.json").takeIf(File::exists)?.let {
            check(it.delete()) { "Could not invalidate inspected metadata for explicit verification" }
        }
    }

    @Synchronized
    fun inspect(file: File, spec: LocalEngineSpec?, checkCurrent: () -> Unit = {}): LocalModelCapabilities? {
        val started = clock()
        val backend = spec?.accelerator?.lowercase() ?: "generic_declaration"
        // Exclude location and diagnostic-only labels; all compatibility-affecting engine fields remain.
        val configuration = spec?.engineConfiguration()?.copy(modelPath = "", litertDispatchLibDir = null, tuningLocked = false, nativeMetricsEnabled = false)?.toString() ?: "all_declared_backends"
        val configurationDigest = PackageDigest.sha256(configuration.toByteArray())
        var receipt = RuntimeInspectionReceipt(
            inspectionId = UUID.randomUUID().toString(),
            runtimeIdentity = environment.runtimeIdentity,
            deviceIdentity = environment.deviceIdentity,
            firmwareDigest = environment.firmwareDigest,
            backend = backend,
            configurationDigest = configurationDigest,
            startedAtElapsedMs = started,
            observedAtElapsedMs = started,
            outcome = "IN_PROGRESS"
        )
        try {
            checkCurrent()
            val validatedAt = clock()
            validateFile(file)
            receipt = receipt.copy(fileValidationMs = clock() - validatedAt)
            val identity = PackageDigest.validateInstalledObserved(file, identityDirectory, checkCurrent)
            receipt = receipt.copy(
                artifactDigest = identity.digest,
                strongFileIdentityAvailable = identity.strongFileIdentity != null,
                digestCacheDisposition = identity.cacheDisposition,
                digestValidationMs = identity.elapsedMs,
                digestLockWaitMs = identity.lockWaitMs,
                modelBytesHashed = identity.bytesHashed
            )
            val declarationKey = PackageDigest.sha256("${environment.runtimeIdentity}|${environment.deviceIdentity}|${environment.firmwareDigest}|${identity.digest}".toByteArray())
            val resultKey = PackageDigest.sha256("$declarationKey|$backend|$configurationDigest".toByteArray())
            receipt = receipt.copy(cacheKeyDigest = resultKey)
            fun unchanged() {
                checkCurrent()
                if (identity.strongFileIdentity != null && identity.strongFileIdentity != PackageDigest.installedFileIdentity(file)) {
                    memory.remove(declarationKey)
                    throw IOException("Model changed during metadata inspection; retry after installation finishes")
                }
            }
            fun parse(): LocalModelCapabilities? {
                val beforeAt = clock()
                beforeNative("inspection|$declarationKey")
                var journalMs = clock() - beforeAt
                val parseAt = clock()
                try {
                    return readMetadata(file.path)
                } finally {
                    receipt = receipt.copy(opaqueMetadataMs = clock() - parseAt)
                    val afterAt = clock()
                    afterNative()
                    journalMs += clock() - afterAt
                    receipt = receipt.copy(journalMs = journalMs)
                }
            }
            var metadata = memory[declarationKey].takeIf { identity.strongFileIdentity != null }
            if (metadata != null) {
                receipt = receipt.copy(metadataCacheDisposition = "memory_hit", opaqueMetadataMs = 0, metadataCacheWaitMs = 0)
            } else if (identity.strongFileIdentity == null) {
                receipt = receipt.copy(metadataCacheDisposition = "identity_unavailable_native_parse")
                metadata = parse()
            } else {
                check(directory.isDirectory || directory.mkdirs()) { "Could not prepare metadata cache" }
                val waitAt = clock()
                RandomAccessFile(File(directory, "metadata.lock"), "rw").use { lock ->
                    lock.channel.lock().use {
                        receipt = receipt.copy(metadataCacheWaitMs = clock() - waitAt)
                        unchanged()
                        val saved = File(directory, "$declarationKey.json")
                        metadata = saved.takeIf { it.isFile && it.length() in 1..65_536 }?.let {
                            runCatching { json.decodeFromString<Stored>(it.readText()) }.getOrNull()
                        }?.takeIf {
                            it.schemaVersion == 1 &&
                                it.declarationKey == declarationKey &&
                                it.capabilitiesDigest == PackageDigest.sha256(json.encodeToString(LocalModelCapabilities.serializer(), it.capabilities).toByteArray())
                        }?.capabilities
                        if (metadata != null) {
                            receipt = receipt.copy(metadataCacheDisposition = "disk_hit", opaqueMetadataMs = 0)
                        } else {
                            receipt = receipt.copy(metadataCacheDisposition = "miss_native_parse")
                            metadata = parse()
                            unchanged()
                            metadata?.let { value ->
                                val capabilityDigest = PackageDigest.sha256(json.encodeToString(LocalModelCapabilities.serializer(), value).toByteArray())
                                val content = json.encodeToString(Stored.serializer(), Stored(declarationKey, value, capabilityDigest))
                                if (content.toByteArray().size <= 65_536) {
                                    val temporary = File.createTempFile(declarationKey, ".tmp", directory)
                                    try {
                                        temporary.outputStream().use { output ->
                                            output.write(content.toByteArray())
                                            output.fd.sync()
                                        }
                                        Files.move(temporary.toPath(), saved.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                                    } finally {
                                        temporary.delete()
                                    }
                                    directory.listFiles().orEmpty().filter { it.name.endsWith(".json") }.sortedByDescending(File::lastModified).drop(64).forEach { it.delete() }
                                }
                            }
                        }
                    }
                }
                metadata?.let {
                    if (memory.size >= 32) memory.remove(memory.keys.first())
                    memory[declarationKey] = it
                }
            }
            unchanged()
            val checkedAt = clock()
            if (spec != null) metadata?.validate(spec)
            receipt = receipt.copy(compatibilityValidationMs = clock() - checkedAt, outcome = if (metadata == null) "UNAVAILABLE" else "PASSED")
            return metadata
        } catch (error: Throwable) {
            receipt = receipt.copy(outcome = "FAILED", failureClass = error.javaClass.simpleName)
            throw error
        } finally {
            latestReceipt = receipt.copy(observedAtElapsedMs = clock(), totalMs = clock() - started)
        }
    }
}
