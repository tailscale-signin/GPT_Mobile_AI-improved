package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StableChatResponseViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingTabsRetainsScrollSpaceButResizingDiscardsObsoleteMeasurements() {
        var selected by mutableStateOf("long")
        var width by mutableStateOf(240.dp)
        compose.setContent {
            Box(Modifier.width(width).testTag("viewport")) {
                StableChatResponseViewport(selected) {
                    Box(Modifier.height(if (selected == "long") 300.dp else 100.dp))
                }
            }
        }
        compose.onNodeWithTag("viewport").assertHeightIsEqualTo(300.dp)
        compose.runOnIdle { selected = "short" }
        compose.onNodeWithTag("viewport").assertHeightIsEqualTo(300.dp)
        compose.runOnIdle { width = 180.dp }
        compose.onNodeWithTag("viewport").assertHeightIsEqualTo(100.dp)
    }
}
