package dev.chungjungsoo.gptmobile.presentation.ui.chat

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
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
}
