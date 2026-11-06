package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Record producers must supply already-redacted values and checkBudget before loading each source page. */
data class DiagnosticCaptureStage(
    val id: String,
    val fileName: String,
    val critical: Boolean = false,
    val lowMemorySafe: Boolean = false,
    val emptyReason: String = "NO_RETAINED_RECORDS",
    val capture: suspend (DiagnosticStageSink) -> Unit
)

data class DiagnosticCaptureOptions(
    val reducedMemory: Boolean = false,
    val bufferBytes: Int = 8192,
    val maxRecordBytes: Int = if (reducedMemory) 64 * 1024 else 512 * 1024,
    val maxStageBytes: Long = if (reducedMemory) 2L * 1024 * 1024 else 16L * 1024 * 1024,
    val maxArchiveBytes: Long = if (reducedMemory) 8L * 1024 * 1024 else 64L * 1024 * 1024,
    val minimumHeadroomBytes: Long = if (reducedMemory) 1024L * 1024 else 4L * 1024 * 1024,
    val memorySampler: () -> DiagnosticExportMemory = DiagnosticExportMemory::sample,
    val guard: () -> Unit = {},
    val onSessionReady: (File) -> Unit = {},
    val resumable: Boolean = true
) {
    init {
        require(bufferBytes in 1024..64 * 1024 && maxRecordBytes in 1..1024 * 1024)
        require(maxStageBytes >= maxRecordBytes && maxArchiveBytes in 1024 * 1024L..256 * 1024 * 1024L)
        require(minimumHeadroomBytes >= 0)
    }
}

data class DiagnosticCaptureResult(
    val file: File,
    val sessionId: String,
    val complete: Boolean,
    val usefulRecords: Long,
    val retryRecommended: Boolean,
    val manifest: JsonObject
)

private class DiagnosticCaptureLimit(val reason: String) : IOException(reason)

/** No history-sized collection or encoded byte array is retained by this sink. */
class DiagnosticStageSink internal constructor(
    private val output: FileOutputStream,
    private val options: DiagnosticCaptureOptions,
    private val maxBytes: Long,
    private val minimumHeadroom: Long
) {
    private val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8), options.bufferBytes / 2)
    var records: Long = 0
        private set
    var bytes: Long = 0
        private set
    var evidenceRecords: Long = 0
        private set
    private val omitted = linkedMapOf<String, Long>()
    val omissions: Map<String, Long> get() = omitted.toMap()

    fun omit(reason: String, count: Long = 1) {
        require(count > 0 && reason.matches(Regex("[A-Z][A-Z0-9_: -]{0,127}")))
        val key = if (omitted.size < 32 || reason in omitted) reason else "OTHER_OMISSIONS"
        omitted[key] = ((omitted[key] ?: 0) + count).coerceAtLeast(count)
    }

    suspend fun note(redactedRecord: String) = record(redactedRecord, evidence = false)

    suspend fun checkBudget() {
        currentCoroutineContext().ensureActive()
        options.guard()
        if (options.memorySampler().javaHeadroomBytes < minimumHeadroom) throw JavaHeapWorkDeferred()
        if (bytes >= maxBytes) throw DiagnosticCaptureLimit("STAGE_OR_ARCHIVE_BYTE_LIMIT")
    }

    /** One newline-delimited complete record is the unit preserved after a later failure. */
    suspend fun record(redactedRecord: String, evidence: Boolean = true) {
        checkBudget()
        if (redactedRecord.isBlank()) return
        if ('\n' in redactedRecord || '\r' in redactedRecord) throw DiagnosticCaptureLimit("MULTILINE_RECORD_REQUIRES_SPLITTING")
        val size = utf8Bytes(redactedRecord) + 1
        if (size > options.maxRecordBytes) throw DiagnosticCaptureLimit("RECORD_BYTE_LIMIT")
        if (bytes + size > maxBytes) throw DiagnosticCaptureLimit("STAGE_OR_ARCHIVE_BYTE_LIMIT")
        writer.write(redactedRecord)
        writer.write("\n")
        writer.flush()
        bytes += size
        records++
        if (evidence) evidenceRecords++
    }

    /** Does not use readLine, which can allocate an unbounded string for a damaged log. */
    suspend fun copyLines(file: File) {
        file.bufferedReader(Charsets.UTF_8, options.bufferBytes / 2).use { reader ->
            val block = CharArray(options.bufferBytes / 2)
            val line = StringBuilder(minOf(options.maxRecordBytes, 4096))
            while (true) {
                checkBudget()
                val count = reader.read(block)
                if (count < 0) break
                for (index in 0 until count) {
                    val character = block[index]
                    if (character == '\n') {
                        record(line.toString().removeSuffix("\r"))
                        line.setLength(0)
                    } else {
                        if (line.length >= options.maxRecordBytes) throw DiagnosticCaptureLimit("RECORD_BYTE_LIMIT")
                        line.append(character)
                    }
                }
            }
            if (line.isNotEmpty()) record(line.toString().removeSuffix("\r"))
        }
    }

    internal fun finish() {
        writer.flush()
        output.fd.sync()
    }

    private fun utf8Bytes(value: String): Long {
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            val char = value[index]
            bytes += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                Character.isHighSurrogate(char) && index + 1 < value.length && Character.isLowSurrogate(value[index + 1]) -> {
                    index++
                    4
                }
                Character.isSurrogate(char) -> 1 // OutputStreamWriter replaces malformed UTF-16 with '?'.
                else -> 3
            }
            index++
        }
        return bytes
    }
}

/** Disk checkpoints are bounded, private working artifacts. Explicit retries reuse the same source identity. */
object StagedDiagnosticCapture {
    const val SCHEMA = "gptmobile.staged-diagnostics.v1"
    private const val MANIFEST_LIMIT = 256 * 1024
    private const val CHECKPOINT_LIMIT = 32 * 1024
    private val safeId = Regex("[a-z][a-z0-9_]{0,63}")
    private val safeFile = Regex("[a-z][a-z0-9_.-]{0,95}")
    private val sessionPattern = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")

    suspend fun capture(
        directory: File,
        sourceIdentity: String,
        stages: List<DiagnosticCaptureStage>,
        options: DiagnosticCaptureOptions = DiagnosticCaptureOptions(),
        resumeSessionId: String? = null
    ): DiagnosticCaptureResult = DiagnosticExportGate.run {
        options.guard()
        require(sourceIdentity.isNotBlank() && sourceIdentity.length <= 4096)
        require(options.resumable || resumeSessionId == null) { "DIAGNOSTIC_RESUME_NOT_SUPPORTED" }
        require(stages.size in 1..32 && stages.map { it.id }.distinct().size == stages.size)
        require(stages.map { it.fileName }.distinct().size == stages.size)
        require(stages.all { safeId.matches(it.id) && safeFile.matches(it.fileName) && it.fileName !in setOf("manifest.json", "session.json") && !it.fileName.endsWith(".partial") && !it.fileName.endsWith(".receipt.json") })
        check(directory.mkdirs() || directory.isDirectory)
        val workRoot = File(directory, "diagnostic-capture-checkpoints").apply { check(mkdirs() || isDirectory) }
        pruneCheckpoints(workRoot, resumeSessionId)
        val identity = digestText(sourceIdentity)
        val plan = digestText(stages.joinToString("\n") { "${it.id}:${it.fileName}:${it.critical}:${it.lowMemorySafe}" })
        val sessionId = resumeSessionId ?: UUID.randomUUID().toString()
        require(sessionPattern.matches(sessionId)) { "INVALID_DIAGNOSTIC_SESSION" }
        val session = File(workRoot, sessionId)
        require(session.canonicalFile.parentFile == workRoot.canonicalFile)
        val sessionFile = File(session, "session.json")
        val now = System.currentTimeMillis()
        val start = if (resumeSessionId != null) {
            require(sessionFile.isFile) { "DIAGNOSTIC_SESSION_NOT_FOUND" }
            val saved = readObject(sessionFile, CHECKPOINT_LIMIT)
            require(saved.text("sourceIdentityHash") == identity && saved.text("stagePlanHash") == plan) { "DIAGNOSTIC_SOURCE_CHANGED" }
            saved.long("startedAtMillis") ?: now
        } else {
            check(session.mkdir())
            now
        }
        atomicJson(
            sessionFile,
            buildJsonObject {
                put("schema", SCHEMA)
                put("sessionId", sessionId)
                put("sourceIdentityHash", identity)
                put("stagePlanHash", plan)
                put("startedAtMillis", start)
                put("updatedAtMillis", now)
                put("captureCompleted", false)
            }
        )
        options.guard()
        options.onSessionReady(session)
        val receipts = mutableListOf<JsonObject>()
        // Reserve useful evidence by capturing every cheap section before history can exhaust the archive budget.
        val ordered = stages.sortedWith(compareBy<DiagnosticCaptureStage> { !it.critical }.thenBy { !it.lowMemorySafe })
        for (stage in ordered) {
            currentCoroutineContext().ensureActive()
            options.guard()
            val existing = readReceipt(session, stage)
            val remainingBytes = options.maxArchiveBytes - MANIFEST_LIMIT - receipts.sumOf { it.long("bytes") ?: 0 }
            if (existing != null &&
                existing.text("status") == "COMPLETED" &&
                (existing.long("bytes") ?: Long.MAX_VALUE) <= minOf(options.maxStageBytes, remainingBytes) &&
                validStageFile(session, existing)
            ) {
                receipts += JsonObject(existing + ("reusedCheckpoint" to JsonPrimitive(true)))
                continue
            }
            val started = System.currentTimeMillis()
            val memoryBefore = options.memorySampler()
            val receiptFile = File(session, "${stage.id}.receipt.json")
            val part = File(session, "${stage.fileName}.partial")
            val destination = File(session, stage.fileName)
            var failure: Throwable? = null
            var sink: DiagnosticStageSink? = null
            val available = options.maxArchiveBytes - MANIFEST_LIMIT - receipts.sumOf { it.long("bytes") ?: 0 }
            try {
                FileOutputStream(part).use { output ->
                    sink = DiagnosticStageSink(output, options, minOf(options.maxStageBytes, available.coerceAtLeast(0)), if (stage.critical || stage.lowMemorySafe) minOf(options.minimumHeadroomBytes, 1024L * 1024) else options.minimumHeadroomBytes)
                    val target = requireNotNull(sink)
                    target.checkBudget()
                    stage.capture(target)
                    target.finish()
                }
            } catch (cancelled: CancellationException) {
                failure = cancelled
            } catch (pressure: OutOfMemoryError) {
                // Retain only completed records. Do not run an unbounded fallback or manufacture evidence.
                failure = pressure
            } catch (problem: Exception) {
                failure = problem
            }
            options.guard()
            val records = sink?.records ?: 0
            val evidenceRecords = sink?.evidenceRecords ?: 0
            val omissions = sink?.omissions.orEmpty()
            val bytes = sink?.bytes ?: 0
            if (part.isFile) {
                java.io.RandomAccessFile(part, "rw").use { output ->
                    output.setLength(bytes)
                    output.fd.sync()
                }
                check(part.renameTo(destination)) { "DIAGNOSTIC_STAGE_PUBLISH_FAILED" }
            } else if (destination.isFile) {
                check(destination.delete())
            }
            val status = when {
                failure == null && omissions.isNotEmpty() -> "PARTIAL"
                failure == null && stage.critical && evidenceRecords == 0L -> "EMPTY"
                failure == null && records > 0 -> "COMPLETED"
                failure == null -> "EMPTY"
                records > 0 -> "PARTIAL"
                else -> "FAILED"
            }
            val reason = when (val problem = failure) {
                null -> when {
                    omissions.isNotEmpty() -> "RETAINED_SOURCE_OMISSIONS"
                    records == 0L || stage.critical && evidenceRecords == 0L -> stage.emptyReason.take(128)
                    else -> null
                }
                is CancellationException -> "CAPTURE_CANCELLED"
                is OutOfMemoryError, is JavaHeapWorkDeferred -> "JAVA_HEAP_PRESSURE"
                is DiagnosticCaptureLimit -> problem.reason
                is IOException -> "SOURCE_IO_FAILURE"
                else -> "SOURCE_CAPTURE_FAILURE"
            }
            val summary = if (destination.isFile) measure(destination, options.maxArchiveBytes) else FileMeasure(0, 0, digestText(""))
            check(summary.bytes == bytes && summary.records == records) { "DIAGNOSTIC_STAGE_VALIDATION_FAILED" }
            val receipt = buildJsonObject {
                put("id", stage.id)
                put("fileName", stage.fileName)
                put("status", status)
                put("collectionCompleted", failure == null)
                put(
                    "availability",
                    when {
                        failure != null && records == 0L -> "UNAVAILABLE"
                        failure != null || omissions.isNotEmpty() -> "PARTIAL"
                        evidenceRecords == 0L -> "EMPTY_RETAINED"
                        else -> "AVAILABLE"
                    }
                )
                put("critical", stage.critical)
                put("records", records)
                put("evidenceRecords", evidenceRecords)
                put("omissions", JsonObject(omissions.mapValues { JsonPrimitive(it.value) }))
                put("bytes", bytes)
                put("sha256", summary.sha256)
                put("startedAtMillis", started)
                put("completedAtMillis", System.currentTimeMillis())
                put("omissionReason", reason?.let(::JsonPrimitive) ?: JsonNull)
                put("errorClass", failure?.javaClass?.simpleName?.take(96)?.let(::JsonPrimitive) ?: JsonNull)
                put("reusedCheckpoint", false)
                put("memoryBefore", memoryBefore.json())
                put("memoryAfter", options.memorySampler().json())
            }
            atomicJson(receiptFile, receipt)
            receipts += receipt
            if (failure is CancellationException) throw failure
        }
        val complete = receipts.all { it.text("status") in setOf("COMPLETED", "EMPTY") }
        val usefulRecords = receipts.sumOf { it.long("evidenceRecords") ?: 0 }
        val emptyCritical = receipts.any { it["critical"] == JsonPrimitive(true) && (it.long("evidenceRecords") ?: 0) == 0L }
        val retry = !complete || emptyCritical || usefulRecords == 0L
        val manifest = buildJsonObject {
            put("schema", SCHEMA)
            put("sessionId", sessionId)
            put("sourceIdentityHash", identity)
            put("startedAtMillis", start)
            put("completedAtMillis", System.currentTimeMillis())
            put("captureCompleted", complete)
            val completedStageCount = receipts.count { it.text("status") in setOf("COMPLETED", "EMPTY") }
            put("captureCompletionPercent", completedStageCount * 100 / receipts.size)
            put("captureCompletionBasis", "Stages collected without omissions divided by requested stages; this is not a claim about uncaptured application history.")
            put(
                "missingEvidence",
                JsonArray(
                    receipts.filter { it.text("status") !in setOf("COMPLETED", "EMPTY") || it["critical"] == JsonPrimitive(true) && (it.long("evidenceRecords") ?: 0) == 0L }.map { row ->
                        buildJsonObject {
                            put("stage", row["id"] ?: JsonNull)
                            put("status", row["status"] ?: JsonNull)
                            put("reason", row["omissionReason"] ?: JsonNull)
                            put("omissions", row["omissions"] ?: JsonObject(emptyMap()))
                        }
                    }
                )
            )
            put("snapshotConsistency", "Capture interval with per-stage timestamps and database row boundaries; independently captured or resumed sections may differ. Compare run and invocation timestamps before inferring a stuck lifecycle.")
            put("capturedFully", false)
            put("sourceCompleteness", "Retained app records only; deleted, evicted or uncaptured events and provider wire payloads are unavailable.")
            put("usefulRecords", usefulRecords)
            put("criticalEvidenceAvailable", receipts.any { it["critical"] == JsonPrimitive(true) && (it.long("evidenceRecords") ?: 0) > 0 })
            put("emptyCriticalEvidence", emptyCritical)
            put("retryRecommended", retry)
            put("resumable", options.resumable)
            put(
                "recovery",
                when {
                    retry && options.resumable -> "Retained stages can be resumed using this sessionId and unchanged source identity. Reduced-memory retry is available."
                    retry -> "This capture does not retain resumable checkpoints. Start a new reduced-memory capture to retry."
                    else -> "All requested stages reached a recorded terminal state; source completeness remains bounded by retention."
                }
            )
            put("reducedMemory", options.reducedMemory)
            put("bufferBytes", options.bufferBytes)
            put("maxRecordBytes", options.maxRecordBytes)
            put("maxStageBytes", options.maxStageBytes)
            put("maxArchiveBytes", options.maxArchiveBytes)
            put("memoryAtCompletion", options.memorySampler().json())
            put("stages", JsonArray(receipts))
        }
        val manifestFile = File(session, "manifest.json")
        atomicJson(manifestFile, manifest, MANIFEST_LIMIT)
        val output = File(directory, "${if (retry) "partial-diagnostics" else "diagnostics"}-$sessionId-${UUID.randomUUID()}.zip")
        val zipPart = File(directory, "${output.name}.partial")
        try {
            ZipOutputStream(zipPart.outputStream().buffered(options.bufferBytes)).use { zip ->
                receipts.forEach { row ->
                    currentCoroutineContext().ensureActive()
                    options.guard()
                    val source = File(session, row.text("fileName")!!)
                    zip.putNextEntry(ZipEntry(source.name))
                    if (source.isFile) {
                        source.inputStream().use { it.copyTo(zip, options.bufferBytes) }
                    } else {
                        // An individual stage may fail before opening its working file.
                        // Its empty entry and failure receipt must not discard healthy sections.
                        check(row.long("bytes") == 0L && row.long("records") == 0L) { "MISSING_DIAGNOSTIC_STAGE" }
                    }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("manifest.json"))
                manifestFile.inputStream().use { it.copyTo(zip, options.bufferBytes) }
                zip.closeEntry()
            }
            verify(zipPart, options.maxArchiveBytes)
            currentCoroutineContext().ensureActive()
            options.guard()
            check(zipPart.renameTo(output)) { "DIAGNOSTIC_ARCHIVE_PUBLISH_FAILED" }
        } finally {
            zipPart.delete()
        }
        atomicJson(
            sessionFile,
            buildJsonObject {
                put("schema", SCHEMA)
                put("sessionId", sessionId)
                put("sourceIdentityHash", identity)
                put("stagePlanHash", plan)
                put("startedAtMillis", start)
                put("updatedAtMillis", System.currentTimeMillis())
                put("captureCompleted", complete)
                put("retryRecommended", retry)
            }
        )
        DiagnosticCaptureResult(output, sessionId, complete, usefulRecords, retry, manifest)
    }

    fun findResumableSession(directory: File, sourceIdentity: String): String? {
        val expected = digestText(sourceIdentity)
        return File(directory, "diagnostic-capture-checkpoints").listFiles().orEmpty()
            .filter { it.isDirectory && sessionPattern.matches(it.name) }
            .mapNotNull { folder ->
                runCatching { readObject(File(folder, "session.json"), CHECKPOINT_LIMIT) }.getOrNull()
                    ?.takeIf { it.text("sourceIdentityHash") == expected && (it["captureCompleted"] != JsonPrimitive(true) || it["retryRecommended"] == JsonPrimitive(true)) }
                    ?.let { folder.name to (it.long("updatedAtMillis") ?: 0) }
            }.maxByOrNull { it.second }?.first
    }

    /** Fully inflates every entry, verifies names, records, bytes and digests within a hard total bound. */
    fun verify(archive: File, maxBytes: Long = 64L * 1024 * 1024): JsonObject {
        val observed = linkedMapOf<String, FileMeasure>()
        var manifest: JsonObject? = null
        var remaining = maxBytes
        ZipInputStream(archive.inputStream().buffered(8192)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && safeFile.matches(entry.name) && entry.name !in observed) { "CORRUPT_DIAGNOSTIC_ENTRY" }
                if (entry.name == "manifest.json") {
                    val bytes = readBounded(zip, minOf(MANIFEST_LIMIT.toLong(), remaining).toInt())
                    manifest = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
                    observed[entry.name] = FileMeasure(bytes.size.toLong(), 0, "")
                } else {
                    observed[entry.name] = measure(zip, remaining)
                }
                remaining -= observed.getValue(entry.name).bytes
                require(remaining >= 0) { "DIAGNOSTIC_ARCHIVE_BYTE_LIMIT" }
            }
        }
        val saved = requireNotNull(manifest) { "MISSING_DIAGNOSTIC_MANIFEST" }
        require(saved.text("schema") == SCHEMA) { "UNSUPPORTED_DIAGNOSTIC_SCHEMA" }
        val stages = saved["stages"] as? JsonArray ?: error("MISSING_DIAGNOSTIC_STAGES")
        require(stages.size in 1..32)
        val names = hashSetOf<String>()
        var count = 0L
        stages.forEach { item ->
            val row = item.jsonObject
            val name = requireNotNull(row.text("fileName"))
            require(name != "manifest.json" && safeFile.matches(name) && names.add(name))
            val expectedBytes = requireNotNull(row.long("bytes"))
            val expectedRecords = requireNotNull(row.long("records"))
            val evidence = requireNotNull(row.long("evidenceRecords"))
            require(expectedBytes >= 0 && expectedRecords >= 0 && evidence in 0..expectedRecords)
            val actual = requireNotNull(observed[name]) { "MISSING_DIAGNOSTIC_STAGE" }
            require(actual.bytes == expectedBytes && actual.records == expectedRecords && actual.sha256 == row.text("sha256")) { "DIAGNOSTIC_STAGE_MISMATCH" }
            val status = row.text("status")
            require(status in setOf("COMPLETED", "EMPTY", "PARTIAL", "FAILED"))
            require(status != "COMPLETED" || expectedRecords > 0) { "EMPTY_DIAGNOSTICS_CANNOT_BE_COMPLETE" }
            require(status != "EMPTY" || evidence == 0L && row.text("omissionReason") != null)
            count += evidence
        }
        require(observed.keys == names + "manifest.json")
        require(saved.long("usefulRecords") == count)
        val rows = stages.map { it.jsonObject }
        val complete = rows.all { it.text("status") in setOf("COMPLETED", "EMPTY") }
        val criticalAvailable = rows.any { it["critical"] == JsonPrimitive(true) && (it.long("evidenceRecords") ?: 0) > 0 }
        val emptyCritical = rows.any { it["critical"] == JsonPrimitive(true) && (it.long("evidenceRecords") ?: 0) == 0L }
        require(saved["captureCompleted"] == JsonPrimitive(complete) && saved["capturedFully"] == JsonPrimitive(false))
        require(saved["criticalEvidenceAvailable"] == JsonPrimitive(criticalAvailable) && saved["emptyCriticalEvidence"] == JsonPrimitive(emptyCritical))
        require(saved["retryRecommended"] == JsonPrimitive(!complete || emptyCritical || count == 0L))
        return saved
    }

    private fun pruneCheckpoints(root: File, retainedSession: String?) {
        val expiry = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val candidates = root.listFiles().orEmpty().filter {
            it.isDirectory &&
                sessionPattern.matches(it.name) &&
                it.name != retainedSession &&
                it.canonicalFile.parentFile == root.canonicalFile
        }.sortedByDescending { File(it, "session.json").lastModified() }
        candidates.forEachIndexed { index, folder ->
            if (index >= 3 || folder.lastModified() < expiry) folder.deleteRecursively()
        }
    }

    private fun readReceipt(session: File, stage: DiagnosticCaptureStage): JsonObject? = runCatching {
        readObject(File(session, "${stage.id}.receipt.json"), CHECKPOINT_LIMIT).takeIf { it.text("id") == stage.id && it.text("fileName") == stage.fileName }
    }.getOrNull()

    private fun validStageFile(session: File, row: JsonObject): Boolean = runCatching {
        val name = requireNotNull(row.text("fileName"))
        require(safeFile.matches(name))
        val measured = measure(File(session, name), 256L * 1024 * 1024)
        measured.bytes == row.long("bytes") && measured.records == row.long("records") && measured.sha256 == row.text("sha256")
    }.getOrDefault(false)

    private fun readObject(file: File, limit: Int): JsonObject = file.inputStream().use { Json.parseToJsonElement(readBounded(it, limit).decodeToString(throwOnInvalidSequence = true)).jsonObject }

    private fun atomicJson(file: File, data: JsonObject, limit: Int = CHECKPOINT_LIMIT) {
        val text = data.toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= limit) { "DIAGNOSTIC_MANIFEST_BYTE_LIMIT" }
        val pending = File(file.parentFile, "${file.name}.partial")
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(pending.renameTo(file)) { "DIAGNOSTIC_CHECKPOINT_PUBLISH_FAILED" }
        } finally {
            pending.delete()
        }
    }

    private data class FileMeasure(val bytes: Long, val records: Long, val sha256: String)

    private fun measure(file: File, limit: Long): FileMeasure = file.inputStream().use { measure(it, limit) }

    private fun measure(input: java.io.InputStream, limit: Long): FileMeasure {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytes = 0L
        var records = 0L
        var last = -1
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            bytes += count
            require(bytes <= limit) { "DIAGNOSTIC_ARCHIVE_BYTE_LIMIT" }
            digest.update(buffer, 0, count)
            for (index in 0 until count) if (buffer[index] == '\n'.code.toByte()) records++
            if (count > 0) last = buffer[count - 1].toInt()
        }
        require(bytes == 0L || last == '\n'.code) { "INCOMPLETE_DIAGNOSTIC_RECORD" }
        return FileMeasure(bytes, records, hex(digest.digest()))
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        require(limit >= 0)
        val output = java.io.ByteArrayOutputStream(minOf(4096, limit))
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "DIAGNOSTIC_METADATA_BYTE_LIMIT" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun digestText(value: String): String = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content
    private fun JsonObject.long(name: String): Long? = text(name)?.toLongOrNull()
}
