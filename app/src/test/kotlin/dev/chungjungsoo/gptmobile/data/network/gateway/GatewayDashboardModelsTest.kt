package dev.chungjungsoo.gptmobile.data.network.gateway

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayDashboardModelsTest {
    @Test fun unavailableFlagsOverrideAnyPlaceholderValues() {
        val source = dashboardFixture()
        val snapshot = parseGatewayDashboard(JsonObject(source + ("availability" to JsonObject(emptyMap()))))
        assertNull(snapshot.outputTokens)
        assertNull(snapshot.tokensPerSecond)
        assertNull(snapshot.openJobs)
        assertNull(snapshot.devices)
        assertNull(snapshot.activities)
        assertNull(snapshot.activeWorkers)
        assertFalse(snapshot.historyAvailable)
    }

    @Test fun missingOrInvalidNumbersRemainUnknownWhileMeasuredZeroStaysZero() {
        val source = dashboardFixture()
        val usage = source["usage"] as JsonObject
        val snapshot = parseGatewayDashboard(JsonObject(source + ("usage" to JsonObject(usage + mapOf("outputTokens" to JsonPrimitive("0"), "tokensPerSecond" to JsonPrimitive(-1), "unmeasuredRounds" to JsonPrimitive(0))))))
        assertNull(snapshot.outputTokens)
        assertNull(snapshot.tokensPerSecond)
        assertEquals(0L, snapshot.unmeasuredRounds)
        assertEquals(2345L, snapshot.days.single().outputTokens)
    }

    @Test fun historyRowsCannotBeUsedToInventTheCompleteOpenJobCount() {
        val snapshot = parseGatewayDashboard(JsonObject(dashboardFixture() + ("openJobs" to JsonNull)))
        assertTrue(snapshot.activities.orEmpty().any { it.status == "running" })
        assertNull(snapshot.openJobs)
    }

    @Test fun malformedAvailableListsDoNotBecomeZeroDeviceObservations() {
        val snapshot = parseGatewayDashboard(JsonObject(dashboardFixture() + mapOf("devices" to JsonNull, "activities" to JsonNull, "jobs" to JsonNull, "engineering" to JsonNull)))
        assertNull(snapshot.devices)
        assertNull(snapshot.activities)
        assertFalse(snapshot.historyAvailable)
    }

    @Test fun boundedProjectionIgnoresPrivateUnknownFields() {
        val source = dashboardFixture()
        val one = (source["devices"] as JsonArray).single() as JsonObject
        val devices = JsonArray((1..160).map { JsonObject(one + mapOf("address" to JsonPrimitive("192.168.1.$it"), "prompt" to JsonPrimitive("private text"))) })
        val snapshot = parseGatewayDashboard(JsonObject(source + ("devices" to devices)))
        assertEquals(128, snapshot.devices?.size)
        assertFalse(snapshot.toString().contains("private text"))
    }

    @Test fun unsupportedIdentityOrScopeFailsClosed() {
        val source = dashboardFixture()
        listOf("schema" to JsonPrimitive("other"), "instanceId" to JsonPrimitive(""), "scope" to JsonPrimitive("all_accounts")).forEach { entry ->
            assertTrue(runCatching { parseGatewayDashboard(JsonObject(source + entry)) }.isFailure)
        }
    }
}
