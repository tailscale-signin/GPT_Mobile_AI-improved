package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GitHubSetupWelcomeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactSetupKeepsConnectAndBackReachable() {
        var connections = 0
        var backs = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(320.dp, 480.dp)) {
                    GitHubSetupWelcome(onConnect = { connections++ }, onBack = { backs++ })
                }
            }
        }
        compose.onNodeWithText("Connect GitHub").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        assertEquals(1, connections)
        assertEquals(1, backs)
    }

    @Test fun authorizationInProgressPreventsDuplicateConnectActions() {
        compose.setContent {
            MaterialTheme { GitHubSetupWelcome(onConnect = {}, onBack = {}, isConnecting = true) }
        }
        compose.onNodeWithText("Connecting…").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun setupOffersPublicOAuthConfigurationAndAnEnterpriseManagementPath() {
        var managed = false
        compose.setContent {
            MaterialTheme {
                GitHubSetupWelcome(onConnect = {}, onBack = {}, clientId = "", error = "Add your app's client ID", onManageConnections = { managed = true })
            }
        }
        compose.onNodeWithText("GitHub OAuth client ID").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add your app's client ID").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Manage existing or enterprise connections").performScrollTo().performClick()
        org.junit.Assert.assertTrue(managed)
    }

    @Test fun multipleAccountsShowTheirHostAndOpenOnlyTheChosenConnection() {
        val personal = ToolConnection("personal", "Personal GitHub", "personal", "GITHUB", "https://api.github.com", "OAUTH", "personal-secret", null)
        val enterprise = personal.copy(connectionUid = "work", name = "Work GitHub", alias = "work", endpointUrl = "https://git.company.example/api/v3", secretRef = "work-secret")
        var state by mutableStateOf(
            ToolConnectionsViewModel.ToolConnectionsUiState(
                connections = listOf(personal, enterprise),
                gitHubAccounts = mapOf(personal.connectionUid to GitHubPublicAuthCoordinator.Account("1", "coder"))
            )
        )
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(320.dp, 480.dp)) {
                    GitHubConnectionEntry(state, {}, {}, setup = { Text("Connection setup") }) { Text("Workspace: ${it.connectionUid}") }
                }
            }
        }
        compose.onNodeWithText("Choose a GitHub connection").assertIsDisplayed()
        compose.onNodeWithText("@coder · github.com · personal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("git.company.example · work").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connection setup").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(connections = listOf(enterprise, personal)) }
        compose.onNodeWithText("Workspace: work").assertDoesNotExist()
        compose.onNodeWithTag("github_connection_work").performScrollTo().performClick()
        compose.onNodeWithText("Workspace: work").assertIsDisplayed()
        compose.onNodeWithText("Workspace: personal").assertDoesNotExist()
    }

    @Test fun soleAccountOpensDirectlyButRevocationRequiresAnExplicitReplacement() {
        val personal = ToolConnection("personal", "Personal GitHub", "personal", "GITHUB", "https://api.github.com", "OAUTH", "personal-secret", null)
        val work = personal.copy(connectionUid = "work", name = "Work GitHub", alias = "work", secretRef = "work-secret")
        var state by mutableStateOf(ToolConnectionsViewModel.ToolConnectionsUiState(connections = listOf(personal)))
        var managed = 0
        var backs = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(320.dp, 480.dp)) {
                    GitHubConnectionEntry(state, { backs++ }, { managed++ }, setup = { Text("Connection setup") }) { Text("Workspace: ${it.connectionUid}") }
                }
            }
        }
        compose.onNodeWithText("Workspace: personal").assertIsDisplayed()
        compose.onNodeWithText("Choose a GitHub connection").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(connections = listOf(work, personal)) }
        compose.onNodeWithText("Workspace: personal").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(pluginStates = mapOf(ToolPluginId.connection(personal.connectionUid) to false)) }
        compose.onNodeWithText("Choose a GitHub connection").assertIsDisplayed()
        compose.onNodeWithText("Workspace: work").assertDoesNotExist()
        compose.onNodeWithText("Connection setup").assertDoesNotExist()
        compose.onNodeWithText("Manage connections").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, managed)
        assertEquals(1, backs)
        compose.onNodeWithTag("github_connection_work").performScrollTo().performClick()
        compose.onNodeWithText("Workspace: work").assertIsDisplayed()
    }
}
