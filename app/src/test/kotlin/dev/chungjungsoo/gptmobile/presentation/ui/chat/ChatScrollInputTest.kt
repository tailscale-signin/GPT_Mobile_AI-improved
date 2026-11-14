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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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

    @Test fun deliberateDownwardInputAtTheBottomReattachesEvenWhenNoPixelsAreConsumed() {
        var following by mutableStateOf(false)
        var followCount = 0
        lateinit var state: LazyListState
        lateinit var dispatcher: NestedScrollDispatcher
        compose.setContent {
            MaterialTheme {
                state = rememberLazyListState(initialFirstVisibleItemIndex = 7)
                dispatcher = remember { NestedScrollDispatcher() }
                val childConnection = remember { object : NestedScrollConnection {} }
                val input = rememberChatScrollInput(state, 8f, onDetach = { following = false }, onFollow = {
                    following = true
                    followCount++
                })
                LazyColumn(
                    Modifier.width(240.dp).height(180.dp).then(input.modifier).nestedScroll(childConnection, dispatcher),
                    state = state
                ) {
                    items(7) { Text("Message $it", Modifier.height(40.dp)) }
                    item(key = "chat-bottom-anchor") { Spacer(Modifier.size(1.dp)) }
                }
            }
        }
        compose.runOnIdle {
            assertTrue(state.isAtGitHubBottom())
            assertFalse(following)
            // A wheel or continued drag at the edge leaves the whole delta
            // unconsumed. It still expresses the same return intent as the arrow.
            val downward = Offset(0f, -24f)
            dispatcher.dispatchPreScroll(downward, NestedScrollSource.UserInput)
            dispatcher.dispatchPostScroll(Offset.Zero, downward, NestedScrollSource.UserInput)
            assertTrue(following)
            assertEquals(1, followCount)
            dispatcher.dispatchPostScroll(Offset.Zero, downward, NestedScrollSource.SideEffect)
            assertEquals(1, followCount)
        }
    }

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
