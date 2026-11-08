package dev.chungjungsoo.gptmobile.data.llama

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaHealthDiagnosticsTest {
    @Test fun admissionRestrictedMetadataIsRememberedWithoutDisablingGatewayReadiness() = runTest {
        val selected = ProviderRequestConfig("https://restricted-health.example/v1", "restricted-health-test")
        GatewayClientDiagnostics.observeInstance(selected, "a".repeat(32))
        var now = 0L
        var restrictedReads = 0
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    when (request.url.encodedPath) {
                        "/props", "/health" -> {
                            restrictedReads++
                            respond("""{"error":{"code":"remote_surface_not_admitted"}}""", HttpStatusCode.Forbidden)
                        }
                        "/v1/models" -> respond("""{"data":[{"id":"selected"}]}""")
                        "/v1/gateway/ready" -> respond("""{"schemaVersion":1,"instanceId":"${"a".repeat(32)}","ready":true,"capabilities":{"selectedModelDigest":"${researchHash("selected")}","modelAvailable":true,"generationReady":true}}""")
                        else -> error("Unexpected probe")
                    }
                }
            }
        }
        http.use {
            val diagnostics = LlamaHealthDiagnostics({ http }, { now })
            assertEquals(LlamaConnectionPhase.MODEL_LOADED, diagnostics.inspect(selected, "selected").phase)
            now = 31_000
            val renewed = diagnostics.inspect(selected, "selected")
            assertEquals(2, restrictedReads)
            assertEquals(listOf("health", "props"), renewed.cachedRestrictedSurfaces)
            assertTrue(renewed.metadataRestricted)
            assertEquals(true, renewed.gatewayReady)
            assertEquals(false, renewed.inferenceTested)
        }
    }

    @Test fun checksStayOnTheAuthenticatedRouteAndDoNotLoadAModelOrRunInference() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    assertEquals("local.example", request.url.host)
                    assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
                    assertEquals("false", request.url.parameters["autoload"])
                    assertEquals("selected", request.url.parameters["model"])
                    paths += request.url.encodedPath
                    val body = when (request.url.encodedPath) {
                        "/prefix/health" -> """{"status":"ok"}"""
                        "/prefix/props" -> """{"default_generation_settings":{"n_ctx":8192},"server_generation":"server-generation-1"}"""
                        "/prefix/v1/models" -> """{"data":[{"id":"selected"}]}"""
                        else -> error("Unexpected endpoint")
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        try {
            val diagnostics = LlamaHealthDiagnostics({ http })
            val config = ProviderRequestConfig("https://local.example/prefix/v1", "secret")
            val report = diagnostics.inspect(config, "selected")
            assertEquals(200, report.healthHttpStatus)
            assertEquals("ok", report.serverStatus)
            assertEquals(true, report.selectedModelListed)
            assertEquals(8192L, report.contextTokens)
            assertEquals(LlamaConnectionPhase.MODEL_LOADED, report.phase)
            assertTrue(report.summary().contains("inferenceTested=false"))
            diagnostics.inspect(config, "selected")
            assertEquals(setOf("/prefix/health", "/prefix/v1/models", "/prefix/props"), paths.toSet())
            assertEquals(3, paths.size)
            assertFalse(report.summary().contains("secret"))
        } finally {
            http.close()
        }
    }

    @Test fun loadingHealthAndMalformedCatalogRemainSeparateUnknownReadiness() = runTest {
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    if (request.url.encodedPath == "/health") {
                        respond("""{"status":"loading model"}""", HttpStatusCode.ServiceUnavailable)
                    } else {
                        respond("""{"data":[{"name":"selected"}]}""")
                    }
                }
            }
        }
        try {
            val report = LlamaHealthDiagnostics({ http }).inspect(ProviderRequestConfig("https://local.example/v1", null), "selected")
            assertEquals(503, report.healthHttpStatus)
            assertEquals("loading model", report.serverStatus)
            assertNull(report.selectedModelListed)
            assertNull(report.templateRevision)
            assertTrue(report.summary().contains("inferenceTested=false"))
        } finally {
            http.close()
        }
    }

    @Test fun timedOutHealthProbeDoesNotDiscardResponsiveModelCatalog() = runTest {
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    if (request.url.encodedPath == "/health") {
                        delay(2_000)
                        respond("""{"status":"ok"}""")
                    } else {
                        respond("""{"data":[{"id":"selected"}]}""")
                    }
                }
            }
        }
        try {
            val report = LlamaHealthDiagnostics({ http }).inspect(ProviderRequestConfig("https://local.example/v1", null), "selected")
            assertNull(report.healthHttpStatus)
            assertNull(report.serverStatus)
            assertEquals(200, report.modelsHttpStatus)
            assertEquals(true, report.selectedModelListed)
            assertTrue(report.summary().contains("inferenceTested=false"))
        } finally {
            http.close()
        }
    }

    @Test fun explicitInferenceProbeVerifiesOneTokenAndNeverSendsToolsOrConversationData() = runTest {
        var inferenceCalls = 0
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
                    val body = when (request.url.encodedPath) {
                        "/health" -> """{"status":"ok"}"""
                        "/v1/models" -> """{"data":[{"id":"selected"}]}"""
                        "/props" -> """{"default_generation_settings":{"n_ctx":4096}}"""
                        "/v1/chat/completions" -> {
                            inferenceCalls++
                            val payload = kotlinx.serialization.json.Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).let { it as kotlinx.serialization.json.JsonObject }
                            assertEquals("1", payload["max_tokens"].toString())
                            assertEquals("true", payload["stream"].toString())
                            assertFalse(payload.containsKey("tools"))
                            assertEquals("\"none\"", payload["tool_choice"].toString())
                            assertEquals(listOf("false"), request.headers.getAll("X-Gateway-Allow-Local-Tools"))
                            assertEquals("false", request.url.parameters["autoload"])
                            assertTrue(payload["messages"].toString().contains("Reply OK."))
                            """{"model":"selected","choices":[{"message":{"content":"OK"},"finish_reason":"length"}],"usage":{"completion_tokens":1}}"""
                        }
                        else -> error("Unexpected endpoint")
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        try {
            val report = LlamaHealthDiagnostics({ http }).testInference(ProviderRequestConfig("https://local.example/v1", "secret", extraHeaders = mapOf("x-gateway-allow-local-tools" to "true")), "selected")
            assertEquals(1, inferenceCalls)
            assertEquals(true, report.inferenceReady)
            assertNull(report.timeToFirstTokenMs)
            assertTrue(report.inferenceLatencyMs != null)
            assertTrue(report.inferenceTested)
            assertEquals(LlamaConnectionPhase.INFERENCE_READY, report.phase)
        } finally {
            http.close()
        }
    }

    @Test fun modelsCatalogAloneNeverTriggersOrQualifiesInferenceProbe() = runTest {
        var inferenceCalls = 0
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    if (request.method.value == "POST") inferenceCalls++
                    if (request.url.encodedPath == "/v1/models") {
                        respond("""{"data":[{"id":"selected"}]}""")
                    } else {
                        respond("Unavailable", HttpStatusCode.ServiceUnavailable)
                    }
                }
            }
        }
        try {
            val report = LlamaHealthDiagnostics({ http }).testInference(ProviderRequestConfig("https://local.example/v1", null), "selected")
            assertEquals(0, inferenceCalls)
            assertFalse(report.inferenceTested)
            assertNull(report.inferenceReady)
            assertEquals(LlamaConnectionPhase.REACHABLE, report.phase)
        } finally {
            http.close()
        }
    }

    @Test fun metadataDnsFailuresRetainTheirOwnStageWithoutDiscardingResponsivePeers() = runTest {
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    if (request.url.encodedPath == "/props") throw java.net.UnknownHostException("not resolved")
                    if (request.url.encodedPath == "/health") {
                        respond("""{"status":"ok"}""")
                    } else {
                        respond("""{"data":[{"id":"selected"}]}""")
                    }
                }
            }
        }
        try {
            val report = LlamaHealthDiagnostics({ http }).inspect(ProviderRequestConfig("https://local.example/v1", null), "selected")
            assertEquals("DNS_FAILED", report.propsFailure)
            assertEquals(200, report.modelsHttpStatus)
            assertEquals(200, report.healthHttpStatus)
            assertNull(report.propsHttpStatus)
            assertFalse(report.inferenceTested)
        } finally {
            http.close()
        }
    }

    @Test fun restrictedAdminRoutesUseExplicitGatewayReadinessForTheSameModelWithoutInference() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    paths += request.url.encodedPath
                    assertEquals("GET", request.method.value)
                    assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
                    assertEquals("selected", request.url.parameters["model"])
                    assertEquals("false", request.url.parameters["autoload"])
                    when (request.url.encodedPath) {
                        "/health", "/props" -> respond("restricted", HttpStatusCode.Forbidden)
                        "/v1/models" -> respond("""{"data":[{"id":"selected"}]}""")
                        "/v1/gateway/ready" -> respond("""{"schemaVersion":1,"instanceId":"${"e".repeat(32)}","ready":true,"capabilities":{"generationReady":true,"modelAvailable":true,"selectedModelDigest":"${researchHash("selected")}"}}""")
                        else -> error("Unexpected endpoint")
                    }
                }
            }
        }
        try {
            val config = ProviderRequestConfig("https://restricted-ready.example/v1", "secret")
            val report = LlamaHealthDiagnostics({ http }).inspect(config, "selected")
            assertEquals(403, report.healthHttpStatus)
            assertEquals(403, report.propsHttpStatus)
            assertTrue(report.metadataRestricted)
            assertEquals(true, report.gatewayReady)
            assertEquals(LlamaConnectionPhase.MODEL_LOADED, report.phase)
            assertFalse(report.inferenceTested)
            assertNull(report.inferenceReady)
            assertNull(report.contextTokens)
            assertEquals("e".repeat(32), GatewayClientDiagnostics.runtimeEpoch(config))
            assertEquals(4, paths.size)
        } finally {
            http.close()
        }
    }

    @Test fun anotherModelOrUnknownGatewaySchemaCannotQualifyReadiness() = runTest {
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    when (request.url.encodedPath) {
                        "/health", "/props" -> respond("restricted", HttpStatusCode.Forbidden)
                        "/v1/models" -> respond("""{"data":[{"id":"selected"}]}""")
                        else -> respond("""{"schemaVersion":1,"instanceId":"${"f".repeat(32)}","ready":true,"capabilities":{"generationReady":true,"modelAvailable":true,"selectedModelDigest":"${researchHash("different")}"}}""")
                    }
                }
            }
        }
        try {
            val report = LlamaHealthDiagnostics({ http }).inspect(ProviderRequestConfig("https://restricted-different.example/v1", null), "selected")
            assertTrue(report.metadataRestricted)
            assertNull(report.gatewayReady)
            assertEquals(LlamaConnectionPhase.REACHABLE, report.phase)
            assertFalse(report.inferenceTested)
        } finally {
            http.close()
        }
    }

    @Test fun explicitStreamingProbeMeasuresFirstTokenAndRejectsUnterminatedOutput() = runTest {
        var terminal = true
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    when (request.url.encodedPath) {
                        "/health" -> respond("""{"status":"ok"}""")
                        "/props" -> respond("{}")
                        "/v1/models" -> respond("""{"data":[{"id":"selected"}]}""")
                        "/v1/chat/completions" -> {
                            delay(75)
                            val delta = """data: {"model":"selected","choices":[{"delta":{"content":"OK"}}]}""" + "\n\n"
                            respond(delta + if (terminal) "data: [DONE]\n\n" else "", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
                        }
                        else -> error("Unexpected endpoint")
                    }
                }
            }
        }
        try {
            val diagnostics = LlamaHealthDiagnostics({ http }, now = { testScheduler.currentTime })
            val config = ProviderRequestConfig("https://streamed-probe.example/v1", null)
            val ready = diagnostics.testInference(config, "selected")
            assertEquals(true, ready.inferenceReady)
            assertEquals(75L, ready.timeToFirstTokenMs)
            assertEquals(75L, ready.inferenceLatencyMs)
            terminal = false
            val interrupted = diagnostics.testInference(config, "selected")
            assertEquals(false, interrupted.inferenceReady)
            assertEquals(75L, interrupted.timeToFirstTokenMs)
            assertTrue(interrupted.inferenceFailure.orEmpty().contains("complete verified"))
        } finally {
            http.close()
        }
    }

    @Test fun recognizedGatewayReportsToolReadinessWithoutPerformingAChatRoundTrip() = runTest {
        var gatewayReads = 0
        val config = ProviderRequestConfig("https://known-gateway.example/v1", "secret")
        GatewayClientDiagnostics.observeInstance(config, "d".repeat(32))
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    assertEquals("GET", request.method.value)
                    assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
                    val body = when (request.url.encodedPath) {
                        "/health" -> """{"status":"ok"}"""
                        "/props" -> "{}"
                        "/v1/models" -> """{"data":[{"id":"selected"}]}"""
                        "/v1/gateway/ready" -> {
                            gatewayReads++
                            """{"schemaVersion":1,"instanceId":"${"d".repeat(32)}","ready":true,"capabilities":{"generationReady":true,"modelAvailable":true,"toolsReady":false,"selectedModelDigest":"${researchHash("selected")}"}}"""
                        }
                        else -> error("Unexpected endpoint")
                    }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        try {
            val report = LlamaHealthDiagnostics({ http }).inspect(config, "selected")
            assertEquals(1, gatewayReads)
            assertEquals(true, report.gatewayReady)
            assertEquals(false, report.gatewayToolsReady)
            assertFalse(report.inferenceTested)
            assertNull(report.timeToFirstTokenMs)
        } finally {
            http.close()
        }
    }
}
