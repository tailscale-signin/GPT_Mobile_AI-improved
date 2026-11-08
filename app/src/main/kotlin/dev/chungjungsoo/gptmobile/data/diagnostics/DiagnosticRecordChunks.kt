package dev.chungjungsoo.gptmobile.data.diagnostics

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

    internal fun sha256(value: String): String = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

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
