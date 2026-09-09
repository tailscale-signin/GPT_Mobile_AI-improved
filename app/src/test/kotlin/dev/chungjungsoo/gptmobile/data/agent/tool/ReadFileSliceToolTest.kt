package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadFileSliceToolTest {

    private val tool = ReadFileSliceTool()

    @Test
    fun `extracts bounded line slice accurately with line numbers`() = runBlocking {
        val content = """
            fun first() = 1
            fun second() = 2
            fun third() = 3
            fun fourth() = 4
            fun fifth() = 5
        """.trimIndent()

        val args = buildJsonObject {
            put("content", content)
            put("start_line", 2)
            put("end_line", 4)
        }

        val result = tool.execute("call-1", args)
        assertFalse(result.isError)
        val text = (result.content as ToolResultContent.Text).text
        val expected = """
            Lines 2-4 of 5:
            2: fun second() = 2
            3: fun third() = 3
            4: fun fourth() = 4
        """.trimIndent()
        assertEquals(expected, text)
    }

    @Test
    fun `extracts single line slice`() = runBlocking {
        val content = "line 1\nline 2\nline 3"
        val args = buildJsonObject {
            put("content", content)
            put("start_line", 2)
            put("end_line", 2)
        }

        val result = tool.execute("call-2", args)
        assertFalse(result.isError)
        val text = (result.content as ToolResultContent.Text).text
        val expected = """
            Line 2 of 3:
            2: line 2
        """.trimIndent()
        assertEquals(expected, text)
    }

    @Test
    fun `caps end_line to total lines gracefully`() = runBlocking {
        val content = "alpha\nbeta\ngamma"
        val args = buildJsonObject {
            put("content", content)
            put("start_line", 2)
            put("end_line", 100)
        }

        val result = tool.execute("call-3", args)
        assertFalse(result.isError)
        val text = (result.content as ToolResultContent.Text).text
        val expected = """
            Lines 2-3 of 3:
            2: beta
            3: gamma
        """.trimIndent()
        assertEquals(expected, text)
    }

    @Test
    fun `rejects invalid start line smaller than 1`() = runBlocking {
        val args = buildJsonObject {
            put("content", "hello\nworld")
            put("start_line", 0)
            put("end_line", 1)
        }

        val result = tool.execute("call-4", args)
        assertTrue(result.isError)
        val text = (result.content as ToolResultContent.Text).text
        assertTrue(text.contains("start_line must be >= 1"))
    }

    @Test
    fun `rejects start_line greater than end_line`() = runBlocking {
        val args = buildJsonObject {
            put("content", "hello\nworld")
            put("start_line", 5)
            put("end_line", 2)
        }

        val result = tool.execute("call-5", args)
        assertTrue(result.isError)
        val text = (result.content as ToolResultContent.Text).text
        assertTrue(text.contains("start_line cannot be greater than end_line"))
    }

    @Test
    fun `rejects empty content`() = runBlocking {
        val args = buildJsonObject {
            put("content", "")
            put("start_line", 1)
            put("end_line", 5)
        }

        val result = tool.execute("call-6", args)
        assertTrue(result.isError)
        val text = (result.content as ToolResultContent.Text).text
        assertTrue(text.contains("cannot be empty"))
    }
}
