package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import org.junit.Assert.assertEquals
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
        assertProcessVisibilityPreservesPreparedAnswer(isError = true)
    }

    @Test fun changingDebugKeepsThePreparedMarkdownAnswerMounted() {
        assertProcessVisibilityPreservesPreparedAnswer(isError = false)
    }

    @Test fun diagnosticOnlyTimelineKeepsTheFallbackAnswerAndProcessVisibilityAcrossDebugChanges() {
        assertProcessVisibilityPreservesPreparedAnswer(isError = false, includeChronologicalText = false)
    }

    @Test fun changingDebugPreservesEveryChronologicalAnswerFragmentExactlyOnce() {
        assertMultipartAnswerSurvivesDebugChanges(filterTimelineAtCaller = false)
    }

    @Test fun changingDebugPreservesEveryAnswerFragmentWhenTheCallerFiltersProcessEntries() {
        assertMultipartAnswerSurvivesDebugChanges(filterTimelineAtCaller = true)
    }

    private fun assertProcessVisibilityPreservesPreparedAnswer(isError: Boolean, includeChronologicalText: Boolean = true) {
        var debug by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                AssistantChronologicalContent(
                    timeline = buildList {
                        add(AssistantTimelineItem(AssistantTimelineItemType.THINKING, "Private reasoning"))
                        add(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, "Internal execution resource"))
                        if (includeChronologicalText) add(AssistantTimelineItem(AssistantTimelineItemType.TEXT, "Repository answer"))
                    },
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
                    isError = isError
                )
            }
        }
        compose.onNodeWithContentDescription("Collapse activity").assertExists()
        compose.onNodeWithText("Private reasoning").assertExists()
        compose.onNodeWithText("Internal execution resource").assertExists()
        val icon = compose.onNodeWithText("AI icon").fetchSemanticsNode()
        val toggle = compose.onNodeWithContentDescription("Collapse activity").fetchSemanticsNode()
        assertTrue(icon.boundsInRoot.center.y in toggle.boundsInRoot.top..toggle.boundsInRoot.bottom)
        // Initial parsing runs on Dispatchers.Default. Once displayed, toggling
        // diagnostics must preserve the answer without another preparation gap.
        compose.waitUntil(5000) { compose.onAllNodesWithText("Repository answer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Repository answer").assertIsDisplayed()
        val answerNodeId = compose.onNodeWithText("Repository answer").fetchSemanticsNode().id

        compose.runOnIdle { debug = false }
        compose.onNodeWithContentDescription("Collapse activity").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand activity").assertDoesNotExist()
        compose.onNodeWithText("Private reasoning").assertDoesNotExist()
        compose.onNodeWithText("Internal execution resource").assertDoesNotExist()
        compose.onNodeWithText("AI icon").assertExists()
        compose.onNodeWithText("Repository answer").assertIsDisplayed()
        assertEquals("Hiding process entries must not remount the prepared answer", answerNodeId, compose.onNodeWithText("Repository answer").fetchSemanticsNode().id)

        compose.runOnIdle { debug = true }
        compose.onNodeWithContentDescription("Collapse activity").assertExists()
        compose.onNodeWithText("Private reasoning").assertExists()
        compose.onNodeWithText("Internal execution resource").assertExists()
        compose.onNodeWithText("AI icon").assertExists()
        compose.onNodeWithText("Repository answer").assertIsDisplayed()
        assertEquals("Restoring process entries must not remount the prepared answer", answerNodeId, compose.onNodeWithText("Repository answer").fetchSemanticsNode().id)
    }

    private fun assertMultipartAnswerSurvivesDebugChanges(filterTimelineAtCaller: Boolean) {
        var debug by mutableStateOf(true)
        val answers = listOf("First repository finding.", "Second repository finding.")
        val timeline = listOf(
            AssistantTimelineItem(AssistantTimelineItemType.THINKING, "Private reasoning"),
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, answers[0]),
            AssistantTimelineItem(AssistantTimelineItemType.TOOL, toolSequence = 1),
            AssistantTimelineItem(AssistantTimelineItemType.NOTICE, "Internal execution resource"),
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, answers[1])
        )
        val tool = ToolEvent(
            eventId = "event", runId = "run", sequence = 1, callId = "call",
            connectionUidSnapshot = null, connectionNameSnapshot = null,
            toolName = "inspect_repository", modelToolName = "inspect_repository",
            arguments = "{}", result = "Private tool result", resultType = "TEXT", status = ToolEventStatus.COMPLETED
        )
        compose.setContent {
            MaterialTheme {
                AssistantChronologicalContent(
                    timeline = if (filterTimelineAtCaller && !debug) timeline.filter { it.type == AssistantTimelineItemType.TEXT } else timeline,
                    toolEvents = listOf(tool),
                    fallbackText = answers.joinToString("\n\n"),
                    fallbackThoughts = "Private reasoning",
                    contentIdentity = "multipart-response",
                    isLoading = false,
                    debugMode = debug,
                    showExecutionTrace = true,
                    responseIcon = { Text("AI icon") },
                    showReasoning = true,
                    expanded = true,
                    onExpandedChange = {}
                )
            }
        }
        compose.waitUntil(5000) { answers.all { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() } }
        val answerNodeIds = answers.map { compose.onNodeWithText(it).fetchSemanticsNode().id }

        fun assertAnswersRemainMounted() {
            answers.forEachIndexed { index, answer ->
                compose.onAllNodesWithText(answer, substring = true).assertCountEquals(1)
                compose.onNodeWithText(answer).assertIsDisplayed()
                assertEquals("Answer fragment $index must survive process visibility changes", answerNodeIds[index], compose.onNodeWithText(answer).fetchSemanticsNode().id)
            }
        }

        compose.onNodeWithContentDescription("Collapse activity").assertExists()
        compose.onNodeWithText("Private reasoning").assertExists()
        compose.onNodeWithText("Internal execution resource").assertExists()
        compose.onNodeWithContentDescription("inspect_repository · Completed. Expand tool details").assertExists()
        compose.onNodeWithText("AI icon").assertExists()
        assertAnswersRemainMounted()

        compose.runOnIdle { debug = false }
        compose.onNodeWithContentDescription("Collapse activity").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand activity").assertDoesNotExist()
        compose.onNodeWithText("Private reasoning").assertDoesNotExist()
        compose.onNodeWithText("Internal execution resource").assertDoesNotExist()
        compose.onNodeWithContentDescription("inspect_repository · Completed. Expand tool details").assertDoesNotExist()
        compose.onNodeWithText("Private tool result").assertDoesNotExist()
        compose.onNodeWithText("AI icon").assertExists()
        assertAnswersRemainMounted()

        compose.runOnIdle { debug = true }
        compose.onNodeWithContentDescription("Collapse activity").assertExists()
        compose.onNodeWithText("Private reasoning").assertExists()
        compose.onNodeWithText("Internal execution resource").assertExists()
        compose.onNodeWithContentDescription("inspect_repository · Completed. Expand tool details").assertExists()
        compose.onNodeWithText("AI icon").assertExists()
        assertAnswersRemainMounted()
    }
}
