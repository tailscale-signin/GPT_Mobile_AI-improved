package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationRoutingTest {
    private val context = GitHubConversationContext("github", "owner", "repo", "branch", "a".repeat(40), "coder")
    private val connection = ToolConnection("github", "GitHub", "github", "GITHUB", null, "OAUTH", "credential", null)
    private val features = AppFeatureSettings(conversationGitHub = mapOf(42 to context))

    @Test fun onlyTheSavedConnectionAndChatSelectGitHub() {
        val state = ConversationRoutingState(features, listOf(connection))
        assertEquals(context to connection, state.githubDestination(42))
        assertNull(state.githubDestination(43))
        assertNull(ConversationRoutingState(features, emptyList()).githubDestination(42))
        assertNull(ConversationRoutingState(features, listOf(connection.copy(type = "MCP"))).githubDestination(42))
    }

    @Test fun disablingEitherPluginRestoresOrdinaryChatRouting() {
        for (plugin in listOf(ToolPluginId.GITHUB, ToolPluginId.connection("github"))) {
            assertNull(ConversationRoutingState(features.withToolPluginEnabled(plugin, false), listOf(connection)).githubDestination(42))
        }
    }
}
