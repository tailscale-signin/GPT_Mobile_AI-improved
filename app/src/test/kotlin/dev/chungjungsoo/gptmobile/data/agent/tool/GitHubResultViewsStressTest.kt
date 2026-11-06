package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubResultViewsStressTest {
    @Test fun completeSourceAndRemoteRecipesAreRetainedOutsideTheDisplayPage() {
        val views = GitHubResultViews(pageBytes = 2048)
        val source = buildJsonObject {
            put("content", "large original source ".repeat(1000))
            put("continuation", Json.parseToJsonElement("""{"action":"list_branches","page":2}"""))
        }
        val raw = AgentToolResult("read", ToolResultContent.Json(source), false)
        val shown = views.present("scope", "github_context", "github_context__one", raw, Json.parseToJsonElement("""{"action":"branches"}""").jsonObject)
        assertEquals(raw.content, shown.retainedContent)
        assertEquals(JsonPrimitive(true), shown.executionReceipt?.get("retained_original_complete"))
        val recipe = shown.executionReceipt!!.getValue("retained_remote_continuations").jsonArray.single().jsonObject.getValue("call").jsonObject
        assertEquals(JsonPrimitive("github_context__one"), recipe["tool"])
        assertEquals(JsonPrimitive(2), recipe.getValue("arguments").jsonObject["page"])
        assertTrue(data(shown).toString().encodeToByteArray().size <= 2048)
        assertFalse(data(shown).containsKey("retained_remote_continuations"))
    }

    @Test fun alteredRecoveryCursorGetsExactIssuedCorrectionWithoutGrantingAnUnissuedOffset() {
        val views = GitHubResultViews(pageBytes = 2048)
        val first = data(views.present("scope", "github_read", "github_read", AgentToolResult("first", ToolResultContent.Json(JsonPrimitive("source")), false)))
        val issued = first.getValue("recovery_call").jsonObject.getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content
        val altered = issued.substringBeforeLast(':')
        val refused = views.read("scope", "github_read", "github_read", altered, "altered")
        assertEquals("SAVED_VIEW_CURSOR_ALTERED", refused.errorCode)
        assertEquals(JsonPrimitive(issued), data(refused).getValue("correction_call").jsonObject.getValue("arguments").jsonObject["view_cursor"])
        assertFalse(refused.dispatched)
        assertEquals("SAVED_VIEW_UNAVAILABLE", views.read("other", "github_read", "github_read", altered, "foreign").errorCode)
        assertEquals("SAVED_VIEW_UNAVAILABLE", views.read("scope", "github_read", "github_read", issued.substringBefore(':') + ":999", "guessed").errorCode)
    }

    @Test fun expiredImmutableViewsExposeExactRecoveryOnlyToTheirAuthorizedScope() {
        var clock = 0L
        val views = GitHubResultViews(now = { clock })
        val request = Json.parseToJsonElement("""{"owner":"o","repo":"r","ranges":[{"path":"A.kt","start_line":1,"end_line":10}]}""").jsonObject
        val sha = "a".repeat(40)
        val raw = AgentToolResult(
            "read",
            ToolResultContent.Json(
                buildJsonObject {
                    put("commit_sha", sha)
                    put("content", "exact source")
                }
            ),
            false
        )
        val first = data(views.present("scope", "github_read", "github_read__connection", raw, request))
        val recipe = first.getValue("source_recovery_call").jsonObject
        assertEquals(JsonObject(request + ("commit_sha" to JsonPrimitive(sha))), recipe.getValue("arguments"))
        val issued = first.getValue("recovery_call").jsonObject.getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content
        clock = 600_001
        val expired = data(views.read("scope", "github_read", "github_read__connection", issued, "expired"))
        assertEquals(JsonPrimitive("EXPIRED"), expired["reason"])
        assertEquals(recipe, expired["recovery_call"])
        val foreign = data(views.read("other", "github_read", "github_read__connection", issued, "foreign"))
        assertEquals(JsonPrimitive("UNAVAILABLE_IN_AUTHORIZED_SCOPE"), foreign["reason"])
        assertFalse(foreign.containsKey("recovery_call"))
        val mutation = data(views.present("scope", "github_commit", "github_commit", raw, request))
        assertFalse(mutation.containsKey("source_recovery_call"))
    }
    private fun data(result: AgentToolResult) = (result.content as ToolResultContent.Json).value.jsonObject
    private fun cursor(page: JsonObject) = page["continuation_call"]!!.jsonObject["arguments"]!!.jsonObject["view_cursor"]!!.jsonPrimitive.content

    @Test fun wrongOperationProvidesExactCorrectionOnlyInsideAuthorizedScope() {
        val views = GitHubResultViews(pageBytes = 2048)
        val raw = AgentToolResult("first", ToolResultContent.Json(JsonPrimitive("results ".repeat(1000))), false)
        val first = data(views.present("scope", "github_ci", "github_ci__account", raw))
        val issued = cursor(first)
        val wrong = views.read("scope", "github_read", "github_read__account", issued, "wrong")
        assertEquals("SAVED_VIEW_WRONG_OPERATION", wrong.errorCode)
        assertFalse(wrong.dispatched)
        val correction = data(wrong)["correction_call"]!!.jsonObject
        assertEquals(JsonPrimitive("github_ci__account"), correction["tool"])
        assertEquals(JsonPrimitive(issued), correction["arguments"]!!.jsonObject["view_cursor"])
        assertFalse(views.read("scope", "github_ci", "github_ci__account", issued, "corrected").isError)
        val foreign = views.read("different-scope", "github_read", "github_read__account", issued, "foreign")
        assertEquals("SAVED_VIEW_UNAVAILABLE", foreign.errorCode)
        assertFalse(foreign.content.toString().contains("correction_call"))
    }

    @Test fun everySavedErrorPageKeepsItsFailureClassificationWithoutRedispatch() {
        val views = GitHubResultViews(pageBytes = 2048)
        val raw = AgentToolResult("first", ToolResultContent.Json(JsonPrimitive("failure ".repeat(1000))), true, errorCode = "GITHUB_WRITE_BLOCKED")
        var current = views.present("scope", "github_read", "github_read", raw)
        var count = 0
        while (true) {
            assertTrue(current.isError)
            assertEquals("GITHUB_WRITE_BLOCKED", current.errorCode)
            if (count > 0) assertFalse(current.dispatched)
            val page = data(current)
            assertTrue(page.toString().toByteArray().size <= 2048)
            if (page["local_page_complete"]!!.jsonPrimitive.boolean) break
            current = views.read("scope", "github_read", "github_read", cursor(page), "next")
            assertTrue(++count < 100)
        }
        assertTrue(count > 1)
    }

    @Test fun multibyteTextReconstructsExactlyAcrossEveryUtf16Offset() {
        val views = GitHubResultViews(pageBytes = 2048)
        val source = "quoted \"line\"\n🍁漢字".repeat(200)
        val raw = AgentToolResult("first", ToolResultContent.Json(buildJsonObject { put("text", source) }), false)
        var page = data(views.present("scope", "github_read", "github_read", raw))
        val recovered = StringBuilder()
        var count = 0
        while (true) {
            assertTrue(page.toString().toByteArray().size <= 2048)
            for (fragment in page["fragments"]!!.jsonArray) {
                val row = fragment.jsonObject
                assertEquals("/text", row["pointer"]!!.jsonPrimitive.content)
                assertEquals(recovered.length, row["offset"]!!.jsonPrimitive.int)
                recovered.append(row["value"]!!.jsonPrimitive.content)
            }
            if (page["local_page_complete"]!!.jsonPrimitive.boolean) break
            page = data(views.read("scope", "github_read", "github_read", cursor(page), "next"))
            assertTrue(++count < 100)
        }
        assertEquals(source, recovered.toString())
    }

    @Test fun largeNumericScalarKeepsItsJsonTypeWhenReplayingASavedView() {
        val views = GitHubResultViews(pageBytes = 2048)
        val number = Json.parseToJsonElement("1".repeat(200))
        val raw = AgentToolResult("first", ToolResultContent.Json(number), false)
        val first = data(views.present("scope", "github_read", "github_read", raw))
        val recovery = first["recovery_call"]!!.jsonObject["arguments"]!!.jsonObject["view_cursor"]!!.jsonPrimitive.content
        val replay = data(views.read("scope", "github_read", "github_read", recovery, "replay"))
        val recovered = replay["fragments"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive
        assertFalse(recovered.isString)
        assertEquals(number, recovered)
    }

    @Test fun continuationPointersEscapeSlashAndTildeLikeDataPointers() {
        val views = GitHubResultViews()
        val raw = AgentToolResult(
            "first",
            ToolResultContent.Json(
                buildJsonObject {
                    put(
                        "/~_continuation",
                        buildJsonObject {
                            put("action", "list_commits")
                            put("page", 2)
                        }
                    )
                }
            ),
            false
        )
        val page = data(views.present("scope", "github_context", "github_context", raw))
        assertEquals("/~1~0_continuation", page["remote_continuations"]!!.jsonArray.single().jsonObject["pointer"]!!.jsonPrimitive.content)
    }

    @Test fun capacityRecoveryNarrowsTheSameImmutableFileOffsetAndDoesNotLoop() {
        val views = GitHubResultViews(capacityBytes = 100)
        val raw = AgentToolResult(
            "first",
            ToolResultContent.Json(
                buildJsonObject {
                    put("sha", "a".repeat(40))
                    put("data", "x".repeat(4000))
                }
            ),
            false
        )
        val request = buildJsonObject {
            put("action", "commit")
            put("page", 2)
            put("per_page", 30)
        }
        val first = data(views.present("scope", "github_context", "github_context", raw, request))
        val call = first["recovery_call"]!!.jsonObject["arguments"]!!.jsonObject
        assertEquals(JsonPrimitive(31), call["page"])
        assertEquals(JsonPrimitive(1), call["per_page"])
        assertEquals(JsonPrimitive(false), call["include_patches"])
        assertEquals(JsonPrimitive("a".repeat(40)), call["commit_sha"])
        assertEquals(emptyList<String>(), ToolArgumentValidator.errors(call, GitHubOperationTool.definition("github_context").inputSchema))
        val stillTooLarge = data(views.present("scope", "github_context", "github_context", raw, call))
        assertFalse("recovery_call" in stillTooLarge)
    }

    @Test fun longJsonPointersAreIncludedInFragmentByteAccounting() {
        val views = GitHubResultViews(pageBytes = 2048)
        val key = "k".repeat(320)
        val value = "v".repeat(380)
        val source = buildJsonObject { put(key, value) }
        val first = views.present("scope", "github_read", "github_read", AgentToolResult("first", ToolResultContent.Json(source), false))
        assertFalse(first.isError)
        assertEquals(source, data(first).getValue("data"))
        val recovery = data(first).getValue("recovery_call").jsonObject.getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content
        var page = data(views.read("scope", "github_read", "github_read", recovery, "recover"))
        val rebuilt = StringBuilder()
        var pages = 0
        while (true) {
            assertTrue(page.toString().encodeToByteArray().size <= 1536)
            for (fragment in page.getValue("fragments").jsonArray) {
                val row = fragment.jsonObject
                assertEquals("/$key", row.getValue("pointer").jsonPrimitive.content)
                assertEquals(rebuilt.length, row.getValue("offset").jsonPrimitive.int)
                rebuilt.append(row.getValue("value").jsonPrimitive.content)
            }
            if (page.getValue("local_page_complete").jsonPrimitive.boolean) break
            page = data(views.read("scope", "github_read", "github_read", cursor(page), "next"))
            assertTrue(++pages < 100)
        }
        assertEquals(value, rebuilt.toString())
    }

    @Test fun tagCommitContinuationUsesPinnedContextContractAndPreservesConnection() {
        val sha = "a".repeat(40)
        val source = Json.parseToJsonElement("""{"commit":{"sha":"$sha","continuation":{"action":"get_commit_detail","commit_sha":"$sha","ref":"$sha","page":2,"per_page":30,"include_patches":false}}}""").jsonObject
        val request = Json.parseToJsonElement("""{"action":"tag","tag_name":"v1","owner":"o","repo":"r"}""").jsonObject
        val first = data(GitHubResultViews().present("scope", "github_release", "github_release__active", AgentToolResult("tag", ToolResultContent.Json(source), false), request))
        val call = first.getValue("remote_continuations").jsonArray.single().jsonObject.getValue("call").jsonObject
        assertEquals(JsonPrimitive("github_context__active"), call["tool"])
        val args = call.getValue("arguments").jsonObject
        assertEquals(JsonPrimitive("commit"), args["action"])
        assertEquals(JsonPrimitive(sha), args["commit_sha"])
        assertEquals(JsonPrimitive(2), args["page"])
        assertFalse(args.containsKey("tag_name"))
        assertTrue(ToolArgumentValidator.errors(args, GitHubOperationTool.definition("github_context").inputSchema).isEmpty())
    }

    @Test fun presentationCapacityRetainsBoundedOriginalAndSeparateUpstreamOutcome() {
        val source = ToolResultContent.Json(
            buildJsonObject {
                put("sha", "a".repeat(40))
                put("receipt", "confirmed")
            }
        )
        val receipt = buildJsonObject { put("accepted_mutations", 1) }
        val original = AgentToolResult("write", source, false, executionReceipt = receipt)
        val result = GitHubResultViews(capacityBytes = 100).present("scope", "github_commit", "github_commit", original)
        assertFalse(result.isError)
        assertEquals(null, result.errorCode)
        assertEquals(original.dispatched, result.dispatched)
        assertEquals(source, result.retainedContent)
        assertEquals(JsonPrimitive(1), result.executionReceipt?.get("accepted_mutations"))
        assertEquals(JsonPrimitive(true), result.executionReceipt?.get("upstream_completed"))
        assertEquals(JsonPrimitive("COMPLETED"), result.executionReceipt?.get("upstream_execution_state"))
        assertEquals(JsonPrimitive("CAPACITY_REJECTED"), result.executionReceipt?.get("presentation_state"))
        assertEquals(JsonPrimitive("RETAINED_RESULT_CAPACITY"), result.executionReceipt?.get("presentation_error_code"))
        assertEquals(JsonPrimitive("UNAVAILABLE"), result.executionReceipt?.get("saved_view_persistence"))
        assertEquals(JsonPrimitive(true), result.executionReceipt?.get("original_available_for_retention"))
    }

    @Test fun presentationCapacityKeepsARefusedUpstreamOutcomeAndDoesNotInventADispatch() {
        val source = ToolResultContent.Json(buildJsonObject { put("message", "Access denied") })
        val original = AgentToolResult(
            "refused",
            source,
            true,
            dispatched = false,
            errorCode = "GITHUB_WRITE_BLOCKED",
            executionReceipt = buildJsonObject { put("accepted_mutations", 0) }
        )
        val result = GitHubResultViews(capacityBytes = 100).present("scope", "github_commit", "github_commit", original)
        assertTrue(result.isError)
        assertFalse(result.dispatched)
        assertEquals("GITHUB_WRITE_BLOCKED", result.errorCode)
        assertEquals(source, result.retainedContent)
        assertEquals(JsonPrimitive(0), result.executionReceipt?.get("accepted_mutations"))
        assertEquals(JsonPrimitive(false), result.executionReceipt?.get("upstream_completed"))
        assertEquals(JsonPrimitive("FAILED"), result.executionReceipt?.get("upstream_execution_state"))
        assertEquals(JsonPrimitive("GITHUB_WRITE_BLOCKED"), result.executionReceipt?.get("upstream_error_code"))
        assertEquals(JsonPrimitive("RETAINED_RESULT_CAPACITY"), result.executionReceipt?.get("presentation_error_code"))
        assertEquals(JsonPrimitive("UNAVAILABLE"), result.executionReceipt?.get("saved_view_persistence"))
    }
}
