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
}
