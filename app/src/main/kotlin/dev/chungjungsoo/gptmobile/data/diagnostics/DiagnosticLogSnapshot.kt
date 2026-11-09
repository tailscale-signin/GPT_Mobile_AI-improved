package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.Writer

/** Immutable disk copies owned by one export. Always close after their bytes have been packaged. */
data class DiagnosticLogSource(
    val name: String,
    val file: File,
    val recordCount: Long,
    val byteCount: Long,
    val skippedSourceBytes: Long = 0,
    val truncatedRecords: Long = 0
)

class DiagnosticLogSnapshot internal constructor(
    val sources: List<DiagnosticLogSource>,
    val capturedAtMillis: Long,
    val trackingEnabled: Boolean,
    val droppedRecords: Long,
    val omissionReason: String? = null,
    val sourceFailures: Map<String, String> = emptyMap(),
    private val snapshotDirectory: File? = null,
    val droppedCriticalRecords: Long = 0
) : Closeable {
    val recordCount: Long get() = sources.sumOf { it.recordCount }
    val byteCount: Long get() = sources.sumOf { it.byteCount }

    fun forEachLine(consume: (String) -> Unit) {
        sources.forEach { source -> source.file.bufferedReader().useLines { lines -> lines.forEach { consume(redactLogMessage(it)) } } }
    }

    fun writeTo(destination: Writer): Long {
        forEachLine { destination.append(it).append('\n') }
        destination.flush()
        return recordCount
    }

    override fun close() {
        snapshotDirectory?.deleteRecursively()
    }
}

/** Bounded byte copying also tolerates an oversized legacy or interrupted log record. */
internal object DiagnosticLogFiles {
    const val BUFFER_BYTES = 4096
    const val MAX_RECORD_BYTES = 16 * 1024
    const val CRITICAL_FILE_BYTES = 128 * 1024L
    const val RECENT_FILE_BYTES = 1_000_000L
    const val REDUCED_RECENT_FILE_BYTES = 32 * 1024L

    fun copyTail(source: File, destination: File, maximumSourceBytes: Long): DiagnosticLogSource {
        require(maximumSourceBytes > 0)
        var recordCount = 0L
        var truncatedRecords = 0L
        var byteCount = 0L
        var skipped = 0L
        val line = ByteArray(MAX_RECORD_BYTES)
        FileInputStream(source).use { fileInput ->
            val sourceBytes = fileInput.channel.size()
            skipped = (sourceBytes - maximumSourceBytes).coerceAtLeast(0L)
            val beginsWithFragment = if (skipped > 0) {
                fileInput.channel.position(skipped - 1)
                val previous = fileInput.read()
                fileInput.channel.position(skipped)
                previous != '\n'.code
            } else {
                false
            }
            fileInput.buffered(BUFFER_BYTES).use { input ->
                // Do not publish a fragment of the first record after seeking into a tail.
                if (beginsWithFragment) {
                    while (true) {
                        val value = input.read()
                        if (value < 0) break
                        skipped++
                        if (value == '\n'.code) break
                    }
                }
                FileOutputStream(destination).buffered(BUFFER_BYTES).use { output ->
                    var used = 0
                    var overflow = false
                    fun emit(interrupted: Boolean = false) {
                        if (used == 0 && !overflow) return
                        var length = used
                        if (length > 0 && line[length - 1] == '\r'.code.toByte()) length--
                        val completeLength = completeUtf8Prefix(line, length)
                        val truncated = overflow || interrupted || completeLength != length
                        length = completeLength
                        if (truncated) truncatedRecords++
                        if (length > 0) {
                            // Retained files can predate current redaction rules. Re-sanitize
                            // their complete physical records before any export snapshot exists.
                            val safe = redactLogMessage(String(line, 0, length, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                            output.write(safe)
                            byteCount += safe.size
                            if (truncated) {
                                val marker = " · truncatedAtSnapshot=true".toByteArray(Charsets.UTF_8)
                                output.write(marker)
                                byteCount += marker.size
                            }
                            output.write('\n'.code)
                            byteCount++
                            recordCount++
                        }
                        used = 0
                        overflow = false
                    }
                    while (true) {
                        val value = input.read()
                        if (value < 0) break
                        if (value == '\n'.code) {
                            emit()
                        } else if (used < line.size) {
                            line[used++] = value.toByte()
                        } else {
                            overflow = true
                        }
                    }
                    emit(interrupted = used > 0 || overflow)
                    output.flush()
                }
            }
        }
        return DiagnosticLogSource(source.name, destination, recordCount, byteCount, skipped, truncatedRecords)
    }

    private fun completeUtf8Prefix(bytes: ByteArray, length: Int): Int {
        if (length == 0) return 0
        var lead = length - 1
        while (lead >= 0 && (bytes[lead].toInt() and 0xc0) == 0x80) lead--
        if (lead < 0) return 0
        val expected = when (bytes[lead].toInt() and 0xff) {
            in 0x00..0x7f -> 1
            in 0xc2..0xdf -> 2
            in 0xe0..0xef -> 3
            in 0xf0..0xf4 -> 4
            else -> 1
        }
        return if (length - lead < expected) lead else length
    }
}
