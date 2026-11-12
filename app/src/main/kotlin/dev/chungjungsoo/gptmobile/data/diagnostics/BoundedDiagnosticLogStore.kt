package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.File
import java.io.FileOutputStream

/** Two bounded files; the caller serializes appends, rotation and snapshot creation. */
internal class BoundedDiagnosticLogStore(
    private val directory: File,
    private val currentName: String,
    private val previousName: String,
    private val maximumFileBytes: Long,
    private val maximumRecordBytes: Int = DiagnosticLogFiles.MAX_RECORD_BYTES
) {
    init {
        require(maximumRecordBytes >= 128 && maximumRecordBytes < maximumFileBytes)
    }

    fun append(record: String, durable: Boolean = false) {
        check(directory.mkdirs() || directory.isDirectory)
        // Limit before encoding, so a legacy caller cannot allocate an unbounded byte array.
        val candidate = record.take(maximumRecordBytes).toByteArray(Charsets.UTF_8)
        val marker = " · truncatedAtRing=true".toByteArray(Charsets.UTF_8)
        val truncated = candidate.size > maximumRecordBytes - marker.size || record.length > maximumRecordBytes
        val bytes = if (truncated) {
            var length = minOf(candidate.size, maximumRecordBytes - marker.size)
            while (length > 0 && length < candidate.size && (candidate[length].toInt() and 0xc0) == 0x80) length--
            candidate.copyOf(length) + marker
        } else {
            candidate
        }
        val current = File(directory, currentName)
        if (current.length() + bytes.size + 1 > maximumFileBytes) {
            val previous = File(directory, previousName)
            check(!previous.exists() || previous.delete()) { "Unable to rotate diagnostic history" }
            check(!current.exists() || current.renameTo(previous)) { "Unable to rotate diagnostic history" }
        }
        FileOutputStream(current, true).use { output ->
            output.write(bytes)
            output.write('\n'.code)
            if (durable) output.fd.sync()
        }
    }
}

/** Correlation prefixes must not expand thousands of short lines into one large temporary string. */
internal fun boundedDiagnosticRecord(prefix: String, eventId: String, message: String): String {
    val marker = " · truncatedAtSource=true · formatCharacterLimit=${DiagnosticLogFiles.MAX_RECORD_BYTES}"
    val limit = DiagnosticLogFiles.MAX_RECORD_BYTES - marker.length
    val result = StringBuilder(minOf(DiagnosticLogFiles.MAX_RECORD_BYTES, message.length.coerceAtMost(limit) + 512))
    var truncated = false
    fun append(value: String) {
        if (truncated) return
        val remaining = limit - result.length
        result.append(value, 0, minOf(value.length, remaining))
        truncated = value.length > remaining
    }
    append(prefix)
    var index = 0
    for (line in message.lineSequence()) {
        if (index > 0) append("\nparentEventId=$eventId · sequence=$index · ")
        append(line)
        if (truncated) break
        index++
    }
    if (truncated) result.append(marker)
    return result.toString()
}

private val criticalDiagnosticTags = setOf("Lifecycle", "Application", "AndroidBuild", "AppCrash", "AnrTraceSummary")

internal fun criticalDiagnosticRecord(tag: String, message: String, level: String): Boolean =
    level == "E" ||
        level == "F" ||
        tag in criticalDiagnosticTags ||
        (tag == "VoiceEngine" && message.contains("nativeOperationId=")) ||
        (tag == "Memory" && level == "W") ||
        message.contains("HEAP_PRESSURE", ignoreCase = true) ||
        message.contains("heap pressure", ignoreCase = true) ||
        (
            (tag.contains("Tool", ignoreCase = true) || tag.contains("GitHub", ignoreCase = true)) &&
                (level == "W" || message.contains("failed", ignoreCase = true) || message.contains("rejected", ignoreCase = true))
            )
