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

    @Test fun multiplePersonalAndEnterpriseConnectionsRequireSelectionInEitherInventoryOrder() {
        val personal = connection.copy(secretRef = "personal-secret")
        val enterprise = connection.copy(connectionUid = "enterprise", name = "Work", alias = "work", endpointUrl = "https://git.company.example/api/v3", secretRef = "work-secret")
        for (connections in listOf(listOf(personal, enterprise), listOf(enterprise, personal))) {
            val state = ToolConnectionsViewModel.ToolConnectionsUiState(connections = connections)
            assertEquals(setOf(personal, enterprise), gitHubModeConnections(state).toSet())
            assertNull(gitHubModeConnection(state))
            assertEquals(personal, gitHubModeConnection(state, personal.connectionUid))
            assertEquals(enterprise, gitHubModeConnection(state, enterprise.connectionUid))
        }
        assertEquals("github.com", gitHubConnectionHost(personal))
        assertEquals("git.company.example", gitHubConnectionHost(enterprise))
    }

    @Test fun removedOrRevokedSelectionCannotFallBackToTheRemainingAccount() {
        val selected = connection.copy(secretRef = "selected-secret")
        val remaining = selected.copy(connectionUid = "remaining", alias = "remaining", secretRef = "remaining-secret")
        val state = ToolConnectionsViewModel.ToolConnectionsUiState(connections = listOf(selected, remaining))
        for (changed in listOf(
            state.copy(connections = listOf(remaining)),
            state.copy(connections = listOf(selected.copy(secretRef = null), remaining)),
            state.copy(pluginStates = mapOf(ToolPluginId.connection(selected.connectionUid) to false)),
            state.copy(pluginStates = mapOf(ToolPluginId.GITHUB to false))
        )) {
            assertNull(gitHubModeConnection(changed, selected.connectionUid))
        }
        assertEquals(remaining, gitHubModeConnection(state.copy(connections = listOf(remaining)), remaining.connectionUid))
    }

    @Test fun expiredPublicAuthorizationCannotRemainSelectedWithoutCredentials() {
        val state = ToolConnectionsViewModel.ToolConnectionsUiState(
            connections = listOf(connection),
            gitHubAccounts = mapOf(connection.connectionUid to GitHubPublicAuthCoordinator.Account("1", "coder"))
        )
        assertEquals(connection, gitHubModeConnection(state, connection.connectionUid))
        assertNull(gitHubModeConnection(state.copy(gitHubAccounts = emptyMap()), connection.connectionUid))
    }

    @Test fun pickerHostLabelsNeverEchoCredentialsOrQueryParameters() {
        assertEquals("Host unavailable", gitHubConnectionHost(connection.copy(endpointUrl = "https://secret@git.company.example/api/v3?token=hidden")))
    }
}
