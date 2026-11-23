package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.voice.VoiceMessageIdentity
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class VoiceMessageControlsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullDetailsActionKeepsTheOriginalMessageAndUsesTheSingleStopControl() {
        val message = MessageV2(id = 3, chatId = 7, content = "Short answer. <details>Extra facts.</details>", platformType = "profile", currentRunId = "details")
        val active = mutableStateOf<VoiceMessageIdentity?>(null)
        val details = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalVoiceMessageControls provides VoiceMessageControls(
                        active.value,
                        if (active.value == null) VoicePhase.PAUSED else VoicePhase.SPEAKING,
                        true,
                        read = { _, _ -> error("Details must use their explicit action") },
                        stop = { active.value = null },
                        pauseCapture = {},
                        readDetails = { selected, text ->
                            details += text
                            active.value = selected.voiceIdentity()
                        }
                    )
                ) { VoiceMessageSpeaker(message, message.content) }
            }
        }
        compose.onNodeWithTag("voice_response_details_3").assertIsDisplayed().performClick()
        compose.onNodeWithTag("voice_response_details_3").assertDoesNotExist()
        compose.onNodeWithTag("voice_response_speaker_3").assertContentDescriptionEquals("Stop reading AI response").performClick()
        compose.runOnIdle { assertEquals(listOf(message.content), details) }
    }

    @Test fun voiceAnswerShowsItsSpeechAndKeepsDetailsCollapsedUntilSelected() {
        val source = "<think>Private planning</think>Short answer. <details>Extra facts.</details>"
        val speech = VoiceSpeechText.project(source)
        assertEquals("Short answer.", speech.trim())
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalVoiceMessageControls provides VoiceMessageControls(null, VoicePhase.PAUSED, true, { _, _ -> }, {}, {})
                ) {
                    VoiceAnswerPresentation(source, "Short answer. Extra facts.", "answer", false, emptyList()) {
                        Text("Expanded rich details")
                    }
                }
            }
        }
        compose.onNodeWithTag("voice_spoken_answer").assertIsDisplayed().assertTextEquals(speech)
        compose.onNodeWithText("Private planning", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Extra facts.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Expanded rich details").assertDoesNotExist()
        compose.onNodeWithTag("voice_answer_details_toggle").assertIsDisplayed().performClick()
        compose.onNodeWithText("Expanded rich details").assertIsDisplayed()
    }

    @Test fun everyResponseHasItsOwnThemedReadActionAndOnlyTheActiveReplyStops() {
        val first = MessageV2(id = 1, chatId = 7, content = "Here is the first answer.", platformType = "profile", currentRunId = "first")
        val second = MessageV2(id = 2, chatId = 7, content = "Here is the next answer.", platformType = "profile", currentRunId = "second")
        val active = mutableStateOf<VoiceMessageIdentity?>(null)
        val reads = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(
                    LocalVoiceMessageControls provides VoiceMessageControls(
                        active.value,
                        if (active.value == null) VoicePhase.PAUSED else VoicePhase.SPEAKING,
                        true,
                        read = { message, _ ->
                            reads += message.id
                            active.value = message.voiceIdentity()
                        },
                        stop = { active.value = null },
                        pauseCapture = {}
                    )
                ) {
                    Column {
                        VoiceMessageSpeaker(first, first.content)
                        VoiceMessageSpeaker(second, second.content)
                    }
                }
            }
        }
        val firstAction = compose.onNodeWithTag("voice_response_speaker_1").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val secondAction = compose.onNodeWithTag("voice_response_speaker_2").assertIsDisplayed()
        firstAction.assertContentDescriptionEquals("Read AI response aloud").performClick()
        firstAction.assertContentDescriptionEquals("Stop reading AI response")
        secondAction.assertContentDescriptionEquals("Read AI response aloud").performClick()
        firstAction.assertContentDescriptionEquals("Read AI response aloud")
        secondAction.assertContentDescriptionEquals("Stop reading AI response").performClick()
        compose.runOnIdle {
            assertEquals(listOf(1, 2), reads)
            assertNull(active.value)
        }
    }

    @Test fun autoplayStopBelongsToTheLatestRunAndHistoricalReplayKeepsItsOwnRevision() {
        val latest = MessageV2(
            id = 1,
            chatId = 7,
            content = "The regenerated answer.",
            platformType = "profile",
            currentRunId = "latest",
            revisions = listOf(AssistantRevision("The original answer.", createdAt = 1, runId = "original"))
        )
        val shown = mutableStateOf(latest)
        val active = mutableStateOf<VoiceMessageIdentity?>(VoiceMessageIdentity(1, "latest"))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(
                    LocalVoiceMessageControls provides VoiceMessageControls(
                        active.value,
                        VoicePhase.SPEAKING,
                        true,
                        read = { message, _ -> active.value = message.voiceIdentity() },
                        stop = { active.value = null },
                        pauseCapture = {}
                    )
                ) { VoiceMessageSpeaker(shown.value, "Selected revision text") }
            }
        }
        val action = compose.onNodeWithTag("voice_response_speaker_1")
        action.assertContentDescriptionEquals("Stop reading AI response")
        compose.runOnIdle { shown.value = latest.copy(activeRevisionIndex = 0) }
        action.assertContentDescriptionEquals("Read AI response aloud").performClick()
        action.assertContentDescriptionEquals("Stop reading AI response")
        compose.runOnIdle {
            assertEquals(VoiceMessageIdentity(1, "original", 0), active.value)
            shown.value = latest
        }
        action.assertContentDescriptionEquals("Read AI response aloud")
        compose.runOnIdle { shown.value = latest.copy(currentRunId = "new-generation") }
        action.assertContentDescriptionEquals("Read AI response aloud")
    }
}
