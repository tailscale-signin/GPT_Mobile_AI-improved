package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
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
}
