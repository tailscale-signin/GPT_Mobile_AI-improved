package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelState
import dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStatus
import dev.chungjungsoo.gptmobile.data.voice.VoiceCaptionCursor
import dev.chungjungsoo.gptmobile.data.voice.VoiceCaptionTiming
import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechText
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import dev.chungjungsoo.gptmobile.data.voice.VoiceTranslationPrompt
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
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
        compose.onNodeWithTag("voice_caption_text").assertTextEquals("Preparing a short reply.")
        compose.runOnIdle { session.value = session.value.copy(phase = VoicePhase.SPEAKING, reply = fullReply) }
        compose.onNodeWithTag("voice_caption_text").assertTextEquals(VoiceSpeechText.project(fullReply))
        compose.onNodeWithTag("voice_live_captions").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("voice_caption_text").assertIsDisplayed()
        microphone.assertIsDisplayed()
        assertEquals(initialBounds, microphone.fetchSemanticsNode().boundsInRoot)
        microphone.performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun readablePreviewKeepsItsOpacityAndReaderWhenTimingArrivesAndSameTextReplayStartsFresh() {
        val reply = (1..40).joinToString("\n") { "Caption line $it" }
        val session = mutableStateOf(VoiceState(phase = VoicePhase.PREPARING, reply = reply, captionPresentationId = 1))
        val cursor = MutableStateFlow(VoiceCaptionCursor(presentationId = 1))
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color.Magenta, onSurface = Color.Black)) {
                Box(Modifier.background(Color.White)) {
                    VoiceLiveCaptions(session.value, compact = false, Modifier.width(320.dp), cursor)
                }
            }
        }
        val caption = compose.onNodeWithTag("voice_caption_text")
        fun range() = caption.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        fun visibleInk(): Int {
            val bitmap = compose.onNodeWithTag("voice_live_captions").captureToImage().asAndroidBitmap()
            var count = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (android.graphics.Color.alpha(pixel) > 128 && android.graphics.Color.red(pixel) < 160 && android.graphics.Color.green(pixel) < 160 && android.graphics.Color.blue(pixel) < 160) count++
                }
            }
            return count
        }
        compose.waitForIdle()
        assertEquals("The preaudio answer starts at its first line", 0f, range().value(), 1f)
        caption.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 400f) }
        compose.waitForIdle()
        val readingPosition = range().value()
        assertTrue(readingPosition > 0f)
        compose.onNodeWithTag("voice_caption_return_to_live").assertIsDisplayed()
        val previewInk = visibleInk()
        assertTrue(previewInk > 0)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            session.value = session.value.copy(phase = VoicePhase.SPEAKING)
            cursor.value = VoiceCaptionCursor(
                0..6,
                timing = VoiceCaptionTiming("first-audio", 0, 2400, 480, 24000, System.nanoTime(), true, bufferedEndFrame = 4800),
                presentationId = 1
            )
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals("The first timing ID must not replace the manual reader", readingPosition, range().value(), 1f)
        assertTrue("An already readable preview must not fade back to zero", visibleInk() >= previewInk / 2)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { session.value = session.value.copy(phase = VoicePhase.PAUSED) }
        compose.waitForIdle()
        assertEquals(readingPosition, range().value(), 1f)

        // Deliver only the replacement snapshot: a collector need not see an empty intermediate.
        compose.runOnIdle {
            session.value = session.value.copy(phase = VoicePhase.PREPARING, captionPresentationId = 2)
            cursor.value = cursor.value.copy(projectedText = "A stale caption from the preceding playback")
        }
        compose.waitForIdle()
        caption.assertTextEquals(reply)
        assertEquals("A same-text replay starts a new reader at the first line", 0f, range().value(), 1f)
        compose.onNodeWithTag("voice_caption_return_to_live").assertDoesNotExist()
    }

    @Test fun microphoneAndLanguageControlsFollowTheTurnAndPickerState() {
        val current = mutableStateOf(state(VoiceState()).copy(preferencesApplied = false))
        var presses = 0
        compose.setContent {
            MaterialTheme {
                VoiceConversationScreen(
                    current.value,
                    actions(
                        stop = { presses++ },
                        language = { current.value = current.value.copy(replyLanguage = it) },
                        translate = { current.value = current.value.copy(translationMode = it) }
                    )
                )
            }
        }
        val microphone = compose.onNodeWithTag("voice_microphone_toggle")
        val language = compose.onNodeWithTag("voice_reply_language")
        compose.onNodeWithTag("voice_captions_toggle").assertDoesNotExist()
        compose.onNodeWithText("Audio").assertDoesNotExist()
        language.assertIsNotEnabled()
        compose.runOnIdle { current.value = current.value.copy(preferencesApplied = true) }
        val chat = compose.onNodeWithText("Text chat").fetchSemanticsNode().boundsInRoot
        val mic = microphone.assertContentDescriptionEquals("Start listening").fetchSemanticsNode().boundsInRoot
        assertTrue(mic.width > chat.height && mic.bottom <= chat.top)
        assertEquals(mic.center.x, chat.center.x, 2f)
        assertTrue(language.fetchSemanticsNode().boundsInRoot.center.x > chat.center.x)
        microphone.performClick()
        language.performClick()
        val translate = compose.onNodeWithTag("voice_translation_mode")
        translate.assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("voice_reply_language_fr").performClick()
        compose.runOnIdle { assertEquals("fr", current.value.replyLanguage) }
        language.performClick()
        translate.assertIsOn()
        compose.onNodeWithText("Done").performClick()
        for (phase in listOf(VoicePhase.LISTENING, VoicePhase.FINALIZING, VoicePhase.THINKING, VoicePhase.PREPARING, VoicePhase.SPEAKING)) {
            compose.runOnIdle { current.value = current.value.copy(session = VoiceState(phase = phase)) }
            language.assertIsNotEnabled()
            microphone.assertContentDescriptionEquals(if (phase == VoicePhase.LISTENING) "Finish speaking" else "Stop").performClick()
        }
        compose.runOnIdle { assertEquals(6, presses) }
    }

    @Test fun translationKeepsTheSourceAboveTheResponseAndControlsAcrossCaptures() {
        val words = "Where is the train station?"
        val translated = "Où est la gare ?"
        val source = VoiceTranslationPrompt(1L, words)
        val session = mutableStateOf(VoiceState(phase = VoicePhase.LISTENING, transcript = words, translationPrompt = source))
        val translating = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.4f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        VoiceConversationScreen(state(session.value).copy(translationMode = translating.value, captions = false), actions())
                    }
                }
            }
        }
        val top = compose.onNodeWithTag("voice_translation_source")
        val bottom = compose.onNodeWithTag("voice_caption_text")
        top.assertTextEquals(words).assertIsDisplayed()
        bottom.assertDoesNotExist()
        val microphone = compose.onNodeWithTag("voice_microphone_toggle")
        val controls = microphone.fetchSemanticsNode().boundsInRoot
        for (phase in listOf(VoicePhase.THINKING, VoicePhase.SPEAKING, VoicePhase.PAUSED, VoicePhase.STARTING)) {
            compose.runOnIdle { session.value = session.value.copy(phase = phase, transcript = "", reply = translated) }
            top.assertTextEquals(words).assertIsDisplayed()
            bottom.assertTextEquals(translated).assertIsDisplayed()
            assertTrue(top.fetchSemanticsNode().boundsInRoot.bottom <= bottom.fetchSemanticsNode().boundsInRoot.top)
            assertTrue(bottom.fetchSemanticsNode().boundsInRoot.bottom <= controls.top)
            assertEquals(controls, microphone.fetchSemanticsNode().boundsInRoot)
        }
        compose.runOnIdle { session.value = session.value.copy(phase = VoicePhase.LISTENING, translationPrompt = null) }
        top.assertDoesNotExist()
        bottom.assertTextEquals(translated)
        val longText = "A long dictated translation prompt. ".repeat(80)
        compose.runOnIdle { session.value = session.value.copy(translationPrompt = VoiceTranslationPrompt(2L, longText), reply = "", phase = VoicePhase.THINKING) }
        top.assertTextEquals(longText).assertIsDisplayed()
        bottom.assertTextEquals(translated).assertIsDisplayed()
        assertEquals(controls, microphone.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { session.value = session.value.copy(reply = "Le prochain train arrive.", replyComplete = true) }
        bottom.assertTextEquals("Le prochain train arrive.")
        compose.runOnIdle { translating.value = false }
        top.assertDoesNotExist()
        bottom.assertDoesNotExist()
    }

    @Test fun translationSourceFadesWithinOneSecondAndKeepsItsOpacityOnPartialCorrections() {
        val source = mutableStateOf<VoiceTranslationPrompt?>(null)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = Color.White, onSurface = Color.Black)) {
                VoiceConversationScreen(state(VoiceState(phase = VoicePhase.LISTENING, translationPrompt = source.value)).copy(translationMode = true), actions())
            }
        }
        val top = compose.onNodeWithTag("voice_translation_source")
        fun ink(): Int {
            val bitmap = top.captureToImage().asAndroidBitmap()
            var darkest = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    darkest = maxOf(darkest, (255 - android.graphics.Color.red(pixel)) * android.graphics.Color.alpha(pixel) / 255)
                }
            }
            return darkest
        }
        fun settleComposition() {
            repeat(2) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { source.value = VoiceTranslationPrompt(1L, "Where is the station?") }
            settleComposition()
            compose.mainClock.advanceTimeBy(160)
            val entering = ink()
            compose.mainClock.advanceTimeBy(240)
            val middle = ink()
            compose.mainClock.advanceTimeBy(480)
            val fullyVisible = ink()
            assertTrue("Fade in: $entering, $middle, $fullyVisible", entering < middle && middle < fullyVisible && fullyVisible > 220)
            compose.runOnIdle { source.value = VoiceTranslationPrompt(1L, "Where is the train station?") }
            settleComposition()
            top.assertTextEquals("Where is the train station?")
            assertTrue(ink() >= fullyVisible - 10)
            compose.runOnIdle { source.value = null }
            settleComposition()
            compose.mainClock.advanceTimeBy(112)
            val exiting = ink()
            assertTrue("Fade out: $exiting of $fullyVisible", exiting in 1 until fullyVisible)
            compose.mainClock.advanceTimeBy(200)
            top.assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
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

    @Test fun drawnHighlightsKeepGlyphGeometryAndSilenceKeepsItsPlace() {
        val reply = "One\nTwo\nThree fish\nFour\nFive\nSix"
        val session = mutableStateOf(VoiceState(phase = VoicePhase.SPEAKING, reply = reply, spokenRange = 14..17))
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color.Magenta, onSurface = Color.Black)) {
                VoiceLiveCaptions(session.value, compact = false, Modifier.width(320.dp))
            }
        }
        fun line(value: String): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(value, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        fun bounds(value: String) = compose.onNodeWithText(value, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        fun accentPixels(): Int {
            val image = compose.onNodeWithTag("voice_live_captions").captureToImage().asAndroidBitmap()
            var count = 0
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val pixel = image.getPixel(x, y)
                    if (android.graphics.Color.red(pixel) > 180 && android.graphics.Color.blue(pixel) > 180 && android.graphics.Color.green(pixel) < 120) count++
                }
            }
            return count
        }
        compose.waitForIdle()
        val focused = bounds("Three fish")
        val geometry = line("Three fish")
        assertEquals(focused.height * .75f, bounds("Two").height, 1f)
        assertEquals(focused.height * .5f, bounds("One").height, 1f)
        assertTrue("The spoken glyphs must actually draw in the theme colour", accentPixels() > 0)
        compose.runOnIdle { session.value = session.value.copy(spokenRange = 8..12) }
        compose.waitForIdle()
        val movedWord = line("Three fish")
        for (offset in "Three fish".indices) assertEquals(geometry.getBoundingBox(offset), movedWord.getBoundingBox(offset))
        assertEquals(focused, bounds("Three fish"))
        compose.runOnIdle { session.value = session.value.copy(spokenRange = null) }
        compose.waitForIdle()
        assertEquals(0, accentPixels())
        assertEquals(focused, bounds("Three fish"))
        compose.runOnIdle { session.value = session.value.copy(spokenRange = 19..22) }
        compose.waitForIdle()
        assertEquals(focused, bounds("Four"))
        assertTrue(accentPixels() > 0)
    }

    @Test fun accessibilityCaptionScrollingCanReadEarlierWordsAndResumeFollowing() {
        val session = mutableStateOf(VoiceState(phase = VoicePhase.PAUSED, reply = (1..40).joinToString("\n") { "Caption line $it" }))
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

    @Test fun returnToLiveUsesTheCurrentSpokenLineAndDoesNotChangePlayback() {
        val reply = (1..40).joinToString("\n") { "Line $it" }
        fun spoken(line: Int): IntRange {
            val start = reply.indexOf("Line $line\n")
            return start until start + "Line $line".length
        }
        val session = mutableStateOf(VoiceState(phase = VoicePhase.SPEAKING, reply = reply, spokenRange = spoken(12)))
        compose.setContent { MaterialTheme { VoiceLiveCaptions(session.value, compact = false, Modifier.width(320.dp)) } }
        val caption = compose.onNodeWithTag("voice_caption_text")
        val returnToLive = compose.onNodeWithTag("voice_caption_return_to_live")
        fun range() = caption.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.waitForIdle()
        val lineStep = range().maxValue() / 39f
        val viewport = caption.fetchSemanticsNode().boundsInRoot
        returnToLive.assertDoesNotExist()
        caption.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -2.5f * lineStep) }
        compose.waitForIdle()
        val readingPosition = range().value()
        assertEquals(8.5f * lineStep, readingPosition, 1f)
        returnToLive.assertIsDisplayed().assertTextEquals("Return to live")
        assertEquals("Showing the control must not move the caption viewport", viewport, caption.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { session.value = session.value.copy(spokenRange = spoken(23)) }
        compose.waitForIdle()
        assertEquals(readingPosition, range().value(), 1f)
        val beforeReturn = session.value
        returnToLive.performClick()
        compose.waitForIdle()
        assertEquals("The control only changes the reader; audio state stays untouched", beforeReturn, session.value)
        assertEquals(22f * lineStep, range().value(), 1f)
        assertTrue("Return to live must use the spoken line, not the end of the reply", range().value() < range().maxValue())
        returnToLive.assertDoesNotExist()
        compose.runOnIdle { session.value = session.value.copy(spokenRange = spoken(27)) }
        compose.waitForIdle()
        assertEquals(26f * lineStep, range().value(), 1f)
        compose.runOnIdle { session.value = session.value.copy(phase = VoicePhase.PAUSED, spokenRange = null) }
        compose.waitForIdle()
        caption.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -2.5f * lineStep) }
        compose.waitForIdle()
        val paused = session.value
        returnToLive.assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals("Returning while paused must not resume audio", paused, session.value)
        assertEquals(26f * lineStep, range().value(), 1f)
        returnToLive.assertDoesNotExist()
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
        compose.onNodeWithText("Go ahead, I’m listening.").assertIsDisplayed()
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

    private fun actions(stop: () -> Unit = {}, select: (String) -> Unit = {}, language: (String) -> Unit = {}, translate: (Boolean) -> Unit = {}) = VoiceConversationActions(
        selectProfile = select,
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
        changeReplyLanguage = language,
        changeTranslationMode = translate,
        end = {}
    )
}
