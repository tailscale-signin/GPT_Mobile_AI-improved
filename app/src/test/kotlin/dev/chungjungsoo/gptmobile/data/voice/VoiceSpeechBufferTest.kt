package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechBufferTest {
    @Test fun wordsArrivingDuringColdSynthesisKeepTheFastOpeningAndFormLongerFollowups() {
        var clock = 0L
        val buffer = VoiceSpeechBuffer { clock }
        val feedback = VoiceSynthesisFeedback(bufferState = "preparing")
        var text = "Soft "
        fun append() = requireNotNull(buffer.append(text, false, earlyStart = true, feedback = feedback, adaptive = true))
        assertTrue(append().isEmpty())
        clock = 90
        text += "light "
        assertTrue(append().isEmpty())
        clock = 120
        assertEquals(listOf(text), append())
        val opening = text
        for ((i, word) in listOf("falls", "across", "the", "floor", "in", "the", "afternoon").withIndex()) {
            clock = 180L + i * 90
            text += "$word "
            assertTrue(append().isEmpty())
        }
        clock = 780
        assertEquals(listOf(text.removePrefix(opening)), append())
        clock = 900
        text += "and slowly "
        assertTrue(append().isEmpty())
        assertEquals(200L, buffer.nextFlushDelayMs(feedback))
        clock += 200
        assertEquals(listOf("and slowly "), append())
        assertTrue(buffer.append(text.trimEnd(), true).orEmpty().isEmpty())
    }

    @Test fun productionTimeIsReservedBeforeThePlaybackHeadRunsOutOfWords() {
        var clock = 0L
        val buffer = VoiceSpeechBuffer { clock }
        val feedback = VoiceSynthesisFeedback(bufferedAudioMs = 1200, predictedNextSynthesisMs = 900)
        fun append(text: String) = buffer.append(text, false, earlyStart = true, feedback = feedback, adaptive = true)
        assertEquals(listOf("Ready. "), append("Ready. "))
        assertTrue(append("Ready. Follow the ").orEmpty().isEmpty())
        assertEquals(300L, buffer.nextFlushDelayMs(feedback))
        clock += 300
        assertEquals(listOf("Follow the "), append("Ready. Follow the "))
    }

    @Test fun balancedAndFastOpenOnStableWordsDuringAProviderPause() {
        var clock = 0L
        val buffer = VoiceSpeechBuffer { clock }
        val raw = "Toronto has "
        assertTrue(buffer.append(raw, false, earlyStart = true).orEmpty().isEmpty())
        assertEquals(120L, buffer.nextFlushDelayMs(null))
        clock += 120
        assertEquals(listOf(raw), buffer.append(raw, false, earlyStart = true))
        assertNull(buffer.nextFlushDelayMs(null))
        assertEquals(listOf("snow today."), buffer.append(raw + "snow today.", true))
    }

    @Test fun aShortQuestionSettlesWithoutWaitingForAnotherProviderToken() {
        var clock = 0L
        val buffer = VoiceSpeechBuffer { clock }
        assertTrue(buffer.append("Ready?", false, earlyStart = true).orEmpty().isEmpty())
        clock += 50
        assertTrue(buffer.append("Ready??", false, earlyStart = true).orEmpty().isEmpty())
        assertEquals(80L, buffer.nextFlushDelayMs(null))
        clock += 80
        assertEquals(listOf("Ready??"), buffer.append("Ready??", false, earlyStart = true))
        assertTrue(buffer.append("Ready??", true).orEmpty().isEmpty())
        val number = VoiceSpeechBuffer { clock }
        assertTrue(number.append("3.", false, earlyStart = true).orEmpty().isEmpty())
        assertNull(number.nextFlushDelayMs(null))
    }

    @Test fun frenchPunctuationSpacingDoesNotRepeatAHeardCueOrSwallowRealWords() {
        for (gap in listOf(" ", "\u00a0", "\u202f")) {
            val buffer = VoiceSpeechBuffer().apply { heardOpening = "Un instant." }
            val source = "Un instant$gap! La réponse est prête."
            val spoken = buildList {
                for (end in 1..source.length) addAll(requireNotNull(buffer.append(source.take(end), false, earlyStart = true)))
                addAll(requireNotNull(buffer.append(source, true)))
            }
            assertEquals("La réponse est prête.", spoken.joinToString(""))
            assertEquals("Un instant de silence.", VoiceSpokenLead.afterCue("Un instant de silence.", "Un instant.", true))
        }
    }

    @Test fun theHeardFrenchCueIsSkippedOnceAndOrdinaryAnswerTextKeepsItsWording() {
        val buffer = VoiceSpeechBuffer().apply { heardOpening = "Un instant." }
        val text = "Un instant. La réponse est prête."
        val speech = buildList {
            for (end in 1..text.length) addAll(buffer.append(text.take(end), false, earlyStart = true).orEmpty())
            addAll(buffer.append(text, true).orEmpty())
        }.joinToString("")
        assertEquals("La réponse est prête.", speech)
        assertEquals(".5 seconde.", VoiceSpokenLead.afterCue("Un instant. .5 seconde.", "Un instant.", true))
        assertEquals("Un instantané précis.", VoiceSpokenLead.afterCue("Un instantané précis.", "Un instant.", true))
    }

    @Test fun bufferedAudioLetsAClauseFinishAndLowReserveReleasesWords() {
        var clock = 0L
        val buffer = VoiceSpeechBuffer { clock }
        val healthy = VoiceSynthesisFeedback(bufferedAudioMs = 1600)
        val low = healthy.copy(bufferedAudioMs = 160)
        val greeting = "Ready. "
        fun append(tail: String, feedback: VoiceSynthesisFeedback) =
            requireNotNull(buffer.append(greeting + tail, false, earlyStart = true, feedback = feedback, adaptive = true))
        assertEquals(listOf(greeting), append("", healthy))
        assertTrue(append("Listen to ", healthy).isEmpty())
        clock += 550L
        assertTrue(append("Listen to ", healthy).isEmpty())
        assertEquals(450L, buffer.nextFlushDelayMs(healthy))
        assertEquals(0L, buffer.nextFlushDelayMs(low))
        assertEquals(listOf("Listen to "), append("Listen to ", low))
        assertEquals(listOf("that sound."), buffer.append(greeting + "Listen to that sound.", true))
    }

    @Test fun streamedInitialsAndAbbreviationsStayWithTheirReferentAndQuotedSentencesStartPromptly() {
        for (term in listOf("J. R. Tolkien", "U.S. cinema", "e.g. lighting", "Fig. 2")) {
            var clock = 0L
            val buffer = VoiceSpeechBuffer(now = { clock }, preserveLiteralTerms = true)
            val reply = "Consider $term before deciding."
            val spoken = mutableListOf<String>()
            for (i in 1..reply.length) {
                clock += 130
                spoken += requireNotNull(buffer.append(reply.take(i), false, earlyStart = true, adaptive = true))
            }
            spoken += requireNotNull(buffer.append(reply, true))
            assertEquals(reply, spoken.joinToString(""))
            assertTrue("Split $term across $spoken", spoken.any { term in it })
        }
        for (quote in listOf("“Go!” ", "‘Yes.’ ", "«Oui…» ", "(Ready?) ")) {
            assertEquals(listOf(quote), VoiceSpeechBuffer(preserveLiteralTerms = true).append(quote + "Next", false, earlyStart = true))
        }
    }

    @Test fun adaptiveDeadlineStartsAtTheFirstStableWordAndSchedulesOnlyUsefulWork() {
        var now = 20L
        val buffer = VoiceSpeechBuffer { now }
        fun append(text: String) = buffer.append(text, final = false, earlyStart = true, adaptive = true)
        assertTrue(append("Toronto ").orEmpty().isEmpty())
        assertNull(buffer.nextFlushDelayMs(null))
        now += 70
        assertTrue(append("Toronto has ").orEmpty().isEmpty())
        assertEquals(50L, buffer.nextFlushDelayMs(null))
        now += 50
        assertEquals(listOf("Toronto has "), append("Toronto has "))
        assertNull(buffer.nextFlushDelayMs(null))
        assertTrue(append("Toronto has ").orEmpty().isEmpty())
        assertEquals(listOf("snow."), buffer.append("Toronto has snow.", true))
    }

    @Test fun oversizedTermsWaitForCompletionWithoutSchedulingRepeatedFlushes() {
        var now = 0L
        val buffer = VoiceSpeechBuffer { now }
        val input = "z".repeat(180) + " short words "
        assertTrue(buffer.append(input, false, earlyStart = true, adaptive = true).orEmpty().isEmpty())
        now = 200
        assertNull(buffer.nextFlushDelayMs(null))
        assertEquals(input.trimEnd(), buffer.append(input, true).orEmpty().joinToString("").trimEnd())
    }

    @Test fun aWaitingLineCannotLeakAsPartialAudioAndLiteralRequestsKeepTheirWording() {
        val prompt = "What is the capital of Canada?"
        val buffer = VoiceSpeechBuffer(skipPreamble = VoiceSpokenLead.forQuestion(prompt))
        val reply = "Sure. Let me get that info for you. Ottawa is the capital."
        val spoken = buildList {
            for (end in 1..reply.length) addAll(requireNotNull(buffer.append(reply.take(end), false, earlyStart = true, adaptive = true)))
            addAll(requireNotNull(buffer.append(reply, true)))
        }.joinToString("")
        assertEquals("Ottawa is the capital.", spoken)
        assertFalse(VoiceSpokenLead.forQuestion("What does the phrase great question mean?"))
        assertFalse(VoiceSpokenLead.forQuestion("Can you repeat exactly what I said?"))
        assertEquals("Great questions take practice.", VoiceSpokenLead.trim("Great questions take practice.", true))
        assertEquals("Let me explain.", VoiceSpokenLead.trim("Let me explain.", true))
        assertEquals("Absolutely.", VoiceSpokenLead.trim("Absolutely.", true))
        assertEquals("", VoiceSpokenLead.trim("Let me get that info for you", true))
    }

    @Test fun conversationalModesKeepTheFirstPhraseShortAndUseLargerFollowups() {
        val first = "One two three four five six seven eight "
        val followUp = "nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twentyone twentytwo twentythree twentyfour "
        val buffer = VoiceSpeechBuffer()
        val words = VoicePerformanceMode.BALANCED.followUpWords
        assertEquals(listOf(first), buffer.append(first, final = false, earlyStart = true, followUpWords = words))
        assertEquals(emptyList<String>(), buffer.append(first + "nine ten eleven ", final = false, earlyStart = true, followUpWords = words))
        assertEquals(listOf(followUp), buffer.append(first + followUp, final = false, earlyStart = true, followUpWords = words))
        assertEquals(listOf("Finished."), buffer.append(first + followUp + "Finished.", final = true, earlyStart = true, followUpWords = words))
    }

    @Test fun adaptiveOpeningPreservesANaturalClauseAndBoundsLongOpeningsWithoutLosingText() {
        val buffer = VoiceSpeechBuffer()
        val reply = "One two three four five six seven eight nine ten more words."
        val opening = buffer.append(reply, final = false, earlyStart = true, adaptive = true).orEmpty()
        assertEquals("One two three four five six ", opening.first())
        val rest = buffer.append(reply, final = true, earlyStart = true, adaptive = true).orEmpty()
        assertEquals(reply, (opening + rest).joinToString(""))
        assertEquals(listOf("Here is the answer. "), VoiceSpeechBuffer().append("Here is the answer. More", false, earlyStart = true, adaptive = true))
    }

    @Test fun openingPrefersTheCompleteClauseOverAnArbitraryFourWordFragment() {
        val buffer = VoiceSpeechBuffer()
        assertEquals(emptyList<String>(), buffer.append("The current season of ", final = false, earlyStart = true))
        assertEquals(listOf("The current season of this show is confirmed. "), buffer.append("The current season of this show is confirmed. ", final = false, earlyStart = true))
    }

    @Test fun shortCompleteAnswerStartsWithoutWaitingForEightWords() {
        assertEquals(listOf("Yes, it is. "), VoiceSpeechBuffer().append("Yes, it is. ", final = false, earlyStart = true))
        assertEquals(listOf("Yes. "), VoiceSpeechBuffer().append("Yes. ", final = false, earlyStart = true))
    }

    @Test fun readFullDetailsUsesTheExplicitProjectionWhileDefaultKeepsThemSilent() {
        val reply = "Short answer. <details><summary>More</summary>Extra detail.</details>"
        val normal = requireNotNull(VoiceSpeechBuffer().append(reply, true)).joinToString("")
        val full = requireNotNull(VoiceSpeechBuffer(includeDetails = true).append(reply, true)).joinToString("")
        assertFalse(normal.contains("Extra detail"))
        assertTrue(full.contains("Extra detail"))
    }

    @Test fun finalSizeNoticeIsProjectedOnceAndNeverAddedAsAnUnmappedExtraChunk() {
        val reply = "word ".repeat(3000)
        val buffer = VoiceSpeechBuffer()
        val spoken = requireNotNull(buffer.append(reply, false, earlyStart = true)) + requireNotNull(buffer.append(reply, true, earlyStart = true))
        assertEquals(VoiceSpeechText.project(reply), spoken.joinToString(""))
        assertEquals(1, Regex("The rest of this response is available in the chat\\.").findAll(spoken.joinToString("")).count())
        assertEquals(emptyList<String>(), buffer.append(reply, true, earlyStart = true))
    }

    @Test fun quietLimiterPreservesNonQuietSamplesAndCapsSilenceAcrossNativeBatches() {
        val limiter = VoiceQuietLimiter(sampleRate = 8000, maxQuietMs = 70)
        val first = floatArrayOf(.01f) + FloatArray(450)
        assertTrue(first.contentEquals(limiter.filter(first)))
        val second = FloatArray(200) + floatArrayOf(-.02f, .001f) + FloatArray(500)
        val expected = FloatArray(110) + floatArrayOf(-.02f, .001f) + FloatArray(500)
        assertTrue(expected.contentEquals(limiter.filter(second)))
        assertEquals(60, limiter.filter(FloatArray(100)).size)
        assertEquals(0, limiter.filter(FloatArray(100)).size)
    }

    @Test fun everyCharacterLinkBoundaryKeepsLabelsWithoutSpeakingDestinationsOrRepeatingWords() {
        val buffer = VoiceSpeechBuffer()
        val input = "See https://private.example/path?token=opaque, " +
            "[these clear reference words describe the useful documentation for this answer](https://hidden.example/a_(b)?key=secret) " +
            "and <http://another.example/details>. The answer continues."
        val expected = "See , these clear reference words describe the useful documentation for this answer and . The answer continues."
        val spoken = mutableListOf<String>()
        for (end in 1..input.length) {
            val snapshot = input.substring(0, end)
            spoken += requireNotNull(buffer.append(snapshot, false, earlyStart = true))
            assertTrue("Spoken prefix changed at $end", expected.startsWith(spoken.joinToString("")))
            assertTrue(requireNotNull(buffer.append(snapshot, false, earlyStart = true)).isEmpty())
        }
        spoken += requireNotNull(buffer.append(input, true, earlyStart = true))
        assertEquals(expected, spoken.joinToString(""))
        assertFalse(spoken.any { "http" in it || "example" in it || "opaque" in it || "secret" in it })
    }

    @Test fun nestedAndEscapedLinkLabelsStayIntactInFinalAndEveryCharacterSpeech() {
        val cases = listOf(
            "[outer [inner] label](https://example.invalid/path_(one))" to "outer inner label",
            "[escaped \\] bracket label](https://example.invalid/path)" to "escaped bracket label",
            "[escaped \\[ bracket label](https://example.invalid/path)" to "escaped bracket label"
        )
        for ((link, label) in cases) {
            val input = "Read $link. Continue."
            val expected = "Read $label. Continue."
            assertEquals(expected, VoiceSpeechText.project(input))
            val buffer = VoiceSpeechBuffer()
            val spoken = mutableListOf<String>()
            for (end in 1..input.length) {
                val snapshot = input.substring(0, end)
                spoken += requireNotNull(buffer.append(snapshot, false, earlyStart = true))
                assertTrue("Link prefix changed at $end", expected.startsWith(spoken.joinToString("")))
                assertTrue(requireNotNull(buffer.append(snapshot, false, earlyStart = true)).isEmpty())
            }
            spoken += requireNotNull(buffer.append(input, true, earlyStart = true))
            assertEquals(expected, spoken.joinToString(""))
        }
    }

    @Test fun streamedFencedCodeNeverEntersSpeechAndProseAfterTheFenceResumes() {
        val buffer = VoiceSpeechBuffer()
        val input = "Readable introduction. \n```kotlin\nval opaqueIdentifier = \"https://private.example\"\n```\nRead the closing explanation."
        val spoken = mutableListOf<String>()
        for (end in 1..input.length) {
            spoken += requireNotNull(buffer.append(input.substring(0, end), false, earlyStart = true))
            assertFalse(spoken.any { "opaque" in it || "http" in it || "kotlin" in it })
        }
        spoken += requireNotNull(buffer.append(input, true, earlyStart = true))
        assertEquals("Readable introduction. Code is available in the chat. Read the closing explanation.", spoken.joinToString("").replace(Regex("\\s+"), " ").trim())
    }

    @Test fun finalFencedExamplesRequireMatchingLineDelimitersBeforeRestoringProse() {
        for (input in fencedExamples()) {
            val projected = VoiceSpeechText.project(input).replace(Regex("\\s+"), " ").trim()
            assertEquals("Intro. Code is available in the chat. Closing prose.", projected)
            val spoken = requireNotNull(VoiceSpeechBuffer().append(input, true, earlyStart = true))
            assertEquals("Intro. Code is available in the chat. Closing prose.", spoken.joinToString("").replace(Regex("\\s+"), " ").trim())
        }
    }

    @Test fun everyCharacterFencedExampleKeepsCodeSilentAndRestoresClosingProseOnce() {
        for (input in fencedExamples()) {
            val buffer = VoiceSpeechBuffer()
            val spoken = mutableListOf<String>()
            for (end in 1..input.length) {
                val snapshot = input.substring(0, end)
                spoken += requireNotNull(buffer.append(snapshot, false, earlyStart = true))
                val prefix = spoken.joinToString("").replace(Regex("\\s+"), " ").trim()
                assertTrue("Code escaped at character $end", "Intro. Code is available in the chat. Closing prose.".startsWith(prefix))
                assertTrue(requireNotNull(buffer.append(snapshot, false, earlyStart = true)).isEmpty())
            }
            spoken += requireNotNull(buffer.append(input, true, earlyStart = true))
            assertEquals("Intro. Code is available in the chat. Closing prose.", spoken.joinToString("").replace(Regex("\\s+"), " ").trim())
        }
    }

    private fun fencedExamples() = listOf(
        "Intro.\n```python\nprint(\"```\")\nopaqueCodeShouldStaySilent()\n```\nClosing prose.",
        "Intro.\n````markdown\n```python\nopaqueCodeShouldStaySilent()\n```\n````\nClosing prose.",
        "Intro.\n~~~text\nquoted(\"~~~\")\n~~~ not a closing fence\n```\nopaqueCodeShouldStaySilent()\n   ~~~~ \t\nClosing prose.",
        "Intro.\n  ```python\r\n    ```\r\nopaqueCodeShouldStaySilent()\r\n   ``` \t\r\nClosing prose."
    )

    @Test fun incompleteMarkupStaysBoundedAndFinishesWithoutReadingItsDestinationOrCode() {
        val link = VoiceSpeechBuffer()
        val unfinished = "Keep this sentence. [Useful label](https://private.example/" + "opaque".repeat(3000)
        assertEquals(listOf("Keep this sentence. "), link.append(unfinished, false, earlyStart = true))
        assertEquals("Useful label The rest of this response is available in the chat.", requireNotNull(link.append(unfinished, true, earlyStart = true)).joinToString("").trim())
        val code = VoiceSpeechBuffer()
        assertEquals("Keep this sentence. Code is available in the chat.", requireNotNull(code.append("Keep this sentence. ```unclosed code", true, earlyStart = true)).joinToString("").trim())
        assertEquals("Code is available in the chat.", requireNotNull(VoiceSpeechBuffer().append("```only code", true, earlyStart = true)).joinToString("").trim())
    }

    @Test fun revisingOnlyUnspokenTextKeepsTheQueuedSentence() {
        val buffer = VoiceSpeechBuffer()
        assertEquals(listOf("Ready. "), buffer.append("Ready. First draft", false))
        assertEquals(listOf("Corrected ending."), buffer.append("Ready. Corrected ending.", true))
        assertNull(buffer.append("Earlier words changed.", true))
    }

    @Test fun terminalWhitespaceTrimmingDoesNotInvalidateQueuedSpeech() {
        val buffer = VoiceSpeechBuffer()
        assertEquals(listOf("A completed sentence. "), buffer.append("A completed sentence. ", false, earlyStart = true))
        assertEquals(emptyList<String>(), buffer.append("A completed sentence.", true, earlyStart = true))
        assertNull(buffer.append("A changed sentence.", true, earlyStart = true))
    }

    @Test fun repeatedSnapshotsSpeakEachSentenceOnceAndRetainTail() {
        val buffer = VoiceSpeechBuffer()
        assertTrue(buffer.append("Hello", false)!!.isEmpty())
        assertEquals(listOf("Hello. "), buffer.append("Hello. Next", false))
        assertTrue(buffer.append("Hello. Next", false)!!.isEmpty())
        assertEquals(listOf("Next sentence"), buffer.append("Hello. Next sentence", true))
        assertTrue(buffer.append("Hello. Next sentence", true)!!.isEmpty())
    }

    @Test fun revisionStopsFurtherSpokenClaims() {
        val buffer = VoiceSpeechBuffer()
        buffer.append("Original. ", false)
        assertNull(buffer.append("Revised answer.", true))
    }

    @Test fun streamingContinuesThroughAnUnpunctuatedSentenceWithoutRepeatsOrLostWords() {
        val buffer = VoiceSpeechBuffer()
        val spoken = mutableListOf<String>()
        var snapshot = ""
        for (index in 1..44) {
            snapshot += "word$index "
            spoken += requireNotNull(buffer.append(snapshot, false, earlyStart = true))
            assertTrue(buffer.append(snapshot, false, earlyStart = true)!!.isEmpty())
            if (index == 8) assertEquals(1, spoken.size)
            if (index == 20) assertEquals(2, spoken.size)
            if (index == 32) assertEquals(3, spoken.size)
        }
        spoken += requireNotNull(buffer.append(snapshot + "finished", true, earlyStart = true))
        assertEquals(snapshot + "finished", spoken.joinToString(""))
    }

    @Test fun continuationUsesStableClausesAndBoundedCompleteWordsIncludingEmoji() {
        val buffer = VoiceSpeechBuffer()
        val opening = "A short first clause, "
        assertEquals(listOf(opening), buffer.append(opening, false, earlyStart = true))
        val clause = "another useful clause, "
        assertEquals(listOf(clause), buffer.append(opening + clause, false, earlyStart = true))
        val tail = ("🗣" + "longword".repeat(10) + " ").repeat(10)
        val chunks = requireNotNull(buffer.append(opening + clause + tail, false, earlyStart = true))
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.length <= 240 && it.last().isWhitespace() })
        val final = requireNotNull(buffer.append(opening + clause + tail, true, earlyStart = true))
        assertEquals(tail, (chunks + final).joinToString(""))
    }

    @Test fun partialTitleAndNumberWaitForTheWholeTermAcrossProviderStalls() {
        val source = "Dr. Chen checked the FP32 GPU for 3.5 milliseconds. Next."
        var clock = 0L
        val b = VoiceSpeechBuffer { clock }
        val pieces = mutableListOf<String>()
        for (end in 1..source.length) {
            val text = source.take(end)
            pieces += requireNotNull(b.append(text, false, true, adaptive = true))
            clock += 180L
            pieces += requireNotNull(b.append(text, false, true, adaptive = true))
        }
        pieces += requireNotNull(b.append(source, true))
        assertEquals(source, pieces.joinToString(""))
        assertTrue(pieces.any { "Dr. Chen" in it })
        assertTrue(pieces.any { "3.5 milliseconds" in it })
    }
}
