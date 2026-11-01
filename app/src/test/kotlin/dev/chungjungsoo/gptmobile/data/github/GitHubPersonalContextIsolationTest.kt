package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataCategory
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPersonalContextIsolationTest {
    private fun history(label: PersonalDataProvenance) = listOf(
        listOf(
            MessageV2(
                chatId = 7,
                content = "A reply derived from saved context",
                platformType = "local",
                timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, personalData = listOf(label)))
            )
        )
    )
    private val repository = PersonalDataProvenance(
        sourceToolId = "github_engineering_memory",
        sourceProfileUid = "local",
        sourceRef = "digest",
        categories = setOf(PersonalDataCategory.PRIVATE_CONTENT),
        localOnly = true,
        sourceConnectionUid = "github-connection",
        retentionChatId = 7
    )

    @Test fun generalPersonalMemoryRequiresPhysicallyCleanHistory() {
        assertTrue(GitHubPersonalContextIsolation.requiresFreshTurn(history(repository.copy(sourceToolId = "memory")), "github-connection", 7))
    }

    @Test fun repositoryMemoryCanReturnOnlyToTheSameChatAndConnection() {
        assertFalse(GitHubPersonalContextIsolation.requiresFreshTurn(history(repository), "github-connection", 7))
        assertTrue(GitHubPersonalContextIsolation.requiresFreshTurn(history(repository), "other-connection", 7))
        assertTrue(GitHubPersonalContextIsolation.requiresFreshTurn(history(repository), "github-connection", 8))
        assertTrue(GitHubPersonalContextIsolation.requiresFreshTurn(history(repository.copy(sourceConnectionUid = null)), "github-connection", 7))
    }

    @Test fun cleanProjectionDropsPrivateRepliesAndRecoveryButPreservesTheCurrentUserRequest() {
        val previous = MessageV2(chatId = 7, content = "Earlier request", platformType = null)
        val current = MessageV2(chatId = 7, content = "Fix this repository", platformType = null)
        val privateHistory = history(repository.copy(sourceToolId = "memory"))
        val projected = GitHubPersonalContextIsolation.project(listOf(previous, current), privateHistory, "github-connection", emptyList())
        assertTrue(projected.fresh)
        assertTrue(projected.assistants.isEmpty())
        assertTrue(projected.users == listOf(current))
        assertTrue(privateHistory.flatten().single().content.isNotEmpty())
        val legacy = GitHubPersonalContextIsolation.project(listOf(current), emptyList(), "github-connection", listOf(repository.copy(sourceConnectionUid = null)))
        assertTrue(legacy.fresh)
    }

    @Test fun changingToARemoteProfileDropsLocalRepositoryMemoryAndDerivedReplies() {
        val current = MessageV2(chatId = 7, content = "status", platformType = null)
        val remote = GitHubPersonalContextIsolation.project(listOf(current), history(repository), "github-connection", emptyList(), privateDestination = false)
        assertTrue(remote.fresh)
        assertTrue(remote.assistants.isEmpty())
        assertTrue(remote.users == listOf(current))
        val local = GitHubPersonalContextIsolation.project(listOf(current), history(repository), "github-connection", emptyList(), privateDestination = true)
        assertFalse(local.fresh)
        assertTrue(local.assistants.isNotEmpty())
    }

    @Test fun ordinaryRepositoryHistoryRemainsAvailable() {
        assertFalse(GitHubPersonalContextIsolation.requiresFreshTurn(listOf(listOf(MessageV2(content = "Read source file", platformType = "local"))), "github-connection", 7))
    }
}
