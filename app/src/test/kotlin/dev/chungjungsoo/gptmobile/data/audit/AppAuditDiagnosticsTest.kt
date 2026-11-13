package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.agent.tool.McpLocalValidationRule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditDiagnosticsTest {
    @Test
    fun localValidationDiagnosticsUseTheSameFixedRulesAsTheTransport() {
        McpLocalValidationRule.entries.forEach { rule ->
            assertEquals(rule.name, safeAuditValidationRule(rule.name))
            assertEquals(rule.field, safeAuditDiagnosticField(rule.field))
            assertTrue(rule.diagnosticCode in AppAuditPluginProbeSource.reasonCodes)
        }
        assertEquals("oauth_refresh", safeAuditFailurePhase("oauth_refresh"))
        assertEquals("AUTHENTICATION_REQUIRED", safeAuditFailureCategory("AUTHENTICATION_REQUIRED"))
        assertEquals("OAUTH_REFRESH_UNAVAILABLE", safeAuditValidationRule("OAUTH_REFRESH_UNAVAILABLE"))
        assertEquals("PROTOCOL_VERSIONS_ARRAY_REQUIRED", safeAuditValidationRule("PROTOCOL_VERSIONS_ARRAY_REQUIRED"))
        assertEquals("error.data.supported", safeAuditDiagnosticField("error.data.supported"))
        assertEquals("catalogue", safeAuditFailurePhase("catalogue"))
        assertEquals("CATALOG_ROUTE_REQUIRED", safeAuditValidationRule("CATALOG_ROUTE_REQUIRED"))
        assertEquals("CAPABILITIES_OBJECT_REQUIRED", safeAuditValidationRule("CAPABILITIES_OBJECT_REQUIRED"))
        assertEquals("tools_list", safeAuditDiagnosticField("tools_list"))
        assertEquals("endpoint", safeAuditDiagnosticField("endpoint"))
    }

    @Test
    fun unknownProviderValuesAndPrivateKeysNeverBecomeDisplayOrExportCodes() {
        for (private in listOf(null, "PRIVATE_CREDENTIAL", "https://private.example/token", "result.private_account", "credential\nAuthorization")) {
            assertNull(safeAuditFailurePhase(private))
            assertNull(safeAuditFailureCategory(private))
            assertNull(safeAuditValidationRule(private))
            assertNull(safeAuditDiagnosticField(private))
            assertNull(safeAuditConfigurationRevision(private))
        }
        assertEquals("UNKNOWN_REASON", safeAuditReason("MCP_PRIVATE_PROVIDER_FAILURE"))
        assertEquals("a".repeat(64), safeAuditConfigurationRevision("a".repeat(64)))
        assertNull(appAuditConfigurationRevision("PRIVATE_CREDENTIAL"))
    }

    @Test
    fun typedCachedFailureRetainsItsOriginalObservationAcrossSerialization() {
        val observation = AppAuditObservation(
            AppAuditOutcome.BLOCKED,
            "MCP_DISCOVERY_SCHEMA_INVALID",
            stage = "mcp_discovery",
            evidenceKind = AppAuditEvidenceKind.LIVE_METADATA,
            observationSource = AppAuditObservationSource.CACHE,
            dispatchEvidence = AppAuditDispatchEvidence.NOT_DISPATCHED,
            retryable = false,
            httpStatus = 200,
            failurePhase = "protocol_negotiation",
            failureCategory = "PROTOCOL_CONFIGURATION",
            validationRule = "PROTOCOL_VERSIONS_ARRAY_REQUIRED",
            validationField = "supportedVersions",
            observedAtEpochMs = 12,
            configurationRevision = "a".repeat(64)
        )
        val result = AppAuditResult("cached", observation, 900, 901, 1, attempted = true)
        assertEquals(result, Json.decodeFromString<AppAuditResult>(Json.encodeToString(result)))
        assertEquals(12L, result.observation.observedAtEpochMs)
        assertEquals(900L, result.startedAtEpochMs)
        assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, result.dispatchEvidence)
    }
}
