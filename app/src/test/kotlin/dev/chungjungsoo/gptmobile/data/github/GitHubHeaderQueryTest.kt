package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubHeaderQueryTest {
    @Test fun lookupErrorsKeepCauseStepAndDiscoveryGuidance() {
        val response = Json.parseToJsonElement("""{"errors":[{"type":"NOT_FOUND","message":"Could not resolve to an Issue"}],"data":{"repository":{"issue":null}}}""") as JsonObject
        val failure = runCatching { GitHubHeaderQuery.normalize(response, false) }.exceptionOrNull() as GitHubHeaderLookupFailure
        assertEquals("GITHUB_HEADER_QUERY_FAILED", failure.code)
        assertEquals(JsonPrimitive("graphql_header_lookup"), failure.details["failed_step"])
        assertTrue(failure.details["causes"].toString().contains("NOT_FOUND"))
        assertTrue(failure.details["next_action"].toString().contains("action=list"))
    }

    @Test fun emptyGraphQlErrorsDoNotInvalidateAnExistingResource() {
        val response = Json.parseToJsonElement("""{"errors":[],"data":{"repository":{"issue":{"number":1,"title":"Example","state":"OPEN"}}}}""") as JsonObject
        assertEquals(JsonPrimitive(1), GitHubHeaderQuery.normalize(response, false)["number"])
    }

    @Test fun applicationErrorRetainsSafeFullPathAndRequestedIssueIdentity() {
        val response = Json.parseToJsonElement("""{"errors":[{"type":"NOT_FOUND","path":["repository","issue",0,"number"],"message":"private body Bearer credential-value","extensions":{"secret":"do-not-retain"}}],"data":{"repository":{"issue":null}}}""") as JsonObject
        val args = Json.parseToJsonElement("""{"owner":"owner","repo":"project","issue_number":42,"token":"ghp_1234567890"}""") as JsonObject
        val failure = runCatching { GitHubHeaderQuery.normalize(response, false, args) }.exceptionOrNull() as GitHubHeaderLookupFailure
        assertEquals(JsonPrimitive("issue_headers"), failure.details["operation"])
        assertEquals(JsonPrimitive("issue"), failure.details["resource"])
        assertEquals(Json.parseToJsonElement("""{"owner":"owner","repo":"project","issue_number":42}"""), failure.details["resource_identity"])
        val cause = (failure.details.getValue("causes") as kotlinx.serialization.json.JsonArray).single() as JsonObject
        assertEquals(Json.parseToJsonElement("""["repository","issue",0,"number"]"""), cause["path"])
        assertEquals(JsonPrimitive(true), cause["path_complete"])
        assertEquals(JsonPrimitive(false), failure.details["snapshot_verified"])
        assertTrue(failure.details.toString().let { "private body" !in it && "credential-value" !in it && "do-not-retain" !in it && "ghp_" !in it })
    }

    @Test fun errorPathProjectionMarksSanitizedOrOverlongPathsIncomplete() {
        val path = kotlinx.serialization.json.JsonArray(List(65) { JsonPrimitive("repository") })
        val response = kotlinx.serialization.json.buildJsonObject {
            put(
                "errors",
                kotlinx.serialization.json.JsonArray(
                    listOf(
                        kotlinx.serialization.json.buildJsonObject {
                            put("type", JsonPrimitive("NOT_FOUND"))
                            put("path", path)
                        }
                    )
                )
            )
        }
        val failure = runCatching { GitHubHeaderQuery.normalize(response, true) }.exceptionOrNull() as GitHubHeaderLookupFailure
        val cause = (failure.details.getValue("causes") as kotlinx.serialization.json.JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive(false), cause["path_complete"])
        assertEquals(JsonPrimitive(65), cause["path_segment_count"])
        assertEquals(JsonPrimitive("pr_headers"), failure.details["operation"])
    }
}
