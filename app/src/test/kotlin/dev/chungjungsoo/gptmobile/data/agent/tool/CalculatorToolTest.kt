package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorToolTest {

    private val tool = CalculatorTool()

    @Test
    fun evaluatesAdvertisedDigitBearingLogarithmInActualToolCalls() = runTest {
        for ((expression, expected) in listOf("log2(8)" to "3", "LOG2 (32)" to "5", "log2(pow(2, 6)) + log(100)" to "8")) {
            val result = tool.execute("logarithm", buildJsonObject { put("expression", expression) })
            assertFalse(expression, result.isError)
            val data = result.content as dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Json
            assertEquals(expression, expected, data.value.jsonObject["formatted"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun unknownDigitBearingIdentifiersCannotAliasFunctionsOrConstants() = runTest {
        for (expression in listOf("log20(8)", "sqrt2(16)", "log2extra(8)", "pi2(3)", "e2")) {
            val result = tool.execute("unknown-name", buildJsonObject { put("expression", expression) })
            assertTrue(expression, result.isError)
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { CalculatorTool.evaluate(expression) }
        }
    }

    @Test
    fun digitBearingFunctionRetainsArityAndDomainValidation() = runTest {
        for ((expression, expectedError) in listOf("log2()" to "expects 1 argument", "log2(8, 2)" to "expects 1 argument", "log2(0)" to "Logarithm of non-positive number", "log2(-8)" to "Logarithm of non-positive number")) {
            val result = tool.execute("invalid-logarithm", buildJsonObject { put("expression", expression) })
            assertTrue(expression, result.isError)
            val data = result.content as dev.chungjungsoo.gptmobile.data.agent.ToolResultContent.Json
            assertTrue(expression, data.value.jsonObject["error"]?.jsonPrimitive?.content?.contains(expectedError) == true)
        }
    }

    @Test
    fun supportsRandomExpressionFromDiagnosticsAndRejectsArguments() {
        repeat(100) {
            val result = CalculatorTool.evaluate("floor(random()*200000)+1")
            assertTrue(result in 1.0..200000.0)
            assertEquals(result, kotlin.math.floor(result), 0.0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { CalculatorTool.evaluate("random(10)") }
    }

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
        assertEquals("14", json.value.jsonObject["formatted"]?.jsonPrimitive?.content)
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
        assertEquals("40", json.value.jsonObject["formatted"]?.jsonPrimitive?.content)
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
        assertEquals("9", json.value.jsonObject["formatted"]?.jsonPrimitive?.content)
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
        assertTrue(json.value.jsonObject["error"]?.jsonPrimitive?.content?.contains("Division by zero") == true)
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
        assertTrue(json.value.jsonObject.containsKey("error"))
    }
}
