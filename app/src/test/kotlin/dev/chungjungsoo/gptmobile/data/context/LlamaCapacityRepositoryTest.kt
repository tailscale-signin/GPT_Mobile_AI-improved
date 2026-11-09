package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.EOFException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class LlamaCapacityRepositoryTest {
    @Test fun rejectedMetadataIsCachedAsFailureWithoutRetryingDeterministicRequestsOrDroppingAutoload() = runBlocking {
        var reads = 0
        HttpClient(
            MockEngine { request ->
                reads++
                assertEquals("/props", request.url.encodedPath)
                assertEquals("selected", request.url.parameters["model"])
                assertEquals("false", request.url.parameters["autoload"])
                respond("""{"error":{"code":"invalid_parameter","param":"model","message":"private-server-detail"}}""", io.ktor.http.HttpStatusCode.BadRequest)
            }
        ).use { http ->
            val repository = LlamaCapacityRepository({ http })
            val config = ProviderRequestConfig("http://127.0.0.1:29981/v1", null, extraHeaders = mapOf("X-Request-ID" to "a".repeat(32)))
            assertEquals(null, repository.discover(config, "selected"))
            assertEquals(null, repository.discover(config, "selected"))
            assertEquals(1, reads)
            val failure = requireNotNull(repository.metadataFailure(config, "selected"))
            assertEquals(400, failure.httpStatus)
            assertEquals("model", failure.parameter)
            assertEquals("METADATA_REQUEST_REJECTED", failure.code)
            assertNotNull(failure.requestId)
            assertFalse(failure.toString().contains("private-server-detail"))
        }
    }

    @Test fun exactModelProofRequiresReportedIdentityWhilePreservingLegacyAllocationEvidence() = runBlocking {
        for ((index, reported) in listOf("", "\"model_alias\":\"test-model\",", "\"model\":\"test-model\",").withIndex()) {
            var reads = 0
            HttpClient(
                MockEngine { request ->
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("/props", request.url.encodedPath)
                    reads++
                    respond("""{$reported"build_info":"fixture-build","chat_template":"{{enable_thinking}}","default_generation_settings":{"n_ctx":8192}}""")
                }
            ).use { http ->
                val repository = LlamaCapacityRepository({ http })
                val config = ProviderRequestConfig("http://127.0.0.1:${29891 + index}/v1", null)
                val capacity = requireNotNull(repository.discover(config, "test-model"))
                assertEquals(CapacityProvenance.VERIFIED_ROUTE, capacity.provenance)
                assertEquals(8192L, capacity.contextTokens)
                assertEquals(reported.isNotEmpty(), repository.hasExactModelProof(config, "test-model", capacity))
                assertFalse(repository.hasExactModelProof(config, "test-model", capacity.copy(fingerprint = capacity.fingerprint.copy(runtimeRevision = "changed"))))
                assertEquals(1, reads)
            }
        }
    }

    @Test fun metadataPoolDoesNotTakeTemplateOrInferencePostsOrAddInferenceRetries() = runBlocking {
        var reads = 0
        val posts = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/props", request.url.encodedPath)
                reads++
                respond("""{"model_alias":"test-model","build_info":"fixture-build","chat_template":"{{enable_thinking}}","default_generation_settings":{"n_ctx":8192}}""")
            }
        ).use { metadata ->
            HttpClient(
                MockEngine { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    posts += request.url.encodedPath
                    if (request.url.encodedPath == "/v1/chat/completions") throw EOFException("response lost")
                    assertEquals("/apply-template", request.url.encodedPath)
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val on = body.getValue("chat_template_kwargs").jsonObject.getValue("enable_thinking").toString() == "true"
                    respond(if (on) """{"prompt":"prefix<think>"}""" else """{"prompt":"prefix<think></think>"}""")
                }
            ).use { ordinary ->
                val network = mockk<NetworkClient>()
                every { network.gatewayReadClient() } returns metadata
                every { network() } returns ordinary
                coEvery { network.evictIdleConnections() } returns Unit
                val repository = LlamaCapacityRepository(network)
                val config = ProviderRequestConfig("http://127.0.0.1:29890/v1", null)
                val capacity = requireNotNull(repository.discover(config, "test-model"))
                assertNotNull(repository.reasoningControl(config, "test-model", capacity))
                assertFalse(repository.supportsReviewSchema(config, "test-model", capacity))
                assertFalse(repository.supportsReviewSchema(config, "test-model", capacity))
                assertEquals(1, reads)
                assertEquals(listOf("/apply-template", "/apply-template", "/v1/chat/completions"), posts)
            }
        }
    }

    @Test fun protectedMetadataCannotDisableAnObservedSchemaCapabilityOrChangeTheSelectedModel() = runTest {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            var probes = 0
            val models = mutableListOf<String>()
            val http = HttpClient(
                MockEngine { request ->
                    assertEquals("Bearer configured-token", request.headers[HttpHeaders.Authorization])
                    assertEquals("selected-identity", request.headers["X-Client-Identity"])
                    assertEquals("false", request.headers["X-Gateway-Allow-Local-Tools"])
                    assertEquals("false", request.url.parameters["autoload"])
                    if (request.url.encodedPath == "/props") {
                        respond("restricted", io.ktor.http.HttpStatusCode.Forbidden, headersOf("X-Gateway-Instance-ID", "1".repeat(32)))
                    } else if (request.url.encodedPath == "/v1/gateway/capabilities") {
                        // Older gateways do not expose selected-model capacity yet.
                        respond("unsupported", io.ktor.http.HttpStatusCode.NotFound)
                    } else {
                        assertEquals("/v1/chat/completions", request.url.encodedPath)
                        assertEquals(null, request.headers["X-Gateway-Job-ID"])
                        assertEquals(null, request.headers["Idempotency-Key"])
                        org.junit.Assert.assertNotEquals("original-attempt", request.headers["X-Gateway-Attempt-ID"])
                        assertNotNull(request.headers["X-Gateway-Attempt-ID"])
                        probes++
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        models += body.getValue("model").toString().trim('"')
                        assertEquals("\"none\"", body["tool_choice"].toString())
                        respond("""{"choices":[{"message":{"content":"{\"ok\":true}"}}]}""")
                    }
                }
            )
            try {
                val repository = LlamaCapacityRepository({ http })
                val config = ProviderRequestConfig("http://127.0.0.1:29771/v1", "configured-token", profileUid = "selected", extraHeaders = mapOf("X-Client-Identity" to "selected-identity", "X-Gateway-Allow-Local-Tools" to "false", "X-Gateway-Job-ID" to "retained-user-job", "X-Gateway-Attempt-ID" to "original-attempt", "Idempotency-Key" to "user-request"))
                assertEquals(null, repository.discover(config, "selected-model"))
                org.junit.Assert.assertTrue(repository.supportsReviewSchema(config, "selected-model"))
                org.junit.Assert.assertTrue(repository.supportsReviewSchema(config, "selected-model"))
                assertEquals(1, probes)
                org.junit.Assert.assertTrue(repository.supportsReviewSchema(config, "changed-model"))
                GatewayClientDiagnostics.observeInstance(config, "2".repeat(32))
                org.junit.Assert.assertTrue(repository.supportsReviewSchema(config, "selected-model"))
                assertEquals(listOf("selected-model", "changed-model", "selected-model"), models)
            } finally {
                http.close()
            }
        }
    }

    @Test fun explicitModelCapacityDoesNotRequireAnOptionalTemplateAndCannotConfirmReasoning() = runTest {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val http = HttpClient(MockEngine { respond("""{"model_alias":"test-model","build_info":"fixture","default_generation_settings":{"n_ctx":8192}}""") })
            try {
                val repository = LlamaCapacityRepository({ http })
                val config = ProviderRequestConfig("http://127.0.0.1:29769/v1", null)
                val capacity = repository.discover(config, "test-model")!!
                assertEquals(8192L, capacity.contextTokens)
                org.junit.Assert.assertNull(repository.reasoningControl(config, "test-model", capacity))
            } finally {
                http.close()
            }
        }
    }

    @Test fun reviewSchemaSupportIsObservedOnceAndBoundToTheVerifiedModelRoute() = runTest {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            var schemas = 0
            val http = HttpClient(
                MockEngine { request ->
                    if (request.url.encodedPath == "/props") {
                        respond("""{"model_alias":"test-model","build_info":"fixture-build","chat_template":"plain","default_generation_settings":{"n_ctx":8192}}""")
                    } else {
                        schemas++
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        assertEquals("json_schema", body["response_format"]!!.jsonObject["type"]!!.toString().trim('"'))
                        respond("""{"choices":[{"message":{"content":"{\"ok\":true}"}}]}""")
                    }
                }
            )
            try {
                val repository = LlamaCapacityRepository({ http })
                val config = ProviderRequestConfig("http://127.0.0.1:29766/v1", null)
                val capacity = repository.discover(config, "test-model")!!
                org.junit.Assert.assertTrue(repository.supportsReviewSchema(config, "test-model", capacity))
                org.junit.Assert.assertTrue(repository.supportsReviewSchema(config, "test-model", capacity))
                assertEquals(1, schemas)
                val anotherAccount = config.copy(token = "another-account")
                val anotherCapacity = repository.discover(anotherAccount, "test-model")!!
                org.junit.Assert.assertTrue(repository.supportsReviewSchema(anotherAccount, "test-model", anotherCapacity))
                assertEquals(2, schemas)
            } finally {
                http.close()
            }
        }
    }

    @Test fun ignoredSchemaIsNotEnabledAndItsFailureIsNegativelyCached() = runTest {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            var schemas = 0
            val http = HttpClient(
                MockEngine { request ->
                    if (request.url.encodedPath == "/props") {
                        respond("""{"model_alias":"test-model","build_info":"fixture-build","chat_template":"plain","default_generation_settings":{"n_ctx":8192}}""")
                    } else {
                        schemas++
                        respond("""{"choices":[{"message":{"content":"{\"ok\":false}"}}]}""")
                    }
                }
            )
            try {
                val repository = LlamaCapacityRepository({ http })
                val config = ProviderRequestConfig("http://127.0.0.1:29767/v1", null)
                val capacity = repository.discover(config, "test-model")!!
                repeat(2) { org.junit.Assert.assertFalse(repository.supportsReviewSchema(config, "test-model", capacity)) }
                assertEquals(1, schemas)
            } finally {
                http.close()
            }
        }
    }

    @Test fun propsAndTemplateProbesRecoverOnceAndReuseVerifiedRouteCache() = runTest {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            var props = 0
            var templates = 0
            val http = HttpClient(
                MockEngine { request ->
                    if (request.url.encodedPath == "/props") {
                        props++
                        if (props == 1) throw EOFException("unexpected end of stream")
                        assertEquals("close", request.headers[HttpHeaders.Connection])
                        respond("""{"model_alias":"test-model","build_info":"fixture-build","chat_template":"{{enable_thinking}}","default_generation_settings":{"n_ctx":8192}}""")
                    } else {
                        templates++
                        assertEquals("/apply-template", request.url.encodedPath)
                        assertEquals("close", request.headers[HttpHeaders.Connection])
                        if (templates == 1) throw EOFException("unexpected end of stream")
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val on = body.getValue("chat_template_kwargs").jsonObject.getValue("enable_thinking").toString() == "true"
                        respond(if (on) """{"prompt":"prefix<think>"}""" else """{"prompt":"prefix<think></think>"}""")
                    }
                }
            )
            try {
                val repository = LlamaCapacityRepository({ http })
                val config = ProviderRequestConfig(apiUrl = "http://127.0.0.1:29764/v1", token = null)
                val capacity = repository.discover(config, "test-model")!!
                assertEquals(8192L, capacity.contextTokens)
                assertNotNull(repository.reasoningControl(config, "test-model", capacity))
                assertNotNull(repository.discover(config, "test-model"))
                assertNotNull(repository.reasoningControl(config, "test-model", capacity))
                assertEquals(2, props)
                assertEquals(3, templates)
            } finally {
                http.close()
            }
        }
    }

    @Test fun failedOptionalProbeHasANegativeCacheInsteadOfAnUnboundedLoop() = runTest {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            var requests = 0
            val http = HttpClient(
                MockEngine {
                    requests++
                    throw EOFException("unexpected end of stream")
                }
            )
            try {
                val repository = LlamaCapacityRepository({ http })
                val config = ProviderRequestConfig(apiUrl = "http://127.0.0.1:29765/v1", token = null)
                assertEquals(null, repository.discover(config, "test-model"))
                assertEquals(null, repository.discover(config, "test-model"))
                assertEquals(2, requests)
            } finally {
                http.close()
            }
        }
    }
}
