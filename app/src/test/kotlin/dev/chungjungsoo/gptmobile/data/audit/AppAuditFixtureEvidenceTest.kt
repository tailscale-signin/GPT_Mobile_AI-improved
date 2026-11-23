package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditFixtureEvidenceTest {
    @Test fun fixedEvidenceDropsPrivateProbeNamesUnknownToolCodesAndInvalidCounters() {
        val private = "PRIVATE_FIXTURE_PAYLOAD"
        val result = AgentToolResult(private, ToolResultContent.Text(private), isError = true, errorCode = private, dispatched = false)
        val state = appAuditFixtureState(result, usedCalls = -1, remainingCalls = Int.MAX_VALUE, invocationCount = 2)
        val evidence = AppAuditFixtureEvidence(
            AppAuditFixtureStatus.EXECUTED,
            1,
            0,
            listOf(
                AppAuditAssertionEvidence(
                    1,
                    AppAuditAssertionCode.ACCEPTED_CALL_RESERVES_BUDGET,
                    false,
                    AppAuditFixtureState(AppAuditFixtureCode.SUCCESS, dispatched = true),
                    state
                )
            )
        )
        val saved = checkNotNull(appAuditFixtureEvidenceJson(evidence, private))
        assertFalse(saved.toString().contains(private))
        val assertion = saved.getValue("assertions").jsonArray.single().jsonObject
        assertEquals("unknown_probe.assertion-1", assertion.getValue("id").jsonPrimitive.content)
        val observed = assertion.getValue("observed").jsonObject
        assertEquals("UNKNOWN_CODE", observed.getValue("code").jsonPrimitive.content)
        assertEquals(JsonNull, observed.getValue("usedCalls"))
        assertEquals(JsonNull, observed.getValue("remainingCalls"))
        assertEquals(2, observed.getValue("invocationCount").jsonPrimitive.int)
    }

    @Test fun boundedExportRetainsExactFailureIdAndDistinctPassedAttemptedTotals() {
        val assertions = (1..100).map { ordinal ->
            AppAuditAssertionEvidence(ordinal, AppAuditAssertionCode.RUNNER_RECEIPT_SUCCESS, ordinal < 100)
        }
        val saved = checkNotNull(appAuditFixtureEvidenceJson(AppAuditFixtureEvidence(AppAuditFixtureStatus.EXECUTED, 100, 99, assertions), "runner_isolation_v1"))
        assertEquals(100, saved.getValue("assertionsAttempted").jsonPrimitive.int)
        assertEquals(99, saved.getValue("assertionsPassed").jsonPrimitive.int)
        assertEquals(64, saved.getValue("retainedAssertions").jsonPrimitive.int)
        assertEquals(36, saved.getValue("omittedAssertions").jsonPrimitive.int)
        val failed = saved.getValue("assertions").jsonArray.last().jsonObject
        assertEquals("runner_isolation_v1.assertion-100", failed.getValue("id").jsonPrimitive.content)
        assertEquals("RUNNER_RECEIPT_SUCCESS", failed.getValue("code").jsonPrimitive.content)
        assertFalse(failed.getValue("passed").jsonPrimitive.boolean)
        assertEquals("FAIL", failed.getValue("outcome").jsonPrimitive.content)
        assertNull(appAuditFixtureEvidenceJson(AppAuditFixtureEvidence(AppAuditFixtureStatus.EXECUTED, 1, 2), "runner_isolation_v1"))
    }

    @Test fun privacyMetadataPreservesUnobservedAndChangedStatesWithoutReadingOrChangingPolicy() {
        assertNull(appAuditPrivacyObservation(null, null))
        val before = AppAuditPrivacyEvidence(true, 3)
        assertEquals(AppAuditPrivacyEvidence(true, 3, false), appAuditPrivacyObservation(before, before))
        assertEquals(AppAuditPrivacyEvidence(true, 3, true), appAuditPrivacyObservation(before, AppAuditPrivacyEvidence(false, 4)))
        assertEquals(before, appAuditPrivacyObservation(before, null))
        assertEquals(before, appAuditPrivacyObservation(null, before))
    }

    @Test fun legacyObservationsDecodeWithoutInventedFixturePrivacyOrRecoveryEvidence() {
        val legacy = Json.decodeFromString<AppAuditObservation>("""{"outcome":"PASS","reasonCode":"LOCAL_CONTRACT_PASSED"}""")
        assertNull(legacy.fixtureEvidence)
        assertNull(legacy.privacyPolicy)
        assertNull(legacy.recovery)
        val current = legacy.copy(
            fixtureEvidence = AppAuditFixtureEvidence(
                AppAuditFixtureStatus.SETUP_BLOCKED,
                1,
                0,
                listOf(AppAuditAssertionEvidence(1, AppAuditAssertionCode.ACCEPTED_CALL_RESERVES_BUDGET, false, blocked = true))
            ),
            privacyPolicy = AppAuditPrivacyEvidence(true, 7, false),
            recovery = AppAuditRecoveryEvidence("MCP_NETWORK_IO_FAILED", 4000, false)
        )
        assertEquals(current, Json.decodeFromString<AppAuditObservation>(Json.encodeToString(current)))
        assertTrue(checkNotNull(current.fixtureEvidence).assertions.single().blocked)
    }
}
