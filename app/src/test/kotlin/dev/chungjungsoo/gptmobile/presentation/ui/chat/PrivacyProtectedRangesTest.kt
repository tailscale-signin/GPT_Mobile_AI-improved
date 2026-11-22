package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyProtectedRangesTest {
    @Test
    fun removingThinkingCannotLendProofToNewlyJoinedCharacters() {
        assertTrue(privacyProtectedValuesAfterThinking("Al<think>Alice</think>ice", "Alice", listOf("Alice")).isEmpty())
        assertTrue(privacyProtectedValuesAfterThinking("Alice and Al<think>hidden</think>ice", "Alice and Alice", listOf("Alice")).isEmpty())
    }

    @Test
    fun unchangedWordsAfterHiddenThinkingKeepTheirOwnProtection() {
        assertEquals(
            listOf("Bob", "João"),
            privacyProtectedValuesAfterThinking("Al<THINK>Alice</THINK>ice and Bob 😀 João", "Alice and Bob 😀 João", listOf("Alice", "Bob", "João"))
        )
        assertEquals(listOf("Alice"), privacyProtectedValuesAfterThinking("  <think>hidden</think> Hello **Alice**  ", "Hello **Alice**", listOf("Alice")))
        assertEquals(listOf("Alice"), privacyProtectedValuesAfterThinking("Hello Alice<think>unfinished", "Hello Alice", listOf("Alice")))
    }

    @Test
    fun unknownThinkingProjectionNeverGuessesCorrespondenceFromARepeatedLiteral() {
        assertTrue(privacyProtectedValuesAfterThinking("prefix Alice", "Alice", listOf("Alice")).isEmpty())
        assertTrue(privacyProtectedValuesAfterThinking("Al<think>Alice</think>ice", "Alice Alice", listOf("Alice")).isEmpty())
        assertEquals(listOf("Alice"), privacyProtectedValuesAfterThinking("  Alice  ", "  Alice  ", listOf("Alice")))
    }

    @Test
    fun privateLookingTextWithoutProtectionEvidenceIsNotHighlighted() {
        assertTrue(privacyProtectedRanges("Email alex@example.com and call 555-123-4567", emptyList()).isEmpty())
    }

    @Test
    fun matchingIsExactAndDoesNotGuessCaseOrSynonyms() {
        assertEquals(listOf(0..3), privacyProtectedRanges("Melo melo Emanuel", listOf("Melo")))
        assertTrue(privacyProtectedRanges("Melo", listOf("Emanuel")).isEmpty())
    }

    @Test
    fun repeatedProtectedValuesAndOverlappingReceiptsProduceStableRanges() {
        val text = "Alice Smith met Alice."
        assertEquals(listOf(0..10, 16..20), privacyProtectedRanges(text, listOf("Alice", "Alice Smith", "Alice")))
    }

    @Test
    fun punctuationIsLiteralAndNeverEvaluatedAsARegularExpression() {
        val text = "a+b@example.com aXb@example.com"
        assertEquals(listOf(0..14), privacyProtectedRanges(text, listOf("a+b@example.com")))
    }

    @Test
    fun unicodeUsesTheSameUtf16OffsetsAsCompose() {
        val text = "😀 João went home. João"
        val ranges = privacyProtectedRanges(text, listOf("João"))
        assertEquals(listOf(3..6, 19..22), ranges)
        assertEquals(listOf("João", "João"), ranges.map { text.substring(it) })
    }

    @Test
    fun emptyAndBlankEvidenceNeverColoursUnrelatedText() {
        assertTrue(privacyProtectedRanges("ordinary text", listOf("", " ", "\n")).isEmpty())
        assertTrue(privacyProtectedRanges("", listOf("private")).isEmpty())
    }

    @Test
    fun adjacentAndOverlappingValuesAreCoalescedWithoutLosingCharacters() {
        assertEquals(listOf(0..5), privacyProtectedRanges("abcabc", listOf("ab", "bc", "abc")))
    }

    @Test
    fun markdownAssembledLiteralCannotBorrowAnotherOccurrencesProtection() {
        val source = "Alice and A**li**ce"
        val displayed = "Alice and Alice"
        val runs = listOf(
            PrivacyVerbatimRun(0, 11, 0, 11),
            PrivacyVerbatimRun(13, 15, 11, 13),
            PrivacyVerbatimRun(17, 19, 13, 15)
        )
        assertEquals(listOf(0..4), privacyProtectedRangesFromSource(displayed, listOf("Alice"), source, runs))
    }

    @Test
    fun protectedUrlDestinationCannotLendProofToAnAssembledVisibleLabel() {
        val source = "[A**li**ce](https://example.com/Alice)"
        val runs = listOf(
            PrivacyVerbatimRun(1, 2, 0, 1),
            PrivacyVerbatimRun(4, 6, 1, 3),
            PrivacyVerbatimRun(8, 10, 3, 5)
        )
        assertTrue(privacyProtectedRangesFromSource("Alice", listOf("Alice"), source, runs).isEmpty())
    }

    @Test
    fun aLiteralWordInsideBoldMarkdownRetainsItsProtection() {
        val source = "Your partner **Alice** called."
        assertEquals(
            listOf(13..17),
            privacyProtectedRangesFromSource("Your partner Alice called.", listOf("Alice"), source, listOf(PrivacyVerbatimRun(15, 20, 13, 18)))
        )
    }

    @Test
    fun uncertainOrChangedSourceRunsNeverClaimProtection() {
        for (run in listOf(
            PrivacyVerbatimRun(-1, 4, 0, 5),
            PrivacyVerbatimRun(2, 1, 0, 5),
            PrivacyVerbatimRun(0, 99, 0, 5),
            PrivacyVerbatimRun(0, 5, -1, 4),
            PrivacyVerbatimRun(0, 5, 3, 2),
            PrivacyVerbatimRun(0, 5, 0, 99),
            PrivacyVerbatimRun(0, 4, 0, 5),
            PrivacyVerbatimRun(1, 6, 0, 5)
        )) {
            assertTrue(privacyProtectedRangesFromSource("Alice", listOf("Alice"), "Alice!", listOf(run)).isEmpty())
        }
        assertTrue(privacyProtectedRangesFromSource("Alice", listOf("Alice"), "**Alice**", emptyList()).isEmpty())
    }
}
