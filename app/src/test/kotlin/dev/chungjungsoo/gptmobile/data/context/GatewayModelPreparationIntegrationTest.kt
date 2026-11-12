package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPIImpl
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayModelPreparationAPI
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only the HTTP transport is simulated; discovery, qualification, load and orchestration are real. */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayModelPreparationIntegrationTest {
    @Test fun exactSelectionLoadsOnceThenFreshCapacityAndTemplateProofQualifyTheOriginalReasoningMode() = runTest {
        for (mode in listOf(ReasoningMode.OFF, ReasoningMode.ON)) {
            Fixture(UnconfinedTestDispatcher(testScheduler), mode, supportsPreparation = true).use { fixture ->
                val states = mutableListOf<GatewayModelPreparationState>()
                val result = requireNotNull(
                    fixture.repository.prepareSelected(fixture.profile, mode) { state ->
                        if (state != null) {
                            if (state.phase == GatewayModelPreparationPhase.READY) assertEquals("LOADED", fixture.modelReads.last())
                            states += state
                        }
                    }
                )

                assertEquals(GatewayModelPreparationPhase.READY, result.phase)
                assertEquals(listOf("UNLOADED", "LOADING", "LOADED"), fixture.modelReads)
                assertEquals(1, fixture.loadRequests)
                assertEquals(GatewayModelPreparationPhase.CHECKING, states.first().phase)
                assertTrue(states.any { it.phase == GatewayModelPreparationPhase.LOADING })
                assertTrue(states.all { it.profileUid == fixture.profile.uid && it.model == fixture.profile.model })
                val qualified = requireNotNull(result.observation)
                assertEquals(fixture.profile.model, qualified.model)
                assertEquals(mode, qualified.requested)
                assertEquals(ReasoningCapabilityStatus.SUPPORTED, qualified.status)
                assertTrue(qualified.routeVerified)
                assertTrue(qualified.supportVerified)
                assertTrue(qualified.permitsRequest)
                assertEquals(mode == ReasoningMode.ON, fixture.profile.reasoning)
                assertTrue(fixture.capabilityReads >= 3)
                assertEquals(listOf("POST /v1/gateway/models/prepare"), fixture.exchanges.filter { it.startsWith("POST ") })
                assertTrue(fixture.exchanges.all { it == "GET /v1/gateway/capabilities" || it == "POST /v1/gateway/models/prepare" })

                // A second observer reuses this exact qualification and never starts inference.
                assertEquals(result, fixture.repository.prepareSelected(fixture.profile, mode))
                assertEquals(1, fixture.loadRequests)
            }
        }
    }

    @Test fun anOlderGatewayWithoutPreparationSupportKeepsTheExactModelBlockedUsingOnlyMetadataGets() = runTest {
        Fixture(UnconfinedTestDispatcher(testScheduler), ReasoningMode.OFF, supportsPreparation = false).use { fixture ->
            val result = requireNotNull(fixture.repository.prepareSelected(fixture.profile, ReasoningMode.OFF))
            assertEquals(GatewayModelPreparationPhase.BLOCKED, result.phase)
            assertEquals(listOf("UNLOADED"), fixture.modelReads)
            assertEquals(0, fixture.loadRequests)
            assertTrue(result.message.contains("update"))
            assertTrue(result.message.contains("prepare-model --model"))
            val observed = requireNotNull(result.observation)
            assertEquals(ReasoningMode.OFF, observed.requested)
            assertEquals(fixture.profile.model, observed.model)
            assertEquals("SELECTED_MODEL_NOT_LOADED", observed.safeReasonCode)
            assertFalse(observed.routeVerified)
            assertFalse(observed.permitsRequest)
            assertTrue(fixture.capabilityReads >= 2)
            assertTrue(fixture.exchanges.all { it == "GET /v1/gateway/capabilities" })
        }
    }

    private class Fixture(engineDispatcher: CoroutineDispatcher, mode: ReasoningMode, private val supportsPreparation: Boolean) : AutoCloseable {
        private val identity = "${mode.name.lowercase()}-${if (supportsPreparation) "current" else "old"}"
        val profile = PlatformV2(
            uid = "preparation-integration-$identity",
            name = "Selected gateway profile",
            compatibleType = ClientType.LLAMA,
            apiUrl = "https://model-preparation-$identity.example/v1",
            token = "synthetic-selection-token",
            model = "exact-selected-model",
            reasoning = mode == ReasoningMode.ON
        )
        val exchanges = mutableListOf<String>()
        val modelReads = mutableListOf<String>()
        var capabilityReads = 0
            private set
        var loadRequests = 0
            private set
        private var readsAfterLoad = 0
        private val instance = "b".repeat(32)
        private val revision = "a".repeat(64)
        private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

        private val http = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    // Keep the production repository's two-second polling delay on virtual time.
                    dispatcher = engineDispatcher
                    addHandler { request ->
                        assertEquals("model-preparation-$identity.example", request.url.host)
                        assertEquals("Bearer ${profile.token}", request.headers[HttpHeaders.Authorization])
                        exchanges += "${request.method.value} ${request.url.encodedPath}"
                        when (request.method.value to request.url.encodedPath) {
                            "GET" to "/v1/gateway/capabilities" -> {
                                val selected = request.url.parameters["model"]
                                if (selected == null) {
                                    capabilityReads++
                                    assertTrue(request.url.parameters.isEmpty())
                                    respond(capabilities(), HttpStatusCode.OK, jsonHeaders)
                                } else {
                                    assertEquals(profile.model, selected)
                                    assertEquals(setOf("model", "autoload"), request.url.parameters.names())
                                    assertEquals("false", request.url.parameters["autoload"])
                                    val state = if (loadRequests == 0) {
                                        "UNLOADED"
                                    } else if (++readsAfterLoad == 1) {
                                        "LOADING"
                                    } else {
                                        "LOADED"
                                    }
                                    modelReads += state
                                    respond(capabilities(selectedModel(state)), HttpStatusCode.OK, jsonHeaders)
                                }
                            }
                            "POST" to "/v1/gateway/models/prepare" -> {
                                assertTrue("Old gateways must never receive a load request", supportsPreparation)
                                loadRequests++
                                assertEquals(1, loadRequests)
                                assertEquals(listOf("UNLOADED"), modelReads)
                                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                                assertEquals(setOf("model", "operationId"), body.keys)
                                assertEquals(profile.model, body.getValue("model").jsonPrimitive.content)
                                val operation = body.getValue("operationId").jsonPrimitive.content
                                assertTrue(operation.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                                assertEquals(operation, request.headers["Idempotency-Key"])
                                respond(
                                    buildJsonObject {
                                        put("schema", "gptmobile.gateway.model-preparation.v1")
                                        put("operationId", operation)
                                        put("requestedModel", profile.model)
                                        put("state", "WAITING_FOR_MODEL")
                                        put("dispatchState", "DISPATCHED")
                                        put("loadAccepted", true)
                                        put("loadRequests", 1)
                                        put("modelState", "LOADING")
                                        put("inferenceTested", false)
                                        put("automaticSubstitution", false)
                                        put("reasoningSettingsChanged", false)
                                    }.toString(),
                                    HttpStatusCode.Accepted,
                                    jsonHeaders
                                )
                            }
                            else -> error("No alternate-model, generation, job, or backend-administration request is allowed during preparation")
                        }
                    }
                }
            )
        )
        private val network = mockk<NetworkClient>().also {
            every { it() } returns http
            every { it.gatewayReadClient() } returns http
        }
        private val gateway = GatewayAPIImpl(network)
        private val readiness = ReasoningCapabilityRepository(network, gateway)
        val repository = GatewayModelPreparationRepository(gateway, readiness, GatewayModelPreparationAPI(network), GatewayModelPreparationStore(ModelPreparationTestVault()))

        private fun capabilities(selected: JsonObject? = null): String = buildJsonObject {
            put("contractVersion", 1)
            put("gatewayVersion", if (supportsPreparation) "16.0.0" else "15.0.0")
            put("instanceId", instance)
            put("features", buildJsonObject { if (supportsPreparation) put("model_preparation_v1", true) })
            if (selected != null) put("selectedModel", selected)
        }.toString()

        /** Same exact-model capacity and measured template-proof contract as GatewayCapacityHandshakeTest. */
        private fun selectedModel(state: String): JsonObject = buildJsonObject {
            put("schema", "gptmobile.gateway.selected-model.v1")
            put("schemaVersion", 1)
            put("requestedModel", profile.model)
            put("modelAdvertised", true)
            put("modelLoaded", state == "LOADED")
            put("modelState", state)
            put("limitsVerified", state == "LOADED")
            put("observedAtMillis", 1_793_960_000_000L)
            put("observationAgeMs", 0)
            if (state == "LOADED") {
                put("reportedModel", profile.model)
                put("contextTokens", 8192)
                put("maxOutputTokens", 1024)
                put("outputLimitSource", "provider_reported")
                put("capabilityRevision", revision)
                put("evidenceOrigin", "GATEWAY_OBSERVED_PROVIDER_METADATA")
                put("reasoningControlState", "VERIFIED")
                put(
                    "reasoningControl",
                    buildJsonObject {
                        put("schemaVersion", 1)
                        put("parameter", "enable_thinking")
                        put("templateProbeVerified", true)
                        put("evidenceOrigin", "GATEWAY_OBSERVED_TEMPLATE_PROBE")
                        put("capabilityRevision", revision)
                        put("templateDigest", "c".repeat(64))
                        put("enabledPromptDigest", "d".repeat(64))
                        put("disabledPromptDigest", "e".repeat(64))
                        put("observedAtMillis", 1_793_960_000_000L)
                    }
                )
            } else {
                put("reasoningControlState", "UNVERIFIED")
                put("reasonCode", if (state == "UNLOADED") "SELECTED_MODEL_NOT_LOADED" else "SELECTED_MODEL_LOADING")
                put("failureCode", "EXECUTION_MODEL_NOT_LOADED")
                put("dependency", "backend_properties")
                put("upstreamStatus", 400)
                put("retryable", true)
            }
        }

        override fun close() = http.close()
    }
}
