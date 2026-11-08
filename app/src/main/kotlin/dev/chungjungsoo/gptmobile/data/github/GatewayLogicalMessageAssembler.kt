package dev.chungjungsoo.gptmobile.data.github

import java.security.MessageDigest

/** Hash all durable parts, but materialize only bounded logical messages for ChatScreen. */
internal object GatewayLogicalMessageAssembler {
    const val LOCAL_PROJECTION_BYTES = 512 * 1024
    data class Result(val content: String?, val bytes: Long)

    suspend fun assemble(first: GatewayMessageRecord, load: suspend (Int) -> List<GatewayMessageRecord>): Result {
        val digest = MessageDigest.getInstance("SHA-256")
        var text: StringBuilder? = StringBuilder()
        var bytes = 0L
        var index = 0
        var firstSequence: Long? = null
        while (index < first.partCount) {
            val parts = load(index)
            check(parts.isNotEmpty() && parts.size <= 16) { "Gateway logical message is incomplete." }
            for (part in parts) {
                check(part.logicalMessageId == first.logicalMessageId && part.partIndex == index && part.partCount == first.partCount && part.contentSha256 == first.contentSha256 && part.jobId == first.jobId && part.role == first.role && part.kind == first.kind && part.conversationKey == first.conversationKey) { "Gateway logical message parts disagree." }
                if (firstSequence == null) firstSequence = part.sequence
                check(part.sequence == requireNotNull(firstSequence) + index) { "Gateway logical message parts are not contiguous." }
                val encoded = part.content.toByteArray(Charsets.UTF_8)
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
}
