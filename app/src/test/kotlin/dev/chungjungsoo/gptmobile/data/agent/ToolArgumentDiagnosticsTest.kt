package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics
import dev.chungjungsoo.gptmobile.data.diagnostics.ToolReportDiagnostics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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

    @Test fun repositoryDiscoveryQueryDiagnosticsSurvivePublicProjectionWithoutQueryContents() {
        val schema = GitHubOperationTool.definition("github_context").inputSchema
        val cases = listOf(
            null to "MISSING_REQUIRED",
            JsonPrimitive("") to "EMPTY_STRING",
            JsonPrimitive(" \t\n ") to "EMPTY_STRING",
            JsonNull to "WRONG_TYPE",
            JsonPrimitive(42) to "WRONG_TYPE",
            buildJsonObject { put("private-search-content", "never-publish") } to "WRONG_TYPE"
        )
        for (operation in listOf("github_context", "github_context__github_api")) {
            for ((query, constraint) in cases) {
                val args = buildJsonObject {
                    put("action", "repositories")
                    query?.let { put("query", it) }
                }
                val errors = ToolArgumentValidator.errors(args, schema).ifEmpty { GitHubOperationArguments.errors("github_context", args) }
                assertTrue(errors.isNotEmpty())
                val result = ToolArgumentValidator.rejection("query-call", operation, args, schema, errors)
                val receipt = requireNotNull(result.executionReceipt)
                val field = if (query == null) "missing_fields" else "invalid_fields"
                assertEquals(JsonArray(listOf(JsonPrimitive("query"))), receipt[field])
                assertEquals(JsonPrimitive(if (query == null) 1 else 0), receipt["missing_field_count"])
                assertEquals(JsonPrimitive(if (query == null) 0 else 1), receipt["invalid_field_count"])
                assertEquals(JsonArray(listOf(JsonPrimitive(constraint))), receipt["invalid_field_classes"])
                assertFalse(result.dispatched)
                val event = ToolEvent("private-event", "private-run", 1, result.callId, null, null, "github_context", operation, args.toString(), null, null, "FAILED")
                val snapshot = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", receipt + ToolReportDiagnostics.fields(event, result), 1_800_000_000_000))
                assertEquals(JsonArray(listOf(JsonPrimitive("query"))), snapshot.values["validation_field_names"])
                assertEquals(receipt["missing_field_count"], snapshot.values["missing_field_count"])
                assertEquals(receipt["invalid_field_count"], snapshot.values["invalid_field_count"])
                assertEquals(receipt["invalid_field_classes"], snapshot.values["invalid_field_classes"])
                assertFalse(snapshot.toString().contains("private-search-content"))
                assertFalse(snapshot.toString().contains("never-publish"))
            }
        }
    }

    @Test fun repositoryQueryEvidenceDoesNotApplyToSavedViewsOtherActionsOrValidQueries() {
        val schema = GitHubOperationTool.definition("github_context").inputSchema
        val cases = listOf(
            "github_search" to """{"action":"repositories"}""",
            "github_context" to """{"action":"status"}""",
            "github_context__github_api" to """{"action":"repositories","view_cursor":"private-cursor"}""",
            "github_context" to """{"action":"repositories","query":"private-search-content"}"""
        )
        for ((operation, input) in cases) {
            val args = Json.parseToJsonElement(input) as JsonObject
            val result = ToolArgumentValidator.rejection("other-call", operation, args, schema, listOf("Unrelated local constraint"))
            val receipt = requireNotNull(result.executionReceipt)
            assertFalse((receipt["missing_fields"] as? JsonArray).orEmpty().contains(JsonPrimitive("query")))
            assertFalse((receipt["invalid_fields"] as? JsonArray).orEmpty().contains(JsonPrimitive("query")))
            assertEquals(JsonPrimitive(0), receipt["missing_field_count"])
            assertEquals(JsonPrimitive(0), receipt["invalid_field_count"])
            assertFalse(receipt.toString().contains("private-search-content"))
            assertFalse(receipt.toString().contains("private-cursor"))
        }
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
