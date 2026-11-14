package dev.chungjungsoo.gptmobile.presentation.ui.chat

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class GitHubLineAutoScrollerTest {
    private val durationScale = object : MotionDurationScale {
        override val scaleFactor = 1f
    }

    @get:Rule val compose = createComposeRule(effectContext = durationScale)

    @Test fun paragraphInsertedBelowTheViewportIsMeasuredAndGetsItsCompleteReadingBudget() {
        var added by mutableStateOf(false)
        lateinit var state: LazyListState
        lateinit var lines: GitHubRenderedLines
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                lines = remember { GitHubRenderedLines() }
                GitHubLineAutoScroller(state, lines, enabled = true, revision = 0, animate = true)
                LazyColumn(Modifier.width(240.dp).height(160.dp), state = state) {
                    item {
                        GitHubLineScope(lines, "turn") {
                            Column {
                                Spacer(Modifier.height(300.dp))
                                Text("Existing text", onTextLayout = rememberGitHubTextLayout("existing"))
                                if (added) Text("First new line\nSecond new line\nThird new line", onTextLayout = rememberGitHubTextLayout("new"))
                            }
                        }
                    }
                    item(key = "chat-bottom-anchor") { Spacer(Modifier.size(1.dp)) }
                }
            }
        }
        compose.mainClock.advanceTimeUntil(2000) { lines.ledger.armed }
        compose.runOnIdle { assertTrue(state.isAtGitHubBottom()) }
        compose.runOnIdle { added = true }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeUntil(250) { !lines.queue.isEmpty }
        compose.runOnIdle {
            assertEquals(4500L, lines.queue.remainingMillis)
            assertFalse(state.isAtGitHubBottom())
        }
        compose.mainClock.advanceTimeBy(3000)
        compose.runOnIdle { assertFalse(state.isAtGitHubBottom()) }
        compose.mainClock.advanceTimeBy(1600)
        compose.runOnIdle {
            assertTrue(lines.queue.isEmpty)
            assertTrue(state.isAtGitHubBottom())
        }
    }

    @Test fun aReattachedDocumentWhoseOldLayoutArrivesLateDoesNotReplayItsLines() {
        var visible by mutableStateOf(false)
        lateinit var lines: GitHubRenderedLines
        val retained = "Saved first line\nSaved second line\nSaved third line"
        compose.setContent {
            MaterialTheme {
                lines = remember { GitHubRenderedLines() }
                GitHubLineScope(lines, "turn") {
                    GitHubLineDocument("answer", retained) {
                        if (visible) Text(retained, onTextLayout = rememberGitHubTextLayout("paragraph"))
                    }
                }
            }
        }
        compose.runOnIdle {
            lines.baseline()
            lines.ledger.arm()
            visible = true
        }
        compose.runOnIdle { assertEquals(0, lines.ledger.takeLines()) }
    }
}
