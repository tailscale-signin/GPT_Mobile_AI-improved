package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChatGenerationProgressAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun statusAnnouncementStaysStableWhileProgressAnimatesAndUpdatesWithActivity() {
        var status by mutableStateOf("Checking workflows")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme { ChatGenerationProgress(emptyList(), emptyList(), statusOverride = status) }
        }
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithContentDescription("Checking workflows").assertExists()
        compose.mainClock.advanceTimeBy(1600)
        compose.onNodeWithContentDescription("Checking workflows").assertExists()
        compose.runOnIdle { status = "Reviewing changes" }
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithContentDescription("Reviewing changes").assertExists()
    }

    @Test fun githubHeaderKeepsActivitySeparateFromItsTimerWhileTheGradientPans() {
        var active by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme { ChatGenerationProgress(emptyList(), emptyList(), statusOverride = "Status: Checking workflows · 12s elapsed", active = active, startedAtMillis = 1000, githubPresentation = true) }
        }
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithText("Running on this device").assertDoesNotExist()
        compose.onNodeWithContentDescription("Checking workflows").assertExists()
        compose.onNodeWithTag("github_loading_total_time").assertExists()
        compose.onNodeWithTag("github_loading_bar").assertExists()
        compose.mainClock.advanceTimeBy(1800)
        compose.onNodeWithContentDescription("Checking workflows").assertExists()
        compose.runOnIdle { active = false }
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithTag("github_loading_bar").assertDoesNotExist()
    }

    @Test fun githubTranscriptOmitsTheRedundantWorkingEllipsis() {
        var loading by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalChatProgressInHeader provides true) {
                    LazyColumn {
                        item {
                            OpponentChatBubble(
                                canRetry = false,
                                isLoading = loading,
                                text = "Repository update",
                                contentIdentity = "github-reply",
                                showExecutionTrace = false,
                                showReasoning = false
                            )
                        }
                        item { Text("Review proposed changes", Modifier.testTag("repository_decision")) }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithTag("repository_decision").assertExists()
        compose.onNodeWithTag("github_generating_ellipsis").assertDoesNotExist()
        compose.runOnIdle { loading = false }
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithTag("github_generating_ellipsis").assertDoesNotExist()
    }
}
