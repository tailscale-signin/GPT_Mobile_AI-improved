package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentDiagnosticsTest {
    private val searchSchema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "query",
                    buildJsonObject {
                        put("type", "string")
                        put("minLength", 1)
                    }
                )
            }
        )
        put("required", JsonArray(listOf(JsonPrimitive("query"))))
        put("additionalProperties", false)
    }

    @Test fun missingSearchQueryRetainsTypedNonDispatchEvidence() {
        val result = ToolArgumentValidator.rejection("call", "github_search__github_api", buildJsonObject {}, searchSchema)
        val receipt = requireNotNull(result.executionReceipt)
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(JsonPrimitive("tool_precheck"), receipt["failed_stage"])
        assertEquals(JsonPrimitive(false), receipt["retryable"])
        assertEquals(JsonPrimitive(false), receipt["requires_reconciliation"])
        assertEquals(JsonPrimitive("TOOL_ARGUMENT_SCHEMA_REJECTED"), receipt["reason_code"])
        assertEquals(JsonArray(listOf(JsonPrimitive("query"))), receipt["missing_fields"])
        assertEquals(JsonPrimitive(1), receipt["missing_field_count"])
        assertEquals(JsonArray(listOf(JsonPrimitive("MISSING_REQUIRED"))), receipt["invalid_field_classes"])
    }

    @Test fun invalidTypeKeepsOnlyItsDeclaredFieldAndCountsUndeclaredNames() {
        val args = buildJsonObject {
            put("query", 42)
            put("private-token-field", "private value")
        }
        val result = ToolArgumentValidator.rejection("call", "github_search", args, searchSchema)
        val receipt = requireNotNull(result.executionReceipt)
        assertEquals(JsonArray(listOf(JsonPrimitive("query"))), receipt["invalid_fields"])
        assertEquals(JsonPrimitive(1), receipt["unknown_field_count"])
        assertFalse(receipt.toString().contains("private"))
        assertFalse(receipt.toString().contains("42"))
        assertEquals(JsonPrimitive(1), receipt["invalid_field_count"])
        assertEquals(JsonArray(listOf(JsonPrimitive("UNKNOWN_FIELD"), JsonPrimitive("WRONG_TYPE"))), receipt["invalid_field_classes"])
    }

    @Test fun domainConstraintFailuresDoNotInventSchemaFieldFailures() {
        val args = buildJsonObject { put("query", "class.*Test") }
        val result = ToolArgumentValidator.rejection("call", "github_search", args, searchSchema, listOf("Pattern mode does not accept regex syntax"))
        val receipt = requireNotNull(result.executionReceipt)
        assertEquals(JsonPrimitive("TOOL_ARGUMENT_CONSTRAINT_REJECTED"), receipt["reason_code"])
        assertFalse("invalid_fields" in receipt)
        assertFalse("missing_fields" in receipt)
        assertEquals(JsonPrimitive(1), receipt["validation_error_count"])
        assertFalse("invalid_field_classes" in receipt)
    }

    @Test fun successfulAlternativeDoesNotLeakRejectedAlternativeClasses() {
        val schema = Json.parseToJsonElement("""{"anyOf":[{"type":"object","properties":{"kind":{"const":"text"},"text":{"type":"string"}},"required":["kind","text"]},{"type":"object","properties":{"kind":{"const":"number"},"count":{"type":"integer","minimum":2}},"required":["kind","count"]}]}""") as JsonObject
        val rejected = ToolArgumentValidator.rejection(
            "call",
            "custom_tool",
            buildJsonObject {
                put("kind", "number")
                put("count", 0)
            },
            schema
        )
        assertEquals(
            JsonArray(listOf(JsonPrimitive("COMPOSITE_CONSTRAINT"), JsonPrimitive("OUT_OF_RANGE"))),
            rejected.executionReceipt?.get("invalid_field_classes")
        )
        val valid = ToolArgumentValidator.rejection(
            "call",
            "custom_tool",
            buildJsonObject {
                put("kind", "number")
                put("count", 3)
            },
            schema,
            listOf("Private external constraint")
        )
        assertFalse("invalid_field_classes" in requireNotNull(valid.executionReceipt))
    }

    @Test fun missingIssueSelectorReturnsAReadOnlyDiscoveryRecipeAndTypedCause() {
        val schema = selectorSchema("issue_number")
        val args = buildJsonObject { put("action", "context") }
        val result = ToolArgumentValidator.rejection("call", "github_issue", args, schema, listOf("issue_number is required"))
        val receipt = requireNotNull(result.executionReceipt)
        val body = (result.content as ToolResultContent.Json).value as JsonObject
        assertEquals(JsonPrimitive("ISSUE_SELECTOR_REQUIRED"), receipt["reason_code"])
        assertEquals(JsonArray(listOf(JsonPrimitive("issue_number"))), receipt["missing_fields"])
        assertEquals(JsonPrimitive("issue_number"), (body["required_selector"] as JsonObject)["field"])
        val correction = (body["correction_call"] as JsonObject)["arguments"] as JsonObject
        assertEquals(buildJsonObject { put("action", "list") }, correction)
        assertTrue(ToolArgumentValidator.errors(correction, schema).isEmpty())
        assertFalse(result.dispatched)
    }

    @Test fun numericStringSelectorsRemainInvalidAndReceiveDiscoveryWithoutCoercion() {
        for ((operation, key, reason) in listOf(Triple("github_pr", "pull_number", "PR_SELECTOR_REQUIRED"), Triple("github_issue", "issue_number", "ISSUE_SELECTOR_REQUIRED"))) {
            val args = buildJsonObject {
                put("action", "context")
                put(key, "42")
            }
            val result = ToolArgumentValidator.rejection("call", operation, args, selectorSchema(key))
            val receipt = requireNotNull(result.executionReceipt)
            val body = (result.content as ToolResultContent.Json).value as JsonObject
            assertEquals(JsonPrimitive(reason), receipt["reason_code"])
            assertEquals(JsonArray(listOf(JsonPrimitive(key))), receipt["invalid_fields"])
            assertEquals(buildJsonObject { put("action", "list") }, (body["correction_call"] as JsonObject)["arguments"])
            assertFalse(result.dispatched)
        }
    }

    private fun selectorSchema(field: String) = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "action",
                    buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(listOf(JsonPrimitive("list"), JsonPrimitive("context"))))
                    }
                )
                put(
                    field,
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                    }
                )
            }
        )
        put("additionalProperties", false)
    }
}
