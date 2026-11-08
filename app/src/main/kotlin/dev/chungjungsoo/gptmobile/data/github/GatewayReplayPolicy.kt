package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Pure validation runs before any local projection or cursor movement. */
internal object GatewayReplayPolicy {
    data class Plan(val messages: List<GatewayConversationMessage>, val cursor: GatewayReplayCursor)

    fun fingerprint(message: GatewayConversationMessage): String = GatewayWorkStore.digest(Json.encodeToString(message))

    fun plan(saved: GatewayReplayCursor, page: GatewayMessagePage, existing: Map<Long, GatewayMessageRecord>): Plan {
        require(page.items.size <= 100 && page.revision >= 0 && page.earliestSequence >= 0 && page.latestSequence >= 0)
        require(page.items.zipWithNext().all { (first, second) -> first.sequence < second.sequence }) { "Gateway replay sequence is not ordered." }
        require(page.items.map { it.messageId }.distinct().size == page.items.size) { "Gateway replay contains duplicate message identities." }
        require(!page.hasMore || page.items.isNotEmpty() && !page.nextCursor.isNullOrBlank()) { "Gateway replay continuation did not advance." }
        val missing = page.gapDetails?.missingRanges.orEmpty()
        require(missing.size <= 101 && (missing.isEmpty() || page.gap)) { "Gateway replay gap details exceed their bound or lack a gap declaration." }
        require(missing.all { it.firstSequence > 0 && it.lastSequence >= it.firstSequence && it.lastSequence <= page.latestSequence }) { "Gateway replay gap range is invalid." }
        require(missing.zipWithNext().all { (first, second) -> first.lastSequence < second.firstSequence }) { "Gateway replay gap ranges overlap or are unordered." }
        require(page.items.none { item -> missing.any { item.sequence in it.firstSequence..it.lastSequence } }) { "Gateway replay labels a retained message as missing." }
        val consumed = mutableSetOf<GatewayMissingRange>()
        fun declaredGap(first: Long, last: Long): Boolean = missing.firstOrNull { it.firstSequence == first && it.lastSequence == last }?.let {
            consumed.add(it)
            true
        } == true
        var sequence = saved.sequence
        var gapBefore = saved.gapBeforeSequence
        val messages = mutableListOf<GatewayConversationMessage>()
        for (message in page.items) {
            require(message.conversationId == page.conversationId && message.sequence > 0 && message.sequence <= page.latestSequence && message.messageId.length in 1..160 && message.jobId.length in 1..160 && message.content.toByteArray(Charsets.UTF_8).size <= 1_000_000) { "Invalid gateway replay message identity or bound." }
            require(message.logicalMessageId.length in 1..160 && message.partCount in 1..1_000_000 && message.partIndex in 0 until message.partCount && (message.partCount == 1 || message.contentSha256?.matches(Regex("[a-f0-9]{64}")) == true)) { "Invalid logical message part identity." }
            if (message.sequence <= saved.sequence) {
                check(existing[message.sequence]?.fingerprint == fingerprint(message)) { "Gateway replay conflicts with a retained message." }
                continue
            }
            if (message.sequence != sequence + 1) {
                val legacyLeading = page.gapDetails == null && messages.isEmpty() && page.gap && message.sequence == page.earliestSequence && page.earliestSequence > sequence + 1
                check(page.gap && (declaredGap(sequence + 1, message.sequence - 1) || legacyLeading)) { "Gateway replay has an unexplained sequence gap." }
                gapBefore = minOf(gapBefore ?: Long.MAX_VALUE, message.sequence)
            }
            sequence = message.sequence
            messages += message
        }
        if (messages.isEmpty() && page.items.isNotEmpty() && page.items.last().sequence <= saved.sequence && page.nextCursor != saved.cursor) {
            // A completed older read may arrive after another observer committed
            // a newer page. Verified duplicates never rewind its opaque cursor.
            return Plan(emptyList(), saved)
        }
        if (!page.hasMore && sequence < page.latestSequence) {
            val legacyEmpty = page.gapDetails == null && page.items.isEmpty() && page.earliestSequence > page.latestSequence
            check(page.gap && (declaredGap(sequence + 1, page.latestSequence) || legacyEmpty)) { "Gateway omitted unread messages without an exact retention gap." }
            gapBefore = minOf(gapBefore ?: Long.MAX_VALUE, sequence + 1)
            sequence = page.latestSequence
        }
        check(missing.all { it in consumed || it.lastSequence <= saved.sequence }) { "Gateway replay declared a gap that does not exactly match its missing sequences." }
        val verifiedDuplicate = messages.isEmpty() && page.items.isNotEmpty() && page.items.last().sequence == saved.sequence && page.nextCursor == saved.cursor
        check(!page.hasMore || verifiedDuplicate || sequence > saved.sequence && page.nextCursor != saved.cursor) { "Gateway replay repeated its continuation without a verified duplicate." }
        check(page.hasMore || sequence >= page.latestSequence) { "Gateway replay ended before its declared watermark." }
        check(sequence == saved.sequence || !page.nextCursor.isNullOrBlank()) { "Gateway replay did not retain a durable continuation cursor." }
        return Plan(
            messages,
            saved.copy(
                sequence = sequence,
                cursor = page.nextCursor ?: saved.cursor,
                hasMore = page.hasMore,
                historyComplete = saved.historyComplete && page.historyComplete && !page.gap && gapBefore == null,
                gapBeforeSequence = gapBefore,
                historyLimitation = saved.historyLimitation ?: if (missing.isNotEmpty()) "${page.historyLimitation ?: "Gateway history is incomplete"}. Missing sequences: ${missing.joinToString { "${it.firstSequence}–${it.lastSequence}" }}." else page.historyLimitation ?: if (page.gap) "Earlier gateway messages are no longer retained." else null
            )
        )
    }
}
