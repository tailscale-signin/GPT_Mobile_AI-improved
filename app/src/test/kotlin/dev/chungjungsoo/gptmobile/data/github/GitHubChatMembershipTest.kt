package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubChatMembershipTest {
    private val context = GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), "llama")

    @Test fun legacyMultiProfileRoomKeepsIdentityAndDraftButOnlyOneParticipant() {
        val old = ChatRoomV2(id = 6, title = "repo", enabledPlatform = listOf("llama", "remote"), conversationMode = ConversationMode.COMBINED, draftText = "continue", combinedPrimaryUid = "remote")
        val repaired = GitHubChatMembership.normalize(old, context)
        assertEquals(listOf("llama"), repaired.enabledPlatform)
        assertEquals(listOf("llama"), repaired.activePlatform)
        assertEquals(ConversationMode.STANDARD, repaired.conversationMode)
        assertEquals(old.id, repaired.id)
        assertEquals(old.draftText, repaired.draftText)
        assertEquals(repaired, GitHubChatMembership.normalize(repaired, context))
        assertTrue(GitHubChatMembership.accepts(listOf("llama"), context))
        assertFalse(GitHubChatMembership.accepts(listOf("llama", "remote"), context))
        assertFalse(GitHubChatMembership.accepts(listOf("remote"), context))
    }

    @Test fun onlyShortStatusRequestsUseTheBoundedReadOnlyPath() {
        assertTrue(GitHubStatusRequest.matches(" status ? "))
        assertTrue(GitHubStatusRequest.matches("Repository status"))
        assertFalse(GitHubStatusRequest.matches("Fix status generation and create a PR"))
        assertFalse(GitHubStatusRequest.matches("Audit all features"))
        assertTrue(GitHubStatusRequest.matches("Please inspect the repository status and recent commits"))
        assertFalse(GitHubStatusRequest.matches("Show repository open issues"))
        assertFalse(GitHubStatusRequest.matches("Inspect repository status and delete old branches"))
        assertFalse(GitHubStatusRequest.matches("Check repository CI and build an APK"))
        assertFalse(GitHubStatusRequest.matches("Review repository status and update the dependencies"))
    }

    @Test fun gatewayRepositoryHistoryKeepsItsSerialQueueMarkerWhenWorkspaceMembershipRestores() {
        val gateway = ChatRoomV2(id = 7, title = "Saved job", enabledPlatform = listOf("llama"), conversationMode = ConversationMode.GATEWAY)
        val normalized = GitHubChatMembership.normalize(gateway, context)
        assertEquals(ConversationMode.GATEWAY, normalized.conversationMode)
        assertEquals(gateway.id, normalized.id)
        assertEquals(listOf("llama"), normalized.activePlatform)
    }
}
