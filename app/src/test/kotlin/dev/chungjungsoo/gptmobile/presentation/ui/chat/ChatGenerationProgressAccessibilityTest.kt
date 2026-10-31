package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChatGenerationProgressAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun statusAnnouncementStaysStableWhileDotsAnimateAndUpdatesWithActivity() {
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
        compose.onNodeWithContentDescription("Checking workflows").assertExists()
        compose.onNodeWithTag("github_loading_total_time").assertExists()
        compose.onNodeWithTag("github_loading_bar").assertExists()
        compose.mainClock.advanceTimeBy(1800)
        compose.onNodeWithContentDescription("Checking workflows").assertExists()
        compose.runOnIdle { active = false }
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithTag("github_loading_bar").assertDoesNotExist()
    }

    @Test fun githubReplyShowsAnEllipsisOnlyWhileItIsGenerating() {
        var loading by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalChatProgressInHeader provides true) {
                    AssistantChronologicalContent(
                        timeline = emptyList(), toolEvents = emptyList(), fallbackText = "Repository update", fallbackThoughts = "", contentIdentity = "github-reply",
                        isLoading = loading, animateResponse = false, debugMode = false, showExecutionTrace = false, showReasoning = false, expanded = false, onExpandedChange = {}
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithTag("github_generating_ellipsis").assertExists()
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithContentDescription("Response is still generating").assertExists()
        compose.runOnIdle { loading = false }
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithTag("github_generating_ellipsis").assertDoesNotExist()
    }
}
