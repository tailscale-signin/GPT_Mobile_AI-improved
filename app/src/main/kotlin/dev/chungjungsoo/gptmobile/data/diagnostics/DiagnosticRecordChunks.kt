package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Input must already be redacted as a complete record so a secret cannot straddle redaction boundaries. */
internal object DiagnosticRecordChunks {
    fun encode(redactedRecord: String, maximumCharacters: Int): Sequence<JsonObject> = sequence {
        require(maximumCharacters >= 256)
        val digest = sha256(redactedRecord)
        var count = 0
        var offset = 0
        while (offset < redactedRecord.length) {
            offset = end(redactedRecord, offset, maximumCharacters)
            count++
        }
        offset = 0
        var index = 0
        while (offset < redactedRecord.length) {
            val next = end(redactedRecord, offset, maximumCharacters)
            val chunk = redactedRecord.substring(offset, next)
            yield(
                buildJsonObject {
                    put("kind", "diagnostic_record_chunk")
                    put("schema", 1)
                    put("recordId", "sha256:$digest")
                    put("recordSha256", digest)
                    put("recordCharacters", redactedRecord.length)
                    put("chunkIndex", index++)
                    put("chunkCount", count)
                    put("offsetUtf16", offset)
                    put("chunkSha256", sha256(chunk))
                    put("representation", "redacted_json_utf8")
                    put("content", chunk)
                }
            )
            offset = next
        }
    }

    private fun end(value: String, offset: Int, limit: Int): Int {
        var next = minOf(value.length, offset + limit)
        if (next < value.length && value[next - 1].isHighSurrogate() && value[next].isLowSurrogate()) next--
        return next
    }

    internal fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (value.length <= 2048) return hex(digest.digest(value.toByteArray(Charsets.UTF_8)))
        // Some Writer implementations copy an entire String to a temporary char array.
        // Keep a single encoder across chunks so a split UTF-16 pair hashes correctly.
        val buffer = CharArray(4096)
        OutputStreamWriter(DigestOutputStream(discard, digest), Charsets.UTF_8).use { writer ->
            var offset = 0
            while (offset < value.length) {
                val count = minOf(buffer.size, value.length - offset)
                value.toCharArray(buffer, 0, offset, offset + count)
                writer.write(buffer, 0, count)
                offset += count
            }
        }
        return hex(digest.digest())
    }

    private val discard = object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(value: ByteArray, offset: Int, length: Int) = Unit
    }

    /** Avoid allocating a Formatter and temporary strings for every byte of every record hash. */
    internal fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val result = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, value ->
            val unsigned = value.toInt() and 0xff
            result[index * 2] = digits[unsigned ushr 4]
            result[index * 2 + 1] = digits[unsigned and 0x0f]
        }
        return result.concatToString()
    }
}
