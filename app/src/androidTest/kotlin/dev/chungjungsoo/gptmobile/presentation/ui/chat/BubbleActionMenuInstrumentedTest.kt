package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BubbleActionMenuInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortHoldDoesNotOpenMenuAndOneSecondHoldPreservesAction() {
        var edited = 0
        compose.setContent {
            MaterialTheme {
                BubbleActionHost("stable-message", listOf(BubbleAction("Edit response", Icons.Rounded.Edit) { edited++ })) {
                    Box(Modifier.size(240.dp, 120.dp).testTag("response")) { Text("Response") }
                }
            }
        }
        compose.onNodeWithTag("response").performTouchInput {
            down(center)
            advanceEventTime(700)
            up()
        }
        compose.onNodeWithText("Edit response").assertDoesNotExist()
        compose.onNodeWithTag("response").performTouchInput {
            down(center)
            advanceEventTime(1100)
            up()
        }
        compose.onNodeWithText("Edit response").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, edited) }
        compose.onNodeWithText("Edit response").assertDoesNotExist()
    }

    @Test fun movingToScrollCancelsTheHold() {
        compose.setContent {
            MaterialTheme {
                BubbleActionHost("message", listOf(BubbleAction("Edit response", Icons.Rounded.Edit) {})) {
                    Box(Modifier.size(240.dp, 120.dp).testTag("response")) { Text("Response") }
                }
            }
        }
        compose.onNodeWithTag("response").performTouchInput {
            down(center)
            moveTo(topCenter)
            advanceEventTime(1100)
            up()
        }
        compose.onNodeWithText("Edit response").assertDoesNotExist()
    }
}
