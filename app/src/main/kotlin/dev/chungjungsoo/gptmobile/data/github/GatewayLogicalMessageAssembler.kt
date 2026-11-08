package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.GatewayMessagePartRecord
import java.security.MessageDigest

/** Verifies every bounded part; large text remains pageable instead of becoming one SQLite row. */
internal object GatewayLogicalMessageAssembler {
    const val PART_BYTES = 256 * 1024
    const val LOCAL_PROJECTION_BYTES = 512 * 1024
    data class Result(val content: String?, val bytes: Long)

    suspend fun assemble(first: GatewayMessagePartRecord, load: suspend (Int) -> List<GatewayMessagePartRecord>): Result {
        val digest = MessageDigest.getInstance("SHA-256")
        var text: StringBuilder? = StringBuilder()
        var bytes = 0L
        var index = 0
        while (index < first.partCount) {
            val parts = load(index)
            check(parts.isNotEmpty() && parts.size <= 8) { "Gateway logical message is incomplete." }
            for (part in parts) {
                check(part.logicalMessageId == first.logicalMessageId && part.partIndex == index && part.partCount == first.partCount && part.contentSha256 == first.contentSha256 && part.turnId == first.turnId && part.role == first.role && part.kind == first.kind && part.status == first.status && part.revision == first.revision && part.publicationId == first.publicationId && part.commandId == first.commandId && part.createdAt == first.createdAt && part.conversationKey == first.conversationKey) { "Gateway logical message parts disagree." }
                val encoded = part.content.toByteArray(Charsets.UTF_8)
                check(encoded.size <= PART_BYTES) { "Gateway message part exceeds its UTF-8 bound." }
                bytes += encoded.size
                digest.update(encoded)
                if (bytes <= LOCAL_PROJECTION_BYTES) text?.append(part.content) else text = null
                index++
            }
        }
        check(index == first.partCount) { "Gateway logical message has unexpected additional parts." }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(first.contentSha256 == null && first.partCount == 1 || first.contentSha256 == actual) { "Gateway logical message content digest does not match." }
        return Result(text?.toString(), bytes)
    }

    /** Older servers may return a whole message; split it before inserting into SQLite. */
    fun boundedParts(message: GatewayTranscriptMessage): List<GatewayTranscriptMessage> {
        val bytes = message.content.toByteArray(Charsets.UTF_8)
        require(message.logicalMessageId.isNotBlank() && message.logicalMessageId.length <= 256 && message.partCount in 1..4096 && message.partIndex in 0 until message.partCount)
        require(message.contentSha256 == null && message.partCount == 1 || message.contentSha256?.matches(Regex("[a-f0-9]{64}")) == true) { "Gateway multipart message has no valid content digest." }
        if (bytes.size <= PART_BYTES) return listOf(message)
        check(message.partCount == 1) { "Gateway message part exceeds its UTF-8 bound." }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(message.contentSha256 == null || message.contentSha256 == digest) { "Gateway message digest does not match." }
        val pieces = mutableListOf<String>()
        var start = 0
        var index = 0
        var size = 0
        while (index < message.content.length) {
            val code = message.content.codePointAt(index)
            val width = when {
                code <= 0x7f -> 1
                code <= 0x7ff -> 2
                code <= 0xffff -> 3
                else -> 4
            }
            if (size + width > PART_BYTES) {
                pieces += message.content.substring(start, index)
                start = index
                size = 0
            }
            size += width
            index += Character.charCount(code)
        }
        pieces += message.content.substring(start)
        return pieces.mapIndexed { part, content -> message.copy(messageId = "${message.logicalMessageId}:${message.revision}:$part", content = content, partIndex = part, partCount = pieces.size, contentSha256 = digest) }
    }
}
