package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionCompute
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionModel
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionTiming
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionTimingProvenance
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionTranscript
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceRecognitionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun nemotronProcessorChoicesReachTheExactModelAndComputeCallbacks() {
        var model by mutableStateOf(VoiceRecognitionModel.MOONSHINE_SMALL)
        var compute by mutableStateOf(VoiceRecognitionCompute.CPU)
        val selections = mutableListOf<Pair<VoiceRecognitionModel, VoiceRecognitionCompute>>()
        compose.setContent {
            MaterialTheme {
                Column {
                    VoiceRecognitionModelChoices(
                        model,
                        compute,
                        0,
                        "en-US",
                        { model = it },
                        {
                            compute = it
                            selections += model to it
                        },
                        {}
                    )
                }
            }
        }
        compose.onNodeWithText("Recognition processor").assertDoesNotExist()
        compose.onNodeWithText(VoiceRecognitionModel.MOONSHINE_SMALL.title).performClick()
        compose.onNodeWithText(VoiceRecognitionModel.NEMOTRON_3_5.title).performScrollTo().performClick()
        compose.onNodeWithText("Recognition processor").assertIsDisplayed()
        compose.onNodeWithText(VoiceRecognitionCompute.CPU.title).performClick()
        compose.onNodeWithText(VoiceRecognitionCompute.GPU.title).performClick()
        compose.onNodeWithText("Recognition CPU threads").assertIsDisplayed()
        compose.onNodeWithText(VoiceRecognitionCompute.GPU.title).performClick()
        compose.onNodeWithText(VoiceRecognitionCompute.NPU.title).performClick()
        compose.onNodeWithText("Recognition CPU threads").assertDoesNotExist()
        compose.onNodeWithText("This NPU pack targets Snapdragon 8 Elite (SM8750).", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(VoiceRecognitionModel.NEMOTRON_3_5, model)
            assertEquals(VoiceRecognitionCompute.NPU, compute)
            assertEquals(
                listOf(VoiceRecognitionModel.NEMOTRON_3_5 to VoiceRecognitionCompute.GPU, VoiceRecognitionModel.NEMOTRON_3_5 to VoiceRecognitionCompute.NPU),
                selections
            )
        }
    }

    @Test
    fun tokenTimingDialogDisplaysStartsWithoutFabricatingWordEnds() {
        val transcript = transcript(
            listOf(
                VoiceRecognitionTiming(0, 5, 1_600),
                VoiceRecognitionTiming(6, 11, 4_800)
            )
        )
        compose.setContent { MaterialTheme { VoiceRecognitionTimingsDialog(transcript) {} } }
        compose.onNodeWithTag("voice_recognition_time_0").assertTextEquals("0.10 s")
        compose.onNodeWithTag("voice_recognition_time_1").assertTextEquals("0.30 s")
        compose.onNodeWithText("This model does not supply word endings.", substring = true).assertIsDisplayed()
    }

    @Test
    fun wordTimingDialogDisplaysTheNativeIntervalsOnTheInputClock() {
        val transcript = transcript(
            listOf(
                VoiceRecognitionTiming(0, 5, 1_600, 4_000, VoiceRecognitionTimingProvenance.WORD_ALIGNMENT),
                VoiceRecognitionTiming(6, 11, 4_800, 8_000, VoiceRecognitionTimingProvenance.WORD_ALIGNMENT)
            )
        )
        compose.setContent { MaterialTheme { VoiceRecognitionTimingsDialog(transcript) {} } }
        compose.onNodeWithTag("voice_recognition_time_0").assertTextEquals("0.10 s – 0.25 s")
        compose.onNodeWithTag("voice_recognition_time_1").assertTextEquals("0.30 s – 0.50 s")
        compose.onNodeWithText("They are separate from reply playback.", substring = true).assertIsDisplayed()
    }

    @Test
    fun liveCaptionTimingOpensAnExactSnapshotAndDoesNotRelabelCorrectedText() {
        val transcript = transcript(listOf(VoiceRecognitionTiming(0, 5, 1_600), VoiceRecognitionTiming(6, 11, 4_800)))
        var state by mutableStateOf(VoiceState(phase = VoicePhase.LISTENING, transcript = transcript.text, recognitionTranscript = transcript))
        compose.setContent { MaterialTheme { VoiceLiveCaptions(state, compact = false, Modifier.width(320.dp)) } }
        compose.onNodeWithTag("voice_recognition_show_timing").performClick()
        compose.runOnIdle { state = state.copy(transcript = "corrected text") }
        compose.onNodeWithText("hello").assertIsDisplayed()
        compose.onNodeWithTag("voice_recognition_time_0").assertTextEquals("0.10 s")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("voice_recognition_timings").assertDoesNotExist()
        compose.onNodeWithTag("voice_recognition_show_timing").assertDoesNotExist()
    }

    private fun transcript(timings: List<VoiceRecognitionTiming>) = VoiceRecognitionTranscript(
        text = "hello world",
        streamId = 1,
        revision = 1,
        isFinal = false,
        sampleRate = 16_000,
        audioFrames = 16_000,
        timings = timings
    )
}
