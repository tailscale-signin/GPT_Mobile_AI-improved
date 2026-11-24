package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PushbackReader
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToStream
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
            yield(chunkRecord(chunk, digest, redactedRecord.length, index++, count, offset))
            offset = next
        }
    }

    private fun chunkRecord(chunk: String, digest: String, length: Int, index: Int, count: Int, offset: Int): JsonObject = buildJsonObject {
        put("kind", "diagnostic_record_chunk")
        put("schema", 1)
        put("recordId", "sha256:$digest")
        put("recordSha256", digest)
        put("recordCharacters", length)
        put("chunkIndex", index)
        put("chunkCount", count)
        put("offsetUtf16", offset)
        put("chunkSha256", sha256(chunk))
        put("representation", "redacted_json_utf8")
        put("content", chunk)
    }

    /** The input has already passed the capture's complete-field redactor and authorization checks. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun streamRedacted(value: JsonElement, directory: File, maximumCharacters: Int, emit: suspend (String) -> Unit) {
        val limit = maximumCharacters / 8
        require(limit >= 256)
        val temporary = File.createTempFile("redacted-row-", ".tmp", directory)
        var linked = true
        try {
            FileOutputStream(temporary).use { output ->
                FileInputStream(temporary).use { input ->
                    // Android/Linux keeps the open file descriptors but removes the directory entry,
                    // including on process death. The finally block covers filesystems that defer deletion.
                    linked = !temporary.delete()
                    val hash = MessageDigest.getInstance("SHA-256")
                    DigestOutputStream(output, hash).use { Json.encodeToStream(JsonElement.serializer(), value, it) }
                    val digest = hex(hash.digest())
                    var length = 0
                    var count = 0
                    readChunks(input, limit) { _, size ->
                        length += size
                        count++
                    }
                    input.channel.position(0)
                    if (length <= maximumCharacters / 2) {
                        emit(input.reader(Charsets.UTF_8).readText())
                    } else {
                        var offset = 0
                        var index = 0
                        readChunks(input, limit) { buffer, size ->
                            emit(chunkRecord(String(buffer, 0, size), digest, length, index++, count, offset).toString())
                            offset += size
                        }
                    }
                }
            }
        } finally {
            if (linked) temporary.delete()
        }
    }

    /** The stream is canonical UTF-8. Keep paired UTF-16 surrogates in the same exported chunk. */
    private suspend fun readChunks(input: FileInputStream, limit: Int, consume: suspend (CharArray, Int) -> Unit) {
        val reader = PushbackReader(input.reader(Charsets.UTF_8), 1)
        val buffer = CharArray(limit)
        while (true) {
            var size = 0
            while (size < limit) {
                val n = reader.read(buffer, size, limit - size)
                if (n < 0) break
                size += n
            }
            if (size == 0) return
            if (size == limit && buffer[size - 1].isHighSurrogate()) reader.unread(buffer[--size].code)
            consume(buffer, size)
        }
    }

    /** The normal path can reuse its encoded payload. Large rows fingerprint the same wire bytes directly. */
    @OptIn(ExperimentalSerializationApi::class)
    internal fun largePayloadDigest(value: JsonElement, minimumCharacters: Int): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        val sink = object : OutputStream() {
            override fun write(b: Int) {
                bytes++
                digest.update(b.toByte())
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                bytes += len
                digest.update(b, off, len)
            }
        }
        Json.encodeToStream(JsonElement.serializer(), value, sink)
        // A UTF-16 code unit uses at most three UTF-8 bytes. Only a small ambiguous value needs a length check.
        if (bytes < minimumCharacters || bytes < minimumCharacters * 3L && value.toString().length < minimumCharacters) return null
        return hex(digest.digest())
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
