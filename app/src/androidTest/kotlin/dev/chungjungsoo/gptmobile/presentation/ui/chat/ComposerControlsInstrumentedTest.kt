package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposerControlsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

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
