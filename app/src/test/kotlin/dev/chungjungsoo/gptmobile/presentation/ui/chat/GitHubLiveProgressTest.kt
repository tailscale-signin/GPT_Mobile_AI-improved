package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GitHubLiveProgressTest {
    @get:Rule val compose = createComposeRule()
    private fun line(text: String, summary: Boolean = false) = AssistantTimelineItem(AssistantTimelineItemType.NOTICE, content = text, progressCheckpoint = true, statusSummary = summary)

    @Test fun summaryUsesThemeForegroundAbovePreviewAndOnlyTheNewestBottomLineAnimates() {
        val summary = "I’ll inspect the loading bar."
        val first = line("Reading source files.")
        var timeline by mutableStateOf(listOf(line(summary, true), first))
        var colors by mutableStateOf(lightColorScheme())
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme(colorScheme = colors) { GitHubLiveProgressCard(timeline, "Fix the loading bar") } }
        compose.mainClock.advanceTimeBy(1600)
        compose.onNodeWithText("Working in your repository").assertDoesNotExist()
        val title = compose.onNodeWithTag("github_activity_summary").fetchSemanticsNode()
        val preview = compose.onNodeWithTag("github_latest_preview").fetchSemanticsNode()
        assertTrue(title.boundsInRoot.bottom <= preview.boundsInRoot.top)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("github_activity_summary").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(colors.onSurface, layouts.single().layoutInput.style.color)
        compose.runOnIdle { colors = darkColorScheme() }
        compose.mainClock.advanceTimeBy(16)
        layouts.clear()
        compose.onNodeWithTag("github_activity_summary").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(colors.onSurface, layouts.single().layoutInput.style.color)

        compose.runOnIdle { timeline = timeline + line("Reading the newest preview. ".repeat(50)) }
        compose.mainClock.advanceTimeBy(1600)
        compose.onAllNodesWithTag("github_latest_preview").assertCountEquals(1)
        compose.onAllNodesWithTag("github_previous_preview").assertCountEquals(1)
        layouts.clear()
        compose.onNodeWithTag("github_latest_preview").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertEquals(0.5f, layouts.single().layoutInput.style.color.alpha, 0.01f)

        compose.runOnIdle { timeline = timeline + line("The files are available; checking the next step.", true) }
        compose.mainClock.advanceTimeBy(16)
        compose.onAllNodesWithTag("github_latest_preview").assertCountEquals(0)
        compose.onAllNodesWithTag("github_previous_preview").assertCountEquals(2)
        compose.onAllNodesWithTag("github_activity_summary").assertCountEquals(2)
        val newSummary = compose.onNodeWithText("The files are available; checking the next step.").fetchSemanticsNode()
        val previousPreview = compose.onAllNodesWithTag("github_previous_preview").fetchSemanticsNodes().last()
        assertTrue("The next summary follows the faded preview", newSummary.boundsInRoot.top >= previousPreview.boundsInRoot.bottom)
        val enteringTop = newSummary.boundsInRoot.top
        compose.mainClock.advanceTimeBy(320)
        val settledTop = compose.onNodeWithText("The files are available; checking the next step.").fetchSemanticsNode().boundsInRoot.top
        assertTrue("The new summary moves downward", settledTop > enteringTop)
    }

    @Test fun taskIntroductionAppearsBeforeAnyProgressEvents() {
        compose.setContent { MaterialTheme { GitHubLiveProgressCard(emptyList(), "status") } }
        compose.onNodeWithText("I’ll check the repository status, current branch, pull requests and latest checks.").assertExists()
        compose.onAllNodesWithTag("github_latest_preview").assertCountEquals(0)
    }

    @Test fun eachPreviewHasAnOutlineIconInOneAlignedLeadingColumn() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                GitHubLiveProgressCard(
                    listOf(
                        line("I’ll inspect the request.", true),
                        line("Searching the internet for relevant information."),
                        line("Examining source files."),
                        line("Waiting for the build checks.")
                    )
                )
            }
        }
        compose.mainClock.advanceTimeBy(1600)
        compose.onAllNodesWithTag("github_preview_row").assertCountEquals(3)
        val icons = listOf("web", "inspect", "wait").map { compose.onNodeWithTag("github_preview_icon_$it").fetchSemanticsNode() }
        val previews = compose.onAllNodesWithTag("github_previous_preview").fetchSemanticsNodes() + compose.onNodeWithTag("github_latest_preview").fetchSemanticsNode()
        icons.zip(previews).forEach { (icon, preview) ->
            assertTrue(icon.boundsInRoot.right < preview.boundsInRoot.left)
            assertEquals(icon.boundsInRoot.center.y, preview.boundsInRoot.center.y, 1f)
            assertEquals(icons.first().boundsInRoot.left, icon.boundsInRoot.left, 0.01f)
            assertEquals(previews.first().boundsInRoot.left, preview.boundsInRoot.left, 0.01f)
            assertTrue("Decorative icons do not repeat the spoken activity", !icon.config.contains(SemanticsProperties.ContentDescription))
        }
        compose.onAllNodesWithTag("github_latest_preview").assertCountEquals(1)
    }

    @Test fun repeatedSummaryStillEntersDownwardAfterOlderRowsAreTrimmed() {
        fun block(id: Int) = listOf(
            line("Checking the result.", true).copy(progressId = "summary-$id"),
            line("Waiting for the check.").copy(progressId = "preview-$id")
        )
        var timeline by mutableStateOf(block(1) + block(2))
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { GitHubLiveProgressCard(timeline) } }
        compose.mainClock.advanceTimeBy(1600)
        compose.runOnIdle { timeline = block(2) + block(3) }
        compose.mainClock.advanceTimeBy(16)
        val entering = compose.onAllNodesWithTag("github_activity_summary").fetchSemanticsNodes().last().boundsInRoot.top
        compose.mainClock.advanceTimeBy(320)
        val settled = compose.onAllNodesWithTag("github_activity_summary").fetchSemanticsNodes().last().boundsInRoot.top
        assertTrue(settled > entering)
        compose.onAllNodesWithTag("github_latest_preview").assertCountEquals(1)
    }
}
