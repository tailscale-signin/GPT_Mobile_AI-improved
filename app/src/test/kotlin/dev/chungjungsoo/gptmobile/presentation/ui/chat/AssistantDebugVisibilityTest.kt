package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AssistantDebugVisibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disablingDebugRemovesAnAlreadyExpandedProcessAndKeepsTheAnswerAndIcon() {
        var debug by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                AssistantChronologicalContent(
                    timeline = listOf(
                        AssistantTimelineItem(AssistantTimelineItemType.THINKING, "Private reasoning"),
                        AssistantTimelineItem(AssistantTimelineItemType.NOTICE, "Internal execution resource"),
                        AssistantTimelineItem(AssistantTimelineItemType.TEXT, "Repository answer")
                    ),
                    toolEvents = emptyList(),
                    fallbackText = "Repository answer",
                    fallbackThoughts = "Private reasoning",
                    contentIdentity = "response",
                    isLoading = false,
                    debugMode = debug,
                    showExecutionTrace = true,
                    responseIcon = { Text("AI icon") },
                    showReasoning = true,
                    expanded = true,
                    onExpandedChange = {},
                    isError = true
                )
            }
        }
        compose.onNodeWithContentDescription("Collapse activity").assertExists()
        compose.onNodeWithText("Private reasoning").assertExists()
        compose.onNodeWithText("Internal execution resource").assertExists()
        val icon = compose.onNodeWithText("AI icon").fetchSemanticsNode()
        val toggle = compose.onNodeWithContentDescription("Collapse activity").fetchSemanticsNode()
        assertTrue(icon.boundsInRoot.center.y in toggle.boundsInRoot.top..toggle.boundsInRoot.bottom)

        compose.runOnIdle { debug = false }
        compose.onNodeWithContentDescription("Collapse activity").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand activity").assertDoesNotExist()
        compose.onNodeWithText("Private reasoning").assertDoesNotExist()
        compose.onNodeWithText("Internal execution resource").assertDoesNotExist()
        compose.onNodeWithText("AI icon").assertExists()
        compose.onNodeWithText("Repository answer").assertExists()
    }
}
