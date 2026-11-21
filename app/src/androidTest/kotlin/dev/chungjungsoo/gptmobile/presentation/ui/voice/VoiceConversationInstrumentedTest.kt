package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoiceConversationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longCaptionsAndLargeTextKeepTheMicrophoneVisibleAndFixed() {
        val session = mutableStateOf(VoiceState(phase = VoicePhase.PREPARING, reply = "Preparing a short reply."))
        val fullReply = "A complete sentence for the live caption. ".repeat(350) + "The unabridged ending 👋"
        var stops = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        VoiceConversationScreen(state(session.value), actions(stop = { stops++ }))
                    }
                }
            }
        }
        val microphone = compose.onNodeWithTag("voice_microphone_toggle").assertIsDisplayed()
        val initialBounds = microphone.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { session.value = session.value.copy(reply = fullReply) }
        compose.onNodeWithTag("voice_caption_text").assertTextEquals(fullReply)
        compose.onNodeWithTag("voice_live_captions").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("voice_caption_text").assertIsDisplayed()
        microphone.assertIsDisplayed()
        assertEquals(initialBounds, microphone.fetchSemanticsNode().boundsInRoot)
        microphone.performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun captionsWaitForRestoredPreferencesAndExposeCheckedState() {
        val initial = state(VoiceState()).copy(preferencesApplied = false, captions = true)
        val current = mutableStateOf(initial)
        var changes = 0
        compose.setContent {
            MaterialTheme {
                VoiceConversationScreen(
                    current.value,
                    actions(captions = {
                        changes++
                        current.value = current.value.copy(captions = !current.value.captions)
                    })
                )
            }
        }
        val captions = compose.onNodeWithTag("voice_captions_toggle")
        captions.assertIsDisplayed().assertIsNotEnabled().assertIsOn().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(0, changes)
            assertEquals(initial, current.value)
            current.value = initial.copy(preferencesApplied = true, captions = false)
        }
        captions.assertIsEnabled().assertIsOff().performClick()
        compose.runOnIdle {
            assertEquals(1, changes)
            assertEquals(initial.copy(preferencesApplied = true, captions = true), current.value)
        }
        captions.assertIsOn().performClick()
        compose.runOnIdle {
            assertEquals(2, changes)
            assertEquals(initial.copy(preferencesApplied = true, captions = false), current.value)
        }
        captions.assertIsOff()
    }

    @Test fun theProfilePickerChangesProfileOnlyBetweenTurns() {
        val session = mutableStateOf(VoiceState())
        var selected: String? = null
        compose.setContent { MaterialTheme { VoiceConversationScreen(state(session.value), actions(select = { selected = it })) } }
        compose.onNodeWithTag("voice_profile_picker").performClick()
        compose.onNodeWithText("Other AI").performClick()
        compose.runOnIdle {
            assertEquals("other", selected)
            session.value = VoiceState(phase = VoicePhase.THINKING, awaitingReply = true)
        }
        compose.onNodeWithTag("voice_profile_picker").assertIsNotEnabled()
    }

    @Test fun onlyTheCenterLineHighlightsAndSilenceKeepsItsPlace() {
        val reply = "One\nTwo\nThree fish\nFour\nFive\nSix"
        val session = mutableStateOf(VoiceState(phase = VoicePhase.SPEAKING, reply = reply, spokenRange = 14..17))
        compose.setContent { MaterialTheme { VoiceLiveCaptions(session.value, compact = false, Modifier.width(320.dp)) } }
        fun line(value: String): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(value, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        val active = line("Three fish").layoutInput
        val mark = active.text.spanStyles.single()
        assertEquals("fish", active.text.text.substring(mark.start, mark.end))
        assertEquals(TextDecoration.Underline, mark.item.textDecoration)
        assertEquals(active.style.fontSize.value * .75f, line("Two").layoutInput.style.fontSize.value, .01f)
        assertEquals(active.style.fontSize.value * .5f, line("One").layoutInput.style.fontSize.value, .01f)
        compose.onNodeWithText("Six", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { session.value = session.value.copy(spokenRange = null) }
        assertTrue(line("Three fish").layoutInput.text.spanStyles.isEmpty())
        assertEquals(active.style.fontSize, line("Three fish").layoutInput.style.fontSize)
        compose.runOnIdle { session.value = session.value.copy(spokenRange = 19..22) }
        assertEquals(active.style.fontSize, line("Four").layoutInput.style.fontSize)
        assertTrue(line("Three fish").layoutInput.text.spanStyles.isEmpty())
        compose.onNodeWithText("One", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun accessibilityCaptionScrollingCanReadEarlierWordsAndResumeFollowing() {
        val session = mutableStateOf(VoiceState(phase = VoicePhase.THINKING, reply = (1..40).joinToString("\n") { "Caption line $it" }))
        compose.setContent {
            MaterialTheme { VoiceLiveCaptions(session.value, compact = false, Modifier.width(320.dp)) }
        }
        val caption = compose.onNodeWithTag("voice_caption_text")
        fun range() = caption.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(range().maxValue(), range().value(), 1f)
        caption.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -400f) }
        compose.waitForIdle()
        val readingPosition = range().value()
        assertTrue(readingPosition < range().maxValue())
        compose.runOnIdle { session.value = session.value.copy(reply = session.value.reply + "\nA new caption line") }
        compose.waitForIdle()
        assertEquals(readingPosition, range().value(), 1f)
        caption.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        compose.runOnIdle { session.value = session.value.copy(reply = session.value.reply + "\nThe current caption line") }
        compose.waitForIdle()
        assertEquals(range().maxValue(), range().value(), 1f)
    }

    @Test fun accessibilityCaptionScrollingPausesObservedRangeFollowingUntilTheReaderReturnsToTheBottom() {
        val reply = (1..40).joinToString("\n") { "Caption line $it" }
        val session = mutableStateOf(VoiceState(phase = VoicePhase.SPEAKING, reply = reply, spokenRange = reply.indices.last - 10..reply.indices.last))
        compose.setContent {
            MaterialTheme { VoiceLiveCaptions(session.value, compact = false, Modifier.width(320.dp)) }
        }
        val caption = compose.onNodeWithTag("voice_caption_text")
        fun range() = caption.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.waitForIdle()
        assertEquals(range().maxValue(), range().value(), 1f)
        caption.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -400f) }
        compose.waitForIdle()
        val readingPosition = range().value()
        assertTrue(readingPosition < range().maxValue())
        compose.runOnIdle {
            session.value = session.value.copy(spokenRange = reply.indices.last - 20..reply.indices.last - 11)
        }
        compose.waitForIdle()
        assertEquals(readingPosition, range().value(), 1f)
        caption.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        compose.runOnIdle {
            val middle = reply.indexOf("Caption line 20")
            session.value = session.value.copy(spokenRange = middle until middle + "Caption line 20".length)
        }
        compose.waitForIdle()
        assertTrue(range().value() < range().maxValue())
        val middlePosition = range().value()
        compose.runOnIdle {
            val later = reply.indexOf("Caption line 25")
            session.value = session.value.copy(spokenRange = later until later + "Caption line 25".length)
        }
        compose.waitForIdle()
        assertTrue("An automatic move must keep following later spoken words", range().value() > middlePosition)
    }

    @Test fun correctedCaptionTextKeepsTheReadersPositionAndControlsStable() {
        val original = (1..40).joinToString("\n") { "Recognized phrase $it" }
        val session = mutableStateOf(VoiceState(phase = VoicePhase.LISTENING, transcript = original))
        compose.setContent { MaterialTheme { VoiceConversationScreen(state(session.value), actions()) } }
        val caption = compose.onNodeWithTag("voice_caption_text")
        fun range() = caption.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.waitForIdle()
        val controlBounds = compose.onNodeWithTag("voice_microphone_toggle").fetchSemanticsNode().boundsInRoot
        caption.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -400f) }
        compose.waitForIdle()
        val readingPosition = range().value()
        compose.runOnIdle {
            session.value = session.value.copy(transcript = original.replace("phrase 1\n", "phrase A\n") + "\nAnother recognized phrase")
        }
        compose.waitForIdle()
        assertEquals(readingPosition, range().value(), 1f)
        assertEquals(controlBounds, compose.onNodeWithTag("voice_microphone_toggle").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun themedVoiceStagesRetainRenderedEvidence() {
        val dark = mutableStateOf(true)
        val session = mutableStateOf(
            VoiceState(phase = VoicePhase.LISTENING, transcript = "Let's plan a beautiful world for our next game.", level = .7f, hasAudioLevel = true)
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f)) {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    VoiceConversationScreen(
                        state(session.value).copy(status = if (dark.value) "Listening" else "Speaking"),
                        actions()
                    )
                }
            }
        }
        compose.onNodeWithTag("voice_microphone_toggle").assertIsDisplayed()
        capture("listening-dark")
        compose.runOnIdle {
            dark.value = false
            session.value = VoiceState(
                phase = VoicePhase.SPEAKING,
                reply = "Imagine a city suspended among the clouds, with warm lights guiding you home.",
                spokenRange = 10..13
            )
        }
        compose.onNodeWithTag("voice_microphone_toggle").assertIsDisplayed()
        capture("speaking-light")
    }

    private fun capture(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ui-review/voice-chat").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun state(session: VoiceState) = VoiceConversationUiState(
        profileName = "Selected AI",
        profiles = listOf(PlatformV2(uid = "current", name = "Selected AI"), PlatformV2(uid = "other", name = "Other AI")),
        selectedProfileId = "current",
        captions = true,
        session = session,
        status = if (session.phase == VoicePhase.PREPARING) "Preparing voice" else "Ready when you are",
        remaining = 0,
        localModel = LocalVoiceModelState(status = LocalVoiceModelStatus.READY),
        language = "en-US",
        recognitionReady = true,
        enabled = true,
        voiceReady = true,
        profileError = null,
        preferencesApplied = true,
        waitingApproval = false,
        draft = ""
    )

    private fun actions(stop: () -> Unit = {}, select: (String) -> Unit = {}, captions: () -> Unit = {}) = VoiceConversationActions(
        selectProfile = select,
        openTranscript = {},
        openSettings = {},
        showSettings = {},
        installLocalModel = {},
        cancelLocalModelInstall = {},
        changeDraft = {},
        sendDraft = {},
        listen = {},
        recordAgain = {},
        repair = {},
        openVoiceConfiguration = {},
        replay = {},
        openChat = {},
        toggleMicrophone = stop,
        toggleCaptions = captions,
        openAudioRoute = {},
        end = {}
    )
}
