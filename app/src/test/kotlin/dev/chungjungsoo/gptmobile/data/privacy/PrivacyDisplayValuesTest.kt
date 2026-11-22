package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyDisplayValuesTest {
    @Test
    fun markdownInsideAProtectedPhraseRetainsItsIndividualWordIndicators() {
        val message = message("Your partner **Alice** called.", "partner **Alice** called")

        assertEquals(setOf("partner **Alice** called", "partner", "Alice", "called"), message.effectivePrivacyProtectedValues().toSet())
    }

    @Test
    fun anUnprotectedRepeatedWordSuppressesThatWordWithoutBorrowingThePhraseReceipt() {
        val message = message("Your partner **Alice** called. Alice is a separate literal.", "partner **Alice** called")
        val values = message.effectivePrivacyProtectedValues()

        assertFalse("Alice" in values)
        assertTrue("partner" in values)
        assertTrue("called" in values)
    }

    @Test
    fun unprotectedSubstringsCannotAcquireAWholeWordPrivacyIndicator() {
        val message = message("Your partner **Ann** called. Announcement follows.", "partner **Ann** called")

        assertFalse("Ann" in message.effectivePrivacyProtectedValues())
    }

    @Test
    fun unicodeNamesApostrophesAndHyphensRemainWholeDisplayValues() {
        val phrase = "**João** [O’Neill](local) Anne-Marie 李明"
        val values = message(phrase, phrase).effectivePrivacyProtectedValues()

        assertTrue(values.containsAll(listOf("João", "O’Neill", "Anne-Marie", "李明")))
        assertFalse("Neill" in values)
    }

    @Test
    fun changedTextAndAnUnprotectedHistoricalRevisionCannotReuseLatestEvidence() {
        val saved = message("Your partner **Alice** called.", "partner **Alice** called")
        assertTrue(saved.copy(content = "Changed Alice text").effectivePrivacyProtectedValues().isEmpty())
        val historical = saved.copy(
            revisions = listOf(AssistantRevision(content = saved.content, createdAt = 1)),
            activeRevisionIndex = 0
        )
        assertTrue(historical.effectivePrivacyProtectedValues().isEmpty())
    }

    private fun message(text: String, protected: String): MessageV2 {
        val start = text.indexOf(protected)
        return MessageV2(
            content = text,
            platformType = "remote",
            timeline = listOf(
                AssistantTimelineItem(
                    type = AssistantTimelineItemType.NOTICE,
                    privacyProtection = PrivacyProtectionReceipt.create(text, listOf(PrivacyProtectedRange(start, start + protected.length)))
                )
            )
        )
    }
}
