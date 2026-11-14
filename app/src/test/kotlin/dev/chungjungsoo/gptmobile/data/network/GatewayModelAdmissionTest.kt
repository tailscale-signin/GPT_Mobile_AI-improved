package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GatewayModelAdmissionTest {
    @Test fun earlyContextRejectionRetainsObservedRouteAndConfiguredCeilingsBeforeAnyInferencePost() = runTest {
        withContext(Dispatchers.Default) {
            val selected = ProviderRequestConfig("http://127.0.0.1:29875/v1", null, profileUid = "rejected-gateway-capacity", providerType = "LLAMA", contextCeiling = 32768)
            GatewayClientDiagnostics.observeInstance(selected, "d".repeat(32))
            val capacityObservedAt = 1_793_960_000_000L
            var metadataReads = 0
            HttpClient(
                MockEngine { request ->
                    metadataReads++
                    assertEquals("GET", request.method.value)
                    assertEquals("/v1/gateway/capabilities", request.url.encodedPath)
                    respond(
                        """{"contractVersion":1,"instanceId":"${"d".repeat(32)}","selectedModel":{
                        "schema":"gptmobile.gateway.selected-model.v1","schemaVersion":1,"requestedModel":"selected-model","reportedModel":"selected-model",
                        "contextTokens":5632,"limitsVerified":true,"outputLimitSource":"context_capacity","capabilityRevision":"${"e".repeat(64)}",
                        "observedAtMillis":$capacityObservedAt,"evidenceOrigin":"GATEWAY_OBSERVED_PROVIDER_METADATA"}}"""
                    )
                }
            ).use { http ->
                val network = mockk<NetworkClient>()
                every { network() } returns http
                every { network.gatewayReadClient() } returns http
                val privateInput = "private complete input ".repeat(2000)
                val original = """{"model":"selected-model","messages":[{"role":"user","content":"$privateInput"}],"max_tokens":4096}"""
                val error = runCatching { ModelRequestPreparation(network).prepare(original, selected, "selected-model", 4096) }.exceptionOrNull()
                org.junit.Assert.assertTrue(error is dev.chungjungsoo.gptmobile.data.context.RequestCapacityException)
                val failure = requireNotNull(providerExceptionDetails(requireNotNull(error) as Exception, partialOutput = false, nowMillis = capacityObservedAt + 250))
                assertEquals("CONTEXT_CAPACITY_EXCEEDED", failure.code)
                assertEquals(JsonPrimitive(5632), failure.failureDetails?.get("admission_capacity_tokens"))
                assertEquals(JsonPrimitive(5632), failure.failureDetails?.get("admission_route_context_tokens"))
                assertEquals(JsonPrimitive(32768), failure.failureDetails?.get("admission_configured_context_tokens"))
                assertEquals(JsonPrimitive("VERIFIED_ROUTE"), failure.failureDetails?.get("admission_capacity_provenance"))
                assertEquals(JsonPrimitive("UTF8_ESTIMATE"), failure.failureDetails?.get("admission_measurement"))
                assertEquals(JsonPrimitive("NOT_DISPATCHED"), failure.failureDetails?.get("attempt_dispatch_state"))
                org.junit.Assert.assertTrue(failure.failureDetails?.containsKey("admission_current_request_tokens") == true)
                assertEquals(JsonPrimitive(250), failure.failureDetails?.get("admission_capacity_age_ms"))
                assertFalse(failure.failureDetails.toString().contains("private complete input"))
                assertEquals(1, metadataReads)
                assertEquals(32768, selected.contextCeiling)
                assertEquals(privateInput, Json.parseToJsonElement(original).jsonObject.getValue("messages").let { it as kotlinx.serialization.json.JsonArray }.single().jsonObject["content"]?.let { it as JsonPrimitive }?.content)
            }
        }
    }

    @Test fun unloadedGatewayModelPreservesStrictReasoningAndRejectsBeforeAnyInferencePost() = runTest {
        withContext(Dispatchers.Default) {
            val selected = ProviderRequestConfig("http://127.0.0.1:29984/v1", null, profileUid = "unloaded-gateway-model", providerType = "LLAMA", reasoningMode = dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF, allowReasoning = false)
            GatewayClientDiagnostics.observeInstance(selected, "c".repeat(32))
            var calls = 0
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals("GET", request.method.value)
                    assertEquals("/v1/gateway/capabilities", request.url.encodedPath)
                    assertEquals("selected-model", request.url.parameters["model"])
                    assertEquals("false", request.url.parameters["autoload"])
                    respond(
                        """{"contractVersion":1,"instanceId":"${"c".repeat(32)}","selectedModel":{
                        "schema":"gptmobile.gateway.selected-model.v1","schemaVersion":1,"requestedModel":"selected-model",
                        "limitsVerified":false,"failureCode":"EXECUTION_MODEL_NOT_LOADED","reasonCode":"SELECTED_MODEL_NOT_LOADED",
                        "dependency":"backend_properties","upstreamStatus":400,"modelState":"UNLOADED","modelAdvertised":true,"modelLoaded":false}}"""
                    )
                }
            ).use { http ->
                val network = mockk<NetworkClient>()
                every { network() } returns http
                every { network.gatewayReadClient() } returns http
                val error = runCatching {
                    ModelRequestPreparation(network).prepare(
                        """{"model":"selected-model","messages":[{"role":"user","content":"Hello"}],"max_tokens":1024}""",
                        selected,
                        "selected-model",
                        1024
                    )
                }.exceptionOrNull()
                org.junit.Assert.assertTrue(error is dev.chungjungsoo.gptmobile.data.context.SelectedModelUnavailableException)
                val failure = requireNotNull(providerExceptionDetails(requireNotNull(error) as Exception, partialOutput = false))
                assertEquals("SELECTED_MODEL_NOT_LOADED", failure.code)
                assertEquals(JsonPrimitive(400), failure.failureDetails?.get("gateway_upstream_status"))
                assertEquals(JsonPrimitive("NOT_DISPATCHED"), failure.failureDetails?.get("attempt_dispatch_state"))
                assertEquals(dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF, selected.reasoningMode)
                assertFalse(selected.allowReasoning)
                assertEquals(1, calls)
            }
        }
    }

    @Test fun anIdentifiedCompatibleGatewayUsesConfirmedLimitsDuringPhysicalRequestPreparation() = runTest {
        withContext(Dispatchers.Default) {
            val selected = ProviderRequestConfig("http://127.0.0.1:29783/v1", null, profileUid = "prepared-gateway-capacity", providerType = "OPENAI", contextCeiling = 1_048_576)
            GatewayClientDiagnostics.observeInstance(selected, "b".repeat(32))
            var calls = 0
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals("GET", request.method.value)
                    assertEquals("/v1/gateway/capabilities", request.url.encodedPath)
                    assertEquals("selected-model", request.url.parameters["model"])
                    respond(
                        """{"contractVersion":1,"gatewayVersion":"15.0.0","instanceId":"${"b".repeat(32)}",
                       "selectedModel":{"schema":"gptmobile.gateway.selected-model.v1","schemaVersion":1,
                       "requestedModel":"selected-model","reportedModel":"selected-model","contextTokens":8192,"maxOutputTokens":1024,
                       "limitsVerified":true,"outputLimitSource":"provider_reported","capabilityRevision":"${"a".repeat(64)}",
                       "observedAtMillis":1793960000000,"evidenceOrigin":"GATEWAY_OBSERVED_PROVIDER_METADATA"}}""",
                        HttpStatusCode.OK
                    )
                }
            ).use { http ->
                val network = mockk<NetworkClient>()
                every { network() } returns http
                every { network.gatewayReadClient() } returns http
                val wire = ModelRequestPreparation(network).prepare(
                    """{"model":"selected-model","messages":[{"role":"user","content":"Hello"}],"max_tokens":4096}""",
                    selected,
                    "selected-model",
                    null
                )
                val body = Json.parseToJsonElement(wire).jsonObject
                assertEquals(JsonPrimitive(1024), body["max_tokens"])
                assertEquals(JsonPrimitive("selected-model"), body["model"])
                assertEquals(1, calls)
                assertFalse(wire.contains("1048576"))
            }
        }
    }
}
