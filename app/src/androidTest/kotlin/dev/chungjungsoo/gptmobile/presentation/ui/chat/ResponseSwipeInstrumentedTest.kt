package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ResponseSwipeInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun horizontalSwipeSwitchesProfilesAndVerticalScrollDoesNot() = verify(false)

    @Test fun rtlSwipeUsesReadingDirectionAndDoesNotWrap() = verify(true)
    private fun verify(rtl: Boolean) {
        var index by mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Box(Modifier.size(280.dp, 200.dp).testTag("response").responseProfileSwipe(3, index) { index = it }) { Text("Response $index") }
                }
            }
        }
        compose.onNodeWithTag("response").performTouchInput { swipeUp() }
        compose.runOnIdle { assertEquals(0, index) }
        compose.onNodeWithTag("response").performTouchInput { if (rtl) swipeRight() else swipeLeft() }
        compose.runOnIdle { assertEquals(1, index) }
        compose.onNodeWithTag("response").performTouchInput { if (rtl) swipeLeft() else swipeRight() }
        compose.runOnIdle { assertEquals(0, index) }
        compose.onNodeWithTag("response").performTouchInput { if (rtl) swipeLeft() else swipeRight() }
        compose.runOnIdle { assertEquals(0, index) }
    }
}
