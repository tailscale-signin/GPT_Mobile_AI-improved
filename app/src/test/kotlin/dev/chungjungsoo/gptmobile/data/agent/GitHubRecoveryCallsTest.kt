package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubRecoveryCallsTest {
    @Test fun exactPinnedSourceRecipeSurvivesCompactionAndRoutesThroughReadAdmission() {
        val readName = "github_read__selected"
        val searchName = "github_search__selected"
        val arguments = Json.parseToJsonElement("""{"owner":"owner","repo":"repo","commit_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ranges":[{"path":"AGENTS.md","start_line":1,"max_lines":200}]}""").jsonObject
        val recipe = buildJsonObject {
            put("tool", readName)
            put("arguments", arguments)
        }
        val body = buildJsonObject {
            put("schema", "github.operation.v1")
            put("operation", "github_search")
            put("local_page_complete", true)
            put("source_read_call", recipe)
            put("data", buildJsonObject { put("content", "search result ".repeat(1000)) })
        }
        val compacted = requireNotNull(GitHubReplayRecovery.compact(ToolResultContent.Json(body), 2048, consumed = true))
        assertEquals(recipe, requireNotNull(GitHubReplayRecovery.envelope(compacted))["source_read_call"])
        for (content in listOf(
            compacted,
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.operation.v1")
                    put("data", buildJsonObject { put("source_read_call", recipe) })
                }
            )
        )) {
            val previous = ProviderEvent.ToolCall("paths", searchName, buildJsonObject { put("query", "*.md") })
            val exchanges = listOf(AgentToolExchange(listOf(previous), listOf(AgentToolResult(previous.callId, content, false))))
            val request = ProviderEvent.ToolCall("source", searchName, buildJsonObject { put("source_read_call", recipe) }).captureProviderInvocation()
            val repaired = GitHubRecoveryCalls.resolve(request, exchanges, mapOf(readName to tool(readName)))
            assertEquals(readName, repaired.name)
            assertEquals(arguments, repaired.arguments)
            assertEquals(request.providerInvocation(), repaired.providerInvocation())
            assertEquals(request, GitHubRecoveryCalls.resolve(request, exchanges, emptyMap()))
            val changedRevision = JsonObject(arguments + ("commit_sha" to JsonPrimitive("b".repeat(40))))
            val invented = request.copy(
                arguments = buildJsonObject {
                    put("source_read_call", JsonObject(recipe + ("arguments" to changedRevision)))
                }
            )
            assertEquals(invented, GitHubRecoveryCalls.resolve(invented, exchanges, mapOf(readName to tool(readName))))
        }
    }

    @Test fun fourOversizedReadsBecomeEightExactWindowsWithoutLosingSourceOrIdentity() {
        val name = "github_read__selected"
        val ranges = (1..4).map { index ->
            buildJsonObject {
                put("path", "src/File$index.kt")
                put("start_line", 51)
                put("max_lines", 2500)
            }
        }
        val original = ProviderEvent.ToolCall(
            "read-all",
            name,
            buildJsonObject {
                put("action", "ranges")
                put("commit_sha", "a".repeat(40))
                put("ranges", kotlinx.serialization.json.JsonArray(ranges))
            }
        ).captureProviderInvocation()
        val corrected = GitHubRecoveryCalls.repairSourceRead(original, tool(name))
        val expected = ranges.flatMap { range ->
            listOf(51 to 2050, 2051 to 2550).map { (first, last) ->
                buildJsonObject {
                    put("path", range.getValue("path"))
                    put("start_line", first)
                    put("end_line", last)
                }
            }
        }
        assertEquals(kotlinx.serialization.json.JsonArray(expected), corrected.arguments["ranges"])
        assertEquals(original.arguments["commit_sha"], corrected.arguments["commit_sha"])
        assertEquals(original.providerInvocation(), corrected.providerInvocation())
        assertEquals(original.callId, corrected.callId)
        assertEquals(emptyList<String>(), ToolArgumentValidator.errors(corrected.arguments, tool(name).definition.inputSchema))
        assertEquals(corrected, GitHubRecoveryCalls.repairSourceRead(corrected, tool(name)))
    }

    @Test fun oversizedReadRepairNeverTruncatesOverflowsOrChangesAmbiguousWindows() {
        for (raw in listOf(
            """{"ranges":[{"path":"a.kt","max_lines":16001}]}""",
            """{"ranges":[{"path":"a.kt","start_line":2147483640,"max_lines":2500}]}""",
            """{"ranges":[{"path":"a.kt","end_line":5000,"max_lines":2500}]}""",
            """{"ranges":[{"path":"a.kt","start_offset":2,"max_lines":2500}]}""",
            """{"ranges":[{"path":"a.kt","max_lines":"2500"}]}"""
        )) {
            val call = ProviderEvent.ToolCall("reject", "github_read", Json.parseToJsonElement(raw).jsonObject)
            assertEquals(call, GitHubRecoveryCalls.repairSourceRead(call, tool(call.name)))
        }
        val tooMany = buildJsonObject {
            put(
                "ranges",
                kotlinx.serialization.json.JsonArray(
                    (1..5).map { index ->
                        buildJsonObject {
                            put("path", "$index.kt")
                            put("max_lines", 2500)
                        }
                    }
                )
            )
        }
        val call = ProviderEvent.ToolCall("reject", "github_read", tooMany)
        assertEquals(call, GitHubRecoveryCalls.repairSourceRead(call, tool(call.name)))
    }

    @Test fun inventorySavedPagesRemoveOnlyMatchingContextFromAnExactlyIssuedCursor() {
        val cursor = "inventory:20:1536"
        for ((name, sourceArguments) in listOf(
            "github_context__selected" to """{"action":"branches"}""",
            "github_issue__selected" to """{"action":"list","state":"open","per_page":100,"page":1}""",
            "github_pr__selected" to """{"action":"list","head":"owner:work","state":"open","page":1}""",
            "github_context__selected" to """{"action":"resolve","owner":"owner","repo":"repo","ref":"main"}""",
            "github_context_inspect__selected" to """{"action":"repository","owner":"owner","repo":"repo"}""",
            "github_search__selected" to """{"query":"*.md","mode":"paths","owner":"owner","repo":"repo","commit_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}""",
            "github_read__selected" to """{"ranges":[{"path":"AGENTS.md","start_line":1,"max_lines":200}],"owner":"owner","repo":"repo","commit_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}""",
            "github_ci__selected" to """{"action":"jobs","run_id":42,"run_attempt":2,"expected_head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","page":1,"per_page":100}"""
        )) {
            val original = Json.parseToJsonElement(sourceArguments).jsonObject
            val source = ProviderEvent.ToolCall("source", name, original)
            val receipt = Json.parseToJsonElement("""{"saved_view_id":"inventory","saved_view_cursor_ids":["$cursor"]}""").jsonObject
            val result = AgentToolResult("source", ToolResultContent.Text("retained inventory"), false, executionReceipt = receipt)
            val exchanges = listOf(AgentToolExchange(listOf(source), listOf(result)))
            val tools = mapOf(name to tool(name))
            val request = source.copy(callId = "page", arguments = JsonObject(original + ("view_cursor" to JsonPrimitive(cursor)))).captureProviderInvocation()
            val repaired = GitHubRecoveryCalls.resolve(request, exchanges, tools)
            assertEquals(buildJsonObject { put("view_cursor", cursor) }, repaired.arguments)
            assertEquals(request.providerInvocation(), repaired.providerInvocation())
            assertEquals(request, GitHubRecoveryCalls.resolve(request, emptyList(), tools))
            assertEquals(request, GitHubRecoveryCalls.resolve(request, listOf(AgentToolExchange(listOf(source.copy(name = name + "_other")), listOf(result))), tools))
            for ((field, value) in listOf(
                "view_cursor" to JsonPrimitive(cursor + "0"),
                "page" to JsonPrimitive(2),
                "state" to JsonPrimitive("closed"),
                "owner" to JsonPrimitive("another-owner"),
                "commit_sha" to JsonPrimitive("b".repeat(40)),
                "query" to JsonPrimitive("another-query"),
                "action" to JsonPrimitive("update")
            )) {
                val changed = request.copy(arguments = JsonObject(request.arguments + (field to value)))
                assertEquals(changed, GitHubRecoveryCalls.resolve(changed, exchanges, tools))
            }
        }
    }

    @Test fun missingIssueAfterIsFilledOnlyFromOneIdenticalIssuedContinuationRecipe() {
        val name = "github_issue__selected"
        val arguments = Json.parseToJsonElement("""{"action":"list","owner":"owner","repo":"repo","page":2,"per_page":100,"state":"open"}""").jsonObject
        val request = ProviderEvent.ToolCall("next", name, arguments).captureProviderInvocation()
        val tools = mapOf(name to tool(name))
        fun issued(cursor: String, issuer: String = name, failed: Boolean = false, sourceName: String = name): AgentToolExchange {
            val recipe = buildJsonObject {
                put("tool", issuer)
                put("arguments", JsonObject(arguments + ("after" to JsonPrimitive(cursor))))
            }
            return AgentToolExchange(
                listOf(ProviderEvent.ToolCall("prior", sourceName, JsonObject(arguments + ("page" to JsonPrimitive(1))))),
                listOf(
                    AgentToolResult(
                        "prior",
                        ToolResultContent.Json(
                            buildJsonObject {
                                put("schema", "github.operation.v1")
                                put("continuation_call", recipe)
                            }
                        ),
                        failed
                    )
                )
            )
        }
        val exchanges = listOf(issued("issued-cursor"))
        val repaired = GitHubRecoveryCalls.resolve(request, exchanges, tools)
        assertEquals(JsonObject(arguments + ("after" to JsonPrimitive("issued-cursor"))), repaired.arguments)
        assertEquals(request.providerInvocation(), repaired.providerInvocation())
        assertEquals(request, GitHubRecoveryCalls.resolve(request, emptyList(), tools))
        assertEquals(request, GitHubRecoveryCalls.resolve(request, listOf(issued("issued-cursor", name + "_other")), tools))
        assertEquals(request, GitHubRecoveryCalls.resolve(request, listOf(issued("issued-cursor", sourceName = name + "_other")), tools))
        assertEquals(request, GitHubRecoveryCalls.resolve(request, listOf(issued("issued-cursor", failed = true)), tools))
        assertEquals(request, GitHubRecoveryCalls.resolve(request, exchanges + issued("another-cursor"), tools))
        for ((field, value) in listOf("page" to JsonPrimitive(3), "per_page" to JsonPrimitive(30), "state" to JsonPrimitive("closed"), "repo" to JsonPrimitive("other"), "action" to JsonPrimitive("update"))) {
            val changed = request.copy(arguments = JsonObject(arguments + (field to value)))
            assertEquals(changed, GitHubRecoveryCalls.resolve(changed, exchanges, tools))
        }
    }

    @Test fun redundantLogsActionResolvesOnlyForTheExactCursorIssuedByThatLogRead() {
        val name = "github_ci__selected"
        val cursor = "00000000-0000-4000-8000-000000000001:47:1536"
        val source = ProviderEvent.ToolCall(
            "logs",
            name,
            buildJsonObject {
                put("action", "logs")
                put("job_id", 42)
            }
        )
        val receipt = buildJsonObject {
            put("saved_view_id", cursor.substringBefore(':'))
            put("saved_view_cursor_ids", Json.parseToJsonElement("""["$cursor"]"""))
        }
        val result = AgentToolResult(source.callId, ToolResultContent.Text("retained"), false, executionReceipt = receipt)
        val request = source.copy(
            callId = "page",
            arguments = buildJsonObject {
                put("action", "logs")
                put("view_cursor", cursor)
            }
        )
        fun route(issuer: ProviderEvent.ToolCall, proposal: ProviderEvent.ToolCall = request) =
            GitHubRecoveryCalls.resolve(proposal, listOf(AgentToolExchange(listOf(issuer), listOf(result))), mapOf(name to tool(name)))
        assertEquals(buildJsonObject { put("view_cursor", cursor) }, route(source).arguments)
        assertEquals(request, route(source.copy(name = "github_ci__other")))
        assertEquals(request, route(source.copy(arguments = buildJsonObject { put("action", "jobs") })))
        val altered = request.copy(
            arguments = buildJsonObject {
                put("action", "logs")
                put("view_cursor", cursor + "0")
            }
        )
        assertEquals(altered, route(source, altered))
    }

    private fun tool(name: String) = object : AgentTool {
        override val definition = GitHubOperationTool.definition(name.substringBefore("__"), name)
        override suspend fun execute(callId: String, arguments: JsonObject) = error("Routing must not execute tools")
    }

    @Test fun exactRetainedReadEnvelopeIsUnwrappedAndRoutedThroughAuthorizedTool() {
        val recipe = buildJsonObject {
            put("tool", "github_search")
            put("arguments", buildJsonObject { put("view_cursor", "issued:4") })
        }
        val result = AgentToolResult(
            "prior",
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.arguments.v1")
                    put("correction_call", recipe)
                }
            ),
            true,
            dispatched = false
        )
        val exchanges = listOf(AgentToolExchange(listOf(ProviderEvent.ToolCall("prior", "github_read", buildJsonObject {})), listOf(result)))
        val call = ProviderEvent.ToolCall("next", "github_read", buildJsonObject { put("correction_call", recipe) })
        val routed = GitHubRecoveryCalls.resolve(call, exchanges, mapOf("github_search" to tool("github_search")))
        assertEquals("github_search", routed.name)
        assertEquals(recipe["arguments"], routed.arguments)
        assertEquals("next", routed.callId)
        assertEquals(call, GitHubRecoveryCalls.resolve(call, emptyList(), mapOf("github_search" to tool("github_search"))))
        assertEquals(call, GitHubRecoveryCalls.resolve(call, exchanges, emptyMap()))
    }

    @Test fun savedLogsRequireAnIssuedCursorFromTheSameLogViewAndConnection() {
        val name = "github_ci__selected"
        val cursor = "view:47:1536"
        val page = buildJsonObject { put("view_cursor", cursor) }
        val request = ProviderEvent.ToolCall("next", name, JsonObject(page + ("action" to kotlinx.serialization.json.JsonPrimitive("logs")))).captureProviderInvocation()
        val tools = mapOf(name to tool(name))
        fun exchange(id: String, args: JsonObject, cursors: String, issuer: String = name) = AgentToolExchange(
            listOf(ProviderEvent.ToolCall(id, issuer, args)),
            listOf(
                AgentToolResult(
                    id,
                    ToolResultContent.Text("Saved"),
                    false,
                    executionReceipt = Json.parseToJsonElement("""{"saved_view_id":"view","saved_view_cursor_ids":[$cursors]}""").jsonObject
                )
            )
        )
        val logs = buildJsonObject {
            put("action", "logs")
            put("job_id", 123)
        }
        val source = exchange("source", logs, "")
        val next = exchange("page", buildJsonObject { put("view_cursor", "view:43:1536") }, "\"$cursor\"")
        val repaired = GitHubRecoveryCalls.resolve(request, listOf(source, next), tools)
        assertEquals(page, repaired.arguments)
        assertEquals(request.providerInvocation(), repaired.providerInvocation())
        assertEquals(request, GitHubRecoveryCalls.resolve(request, listOf(source), tools))
        assertEquals(request, GitHubRecoveryCalls.resolve(request, listOf(next), tools))
        assertEquals(request, GitHubRecoveryCalls.resolve(request, listOf(exchange("source", logs, "", "github_ci__other"), next), tools))
        assertEquals(request, GitHubRecoveryCalls.resolve(request, listOf(exchange("source", buildJsonObject { put("action", "jobs") }, ""), next), tools))
    }

    @Test fun exactMalformedSourceReadBecomesAnAdmittedToolProposalWithOriginalReplayIdentity() {
        val name = "github_read__selected"
        val original = Json.parseToJsonElement("""{"owner":"owner","repo":"repo","path":"src/Reader.kt","start_line":2,"max_lines":12}""").jsonObject
        val call = ProviderEvent.ToolCall("read", name, original).captureProviderInvocation()
        val repaired = GitHubRecoveryCalls.repairSourceRead(call, tool(name))
        assertEquals(call.callId, repaired.callId)
        assertEquals(call.name, repaired.name)
        assertEquals(call.providerInvocation(), repaired.providerInvocation())
        assertEquals(
            Json.parseToJsonElement("""{"owner":"owner","repo":"repo","action":"ranges","ranges":[{"path":"src/Reader.kt","start_line":2,"max_lines":12}]}""").jsonObject,
            repaired.arguments
        )
        assertEquals(call, GitHubRecoveryCalls.repairSourceRead(call, null))
        assertEquals(repaired, GitHubRecoveryCalls.repairSourceRead(repaired, tool(name)))
    }

    @Test fun unsupportedFirstPageSearchSizeIsDroppedWithoutChangingOriginalInvocation() {
        val name = "github_search__selected"
        val original = Json.parseToJsonElement(
            """{"action":"search","query":"important class","per_page":50}"""
        ).jsonObject
        val call = ProviderEvent.ToolCall("search", name, original).captureProviderInvocation()
        val registered = tool(name)
        val repaired = GitHubRecoveryCalls.repairSourceRead(call, registered)
        assertEquals(
            Json.parseToJsonElement("""{"action":"search","query":"important class"}""").jsonObject,
            repaired.arguments
        )
        assertEquals(call.callId, repaired.callId)
        assertEquals(call.name, repaired.name)
        assertEquals(call.providerInvocation(), repaired.providerInvocation())
        assertEquals(emptyList<String>(), ToolArgumentValidator.errors(repaired.arguments, registered.definition.inputSchema))
        assertEquals(repaired, GitHubRecoveryCalls.repairSourceRead(repaired, registered))
    }

    @Test fun build142InitialSearchZeroBlobsIsBoundedWithoutChangingRequestIdentity() {
        val tool = tool("github_search__selected")
        for ((raw, expected) in listOf(
            """{"action":"search","mode":"paths","query":"*","index_cursor":0,"batch_size":1000,"max_new_blobs":0}""" to
                """{"action":"search","mode":"paths","query":"*","index_cursor":0,"batch_size":1000,"max_new_blobs":1}""",
            """{"action":"search","mode":"paths","query":"*","batch_size":1000,"max_new_blobs":0}""" to
                """{"action":"search","mode":"paths","query":"*","batch_size":1000,"max_new_blobs":1}""",
            """{"action":"search","mode":"paths","query":"*","max_new_blobs":1000}""" to
                """{"action":"search","mode":"paths","query":"*","max_new_blobs":100}"""
        )) {
            val original = ProviderEvent.ToolCall("same-call-id", tool.definition.name, Json.parseToJsonElement(raw).jsonObject)
                .captureProviderInvocation()
            val repaired = GitHubRecoveryCalls.repairSourceRead(original, tool)
            assertEquals(Json.parseToJsonElement(expected).jsonObject, repaired.arguments)
            assertEquals(original.callId, repaired.callId)
            assertEquals(original.name, repaired.name)
            assertEquals(original.providerInvocation(), repaired.providerInvocation())
            assertEquals(emptyList<String>(), ToolArgumentValidator.errors(repaired.arguments, tool.definition.inputSchema))
            assertEquals(repaired, GitHubRecoveryCalls.repairSourceRead(repaired, tool))
        }
    }

    @Test fun searchRepairNeverChangesAdvancedCursorsOrMalformedBlobTypes() {
        val tool = tool("github_search")
        for (raw in listOf(
            """{"query":"*","index_cursor":2000,"max_new_blobs":0}""",
            """{"query":"*","search_id":"unissued","max_new_blobs":0}""",
            """{"query":"*","view_cursor":"unissued","max_new_blobs":0}""",
            """{"query":"*","page":2,"max_new_blobs":0}""",
            """{"query":"*","index_cursor":"0","max_new_blobs":0}""",
            """{"query":"*","max_new_blobs":"0"}""",
            """{"query":"*","max_new_blobs":0,"unknown":"not-allowed"}"""
        )) {
            val call = ProviderEvent.ToolCall("reject", tool.definition.name, Json.parseToJsonElement(raw).jsonObject)
            assertEquals(call, GitHubRecoveryCalls.repairSourceRead(call, tool))
        }
    }

    @Test fun unsafeOrAmbiguousSearchCorrectionsRemainRejectedWithoutDispatch() {
        val name = "github_search"
        for (value in listOf(
            """{"action":"search","query":"class","per_page":50,"view_cursor":"unissued"}""",
            """{"action":"search","query":"class","per_page":50,"index_cursor":4}""",
            """{"action":"search","query":"class","per_page":50,"search_id":"previous"}""",
            """{"action":"search","query":"class","per_page":50,"page":2}""",
            """{"action":"search","query":"class","per_page":50,"unknown":"unsafe"}""",
            """{"action":"search","per_page":50}""",
            """{"action":"search","query":" ","per_page":50}""",
            """{"action":"search","query":123,"per_page":50}"""
        )) {
            val call = ProviderEvent.ToolCall("search", name, Json.parseToJsonElement(value).jsonObject)
            assertEquals(call, GitHubRecoveryCalls.repairSourceRead(call, tool(name)))
        }
    }

    @Test fun malformedRecoveryLeavesUnsafePathsAndMixedSelectorsForRejection() {
        val name = "github_read"
        for (text in listOf(
            """{"path":"../secret.txt"}""",
            """{"path":"src/App.kt","view_cursor":"untrusted"}""",
            """{"path":"src/App.kt","start_line":0}""",
            """{"path":"src/App.kt","ranges":[{"path":"src/Other.kt"}]}"""
        )) {
            val call = ProviderEvent.ToolCall("read", name, Json.parseToJsonElement(text).jsonObject)
            assertEquals(call, GitHubRecoveryCalls.repairSourceRead(call, tool(name)))
            val mutation = call.copy(name = "github_write")
            assertEquals(mutation, GitHubRecoveryCalls.repairSourceRead(mutation, tool("github_write")))
        }
    }

    @Test fun build142ReadAndSearchShapesKeepTheirOriginalIdentity() {
        for ((name, raw, expected) in listOf(
            Triple(
                "github_read",
                """{"path":"AGENTS.md","start_line":1,"end_line":250,"response_detail":"full"}""",
                """{"action":"ranges","ranges":[{"path":"AGENTS.md","start_line":1,"end_line":250}],"response_detail":"full"}"""
            ),
            Triple(
                "github_read",
                """{"action":"ranges","ranges":[{"path":"AGENTS.md","end_line":-1,"max_lines":250}],"response_detail":"full"}""",
                """{"action":"ranges","ranges":[{"path":"AGENTS.md","max_lines":250}],"response_detail":"full"}"""
            ),
            Triple(
                "github_search",
                """{"query":"checkpoint","per_page":50,"response_detail":"full"}""",
                """{"query":"checkpoint"}"""
            )
        )) {
            val call = ProviderEvent.ToolCall("original", name, Json.parseToJsonElement(raw).jsonObject).captureProviderInvocation()
            val repaired = GitHubRecoveryCalls.repairSourceRead(call, tool(name))
            assertEquals(Json.parseToJsonElement(expected).jsonObject, repaired.arguments)
            assertEquals(call.providerInvocation(), repaired.providerInvocation())
            assertEquals(call.callId, repaired.callId)
        }
    }

    @Test fun retainedMutationRecipeIsNeverAutomaticallyExecutedOrUnwrapped() {
        val recipe = buildJsonObject {
            put("tool", "github_commit")
            put("arguments", buildJsonObject { put("message", "change") })
        }
        val result = AgentToolResult(
            "prior",
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.operation.v1")
                    put("recovery_call", recipe)
                }
            ),
            false
        )
        val call = ProviderEvent.ToolCall("next", "github_read", buildJsonObject { put("recovery_call", recipe) })
        val exchanges = listOf(AgentToolExchange(listOf(call.copy(callId = "prior")), listOf(result)))
        assertEquals(call, GitHubRecoveryCalls.resolve(call, exchanges, mapOf("github_commit" to tool("github_commit"))))
    }
}
