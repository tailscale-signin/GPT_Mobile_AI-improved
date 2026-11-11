package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpDiscoveryDiagnosticsTest {
    @Test fun discoveryShapeReportsMissingOrMalformedVersionsWithoutServerFreeText() {
        val malformed = kotlinx.serialization.json.Json.parseToJsonElement("""{"supportedVersions":["2026-07-28","private-token"],"capabilities":{},"private-server-key":"private-body","instructions":"private-instructions"}""") as kotlinx.serialization.json.JsonObject
        val diagnostic = mcpDiscoveryResponseShape(malformed)
        assertTrue(diagnostic.contains("protocolSummary=2026-07-28,invalid"))
        assertTrue(diagnostic.contains("unrecognizedFieldCount=1"))
        assertFalse(diagnostic.contains("private"))
        val missing = mcpDiscoveryResponseShape(kotlinx.serialization.json.JsonObject(emptyMap()))
        assertTrue(missing.contains("supportedVersionsType=missing"))
    }

    @Test fun refreshIdentityDistinguishesExplicitAndAutomaticProbesWithoutExposingConnectionSecrets() {
        val config = McpConnectionConfig("private-connection", "https://private.example/mcp?token=hidden", false, "Bearer secret", "private-api-key", "a".repeat(64))
        val automatic = mcpDiscoveryDiagnostic(config, McpDiscoveryTrigger.AUTOMATIC_HEALTH, false, McpDiscoveryDecision.PAUSED_CONFIGURATION)
        val explicit = mcpDiscoveryDiagnostic(config, McpDiscoveryTrigger.USER_RECONNECT, true, McpDiscoveryDecision.START_ACQUISITION)
        assertTrue(automatic.contains("trigger=AUTOMATIC_HEALTH"))
        assertTrue(automatic.contains("decision=PAUSED_CONFIGURATION"))
        assertTrue(explicit.contains("trigger=USER_RECONNECT"))
        assertTrue(explicit.contains("configurationRevision=${"a".repeat(64)}"))
        for (message in listOf(automatic, explicit)) {
            assertFalse(message.contains("private"))
            assertFalse(message.contains("Bearer"))
            assertFalse(message.contains("secret"))
            assertFalse(message.contains("hidden"))
        }
        val unsafe = McpConnectionConfig("same", "https://example.com/mcp", false, configurationRevision = "token=private")
        assertTrue(mcpDiscoveryDiagnostic(unsafe, McpDiscoveryTrigger.AUDIT_REFRESH, true, McpDiscoveryDecision.REQUESTED).contains("configurationRevision=unobserved"))
    }
}
