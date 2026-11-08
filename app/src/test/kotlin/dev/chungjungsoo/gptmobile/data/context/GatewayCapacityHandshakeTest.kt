package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCapacityHandshakeTest {
    private val config = ProviderRequestConfig("http://127.0.0.1:29781/v1", "selected-token", profileUid = "capacity-handshake")
    private val observation = Json.parseToJsonElement(
        """{"schema":"gptmobile.gateway.selected-model.v1","schemaVersion":1,"requestedModel":"selected-model",
           "reportedModel":"selected-model","contextTokens":8192,"maxOutputTokens":1024,"limitsVerified":true,
           "outputLimitSource":"provider_reported","capabilityRevision":"${"a".repeat(64)}","observedAtMillis":1793960000000,
           "evidenceOrigin":"GATEWAY_OBSERVED_PROVIDER_METADATA"}"""
    ).jsonObject

    private fun handshake(selected: JsonObject = observation): String = JsonObject(
        mapOf("contractVersion" to JsonPrimitive(1), "gatewayVersion" to JsonPrimitive("15.0.0"), "instanceId" to JsonPrimitive("b".repeat(32)), "selectedModel" to selected)
    ).toString()

    private fun control(): JsonObject = Json.parseToJsonElement(
        """{"schemaVersion":1,"parameter":"enable_thinking","templateProbeVerified":true,
            "evidenceOrigin":"GATEWAY_OBSERVED_TEMPLATE_PROBE","capabilityRevision":"${"a".repeat(64)}",
            "templateDigest":"${"c".repeat(64)}","enabledPromptDigest":"${"d".repeat(64)}",
            "disabledPromptDigest":"${"e".repeat(64)}","observedAtMillis":1793960000000}"""
    ).jsonObject

    @Test fun anObservedGatewayDoesNotProbeAdministrativePropsToUseVerifiedReasoningControl() = runTest {
        withContext(Dispatchers.Default) {
            val selected = config.copy(apiUrl = "http://127.0.0.1:29881/v1")
            dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics.observeInstance(selected, "b".repeat(32))
            var calls = 0
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals("/v1/gateway/capabilities", request.url.encodedPath)
                    respond(handshake(JsonObject(observation + ("reasoningControl" to control()))))
                }
            ).use { http ->
                val repository = LlamaCapacityRepository({ http })
                val capacity = requireNotNull(repository.discover(selected, "selected-model"))
                assertTrue(repository.hasExactModelProof(selected, "selected-model", capacity))
                assertEquals(ReasoningTemplateControl.ENABLE_THINKING, repository.reasoningControl(selected, "selected-model", capacity))
                assertEquals(1, calls)
                assertEquals(ReasoningMode.OFF, ReasoningPolicy.resolve(ReasoningMode.OFF, true, repository.reasoningControl(selected, "selected-model", capacity)).effective)
            }
        }
    }

    @Test fun gatewayReasoningProofRejectsChangedModelRevisionAndUnmeasuredToggles() {
        val capacity = requireNotNull(parseGatewayCapacity(handshake(), config, "selected-model", 1))
        fun body(proof: JsonObject) = handshake(JsonObject(observation + ("reasoningControl" to proof)))
        assertEquals(ReasoningTemplateControl.ENABLE_THINKING, parseGatewayReasoningControl(body(control()), capacity))
        for (change in listOf(
            "capabilityRevision" to JsonPrimitive("f".repeat(64)),
            "templateProbeVerified" to JsonPrimitive(false),
            "templateProbeVerified" to JsonPrimitive("true"),
            "parameter" to JsonPrimitive("reasoning_effort"),
            "enabledPromptDigest" to JsonPrimitive("e".repeat(64)),
            "observedAtMillis" to JsonPrimitive(0),
            "evidenceOrigin" to JsonPrimitive("MODEL_CATALOG")
        )) {
            assertNull(parseGatewayReasoningControl(body(JsonObject(control() + change)), capacity))
        }
        val changedModel = JsonObject(observation + ("reasoningControl" to control()) + ("reportedModel" to JsonPrimitive("other-model")))
        assertNull(parseGatewayReasoningControl(handshake(changedModel), capacity))
    }

    @Test fun restrictedPropsUseOnlyTheAlreadyIdentifiedGatewaysExactSelectedModel() = runTest {
        withContext(Dispatchers.Default) {
            val paths = mutableListOf<String>()
            HttpClient(
                MockEngine { request ->
                    paths += request.url.encodedPath
                    assertEquals("GET", request.method.value)
                    assertEquals("Bearer selected-token", request.headers["Authorization"])
                    assertEquals("selected-model", request.url.parameters["model"])
                    when (request.url.encodedPath) {
                        "/props" -> respond("restricted", HttpStatusCode.Forbidden, headersOf("X-Gateway-Instance-ID", "b".repeat(32)))
                        "/v1/gateway/capabilities" -> respond(handshake(), HttpStatusCode.OK)
                        else -> error("Unexpected route")
                    }
                }
            ).use { http ->
                val repository = LlamaCapacityRepository({ http })
                val capacity = repository.discover(config, "selected-model")!!
                assertEquals(8192L, capacity.contextTokens)
                assertEquals(1024L, capacity.outputTokens)
                assertEquals(CapacityProvenance.VERIFIED_ROUTE, capacity.provenance)
                assertEquals("selected-model", capacity.fingerprint.requestedModel)
                assertEquals("selected-model", capacity.fingerprint.resolvedModel)
                assertTrue(capacity.fingerprint.runtimeRevision.startsWith("${"b".repeat(32)}:"))
                // The first headers establish a gateway epoch; the next stable
                // route read can refresh once under that new cache identity.
                repository.discover(config, "selected-model")
                val stableCalls = paths.size
                repository.discover(config, "selected-model")
                assertEquals(stableCalls, paths.size)
                assertEquals(listOf("/props", "/v1/gateway/capabilities"), paths.take(2))
            }
        }
    }

    @Test fun unobservedConnectionDoesNotAcquireAnotherDiscoveryPath() = runTest {
        var calls = 0
        HttpClient(
            MockEngine {
                calls++
                error("No gateway has been identified")
            }
        ).use { http ->
            val fresh = config.copy(profileUid = "unobserved-capacity", apiUrl = "http://127.0.0.1:29782/v1")
            assertNull(LlamaCapacityRepository({ http }).discoverGateway(fresh, "selected-model"))
            assertEquals(0, calls)
        }
    }

    @Test fun exactModelAndVerifiedEvidenceAreRequiredBeforeReplacingTheCatalogCeiling() {
        for (change in listOf(
            mapOf("reportedModel" to JsonPrimitive("another-model")),
            mapOf("requestedModel" to JsonPrimitive("another-model")),
            mapOf("limitsVerified" to JsonPrimitive(false)),
            mapOf("evidenceOrigin" to JsonPrimitive("MODEL_CATALOG")),
            mapOf("capabilityRevision" to JsonNull),
            mapOf("contextTokens" to JsonPrimitive("1048576")),
            mapOf("maxOutputTokens" to JsonPrimitive(9000))
        )) {
            assertNull(parseGatewayCapacity(handshake(JsonObject(observation + change)), config, "selected-model", 1))
        }
        assertNull(parseGatewayCapacity("""{"data":[{"id":"selected-model","context_length":1048576}]}""", config, "selected-model", 1))
        val actual = parseGatewayCapacity(handshake(), config, "selected-model", 1)!!.withCeilings(1_048_576, null)
        assertEquals(8192L, actual.contextTokens)
        val rejected = runCatching {
            RequestAdmission.prepare("{}", "primary", actual, RequestMeasurement(8000, 512, TokenMeasurementKind.UTF8_ESTIMATE), 1024, "selected-permission")
        }.exceptionOrNull()
        assertTrue(rejected?.message.orEmpty().contains("CONTEXT_CAPACITY_EXCEEDED"))
    }

    @Test fun contextCapacityDoesNotFabricateAProviderReportedOutputLimit() {
        val unknown = JsonObject(observation + mapOf("outputLimitSource" to JsonPrimitive("context_capacity"), "maxOutputTokens" to JsonNull))
        val capacity = parseGatewayCapacity(handshake(unknown), config, "selected-model", 1)!!
        assertEquals(8192L, capacity.contextTokens)
        assertNull(capacity.outputTokens)
        assertNull(parseGatewayCapacity(handshake(JsonObject(unknown + ("maxOutputTokens" to JsonPrimitive(8192)))), config, "selected-model", 1))
    }
}
