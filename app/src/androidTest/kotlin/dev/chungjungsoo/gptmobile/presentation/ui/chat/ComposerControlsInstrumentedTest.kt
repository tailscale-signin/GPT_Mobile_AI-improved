package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.common.GlassBackdropHost
import dev.chungjungsoo.gptmobile.presentation.common.glassBackdropSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposerControlsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun glassComposerKeepsSendStopAndLatestActionsIndependent() {
        var stopped = 0
        var sent = 0
        var latest = 0
        composeRule.setContent {
            MaterialTheme {
                GlassBackdropHost {
                    Box(Modifier.fillMaxSize()) {
                        // Like ChatScreen, keep both consumers outside their blur source.
                        Canvas(Modifier.fillMaxSize().glassBackdropSource()) {
                            for (x in 0..size.width.toInt() step 8) {
                                drawRect(if ((x / 8) % 2 == 0) Color.Red else Color.Blue, Offset(x.toFloat(), 0f), Size(8f, size.height))
                            }
                        }
                        Column {
                            ChatInputBox(
                                inputState = rememberTextFieldState("Follow up"),
                                isRunning = true,
                                onCancelButtonClick = { stopped++ },
                                onSendButtonClick = { sent++ }
                            )
                            ScrollToBottomButton(isGenerating = true) { latest++ }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("chat_input_glass").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_jump_to_bottom").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.onNodeWithTag("chat_send").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("chat_stop").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, stopped)
            assertEquals(1, sent)
            assertEquals(1, latest)
        }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val pixels = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            java.io.File(instrumentation.targetContext.filesDir, "chat-glass-controls.png").outputStream().use {
                check(pixels.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            pixels.recycle()
        }
    }

    @Test
    fun typingFollowUpKeepsStopAndSendIndependent() {
        var stopped = 0
        var sent = 0
        composeRule.setContent {
            MaterialTheme {
                ChatInputBox(
                    inputState = rememberTextFieldState("Follow up"),
                    isRunning = true,
                    onCancelButtonClick = { stopped++ },
                    onSendButtonClick = { sent++ }
                )
            }
        }
        composeRule.onNodeWithTag("chat_stop").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("chat_send").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(0, stopped)
            assertEquals(1, sent)
        }
        composeRule.onNodeWithTag("chat_stop").performClick()
        composeRule.runOnIdle {
            assertEquals(1, stopped)
            assertEquals(1, sent)
        }
    }
}
