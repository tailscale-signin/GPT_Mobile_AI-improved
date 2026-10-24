package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.chat.CompletedGeneration
import dev.chungjungsoo.gptmobile.data.chat.ReplyDisplayRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUnreadRepliesTest {
    private val published = CompletedGeneration("reply", 2, 11, 100, revision = 1, profileUid = "a", displayRole = ReplyDisplayRole.DIRECT, contentDigest = "answer-digest")

    @Test fun onlyPublishedVisibleAnswersBecomeUnread() {
        val inputs = listOf(
            published,
            published.copy(runId = "contributor", displayRole = ReplyDisplayRole.CONTRIBUTOR),
            published.copy(runId = "internal", displayRole = ReplyDisplayRole.INTERNAL),
            published.copy(runId = "not-committed", revision = 0),
            published.copy(runId = "no-answer", contentDigest = ""),
            published.copy(runId = "no-message", assistantMessageId = 0)
        )
        assertEquals(listOf(published), homeUnreadReplies(inputs, emptyMap()))
    }

    @Test fun childReplyMarksItsWorkspaceWithoutLosingExactRevisionIdentity() {
        val mapped = homeUnreadReplies(listOf(published), mapOf(1 to listOf(2, 3))).single()
        assertEquals(1, mapped.visibleChatId)
        assertEquals(published, mapped.copy(visibleChatId = published.visibleChatId))
    }

    @Test fun acknowledgedReplyDisappearsAndNewRevisionBecomesUnreadAgain() {
        assertEquals(listOf(published), homeUnreadReplies(listOf(published), emptyMap()))
        // The publication store removes only acknowledged revisions from this input.
        assertTrue(homeUnreadReplies(emptyList(), emptyMap()).isEmpty())
        val revision = published.copy(revision = 2, contentDigest = "new-answer-digest")
        assertEquals(listOf(revision), homeUnreadReplies(listOf(revision), emptyMap()))
    }

    @Test fun aPublishedPartialAnswerStillNeedsToBeRead() {
        val partial = published.copy(deliveryKind = "PARTIAL")
        assertEquals(listOf(partial), homeUnreadReplies(listOf(partial), emptyMap()))
    }
}
