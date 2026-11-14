package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChatScrollInputTest {
    @get:Rule val compose = createComposeRule()

    @Test fun manualFlingReattachesAndViewportChangesCannotAttachWithoutInput() {
        var following by mutableStateOf(true)
        var height by mutableStateOf(180.dp)
        lateinit var state: LazyListState
        compose.setContent {
            MaterialTheme {
                state = rememberLazyListState(initialFirstVisibleItemIndex = 7)
                val input = rememberChatScrollInput(state, 8f, onDetach = { following = false }, onFollow = { following = true })
                // The input owner is shared by every chat; no smooth-streaming
                // preference or animation is needed to express follow intent.
                LazyColumn(Modifier.width(240.dp).height(height).then(input.modifier).testTag("conversation"), state = state) {
                    items(7) { Text("Message $it", Modifier.height(40.dp)) }
                    item(key = "chat-bottom-anchor") { Spacer(Modifier.size(1.dp)) }
                }
            }
        }
        compose.runOnIdle { assertTrue(state.isAtGitHubBottom()) }
        compose.onNodeWithTag("conversation").performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertFalse(following)
            height = 600.dp
        }
        compose.runOnIdle {
            assertTrue(state.isAtGitHubBottom())
            assertFalse(following)
            height = 180.dp
        }
        compose.onNodeWithTag("conversation").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue(state.isAtGitHubBottom())
            assertTrue(following)
        }
    }
}
