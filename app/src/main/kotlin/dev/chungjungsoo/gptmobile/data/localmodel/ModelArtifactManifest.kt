package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
enum class ArtifactRuntime { LITERT_LM, GENIE_LEGACY, GENIEX_QAIRT, GENIEX_LLAMA_CPP, LLAMA_CPP }

@Serializable
enum class ArtifactEvidence { DISCOVERED, PREFLIGHT_PASSED, SMOKE_TESTED, VERIFIED_ON_DEVICE, INCOMPATIBLE, QUARANTINED }

@Serializable
data class ArtifactFile(val path: String, val sizeBytes: Long, val sha256: String)

@Serializable
data class ArtifactValidation(
    val artifactSha256: String,
    val runtimeVersion: String,
    val deviceFingerprint: String,
    val backend: String,
    val suiteVersion: String,
    val passed: Boolean
)

/** Runtime, quantization and package format are independent identities. Unknown stays unknown. */
@Serializable
data class ModelArtifactManifest(
    val schemaVersion: Int = 1,
    val familyId: String,
    val artifactId: String,
    val runtime: ArtifactRuntime,
    val format: String,
    val entryFile: String,
    val files: List<ArtifactFile>,
    val contextTokens: Int,
    val supportedBackends: Set<String>,
    val sourceRevision: String = "",
    val sourceUrl: String = "",
    val runtimeVersion: String = "",
    val soc: String? = null,
    val tokenizerFile: String? = null,
    val projectorFile: String? = null,
    val architecture: String? = null,
    val templateId: String? = null,
    val weightPrecision: String? = null,
    val activationPrecision: String? = null,
    val kvPrecision: String? = null,
    val tools: Boolean = false,
    val thinking: Boolean = false,
    val vision: Boolean = false,
    val evidence: ArtifactEvidence = ArtifactEvidence.DISCOVERED,
    val validations: List<ArtifactValidation> = emptyList()
) {
    fun validate(root: File, backend: String? = null, deviceSoc: String? = null, checkCurrent: () -> Unit = {}) {
        require(schemaVersion in 1..3) { "Unsupported artifact manifest version $schemaVersion" }
        require(familyId.isNotBlank() && artifactId.isNotBlank() && contextTokens > 0)
        require(files.isNotEmpty() && files.size <= 256)
        require(files.map { it.path.lowercase() }.distinct().size == files.size) { "Duplicate package paths" }
        require(files.any { it.path == entryFile } && (tokenizerFile == null || files.any { it.path == tokenizerFile })) { "Missing entry or tokenizer asset" }
        require(runtime !in setOf(ArtifactRuntime.GENIEX_LLAMA_CPP, ArtifactRuntime.LLAMA_CPP) || (format == "gguf" && entryFile.endsWith(".gguf"))) { "GGUF runtime requires a GGUF artifact" }
        require(runtime != ArtifactRuntime.LITERT_LM || (format == "litertlm" && entryFile.endsWith(".litertlm"))) { "LiteRT runtime requires a LiteRT artifact" }
        require(supportedBackends.isNotEmpty() && supportedBackends.all { it in setOf("cpu", "gpu", "npu") })
        require(projectorFile == null || schemaVersion == 2 && files.any { it.path == projectorFile }) { "Missing versioned projector asset" }
        if (runtime == ArtifactRuntime.GENIEX_LLAMA_CPP && "npu" in supportedBackends) {
            require(schemaVersion == 2 && runtimeVersion == dev.chungjungsoo.gptmobile.BuildConfig.GENIEX_VERSION && weightPrecision.equals("Q4_0", true) && !architecture.isNullOrBlank()) { "GGUF Hexagon requires a v2 manifest, the pinned runtime, explicit architecture and Q4_0 precision" }
            if (backend == "npu") require(canonicalSoc(deviceSoc.orEmpty()) in setOf("SM8750", "SM8850")) { "This chipset is outside the initial GGUF Hexagon qualification corpus. Choose an explicit CPU/GPU alternative." }
        }
        if (vision && runtime == ArtifactRuntime.GENIEX_LLAMA_CPP) require(schemaVersion == 2 && projectorFile != null) { "GGUF image input requires its exact hashed projector" }
        require(backend == null || backend in supportedBackends) { "Requested backend is unsupported by this artifact" }
        if (runtime in setOf(ArtifactRuntime.GENIE_LEGACY, ArtifactRuntime.GENIEX_QAIRT)) {
            require(supportedBackends == setOf("npu") && !soc.isNullOrBlank() && tokenizerFile != null) { "Compiled Qualcomm bundles require exact SoC, tokenizer and NPU-only execution" }
        }
        if (backend == "npu" && deviceSoc != null && runtime != ArtifactRuntime.GENIEX_LLAMA_CPP) {
            require(!soc.isNullOrBlank() && canonicalSoc(soc) == canonicalSoc(deviceSoc)) { "Package SoC does not match this device" }
        }
        require(evidence !in setOf(ArtifactEvidence.INCOMPATIBLE, ArtifactEvidence.QUARANTINED)) { "Artifact is quarantined or incompatible" }
        files.forEach { item ->
            checkCurrent()
            val file = safeFile(root, item.path, checkCurrent)
            require(item.sizeBytes > 0 && file.isFile && file.length() == item.sizeBytes) { "Missing or truncated asset: ${item.path}" }
            require(item.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Missing SHA-256: ${item.path}" }
            PackageDigest.verifyInstalledAssetCached(file, item.sha256, checkCurrent)
        }
        if (runtime in setOf(ArtifactRuntime.GENIEX_LLAMA_CPP, ArtifactRuntime.LLAMA_CPP)) {
            val magic = safeFile(root, entryFile, checkCurrent).inputStream().use { stream -> ByteArray(4).also { require(stream.read(it) == 4) } }
            require(magic.contentEquals("GGUF".toByteArray())) { "Invalid GGUF header" }
        }
        if (runtime == ArtifactRuntime.LLAMA_CPP) {
            require(schemaVersion == 3 && supportedBackends in setOf(setOf("cpu"), setOf("cpu", "gpu")) && !vision && projectorFile == null) { "Standalone GGUF requires a schema-3 CPU/GPU text manifest" }
            // GGUF is portable model data, not a binary linked to the importing engine.
            // Keep the import revision as provenance; qualify the actual architecture,
            // template and tensors against the currently packaged engine at load time.
            require(runtimeVersion.matches(Regex("[a-fA-F0-9]{40}"))) { "Missing GGUF import runtime revision" }
        }
        if (runtime == ArtifactRuntime.GENIEX_QAIRT) validateQairtConfiguration(root)
    }

    /** Preview accepts data-only, relative-path QAIRT bundles, never library models. */
    private fun validateQairtConfiguration(root: File) {
        require(format == "qairt_context" && entryFile == "genie_config.json") { "QAIRT requires genie_config.json at the bundle root" }
        val declared = files.map { it.path }.toSet()
        val visited = mutableSetOf<String>()
        fun readConfig(path: String): JsonObject {
            val file = safeFile(root, path)
            require(path in declared && file.length() <= 1_048_576) { "Missing or oversized QAIRT configuration" }
            return Json.parseToJsonElement(file.readText()) as? JsonObject ?: error("QAIRT configuration must be an object")
        }
        fun checkReferences(node: JsonElement, key: String = "", depth: Int = 0) {
            require(depth <= 32) { "QAIRT configuration nesting exceeds the preview limit" }
            when (node) {
                is JsonObject -> node.forEach { (name, value) -> checkReferences(value, name, depth + 1) }
                is JsonArray -> node.forEach { checkReferences(it, key, depth + 1) }
                is JsonPrimitive -> if (node.isString) {
                    val value = node.content
                    val locationKey = key.lowercase().let { name ->
                        name.split('_', '-').any { it in setOf("path", "file", "filename", "library", "directory") } ||
                            name in setOf("extensions", "ctx-bins", "model-bin", "forecast-prefix-name")
                    }
                    if (locationKey || value.any { it in "/\\:." }) {
                        require(value in declared) { "QAIRT configuration references an undeclared asset: $key" }
                        safeFile(root, value)
                        // Tokenizer vocabulary is data, not a native path configuration.
                        if (value.endsWith(".json") && value != tokenizerFile && visited.add(value)) {
                            checkReferences(readConfig(value), depth = depth + 1)
                        }
                    }
                }
            }
        }
        val config = readConfig(entryFile)
        val dialog = config["dialog"] as? JsonObject ?: error("Missing QAIRT dialog")
        val context = dialog["context"] as? JsonObject ?: error("Missing compiled QAIRT context")
        require((context["size"] as? JsonPrimitive)?.intOrNull == contextTokens) { "Manifest context disagrees with the compiled QAIRT configuration" }
        val tokenizer = dialog["tokenizer"] as? JsonObject ?: error("Missing QAIRT tokenizer configuration")
        require((tokenizer["path"] as? JsonPrimitive)?.content == tokenizerFile) { "QAIRT tokenizer disagrees with the manifest" }
        val engine = dialog["engine"] as? JsonObject ?: error("Missing QAIRT engine")
        val model = engine["model"] as? JsonObject ?: error("Missing QAIRT model")
        require((model["type"] as? JsonPrimitive)?.content == "binary") { "Only data-only QAIRT context binaries are supported" }
        val binary = model["binary"] as? JsonObject ?: error("Missing QAIRT binaries")
        val shards = binary["ctx-bins"] as? JsonArray ?: error("Missing QAIRT shard inventory")
        require(shards.isNotEmpty() && shards.size <= 256 && shards.all { (it as? JsonPrimitive)?.content in declared }) { "Missing declared QAIRT shards" }
        visited += entryFile
        checkReferences(config)
    }

    /** Imported badge text is never trusted. Verification resolves to a matching test tuple. */
    fun verifiedOnDevice(digest: String, version: String, fingerprint: String, backend: String, suite: String): Boolean =
        evidence == ArtifactEvidence.VERIFIED_ON_DEVICE &&
            validations.any {
                it.passed &&
                    it.artifactSha256 == digest &&
                    it.runtimeVersion == version &&
                    it.deviceFingerprint == fingerprint &&
                    it.backend == backend &&
                    it.suiteVersion == suite
            }

    companion object {
        private fun canonicalSoc(value: String) = value.uppercase().replace(Regex("[^A-Z0-9]"), "").substringBefore("AC")
        fun safeFile(root: File, path: String, checkCurrent: () -> Unit = {}): File {
            require(path.isNotBlank() && !File(path).isAbsolute && '\\' !in path && ':' !in path && path.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Unsafe package path" }
            require(path.substringAfterLast('.').lowercase() !in setOf("so", "dll", "dylib", "exe", "sh", "bat", "py", "dex", "jar", "apk")) { "Imported code is not a model asset" }
            LinkedModelSources.resolveAsset(root, path, checkCurrent)?.let { return it }
            val file = File(root, path).canonicalFile
            require(file.toPath().startsWith(root.canonicalFile.toPath()) && file != root.canonicalFile) { "Package path escapes installation" }
            return file
        }
    }
}

internal object ArtifactManifestStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    fun read(file: File): ModelArtifactManifest {
        require(file.isFile && file.length() <= 262_144) { "Missing or oversized model manifest" }
        return json.decodeFromString<ModelArtifactManifest>(file.readText())
    }
    fun write(file: File, manifest: ModelArtifactManifest) {
        val staging = File(file.parentFile, file.name + ".tmp")
        staging.outputStream().use { stream ->
            stream.write(json.encodeToString(manifest).toByteArray())
            stream.fd.sync()
        }
        java.nio.file.Files.move(staging.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
