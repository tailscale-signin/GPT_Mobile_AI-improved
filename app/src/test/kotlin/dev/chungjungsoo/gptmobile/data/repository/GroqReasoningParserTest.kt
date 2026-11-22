package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroqReasoningParserTest {
    @Test
    fun `parsed reasoning is emitted separately from content`() {
        val parser = GroqReasoningParser()

        val emitted = parser.append(
            reasoningChunk = "Thoughts",
            contentChunk = "Answer"
        ) + parser.flush()

        assertEquals(
            listOf(
                ApiState.Thinking("Thoughts"),
                ApiState.Success("Answer", OutputLineage(OutputOrigin.MODEL_TEXT))
            ),
            emitted
        )
    }

    @Test
    fun `raw think tags are extracted into thinking state`() {
        val parser = GroqReasoningParser()

        val emitted = parser.append(contentChunk = "<think>Reasoning</think>Answer") + parser.flush()

        assertEquals(
            listOf(
                ApiState.Thinking("Reasoning"),
                ApiState.Success("Answer", OutputLineage(OutputOrigin.MODEL_TEXT))
            ),
            emitted
        )
    }

    @Test
    fun `empty think block does not leave blank answer gap`() {
        val parser = GroqReasoningParser()

        val emitted = parser.append(contentChunk = "<think>   \n </think>\n\nAnswer") + parser.flush()

        assertEquals(
            listOf(
                ApiState.Success("Answer", OutputLineage(OutputOrigin.MODEL_TEXT))
            ),
            emitted
        )
    }

    @Test
    fun `split think tags across chunks are reconstructed`() {
        val parser = GroqReasoningParser()

        val emitted = buildList {
            addAll(parser.append(contentChunk = "<thi"))
            addAll(parser.append(contentChunk = "nk>Plan"))
            addAll(parser.append(contentChunk = "</thi"))
            addAll(parser.append(contentChunk = "nk> Answer"))
            addAll(parser.flush())
        }

        assertEquals(
            listOf(
                ApiState.Thinking("Plan"),
                ApiState.Success("Answer", OutputLineage(OutputOrigin.MODEL_TEXT))
            ),
            emitted
        )
    }

    @Test
    fun `unclosed think block remains reasoning on completion`() {
        val parser = GroqReasoningParser()

        val emitted = parser.append(contentChunk = "<think>unfinished") + parser.flush()

        assertEquals(
            listOf(
                ApiState.Thinking("unfinished")
            ),
            emitted
        )
    }

    @Test
    fun `reasoningChunk passes through while content is empty`() {
        val parser = GroqReasoningParser()

        val emitted = parser.append(reasoningChunk = "Thinking step 1") +
            parser.append(reasoningChunk = " step 2") +
            parser.flush()

        assertEquals(
            listOf(
                ApiState.Thinking("Thinking step 1"),
                ApiState.Thinking(" step 2")
            ),
            emitted
        )
    }

    @Test
    fun `every split of leading mixed-case tags keeps reasoning out of answer`() {
        val source = " \n<ThInK>private planning</tHiNk>\nThe answer is ready."
        for (split in 0..source.length) {
            val parser = GroqReasoningParser()
            val states = parser.append(source.take(split)) + parser.append(source.drop(split)) + parser.flush()
            assertEquals("split=$split", "The answer is ready.", states.filterIsInstance<ApiState.Success>().joinToString("") { it.textChunk })
            assertEquals("split=$split", "private planning", states.filterIsInstance<ApiState.Thinking>().joinToString("") { it.thinkingChunk })
        }
    }

    @Test
    fun `quoted fenced escaped and prose tag examples remain literal at every boundary`() {
        val examples = listOf(
            "`<think>literal</think>`",
            "```xml\n<think>literal</think>\n```",
            "\"<think>literal</think>\"",
            "> <think>quoted</think>",
            "The syntax is <think>literal</think>.",
            "&lt;think&gt;literal&lt;/think&gt;"
        )
        for (source in examples) {
            for (split in 0..source.length) {
                val parser = GroqReasoningParser()
                val states = parser.append(source.take(split)) + parser.append(source.drop(split)) + parser.flush()
                assertEquals("source=$source split=$split", source, states.filterIsInstance<ApiState.Success>().joinToString("") { it.textChunk })
                assertTrue(states.none { it is ApiState.Thinking })
            }
        }
    }

    @Test
    fun `subject metadata can precede leading reasoning without exposing it`() {
        val subject = "<!-- chat_subject: Voice answer -->"
        val source = "$subject\n<think>private</think> Answer."
        for (split in 0..source.length) {
            val parser = GroqReasoningParser()
            val states = parser.append(source.take(split)) + parser.append(source.drop(split)) + parser.flush()
            assertEquals("$subject" + "Answer.", states.filterIsInstance<ApiState.Success>().joinToString("") { it.textChunk })
            assertEquals("private", states.filterIsInstance<ApiState.Thinking>().joinToString("") { it.thinkingChunk })
        }
    }

    @Test
    fun `long interrupted reasoning is delivered only through reasoning channel`() {
        val parser = GroqReasoningParser()
        val thought = "x".repeat(20_000)
        val states = ("<think>" + thought).chunked(257).flatMap { parser.append(it) } + parser.flush()
        assertTrue(states.none { it is ApiState.Success })
        assertEquals(thought, states.filterIsInstance<ApiState.Thinking>().joinToString("") { it.thinkingChunk })
        assertTrue(parser.flush().isEmpty())
    }

    @Test
    fun `incomplete opening delimiter cannot become spoken text`() {
        for (prefix in listOf("<th", "<thi", "<think")) {
            val parser = GroqReasoningParser()
            assertTrue((parser.append(prefix) + parser.flush()).none { it is ApiState.Success })
        }
    }

    @Test
    fun `literal tags after an answer begins are not reclassified`() {
        val parser = GroqReasoningParser()
        val states = parser.append("<think>private</think>Here is ") + parser.append("<think>literal</think>.") + parser.flush()
        assertEquals("Here is <think>literal</think>.", states.filterIsInstance<ApiState.Success>().joinToString("") { it.textChunk })
        assertEquals("private", states.filterIsInstance<ApiState.Thinking>().joinToString("") { it.thinkingChunk })
    }
}
