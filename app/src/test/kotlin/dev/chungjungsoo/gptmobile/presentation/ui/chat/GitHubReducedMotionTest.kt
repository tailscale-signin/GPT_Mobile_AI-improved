package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
