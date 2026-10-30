package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.chungjungsoo.gptmobile.data.github.GitHubCheckWatch
import dev.chungjungsoo.gptmobile.data.github.GitHubPullRequestHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GitHubChatChecksCompactTest {
    @get:Rule val compose = createComposeRule()
    private val watch = GitHubCheckWatch("id", 1, "connection", "profile", "owner", "repo", 760, null, "a".repeat(40), 1, health = GitHubPullRequestHealth(state = "CLOSED"))

    @Test fun compactCardKeepsActionsVisibleAndMovesMetadataIntoDetails() {
        var stopped = 0
        var opened = 0
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            MaterialTheme {
                GitHubCheckWatchCard(watch, { opened++ }, {}, { stopped++ }, Modifier.testTag("card"))
            }
        }
        assertTrue(compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot.height / density <= 92f)
        compose.onNodeWithText("PR closed.").assertIsDisplayed()
        compose.onNodeWithText("owner/repo").assertDoesNotExist()
        compose.onNodeWithText("GitHub").performClick()
        compose.onNodeWithText("Stop").performClick()
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(1, stopped)
        }
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("owner/repo").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("owner/repo").assertDoesNotExist()
    }
}
