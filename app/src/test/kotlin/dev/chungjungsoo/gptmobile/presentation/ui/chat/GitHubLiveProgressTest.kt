package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GitHubLiveProgressTest {
    @get:Rule val compose = createComposeRule()
    private fun line(text: String, summary: Boolean = false) = AssistantTimelineItem(AssistantTimelineItemType.NOTICE, content = text, progressCheckpoint = true, statusSummary = summary)

    @Test fun finishedActivityKeepsHistoryWithoutAnAnimatedLatestRow() {
        var active by mutableStateOf(true)
        val timeline = listOf(line("Reviewing the task", true), line("Reading source files."))
        compose.setContent { MaterialTheme { GitHubLiveProgressCard(timeline, active = active) } }
        compose.onAllNodesWithTag("github_latest_preview").assertCountEquals(1)
        compose.runOnIdle { active = false }
        compose.onAllNodesWithTag("github_latest_preview").assertCountEquals(0)
        compose.onAllNodesWithTag("github_previous_preview").assertCountEquals(1)
    }

    @Test fun reducedEffectsFreezeProgressAtAVisiblePhaseAndRecoverAfterCooldown() {
        compose.mainClock.autoAdvance = false
        lateinit var phase: androidx.compose.runtime.State<Float>
        val guard = dev.chungjungsoo.gptmobile.data.diagnostics.FrameEffectPressure.guard
        try {
            compose.setContent { phase = rememberGitHubProgressMotion(true) }
            compose.mainClock.advanceTimeBy(400)
            compose.runOnIdle { guard.observeCriticalStall(300_000_000L, 0L) }
            compose.mainClock.advanceTimeBy(32)
            compose.runOnIdle { assertEquals(0.5f, phase.value, 0f) }
            compose.mainClock.advanceTimeBy(2000)
            compose.runOnIdle { assertEquals(0.5f, phase.value, 0f) }
            compose.runOnIdle { guard.recoverAfterCooldown(30_000L) }
            compose.mainClock.advanceTimeBy(400)
            compose.runOnIdle { assertTrue(phase.value != 0.5f) }
        } finally {
            guard.recoverAfterCooldown(30_000L)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun summariesUseParagraphTypographyAndOnlyTheNewestActivityHasAnAccent() {
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
        assertEquals(1.5.em, layouts.single().layoutInput.style.lineHeight)
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
        assertEquals(2, layouts.single().lineCount)
        assertEquals(colors.onSurface.copy(alpha = 0.5f), layouts.single().layoutInput.style.color)

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
        assertEquals("The arrival fade must not move the new paragraph", enteringTop, settledTop, 0f)
    }

    @Test fun taskIntroductionAppearsBeforeAnyProgressEvents() {
        compose.setContent { MaterialTheme { GitHubLiveProgressCard(emptyList(), "status") } }
        compose.onNodeWithText(dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation.introduction("status")).assertExists()
        compose.onAllNodesWithTag("github_latest_preview").assertCountEquals(0)
    }

    @Test fun countersChangeInTheSameSingleLineRowsWithoutMovingOrDuplicatingThem() {
        var timeline by mutableStateOf(listOf(line("I will verify the checks and changed files.", true), line("Check 2/8"), line("Files 1/3")))
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { GitHubLiveProgressCard(timeline) } }
        compose.mainClock.advanceTimeBy(1600)
        val checks = compose.onNodeWithText("Check 2/8").fetchSemanticsNode()
        val files = compose.onNodeWithText("Files 1/3").fetchSemanticsNode()
        compose.runOnIdle { timeline = timeline + line("Check 3/8") + line("Files 2/3") }
        compose.mainClock.advanceTimeBy(32)
        val updatedChecks = compose.onNodeWithText("Check 3/8").fetchSemanticsNode()
        val updatedFiles = compose.onNodeWithText("Files 2/3").fetchSemanticsNode()
        assertEquals(checks.id, updatedChecks.id)
        assertEquals(files.id, updatedFiles.id)
        assertEquals(checks.boundsInRoot.top, updatedChecks.boundsInRoot.top, .1f)
        assertEquals(files.boundsInRoot.top, updatedFiles.boundsInRoot.top, .1f)
        compose.onNodeWithText("Check 2/8").assertDoesNotExist()
        compose.onNodeWithText("Files 1/3").assertDoesNotExist()
        compose.onAllNodesWithTag("github_preview_row").assertCountEquals(2)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Check 3/8").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
    }

    @Test fun detailedSummariesRemainCompleteInCompactActivity() {
        val summary = "I verified the files changed by this request.\n".repeat(8) + "The remaining check is still running."
        compose.setContent { MaterialTheme { GitHubLiveProgressCard(listOf(line(summary, true).copy(modelAuthored = true)), expanded = false) } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("github_activity_summary").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Int.MAX_VALUE, layouts.single().layoutInput.maxLines)
        assertTrue(layouts.single().lineCount > 6)
        compose.onNodeWithTag("github_activity_summary").assertTextEquals(summary)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun overflowingActivityExpandsWithoutLosingItsPublicDescriptionOrRepeatingItForAccessibility() {
        val activity = "Checking the changed Kotlin files against the retained comparison, including the recovery boundary and the progress presentation.\nThe validation result is still pending, so this activity does not claim that the checks passed."
        var latest by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    GitHubActivityPreview(activity, latest, remember { mutableFloatStateOf(0.5f) }, animateEntry = false)
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("github_latest_preview").assertTextEquals(activity)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().lineCount)
        assertTrue(layouts.single().hasVisualOverflow)
        compose.onAllNodes(androidx.compose.ui.test.hasText(activity)).assertCountEquals(1)
        compose.onNodeWithTag("github_preview_details").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        layouts.clear()
        compose.onNodeWithTag("github_latest_preview").assertTextEquals(activity)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Int.MAX_VALUE, layouts.single().layoutInput.maxLines)
        assertTrue(layouts.single().lineCount > 2)
        assertTrue(!layouts.single().hasVisualOverflow)
        assertEquals("Expanded", compose.onNodeWithTag("github_preview_details").fetchSemanticsNode().config[SemanticsProperties.StateDescription])

        // Becoming history must not close a detail view that the user opened.
        compose.runOnIdle { latest = false }
        layouts.clear()
        compose.onNodeWithTag("github_previous_preview").assertTextEquals(activity)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Int.MAX_VALUE, layouts.single().layoutInput.maxLines)
        compose.onNodeWithTag("github_preview_details").performClick()
        layouts.clear()
        compose.onNodeWithTag("github_previous_preview").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().lineCount)
        assertEquals("Collapsed", compose.onNodeWithTag("github_preview_details").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        compose.onAllNodes(androidx.compose.ui.test.hasText(activity)).assertCountEquals(1)
    }

    @Test fun shortActivityNeedsNoExpansionControlAndKeepsItsCompactHeight() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp)) {
                    GitHubActivityPreview("Checked repository access", false, remember { mutableFloatStateOf(0.5f) }, animateEntry = false)
                }
            }
        }
        compose.onNodeWithTag("github_preview_details").assertDoesNotExist()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("github_previous_preview").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue(!layouts.single().hasVisualOverflow)
        val row = compose.onNodeWithTag("github_preview_row").fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithTag("github_previous_preview").fetchSemanticsNode().boundsInRoot
        assertEquals(text.height, row.height, 0f)
    }

    @Test fun arrivalFadeUsesOneAndAHalfSecondsAndDoesNotReplayWhenTheThemeChanges() {
        compose.mainClock.autoAdvance = false
        var dark by mutableStateOf(false)
        lateinit var opacity: State<Float>
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                opacity = rememberGitHubActivityEntry()
                androidx.compose.material3.Text("The saved result is available.")
            }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertTrue(opacity.value > 0f && opacity.value < 1f) }
        compose.mainClock.advanceTimeBy(688)
        compose.runOnIdle { assertEquals("The entrance is halfway through its 1500 ms fade", 0.5f, opacity.value, 0.05f) }
        compose.mainClock.advanceTimeBy(800)
        compose.runOnIdle {
            assertEquals(1f, opacity.value, 0f)
            dark = true
        }
        compose.mainClock.advanceTimeBy(16)
        compose.runOnIdle { assertEquals(1f, opacity.value, 0f) }
    }

    @Test fun replacementCrossfadeKeepsItsExitForOneAndAHalfSecondsAndAnnouncesOnlyCurrentText() {
        compose.mainClock.autoAdvance = false
        val previous = "The repository read is recorded."
        val replacement = "The test result is still pending."
        var text by mutableStateOf(previous)
        val rendered = mutableSetOf<String>()
        compose.setContent {
            MaterialTheme {
                GitHubActivityCrossfade(text) { displayed, _ ->
                    DisposableEffect(displayed) {
                        rendered.add(displayed)
                        onDispose { rendered.remove(displayed) }
                    }
                    Text(displayed)
                }
            }
        }
        compose.onNodeWithText(previous).assertExists()
        compose.runOnIdle { text = replacement }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText(previous).assertDoesNotExist()
        compose.onNodeWithText(replacement).assertExists()
        compose.runOnIdle { assertEquals(setOf(previous, replacement), rendered) }
        compose.mainClock.advanceTimeBy(720)
        compose.runOnIdle { assertEquals("The outgoing text must still be fading after 750 ms", setOf(previous, replacement), rendered) }
        compose.mainClock.advanceTimeBy(800)
        compose.runOnIdle { assertEquals(setOf(replacement), rendered) }
        compose.onAllNodes(androidx.compose.ui.test.hasText(replacement)).assertCountEquals(1)
    }

    @Test
    @Config(qualifiers = "w400dp-h1000dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun onlyTheActiveBottomPreviewPansAcrossItsTextAndHistoryStaysHalfOpaque() {
        compose.mainClock.autoAdvance = false
        val older = "Reviewed the changed source files"
        val current = "Checking the retained test results"
        var timeline by mutableStateOf(listOf(line("The source read completed.", true), line(older), line(current)))
        val colors = lightColorScheme()
        compose.setContent {
            MaterialTheme(colorScheme = colors) {
                Surface { Column(Modifier.width(320.dp)) { GitHubLiveProgressCard(timeline) } }
            }
        }
        compose.mainClock.advanceTimeBy(1600)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(older).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(colors.onSurface.copy(alpha = 0.5f), layouts.single().layoutInput.style.color)
        fun textImage(value: String) = compose.onNodeWithText(value).assertIsDisplayed().captureToImage().asAndroidBitmap()
        val olderBefore = textImage(older)
        val currentBefore = textImage(current)
        compose.mainClock.advanceTimeBy(400)
        assertTrue("Retained history must not pan or replay its entrance", olderBefore.sameAs(textImage(older)))
        assertFalse("The highlight must move across the text, not only the leading icon", currentBefore.sameAs(textImage(current)))

        compose.runOnIdle { timeline = timeline + line("The next verification is still running.", true) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        compose.onAllNodesWithTag("github_latest_preview").assertCountEquals(0)
        val settledPreview = textImage(current)
        compose.mainClock.advanceTimeBy(400)
        assertTrue("A newer summary ends the old preview's active highlight without reordering it", settledPreview.sameAs(textImage(current)))
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

    @Test fun replacementSummaryKeepsItsPositionDuringEntryAfterOlderRowsAreTrimmed() {
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
        assertEquals("A newly keyed summary fades without shifting its position", entering, settled, 0f)
        compose.onAllNodesWithTag("github_latest_preview").assertCountEquals(1)
    }

    @Test
    @Config(qualifiers = "w400dp-h1000dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun progressParagraphsAndExpandableActivityRenderInBothThemes() {
        val summary = "The saved results show that the repository read completed, so the next step can reuse it instead of repeating the request.\n\nI’m checking the remaining validation results before calling the work complete. The test run is still in progress."
        val activity = "Checking the retained source changes against the selected revision, including the recovery boundary, public progress presentation and the validation results that are still pending."
        var theme by mutableStateOf(ThemeMode.DARK)
        compose.setContent {
            GPTMobileTheme(themeMode = theme) {
                Surface {
                    Column(Modifier.width(360.dp).padding(16.dp).testTag("github_progress_render")) {
                        GitHubLoadingHeader("Checking changed sources", active = true, startedAtMillis = null)
                        GitHubLiveProgressCard(
                            listOf(
                                line(summary, true).copy(modelAuthored = true),
                                line("Reviewed repository access"),
                                line("Updated progress presentation"),
                                line(activity)
                            )
                        )
                    }
                }
            }
        }
        fun capture(name: String) {
            val bitmap = compose.onNodeWithTag("github_progress_render").captureToImage().asAndroidBitmap()
            assertTrue(bitmap.width > 300 && bitmap.height > 200)
            val file = File("build/reports/reliability-ui/$name.png").apply { parentFile.mkdirs() }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        compose.onNodeWithText(summary).assertIsDisplayed()
        compose.onNodeWithTag("github_preview_details").assertIsDisplayed()
        capture("github-progress-dark")
        compose.onNodeWithTag("github_preview_details").performClick()
        capture("github-progress-expanded-dark")
        compose.runOnIdle { theme = ThemeMode.LIGHT }
        capture("github-progress-expanded-light")
    }
}
