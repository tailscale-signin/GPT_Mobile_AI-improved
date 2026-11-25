package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStreamingPhrasePlanTest {
    @Test fun aLongSavedReplyIsSplitBeforeInferenceWithoutLosingItsText() {
        val text = "A complete sentence with useful details. ".repeat(120).trimEnd()
        val plan = VoiceStreamingPhrasePlan(text, emptyMap(), .5f)
        val phrases = generateSequence { plan.next(8) }.toList()
        assertTrue(phrases.size > 50)
        assertTrue(phrases.all { it.text.length <= 64 })
        assertEquals(text, phrases.joinToString("") { it.text })
        for (phrase in phrases) {
            assertEquals(phrase.text, phrase.sourceCharacters.joinToString("") { text.substring(it) })
        }
    }

    @Test fun pronunciationExpansionsKeepOriginalCaptionOffsetsInLaterPhrases() {
        val text = "Here are the first words. Sentoria is ready."
        val plan = VoiceStreamingPhrasePlan(text, mapOf("sentoria" to "sen toria"), 1f)
        val phrases = generateSequence { plan.next(4) }.toList()
        assertTrue(phrases.size > 1)
        assertEquals("Here are the first words. sen toria is ready.", phrases.joinToString("") { it.text })
        val phrase = phrases.first { "sen" in it.text }
        val start = phrase.text.indexOf("sen")
        val startByte = phrase.text.substring(0, start).toByteArray(Charsets.UTF_8).size
        val mapped = VoiceStreamFrames(phrase.text, sourceCharacters = phrase.sourceCharacters).accept(
            VoicePcmChunk(floatArrayOf(.1f), wordCues = listOf(VoiceWordCue(startByte, startByte + 3, 0, 1)))
        )
        val original = text.indexOf("Sentoria")
        assertEquals(original until original + "Sentoria".length, mapped.single().range)
    }

    @Test fun unbrokenUnicodeTextDoesNotSplitSurrogatesOrDropCombiningMarks() {
        val text = "🙂xe\u0301 ".repeat(100).trimEnd()
        val plan = VoiceStreamingPhrasePlan(text, emptyMap(), .5f)
        val phrases = generateSequence { plan.next(8) }.toList()
        assertEquals(text, phrases.joinToString("") { it.text })
        assertTrue(phrases.size > 1)
        assertTrue(phrases.all { it.text.length <= 64 })
        assertTrue(phrases.all { !it.text.first().isLowSurrogate() && !it.text.last().isHighSurrogate() })
        assertTrue(phrases.all { it.text.first() != '\u0301' })
        assertFalse(phrases.any { it.text.isBlank() })
    }

    @Test fun sdkCorrectionsUseFullSymbolicTermsAndKeepOriginalWordCues() {
        val text = "São uses C++ and C# with .NET."
        val plan = VoiceStreamingPhrasePlan(text, mapOf("São" to "sow", "C++" to "see plus plus", "C#" to "see sharp", ".NET" to "dot net"), 1f)
        val phrases = generateSequence { plan.next(4) }.toList()
        assertEquals("sow uses see plus plus and see sharp with dot net.", phrases.joinToString("") { it.text })
        val phrase = phrases.first { "sharp" in it.text }
        val start = phrase.text.indexOf("sharp")
        val range = phrase.sourceCharacters[start]
        assertEquals("C#", text.substring(range))
        assertTrue(phrase.sourceCharacters.subList(start, start + 5).all { it == range })
    }

    @Test fun aPronunciationGroupMustFitWholeWithinTheRateDependentCharacterCeiling() {
        val input = "Sentoria"
        val pronunciation = List(8) { "pronouncing" }.joinToString(" ")
        val overrides = mapOf(input to pronunciation)
        assertEquals(95, pronunciation.length)
        assertTrue(VoicePronunciationOverrides.valid(input, pronunciation))

        val slow = VoiceStreamingPhrasePlan(input, overrides, .5f)
        val failure = assertThrows(IllegalArgumentException::class.java) { slow.next(4) }
        assertEquals("STREAM_INPUT_LIMIT", failure.message)

        val normal = VoiceStreamingPhrasePlan(input, overrides, 1f)
        val phrase = normal.next(4)!!
        assertEquals(pronunciation, phrase.text)
        assertTrue(phrase.text.length <= 128)
        assertEquals(List(pronunciation.length) { input.indices }, phrase.sourceCharacters)
        assertEquals(null, normal.next(4))
    }

    @Test fun separatorAfterTheExactCharacterCeilingRemainsInTheFollowingChunk() {
        val input = "a".repeat(128) + " b"
        val plan = VoiceStreamingPhrasePlan(input, emptyMap(), 1f)
        val first = plan.next(8)!!
        val second = plan.next(8)!!

        assertEquals("a".repeat(128), first.text)
        assertEquals(" b", second.text)
        assertEquals(listOf(128..128, 129..129), second.sourceCharacters)
        assertEquals(input, first.text + second.text)
        assertEquals(null, plan.next(8))
    }

    @Test fun repeatedWhitespaceBoundariesRemainExactAtEveryCharacterCeiling() {
        for ((speed, ceiling) in listOf(.5f to 64, 1f to 128, 1.5f to 192)) {
            for (separator in listOf(" ", "\t", "\n", " \t\n")) {
                val input = "a".repeat(ceiling) + separator + "b".repeat(ceiling - separator.length) + separator + "c".repeat(ceiling - separator.length) + separator + "end"
                val plan = VoiceStreamingPhrasePlan(input, emptyMap(), speed)
                val phrases = generateSequence { plan.next(4) }.toList()

                assertEquals(input, phrases.joinToString("") { it.text })
                assertTrue(phrases.all { it.text.length <= ceiling && it.text.isNotBlank() })
                for (phrase in phrases) {
                    assertEquals(phrase.text, phrase.sourceCharacters.joinToString("") { input.substring(it) })
                }
            }
        }
    }

    @Test fun anUnbrokenWordDoesNotGainASeparatorAtTheSameCharacterBoundary() {
        for (length in listOf(1, 64, 127, 128)) {
            val input = "a".repeat(length - 1) + "b"
            val plan = VoiceStreamingPhrasePlan(input, emptyMap(), 1f)
            val phrases = generateSequence { plan.next(8) }.toList()

            assertEquals(input, phrases.joinToString("") { it.text })
            assertTrue(phrases.all { it.text.length <= 128 && it.text.none(Char::isWhitespace) })
            assertEquals(input.indices.map { it..it }, phrases.flatMap { it.sourceCharacters })
        }
    }

    @Test fun aWhitespaceTailDoesNotCreateAStandaloneSpeechRequest() {
        val plan = VoiceStreamingPhrasePlan("a".repeat(128) + " \t\n", emptyMap(), 1f)

        assertEquals("a".repeat(128), plan.next(8)!!.text)
        assertEquals(null, plan.next(8))
        assertEquals(null, plan.next(8))
        assertEquals(null, VoiceStreamingPhrasePlan(" \t\n", emptyMap(), 1f).next(8))
    }

    @Test fun aSeparatorMustFitWithSpeechWithoutWideningTheCharacterCeiling() {
        val fitting = "a".repeat(128) + " ".repeat(127) + "b"
        val fittingPlan = VoiceStreamingPhrasePlan(fitting, emptyMap(), 1f)
        val fittingPhrases = generateSequence { fittingPlan.next(8) }.toList()
        assertEquals(fitting, fittingPhrases.joinToString("") { it.text })
        assertTrue(fittingPhrases.all { it.text.length <= 128 && it.text.isNotBlank() })

        for (tail in listOf(" ".repeat(128) + "b", " ".repeat(127) + "🙂")) {
            val plan = VoiceStreamingPhrasePlan("a".repeat(128) + tail, emptyMap(), 1f)
            assertEquals("a".repeat(128), plan.next(8)!!.text)
            val failure = assertThrows(IllegalArgumentException::class.java) { plan.next(8) }
            assertEquals("STREAM_INPUT_LIMIT", failure.message)
        }
    }
}
