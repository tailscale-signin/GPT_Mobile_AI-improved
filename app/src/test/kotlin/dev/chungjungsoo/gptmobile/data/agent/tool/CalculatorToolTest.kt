package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorToolTest {

    private val tool = CalculatorTool()

    @Test
    fun evaluatesBasicArithmetic() = runTest {
        val result = tool.execute(
            callId = "call_1",
            arguments = buildJsonObject {
                put("expression", "2 + 3 * 4")
            }
        )
        assertFalse(result.isError)
        val json = result.content as dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Json
        assertEquals("14", json.value["formatted"]?.jsonPrimitive?.content)
    }

    @Test
    fun evaluatesParenthesesAndExponents() = runTest {
        val result = tool.execute(
            callId = "call_2",
            arguments = buildJsonObject {
                put("expression", "(2 + 3) * 2^3")
            }
        )
        assertFalse(result.isError)
        val json = result.content as dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Json
        assertEquals("40", json.value["formatted"]?.jsonPrimitive?.content)
    }

    @Test
    fun evaluatesMathFunctionsAndConstants() = runTest {
        val result = tool.execute(
            callId = "call_3",
            arguments = buildJsonObject {
                put("expression", "sqrt(16) + min(10, 5) * cos(0)")
            }
        )
        assertFalse(result.isError)
        val json = result.content as dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Json
        assertEquals("9", json.value["formatted"]?.jsonPrimitive?.content)
    }

    @Test
    fun handlesDivisionByZeroGracefully() = runTest {
        val result = tool.execute(
            callId = "call_4",
            arguments = buildJsonObject {
                put("expression", "10 / 0")
            }
        )
        assertTrue(result.isError)
        val json = result.content as dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Json
        assertTrue(json.value["error"]?.jsonPrimitive?.content?.contains("Division by zero") == true)
    }

    @Test
    fun handlesInvalidSyntaxGracefully() = runTest {
        val result = tool.execute(
            callId = "call_5",
            arguments = buildJsonObject {
                put("expression", "2 + * 3")
            }
        )
        assertTrue(result.isError)
        val json = result.content as dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Json
        assertTrue(json.value.containsKey("error"))
    }
}
