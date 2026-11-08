package dev.chungjungsoo.gptmobile.data.network.gateway

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAvailabilityReadinessTest {
    @Test fun busyCapacityAndAdmissionAreReadFromExplicitTypedFields() {
        val base = readinessFixture("selected")
        val capabilities = base["capabilities"] as JsonObject
        val response = JsonObject(
            base + mapOf(
                "capabilities" to JsonObject(capabilities + mapOf("canAccept" to JsonPrimitive(true), "canQueue" to JsonPrimitive(true))),
                "components" to JsonObject(mapOf("workerCapacity" to JsonObject(mapOf("availableWorkers" to JsonPrimitive(0)))))
            )
        )
        val observed = parseGatewayReadiness(response, 200, "selected", 100)
        assertEquals(0L, observed.availableWorkers)
        assertEquals(true, observed.canAccept)
        assertEquals(true, observed.canQueue)
        assertEquals(100L, observed.receivedAtMillis)
    }

    @Test fun missingOrStringAdmissionFlagsAndInvalidCapacityNeverProveQueueAcceptance() {
        val base = readinessFixture("selected")
        val capabilities = base["capabilities"] as JsonObject
        val response = JsonObject(
            base + mapOf(
                "capabilities" to JsonObject(capabilities + mapOf("canAccept" to JsonPrimitive("true"), "canQueue" to JsonPrimitive("true"))),
                "components" to JsonObject(mapOf("workerCapacity" to JsonObject(mapOf("availableWorkers" to JsonPrimitive(-1)))))
            )
        )
        val observed = parseGatewayReadiness(response, 200, "selected", 100)
        assertNull(observed.canAccept)
        assertNull(observed.canQueue)
        assertNull(observed.availableWorkers)
        assertNull(parseGatewayReadiness(base, 200, "selected", 100).canQueue)
    }

    @Test fun anotherModelAndContradictoryStatusRemainRejected() {
        val response = readinessFixture("selected")
        assertTrue(runCatching { parseGatewayReadiness(response, 200, "another", 100) }.isFailure)
        assertTrue(runCatching { parseGatewayReadiness(response, 503, "selected", 100) }.isFailure)
    }
}
