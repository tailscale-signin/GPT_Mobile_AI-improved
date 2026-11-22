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

    @Test
    fun `literal reasoning tags stay hidden at every split boundary`() {
        val thoughts = listOf(
            "Example is \"</think>\" but PRIVATE_SECRET remains.",
            "Study the literal '<think>' token; PRIVATE_SECRET remains.",
            "Example is 'it isn't </think> but PRIVATE_SECRET remains' before finishing.",
            "Example is ‘it isn’t </think> but PRIVATE_SECRET remains’ before finishing.",
            "The tokens “</think>” and ‘<think>’ are PRIVATE_SECRET.",
            "Do not output `</think>` or ``<think>``; PRIVATE_SECRET remains.",
            "Escaped \\</think> and \\<think> are PRIVATE_SECRET.",
            "\n```xml\n</think>\n<think>\n```\nPRIVATE_SECRET remains.",
            "\n~~~xml\n</think>\n<think>\n~~~\nPRIVATE_SECRET remains.",
            "\n    </think>\n    <think>\nPRIVATE_SECRET remains.",
            "\n    1.. </think> PRIVATE_SECRET\nReasoning continues.",
            "\n    ####### </think> PRIVATE_SECRET\nReasoning continues.",
            "It isn't public; users' PRIVATE_SECRET remains.",
            "\n    - It isn't public; PRIVATE_SECRET remains.",
            "Nested <think>PRIVATE_SECRET</think> remains hidden."
        )
        for (thought in thoughts) {
            val input = "<think>$thought</think>Answer."
            for (split in 0..input.length) {
                val parser = GroqReasoningParser()
                val states = parser.append(input.take(split)) + parser.append(input.drop(split)) + parser.flush()
                assertEquals("split=$split input=$input", "Answer.", states.filterIsInstance<ApiState.Success>().joinToString("") { it.textChunk })
                assertEquals("split=$split input=$input", thought.trim(), states.filterIsInstance<ApiState.Thinking>().joinToString("") { it.thinkingChunk })
            }
            val parser = GroqReasoningParser()
            val states = input.map { it.toString() }.flatMap { parser.append(it) } + parser.flush()
            assertEquals(input, "Answer.", states.filterIsInstance<ApiState.Success>().joinToString("") { it.textChunk })
            assertEquals(input, thought.trim(), states.filterIsInstance<ApiState.Thinking>().joinToString("") { it.thinkingChunk })
        }
    }

    @Test
    fun `literal state survives character chunks and bounded reasoning delivery`() {
        val thought = "\"" + "x".repeat(9000) + "</think> PRIVATE_SECRET\" then `</think>` and nested <think>private</think>."
        val parser = GroqReasoningParser()
        val states = ("<think>$thought</think>Answer.").map { it.toString() }.flatMap { parser.append(it) } + parser.flush()
        assertEquals("Answer.", states.filterIsInstance<ApiState.Success>().joinToString("") { it.textChunk })
        assertEquals(thought, states.filterIsInstance<ApiState.Thinking>().joinToString("") { it.thinkingChunk })
        assertTrue(states.filterIsInstance<ApiState.Thinking>().size > 1)
    }

    @Test
    fun `unfinished quoted and code reasoning stays reasoning at every boundary`() {
        for (literal in listOf("\"", "'", "“", "‘", "`", "``", "```xml\n", "~~~xml\n")) {
            val thought = "${literal}literal </think> PRIVATE_SECRET"
            val input = "<think>$thought"
            for (split in 0..input.length) {
                val parser = GroqReasoningParser()
                val states = parser.append(input.take(split)) + parser.append(input.drop(split)) + parser.flush()
                assertTrue("split=$split input=$input", states.none { it is ApiState.Success })
                assertEquals(thought, states.filterIsInstance<ApiState.Thinking>().joinToString("") { it.thinkingChunk })
            }
        }
    }
}
