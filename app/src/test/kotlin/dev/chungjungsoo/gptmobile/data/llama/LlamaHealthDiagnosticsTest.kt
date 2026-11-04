package dev.chungjungsoo.gptmobile.data.llama

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
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
            assertNull(report.contextTokens)
            assertTrue(report.summary().contains("inferenceTested=false"))
            diagnostics.inspect(config, "selected")
            assertEquals(setOf("/prefix/health", "/prefix/v1/models"), paths.toSet())
            assertEquals(2, paths.size)
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
}
