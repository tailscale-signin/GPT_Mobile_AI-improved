package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubToolErrorDiagnosticsTest {
    @Test fun validationCaptureRetainsOnlyClosedServerCategoriesAndHashedRequestIdentity() {
        val details = GitHubToolErrorDiagnostics.response(
            """{"message":"secret-branch","errors":[{"resource":"PullRequest","field":"head","code":"invalid","value":"private/repo","message":"sensitive-detail"},{"resource":"PullRequest","field":"base","code":"missing_field"},{"resource":"secret-resource","field":"secret-field","code":"secret-code"}]}""",
            "private-request-id"
        )
        assertEquals(JsonArray(listOf(JsonPrimitive("PullRequest"))), details["github_error_resources"])
        assertEquals(JsonArray(listOf(JsonPrimitive("base"), JsonPrimitive("head"))), details["github_error_fields"])
        assertEquals(JsonArray(listOf(JsonPrimitive("invalid"), JsonPrimitive("missing_field"))), details["github_error_codes"])
        assertTrue((details["server_request_id_hash"] as JsonPrimitive).content.matches(Regex("[a-f0-9]{64}")))
        for (secret in listOf("secret-", "private/", "sensitive-detail", "private-request-id")) assertFalse(details.toString().contains(secret))
    }

    @Test fun malformedOrOversizedErrorsRemainBoundedWithoutMaskingTheOriginalFailure() {
        for (body in listOf("not JSON", "[]", """{"errors":["unknown server message",null,42]}""", "x".repeat(256 * 1024 + 1))) {
            val details = GitHubToolErrorDiagnostics.response(body, null)
            assertEquals(JsonArray(emptyList()), details["github_error_resources"])
            assertEquals(JsonArray(emptyList()), details["github_error_fields"])
            assertEquals(JsonArray(emptyList()), details["github_error_codes"])
            assertFalse("server_request_id_hash" in details)
        }
    }
}
