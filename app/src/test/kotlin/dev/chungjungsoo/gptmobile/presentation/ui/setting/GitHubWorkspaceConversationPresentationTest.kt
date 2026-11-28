package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubWorkspaceConversationPresentationTest {
    private val context = GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), "coder")
    private val resolved = GitHubProgrammerState(
        context = context,
        profileUid = "coder",
        chatId = 42,
        privacyModeLocked = false,
        conversationResolved = true,
        draftLoaded = true,
        messages = listOf(MessageV2(id = 7, chatId = 42, content = "Saved answer", platformType = "coder")),
        canLoadEarlier = true,
        eventCount = 2,
        eventCountsByRun = mapOf("saved-run" to 2)
    )

    @Test fun resolvedMatchingRoomKeepsItsIdentityAndHistory() {
        val visible = resolved.forWorkspaceConversation("github", context.repository, context)
        assertEquals(42, visible.chatId)
        assertEquals(resolved.messages, visible.messages)
        assertEquals(resolved.eventCountsByRun, visible.eventCountsByRun)
        assertTrue(visible.canLoadEarlier)
    }

    @Test fun savedRouteContextCannotMountAPendingOrMissingRoom() {
        for (state in listOf(resolved.copy(conversationResolved = false), resolved.copy(chatId = null))) {
            val visible = state.forWorkspaceConversation("github", context.repository, context)
            assertEquals(context, visible.context)
            assertNull(visible.chatId)
            assertTrue(visible.messages.isEmpty())
            assertEquals(0, visible.eventCount)
            assertTrue(visible.eventCountsByRun.isEmpty())
            assertFalse(visible.canLoadEarlier)
        }
    }

    @Test fun pendingWorkspaceCannotBorrowTheSavedRoutesRoomIdentity() {
        val visible = resolved.forWorkspaceConversation("github", null, context)
        assertEquals(context, visible.context)
        assertNull(visible.chatId)
        assertFalse(visible.draftLoaded)
    }

    @Test fun rejectedBindingsCannotMountBehindTheSelectedWorkspaceHeader() {
        val rejected = listOf(
            context.copy(connectionUid = "another-account"),
            context.copy(owner = "another-owner"),
            context.copy(repo = "another-repo"),
            context.copy(ref = "another-branch"),
            context.copy(mainProfileUid = "another-profile")
        )
        for (binding in rejected) {
            val visible = resolved.copy(context = binding).forWorkspaceConversation("github", context.repository, context)
            assertEquals(context, visible.context)
            assertNull(visible.chatId)
            assertTrue(visible.messages.isEmpty())
        }
    }

    @Test fun unknownOrIncompatiblePrivacyCannotMountAComposer() {
        assertNull(resolved.copy(privacyModeLocked = null).forWorkspaceConversation("github", context.repository, context).chatId)
        assertNull(resolved.forWorkspaceConversation("github", context.repository, context, privateRoute = true).chatId)
        val privateRoom = resolved.copy(privacyModeLocked = true)
        assertEquals(42, privateRoom.forWorkspaceConversation("github", context.repository, context, privateRoute = true).chatId)
        assertEquals(42, privateRoom.forWorkspaceConversation("github", context.repository, context, privateRoute = false).chatId)
    }

    @Test fun failedRestorationCannotReuseAPreviouslyVisibleRoom() {
        val failed = resolved.copy(conversationRestoreFailed = true, error = "Could not restore conversation")
        val visible = failed.forWorkspaceConversation("github", context.repository, context)
        assertNull(visible.chatId)
        assertTrue(visible.messages.isEmpty())
        assertTrue(visible.conversationRestoreFailed)
        assertEquals(failed.error, visible.error)
    }

    @Test fun unrelatedConversationErrorsDoNotHideAnAcceptedRoom() {
        val visible = resolved.copy(error = "A previous command failed").forWorkspaceConversation("github", context.repository, context)
        assertEquals(42, visible.chatId)
        assertEquals(resolved.messages, visible.messages)
    }
}
