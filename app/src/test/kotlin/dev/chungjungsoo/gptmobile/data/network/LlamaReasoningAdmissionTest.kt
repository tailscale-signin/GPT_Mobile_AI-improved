package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.context.ReasoningPolicy
import dev.chungjungsoo.gptmobile.data.context.ReasoningTemplateControl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaReasoningAdmissionTest {
    @Test fun repairUsesBoundedAutoControlWithoutChangingExplicitReasoningPolicy() = runBlocking {
        val paths = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                val response = when (request.url.encodedPath) {
                    "/props" -> """{"model_alias":"selected","build_info":"repair-helper-fixture","default_generation_settings":{"n_ctx":32768},"chat_template":"enable_thinking"}"""
                    "/apply-template" -> {
                        val root = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val on = root.getValue("chat_template_kwargs").jsonObject["enable_thinking"] == JsonPrimitive(true)
                        buildJsonObject { put("prompt", "user: Template capability check.\nassistant:<think>\n" + if (on) "" else "\n</think>\n") }.toString()
                    }
                    else -> error("No inference is authorized during this admission fixture")
                }
                respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ).use { http ->
            val network = mockk<NetworkClient>()
            every { network() } returns http
            every { network.gatewayReadClient() } returns http
            val preparation = ModelRequestPreparation(network)
            for (mode in ReasoningMode.entries) {
                val config = ProviderRequestConfig("https://repair-helper.invalid/v1", null, providerType = "LLAMA", reasoningMode = mode, requestRole = "github-review-repair")
                val wire = preparation.prepare("""{"model":"selected","messages":[{"role":"user","content":"Correct only the supplied candidate."}],"max_tokens":4096}""", config, "selected", 4096)
                val payload = Json.parseToJsonElement(wire).jsonObject
                assertEquals(JsonPrimitive(mode == ReasoningMode.ON), payload.getValue("chat_template_kwargs").jsonObject["enable_thinking"])
                assertEquals(JsonPrimitive(4096), payload["max_tokens"])
                assertFalse(payload.containsKey("response_format"))
                val receipt = ReasoningControlReceipt.fromPayload(wire, true, mode).evidence()
                assertEquals(kotlinx.serialization.json.JsonNull, receipt["effective_reasoning"])
                assertEquals(JsonPrimitive("NOT_MEASURED"), receipt["effective_settings_source"])
            }
            assertEquals(listOf("/props", "/apply-template", "/apply-template"), paths)
        }
    }

    @Test fun unloadedSelectedGatewayModelBlocksAutoAndExplicitOffBeforeInference() = runBlocking {
        val config = ProviderRequestConfig("https://unloaded-admission.example/v1", null, providerType = "LLAMA")
        val instance = "a".repeat(32)
        dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics.observeInstance(config, instance)
        val paths = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                assertEquals("GET", request.method.value)
                assertEquals("selected", request.url.parameters["model"])
                assertEquals("false", request.url.parameters["autoload"])
                respond("""{"contractVersion":1,"instanceId":"$instance","selectedModel":{"schema":"gptmobile.gateway.selected-model.v1","schemaVersion":1,"requestedModel":"selected","limitsVerified":false,"failureCode":"EXECUTION_MODEL_NOT_LOADED","reasonCode":"SELECTED_MODEL_NOT_LOADED","modelState":"UNLOADED","modelLoaded":false}}""")
            }
        ).use { http ->
            val network = mockk<NetworkClient>()
            every { network() } returns http
            every { network.gatewayReadClient() } returns http
            val preparation = ModelRequestPreparation(network)
            for (mode in listOf(ReasoningMode.AUTO, ReasoningMode.OFF)) {
                val dispatch = dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext(emptySet(), "run", "invocation")
                kotlinx.coroutines.withContext(dispatch) {
                    val error = runCatching {
                        preparation.prepare("""{"model":"selected","messages":[{"role":"user","content":"request"}]}""", config.copy(reasoningMode = mode), "selected", 256)
                    }.exceptionOrNull()
                    assertTrue(error is dev.chungjungsoo.gptmobile.data.context.SelectedModelUnavailableException)
                    val detail = requireNotNull(providerExceptionDetails(requireNotNull(error as? Exception), partialOutput = false))
                    assertEquals("SELECTED_MODEL_NOT_LOADED", detail.code)
                    assertEquals("SELECTED_MODEL_NOT_LOADED", dispatch.preflightRejection?.reason?.name)
                    assertTrue(dispatch.provenEntirelyUnsent())
                }
            }
        }
        assertEquals(listOf("/v1/gateway/capabilities"), paths)
    }

    @Test fun unavailableGatewayMetadataRejectsStrictOffWithoutRawProbesExtraReadinessOrInference() = runBlocking {
        val config = ProviderRequestConfig("https://unavailable-strict-off.example/v1", "secret", providerType = "LLAMA", reasoningMode = ReasoningMode.OFF)
        dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics.observeInstance(config, "b".repeat(32))
        val paths = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                assertEquals("GET", request.method.value)
                assertEquals("/v1/gateway/capabilities", request.url.encodedPath)
                respond("""{"contractVersion":1,"instanceId":"${"b".repeat(32)}","selectedModel":{"schema":"gptmobile.gateway.selected-model.v1","schemaVersion":1,"requestedModel":"selected","limitsVerified":false,"failureCode":"EXECUTION_BACKEND_UNAVAILABLE","reasonCode":"BACKEND_CONNECTION_REFUSED","failureCategory":"connection_refused","reasoningControlState":"UNVERIFIED","retryable":true}}""")
            }
        ).use { http ->
            val network = mockk<NetworkClient>()
            every { network() } returns http
            every { network.gatewayReadClient() } returns http
            val preparation = ModelRequestPreparation(network)
            val dispatch = dev.chungjungsoo.gptmobile.data.context.RequestDispatchContext(emptySet(), "run", "invocation")
            kotlinx.coroutines.withContext(dispatch) {
                repeat(2) {
                    val failure = runCatching { preparation.prepare("""{"model":"selected","messages":[{"role":"user","content":"request"}],"max_tokens":256}""", config, "selected", 256) }.exceptionOrNull()
                    assertTrue(failure is dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityUnavailableException)
                    assertEquals("REASONING_CAPABILITY_UNAVAILABLE", (failure as dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityUnavailableException).code)
                }
            }
            assertEquals(listOf("/v1/gateway/capabilities"), paths)
            assertTrue(dispatch.provenEntirelyUnsent())
            assertTrue(dispatch.receipts.isEmpty())
            assertEquals(ReasoningMode.OFF, config.reasoningMode)
        }
    }

    @Test fun textOnlyRecoveryPreservesReasoningAdmissionAndHardConstraints() {
        val original = dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints(
            maxOutputTokens = 512,
            allowGatewayLocalTools = true,
            contextCeiling = 8192,
            permissionRevision = "accepted-policy",
            originalRequestLineage = "original"
        )
        val recovery = original.withoutTools()
        assertFalse(recovery.allowTools)
        assertFalse(recovery.allowGatewayLocalTools)
        assertEquals(original.copy(allowTools = false, allowGatewayLocalTools = false), recovery)
        assertEquals(ReasoningMode.AUTO, ReasoningPolicy.resolve(ReasoningMode.AUTO, recovery.allowReasoning, null).effective)
        assertThrows(IllegalStateException::class.java) { ReasoningPolicy.resolve(ReasoningMode.OFF, recovery.allowReasoning, null) }
        assertFalse(original.copy(allowReasoning = false).withoutTools().allowReasoning)
    }

    @Test fun explicitOffUsesOnlyTheProbedControlAndKeepsTheConfiguredRoute() = runBlocking {
        val urls = mutableListOf<String>()
        val engine = MockEngine { incoming ->
            urls += incoming.url.toString()
            assertEquals("Bearer synthetic-secret", incoming.headers[HttpHeaders.Authorization])
            assertEquals("false", incoming.url.parameters["autoload"])
            val response = when (incoming.url.encodedPath) {
                "/prefix/props" -> """{"model_alias":"selected","build_info":"test-build","default_generation_settings":{"n_ctx":8192},"chat_template":"enable_thinking"}"""
                "/prefix/apply-template" -> {
                    val body = Json.parseToJsonElement((incoming.body as TextContent).text).jsonObject
                    assertEquals(JsonPrimitive("selected"), body["model"])
                    val enabled = body.getValue("chat_template_kwargs").jsonObject.getValue("enable_thinking") == JsonPrimitive(true)
                    buildJsonObject { put("prompt", "user: Template capability check.\nassistant:<think>\n" + if (enabled) "" else "\n</think>\n") }.toString()
                }
                else -> error("Unexpected route")
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine)
        val wrapper = mockk<NetworkClient>()
        every { wrapper() } returns client
        every { wrapper.gatewayReadClient() } returns client
        try {
            val preparation = ModelRequestPreparation(wrapper)
            val config = ProviderRequestConfig("https://fixture.invalid/prefix/v1", "synthetic-secret", providerType = "LLAMA", reasoningMode = ReasoningMode.OFF)
            repeat(2) {
                val wire = preparation.prepare("""{"model":"selected","messages":[{"role":"user","content":"Transport fixture request."}],"max_tokens":256}""", config, "selected", null)
                val body = Json.parseToJsonElement(wire).jsonObject
                assertEquals(mapOf("enable_thinking" to JsonPrimitive(false)), body.getValue("chat_template_kwargs").jsonObject)
                assertFalse(body.containsKey("reasoning_effort"))
            }
            assertEquals(3, urls.size)
            assertTrue(urls.all { it.startsWith("https://fixture.invalid/prefix/") })
        } finally {
            client.close()
        }
    }

    @Test fun rewriteAndSavedJobRequestsKeepExplicitOffOnTheExactAdmittedWire() = runBlocking {
        val paths = mutableListOf<String>()
        val engine = MockEngine { incoming ->
            paths += incoming.url.encodedPath
            val response = when (incoming.url.encodedPath) {
                "/props" -> """{"model_alias":"selected","build_info":"off-role-fixture","default_generation_settings":{"n_ctx":8192},"chat_template":"thinking"}"""
                "/apply-template" -> {
                    val body = Json.parseToJsonElement((incoming.body as TextContent).text).jsonObject
                    assertEquals(JsonPrimitive("selected"), body["model"])
                    val enabled = body.getValue("chat_template_kwargs").jsonObject.getValue("thinking") == JsonPrimitive(true)
                    buildJsonObject { put("prompt", "user: Template capability check.\nassistant:<think>\n" + if (enabled) "" else "\n</think>\n") }.toString()
                }
                else -> error("No inference or replacement job is authorized by this admission fixture")
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine)
        val wrapper = mockk<NetworkClient>()
        every { wrapper() } returns client
        every { wrapper.gatewayReadClient() } returns client
        try {
            val preparation = ModelRequestPreparation(wrapper)
            val accepted = dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints(reasoningModeOverride = ReasoningMode.OFF, lockSelectedRoute = true)
            for (role in listOf("primary", "answer_contract_rewrite", "github-claim-extraction")) {
                val constraints = accepted.forRole(role).withoutTools()
                val config = ProviderRequestConfig("https://off-role-fixture.invalid/v1", null, providerType = "LLAMA", requestRole = role, reasoningMode = requireNotNull(constraints.reasoningModeOverride), allowReasoning = constraints.allowReasoning, extraHeaders = mapOf("X-Gateway-Job-ID" to "saved-job"), lockedModel = "selected", lockedApiUrl = "https://off-role-fixture.invalid/v1")
                val wire = preparation.prepare("""{"model":"selected","messages":[{"role":"user","content":"Resume retained work."}],"max_tokens":256,"reasoning_effort":"high"}""", config, "selected", null)
                val body = Json.parseToJsonElement(wire).jsonObject
                assertEquals(mapOf("thinking" to JsonPrimitive(false)), body.getValue("chat_template_kwargs").jsonObject)
                assertFalse(body.containsKey("reasoning_effort"))
                val receipt = ReasoningControlReceipt.fromPayload(wire, config.allowReasoning, config.reasoningMode)
                assertTrue(receipt.disableRequested)
                assertTrue(receipt.transmitsDisable)
                assertEquals("UNVERIFIED", receipt.providerSupport)
            }
            assertEquals(listOf("/props", "/apply-template", "/apply-template"), paths)
        } finally {
            client.close()
        }
    }

    @Test fun helperAutoKeepsTheSelectedRouteAndStrictOffRejectsBeforeAnySchemaInference() = runBlocking {
        val paths = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                check(request.url.encodedPath == "/props") { "No inference is authorized by this admission fixture" }
                respond("""{"model_alias":"selected","build_info":"helper-auto-fixture","default_generation_settings":{"n_ctx":8192},"chat_template":"no-toggle"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        val network = mockk<NetworkClient>()
        every { network() } returns client
        every { network.gatewayReadClient() } returns client
        val preparation = ModelRequestPreparation(network)
        val config = ProviderRequestConfig("https://helper-auto.invalid/v1", null, providerType = "LLAMA", reasoningMode = ReasoningMode.AUTO)
        val payload = """{"model":"selected","messages":[{"role":"user","content":"Summarize these factual receipts."}],"max_tokens":256}"""
        try {
            for (role in listOf("github-progress-summary", "answer_contract_rewrite", "synthesis")) {
                val wire = preparation.prepare(payload, config.copy(requestRole = role), "selected", 256)
                val body = Json.parseToJsonElement(wire).jsonObject
                assertEquals(JsonPrimitive("selected"), body["model"])
                assertFalse(body.containsKey("chat_template_kwargs"))
                assertFalse(body.containsKey("reasoning_effort"))
            }
            try {
                preparation.prepare(payload, config.copy(requestRole = "github-review", reasoningMode = ReasoningMode.OFF), "selected", 256)
                error("Strict Off must be rejected")
            } catch (expected: dev.chungjungsoo.gptmobile.data.context.ReasoningAdmissionException) {
                assertEquals(ReasoningMode.OFF, expected.requested)
            }
            assertEquals(listOf("/props"), paths)
        } finally {
            client.close()
        }
    }

    @Test fun autoPreservesDefaultsAndUnsupportedOffCannotClaimDisabledComputation() {
        val auto = ReasoningPolicy.resolve(ReasoningMode.AUTO, true, null)
        assertEquals(null, auto.transmittedControl)
        assertEquals(null, auto.observedComputationEnabled)
        assertThrows(IllegalStateException::class.java) { ReasoningPolicy.resolve(ReasoningMode.OFF, true, null) }
        assertThrows(IllegalStateException::class.java) { ReasoningPolicy.resolve(ReasoningMode.ON, false, null) }
        val restricted = ReasoningPolicy.resolve(ReasoningMode.ON, false, ReasoningTemplateControl.THINKING)
        assertEquals(ReasoningMode.OFF, restricted.effective)
        val body = ReasoningPolicy.apply(buildJsonObject { put("reasoning_format", "none") }, restricted)
        assertEquals(mapOf("thinking" to JsonPrimitive(false)), body.getValue("chat_template_kwargs").jsonObject)
        assertEquals(null, restricted.observedComputationEnabled)
    }

    @Test fun ignoredToggleOrDifferentModelPromptDoesNotQualifyAControl() {
        assertFalse(ReasoningPolicy.verifiesThinkingToggle("same", "same"))
        assertFalse(ReasoningPolicy.verifiesThinkingToggle("A<think>\n", "B<think>\n</think>"))
        assertFalse(ReasoningPolicy.verifiesThinkingToggle("A<think>reasoning", "A<think>\n</think>"))
        assertTrue(ReasoningPolicy.verifiesThinkingToggle("A<think>\n", "A<think>\n</think>\n"))
    }
}
