package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.VoiceMicrophoneAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class VoiceMicrophoneButtonInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pressingTheIdleMicrophoneHighlightsAndPulsesWhileStopRemainsStatic() {
        val action = mutableStateOf(VoiceMicrophoneAction.LISTEN)
        var presses = 0
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(surfaceContainerHigh = Color.LightGray, primary = Color.Blue, onPrimary = Color.White)) {
                VoiceMicrophoneButton(
                    action.value,
                    onClick = {
                        presses++
                        if (action.value == VoiceMicrophoneAction.LISTEN) action.value = VoiceMicrophoneAction.FINISH_CAPTURE
                    },
                    modifier = Modifier.size(88.dp).testTag("microphone"),
                    iconSize = 44.dp,
                    motionEnabled = true
                )
            }
        }
        val button = compose.onNodeWithTag("microphone")
        fun background(): Int {
            val bitmap = button.captureToImage().asAndroidBitmap()
            return bitmap.getPixel(bitmap.width / 8, bitmap.height / 2)
        }
        button.assertContentDescriptionEquals("Start listening")
        val neutral = background()
        compose.mainClock.autoAdvance = false
        button.performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        // Finish the press ripple and one full pulse before comparing its two ends.
        compose.mainClock.advanceTimeBy(1800)
        button.assertContentDescriptionEquals("Finish speaking")
        val listening = background()
        compose.mainClock.advanceTimeBy(900)
        val faded = background()
        assertNotEquals(neutral, listening)
        assertNotEquals(neutral, faded)
        assertNotEquals(listening, faded)

        compose.runOnIdle { action.value = VoiceMicrophoneAction.STOP }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        button.assertContentDescriptionEquals("Stop")
        val stopped = background()
        compose.mainClock.advanceTimeBy(900)
        assertEquals(stopped, background())
        button.performClick()
        compose.runOnIdle { assertEquals(2, presses) }
    }

    @Test fun reducedMotionKeepsCaptureHighlightedAndReadinessDisablesTheIdleControl() {
        val action = mutableStateOf(VoiceMicrophoneAction.FINISH_CAPTURE)
        val enabled = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                VoiceMicrophoneButton(action.value, {}, Modifier.testTag("microphone"), enabled = enabled.value, compact = true, stopLabel = "Stop reply", motionEnabled = false)
            }
        }
        val button = compose.onNodeWithTag("microphone")
        fun background(): Int {
            val bitmap = button.captureToImage().asAndroidBitmap()
            return bitmap.getPixel(bitmap.width / 8, bitmap.height / 2)
        }
        compose.mainClock.autoAdvance = false
        button.assertContentDescriptionEquals("Finish speaking")
        val highlighted = background()
        compose.mainClock.advanceTimeBy(900)
        assertEquals(highlighted, background())
        compose.runOnIdle {
            action.value = VoiceMicrophoneAction.LISTEN
            enabled.value = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        button.assertContentDescriptionEquals("Start listening").assertIsNotEnabled()
        assertNotEquals(highlighted, background())
    }
}
