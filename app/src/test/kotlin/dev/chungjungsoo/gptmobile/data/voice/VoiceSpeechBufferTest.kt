package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechBufferTest {
    @Test fun everyCharacterLinkBoundaryKeepsLabelsWithoutSpeakingDestinationsOrRepeatingWords() {
        val buffer = VoiceSpeechBuffer()
        val input = "See https://private.example/path?token=opaque, " +
            "[these clear reference words describe the useful documentation for this answer](https://hidden.example/a_(b)?key=secret) " +
            "and <http://another.example/details>. The answer continues."
        val expected = "See link, these clear reference words describe the useful documentation for this answer and link. The answer continues."
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
        assertEquals(
            listOf("Useful label", "The rest of this response is available in the chat."),
            link.append(unfinished, true, earlyStart = true)
        )
        val code = VoiceSpeechBuffer()
        assertEquals("Keep this sentence. Code is available in the chat.", requireNotNull(code.append("Keep this sentence. ```unclosed code", true, earlyStart = true)).joinToString("").trim())
        assertEquals("Code is available in the chat.", requireNotNull(VoiceSpeechBuffer().append("```only code", true, earlyStart = true)).joinToString("").trim())
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
}
