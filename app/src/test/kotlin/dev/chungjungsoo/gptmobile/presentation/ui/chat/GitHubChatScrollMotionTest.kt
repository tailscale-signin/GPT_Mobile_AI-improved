package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GitHubChatScrollMotionTest {
    @get:Rule val compose = createComposeRule()

    private fun advanceFrames(millis: Long) {
        val until = compose.mainClock.currentTime + millis
        while (compose.mainClock.currentTime < until) {
            compose.mainClock.advanceTimeByFrame()
            // Lazy-list target distances depend on Android measurement as well
            // as Compose's animation clock; render each measured frame on the host.
            compose.waitForIdle()
        }
    }

    private fun position(state: LazyListState) =
        "item=${state.firstVisibleItemIndex}, offset=${state.firstVisibleItemScrollOffset}, forward=${state.canScrollForward}, scrolling=${state.isScrollInProgress}, clock=${compose.mainClock.currentTime}"

    @Test fun changedIntentStopsAnActivePromptAnimationAndOnlyANewRequestReanchors() {
        lateinit var state: LazyListState
        var request by mutableIntStateOf(0)
        var attached by mutableStateOf(true)
        compose.setContent {
            state = rememberLazyListState()
            Box(Modifier.size(320.dp, 300.dp)) {
                LazyColumn(state = state) {
                    items(8) { Spacer(Modifier.height(100.dp)) }
                    item { Spacer(Modifier.height(300.dp)) }
                }
            }
            LaunchedEffect(request) {
                if (request > 0) state.animateToGitHubPrompt(8, shouldAnchor = { attached })
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { request++ }
        advanceFrames(32)
        advanceFrames(500)
        compose.runOnIdle {
            assertTrue("Expected intermediate prompt motion: ${position(state)}", state.firstVisibleItemIndex in 1..7)
            attached = false
        }
        advanceFrames(32)
        val stopped = compose.runOnIdle { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
        advanceFrames(1700)
        compose.runOnIdle { assertEquals(stopped, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset) }
        compose.runOnIdle { attached = true }
        advanceFrames(1700)
        compose.runOnIdle { assertEquals(stopped, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset) }
        compose.runOnIdle { request++ }
        advanceFrames(1700)
        compose.runOnIdle {
            assertEquals(8, state.firstVisibleItemIndex)
            assertTrue(state.firstVisibleItemScrollOffset <= 2)
        }
    }

    @Test fun previewGrowthUsesTheFullSmoothFollowDuration() {
        lateinit var state: LazyListState
        var height by mutableStateOf(600.dp)
        compose.setContent {
            state = rememberLazyListState()
            Box(Modifier.size(320.dp, 300.dp)) {
                LazyColumn(state = state) {
                    item { Spacer(Modifier.height(height)) }
                    item { Spacer(Modifier.height(1.dp)) }
                }
                ChatBottomAutoScroller(state, isEnabled = true, githubMotion = true)
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val initial = compose.runOnIdle { state.firstVisibleItemScrollOffset }
        compose.runOnIdle { height += 200.dp }
        advanceFrames(300)
        compose.runOnIdle {
            assertTrue(state.firstVisibleItemScrollOffset > initial)
            assertTrue("Follow should still be moving before 1500ms", state.canScrollForward)
        }
        advanceFrames(1400)
        compose.runOnIdle { assertFalse("Follow should reach the measured footer", state.canScrollForward) }
    }

    @Test fun growingGitHubFooterFollowsOnceAndCancellingFollowKeepsTheReadingPosition() {
        lateinit var state: LazyListState
        var height by mutableStateOf(600.dp)
        var following by mutableStateOf(true)
        compose.setContent {
            state = rememberLazyListState()
            Box(Modifier.size(320.dp, 300.dp)) {
                LazyColumn(state = state, contentPadding = PaddingValues(top = 20.dp, bottom = 60.dp)) {
                    item(key = "response") { Spacer(Modifier.height(height)) }
                    item(key = "chat-bottom-anchor") { Spacer(Modifier.height(1.dp)) }
                }
                ChatBottomAutoScroller(state, isEnabled = following, githubMotion = true)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("Expected the true bottom: ${position(state)}", state.canScrollForward) }
        compose.mainClock.autoAdvance = false
        var previousOffset = compose.runOnIdle { state.firstVisibleItemScrollOffset }
        repeat(12) {
            compose.runOnIdle { height += 3.dp }
            advanceFrames(32)
            compose.runOnIdle {
                assertEquals(0, state.firstVisibleItemIndex)
                assertTrue("Growth must not reverse from $previousOffset: ${position(state)}", state.firstVisibleItemScrollOffset >= previousOffset)
                previousOffset = state.firstVisibleItemScrollOffset
            }
        }
        advanceFrames(1700)
        compose.runOnIdle { assertFalse("Expected the true bottom: ${position(state)}", state.canScrollForward) }
        compose.runOnIdle { following = false }
        advanceFrames(32)
        val stopped = compose.runOnIdle { state.firstVisibleItemScrollOffset }
        // Deliver late layout growth after the disabled follower has left composition.
        compose.runOnIdle { height += 40.dp }
        advanceFrames(1700)
        compose.runOnIdle {
            assertEquals("Disabled follow moved: ${position(state)}", stopped, state.firstVisibleItemScrollOffset)
            assertTrue("Late growth must remain below the reading position: ${position(state)}", state.canScrollForward)
            following = true
        }
        advanceFrames(1700)
        compose.runOnIdle { assertFalse("Expected the true bottom: ${position(state)}", state.canScrollForward) }
    }

    @Test fun explicitFollowWaitsForBackwardScrollingAndDoesNotCancelItsOwnFirstFrame() {
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        lateinit var gesture: Job
        var following by mutableStateOf(true)
        compose.setContent {
            state = rememberLazyListState()
            scope = rememberCoroutineScope()
            val dragging by state.interactionSource.collectIsDraggedAsState()
            Box(Modifier.size(320.dp, 300.dp)) {
                LazyColumn(state = state, contentPadding = PaddingValues(top = 20.dp, bottom = 60.dp)) {
                    item { Spacer(Modifier.height(600.dp)) }
                    item(key = "chat-bottom-anchor") { Spacer(Modifier.height(1.dp)) }
                }
                ChatBottomAutoScroller(
                    state,
                    isEnabled = shouldAutoScrollToBottom(
                        isFollowing = following,
                        isUserDragging = dragging,
                        isScrollInProgress = state.isScrollInProgress,
                        isScrollingAway = state.lastScrolledBackward,
                        followIntentIsExplicit = true
                    ),
                    githubMotion = true
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(state.canScrollForward)
            following = false
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            gesture = scope.launch {
                state.scroll(MutatePriority.UserInput) {
                    scrollBy(-20f)
                    // A fling still owns its mutation after the drag interaction ends.
                    awaitCancellation()
                }
            }
        }
        advanceFrames(32)
        var historyOffset = 0
        compose.runOnIdle {
            assertTrue(state.isScrollInProgress)
            assertTrue(state.lastScrolledBackward)
            assertTrue(state.canScrollForward)
            historyOffset = state.firstVisibleItemScrollOffset
            following = true
        }
        advanceFrames(64)
        compose.runOnIdle {
            assertEquals(historyOffset, state.firstVisibleItemScrollOffset)
            gesture.cancel()
        }
        advanceFrames(1700)
        compose.runOnIdle {
            assertFalse(state.canScrollForward)
            assertTrue(state.firstVisibleItemScrollOffset > historyOffset)
        }
    }

    @Test fun viewportChangesKeepTheGitHubFooterAtTheBottomOnlyWhileFollowing() = assertViewportChangesRespectFollowIntent(githubMotion = true)

    @Test fun viewportChangesKeepTheOrdinaryChatFooterAtTheBottomOnlyWhileFollowing() = assertViewportChangesRespectFollowIntent(githubMotion = false)

    private fun assertViewportChangesRespectFollowIntent(githubMotion: Boolean) {
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        var bottomPadding by mutableStateOf(60.dp)
        var viewportHeight by mutableStateOf(300.dp)
        var following by mutableStateOf(true)
        compose.setContent {
            state = rememberLazyListState()
            scope = rememberCoroutineScope()
            Box(Modifier.size(320.dp, viewportHeight)) {
                LazyColumn(state = state, contentPadding = PaddingValues(top = 20.dp, bottom = bottomPadding)) {
                    item { Spacer(Modifier.height(600.dp)) }
                    item(key = "chat-bottom-anchor") { Spacer(Modifier.height(1.dp)) }
                }
                ChatBottomAutoScroller(state, isEnabled = following, githubMotion = githubMotion)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(state.canScrollForward)
            assertEquals("chat-bottom-anchor", state.layoutInfo.visibleItemsInfo.last().key)
            // Item count and row sizes stay fixed, and the footer remains visible.
            bottomPadding = 100.dp
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(state.canScrollForward)
            assertEquals("chat-bottom-anchor", state.layoutInfo.visibleItemsInfo.last().key)
            // Keyboard/viewport changes need the same follow behavior without row growth.
            viewportHeight = 240.dp
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(state.canScrollForward)
            following = false
        }
        compose.waitForIdle()
        compose.runOnIdle { scope.launch { state.scrollToItem(0, 80) } }
        compose.waitForIdle()
        val readingPosition = compose.runOnIdle { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
        compose.runOnIdle {
            bottomPadding = 140.dp
            viewportHeight = 200.dp
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Viewport changes must preserve a detached reading position", readingPosition, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
            assertTrue(state.canScrollForward)
        }
    }
}
