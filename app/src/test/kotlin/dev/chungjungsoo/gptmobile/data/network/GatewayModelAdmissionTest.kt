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
