package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class McpReportDiagnosticsTest {
    @Test fun reportingAliasesPreserveTypedSearchAndValidationCategories() {
        assertEquals("SAVED_VIEW_UNAVAILABLE", IssueReportCodes.toolResult("GITHUB_SEARCH_CURSOR_UNAVAILABLE"))
        assertEquals("GITHUB_VALIDATION_FAILED", IssueReportCodes.toolResult("GITHUB_PR_VALIDATION_FAILED"))
    }

    @Test fun currentFailureDoesNotInheritRetainedConnectionCauseOrDispatch() {
        val fields = McpReportDiagnostics.fields("private-connection", "configuration", "UNKNOWN", "UNREACHABLE", "STALE", "CONNECTION_REFUSED", 800)
        val diagnostic = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields + mapOf("diagnostic_area" to "TOOL"), 1_800_000_000_000))
        assertEquals(JsonPrimitive("UNKNOWN"), diagnostic.values["mcp_failure_category"])
        assertEquals(JsonPrimitive("CONNECTION_REFUSED"), diagnostic.values["mcp_connection_health"])
        assertEquals(JsonPrimitive("RETAINED_SNAPSHOT"), diagnostic.values["mcp_health_observation"])
        assertEquals(JsonPrimitive(800), diagnostic.values["mcp_health_age_ms"])
        assertFalse(diagnostic.values.toString().contains("private"))
        assertNull(diagnostic.values["dispatched"])
        assertNull(diagnostic.values["retryable"])
    }

    @Test fun missingHealthAndUnknownValuesStayUnavailableWithoutEndpointLeakage() {
        val fields = McpReportDiagnostics.fields(null, "initialization", "CONNECTION_REFUSED")
        assertEquals(JsonPrimitive("UNAVAILABLE"), fields["mcp_health_observation"])
        assertNull(fields["mcp_connection_hash"])
        assertNull(fields["mcp_readiness_state"])
        val invalid = McpReportDiagnostics.fields("private", "http://private:8101", "private-server-error", observedAgeMs = -1)
        assertNull(invalid["mcp_failure_phase"])
        assertNull(invalid["mcp_failure_category"])
        assertNull(invalid["mcp_health_age_ms"])
        assertFalse(invalid.toString().contains("private"))
    }
}
