package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadFileSliceToolTest {

    private val tool = ReadFileSliceTool()

    @Test
    fun `execute returns formatted lines with line numbers`() = runTest {
        val content = """
            line 1
            line 2
            line 3
            line 4
            line 5
        """.trimIndent()

        val args = buildJsonObject {
            put("content", content)
            put("start_line", 2)
            put("end_line", 4)
        }

        val result = tool.execute("call-1", args)
        assertFalse(result.isError)
        val text = (result.content as ToolResultContent.Text).value
        val expected = """
            Lines 2-4 of 5:
            2: line 2
            3: line 3
            4: line 4
        """.trimIndent()
        assertEquals(expected, text)
    }

    @Test
    fun `execute clamps end_line to total line count`() = runTest {
        val content = "alpha\nbeta\ngamma"
        val args = buildJsonObject {
            put("content", content)
            put("start_line", 2)
            put("end_line", 100)
        }

        val result = tool.execute("call-2", args)
        assertFalse(result.isError)
        val text = (result.content as ToolResultContent.Text).value
        val expected = """
            Lines 2-3 of 3:
            2: beta
            3: gamma
        """.trimIndent()
        assertEquals(expected, text)
    }

    @Test
    fun `execute returns single line header when start equals end`() = runTest {
        val content = "first\nsecond\nthird"
        val args = buildJsonObject {
            put("content", content)
            put("start_line", 1)
            put("end_line", 1)
        }

        val result = tool.execute("call-3", args)
        assertFalse(result.isError)
        val text = (result.content as ToolResultContent.Text).value
        val expected = """
            Line 1 of 3:
            1: first
        """.trimIndent()
        assertEquals(expected, text)
    }

    @Test
    fun `execute returns error when content is empty`() = runTest {
        val args = buildJsonObject {
            put("content", "")
            put("start_line", 1)
            put("end_line", 1)
        }

        val result = tool.execute("call-4", args)
        assertTrue(result.isError)
        val text = (result.content as ToolResultContent.Text).value
        assertEquals("Provided content cannot be empty.", text)
    }

    @Test
    fun `execute returns error when start_line is less than 1`() = runTest {
        val args = buildJsonObject {
            put("content", "hello\nworld")
            put("start_line", 0)
            put("end_line", 2)
        }

        val result = tool.execute("call-5", args)
        assertTrue(result.isError)
        val text = (result.content as ToolResultContent.Text).value
        assertEquals("start_line must be >= 1, received: 0", text)
    }

    @Test
    fun `execute returns error when start_line exceeds end_line`() = runTest {
        val args = buildJsonObject {
            put("content", "hello\nworld")
            put("start_line", 2)
            put("end_line", 1)
        }

        val result = tool.execute("call-6", args)
        assertTrue(result.isError)
        val text = (result.content as ToolResultContent.Text).value
        assertEquals("start_line cannot be greater than end_line (2 > 1).", text)
    }

    @Test
    fun `execute returns error when start_line exceeds total line count`() = runTest {
        val args = buildJsonObject {
            put("content", "hello\nworld")
            put("start_line", 5)
            put("end_line", 10)
        }

        val result = tool.execute("call-7", args)
        assertTrue(result.isError)
        val text = (result.content as ToolResultContent.Text).value
        assertEquals("start_line (5) exceeds total line count (2).", text)
    }
}
