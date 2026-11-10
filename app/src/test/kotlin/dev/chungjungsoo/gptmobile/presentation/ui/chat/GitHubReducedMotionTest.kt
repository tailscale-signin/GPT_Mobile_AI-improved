package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalTestApi::class)
class GitHubReducedMotionTest {
    private val durationScale = object : MotionDurationScale {
        override var scaleFactor by mutableFloatStateOf(0f)
    }

    @get:Rule val compose = createComposeRule(effectContext = durationScale)

    @Test fun disabledAnimationsShowActivityImmediatelyAndEnablingMotionDoesNotReplayItsEntrance() {
        compose.mainClock.autoAdvance = false
        lateinit var opacity: State<Float>
        lateinit var phase: State<Float>
        var activity by mutableStateOf("Checking changed sources")
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp)) {
                    opacity = rememberGitHubActivityEntry()
                    phase = rememberGitHubProgressMotion(true)
                    GitHubActivityPreview(activity, latest = true, motion = phase)
                }
            }
        }
        compose.mainClock.advanceTimeBy(16)
        val bounds = compose.onNodeWithTag("github_latest_preview").assertTextEquals(activity).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertEquals(1f, opacity.value, 0f)
            assertEquals(0.5f, phase.value, 0f)
        }
        compose.mainClock.advanceTimeBy(2400)
        compose.onNodeWithTag("github_latest_preview").assertTextEquals(activity)
        assertEquals(bounds, compose.onNodeWithTag("github_latest_preview").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            assertEquals(1f, opacity.value, 0f)
            assertEquals(0.5f, phase.value, 0f)
            activity = "Reviewing the saved results"
        }
        // Publish the snapshot write before advancing the paused clock at scale zero.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(16)
        compose.onNodeWithTag("github_latest_preview").assertTextEquals(activity).assertIsDisplayed()
        compose.runOnIdle { durationScale.scaleFactor = 1f }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { assertEquals(1f, opacity.value, 0f) }
        compose.onNodeWithTag("github_latest_preview").assertTextEquals(activity).assertIsDisplayed()
    }

    @Test fun disabledSystemAnimationsKeepTheActiveProgressVisibleAndStationary() {
        compose.mainClock.autoAdvance = false
        lateinit var phase: State<Float>
        compose.setContent {
            MaterialTheme {
                phase = rememberGitHubProgressMotion(true)
                GitHubLoadingHeader("Checking workflows", active = true, startedAtMillis = null)
            }
        }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { assertEquals(0.5f, phase.value, 0f) }
        compose.mainClock.advanceTimeBy(2400)
        compose.runOnIdle { assertEquals(0.5f, phase.value, 0f) }
        compose.onNodeWithTag("github_loading_bar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Checking workflows").assertExists()
        compose.runOnIdle { durationScale.scaleFactor = 1f }
        // runOnIdle synchronizes before its action. Publish this snapshot write
        // before advancing the clock: no animation is running at scale zero.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        val resumedPhase = compose.runOnIdle {
            phase.value.also { assertTrue("Enabled animations must leave the stationary phase", it != 0.5f) }
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertTrue("Resumed progress must advance between frames", phase.value != resumedPhase) }
        compose.runOnIdle { durationScale.scaleFactor = 0f }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { assertEquals(0.5f, phase.value, 0f) }
        compose.mainClock.advanceTimeBy(2400)
        compose.runOnIdle { assertEquals(0.5f, phase.value, 0f) }
    }
}
