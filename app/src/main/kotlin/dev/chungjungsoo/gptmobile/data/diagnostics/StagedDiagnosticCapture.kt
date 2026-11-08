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
    val resumableRecords: Boolean = false,
    val refreshOnResume: Boolean = false,
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
    val traceStage: (String) -> (() -> Unit) = { {} },
    val resumable: Boolean = true,
    val deferArchiveHandoff: Boolean = false
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
    private val minimumHeadroom: Long,
    initialProgress: JsonObject? = null,
    initialCursor: JsonObject? = null,
    private val saveProgress: ((JsonObject) -> Unit)? = null
) {
    private val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8), options.bufferBytes / 2)
    var records: Long = initialProgress?.number("records") ?: 0
        private set
    var bytes: Long = initialProgress?.number("bytes") ?: 0
        private set
    var evidenceRecords: Long = initialProgress?.number("evidenceRecords") ?: 0
        private set
    private var recordChain = initialProgress?.value("recordChainSha256") ?: DiagnosticRecordChunks.sha256("")
    private val omitted = linkedMapOf<String, Long>().apply {
        (initialProgress?.get("omissions") as? JsonObject)?.forEach { (key, value) ->
            (value as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 }?.let { put(key, it) }
        }
    }
    val omissions: Map<String, Long> get() = omitted.toMap()
    var resumeCursor: JsonObject? = initialCursor ?: initialProgress?.get("cursor") as? JsonObject
        private set
    private var committed = progress()

    /** The byte boundary and source cursor are committed together after a complete logical row. */
    fun checkpoint(cursor: JsonObject) {
        val saved = progress(cursor)
        writer.flush()
        output.fd.sync()
        saveProgress?.invoke(saved)
        resumeCursor = cursor
        committed = saved
    }

    /** A multi-chunk logical record is either complete in this part or absent from it. */
    suspend fun logicalRecord(write: suspend () -> Unit) {
        val before = progress()
        try {
            write()
        } catch (failure: Throwable) {
            restore(before)
            throw failure
        }
    }

    internal fun discardUncommitted() {
        if (saveProgress != null) restore(committed)
    }

    internal fun progress(cursor: JsonObject? = resumeCursor): JsonObject = buildJsonObject {
        put("records", records)
        put("bytes", bytes)
        put("evidenceRecords", evidenceRecords)
        put("recordChainSha256", recordChain)
        put("omissions", JsonObject(omitted.mapValues { JsonPrimitive(it.value) }))
        put("cursor", cursor ?: JsonNull)
    }

    private fun restore(saved: JsonObject) {
        writer.flush()
        bytes = requireNotNull(saved.number("bytes"))
        output.channel.truncate(bytes)
        output.channel.position(bytes)
        records = requireNotNull(saved.number("records"))
        evidenceRecords = requireNotNull(saved.number("evidenceRecords"))
        recordChain = requireNotNull(saved.value("recordChainSha256"))
        resumeCursor = saved["cursor"] as? JsonObject
        omitted.clear()
        (saved["omissions"] as? JsonObject)?.forEach { (key, value) ->
            (value as? JsonPrimitive)?.content?.toLongOrNull()?.let { omitted[key] = it }
        }
    }

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
        // The bounded writer drains at checkpoints, rollback and stage finish (also
        // on failure). Avoid a separate file write for every tiny journal event.
        bytes += size
        records++
        if (evidence) evidenceRecords++
        recordChain = DiagnosticRecordChunks.sha256("$recordChain:${DiagnosticRecordChunks.sha256(redactedRecord)}")
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

    private fun JsonObject.value(key: String): String? = (get(key) as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content
    private fun JsonObject.number(key: String): Long? = value(key)?.toLongOrNull()
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
        require(stages.all { safeId.matches(it.id) && safeFile.matches(it.fileName) && it.fileName !in setOf("manifest.json", "session.json") && !it.fileName.endsWith(".partial") && !it.fileName.endsWith(".receipt.json") && !it.fileName.endsWith(".progress.json") })
        check(directory.mkdirs() || directory.isDirectory)
        val workRoot = File(directory, "diagnostic-capture-checkpoints").apply { check(mkdirs() || isDirectory) }
        pruneCheckpoints(workRoot, resumeSessionId)
        val identity = digestText(sourceIdentity)
        val plan = digestText(stages.joinToString("\n") { "${it.id}:${it.fileName}:${it.critical}:${it.lowMemorySafe}:${it.resumableRecords}:${it.refreshOnResume}" })
        val sessionId = resumeSessionId ?: UUID.randomUUID().toString()
        require(sessionPattern.matches(sessionId)) { "INVALID_DIAGNOSTIC_SESSION" }
        val session = File(workRoot, sessionId)
        require(session.canonicalFile.parentFile == workRoot.canonicalFile)
        val sessionFile = File(session, "session.json")
        val now = System.currentTimeMillis()
        val savedSession = if (resumeSessionId != null) {
            require(sessionFile.isFile) { "DIAGNOSTIC_SESSION_NOT_FOUND" }
            readObject(sessionFile, CHECKPOINT_LIMIT).also {
                require(it.text("sourceIdentityHash") == identity && it.text("stagePlanHash") == plan) { "DIAGNOSTIC_SOURCE_CHANGED" }
            }
        } else {
            check(session.mkdir())
            null
        }
        val start = savedSession?.long("startedAtMillis") ?: now
        val published = savedSession?.get("publishedArchive") as? JsonObject
        require(published?.get("handedOff") != JsonPrimitive(false)) { "DIAGNOSTIC_ARCHIVE_HANDOFF_PENDING" }
        val nextPart = stages.any { it.resumableRecords } && published != null
        val archivePart = (savedSession?.long("archivePart") ?: 1L) + if (nextPart) 1 else 0
        val previousArchive = if (nextPart) published else savedSession?.get("previousArchive") as? JsonObject
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
                put("archivePart", archivePart)
                put("previousArchive", previousArchive ?: JsonNull)
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
            val available = options.maxArchiveBytes - MANIFEST_LIMIT - receipts.sumOf { it.long("bytes") ?: 0 }
            receipts += captureStage(session, stage, options, available.coerceAtLeast(0), archivePart, previousArchive)
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
            put("archivePart", archivePart)
            put("previousArchive", previousArchive ?: JsonNull)
            put("externalPartsRequired", archivePart > 1)
            put("recordAtomicity", "A logical record and all of its chunks are retained together in one archive part. A durable source cursor advances only after their byte boundary is committed.")
            put("continuationAvailable", options.resumable && receipts.any { it["resumableRecords"] == JsonPrimitive(true) && it["collectionCompleted"] != JsonPrimitive(true) && it["cursor"] is JsonObject })
            put("stageBytePolicy", "Ordinary stages use maxStageBytes. Cursor-backed stages automatically stream successive bounded records within maxArchiveBytes, then continue in the next archive part; earlier parts are referenced by digest, not duplicated.")
            val completedStageCount = receipts.count { it.text("status") in setOf("COMPLETED", "EMPTY") }
            put("captureCompletionPercent", completedStageCount * 100 / receipts.size)
            put("captureCompletionBasis", "Stages collected or explicitly referenced without omissions divided by requested stages; this is not the percentage of application history captured. Earlier archive parts remain required where referenced.")
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
                    retry && options.resumable -> "Resume with this sessionId and unchanged source identity. Cursor-backed stages export the next tail without repeating an earlier part; keep earlier archives identified by previousArchive and continuedFrom digests. Reduced-memory retry is available."
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
        val output = File(directory, "${if (retry) "partial-diagnostics" else "diagnostics"}-$sessionId-part-$archivePart-${UUID.randomUUID()}.zip")
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
                put("archivePart", archivePart)
                put("previousArchive", previousArchive ?: JsonNull)
                put(
                    "publishedArchive",
                    buildJsonObject {
                        put("fileName", output.name)
                        put("sha256", binaryDigest(output))
                        put("archivePart", archivePart)
                        put("handedOff", !options.deferArchiveHandoff)
                    }
                )
            }
        )
        DiagnosticCaptureResult(output, sessionId, complete, usefulRecords, retry, manifest)
    }

    private suspend fun captureStage(
        session: File,
        stage: DiagnosticCaptureStage,
        options: DiagnosticCaptureOptions,
        available: Long,
        archivePart: Long,
        previousArchive: JsonObject?
    ): JsonObject {
        val existing = readReceipt(session, stage)
        require(!stage.resumableRecords || archivePart == 1L || existing != null) { "DIAGNOSTIC_CONTINUATION_CHECKPOINT_INVALID" }
        val olderPart = existing != null && (existing.long("archivePart") ?: 1) < archivePart
        val sourceReference = existing?.get("sourceReference") as? JsonObject ?: if (
            stage.resumableRecords && olderPart && existing["collectionCompleted"] == JsonPrimitive(true)
        ) {
            stageReference(requireNotNull(existing), requireNotNull(previousArchive))
        } else {
            null
        }
        val limit = if (stage.resumableRecords) available else minOf(options.maxStageBytes, available)
        if (!stage.refreshOnResume &&
            sourceReference == null &&
            existing != null &&
            existing.text("status") == "COMPLETED" &&
            (!stage.resumableRecords || !olderPart) &&
            (existing.long("bytes") ?: Long.MAX_VALUE) <= limit &&
            validStageFile(session, existing)
        ) {
            return JsonObject(existing + ("reusedCheckpoint" to JsonPrimitive(true)))
        }
        val started = System.currentTimeMillis()
        val memoryBefore = options.memorySampler()
        val progressFile = File(session, "${stage.id}.progress.json")
        val receiptFile = File(session, "${stage.id}.receipt.json")
        val part = File(session, "${stage.fileName}.partial")
        val destination = File(session, stage.fileName)
        val progress = if (stage.resumableRecords && !stage.refreshOnResume) {
            runCatching { readObject(progressFile, CHECKPOINT_LIMIT) }.getOrNull()?.takeIf {
                it.long("archivePart") == archivePart &&
                    it.text("id") == stage.id &&
                    it["cursor"] is JsonObject &&
                    validProgressFile(if (part.isFile) part else destination, it)
            }
        } else {
            null
        }
        // An unpublished committed prefix has no external archive to reference yet.
        // Preserve it when a smaller retry cannot hold it, rather than restarting its source.
        require(progress == null || (progress.long("bytes") ?: Long.MAX_VALUE) <= limit) { "DIAGNOSTIC_RESUME_BUDGET_TOO_SMALL" }
        val initialCursor = progress?.get("cursor") as? JsonObject ?: if (stage.resumableRecords && olderPart) existing["cursor"] as? JsonObject else null
        val continuedFrom = progress?.get("continuedFrom") as? JsonObject ?: if (stage.resumableRecords && olderPart) {
            stageReference(requireNotNull(existing), requireNotNull(previousArchive))
        } else {
            null
        }
        if (progress != null) {
            if (!part.isFile) check(destination.renameTo(part)) { "DIAGNOSTIC_CHECKPOINT_RESUME_FAILED" }
            java.io.RandomAccessFile(part, "rw").use { it.setLength(requireNotNull(progress.long("bytes"))) }
        }
        var failure: Throwable? = null
        var sink: DiagnosticStageSink? = null
        try {
            FileOutputStream(part, progress != null).use { output ->
                sink = DiagnosticStageSink(
                    output,
                    options,
                    limit,
                    if (stage.critical || stage.lowMemorySafe) minOf(options.minimumHeadroomBytes, 1024L * 1024) else options.minimumHeadroomBytes,
                    progress,
                    initialCursor,
                    if (stage.resumableRecords) {
                        { state ->
                            atomicJson(
                                progressFile,
                                JsonObject(
                                    state + mapOf(
                                        "id" to JsonPrimitive(stage.id),
                                        "archivePart" to JsonPrimitive(archivePart),
                                        "continuedFrom" to (continuedFrom ?: JsonNull)
                                    )
                                )
                            )
                        }
                    } else {
                        null
                    }
                )
                val target = requireNotNull(sink)
                try {
                    if (initialCursor != null && progress == null) {
                        (existing?.get("omissions") as? JsonObject)?.forEach { (reason, count) ->
                            (count as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 }?.let { target.omit(reason, it) }
                        }
                        target.checkpoint(initialCursor)
                    }
                    target.checkBudget()
                    if (sourceReference != null) {
                        target.note(
                            buildJsonObject {
                                put("kind", "diagnostic_stage_reference")
                                put("source", sourceReference)
                                put("meaning", "This completed source stage is in the identified earlier archive part; its payload is not duplicated here.")
                            }.toString()
                        )
                        initialCursor?.let(target::checkpoint)
                    } else {
                        val endTrace = options.traceStage(stage.id)
                        try {
                            stage.capture(target)
                        } finally {
                            endTrace()
                        }
                    }
                } catch (problem: Throwable) {
                    target.discardUncommitted()
                    throw problem
                } finally {
                    target.finish()
                }
            }
        } catch (cancelled: CancellationException) {
            failure = cancelled
        } catch (pressure: OutOfMemoryError) {
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
            failure == null && sourceReference != null -> existing?.text("status") ?: "COMPLETED"
            failure == null && omissions.isNotEmpty() -> "PARTIAL"
            failure == null && stage.critical && evidenceRecords == 0L -> "EMPTY"
            failure == null && records > 0 -> "COMPLETED"
            failure == null -> "EMPTY"
            records > 0 -> "PARTIAL"
            else -> "FAILED"
        }
        val reason = when (val problem = failure) {
            null -> when {
                sourceReference != null -> existing?.text("omissionReason")
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
                if (failure != null || omissions.isNotEmpty()) if (records == 0L) "UNAVAILABLE" else "PARTIAL" else if (sourceReference != null) {
                    "REFERENCED"
                } else if (evidenceRecords == 0L) {
                    "EMPTY_RETAINED"
                } else {
                    "AVAILABLE"
                }
            )
            put("critical", stage.critical)
            put("records", records)
            put("evidenceRecords", evidenceRecords)
            put("omissions", JsonObject(omissions.mapValues { JsonPrimitive(it.value) }))
            put("bytes", bytes)
            put("sha256", summary.sha256)
            put("recordChainSha256", summary.recordChainSha256)
            put("startedAtMillis", started)
            put("completedAtMillis", System.currentTimeMillis())
            put("omissionReason", reason?.let(::JsonPrimitive) ?: JsonNull)
            put("errorClass", failure?.javaClass?.simpleName?.take(96)?.let(::JsonPrimitive) ?: JsonNull)
            put("reusedCheckpoint", progress != null)
            put("archivePart", archivePart)
            put("resumableRecords", stage.resumableRecords)
            put("cursor", sink?.resumeCursor ?: initialCursor ?: JsonNull)
            put("continuedFrom", continuedFrom ?: JsonNull)
            put("sourceReference", sourceReference ?: JsonNull)
            put("memoryBefore", memoryBefore.json())
            put("memoryAfter", options.memorySampler().json())
        }
        atomicJson(receiptFile, receipt)
        if (failure is CancellationException) throw failure
        return receipt
    }

    private fun stageReference(receipt: JsonObject, archive: JsonObject): JsonObject = buildJsonObject {
        put("archive", archive)
        put("stage", receipt["id"] ?: JsonNull)
        put("fileName", receipt["fileName"] ?: JsonNull)
        put("sha256", receipt["sha256"] ?: JsonNull)
        put("records", receipt["records"] ?: JsonNull)
        put("bytes", receipt["bytes"] ?: JsonNull)
        put("omissions", receipt["omissions"] ?: JsonObject(emptyMap()))
    }

    /** A failed copy/share preparation must deliver its existing part before advancing source cursors. */
    fun pendingArchive(directory: File, sourceIdentity: String, sessionId: String): DiagnosticCaptureResult? {
        require(sessionPattern.matches(sessionId)) { "INVALID_DIAGNOSTIC_SESSION" }
        val session = File(directory, "diagnostic-capture-checkpoints/$sessionId")
        val saved = readObject(File(session, "session.json"), CHECKPOINT_LIMIT)
        require(saved.text("sourceIdentityHash") == digestText(sourceIdentity)) { "DIAGNOSTIC_SOURCE_CHANGED" }
        val published = saved["publishedArchive"] as? JsonObject ?: return null
        if (published["handedOff"] != JsonPrimitive(false)) return null
        val name = requireNotNull(published.text("fileName"))
        require(name == File(name).name && name.endsWith(".zip") && sessionId in name) { "INVALID_DIAGNOSTIC_ARCHIVE" }
        val archive = File(directory, name)
        require(archive.isFile && archive.canonicalFile.parentFile == directory.canonicalFile) { "DIAGNOSTIC_PENDING_ARCHIVE_UNAVAILABLE" }
        require(archive.length() in 1..256L * 1024 * 1024) { "DIAGNOSTIC_ARCHIVE_BYTE_LIMIT" }
        require(binaryDigest(archive) == published.text("sha256")) { "DIAGNOSTIC_PENDING_ARCHIVE_CORRUPT" }
        val manifest = verify(archive, 256L * 1024 * 1024)
        require(manifest.text("sessionId") == sessionId && manifest.text("sourceIdentityHash") == saved.text("sourceIdentityHash")) { "DIAGNOSTIC_SOURCE_CHANGED" }
        return DiagnosticCaptureResult(
            archive,
            sessionId,
            manifest["captureCompleted"] == JsonPrimitive(true),
            requireNotNull(manifest.long("usefulRecords")),
            manifest["retryRecommended"] == JsonPrimitive(true),
            manifest
        )
    }

    /** Call only after the archive is durable at the user-facing export destination. */
    fun acknowledgeArchiveHandoff(directory: File, result: DiagnosticCaptureResult) {
        require(sessionPattern.matches(result.sessionId)) { "INVALID_DIAGNOSTIC_SESSION" }
        val sessionFile = File(directory, "diagnostic-capture-checkpoints/${result.sessionId}/session.json")
        val saved = readObject(sessionFile, CHECKPOINT_LIMIT)
        val published = requireNotNull(saved["publishedArchive"] as? JsonObject)
        require(published.text("fileName") == result.file.name && saved.text("sourceIdentityHash") == result.manifest.text("sourceIdentityHash")) { "DIAGNOSTIC_ARCHIVE_HANDOFF_CHANGED" }
        atomicJson(sessionFile, JsonObject(saved + ("publishedArchive" to JsonObject(published + ("handedOff" to JsonPrimitive(true))))))
        result.file.delete()
    }

    fun findResumableSession(directory: File, sourceIdentity: String): String? {
        val expected = digestText(sourceIdentity)
        return File(directory, "diagnostic-capture-checkpoints").listFiles().orEmpty()
            .filter { it.isDirectory && sessionPattern.matches(it.name) }
            .mapNotNull { folder ->
                runCatching { readObject(File(folder, "session.json"), CHECKPOINT_LIMIT) }.getOrNull()
                    ?.takeIf {
                        it.text("sourceIdentityHash") == expected &&
                            (it["captureCompleted"] != JsonPrimitive(true) || it["retryRecommended"] == JsonPrimitive(true) || (it["publishedArchive"] as? JsonObject)?.get("handedOff") == JsonPrimitive(false))
                    }
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

    private fun validProgressFile(file: File, progress: JsonObject): Boolean = runCatching {
        val bytes = requireNotNull(progress.long("bytes"))
        val records = requireNotNull(progress.long("records"))
        val evidence = requireNotNull(progress.long("evidenceRecords"))
        require(bytes in 0..256L * 1024 * 1024 && records >= 0 && evidence in 0..records && file.length() >= bytes)
        val measured = file.inputStream().use { measure(it, bytes, stopAfter = bytes) }
        measured.bytes == bytes && measured.records == records && measured.recordChainSha256 == progress.text("recordChainSha256")
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

    private data class FileMeasure(val bytes: Long, val records: Long, val sha256: String, val recordChainSha256: String = digestText(""))

    private fun measure(file: File, limit: Long): FileMeasure = file.inputStream().use { measure(it, limit) }

    private fun measure(input: java.io.InputStream, limit: Long, stopAfter: Long? = null): FileMeasure {
        val digest = MessageDigest.getInstance("SHA-256")
        val lineDigest = MessageDigest.getInstance("SHA-256")
        var chain = digestText("")
        val buffer = ByteArray(8192)
        var bytes = 0L
        var records = 0L
        var last = -1
        while (true) {
            if (stopAfter != null && bytes == stopAfter) break
            val requested = stopAfter?.let { minOf(buffer.size.toLong(), it - bytes).toInt() } ?: buffer.size
            val count = input.read(buffer, 0, requested)
            if (count < 0) break
            bytes += count
            require(bytes <= limit) { "DIAGNOSTIC_ARCHIVE_BYTE_LIMIT" }
            digest.update(buffer, 0, count)
            var lineStart = 0
            for (index in 0 until count) {
                if (buffer[index] == '\n'.code.toByte()) {
                    lineDigest.update(buffer, lineStart, index - lineStart)
                    chain = digestText("$chain:${hex(lineDigest.digest())}")
                    records++
                    lineStart = index + 1
                }
            }
            if (lineStart < count) lineDigest.update(buffer, lineStart, count - lineStart)
            if (count > 0) last = buffer[count - 1].toInt()
        }
        require(bytes == 0L || last == '\n'.code) { "INCOMPLETE_DIAGNOSTIC_RECORD" }
        return FileMeasure(bytes, records, hex(digest.digest()), chain)
    }

    private fun binaryDigest(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        hex(digest.digest())
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
    private fun hex(bytes: ByteArray): String = DiagnosticRecordChunks.hex(bytes)
    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content
    private fun JsonObject.long(name: String): Long? = text(name)?.toLongOrNull()
}
