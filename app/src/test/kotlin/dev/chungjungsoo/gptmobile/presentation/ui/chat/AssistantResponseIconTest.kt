package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AssistantResponseIconTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ringAndGlyphStayCenteredInsideTheHistoryButtonDuringAnimationAndCompletion() {
        var status by mutableStateOf(AgentRunStatus.RUNNING)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                IconButton(onClick = {}, modifier = Modifier.testTag("history_button")) {
                    GPTMobileIcon(loading = shouldShowAssistantResponseProgress(true, true, status), github = true)
                }
            }
        }
        compose.mainClock.advanceTimeBy(32)
        val button = compose.onNodeWithTag("history_button").fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag("assistant_response_icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val ring = compose.onNodeWithTag("assistant_response_progress", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val glyph = compose.onNodeWithTag("assistant_response_glyph", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(button.center.x, icon.center.x, .1f)
        assertEquals(button.center.y, icon.center.y, .1f)
        assertEquals(icon.width, icon.height, .1f)
        assertEquals(ring.center.x, glyph.center.x, .1f)
        assertEquals(ring.center.y, glyph.center.y, .1f)
        compose.mainClock.advanceTimeBy(450)
        assertEquals(glyph, compose.onNodeWithTag("assistant_response_glyph", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { status = AgentRunStatus.PARTIAL }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("assistant_response_progress", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(icon, compose.onNodeWithTag("assistant_response_icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        assertEquals(glyph, compose.onNodeWithTag("assistant_response_glyph", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
    }
}
