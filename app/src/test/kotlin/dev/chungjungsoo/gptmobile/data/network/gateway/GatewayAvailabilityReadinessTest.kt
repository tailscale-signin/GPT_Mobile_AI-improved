package dev.chungjungsoo.gptmobile.data.network.gateway

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAvailabilityReadinessTest {
    @Test fun storageBlockerDoesNotBorrowAnUnrelatedBackendFailure() {
        val base = readinessFixture("selected", ready = false)
        val backend = JsonObject(
            (base["capabilities"] as JsonObject) + mapOf(
                "reasonCode" to JsonPrimitive("BACKEND_CONNECTION_REFUSED"),
                "dependency" to JsonPrimitive("model_backend"),
                "upstreamStatus" to JsonPrimitive(502)
            )
        )
        val result = parseGatewayReadiness(JsonObject(base + mapOf("capabilities" to backend, "reasonCode" to JsonPrimitive("DURABLE_STORAGE_UNAVAILABLE"))), 503, "selected", 100)
        assertEquals("DURABLE_STORAGE_UNAVAILABLE", result.reasonCode)
        assertEquals("durable_storage", result.dependency)
        assertEquals("restore_storage", result.recoveryAction)
        assertNull(result.upstreamStatus)
    }

    @Test fun selectedModelRecoveryHintAndBackendStatusSurviveUnavailableReadiness() {
        val base = readinessFixture("selected", ready = false)
        val backend = JsonObject(
            (base["capabilities"] as JsonObject) + mapOf(
                "reasonCode" to JsonPrimitive("SELECTED_MODEL_LOADING"),
                "dependency" to JsonPrimitive("selected_model"),
                "upstreamStatus" to JsonPrimitive(503)
            )
        )
        val result = parseGatewayReadiness(JsonObject(base + ("capabilities" to backend)), 503, "selected", 100)
        assertEquals("SELECTED_MODEL_LOADING", result.reasonCode)
        assertEquals("wait_for_model", result.recoveryAction)
        assertEquals(503, result.upstreamStatus)
        assertEquals("selected_model_loaded", gatewayRecoveryCondition(result.recoveryAction!!))
    }

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
        assertTrue(runCatching { parseGatewayReadiness(response, 200, "selected", 100) }.isFailure)
        assertNull(parseGatewayReadiness(base, 200, "selected", 100).canQueue)
    }

    @Test fun anotherModelAndContradictoryStatusRemainRejected() {
        val response = readinessFixture("selected")
        assertTrue(runCatching { parseGatewayReadiness(response, 200, "another", 100) }.isFailure)
        assertTrue(runCatching { parseGatewayReadiness(response, 503, "selected", 100) }.isFailure)
    }

    @Test fun originalProbeTimeAndMonotonicAgeArePreservedAcrossClientCaching() {
        val base = readinessFixture("selected")
        val capabilities = base["capabilities"] as JsonObject
        val response = JsonObject(
            base + mapOf(
                "serverTimeMs" to JsonPrimitive(80_000),
                "capabilities" to JsonObject(capabilities + mapOf("observedAtMs" to JsonPrimitive(60_001), "observationAgeMs" to JsonPrimitive(19_999)))
            )
        )
        val observation = parseGatewayReadiness(response, 200, "selected", 100, 10)
        assertEquals(60_001L, observation.observedAtMillis)
        assertEquals(19_999L, observation.observationAgeMillis)
        assertEquals(10L, observation.receivedAtMonotonicMillis)
        assertNull(parseGatewayReadiness(base, 200, "selected", 100).observationAgeMillis)
    }

    @Test fun malformedOrPartialAgeAndCapacityEvidenceCannotProveReadiness() {
        val base = readinessFixture("selected")
        val capabilities = base["capabilities"] as JsonObject
        for (fields in listOf(
            mapOf("observedAtMs" to JsonPrimitive(100)),
            mapOf("observationAgeMs" to JsonPrimitive(0)),
            mapOf("observedAtMs" to JsonPrimitive(100), "observationAgeMs" to JsonPrimitive("0")),
            mapOf("observedAtMs" to JsonPrimitive(100), "observationAgeMs" to JsonPrimitive(-1)),
            mapOf("canAccept" to JsonPrimitive("true")),
            mapOf("canQueue" to JsonPrimitive(1))
        )) {
            val response = JsonObject(base + ("capabilities" to JsonObject(capabilities + fields)))
            assertTrue(runCatching { parseGatewayReadiness(response, 200, "selected", 100) }.isFailure)
        }
        val negativeCapacity = JsonObject(base + ("components" to JsonObject(mapOf("workerCapacity" to JsonObject(mapOf("availableWorkers" to JsonPrimitive(-1)))))))
        assertTrue(runCatching { parseGatewayReadiness(negativeCapacity, 200, "selected", 100) }.isFailure)
    }
}
