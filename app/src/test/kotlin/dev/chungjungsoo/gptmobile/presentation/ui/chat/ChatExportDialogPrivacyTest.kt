package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class ChatExportDialogPrivacyTest {
    // The v1 idle loop expects its default unconfined dispatcher for scroll actions.
    @get:Rule val compose = createComposeRule()

    @Test fun forensicExportRequiresItsOwnPreviewSelectionWhileDefaultExportStaysRedacted() {
        var forensicCalls = 0
        var normalCalls = 0
        compose.setContent {
            MaterialTheme {
                ChatExportDialog(
                    onDismiss = {},
                    debugMode = true,
                    forensicPreview = { "Synthetic local sample" },
                    onForensicDebugExport = { forensicCalls++ },
                    onExport = { if (it == ChatExportFormat.DEBUG) normalCalls++ }
                )
            }
        }
        val normalScrolls = clickVisibleExportOption("Redacted diagnostic summary")
        compose.runOnIdle {
            assertEquals(1, normalCalls)
            assertEquals(0, forensicCalls)
        }
        val forensicScrolls = clickVisibleExportOption("Full forensic export")
        assertTrue("The phone-sized fixture must exercise scrolling", normalScrolls + forensicScrolls > 0)
        compose.onNodeWithTag("forensic_export_preview").assertTextContains("Synthetic local sample")
        compose.runOnIdle { assertEquals(0, forensicCalls) }
        compose.onNodeWithTag("confirm_forensic_export").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, forensicCalls) }
    }

    @Test fun backingOutOfForensicPreviewDoesNotInvokeExportOrResume() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                ChatExportDialog(
                    onDismiss = {},
                    debugMode = true,
                    onResumeForensicExport = { calls++ },
                    onExport = { calls++ }
                )
            }
        }
        clickVisibleExportOption("Resume full forensic capture")
        compose.onNodeWithText("Back", substring = false).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("Resume full forensic capture", substring = true).assertExists()
    }

    // A refused or non-progressing scroll must fail here instead of looping in performScrollTo.
    private fun clickVisibleExportOption(label: String): Int {
        val target = compose.onNodeWithText(label, substring = true)
        val scrollable = compose.onNode(hasScrollAction())
        for (step in 0..8) {
            val scrollNode = scrollable.fetchSemanticsNode()
            val viewport = scrollNode.boundsInRoot
            val targetNode = target.fetchSemanticsNode()
            val position = targetNode.positionInRoot
            val bounds = Rect(position.x, position.y, position.x + targetNode.size.width, position.y + targetNode.size.height)
            val fullyVisible = bounds.width > 0f &&
                bounds.height > 0f &&
                bounds.left >= viewport.left &&
                bounds.right <= viewport.right &&
                bounds.top >= viewport.top &&
                bounds.bottom <= viewport.bottom
            if (fullyVisible && target.isDisplayed()) {
                target.assertIsDisplayed().performClick()
                return step
            }
            assertTrue("Export option was not fully reachable after 8 scrolls: $label", step < 8)
            val range = scrollNode.config[SemanticsProperties.VerticalScrollAxisRange]
            val before = compose.runOnIdle { range.value() }
            // Avoid stepping past the point where this entire option fits in the viewport.
            val distance = minOf(viewport.height * 0.6f, bounds.bottom - viewport.bottom)
            assertTrue("Export option must be reachable below a visible viewport: $label", distance.isFinite() && distance > 0f)
            scrollable.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
                assertTrue("Export menu rejected scrolling toward $label", scrollBy(0f, distance))
            }
            compose.waitForIdle()
            val updatedRange = scrollable.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val after = compose.runOnIdle { updatedRange.value() }
            assertTrue("Export menu made no progress toward $label: $before -> $after", after > before)
        }
        error("Unreachable bounded-scroll state")
    }
}
