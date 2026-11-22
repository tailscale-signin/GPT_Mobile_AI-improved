package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.PublicProgressParser
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateProtectionContextTest {
    @Test
    fun answerCannotBorrowAProtectedRepeatedValueFromDiscardedProgress() {
        val text = "<progress_update>Alice</progress_update>Alice"
        val context = context()
        val start = text.indexOf("Alice")
        context.restored(protected(text, start, start + "Alice".length))
        val answer = PublicProgressParser().accept(text, flush = true).single { !it.first }.second

        assertEquals("Alice", answer)
        assertTrue(context.displayReceipt(answer).ranges.isEmpty())
    }

    @Test
    fun splitChunksRetainOnlyTheirActualProtectedSourceOffsets() {
        val context = context()
        context.restored(protected("Hello Alice!", 6, 11))

        assertTrue(context.displayReceipt("Hello ").ranges.isEmpty())
        assertEquals(listOf(PrivacyProtectedRange(0, 2)), context.displayReceipt("Al").ranges)
        val tail = context.displayReceipt("ice!")
        assertEquals(listOf(PrivacyProtectedRange(0, 3)), tail.ranges)
        assertTrue(tail.validFor("ice!"))
        assertEquals(listOf("ice"), tail.protectedValues("ice!"))
    }

    @Test
    fun ambiguousRepeatedChunksCannotBorrowEitherSourceOccurrence() {
        val context = context()
        context.restored(protected("Alice met Alice", 0, 5))

        assertTrue(context.displayReceipt("Alice").ranges.isEmpty())
        assertTrue(context.displayReceipt(" met ").ranges.isEmpty())
        assertTrue(context.displayReceipt("Alice").ranges.isEmpty())
    }

    @Test
    fun aFullReplyWithAnUnprotectedRepeatedValueCannotClaimAllOccurrencesStayedLocal() {
        val context = context()
        context.restored(protected("Alice met Alice", 0, 5))

        val receipt = context.displayReceipt("Alice met Alice")
        assertEquals(listOf(PrivacyProtectedRange(0, 5)), receipt.ranges)
        assertTrue(receipt.protectedValues("Alice met Alice").isEmpty())
    }

    @Test
    fun unrelatedDisplayTextCannotBorrowProtectionFromEarlierRestoredValues() {
        val context = context()
        context.restored(protected("Hello Alice", 6, 11))

        assertTrue(context.displayReceipt("Alice appears in another response").ranges.isEmpty())
        assertTrue(context.displayReceipt("Hello Alice").ranges.isEmpty())
    }

    @Test
    fun separateResponsesNeverShareThePreviousResponseOffsetOrProtectedValues() {
        val context = context()
        context.restored(protected("One Alice", 4, 9))
        context.restored(protected("Two Bob", 4, 7))

        assertEquals(listOf("Alice"), context.displayReceipt("One Alice").protectedValues("One Alice"))
        assertEquals(listOf("Bob"), context.displayReceipt("Two Bob").protectedValues("Two Bob"))
        assertTrue(context.displayReceipt("Alice").ranges.isEmpty())
    }

    @Test
    fun metadataRemovalMaySkipSourceCharactersButNeverRewindTheResponse() {
        val context = context()
        context.restored(protected("<!-- subject -->\nHello Alice", 23, 28))

        assertEquals(listOf("Alice"), context.displayReceipt("Hello Alice").protectedValues("Hello Alice"))
        assertTrue(context.displayReceipt("Hello Alice").ranges.isEmpty())
    }

    @Test
    fun invalidAndUnconfirmedResponseReceiptsNeverEstablishProtection() {
        for (receipt in listOf(
            PrivacyProtectionReceipt.create("different", listOf(PrivacyProtectedRange(0, 5))),
            PrivacyProtectionReceipt.create("Alice", listOf(PrivacyProtectedRange(0, 5)), confirmed = false)
        )) {
            val context = context()
            context.restored(PrivateProtectedText("Alice", receipt))
            assertTrue(context.displayReceipt("Alice").ranges.isEmpty())
        }
    }

    @Test
    fun clearingAfterCancellationDropsResponsesQueuedInputsAndProjectionOffset() = runBlocking {
        val saved = mutableListOf<MessageV2>()
        val context = PrivateProtectionContext { message, _ -> saved += message }
        context.input(user(), PrivacyProtectionReceipt.create("Alice", listOf(PrivacyProtectedRange(0, 5))))
        context.restored(protected("Alice", 0, 5))
        context.displayReceipt("Al")

        context.clear()
        context.flushInputs()

        assertTrue(saved.isEmpty())
        assertTrue(context.displayReceipt("ice").ranges.isEmpty())
        context.restored(protected("Bob", 0, 3))
        assertEquals(listOf("Bob"), context.displayReceipt("Bob").protectedValues("Bob"))
    }

    @Test
    fun persistedUserAndAssistantHistoryAreQueuedAndSuccessfulFlushDoesNotRepeatThem() = runBlocking {
        val saved = mutableListOf<MessageV2>()
        val context = PrivateProtectionContext { message, _ -> saved += message }
        val receipt = PrivacyProtectionReceipt.create("Alice", listOf(PrivacyProtectedRange(0, 5)))
        context.input(user(), receipt)
        context.input(user().copy(id = 0), receipt)
        context.input(user().copy(id = 2, platformType = "remote"), receipt)

        context.flushInputs()
        context.flushInputs()

        assertEquals(listOf(user(), user().copy(id = 2, platformType = "remote")), saved)
    }

    private fun context() = PrivateProtectionContext { _, _ -> }

    private fun user() = MessageV2(id = 1, content = "Alice", platformType = null, createdAt = 1)

    private fun protected(text: String, start: Int, end: Int): PrivateProtectedText =
        PrivateProtectedText(text, PrivacyProtectionReceipt.create(text, listOf(PrivacyProtectedRange(start, end))))
}
