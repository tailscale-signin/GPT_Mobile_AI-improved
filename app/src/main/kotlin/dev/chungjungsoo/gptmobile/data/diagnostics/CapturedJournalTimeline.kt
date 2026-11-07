package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class CapturedJournalTimelineFailure(val code: String) : IOException(code)

/** Project the exact captured journal, never a second, later read of the live encrypted store. */
internal object CapturedJournalTimeline {
    private const val MAX_RECEIPT_BYTES = 32 * 1024
    private const val MAX_STAGE_BYTES = 256L * 1024 * 1024
    private const val MAX_LINE_CHARACTERS = 1024 * 1024
    private const val MAX_RECORD_CHARACTERS = 2 * 1024 * 1024
    private val hashPattern = Regex("[a-f0-9]{64}")

    /** The caller wraps this projection in one sink.logicalRecord so bad integrity rolls it back. */
    suspend fun stream(
        session: File,
        beforeRead: suspend () -> Unit,
        consume: suspend (JsonObject, Boolean) -> Unit,
        omitted: (String) -> Unit
    ): JsonObject {
        beforeRead()
        val receiptFile = File(session, "run_journal.receipt.json")
        valid(receiptFile.isFile && receiptFile.length() in 1..MAX_RECEIPT_BYTES.toLong(), "SOURCE_UNAVAILABLE")
        val receipt = receiptFile.inputStream().use { input ->
            val bytes = ByteArray(MAX_RECEIPT_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                beforeRead()
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            valid(count <= MAX_RECEIPT_BYTES, "RECEIPT_INVALID")
            parse(bytes.copyOf(count).decodeToString(throwOnInvalidSequence = true))
        }
        valid(receipt.text("id") == "run_journal" && receipt.text("fileName") == "run-journal.jsonl", "RECEIPT_INVALID")
        val expectedBytes = receipt.number("bytes")
        val expectedRecords = receipt.number("records")
        val expectedHash = receipt.text("sha256")
        valid(expectedBytes != null && expectedBytes in 0..MAX_STAGE_BYTES && expectedRecords != null && expectedRecords >= 0 && expectedHash != null && hashPattern.matches(expectedHash), "RECEIPT_INVALID")
        val source = File(session, "run-journal.jsonl")
        valid(source.isFile && source.length() == expectedBytes, "SOURCE_CHANGED")
        val digest = MessageDigest.getInstance("SHA-256")
        val chunks = RecordAssembly()
        var records = 0L
        var projectedEvents = 0L
        var coverageRecords = 0L
        source.inputStream().use { input ->
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            InputStreamReader(DigestInputStream(input, digest), decoder).use { reader ->
                val buffer = CharArray(4096)
                val line = StringBuilder(1024)
                while (true) {
                    beforeRead()
                    val size = reader.read(buffer)
                    if (size < 0) break
                    for (index in 0 until size) {
                        val character = buffer[index]
                        if (character != '\n') {
                            valid(line.length < MAX_LINE_CHARACTERS, "RECORD_LIMIT")
                            line.append(character)
                            continue
                        }
                        beforeRead()
                        val row = chunks.accept(parse(line.toString()))
                        line.setLength(0)
                        records++
                        if (row == null) continue
                        if (row.text("kind") == "journal_coverage") {
                            consume(JsonObject(row + mapOf("kind" to JsonPrimitive("timeline_journal_coverage"), "sourceFile" to JsonPrimitive(source.name))), false)
                            coverageRecords++
                        } else {
                            val event = DiagnosticTimeline.journal(row)
                                ?: throw CapturedJournalTimelineFailure("TIMELINE_JOURNAL_RECORD_INVALID")
                            consume(event, true)
                            projectedEvents++
                        }
                    }
                }
                valid(line.isEmpty() && !chunks.pending, "INCOMPLETE_RECORD")
            }
        }
        beforeRead()
        valid(records == expectedRecords && source.length() == expectedBytes && DiagnosticRecordChunks.hex(digest.digest()) == expectedHash, "CHECKSUM_MISMATCH")
        val complete = receipt["collectionCompleted"] == JsonPrimitive(true) && receipt.text("status") in setOf("COMPLETED", "EMPTY") && (receipt["omissions"] as? JsonObject).isNullOrEmpty()
        if (!complete) omitted("TIMELINE_JOURNAL_COVERAGE_INCOMPLETE")
        return buildJsonObject {
            put("kind", "timeline_journal_source")
            put("sourceFile", source.name)
            put("sourceSha256", expectedHash)
            put("sourceRecords", records)
            put("sourceBytes", expectedBytes)
            put("sourceStatus", receipt["status"] ?: JsonPrimitive("UNAVAILABLE"))
            put("sourceCollectionCompleted", receipt["collectionCompleted"] as? JsonPrimitive ?: JsonPrimitive(false))
            put("sourceOmissions", receipt["omissions"] as? JsonObject ?: JsonObject(emptyMap()))
            put("projectedEvents", projectedEvents)
            put("coverageRecords", coverageRecords)
            put("retainedSourceComplete", complete)
            put("liveJournalReopened", false)
        }
    }

    /** Retains at most one redacted logical record, validating every existing chunk envelope. */
    private class RecordAssembly {
        private var content: StringBuilder? = null
        private var digest: String? = null
        private var characters = 0L
        private var count = 0L
        private var next = 0L
        val pending: Boolean get() = content != null

        fun accept(row: JsonObject): JsonObject? {
            if (row.text("kind") != "diagnostic_record_chunk") {
                valid(!pending, "INCOMPLETE_RECORD")
                return row
            }
            val part = row.text("content") ?: throw CapturedJournalTimelineFailure("TIMELINE_JOURNAL_CHUNK_INVALID")
            val partDigest = row.text("recordSha256")
            val partIndex = row.number("chunkIndex")
            val partCount = row.number("chunkCount")
            val partCharacters = row.number("recordCharacters")
            valid(row["schema"] == JsonPrimitive(1) && row.text("representation") == "redacted_json_utf8" && partDigest != null && hashPattern.matches(partDigest) && row.text("recordId") == "sha256:$partDigest", "CHUNK_INVALID")
            valid(partCharacters != null && partCharacters in 1..MAX_RECORD_CHARACTERS.toLong() && partCount != null && partCount in 1..8192 && partIndex != null && partIndex >= 0 && partIndex < partCount, "CHUNK_INVALID")
            if (content == null) {
                valid(partIndex == 0L, "INCOMPLETE_RECORD")
                content = StringBuilder(minOf(requireNotNull(partCharacters).toInt(), 8192))
                digest = partDigest
                characters = partCharacters
                count = requireNotNull(partCount)
                next = 0
            }
            val text = requireNotNull(content)
            valid(partDigest == digest && partIndex == next && partCount == count && partCharacters == characters && row.number("offsetUtf16") == text.length.toLong(), "CHUNK_INVALID")
            valid(text.length.toLong() + part.length <= characters && row.text("chunkSha256") == DiagnosticRecordChunks.sha256(part), "CHUNK_INVALID")
            text.append(part)
            next++
            if (next < count) return null
            valid(text.length.toLong() == characters && DiagnosticRecordChunks.sha256(text.toString()) == digest, "CHUNK_INVALID")
            val result = parse(text.toString())
            content = null
            return result
        }
    }

    private fun parse(text: String): JsonObject = try {
        Json.parseToJsonElement(text) as? JsonObject ?: throw CapturedJournalTimelineFailure("TIMELINE_JOURNAL_RECORD_INVALID")
    } catch (_: IllegalArgumentException) {
        throw CapturedJournalTimelineFailure("TIMELINE_JOURNAL_RECORD_INVALID")
    }

    private fun valid(value: Boolean, reason: String) {
        if (!value) throw CapturedJournalTimelineFailure("TIMELINE_JOURNAL_$reason")
    }

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(name: String): Long? = (get(name) as? JsonPrimitive)?.longOrNull
}
