package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ChatBottomIndicatorTest {
    private val durationScale = object : MotionDurationScale {
        override var scaleFactor by mutableFloatStateOf(1f)
    }

    @get:Rule val compose = createComposeRule(effectContext = durationScale)
    private var following by mutableStateOf(true)
    private var generating by mutableStateOf(true)
    private var animate by mutableStateOf(false)
    private var smoothStreaming by mutableStateOf(false)
    private var viewportHeight by mutableStateOf(180.dp)
    private var followRequests = 0
    private lateinit var state: LazyListState
    private lateinit var scope: CoroutineScope

    @Test fun jumpArrowRespectsTheSavedResponseAnimationPreference() {
        assertEquals(1000, chatScrollArrowFadeMillis(true))
        assertEquals(0, chatScrollArrowFadeMillis(false))
    }

    @Test fun manualBottomArrivalAndButtonHaveTheSameIndicatorAndFollowBehavior() {
        // Exercise follow intent independently of the optional arrow entrance animation.
        animate = false
        setConversation()
        compose.onNodeWithTag("chat_bottom_generation").assertDoesNotExist()
        for (smooth in listOf(false, true)) {
            compose.runOnIdle { smoothStreaming = smooth }
            compose.onNodeWithTag("conversation").performTouchInput { swipeDown() }
            compose.waitForIdle()
            compose.runOnIdle {
                assertFalse("An upward history gesture must detach follow intent", following)
                assertTrue(state.canScrollForward)
                assertFalse(state.isAtGitHubBottom())
            }
            compose.onNodeWithTag("chat_jump_to_bottom").assertIsDisplayed()
            compose.onNodeWithTag("chat_bottom_generation").assertDoesNotExist()

            compose.onNodeWithTag("conversation").performTouchInput { swipeUp() }
            compose.onNodeWithTag("chat_jump_to_bottom").assertDoesNotExist()
            compose.onNodeWithTag("chat_bottom_generation").assertDoesNotExist()
            compose.onNodeWithTag("chat_anchor_dots").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(state.isAtGitHubBottom())
                assertTrue(following)
            }

            compose.onNodeWithTag("conversation").performTouchInput { swipeDown() }
            advanceIndicatorClock(1_100)
            val requestsBeforeClick = compose.runOnIdle { followRequests }
            compose.onNodeWithTag("chat_jump_to_bottom").assertIsDisplayed().performClick()
            compose.onNodeWithTag("chat_bottom_generation").assertDoesNotExist()
            compose.onNodeWithTag("chat_jump_to_bottom").assertDoesNotExist()
            compose.onNodeWithTag("chat_anchor_dots").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(state.isAtGitHubBottom())
                assertTrue(following)
                assertEquals(requestsBeforeClick + 1, followRequests)
            }
        }
    }

    @Test fun idleChatHasNoDotsAndViewportResizeCannotRestoreFollowIntent() {
        generating = false
        setConversation()
        compose.onNodeWithTag("chat_bottom_generation").assertDoesNotExist()
        compose.onNodeWithTag("chat_jump_to_bottom").assertDoesNotExist()
        compose.onNodeWithTag("conversation").performTouchInput { swipeDown() }
        compose.onNodeWithTag("chat_jump_to_bottom").assertIsDisplayed()
        compose.runOnIdle { viewportHeight = 340.dp }
        compose.runOnIdle {
            assertTrue(state.isAtGitHubBottom())
            assertFalse(following)
            generating = true
        }
        compose.onNodeWithTag("chat_jump_to_bottom").assertIsDisplayed()
        compose.onNodeWithTag("chat_bottom_generation").assertDoesNotExist()
        compose.onNodeWithTag("chat_jump_to_bottom").performClick()
        compose.onNodeWithTag("chat_bottom_generation").assertDoesNotExist()
        compose.runOnIdle { generating = false }
        compose.onNodeWithTag("chat_bottom_generation").assertDoesNotExist()
        compose.onNodeWithTag("chat_jump_to_bottom").assertDoesNotExist()
    }

    @Test fun followingReachesTheMeasuredBottomWithoutGeneratingDots() {
        following = false
        setConversation(initialItem = 0, autoScroll = false)
        compose.runOnIdle { assertFalse(state.isAtGitHubBottom()) }
        compose.onNodeWithTag("chat_jump_to_bottom").performClick()
        compose.runOnIdle { assertTrue(following) }
        compose.onNodeWithTag("chat_bottom_generation").assertDoesNotExist()
        compose.runOnIdle { scope.launch { state.scrollToItem(7) } }
        compose.onNodeWithTag("chat_bottom_generation").assertDoesNotExist()
        compose.onNodeWithTag("conversation").performTouchInput { swipeDown() }
        compose.onNodeWithTag("chat_jump_to_bottom").assertIsDisplayed()
        compose.onNodeWithTag("chat_bottom_generation").assertDoesNotExist()
    }

    @Test fun fastScrollArrowRemainsAvailableAfterReleaseThenFadesAway() {
        following = false
        generating = false
        val input = ChatScrollInput()
        compose.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                Box(Modifier.size(width = 240.dp, height = 400.dp).background(Color.White)) {
                    LazyColumn(Modifier.width(240.dp).height(140.dp), state = state) {
                        items(12) { Text("Message $it", Modifier.height(40.dp)) }
                    }
                    Box(Modifier.align(Alignment.BottomCenter).size(48.dp)) {
                        ChatBottomIndicator(state, following, generating, animate = false, scrollInput = input, onFollow = { following = true })
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(following)
            assertTrue(state.canScrollForward)
            assertFalse(state.isAtGitHubBottom())
        }
        compose.onNodeWithTag("chat_jump_to_bottom").assertDoesNotExist()

        compose.runOnIdle { input.recordFling(230f) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("The test list must have history below its initial viewport", state.canScrollForward)
            assertTrue("Fast user motion must pass the reading-speed gate", input.scrollingFasterThanReading)
            assertFalse(following)
        }
        compose.onNodeWithTag("chat_jump_to_bottom").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { input.stopScroll() }
        advanceIndicatorClock(4_500)
        compose.onNodeWithTag("chat_jump_to_bottom").assertIsDisplayed()
        compose.runOnIdle { input.recordFling(230f) }
        advanceIndicatorClock(650)
        compose.onNodeWithTag("chat_jump_to_bottom").assertIsDisplayed()
        compose.runOnIdle { input.stopScroll() }
        advanceIndicatorClock(4_500)
        compose.onNodeWithTag("chat_jump_to_bottom").assertIsDisplayed()
        advanceIndicatorClock(650)
        compose.onNodeWithTag("chat_jump_to_bottom").assertDoesNotExist()

        // Slow reading motion never resurrects the arrow after its timeout.
        compose.runOnIdle { input.recordFling(70f) }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_jump_to_bottom").assertDoesNotExist()
    }

    private fun advanceIndicatorClock(milliseconds: Long) {
        // Commit the visibility change and settle Android's layout before timing
        // the transition. Advancing the Compose clock alone does not draw a frame.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    private fun setConversation(initialItem: Int = 7, autoScroll: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                state = rememberLazyListState(initialFirstVisibleItemIndex = initialItem)
                scope = rememberCoroutineScope()
                val isUserDragging by state.interactionSource.collectIsDraggedAsState()
                val follow = {
                    followRequests++
                    following = true
                }
                val input = rememberChatScrollInput(state, 8f, onDetach = { following = false }, onFollow = follow)
                // ChatScreen overlays the indicator in a stable viewport. A
                // wrap-content Column couples its host size to AnimatedVisibility.
                Box(Modifier.size(width = 240.dp, height = 400.dp).background(Color.White)) {
                    LazyColumn(Modifier.width(240.dp).height(viewportHeight).then(input.modifier).testTag("conversation"), state = state) {
                        items(7) { Text("Message $it", Modifier.height(40.dp)) }
                        item(key = "chat-bottom-anchor") { Spacer(Modifier.size(1.dp)) }
                    }
                    ChatBottomAutoScroller(
                        state,
                        isEnabled = autoScroll && shouldAutoScrollToBottom(following, input.dragging || isUserDragging, state.isScrollInProgress, state.lastScrolledBackward),
                        animate = animate && smoothStreaming
                    )
                    Box(Modifier.align(Alignment.BottomCenter).size(48.dp)) {
                        ChatBottomIndicator(state, following, generating, animate, showAnchorDots = true, onFollow = follow)
                    }
                }
            }
        }
    }
}
