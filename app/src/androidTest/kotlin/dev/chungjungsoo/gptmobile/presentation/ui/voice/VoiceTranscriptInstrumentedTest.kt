package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoiceTranscriptInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun themedTranscriptControlsRetainRenderedEvidence() {
        val dark = mutableStateOf(true)
        val fixture = Fixture()
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f),
                LocalContentColor provides Color.Black
            ) {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) { Transcript(fixture) }
                }
            }
        }
        for (isDark in listOf(true, false)) {
            compose.runOnIdle { dark.value = isDark }
            val expected = if (isDark) darkColorScheme().onSurface else lightColorScheme().onSurface
            assertTextColor("voice_transcript_profile", expected)
            assertTextColor("voice_transcript_auto_read_label", expected)
            assertIconColor("voice_transcript_back", expected)
            assertIconColor("voice_transcript_settings", expected)
            assertTabsVerticallyAligned()
            compose.onNodeWithTag("voice_transcript_microphone").assertIsDisplayed()
            compose.onNodeWithTag("voice_response_speaker_7").assertIsDisplayed()
            capture("transcript-controls-${if (isDark) "dark" else "light"}")
        }
    }

    @Test fun largeTextTranscriptActionsRemainReachableAndKeepMessageOwnership() {
        val fixture = Fixture()
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
                LocalContentColor provides Color.Black
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) { Transcript(fixture) }
                }
            }
        }
        compose.onNodeWithTag("voice_transcript_back").assertIsDisplayed().performClick()
        compose.onNodeWithTag("voice_transcript_settings").assertIsDisplayed().performClick()
        compose.onNodeWithTag("voice_transcript_show_voice").assertIsDisplayed().performClick()
        assertTabsVerticallyAligned()
        compose.onNodeWithTag("voice_chat_auto_read").assertIsDisplayed().performClick().assertIsOff()
        val speaker = compose.onNodeWithTag("voice_response_speaker_7").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        speaker.assertContentDescriptionEquals("Read AI response aloud").performClick()
        speaker.assertContentDescriptionEquals("Stop reading AI response")
        val microphone = compose.onNodeWithTag("voice_transcript_microphone").assertIsDisplayed()
        val playingBounds = microphone.fetchSemanticsNode().boundsInRoot
        microphone.assertContentDescriptionEquals("Stop reply").performClick()
        speaker.assertContentDescriptionEquals("Read AI response aloud")
        microphone.assertContentDescriptionEquals("Microphone").assertIsDisplayed()
        assertEquals(playingBounds, microphone.fetchSemanticsNode().boundsInRoot)
        speaker.performClick().performClick()
        compose.onNodeWithTag("voice_transcript_audio_output").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, fixture.back)
            assertEquals(1, fixture.settings)
            assertEquals(1, fixture.voice)
            assertEquals(1, fixture.microphone)
            assertEquals(1, fixture.routes)
            assertEquals(listOf(7, 7), fixture.reads)
            assertEquals(1, fixture.stops)
        }
        capture("transcript-controls-large-dark")
    }

    @Composable
    private fun Transcript(fixture: Fixture) {
        val phase = fixture.phase.value
        VoiceTranscriptFrame(
            profileName = "Selected AI",
            spokenReplies = fixture.autoRead.value,
            changeSpokenReplies = { fixture.autoRead.value = it },
            showVoice = { fixture.voice++ },
            settings = { fixture.settings++ },
            end = { fixture.back++ }
        ) {
            Column(Modifier.fillMaxSize()) {
                Text("You · Plan a quiet weekend.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                CompositionLocalProvider(
                    LocalVoiceMessageControls provides VoiceMessageControls(
                        fixture.message.voiceIdentity().takeIf { phase == VoicePhase.SPEAKING },
                        phase,
                        available = true,
                        read = { message, _ ->
                            fixture.reads += message.id
                            fixture.phase.value = VoicePhase.SPEAKING
                        },
                        stop = {
                            fixture.stops++
                            fixture.phase.value = VoicePhase.PAUSED
                        },
                        pauseCapture = {}
                    )
                ) { VoiceMessageSpeaker(fixture.message, fixture.message.content) }
                Text(fixture.message.content, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                VoiceTranscriptOutputControls(
                    status = "Ready when you are",
                    phase = phase,
                    microphone = {
                        fixture.microphone++
                        fixture.phase.value = VoicePhase.PAUSED
                    },
                    audioRoute = { fixture.routes++ }
                )
            }
        }
    }

    private fun assertTabsVerticallyAligned() {
        val voice = compose.onNodeWithTag("voice_transcript_show_voice").fetchSemanticsNode().boundsInRoot
        val chat = compose.onNodeWithTag("voice_transcript_chat").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(voice.center.y, chat.center.y, 1f)
    }

    private fun assertTextColor(tag: String, expected: Color) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(expected, layouts.single().layoutInput.style.color)
    }

    private fun assertIconColor(tag: String, expected: Color) {
        val bitmap = compose.onNodeWithTag(tag).assertIsDisplayed().captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val color = expected.toArgb()
        val matches = pixels.count { pixel ->
            listOf(0, 8, 16).all { shift -> abs((pixel shr shift and 255) - (color shr shift and 255)) <= 12 }
        }
        assertTrue("$tag must visibly render the theme foreground", matches >= 8)
    }

    private fun capture(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ui-review/voice-chat").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private class Fixture {
        val message = MessageV2(id = 7, chatId = 3, content = "Take a walk, then relax with a book.", platformType = "profile", currentRunId = "reply")
        val phase = mutableStateOf(VoicePhase.PAUSED)
        val autoRead = mutableStateOf(true)
        val reads = mutableListOf<Int>()
        var back = 0
        var settings = 0
        var voice = 0
        var microphone = 0
        var routes = 0
        var stops = 0
    }
}
