package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GitHubPromptMotionInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun promptMovesOverOneAndHalfSecondsWithoutAnImmediateJump() {
        lateinit var state: LazyListState
        var submitted by mutableStateOf(false)
        composeRule.setContent {
            state = rememberLazyListState()
            Box(Modifier.size(320.dp, 300.dp)) {
                LazyColumn(state = state) {
                    items(8) { Spacer(Modifier.height(100.dp)) }
                    item { Spacer(Modifier.height(300.dp)) }
                }
            }
            LaunchedEffect(submitted) { if (submitted) state.animateToGitHubPrompt(8) }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { submitted = true }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.runOnIdle { assertTrue(state.firstVisibleItemIndex < 8) }
        composeRule.mainClock.advanceTimeBy(700)
        composeRule.runOnIdle { assertTrue(state.firstVisibleItemIndex in 1..7) }
        composeRule.mainClock.advanceTimeBy(850)
        composeRule.runOnIdle {
            assertEquals(8, state.firstVisibleItemIndex)
            assertTrue(state.firstVisibleItemScrollOffset <= 2)
        }
    }

    @Test fun reattachmentWaitsForTheUserScrollAndRechecksAnchorIntent() {
        lateinit var state: LazyListState
        lateinit var scope: CoroutineScope
        lateinit var gesture: Job
        var submitted by mutableStateOf(0)
        var attached by mutableStateOf(true)
        composeRule.setContent {
            state = rememberLazyListState()
            scope = rememberCoroutineScope()
            Box(Modifier.size(320.dp, 300.dp)) {
                LazyColumn(state = state) {
                    items(8) { Spacer(Modifier.height(100.dp)) }
                    item { Spacer(Modifier.height(300.dp)) }
                }
            }
            LaunchedEffect(submitted) {
                if (submitted > 0) state.animateToGitHubPrompt(8, shouldAnchor = { attached })
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle {
            gesture = scope.launch { state.scroll(MutatePriority.UserInput) { awaitCancellation() } }
        }
        composeRule.waitUntil(5_000) { state.isScrollInProgress }
        composeRule.runOnIdle { submitted++ }
        composeRule.mainClock.advanceTimeBy(1600)
        composeRule.runOnIdle { assertEquals(0, state.firstVisibleItemIndex) }
        composeRule.runOnIdle { gesture.cancel() }
        composeRule.mainClock.advanceTimeBy(1700)
        composeRule.runOnIdle { assertEquals(8, state.firstVisibleItemIndex) }

        // A bottom-button or history-detach decision made while waiting wins.
        composeRule.runOnIdle {
            gesture = scope.launch {
                state.scrollToItem(0)
                state.scroll(MutatePriority.UserInput) { awaitCancellation() }
            }
        }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitUntil(5_000) { state.firstVisibleItemIndex == 0 && state.isScrollInProgress }
        composeRule.runOnIdle { submitted++ }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.runOnIdle {
            attached = false
            gesture.cancel()
        }
        composeRule.mainClock.advanceTimeBy(1700)
        composeRule.runOnIdle { assertEquals(0, state.firstVisibleItemIndex) }
    }
}
