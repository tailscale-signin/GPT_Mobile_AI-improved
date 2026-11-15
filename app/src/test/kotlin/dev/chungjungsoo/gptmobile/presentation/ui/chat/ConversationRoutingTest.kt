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
    }

    @Test fun missingWrongTypeOrUnrelatedConnectionsKeepTheSavedGitHubDestination() {
        for (connections in listOf(emptyList(), listOf(connection.copy(type = "MCP")), listOf(connection.copy(connectionUid = "another-account")))) {
            val destination = ConversationRoutingState(features, connections).githubDestination(42)
            assertEquals(context, destination?.first)
            assertNull(destination?.second)
        }
    }

    @Test fun disablingEitherPluginKeepsTheSavedGitHubDestinationUnavailable() {
        for (plugin in listOf(ToolPluginId.GITHUB, ToolPluginId.connection("github"))) {
            val destination = ConversationRoutingState(features.withToolPluginEnabled(plugin, false), listOf(connection)).githubDestination(42)
            assertEquals(context, destination?.first)
            assertNull(destination?.second)
        }
    }

    @Test fun ordinaryChatsRemainOrdinaryWhenGitHubIsUnavailable() {
        val ordinary = features.copy(conversationGitHub = emptyMap()).withToolPluginEnabled(ToolPluginId.GITHUB, false)
        assertNull(ConversationRoutingState(ordinary, emptyList()).githubDestination(42))
        assertNull(ConversationRoutingState(ordinary, listOf(connection)).githubDestination(42))
    }

    @Test fun restoringOnlyTheOriginalEnabledConnectionReopensTheSameWorkspace() {
        val disabled = features.withToolPluginEnabled(ToolPluginId.GITHUB, false).withToolPluginEnabled(ToolPluginId.connection("github"), false)
        val githubEnabled = disabled.withToolPluginEnabled(ToolPluginId.GITHUB, true)
        val bothEnabled = githubEnabled.withToolPluginEnabled(ToolPluginId.connection("github"), true)
        for ((settings, connections) in listOf(
            disabled to listOf(connection),
            githubEnabled to listOf(connection),
            bothEnabled to emptyList(),
            bothEnabled to listOf(connection.copy(connectionUid = "another-account"))
        )) {
            val destination = ConversationRoutingState(settings, connections).githubDestination(42)
            assertEquals(context, destination?.first)
            assertNull(destination?.second)
        }
        assertEquals(context to connection, ConversationRoutingState(bothEnabled, listOf(connection)).githubDestination(42))
        assertEquals(context, bothEnabled.conversationGitHub[42])
    }

    @Test fun unavailableWorkspaceCannotBorrowAnotherChatsEnabledConnection() {
        val otherContext = context.copy(connectionUid = "another-account", repo = "another-repository", mainProfileUid = "another-profile")
        val otherConnection = connection.copy(connectionUid = otherContext.connectionUid)
        val settings = features.copy(conversationGitHub = mapOf(42 to context, 43 to otherContext)).withToolPluginEnabled(ToolPluginId.connection("github"), false)
        val state = ConversationRoutingState(settings, listOf(connection, otherConnection))
        val unavailable = state.githubDestination(42)
        assertEquals(context, unavailable?.first)
        assertNull(unavailable?.second)
        assertEquals(otherContext to otherConnection, state.githubDestination(43))
    }
}
