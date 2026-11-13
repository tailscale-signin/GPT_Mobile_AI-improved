package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSavedViewDiagnosticsTest {
    @Test fun savedPageFailureCarriesProvenOwnerExpiryAndSourceIdentityWithoutPayload() {
        var now = 0L
        val views = GitHubResultViews(now = { now })
        val first = views.present("private-scope", "github_issue", "github_issue__fixture", AgentToolResult("first", ToolResultContent.Json(buildJsonObject { put("title", "private-title") }), false))
        val body = (first.content as ToolResultContent.Json).value.jsonObject
        val cursor = body.getValue("recovery_call").jsonObject.getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content
        val wrong = views.read("private-scope", "github_search", "github_search__fixture", cursor, "wrong")
        assertEquals("SAVED_VIEW_WRONG_OPERATION", wrong.errorCode)
        val details = requireNotNull(wrong.executionReceipt)
        assertEquals(JsonPrimitive("github_issue"), details["saved_view_owner_operation"])
        assertEquals(JsonPrimitive(true), details["saved_view_cursor_issued"])
        assertEquals(JsonPrimitive(false), details["saved_view_expired"])
        for (field in listOf("saved_view_scope_hash", "saved_view_cursor_hash", "saved_view_source_hash")) {
            assertTrue((details[field] as JsonPrimitive).content.matches(Regex("[a-f0-9]{64}")))
        }
        assertFalse(details.toString().contains("private-"))
        assertFalse(details.toString().contains(cursor))
        now = 11 * 60_000L
        val expired = views.read("private-scope", "github_issue", "github_issue__fixture", cursor, "expired")
        assertEquals("SAVED_VIEW_UNAVAILABLE", expired.errorCode)
        assertEquals(JsonPrimitive(true), expired.executionReceipt?.get("saved_view_expired"))
        assertEquals(JsonPrimitive(true), expired.executionReceipt?.get("saved_view_cursor_issued"))
        assertEquals(details["saved_view_source_hash"], expired.executionReceipt?.get("saved_view_source_hash"))
        val foreign = views.read("another-scope", "github_issue", "github_issue__fixture", cursor, "foreign")
        assertEquals("SAVED_VIEW_UNAVAILABLE", foreign.errorCode)
        for (unknown in listOf("saved_view_owner_operation", "saved_view_source_hash", "saved_view_cursor_issued", "saved_view_expired")) assertFalse(unknown in requireNotNull(foreign.executionReceipt))
    }

    @Test fun diagnosticSourceIdentitySurvivesSavedViewRestartWithoutChangingCursorAuthorization() {
        val views = GitHubResultViews()
        val first = views.present("scope", "github_issue", "github_issue__fixture", AgentToolResult("first", ToolResultContent.Json(buildJsonObject { put("number", 1) }), false))
        val saved = requireNotNull(views.checkpoint("scope", first))
        val body = (first.content as ToolResultContent.Json).value as JsonObject
        val cursor = body.getValue("recovery_call").jsonObject.getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content
        val before = views.read("scope", "github_search", "github_search__fixture", cursor, "before")
        val restored = GitHubResultViews()
        assertTrue(restored.restore("scope", "github_issue", "github_issue__fixture", cursor, saved.second))
        val after = restored.read("scope", "github_search", "github_search__fixture", cursor, "after")
        assertEquals(before.executionReceipt, after.executionReceipt)
        assertFalse(restored.read("scope", "github_issue", "github_issue__fixture", cursor, "read").isError)
        assertFalse(restored.restore("scope", "github_search", "github_search__fixture", cursor, saved.second))
    }
}
