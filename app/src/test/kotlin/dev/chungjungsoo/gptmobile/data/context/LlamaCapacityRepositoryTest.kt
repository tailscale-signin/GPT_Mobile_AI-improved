package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import java.io.EOFException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LlamaCapacityRepositoryTest {
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
