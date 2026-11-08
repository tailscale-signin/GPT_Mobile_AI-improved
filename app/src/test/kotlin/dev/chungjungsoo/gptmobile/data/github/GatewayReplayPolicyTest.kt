package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayReplayPolicyTest {
    private fun message(sequence: Long, content: String = "message $sequence") = GatewayConversationMessage("message-$sequence", "conversation", "job", sequence, if (sequence % 2L == 1L) "user" else "assistant", "message", content, sequence * 1000)
    private fun page(vararg sequence: Long, more: Boolean = false, latest: Long = sequence.lastOrNull() ?: 0, gap: Boolean = false, earliest: Long = 1) = GatewayMessagePage("gateway", "principal", "conversation", 4, sequence.map { message(it) }, "cursor-${sequence.lastOrNull() ?: latest}", more, earliest, latest, !gap, if (gap) "Retention expired" else null, gap, 5000)
    private fun retained(message: GatewayConversationMessage) = GatewayMessageRecord("local", message.sequence, message.messageId, message.jobId, message.role, message.kind, message.content, message.createdAtMs, GatewayReplayPolicy.fingerprint(message), message.sequence.toInt())

    @Test fun duplicatePrefixIsVerifiedAndOnlyNewTailIsProjected() {
        val old = GatewayReplayCursor("local", 2, "cursor-2", false)
        val plan = GatewayReplayPolicy.plan(old, page(2, 3, 4), mapOf(2L to retained(message(2))))
        assertEquals(listOf(3L, 4L), plan.messages.map { it.sequence })
        assertEquals(4L, plan.cursor.sequence)
        assertEquals("cursor-4", plan.cursor.cursor)
        assertFalse(plan.cursor.hasMore)
    }

    @Test fun changedDuplicateIsRejectedBeforeProjection() {
        val old = GatewayReplayCursor("local", 2, "cursor-2")
        assertTrue(runCatching { GatewayReplayPolicy.plan(old, page(2, 3), mapOf(2L to retained(message(2, "changed")))) }.isFailure)
        assertEquals(2L, old.sequence)
    }

    @Test fun unexplainedGapCannotAdvanceDurableCursor() {
        val old = GatewayReplayCursor("local", 2, "cursor-2")
        assertTrue(runCatching { GatewayReplayPolicy.plan(old, page(5, 6), emptyMap()) }.isFailure)
        assertEquals("cursor-2", old.cursor)
    }

    @Test fun explicitRetentionGapRemainsVisibleAfterLaterCompletePage() {
        val first = GatewayReplayPolicy.plan(GatewayReplayCursor("local", 2, "cursor-2"), page(5, 6, more = true, latest = 8, gap = true, earliest = 5), emptyMap())
        assertEquals(5L, first.cursor.gapBeforeSequence)
        assertFalse(first.cursor.historyComplete)
        val next = GatewayReplayPolicy.plan(first.cursor, page(7, 8), emptyMap())
        assertEquals(8L, next.cursor.sequence)
        assertFalse(next.cursor.historyComplete)
        assertEquals("Retention expired", next.cursor.historyLimitation)
    }

    @Test fun emptyTailKeepsCursorForLaterFollowUp() {
        val old = GatewayReplayCursor("local", 4, "opaque:four", false)
        val plan = GatewayReplayPolicy.plan(old, page(latest = 4).copy(nextCursor = "opaque:four"), emptyMap())
        assertTrue(plan.messages.isEmpty())
        assertEquals(old, plan.cursor)
        val followUp = GatewayReplayPolicy.plan(plan.cursor, page(5, 6), emptyMap())
        assertEquals(listOf(5L, 6L), followUp.messages.map { it.sequence })
    }

    @Test fun prematureFinalPageAndRepeatedContinuationAreRejected() {
        assertTrue(runCatching { GatewayReplayPolicy.plan(GatewayReplayCursor("local"), page(1, latest = 2), emptyMap()) }.isFailure)
        assertTrue(runCatching { GatewayReplayPolicy.plan(GatewayReplayCursor("local", 1, "cursor-1"), page(2, more = true, latest = 3).copy(nextCursor = "cursor-1"), emptyMap()) }.isFailure)
    }

    @Test fun unorderedOrOversizedPagesAreRejected() {
        assertTrue(runCatching { GatewayReplayPolicy.plan(GatewayReplayCursor("local"), page(2, 1), emptyMap()) }.isFailure)
        assertTrue(runCatching { GatewayReplayPolicy.plan(GatewayReplayCursor("local"), page(*LongArray(101) { it + 1L }), emptyMap()) }.isFailure)
    }

    @Test fun olderVerifiedPageCannotRewindOpaqueCursorOrCoverage() {
        val saved = GatewayReplayCursor("local", 4, "cursor-4", false)
        val older = page(1, 2, latest = 4, more = true)
        val replayed = GatewayReplayPolicy.plan(saved, older, mapOf(1L to retained(message(1)), 2L to retained(message(2))))
        assertEquals(saved, replayed.cursor)
        assertTrue(replayed.messages.isEmpty())
    }

    @Test fun explicitInteriorAndTrailingGapsAdvanceOnlyThroughDeclaredMissingRanges() {
        val response = page(1, 3, latest = 4, gap = true).copy(nextCursor = "cursor-4", gapDetails = GatewayReplayGap(listOf(GatewayMissingRange(2, 2), GatewayMissingRange(4, 4))))
        val plan = GatewayReplayPolicy.plan(GatewayReplayCursor("local"), response, emptyMap())
        assertEquals(listOf(1L, 3L), plan.messages.map { it.sequence })
        assertEquals(4L, plan.cursor.sequence)
        assertEquals("cursor-4", plan.cursor.cursor)
        assertFalse(plan.cursor.historyComplete)
        assertTrue(requireNotNull(plan.cursor.historyLimitation).contains("2–2"))
        assertTrue(requireNotNull(plan.cursor.historyLimitation).contains("4–4"))
    }

    @Test fun unprovedOrOverlappingGapCannotSkipRetainedBytes() {
        val response = page(1, 3, latest = 4, gap = true).copy(nextCursor = "cursor-4")
        for (ranges in listOf(listOf(GatewayMissingRange(2, 4)), listOf(GatewayMissingRange(2, 2)), listOf(GatewayMissingRange(4, 4), GatewayMissingRange(2, 2)))) {
            assertTrue(runCatching { GatewayReplayPolicy.plan(GatewayReplayCursor("local"), response.copy(gapDetails = GatewayReplayGap(ranges)), emptyMap()) }.isFailure)
        }
    }
}
