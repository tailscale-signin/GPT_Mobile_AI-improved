package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
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

    @Test fun manualBottomArrivalAndButtonHaveTheSameIndicatorAndFollowBehavior() {
        animate = true
        setConversation()
        compose.onNodeWithTag("chat_bottom_generation").assertIsDisplayed()
        for (smooth in listOf(false, true)) {
            compose.runOnIdle { smoothStreaming = smooth }
            compose.onNodeWithTag("conversation").performTouchInput { swipeDown() }
            compose.onNodeWithTag("chat_jump_to_bottom").assertIsDisplayed()
            compose.onNodeWithTag("chat_bottom_generation").assertDoesNotExist()
            compose.runOnIdle { assertFalse(following) }

            compose.onNodeWithTag("conversation").performTouchInput { swipeUp() }
            compose.onNodeWithTag("chat_jump_to_bottom").assertDoesNotExist()
            compose.onNodeWithTag("chat_bottom_generation").assertIsDisplayed()
            compose.runOnIdle {
                assertTrue(state.isAtGitHubBottom())
                assertTrue(following)
            }

            compose.onNodeWithTag("conversation").performTouchInput { swipeDown() }
            val requestsBeforeClick = compose.runOnIdle { followRequests }
            compose.onNodeWithTag("chat_jump_to_bottom").performClick()
            compose.onNodeWithTag("chat_bottom_generation").assertIsDisplayed()
            compose.onNodeWithTag("chat_jump_to_bottom").assertDoesNotExist()
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
        compose.onNodeWithTag("chat_bottom_generation").assertIsDisplayed()
        compose.runOnIdle { generating = false }
        compose.onNodeWithTag("chat_bottom_generation").assertDoesNotExist()
        compose.onNodeWithTag("chat_jump_to_bottom").assertDoesNotExist()
    }

    @Test fun followRequestShowsDotsOnlyAfterTheMeasuredBottomIsReached() {
        following = false
        setConversation(initialItem = 0, autoScroll = false)
        compose.runOnIdle { assertFalse(state.isAtGitHubBottom()) }
        compose.onNodeWithTag("chat_jump_to_bottom").performClick()
        compose.runOnIdle { assertTrue(following) }
        compose.onNodeWithTag("chat_bottom_generation").assertDoesNotExist()
        compose.runOnIdle { scope.launch { state.scrollToItem(7) } }
        compose.onNodeWithTag("chat_bottom_generation").assertIsDisplayed()
        compose.onNodeWithTag("conversation").performTouchInput { swipeDown() }
        compose.onNodeWithTag("chat_jump_to_bottom").assertIsDisplayed()
        compose.onNodeWithTag("chat_bottom_generation").assertDoesNotExist()
    }

    @Test fun disablingAppOrSystemMotionKeepsVisibleStationaryDots() {
        setConversation()
        compose.onNodeWithTag("chat_bottom_generation").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        for ((appAnimations, systemScale) in listOf(false to 1f, true to 0f)) {
            compose.runOnIdle {
                animate = appAnimations
                durationScale.scaleFactor = systemScale
            }
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
            val before = dotsBitmap()
            val pixels = IntArray(before.width * before.height)
            before.getPixels(pixels, 0, before.width, 0, 0, before.width, before.height)
            assertTrue("Reduced motion must keep the generation dots visible", pixels.any { it != android.graphics.Color.WHITE })
            compose.mainClock.advanceTimeBy(2400)
            assertTrue("Reduced motion must keep the dots stationary", before.sameAs(dotsBitmap()))
        }
    }

    private fun dotsBitmap(): Bitmap = requireNotNull(
        compose.onNodeWithTag("chat_bottom_generation").assertIsDisplayed().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
    )

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
                Column(Modifier.background(Color.White)) {
                    LazyColumn(Modifier.width(240.dp).height(viewportHeight).then(input.modifier).testTag("conversation"), state = state) {
                        items(7) { Text("Message $it", Modifier.height(40.dp)) }
                        item(key = "chat-bottom-anchor") { Spacer(Modifier.size(1.dp)) }
                    }
                    ChatBottomAutoScroller(
                        state,
                        isEnabled = autoScroll && shouldAutoScrollToBottom(following, input.dragging || isUserDragging, state.isScrollInProgress, state.lastScrolledBackward),
                        animate = animate && smoothStreaming
                    )
                    ChatBottomIndicator(state, following, generating, animate, onFollow = follow)
                }
            }
        }
    }
}
