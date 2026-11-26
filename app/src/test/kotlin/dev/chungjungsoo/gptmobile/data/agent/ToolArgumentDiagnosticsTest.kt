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

    @Test fun focusedLogSchemaCanSelectTheReadNeededToDiscoverAnActualJob() {
        val tool = GitHubOperationTool.definition("github_ci", "github_ci__bound")
        val logs = requireNotNull(GitHubActionSchema.project(tool, "logs")).inputSchema
        for ((runId, expectedAction) in listOf(null to "runs", 73 to "jobs")) {
            val arguments = buildJsonObject {
                put("action", "logs")
                put("start_line", 1)
                put("end_line", 20)
                runId?.let { put("run_id", it) }
            }
            val result = ToolArgumentValidator.rejection("logs", tool.name, arguments, logs)
            assertTrue(result.isError)
            assertFalse(result.dispatched)
            val body = (result.content as ToolResultContent.Json).value as JsonObject
            val correction = body.getValue("correction_call") as JsonObject
            assertEquals(JsonPrimitive(ProgressiveToolCatalog.NAME), correction["tool"])
            val selection = correction.getValue("arguments") as JsonObject
            assertEquals(JsonArray(listOf(JsonPrimitive(tool.name))), selection["names"])
            assertEquals(JsonPrimitive(expectedAction), selection["action"])
            assertEquals(JsonPrimitive(true), selection["catalog_only"])
            val full = ToolArgumentValidator.rejection("all-actions", tool.name, arguments, tool.inputSchema)
            val fullBody = (full.content as ToolResultContent.Json).value as JsonObject
            val next = fullBody.getValue("correction_call") as JsonObject
            assertEquals(JsonPrimitive(tool.name), next["tool"])
            val read = next.getValue("arguments") as JsonObject
            assertEquals(JsonPrimitive(expectedAction), read["action"])
            assertEquals(runId?.let { JsonPrimitive(it) }, read["run_id"])
            assertFalse(read.containsKey("job_id"))
        }
    }

    @Test fun pullListCorrectsOnlyAnExplicitValidStringPageSize() {
        val schema = GitHubOperationTool.definition("github_pr").inputSchema
        for (size in listOf("1", "25", "100", "0", "101", "12.5", "private")) {
            val arguments = buildJsonObject {
                put("action", "list")
                put("state", "open")
                put("per_page", size)
            }
            assertTrue(ToolArgumentValidator.errors(arguments, schema).isNotEmpty())
            val refusal = ToolArgumentValidator.rejection("pr-list", "github_pr", arguments, schema)
            assertFalse(refusal.dispatched)
            val body = (refusal.content as ToolResultContent.Json).value as JsonObject
            if (size in listOf("1", "25", "100")) {
                val corrected = (body.getValue("correction_call") as JsonObject).getValue("arguments") as JsonObject
                assertEquals(JsonObject(arguments + ("per_page" to JsonPrimitive(size.toInt()))), corrected)
                assertTrue(ToolArgumentValidator.errors(corrected, schema).isEmpty())
            } else {
                assertFalse("correction_call" in body)
            }
        }
    }

    @Test fun readerCorrectionPreservesFiltersAndUnknownKeysStayPrivateInReports() {
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"url":{"type":"string"},"includeLinks":{"type":"boolean"},"excludeDomains":{"type":"array","items":{"type":"string"}}},"required":["url"],"additionalProperties":false}""") as JsonObject
        val args = Json.parseToJsonElement("""{"url":"https://example.invalid","include_links":"true","excludeDomains":["example.org"]}""") as JsonObject
        val correction = ToolArgumentValidator.rejection("read", "read_url", args, schema)
        val audience = (correction.content as ToolResultContent.Json).value as JsonObject
        val recipe = (audience["correction_call"] as JsonObject)["arguments"] as JsonObject
        assertEquals(args["url"], recipe["url"])
        assertEquals(args["excludeDomains"], recipe["excludeDomains"])
        assertEquals(JsonPrimitive(true), recipe["includeLinks"])
        assertTrue(ToolArgumentValidator.errors(recipe, schema).isEmpty())

        val extra = JsonObject(args + ("privateField" to JsonPrimitive("private-value")))
        val refused = ToolArgumentValidator.rejection("next", "read_url", extra, schema)
        assertFalse(((refused.content as ToolResultContent.Json).value as JsonObject).containsKey("correction_call"))
        val hashes = requireNotNull(refused.executionReceipt)["unknown_field_hashes"] as JsonArray
        assertEquals(2, hashes.size)
        assertTrue(hashes.all { (it as JsonPrimitive).content.matches(Regex("[a-f0-9]{64}")) })
    }

    @Test fun watchRequiresAnObservedSelectorAndAcceptsSavedViewContinuations() {
        val schema = GitHubOperationTool.definition("github_watch_checks").inputSchema
        val args = buildJsonObject {
            put("commit_sha", "a".repeat(40))
            put("wait_timeout_seconds", 5)
        }
        assertTrue(ToolArgumentValidator.errors(args, schema).isNotEmpty())
        assertTrue(GitHubOperationArguments.errors("github_watch_checks", args).isNotEmpty())
        val result = ToolArgumentValidator.rejection("watch", "github_watch_checks", args, schema)
        assertFalse(result.dispatched)
        assertEquals(JsonArray(listOf("pr_number", "run_id").map(::JsonPrimitive)), publicSnapshot("github_watch_checks", args, result).values["validation_field_names"])
        assertTrue((result.content as ToolResultContent.Json).value.toString().contains("github_ci action=runs"))
        for (selector in listOf("pr_number", "run_id")) {
            val valid = JsonObject(args + (selector to JsonPrimitive(7)))
            assertTrue(ToolArgumentValidator.errors(valid, schema).isEmpty())
            assertTrue(GitHubOperationArguments.errors("github_watch_checks", valid).isEmpty())
        }
        assertTrue(ToolArgumentValidator.errors(buildJsonObject { put("view_cursor", "saved") }, schema).isEmpty())
    }

    @Test fun schemaRejectionOfNestedReadKeysKeepsOnlyPublicFieldNames() {
        val args = Json.parseToJsonElement("""{"ranges":[{"path":"private-path","per_page":23,"private-key":"private-value"}]}""") as JsonObject
        val schema = GitHubOperationTool.definition("github_read").inputSchema
        val result = ToolArgumentValidator.rejection("read", "github_read", args, schema)
        assertFalse(result.dispatched)
        val snapshot = publicSnapshot("github_read", args, result)
        assertEquals(JsonArray(listOf(JsonPrimitive("RANGE_FIELDS"))), snapshot.values["semantic_read_rules"])
        assertTrue(JsonPrimitive("per_page") in (snapshot.values["validation_field_names"] as JsonArray))
        assertFalse(snapshot.toString().contains("private"))
        assertFalse(requireNotNull(result.executionReceipt).toString().contains("private"))
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

    @Test
    fun githubSearchUnknownPageSizeSuggestsOnlySchemaValidCorrection() {
        val schema = GitHubOperationTool.definition("github_search").inputSchema
        val args = buildJsonObject {
            put("action", "search")
            put("query", "fixture")
            put("per_page", 50)
        }
        val failures = ToolArgumentValidator.errors(args, schema)
        assertTrue(failures.any { "per_page" in it })
        val rejected = ToolArgumentValidator.rejection("search-invalid-page", "github_search", args, schema)
        assertEquals("INVALID_ARGUMENTS", rejected.errorCode)
        assertFalse(rejected.dispatched)
        val response = (rejected.content as ToolResultContent.Json).value as JsonObject
        val guidance = (response.getValue("message") as JsonPrimitive).content
        assertTrue(guidance.contains("Do not send per_page"))
        val fix = ((response.getValue("correction_call") as JsonObject).getValue("arguments") as JsonObject)
        assertFalse("per_page" in fix)
        assertEquals(JsonPrimitive("search"), fix["action"])
        assertEquals(JsonPrimitive("fixture"), fix["query"])
        assertTrue(ToolArgumentValidator.errors(fix, schema).isEmpty())
        assertFalse((rejected.executionReceipt ?: JsonObject(emptyMap())).toString().contains("fixture"))
    }

    @Test fun initialIndexedSearchReportsBoundedBlobCorrectionWhenNotExecuted() {
        val schema = GitHubOperationTool.definition("github_search").inputSchema
        val initial = Json.parseToJsonElement(
            """{"action":"search","mode":"paths","query":"*","index_cursor":0,"batch_size":1000,"max_new_blobs":0}"""
        ) as JsonObject
        val rejected = ToolArgumentValidator.rejection("zero-blobs", "github_search", initial, schema)
        assertFalse(rejected.dispatched)
        val response = (rejected.content as ToolResultContent.Json).value as JsonObject
        val corrected = ((response.getValue("correction_call") as JsonObject).getValue("arguments") as JsonObject)
        assertEquals(JsonPrimitive(0), corrected["index_cursor"])
        assertEquals(JsonPrimitive(1), corrected["max_new_blobs"])
        assertEquals(JsonPrimitive(1000), corrected["batch_size"])
        assertTrue(ToolArgumentValidator.errors(corrected, schema).isEmpty())
        val nonInitial = JsonObject(initial + ("index_cursor" to JsonPrimitive(2000)))
        val unsafe = ToolArgumentValidator.rejection("later-page", "github_search", nonInitial, schema)
        assertFalse("correction_call" in (unsafe.content as ToolResultContent.Json).value as JsonObject)
    }

    @Test fun githubReadTopLevelPathSuggestsOneSafeSchemaValidCorrection() {
        val schema = GitHubOperationTool.definition("github_read").inputSchema
        val args = buildJsonObject {
            put("owner", "owner")
            put("repo", "repo")
            put("path", "docs/README.md")
        }
        assertTrue(ToolArgumentValidator.errors(args, schema).isNotEmpty())
        val rejected = ToolArgumentValidator.rejection("bad-read", "github_read", args, schema)
        assertEquals("INVALID_ARGUMENTS", rejected.errorCode)
        assertFalse(rejected.dispatched)
        val body = (rejected.content as ToolResultContent.Json).value as JsonObject
        val fixed = (body.getValue("correction_call") as JsonObject).getValue("arguments") as JsonObject
        assertEquals(JsonPrimitive("ranges"), fixed["action"])
        assertEquals(JsonArray(listOf(buildJsonObject { put("path", "docs/README.md") })), fixed["ranges"])
        assertFalse("path" in fixed)
        assertTrue(ToolArgumentValidator.errors(fixed, schema).isEmpty())
        assertFalse(rejected.executionReceipt.toString().contains("docs/README.md"))
    }

    @Test fun unsafeReadPathCannotBecomeACorrection() {
        val schema = GitHubOperationTool.definition("github_read").inputSchema
        val rejected = ToolArgumentValidator.rejection(
            "bad-traversal",
            "github_read",
            buildJsonObject { put("path", "../private.txt") },
            schema
        )
        val body = (rejected.content as ToolResultContent.Json).value as JsonObject
        assertFalse("correction_call" in body)
        assertFalse(rejected.dispatched)
    }

    @Test
    fun githubSearchInvalidActionDoesNotInventAReplacementAction() {
        val schema = GitHubOperationTool.definition("github_search").inputSchema
        val args = buildJsonObject {
            put("action", "unsupported")
            put("query", "fixture")
        }
        assertTrue(ToolArgumentValidator.errors(args, schema).isNotEmpty())
        val rejected = ToolArgumentValidator.rejection("bad-action", "github_search", args, schema)
        val response = (rejected.content as ToolResultContent.Json).value as JsonObject
        assertFalse("correction_call" in response)
        assertFalse(rejected.dispatched)
        val guidance = (response.getValue("message") as JsonPrimitive).content
        assertTrue(guidance.contains("search_repository"))
        assertTrue(guidance.contains("search_code"))
    }

    @Test fun unknownPublicFieldProjectsItsIdentityWithoutBecomingAnInvalidDeclaredField() {
        val args = buildJsonObject {
            put("query", "private query contents")
            put("limit", 739)
        }
        val errors = ToolArgumentValidator.errors(args, searchSchema)
        assertEquals(listOf("$.limit: undeclared field"), errors)
        val result = ToolArgumentValidator.rejection("public-unknown", "github_search", args, searchSchema, errors)
        val receipt = requireNotNull(result.executionReceipt)
        assertEquals(JsonPrimitive(1), receipt["unknown_field_count"])
        assertEquals(JsonPrimitive(0), receipt["invalid_field_count"])
        assertEquals(JsonPrimitive(0), receipt["missing_field_count"])
        assertFalse("invalid_fields" in receipt)
        assertEquals(JsonArray(listOf(JsonPrimitive("limit"))), receipt["validation_field_names"])
        assertEquals(JsonArray(listOf(JsonPrimitive("UNKNOWN_FIELD"))), receipt["invalid_field_classes"])
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(JsonPrimitive(false), receipt["retryable"])
        assertEquals(JsonPrimitive(false), receipt["requires_reconciliation"])
        assertFalse("correction_call" in ((result.content as ToolResultContent.Json).value as JsonObject))
        val snapshot = publicSnapshot("github_search", args, result)
        assertEquals(receipt["validation_field_names"], snapshot.values["validation_field_names"])
        assertEquals(receipt["unknown_field_count"], snapshot.values["unknown_field_count"])
        assertEquals(receipt["invalid_field_count"], snapshot.values["invalid_field_count"])
        assertFalse(snapshot.toString().contains("private query contents"))
        assertFalse(snapshot.values.values.contains(JsonPrimitive(739)))
    }

    @Test fun unknownPrivateFieldIsCountedWithoutPublishingItsNameOrValues() {
        val args = buildJsonObject {
            put("query", "private query contents")
            put("private-token-field", "private argument contents")
        }
        val errors = ToolArgumentValidator.errors(args, searchSchema)
        assertEquals(listOf("$.private-token-field: undeclared field"), errors)
        val result = ToolArgumentValidator.rejection("private-unknown", "github_search", args, searchSchema, errors)
        val receipt = requireNotNull(result.executionReceipt)
        assertEquals(JsonPrimitive(1), receipt["unknown_field_count"])
        assertEquals(JsonPrimitive(0), receipt["invalid_field_count"])
        assertEquals(JsonPrimitive(0), receipt["missing_field_count"])
        assertFalse("invalid_fields" in receipt)
        assertFalse("validation_field_names" in receipt)
        assertFalse(result.dispatched)
        val snapshot = publicSnapshot("github_search", args, result)
        assertFalse("validation_field_names" in snapshot.values)
        assertEquals(receipt["unknown_field_count"], snapshot.values["unknown_field_count"])
        assertEquals(receipt["invalid_field_count"], snapshot.values["invalid_field_count"])
        for (privateText in listOf("private-token-field", "private query contents", "private argument contents")) {
            assertFalse(receipt.toString().contains(privateText))
            assertFalse(snapshot.toString().contains(privateText))
        }
        assertTrue(ToolArgumentValidator.errors(args, JsonObject(searchSchema + ("additionalProperties" to JsonPrimitive(true)))).isEmpty())
    }

    @Test fun unknownFieldNamesJoinSemanticReadAndCiEvidenceWithoutChangingCorrectionAdmission() {
        val cases = listOf(
            "github_read" to """{"action":"ranges","ranges":[{"path":"private-source-path","start_line":1,"end_line":-1,"max_lines":5}],"limit":739}""",
            "github_ci" to """{"action":"logs","job_id":7,"start_line":20,"end_line":2020,"limit":739}"""
        )
        for ((operation, input) in cases) {
            val args = Json.parseToJsonElement(input) as JsonObject
            val schema = GitHubOperationTool.definition(operation).inputSchema
            val readRules = linkedSetOf<GitHubOperationArguments.ReadRule>()
            val ciRejections = linkedSetOf<GitHubOperationArguments.CiRejection>()
            val semanticErrors = GitHubOperationArguments.errors(operation, args, readRules, ciRejections)
            assertTrue(semanticErrors.isNotEmpty())
            val errors = ToolArgumentValidator.errors(args, schema) + semanticErrors
            val result = ToolArgumentValidator.rejection("joined-$operation", operation, args, schema, errors, readRules, ciRejections)
            val receipt = requireNotNull(result.executionReceipt)
            val expected = if (operation == "github_read") {
                listOf("end_line", "limit", "max_lines", "ranges", "start_line")
            } else {
                listOf("end_line", "limit", "start_line")
            }
            assertEquals(JsonArray(expected.map(::JsonPrimitive)), receipt["validation_field_names"])
            assertEquals(JsonPrimitive(1), receipt["unknown_field_count"])
            assertEquals(JsonPrimitive(if (operation == "github_read") 1 else 0), receipt["invalid_field_count"])
            assertFalse(result.dispatched)
            assertFalse("correction_call" in ((result.content as ToolResultContent.Json).value as JsonObject))
            val snapshot = publicSnapshot(operation, args, result)
            assertEquals(receipt["validation_field_names"], snapshot.values["validation_field_names"])
            assertEquals(receipt[if (operation == "github_read") "semantic_read_rules" else "semantic_ci_rules"], snapshot.values[if (operation == "github_read") "semantic_read_rules" else "semantic_ci_rules"])
            assertFalse(snapshot.toString().contains("private-source-path"))
            assertFalse(snapshot.values.values.contains(JsonPrimitive(739)))

            val knownArgs = JsonObject(args - "limit")
            val stillRejected = ToolArgumentValidator.rejection("known-$operation", operation, knownArgs, schema, semanticErrors, readRules, ciRejections)
            assertFalse(stillRejected.dispatched)
            val body = (stillRejected.content as ToolResultContent.Json).value as JsonObject
            val correction = requireNotNull(body["correction_call"] as? JsonObject)
            assertEquals(JsonPrimitive(operation), correction["tool"])
            val corrected = correction["arguments"] as JsonObject
            assertTrue(ToolArgumentValidator.errors(corrected, schema).isEmpty())
            assertTrue(GitHubOperationArguments.errors(operation, corrected).isEmpty())
        }
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

    private fun publicSnapshot(operation: String, args: JsonObject, result: AgentToolResult): IssueReportDiagnostics {
        val event = ToolEvent("private-event", "private-run", 1, result.callId, null, null, operation, operation, args.toString(), null, null, "FAILED")
        return requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", requireNotNull(result.executionReceipt) + ToolReportDiagnostics.fields(event, result), 1_800_000_000_000))
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
