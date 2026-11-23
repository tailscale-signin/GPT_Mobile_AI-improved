package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalTestApi::class)
class ChatExportDialogPrivacyTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())

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
        compose.onNodeWithText("Redacted diagnostic summary", substring = true).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, normalCalls)
            assertEquals(0, forensicCalls)
        }
        compose.onNodeWithText("Full forensic export", substring = true).performScrollTo().performClick()
        compose.onNodeWithTag("forensic_export_preview").assertTextContains("Synthetic local sample")
        compose.runOnIdle { assertEquals(0, forensicCalls) }
        compose.onNodeWithTag("confirm_forensic_export").performClick()
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
        compose.onNodeWithText("Resume full forensic capture", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Back", substring = false).performClick()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("Resume full forensic capture", substring = true).assertExists()
    }
}
