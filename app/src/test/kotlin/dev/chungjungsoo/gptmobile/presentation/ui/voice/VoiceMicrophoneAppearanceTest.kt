package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.chungjungsoo.gptmobile.data.voice.VoiceMicrophoneAction
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import dev.chungjungsoo.gptmobile.data.voice.microphoneAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMicrophoneAppearanceTest {
    private val neutral = Color(0xffe5e1e6)
    private val highlight = Color(0xff6750a4)

    @Test fun idleAndUnavailableCaptureStatesKeepTheNeutralMicrophone() {
        for (phase in listOf(VoicePhase.PAUSED, VoicePhase.REVIEWING, VoicePhase.ERROR, VoicePhase.CLOSED)) {
            val action = VoiceState(phase = phase).microphoneAction
            assertEquals(VoiceMicrophoneAction.LISTEN, action)
            assertEquals(neutral, voiceMicrophoneContainerColor(action, neutral, highlight, 0f))
            assertEquals(neutral, voiceMicrophoneContainerColor(action, neutral, highlight, 1f))
        }
    }

    @Test fun startingAndListeningHighlightImmediatelyAndFadeWithoutLookingIdle() {
        for (phase in listOf(VoicePhase.STARTING, VoicePhase.LISTENING)) {
            val action = VoiceState(phase = phase).microphoneAction
            assertEquals(VoiceMicrophoneAction.FINISH_CAPTURE, action)
            assertEquals(highlight, voiceMicrophoneContainerColor(action, neutral, highlight, 1f))
            val quiet = voiceMicrophoneContainerColor(action, neutral, highlight, 0f)
            assertNotEquals(highlight, quiet)
            assertNotEquals(neutral, quiet)
            assertTrue(quiet.luminance() > highlight.luminance())
            assertTrue(quiet.luminance() < neutral.luminance())
        }
    }

    @Test fun queuedGeneratingAndPlayingRepliesKeepTheStopHighlightStatic() {
        val states = listOf(
            VoiceState(awaitingReply = true),
            VoiceState(submittedDraftRequestId = "queued"),
            VoiceState(phase = VoicePhase.LISTENING, awaitingReply = true)
        ) + listOf(VoicePhase.FINALIZING, VoicePhase.THINKING, VoicePhase.PREPARING, VoicePhase.SPEAKING).map { VoiceState(phase = it) }
        for (state in states) {
            assertEquals(VoiceMicrophoneAction.STOP, state.microphoneAction)
            for (pulse in listOf(0f, .5f, 1f)) assertEquals(highlight, voiceMicrophoneContainerColor(state.microphoneAction, neutral, highlight, pulse))
        }
    }

    @Test fun listeningFadeRetainsOpaqueIconContrastInLightAndDarkThemes() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            for (pulse in listOf(0f, .5f, 1f)) {
                val background = voiceMicrophoneContainerColor(VoiceMicrophoneAction.FINISH_CAPTURE, scheme.surfaceContainerHigh, scheme.primary, pulse)
                val luminances = listOf(background.luminance(), scheme.onPrimary.luminance())
                val contrast = (luminances.max() + .05f) / (luminances.min() + .05f)
                assertTrue("The microphone must retain at least 3:1 contrast: $contrast", contrast >= 3f)
                assertEquals(1f, background.alpha, 0f)
            }
        }
    }
}
