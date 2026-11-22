package dev.chungjungsoo.gptmobile.data.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateTextMaskReceiptTest {
    @Test
    fun protectedInputReceiptUsesOriginalOffsetsAndRestorationCreatesResponseOffsets() {
        val mask = PrivateTextMask(setOf(PrivacyCategory.CUSTOM), listOf("Alice"))
        val input = "🙂 Alice arrived."
        val protected = mask.protectWithReceipt(input)
        assertFalse(protected.text.contains("Alice"))
        assertEquals(listOf(PrivacyProtectedRange(3, 8)), protected.receipt.ranges)
        assertTrue(protected.receipt.validFor(input))
        assertFalse(protected.receipt.validFor(protected.text))
        val marker = protected.text.removePrefix("🙂 ").removeSuffix(" arrived.")
        val restored = mask.sealWithReceipts()("Hello $marker!")
        assertEquals("Hello Alice!", restored.text)
        assertEquals(listOf(PrivacyProtectedRange(6, 11)), restored.receipt.ranges)
        assertTrue(restored.receipt.validFor(restored.text))
        assertEquals(listOf("Alice"), restored.receipt.protectedValues(restored.text))
    }

    @Test
    fun echoedLiteralWithoutAMarkerCannotBeClaimedAsKeptLocal() {
        val mask = PrivateTextMask(setOf(PrivacyCategory.CUSTOM), listOf("Alice"))
        val marker = mask.protect("Alice")
        val restored = mask.sealWithReceipts()("$marker met Alice.")
        assertEquals("Alice met Alice.", restored.text)
        assertEquals(listOf(PrivacyProtectedRange(0, 5)), restored.receipt.ranges)
        assertTrue(restored.receipt.protectedValues(restored.text).isEmpty())
    }

    @Test
    fun repeatedGenuineMarkersReceiveIndependentRanges() {
        val mask = PrivateTextMask(setOf(PrivacyCategory.CUSTOM), listOf("Alice"))
        val marker = mask.protect("Alice")
        val restored = mask.sealWithReceipts()("$marker met $marker.")
        assertEquals("Alice met Alice.", restored.text)
        assertEquals(listOf(PrivacyProtectedRange(0, 5), PrivacyProtectedRange(10, 15)), restored.receipt.ranges)
        assertEquals(listOf("Alice"), restored.receipt.protectedValues(restored.text))
    }

    @Test
    fun anotherRequestAndUnknownMarkerIndicesNeverRestorePrivateValues() {
        val first = PrivateTextMask(setOf(PrivacyCategory.CUSTOM), listOf("Alice"))
        val second = PrivateTextMask(setOf(PrivacyCategory.CUSTOM), listOf("Alice"))
        val firstMarker = first.protect("Alice")
        val secondMarker = second.protect("Alice")
        val restore = first.sealWithReceipts()
        val unknown = firstMarker.replace("_0]", "_999]")
        val foreign = "$secondMarker $unknown [PRIVATE_1]"
        val restored = restore(foreign)
        assertEquals(foreign, restored.text)
        assertTrue(restored.receipt.ranges.isEmpty())
        assertEquals("Alice", restore(firstMarker).text)
    }

    @Test
    fun preseededIntroductionsKeepBothNamesLocalAcrossAllRequestMessages() {
        val introduction = "My girlfriend Alice and I, (Sam) are dating and are looking for ideas."
        val previous = "ALICE and Sam enjoy museums."
        val mask = PrivateTextMask(setOf(PrivacyCategory.IDENTITIES), emptyList())
        mask.seedIdentities(listOf(previous, introduction))
        val earlier = mask.protectWithReceipt(previous)
        val current = mask.protectWithReceipt(introduction)
        assertFalse(earlier.text.contains("Alice", ignoreCase = true) || earlier.text.contains("Sam"))
        assertFalse(current.text.contains("Alice") || current.text.contains("Sam"))
        assertEquals(listOf("ALICE", "Sam"), earlier.receipt.protectedValues(previous))
        assertEquals(listOf("Alice", "Sam"), current.receipt.protectedValues(introduction))
        val restore = mask.sealWithReceipts()
        assertEquals(previous, restore(earlier.text).text)
        assertEquals(introduction, restore(current.text).text)
    }

    @Test
    fun categoryOptOutPreservesNamesAndProducesNoProofForThem() {
        val introduction = "My girlfriend Alice and I, (Sam) are dating."
        val mask = PrivateTextMask(setOf(PrivacyCategory.CONTACTS), emptyList())
        mask.seedIdentities(listOf(introduction))
        val protected = mask.protectWithReceipt(introduction)
        assertEquals(introduction, protected.text)
        assertTrue(protected.receipt.ranges.isEmpty())
        val restored = mask.sealWithReceipts()("Alice and Sam")
        assertEquals("Alice and Sam", restored.text)
        assertTrue(restored.receipt.ranges.isEmpty())
    }

    @Test
    fun responseWithoutMarkersDoesNotInheritTheInputReceipt() {
        val mask = PrivateTextMask(setOf(PrivacyCategory.CONTACTS), emptyList())
        mask.protectWithReceipt("Email ada@example.org.")
        val restored = mask.sealWithReceipts()("Here are three ideas.")
        assertTrue(restored.receipt.validFor(restored.text))
        assertTrue(restored.receipt.ranges.isEmpty())
    }

    @Test
    fun maskAndRestorationReceiptsContainNoPlaintextReplacementDictionary() {
        val secret = "private-code-4927"
        val mask = PrivateTextMask(setOf(PrivacyCategory.CUSTOM), listOf(secret))
        val protected = mask.protectWithReceipt(secret)
        assertFalse(protected.text.contains(secret))
        assertFalse(protected.receipt.toString().contains(secret))
        val restore = mask.sealWithReceipts()
        val restored = restore(protected.text)
        assertEquals(secret, restored.text)
        assertFalse(restored.receipt.toString().contains(secret))
        assertFalse(restored.receipt.toString().contains("PRIVATE_"))
    }
}
