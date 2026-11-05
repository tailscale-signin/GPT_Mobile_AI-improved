package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubConnectionSetupTest {
    private val connection = ToolConnection("github", "GitHub", "github", "GITHUB", "https://api.github.com", "OAUTH", null, null)

    @Test fun anUnfinishedAuthorizationRecordKeepsTheWelcomeScreenVisible() {
        val state = ToolConnectionsViewModel.ToolConnectionsUiState(connections = listOf(connection))
        assertNull(gitHubModeConnection(state))
        assertEquals(connection, gitHubModeConnection(state.copy(gitHubAccounts = mapOf("github" to GitHubPublicAuthCoordinator.Account("1", "coder")))))
    }

    @Test fun disabledConnectionsRemainInSetupUntilExplicitlyEnabled() {
        val ready = connection.copy(secretRef = "secret-reference")
        val state = ToolConnectionsViewModel.ToolConnectionsUiState(connections = listOf(ready))
        assertEquals(ready, gitHubModeConnection(state))
        assertNull(gitHubModeConnection(state.copy(pluginStates = mapOf(ToolPluginId.GITHUB to false))))
        assertNull(gitHubModeConnection(state.copy(pluginStates = mapOf(ToolPluginId.connection("github") to false))))
    }

    @Test fun incompleteRecordsDoNotHideAnotherAuthorizedConnection() {
        val authorized = connection.copy(connectionUid = "authorized", secretRef = "secret-reference")
        assertEquals(authorized, gitHubModeConnection(ToolConnectionsViewModel.ToolConnectionsUiState(connections = listOf(connection, authorized))))
    }
}
