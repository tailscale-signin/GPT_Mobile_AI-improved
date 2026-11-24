package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.github.GitHubProgressSnapshot
import dev.chungjungsoo.gptmobile.data.github.GitHubRichSummaryPresentation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GitHubRichSummaryRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun summary(source: String): String = GitHubRichSummaryPresentation.render(
        GitHubProgressSnapshot(
            revision = 1,
            task = "Review check results",
            completed = 1,
            failed = 0,
            activity = "Inspecting checks",
            recentSteps = listOf(source),
            next = "Inspect the failed step"
        )
    )

    private fun timeline(text: String) = listOf(
        AssistantTimelineItem(
            type = AssistantTimelineItemType.NOTICE,
            content = text,
            progressCheckpoint = true,
            statusSummary = true
        )
    )

    @Test fun structuredSummaryRendersMarkdownTableAndSourceCountChart() {
        val text = summary("Recorded checks: 12 successful, 4 skipped, 0 in progress, 1 failed (complete captured list).")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).testTag("github_summary_host")) {
                    GitHubLiveProgressCard(timeline(text), active = false)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("github_activity_rich_summary").assertExists()
        compose.onNodeWithTag("github_summary_copy_csv").assertExists()
        compose.onNodeWithTag("github_check_count_chart").assertExists()
        compose.onNodeWithText("Recorded check distribution").assertExists()
    }

    @Test fun modelAuthoredTableCannotAcquireSourceChartOrExportActions() {
        val text = summary("Recorded checks: 12 successful, 4 skipped, 0 in progress, 1 failed (complete captured list).")
        compose.setContent {
            MaterialTheme {
                GitHubLiveProgressCard(timeline(text).map { it.copy(modelAuthored = true) }, active = false)
            }
        }
        compose.onNodeWithTag("github_activity_rich_summary").assertExists()
        compose.onNodeWithTag("github_summary_copy_csv").assertDoesNotExist()
        compose.onNodeWithTag("github_check_count_chart").assertDoesNotExist()
    }

    @Test fun modelAuthoredEmphasisUsesMarkdownWithoutAHeadingAndBulletTemplate() {
        val text = "**Reader.kt** retries before recording the response. I’ll check cancellation next."
        compose.setContent {
            MaterialTheme {
                GitHubLiveProgressCard(timeline(text).map { it.copy(modelAuthored = true) }, active = false)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("github_activity_rich_summary").assertExists()
        compose.onNodeWithText("Reader.kt", substring = true).assertExists()
        compose.onNodeWithText("**Reader.kt**", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("github_summary_copy_csv").assertDoesNotExist()
        compose.onNodeWithTag("github_check_count_chart").assertDoesNotExist()
    }

    @Test fun narrowChartRetainsLargeCountsAndAccessibleStateLabels() {
        val text = summary("Recorded checks: 100000 successful, 1 skipped, 100000 in progress, 1 failed (incomplete captured list).")
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    Column(Modifier.width(240.dp)) { GitHubRichSummaryChart(text) }
                }
            }
        }
        compose.onNodeWithContentDescription("In progress: 100000 recorded checks").assertExists()
        compose.onNodeWithContentDescription("Successful: 100000 recorded checks").assertExists()
        compose.onNodeWithTag("github_summary_copy_csv").assertExists()
    }

    @Test fun sourceOnlySummaryDoesNotInventATableOrGraph() {
        val text = summary("The source read for Reader.kt returned a result for inspection.")
        compose.setContent { MaterialTheme { GitHubLiveProgressCard(timeline(text), active = false) } }
        compose.waitForIdle()
        compose.onNodeWithText("Reader.kt", substring = true).assertExists()
        compose.onNodeWithTag("github_activity_rich_summary").assertDoesNotExist()
        compose.onNodeWithTag("github_check_count_chart").assertDoesNotExist()
        compose.onNodeWithTag("github_summary_copy_csv").assertDoesNotExist()
    }

    @Test fun originalTextOnlySummaryStillUsesItsExistingLayout() {
        compose.setContent { MaterialTheme { GitHubLiveProgressCard(timeline("A previously saved plain progress paragraph."), active = false) } }
        compose.onNodeWithTag("github_activity_summary").assertExists()
        compose.onNodeWithTag("github_activity_rich_summary").assertDoesNotExist()
    }
}
