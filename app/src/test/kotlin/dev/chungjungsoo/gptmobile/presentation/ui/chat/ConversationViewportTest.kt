package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun detailPaneUsesRemainingWidthAndTracksWindowAndKeyboardResize() {
        var windowWidth by mutableStateOf(900.dp)
        var availableHeight by mutableStateOf(600.dp)
        var measuredWidth: Dp = 0.dp
        var measuredHeight: Dp = 0.dp
        compose.setContent {
            Row(Modifier.requiredSize(windowWidth, availableHeight)) {
                Box(Modifier.width(300.dp))
                Box(Modifier.weight(1f)) {
                    ConversationViewport { width, height ->
                        measuredWidth = width
                        measuredHeight = height
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(600.dp, measuredWidth)
            assertEquals(600.dp, measuredHeight)
            windowWidth = 660.dp
            availableHeight = 240.dp
        }
        compose.runOnIdle {
            assertEquals(360.dp, measuredWidth)
            assertEquals(240.dp, measuredHeight)
        }
    }
}
