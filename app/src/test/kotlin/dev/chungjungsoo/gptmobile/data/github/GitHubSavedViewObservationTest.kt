package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSavedViewObservationTest {
    private val head = "a".repeat(40)
    private val request = buildJsonObject {
        put("action", "jobs")
        put("run_id", 7)
    }

    private fun source(characters: Int = 5000) = AgentToolResult(
        "source",
        ToolResultContent.Json(
            buildJsonObject {
                put("run_id", 7)
                put("head_sha", head)
                put("log", "x".repeat(characters))
            }
        ),
        false,
        dispatched = true,
        executionReceipt = buildJsonObject { put("read_requests", 1) }
    )

    private fun body(result: AgentToolResult) = (result.content as ToolResultContent.Json).value.jsonObject
    private fun cursor(result: AgentToolResult) = body(result).getValue("recovery_call").jsonObject
        .getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content
    private fun call(id: String, cursor: String) = ProviderEvent.ToolCall(id, "github_ci", buildJsonObject { put("view_cursor", cursor) })

    @Test fun originalObservationSurvivesFragmentPagingAndProcessRestoration() = runBlocking {
        for (characters in listOf(5, 5000, 1_100_000)) {
            var clock = 1000L
            val saved = linkedMapOf<String, JsonObject>()
            fun book() = GitHubEvidenceBook(
                "task", "owner/repo", "main", setOf("github_ci"), now = { clock },
                load = { saved[it] }, save = { id, value -> saved[id] = value },
                retainedIds = { saved.keys.filter { it.startsWith("ev_") } },
                runtimeIdentity = { JsonObject(emptyMap()) }
            )
            val views = GitHubResultViews(now = { clock }, pageBytes = 2048)
            val presented = views.present("scope", "github_ci", "github_ci", source(characters), request)
            val first = book()
            first.observe(ProviderEvent.ToolCall("source", "github_ci", request), presented)
            val original = first.snapshot().first.values.single()
            val checkpoint = requireNotNull(views.checkpoint("scope", presented)).second
            val handle = cursor(presented)
            clock = 2000L
            val restoredViews = GitHubResultViews(now = { clock }, pageBytes = 2048)
            assertTrue(restoredViews.restore("scope", "github_ci", "github_ci", handle, checkpoint))
            val restoredBook = book()
            restoredBook.restore()
            val page = restoredViews.read("scope", "github_ci", "github_ci", handle, "page")
            restoredBook.observe(call("page", handle), page)
            val (receipts, epoch) = restoredBook.snapshot()
            val row = receipts.values.single { it["call_id"] == JsonPrimitive("page") }
            assertEquals(JsonPrimitive(1000), row["observed_at_ms"])
            assertEquals(original["epoch"], row["epoch"])
            assertEquals(JsonPrimitive(head), row["source_head_sha"])
            assertEquals(original["receipt_id"], row["source_observation_receipt_id"])
            assertEquals(JsonPrimitive("CACHED"), row["evidence_origin"])
            assertEquals(JsonPrimitive(false), row["dispatched"])
            assertEquals(JsonPrimitive(false), row["head_observation"])
            val claim = buildJsonObject {
                put("receipt_id", row.getValue("receipt_id"))
                put("pointer", "/metadata/head_sha")
                put("value", head)
            }
            assertEquals(JsonPrimitive("VERIFIED"), GitHubEvidencePolicy.verify(claim, receipts, clock, epoch)["status"])
            val diagnosis = buildJsonObject {
                put(
                    "claims",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("kind", "inferred")
                                put("text", "The retained job observation identifies the revision for this investigation.")
                                put("sources", JsonArray(listOf(claim)))
                            },
                            buildJsonObject {
                                put("kind", "recommendation")
                                put("text", "Inspect the failed job logs at this recorded revision before choosing a repair.")
                                put("sources", JsonArray(listOf(claim)))
                            }
                        )
                    )
                )
            }
            val (text, report) = GitHubEvidencePolicy.render(diagnosis, receipts, clock, epoch, GitHubWorkflowPreset.selected("diagnose_ci").prompt())
            assertTrue(text.contains("The retained job observation identifies the revision"))
            assertTrue(text.contains("Inspect the failed job logs at this recorded revision"))
            assertFalse(text.contains("Unverified interpretation"))
            assertFalse(text.contains("published implementation receipt"))
            val renderedClaims = report.getValue("claims").jsonArray
            assertEquals(2, renderedClaims.size)
            for (renderedClaim in renderedClaims) {
                assertEquals(JsonPrimitive("INFERRED"), renderedClaim.jsonObject["status"])
                assertEquals(JsonPrimitive("VERIFIED"), renderedClaim.jsonObject.getValue("sources").jsonArray.single().jsonObject["status"])
            }
            assertEquals(JsonArray(emptyList()), report["unresolved_claims"])
            // Supported advice remains inference, even when its source facts verify.
            assertEquals(JsonPrimitive(false), report["claims_verified"])
            assertEquals(JsonPrimitive(false), report["completion_verified"])
        }
    }

    @Test fun readingAnOldPageDoesNotRefreshItsEpochAfterTheBranchChanges() = runBlocking {
        var clock = 1000L
        val book = GitHubEvidenceBook("task", "owner/repo", "main", setOf("github_ci", "github_context"), now = { clock }, runtimeIdentity = { JsonObject(emptyMap()) })
        suspend fun observeHead(id: String, sha: String) {
            book.observe(
                ProviderEvent.ToolCall(id, "github_context", buildJsonObject { put("action", "status") }),
                AgentToolResult(id, ToolResultContent.Json(buildJsonObject { put("head_sha", sha) }), false)
            )
        }
        observeHead("initial-head", head)
        val views = GitHubResultViews(now = { clock }, pageBytes = 2048)
        val presented = views.present("scope", "github_ci", "github_ci", source(), request)
        book.observe(ProviderEvent.ToolCall("source", "github_ci", request), presented)
        val handle = cursor(presented)
        clock = 2000L
        observeHead("next-head", "b".repeat(40))
        book.observe(call("page", handle), views.read("scope", "github_ci", "github_ci", handle, "page"))
        val (receipts, epoch) = book.snapshot()
        val row = receipts.values.single { it["call_id"] == JsonPrimitive("page") }
        assertEquals(1L, epoch)
        assertEquals(JsonPrimitive(0), row["epoch"])
        assertEquals(JsonPrimitive(1000), row["observed_at_ms"])
        assertEquals(JsonPrimitive(false), row["immutable"])
        val claim = buildJsonObject {
            put("receipt_id", row.getValue("receipt_id"))
            put("pointer", "/metadata/head_sha")
            put("value", head)
        }
        val checked = GitHubEvidencePolicy.verify(claim, receipts, clock, epoch)
        assertEquals(JsonPrimitive("STALE"), checked["status"])
        assertEquals(JsonPrimitive("MUTABLE_EVIDENCE_EXPIRED_OR_REVISION_CHANGED"), checked["reason"])
    }

    @Test fun missingOrMismatchedSourceCannotBorrowAnotherObservationTime() = runBlocking {
        val book = GitHubEvidenceBook("task", "owner/repo", "main", setOf("github_ci"), now = { 1000L }, runtimeIdentity = { JsonObject(emptyMap()) })
        val views = GitHubResultViews(pageBytes = 2048)
        val presented = views.present("scope", "github_ci", "github_ci", source(), request)
        val handle = cursor(presented)
        val page = views.read("scope", "github_ci", "github_ci", handle, "missing")
        book.observe(call("missing", handle), page)
        book.observe(ProviderEvent.ToolCall("source", "github_ci", request), presented)
        val mismatch = page.copy(
            callId = "mismatch",
            executionReceipt = JsonObject(page.executionReceipt.orEmpty() + ("saved_view_source_sha256" to JsonPrimitive("b".repeat(64))))
        )
        book.observe(call("mismatch", handle), mismatch)
        for (row in book.snapshot().first.values.filter { it["call_id"] != JsonPrimitive("source") }) {
            assertEquals(kotlinx.serialization.json.JsonNull, row["observed_at_ms"])
            assertNull(row["source_observation_receipt_id"])
            assertFalse(row["dispatched"] == JsonPrimitive(true))
        }
    }

    @Test fun legacyPageRequiresOneOriginalAndDoesNotChooseANewerIdenticalRead() = runBlocking {
        var clock = 1000L
        val book = GitHubEvidenceBook("task", "owner/repo", "main", setOf("github_ci"), now = { clock }, runtimeIdentity = { JsonObject(emptyMap()) })
        val views = GitHubResultViews(pageBytes = 2048)
        val presented = views.present("scope", "github_ci", "github_ci", source(), request)
        val handle = cursor(presented)
        val retained = presented.copy(executionReceipt = JsonObject(presented.executionReceipt.orEmpty() + ("saved_view_id" to JsonPrimitive(handle.substringBefore(':')))))
        book.observe(ProviderEvent.ToolCall("source", "github_ci", request), retained)
        fun legacy(id: String): AgentToolResult {
            val page = views.read("scope", "github_ci", "github_ci", handle, id)
            return page.copy(executionReceipt = JsonObject(page.executionReceipt.orEmpty() - "saved_view_source_call_id"))
        }
        book.observe(call("unique", handle), legacy("unique"))
        clock = 2000L
        book.observe(ProviderEvent.ToolCall("newer", "github_ci", request), retained.copy(callId = "newer"))
        book.observe(call("ambiguous", handle), legacy("ambiguous"))
        val rows = book.snapshot().first.values.associateBy { it.getValue("call_id").jsonPrimitive.content }
        assertEquals(JsonPrimitive(1000), rows.getValue("unique")["observed_at_ms"])
        assertEquals(kotlinx.serialization.json.JsonNull, rows.getValue("ambiguous")["observed_at_ms"])
        assertNull(rows.getValue("ambiguous")["source_observation_receipt_id"])
    }

    @Test fun missingLegacyOriginalCannotUseALaterIdenticalReadFromAnotherView() = runBlocking {
        val book = GitHubEvidenceBook("task", "owner/repo", "main", setOf("github_ci"), now = { 2000L }, runtimeIdentity = { JsonObject(emptyMap()) })
        val views = GitHubResultViews(pageBytes = 2048)
        val original = views.present("scope", "github_ci", "github_ci", source(), request)
        val later = views.present("scope", "github_ci", "github_ci", source().copy(callId = "later"), request)
        val laterHandle = cursor(later)
        book.observe(
            ProviderEvent.ToolCall("later", "github_ci", request),
            later.copy(executionReceipt = JsonObject(later.executionReceipt.orEmpty() + ("saved_view_id" to JsonPrimitive(laterHandle.substringBefore(':')))))
        )
        val handle = cursor(original)
        val page = views.read("scope", "github_ci", "github_ci", handle, "legacy")
        book.observe(call("legacy", handle), page.copy(executionReceipt = JsonObject(page.executionReceipt.orEmpty() - "saved_view_source_call_id")))
        val row = book.snapshot().first.values.single { it["call_id"] == JsonPrimitive("legacy") }
        assertEquals(kotlinx.serialization.json.JsonNull, row["observed_at_ms"])
        assertNull(row["source_observation_receipt_id"])
    }
}
