package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Original parts remain immutable. Only verified, session-owned files enter the final share. */
internal object DiagnosticArchiveBundle {
    private const val SCHEMA = "gptmobile.diagnostic-bundle.v1"
    private const val MANIFEST_LIMIT = 256 * 1024
    private const val DEFAULT_BYTE_LIMIT = 256L * 1024 * 1024
    private val digestPattern = Regex("[a-f0-9]{64}")
    private val sessionPattern = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")

    private data class Part(val file: File, val digest: String, val number: Long, val stages: Map<String, JsonObject>)
    private data class Link(val archive: JsonObject, val parentPart: Long, val stage: JsonObject? = null)

    suspend fun prepare(
        result: DiagnosticCaptureResult,
        sourceDirectories: List<File>,
        maximumParts: Int = 64,
        maximumBytes: Long = DEFAULT_BYTE_LIMIT,
        guard: () -> Unit = {}
    ): DiagnosticCaptureResult {
        require(maximumParts in 1..64 && maximumBytes in 1024..DEFAULT_BYTE_LIMIT)
        require(sessionPattern.matches(result.sessionId))
        if ((result.manifest.number("archivePart") ?: 1) <= 1) return result
        val context = currentCoroutineContext()
        fun checkActive() {
            context.ensureActive()
            guard()
        }
        val namePattern = Regex("(?:partial-)?diagnostics-${Regex.escape(result.sessionId)}-part-[1-9][0-9]{0,17}-[0-9a-f-]{36}\\.zip")
        val roots = (listOf(result.file.parentFile) + sourceDirectories).map { it.canonicalFile }.distinct()
        val parts = linkedMapOf<String, Part>()
        val pending = ArrayDeque<Link>()
        val missing = linkedMapOf<String, JsonObject>()
        val rejected = hashSetOf<String>()
        var truncated = false
        var compressedBytes = 0L
        var inflatedBytes = 0L
        var visitedLinks = 0

        fun missing(link: Link, reason: String) {
            val name = link.archive.text("fileName").orEmpty()
            val key = "$name:$reason:${link.stage?.text("stage").orEmpty()}"
            if (missing.size >= 128 && key !in missing) {
                truncated = true
                return
            }
            missing[key] = buildJsonObject {
                put("fileName", name.take(192))
                put("sha256", link.archive.text("sha256")?.take(64)?.let(::JsonPrimitive) ?: JsonNull)
                put("archivePart", link.archive["archivePart"] ?: JsonNull)
                put("stage", link.stage?.get("stage") ?: JsonNull)
                put("reason", reason)
            }
        }

        fun collect(manifest: JsonObject, number: Long) {
            val previous = manifest["previousArchive"] as? JsonObject
            if (number > 1 && previous == null) {
                missing(Link(JsonObject(emptyMap()), number), "PREVIOUS_ARCHIVE_REFERENCE_MISSING")
            }
            previous?.let { pending.addLast(Link(it, number)) }
            (manifest["stages"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach { stage ->
                for (field in listOf("continuedFrom", "sourceReference")) {
                    val reference = stage[field] as? JsonObject ?: continue
                    val archive = reference["archive"] as? JsonObject
                    if (archive == null) {
                        missing(Link(JsonObject(emptyMap()), number, reference), "INVALID_ARCHIVE_REFERENCE")
                    } else {
                        pending.addLast(Link(archive, number, reference))
                    }
                }
            }
        }

        fun load(file: File, expectedDigest: String? = null): Part {
            checkActive()
            require(file.isFile && file.canonicalFile.parentFile in roots && namePattern.matches(file.name)) { "ARCHIVE_UNAVAILABLE" }
            require(file.length() in 1..(maximumBytes - compressedBytes)) { "ARCHIVE_BYTE_LIMIT" }
            val digest = file.inputStream().use { hash(it, maximumBytes - compressedBytes, ::checkActive).second }
            require(expectedDigest == null || digest == expectedDigest) { "ARCHIVE_DIGEST_MISMATCH" }
            val manifest = StagedDiagnosticCapture.verify(file, maximumBytes - inflatedBytes)
            checkActive()
            require(expectedDigest != null || manifest == result.manifest) { "DIAGNOSTIC_ARCHIVE_HANDOFF_CHANGED" }
            require(manifest.text("sessionId") == result.sessionId && manifest["sourceIdentityHash"] == result.manifest["sourceIdentityHash"]) { "ARCHIVE_SOURCE_MISMATCH" }
            val number = requireNotNull(manifest.number("archivePart"))
            require(number > 0 && file.name.contains("-part-$number-")) { "ARCHIVE_PART_MISMATCH" }
            val stages = (manifest["stages"] as JsonArray).filterIsInstance<JsonObject>()
            val inflated = stages.sumOf { requireNotNull(it.number("bytes")) } + manifest.toString().toByteArray(Charsets.UTF_8).size
            require(inflated <= maximumBytes - inflatedBytes) { "ARCHIVE_BYTE_LIMIT" }
            inflatedBytes += inflated
            compressedBytes += file.length()
            collect(manifest, number)
            return Part(file, digest, number, stages.associate { stage -> requireNotNull(stage.text("id")) to JsonObject(stage.filterKeys { it in setOf("fileName", "sha256", "bytes", "records") }) })
        }

        val latest = load(result.file)
        require(latest.number == result.manifest.number("archivePart")) { "DIAGNOSTIC_ARCHIVE_HANDOFF_CHANGED" }
        parts[latest.file.name] = latest
        while (pending.isNotEmpty()) {
            checkActive()
            val link = pending.removeFirst()
            if (++visitedLinks > 4096) {
                missing(link, "DEPENDENCY_LINK_LIMIT")
                truncated = true
                break
            }
            val name = link.archive.text("fileName")
            val digest = link.archive.text("sha256")
            val number = link.archive.number("archivePart")
            if (name == null || !namePattern.matches(name) || digest == null || !digestPattern.matches(digest) || number == null || number !in 1 until link.parentPart || link.stage == null && number != link.parentPart - 1) {
                missing(link, "INVALID_ARCHIVE_REFERENCE")
                continue
            }
            var part = parts[name]
            if (part == null && name !in rejected) {
                if (parts.size >= maximumParts) {
                    missing(link, "DEPENDENCY_PART_LIMIT")
                    rejected += name
                    continue
                }
                val candidates = roots.map { File(it, name) }.filter { it.isFile && it.canonicalFile.parentFile in roots }
                for (candidate in candidates) {
                    part = try {
                        load(candidate, digest)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (part != null) break
                }
                if (part == null) {
                    missing(link, if (candidates.isEmpty()) "ARCHIVE_UNAVAILABLE" else "ARCHIVE_UNVERIFIED_OR_BYTE_LIMIT")
                    rejected += name
                    continue
                }
                parts[name] = part
            }
            if (part == null) continue
            if (part.digest != digest || part.number != number) {
                missing(link, "ARCHIVE_REFERENCE_MISMATCH")
                continue
            }
            link.stage?.let { reference ->
                val stage = part.stages[reference.text("stage")]
                if (stage == null || listOf("fileName", "sha256", "bytes", "records").any { stage[it] != reference[it] }) {
                    missing(link, "STAGE_REFERENCE_MISMATCH")
                }
            }
        }
        val closureComplete = missing.isEmpty() && !truncated
        val bundleManifest = buildJsonObject {
            put("schema", SCHEMA)
            put("sessionId", result.sessionId)
            put("latestArchive", latest.file.name)
            put("captureCompleted", result.complete)
            put("dependencyClosureComplete", closureComplete)
            put("shareComplete", result.complete && closureComplete)
            put("status", if (closureComplete) "VERIFIED_PARTS_INCLUDED" else "MISSING_OR_UNVERIFIED_PARTS")
            put("capturedFully", false)
            put("sourceCompleteness", "Retained source records only. Verified dependency closure does not establish complete application history.")
            put("readInstructions", "Open latestArchive first. Its stage references resolve to the original ZIP parts included here by filename and SHA-256. Missing parts are listed explicitly; references must not be treated as recovered evidence.")
            put(
                "parts",
                JsonArray(
                    parts.values.map { part ->
                        buildJsonObject {
                            put("fileName", part.file.name)
                            put("archivePart", part.number)
                            put("sha256", part.digest)
                            put("bytes", part.file.length())
                        }
                    }
                )
            )
            put("missingParts", JsonArray(missing.values.toList()))
            put("dependencyListTruncated", truncated)
            put("maximumParts", maximumParts)
            put("maximumBytes", maximumBytes)
        }
        val encodedManifest = bundleManifest.toString().toByteArray(Charsets.UTF_8)
        require(encodedManifest.size <= MANIFEST_LIMIT)
        val destination = File(result.file.parentFile, "diagnostic-bundle-${result.sessionId}-${UUID.randomUUID()}.zip")
        val partial = File(destination.parentFile, "${destination.name}.partial")
        try {
            FileOutputStream(partial).use { output ->
                ZipOutputStream(output).use { zip ->
                    // Each member is already compressed; a second deflate pass cannot
                    // improve source coverage and adds CPU work under memory pressure.
                    zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                    parts.values.forEach { part ->
                        checkActive()
                        zip.putNextEntry(ZipEntry(part.file.name))
                        val observed = part.file.inputStream().use { input -> hash(input, maximumBytes, ::checkActive) { bytes, count -> zip.write(bytes, 0, count) } }
                        require(observed.second == part.digest && observed.first == part.file.length()) { "DIAGNOSTIC_ARCHIVE_CHANGED_DURING_HANDOFF" }
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(encodedManifest)
                    zip.closeEntry()
                    zip.finish()
                    zip.flush()
                    output.fd.sync()
                }
            }
            require(verify(partial, maximumBytes, ::checkActive) == bundleManifest)
            checkActive()
            check(partial.renameTo(destination)) { "DIAGNOSTIC_BUNDLE_PUBLISH_FAILED" }
        } finally {
            partial.delete()
        }
        return result.copy(
            file = destination,
            complete = result.complete && closureComplete,
            retryRecommended = result.retryRecommended || !closureComplete,
            manifest = JsonObject(result.manifest + ("archiveBundle" to bundleManifest))
        )
    }

    /** Verify exact entries, bytes and digests again after streaming the complete bundle. */
    internal fun verify(file: File, maximumBytes: Long = DEFAULT_BYTE_LIMIT, guard: () -> Unit = {}): JsonObject {
        val observed = linkedMapOf<String, Pair<Long, String>>()
        var manifest: JsonObject? = null
        var remaining = maximumBytes
        ZipInputStream(file.inputStream().buffered(8192)).use { zip ->
            while (true) {
                guard()
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name == File(entry.name).name && observed.size <= 64 && entry.name !in observed)
                if (entry.name == "manifest.json") {
                    require(manifest == null)
                    val output = java.io.ByteArrayOutputStream(4096)
                    hash(zip, MANIFEST_LIMIT.toLong(), guard) { bytes, count -> output.write(bytes, 0, count) }
                    val bytes = output.toByteArray()
                    manifest = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as JsonObject
                } else {
                    val measured = hash(zip, remaining, guard)
                    observed[entry.name] = measured
                    remaining -= measured.first
                }
            }
        }
        val saved = requireNotNull(manifest)
        require(saved.text("schema") == SCHEMA)
        val parts = requireNotNull(saved["parts"] as? JsonArray).filterIsInstance<JsonObject>()
        require(parts.size == observed.size && parts.map { it.text("fileName") }.distinct().size == parts.size)
        parts.forEach { part ->
            val actual = requireNotNull(observed[part.text("fileName")])
            require(actual.first == part.number("bytes") && actual.second == part.text("sha256")) { "DIAGNOSTIC_BUNDLE_PART_MISMATCH" }
        }
        require(saved.text("latestArchive") in observed)
        val complete = (saved["missingParts"] as? JsonArray)?.isEmpty() == true && saved["dependencyListTruncated"] == JsonPrimitive(false)
        require(saved["dependencyClosureComplete"] == JsonPrimitive(complete))
        require(saved["shareComplete"] == JsonPrimitive(complete && saved["captureCompleted"] == JsonPrimitive(true)))
        return saved
    }

    private fun hash(input: InputStream, limit: Long, guard: () -> Unit, consume: (ByteArray, Int) -> Unit = { _, _ -> }): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytes = 0L
        while (true) {
            guard()
            val count = input.read(buffer)
            if (count < 0) break
            bytes += count
            require(bytes <= limit) { "DIAGNOSTIC_BUNDLE_BYTE_LIMIT" }
            digest.update(buffer, 0, count)
            consume(buffer, count)
        }
        return bytes to DiagnosticRecordChunks.hex(digest.digest())
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content
    private fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
}
