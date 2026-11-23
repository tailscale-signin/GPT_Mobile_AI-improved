package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyProtectionReceiptTest {
    @Test
    fun receiptBindsExactUtf16OffsetsToItsOriginalContent() {
        val text = "🙂 Alice met Élodie."
        val alice = range(text, "Alice")
        val elodie = range(text, "Élodie")
        val receipt = PrivacyProtectionReceipt.create(text, listOf(alice, elodie))
        assertEquals(listOf(PrivacyProtectedRange(3, 8), PrivacyProtectedRange(13, 19)), receipt.ranges)
        assertTrue(receipt.validFor(text))
        assertEquals(listOf("Alice", "Élodie"), receipt.protectedValues(text))
        assertFalse(receipt.validFor(text.replace("Alice", "Alicia")))
        assertTrue(receipt.protectedValues(text + " ").isEmpty())
    }

    @Test
    fun invalidDigestsVersionsAndRangesCannotAuthorizeHighlighting() {
        val text = "Alice and Bob"
        val valid = PrivacyProtectionReceipt.create(text, listOf(range(text, "Alice")))
        val invalid = listOf(
            valid.copy(contentDigest = "unverified"),
            valid.copy(version = 2),
            valid.copy(ranges = listOf(PrivacyProtectedRange(-1, 5))),
            valid.copy(ranges = listOf(PrivacyProtectedRange(0, text.length + 1))),
            valid.copy(ranges = listOf(PrivacyProtectedRange(2, 2))),
            valid.copy(ranges = listOf(PrivacyProtectedRange(4, 2))),
            valid.copy(ranges = listOf(PrivacyProtectedRange(10, 13), PrivacyProtectedRange(0, 5))),
            valid.copy(ranges = listOf(PrivacyProtectedRange(0, 5), PrivacyProtectedRange(3, 8)))
        )
        invalid.forEach { receipt ->
            assertFalse(receipt.validFor(text))
            assertTrue(receipt.protectedValues(text).isEmpty())
        }
    }

    @Test
    fun normalizationMergesActualCoveredOffsetsWithoutKeepingInvalidRanges() {
        val text = "Alice and Bob"
        val receipt = PrivacyProtectionReceipt.create(
            text,
            listOf(PrivacyProtectedRange(3, 5), PrivacyProtectedRange(0, 3), PrivacyProtectedRange(0, 4), PrivacyProtectedRange(-1, 4))
        )
        assertEquals(listOf(PrivacyProtectedRange(0, 5)), receipt.ranges)
        assertEquals(listOf("Alice"), receipt.protectedValues(text))
    }

    @Test
    fun plaintextValuesAndReplacementDictionaryAreAbsentFromPersistedReceiptShape() {
        val text = "private-code-4927"
        val receipt = PrivacyProtectionReceipt.create(text, listOf(PrivacyProtectedRange(0, text.length)))
        val fields = PrivacyProtectionReceipt::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(setOf("contentDigest", "ranges", "confirmed", "version"), fields)
        assertEquals(64, receipt.contentDigest.length)
        assertFalse(receipt.toString().contains(text))
        assertFalse(receipt.toString().contains("PRIVATE_"))
    }

    @Test
    fun unmaskedOccurrencePreventsLiteralWideHighlighting() {
        val text = "Alice met Alice."
        val firstOnly = PrivacyProtectionReceipt.create(text, listOf(range(text, "Alice")))
        assertTrue(firstOnly.protectedValues(text).isEmpty())
        val both = PrivacyProtectionReceipt.create(text, listOf(PrivacyProtectedRange(0, 5), PrivacyProtectedRange(10, 15)))
        assertEquals(listOf("Alice"), both.protectedValues(text))
    }

    @Test
    fun overlappingUnprotectedOccurrenceAlsoSuppressesHighlighting() {
        val text = "ababa"
        val receipt = PrivacyProtectionReceipt.create(text, listOf(PrivacyProtectedRange(0, 3)))
        assertTrue(receipt.protectedValues(text).isEmpty())
    }

    @Test
    fun retryIntersectionOnlyRetainsWordsProtectedByBothCompletedAttempts() {
        val text = "Alice and Bob"
        val alice = range(text, "Alice")
        val bob = range(text, "Bob")
        val previous = PrivacyProtectionReceipt.create(text, listOf(alice, bob))
        val retry = PrivacyProtectionReceipt.create(text, listOf(bob))
        assertEquals(listOf(bob), previous.intersect(text, retry).ranges)
        assertEquals(listOf("Bob"), previous.intersect(text, retry).protectedValues(text))
        val unprotected = PrivacyProtectionReceipt.create(text, emptyList())
        assertTrue(unprotected.intersect(text, previous).ranges.isEmpty())
        assertTrue(previous.intersect(text, unprotected).ranges.isEmpty())
    }

    @Test
    fun retryCannotRetainEvidenceForDifferentContentOrInvalidNextReceipt() {
        val text = "Alice and Bob"
        val valid = PrivacyProtectionReceipt.create(text, listOf(range(text, "Alice")))
        val stale = PrivacyProtectionReceipt.create("Alice arrived", listOf(PrivacyProtectedRange(0, 5)))
        assertTrue(valid.intersect(text, stale).protectedValues(text).isEmpty())
        assertTrue(stale.intersect(text, valid).protectedValues(text).isEmpty())
        assertTrue(valid.intersect(text, valid.copy(version = 2)).protectedValues(text).isEmpty())
    }

    @Test
    fun pendingUpdateCannotReopenAConfirmedUnprotectedReceipt() {
        val text = "Alice"
        val empty = PrivacyProtectionReceipt.create(text, emptyList())
        val pending = PrivacyProtectionReceipt.create(text, emptyList(), confirmed = false)
        val protected = PrivacyProtectionReceipt.create(text, listOf(PrivacyProtectedRange(0, text.length)))
        val retained = empty.intersect(text, pending).intersect(text, protected)
        assertTrue(retained.confirmed)
        assertTrue(retained.ranges.isEmpty())
    }

    @Test
    fun pendingReceiptIsInvisibleUntilConfirmedEvidenceArrives() {
        val text = "Alice and Bob"
        val pending = PrivacyProtectionReceipt.create(text, emptyList(), confirmed = false)
        val confirmed = PrivacyProtectionReceipt.create(text, listOf(range(text, "Alice")))
        assertTrue(pending.protectedValues(text).isEmpty())
        assertTrue(confirmed.copy(confirmed = false).protectedValues(text).isEmpty())
        val completed = pending.intersect(text, confirmed)
        assertTrue(completed.confirmed)
        assertEquals(listOf("Alice"), completed.protectedValues(text))
    }

    @Test
    fun receiptHelperUsesSelectedRevisionForUserAndAssistantMessages() {
        val current = "Alice is here."
        val old = "Bob was here."
        for (profile in listOf(null, "test-profile")) {
            val message = MessageV2(
                content = current,
                platformType = profile,
                timeline = listOf(notice(current, "Alice")),
                revisions = listOf(AssistantRevision(content = old, createdAt = 1L, timeline = listOf(notice(old, "Bob"))))
            )
            assertEquals(listOf("Alice"), message.effectivePrivacyProtectedValues())
            assertEquals(listOf("Bob"), message.copy(activeRevisionIndex = 0).effectivePrivacyProtectedValues())
            assertEquals(listOf("Alice"), message.copy(activeRevisionIndex = 99).effectivePrivacyProtectedValues())
        }
    }

    @Test
    fun anUnprotectedHistoricalRevisionCannotBorrowCurrentProofEvenForIdenticalText() {
        val text = "Alice is here."
        val message = MessageV2(
            content = text,
            platformType = "test-profile",
            timeline = listOf(notice(text, "Alice")),
            revisions = listOf(AssistantRevision(content = text, createdAt = 1L))
        )
        assertTrue(message.copy(activeRevisionIndex = 0).effectivePrivacyProtectedValues().isEmpty())
        assertTrue(message.copy(content = "Alice was here.").effectivePrivacyProtectedValues().isEmpty())
    }

    @Test
    fun legacyMessagesFragmentReceiptsAndPendingProofRemainUnhighlighted() {
        val text = "Alice is here."
        val legacy = MessageV2(content = text, platformType = null)
        assertTrue(legacy.effectivePrivacyProtectedValues().isEmpty())
        val fragment = notice(text, "Alice").copy(type = AssistantTimelineItemType.TEXT)
        assertTrue(legacy.copy(timeline = listOf(fragment)).effectivePrivacyProtectedValues().isEmpty())
        val pending = AssistantTimelineItem(
            AssistantTimelineItemType.NOTICE,
            privacyProtection = PrivacyProtectionReceipt.create(text, listOf(range(text, "Alice")), confirmed = false)
        )
        assertTrue(legacy.copy(timeline = listOf(notice(text, "Alice"), pending)).effectivePrivacyProtectedValues().isEmpty())
    }

    private fun range(text: String, value: String): PrivacyProtectedRange {
        val start = text.indexOf(value)
        check(start >= 0)
        return PrivacyProtectedRange(start, start + value.length)
    }

    private fun notice(text: String, value: String) = AssistantTimelineItem(
        AssistantTimelineItemType.NOTICE,
        privacyProtection = PrivacyProtectionReceipt.create(text, listOf(range(text, value)))
    )
}
