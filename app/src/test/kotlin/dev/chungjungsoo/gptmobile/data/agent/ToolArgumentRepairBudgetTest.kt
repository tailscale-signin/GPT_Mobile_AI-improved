package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentRepairBudgetTest {
    private val schema = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put(
            "properties",
            buildJsonObject {
                put("action", buildJsonObject { put("type", "string") })
                put("issue_number", buildJsonObject { put("type", "integer") })
            }
        )
    }
    private val definition = AgentToolDefinition("github_issue__account", "Issue fixture", schema)

    @Test fun repeatedConstraintIsBoundedAcrossDifferentArgumentsAndNewCallIds() {
        val budget = ToolArgumentRepairBudget()
        val signatures = mutableSetOf<kotlinx.serialization.json.JsonElement?>()
        repeat(7) { index ->
            val arguments = buildJsonObject {
                put("action", "context")
                put("number", index + 1)
            }
            val result = budget.rejection("new-call-$index", definition, arguments, ToolArgumentValidator.errors(arguments, schema))
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertFalse(result.dispatched)
            assertEquals(JsonPrimitive(index >= 1), result.executionReceipt?.get("argument_repair_exhausted"))
            assertEquals(JsonPrimitive((index + 1).coerceAtMost(2)), result.executionReceipt?.get("argument_repair_attempt"))
            assertEquals(index > 0, result.sharedResult)
            signatures += result.executionReceipt?.get("argument_repair_signature_hash")
        }
        assertEquals(1, signatures.size)
        assertTrue((signatures.single() as JsonPrimitive).content.matches(Regex("[a-f0-9]{64}")))
    }

    @Test fun correctedArgumentsDifferentConstraintsActionsAndSchemasRemainEligible() {
        val budget = ToolArgumentRepairBudget()
        fun reject(definition: AgentToolDefinition = this.definition, field: String = "number", action: String = "context"): AgentToolResult {
            val arguments = buildJsonObject {
                put("action", action)
                put(field, 4)
            }
            return budget.rejection("new-call", definition, arguments, ToolArgumentValidator.errors(arguments, definition.inputSchema))
        }
        repeat(3) { reject() }
        val corrected = buildJsonObject {
            put("action", "context")
            put("issue_number", 4)
        }
        assertTrue(ToolArgumentValidator.errors(corrected, schema).isEmpty())
        assertEquals(JsonPrimitive(false), reject(field = "unknown_selector").executionReceipt?.get("argument_repair_exhausted"))
        assertEquals(JsonPrimitive(false), reject(action = "comments").executionReceipt?.get("argument_repair_exhausted"))
        val changed = definition.copy(inputSchema = JsonObject(schema + ("required" to JsonArray(listOf(JsonPrimitive("issue_number"))))))
        assertEquals(JsonPrimitive(false), reject(changed).executionReceipt?.get("argument_repair_exhausted"))
        assertEquals(JsonPrimitive(false), ToolArgumentRepairBudget().rejection("new-turn", definition, buildJsonObject { put("number", 1) }, listOf("$.number: undeclared field")).executionReceipt?.get("argument_repair_exhausted"))
    }
}
