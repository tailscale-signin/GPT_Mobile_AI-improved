package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.EOFException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAdmissionClientTest {
    private val config = ProviderRequestConfig("http://127.0.0.1:29820/v1", null)
    private val sha = "a".repeat(40)
    private val identity = """{"schema_version":1,"protocol_version":1,"instance_id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","gateway_id":"original-installation","principal_id":"network:shared"}"""
    private val conversations = """{"schema_version":1,"gateway_id":"original-installation","principal_id":"network:shared","items":[]}"""
    private val catalog = """{"schema_version":1,"gateway_id":"original-installation","principal_id":"network:shared","server_now":1,"items":[],"complete":true}"""
    private fun capabilities(source: String = sha) = """{"gatewayVersion":"15.0.0","contractVersion":1,"gateway_id":"original-installation","principal_id":"network:shared","instanceId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","build":{"sourceCommit":"$source"},"features":{"conversation_sync_v1":true,"job_catalog_v1":true},"contracts":{"gatewayConversations":["gptmobile.gateway.conversations.v1"],"gatewayJobCatalog":["gptmobile.gateway.job-catalog.v1"]}}"""

    @Test fun explicitRemoteSurfaceDenialKeepsItsRepairAndNeverRequestsAnApiTokenOrRetries() = runBlocking {
        var calls = 0
        HttpClient(
            MockEngine { request ->
                calls++
                assertEquals(HttpMethod.Get, request.method)
                assertNull(request.headers["Authorization"])
                respond("private body need not be read", HttpStatusCode.Forbidden, headersOf("X-Gateway-Admission-Error", "remote_surface_not_admitted"))
            }
        ) { install(HttpTimeout) }.use { http ->
            val failure = runCatching { GatewayJobClient(http).identity(config) }.exceptionOrNull() as GatewayAdmissionException
            assertEquals(GatewayAdmissionReason.REMOTE_SURFACE_NOT_ADMITTED, failure.reason)
            assertEquals("remote_surface_not_admitted", failure.wireCode)
            assertEquals(403, failure.httpStatus)
            assertEquals(GatewayAdmissionSurface.IDENTITY, failure.surface)
            assertEquals(GatewayAdmissionPhase.PRECHECK, failure.phase)
            assertEquals(GatewayAdmissionDispatchState.NOT_DISPATCHED, failure.dispatchState)
            assertEquals(GatewayAdmissionRepair.UPDATE_GATEWAY, failure.repair)
            assertEquals(GatewayAdmissionRetryability.AFTER_REPAIR, failure.retryability)
            assertTrue(failure.message.orEmpty().contains("version label alone"))
            assertFalse(failure.message.orEmpty().contains("original account"))
            assertNull(failure.cause)
        }
        assertEquals(1, calls)
    }

    @Test fun boundedBodyCodesSeparateMissingScopeTransportIdentityAndStorageFromRouteCompatibility() = runBlocking {
        val cases = listOf(
            Triple(HttpStatusCode.Forbidden, "scope_not_granted", GatewayAdmissionReason.MISSING_SCOPE),
            Triple(HttpStatusCode.Forbidden, "engineering_scope_required", GatewayAdmissionReason.MISSING_SCOPE),
            Triple(HttpStatusCode.Unauthorized, "transport_identity_required", GatewayAdmissionReason.TRANSPORT_IDENTITY_REQUIRED),
            Triple(HttpStatusCode.ServiceUnavailable, "conversation_storage_unavailable", GatewayAdmissionReason.STORAGE_UNAVAILABLE),
            Triple(HttpStatusCode.Conflict, "CONTINUITY_IDENTITY_MISMATCH", GatewayAdmissionReason.IDENTITY_MISMATCH)
        )
        for ((status, code, reason) in cases) {
            var calls = 0
            HttpClient(
                MockEngine {
                    calls++
                    respond("""{"error":{"code":"$code","message":"Bearer private-secret https://private.invalid/?token=hidden"}}""", status)
                }
            ) { install(HttpTimeout) }.use { http ->
                val failure = runCatching { GatewayJobClient(http).identity(config) }.exceptionOrNull() as GatewayAdmissionException
                assertEquals(reason, failure.reason)
                assertEquals(status.value, failure.httpStatus)
                assertEquals(code, failure.wireCode)
                assertEquals(1, calls)
                assertFalse(failure.stackTraceToString().contains("private-secret"))
                assertFalse(failure.stackTraceToString().contains("private.invalid"))
            }
        }
    }

    @Test fun unknownMalformedAndOversizedRejectionBodiesCannotEscapeOrBecomeCredentialAdvice() = runBlocking {
        for (body in listOf("""{"error":{"code":"private-secret","message":"private-secret"}}""", "not-json private-secret", "private-secret".repeat(2000))) {
            var calls = 0
            HttpClient(
                MockEngine {
                    calls++
                    respond(body, HttpStatusCode.Forbidden, headersOf("X-Gateway-Admission-Error", "https://private.invalid/?token=private-secret"))
                }
            ) { install(HttpTimeout) }.use { http ->
                val failure = runCatching { GatewayJobClient(http).catalog(config) }.exceptionOrNull() as GatewayAdmissionException
                assertEquals(GatewayAdmissionReason.HTTP_REJECTED, failure.reason)
                assertNull(failure.wireCode)
                assertNull(failure.cause)
                assertFalse(failure.stackTraceToString().contains("private-secret"))
                assertFalse(failure.stackTraceToString().contains("private.invalid"))
                assertEquals(1, calls)
            }
        }
    }

    @Test fun unsupportedAndCursorCatchCompatibilityDoesNotHideAnExplicitIdentityOrScopeFailure() = runBlocking {
        for ((status, body, expected) in listOf(
            Triple(HttpStatusCode.NotFound, "missing", GatewayHistoryUnsupported::class.java),
            Triple(HttpStatusCode.Conflict, """{"error":{"code":"cursor_expired"}}""", GatewayCursorExpired::class.java),
            Triple(HttpStatusCode.Conflict, """{"error":{"code":"CONTINUITY_IDENTITY_MISMATCH"}}""", GatewayAdmissionException::class.java)
        )) {
            HttpClient(MockEngine { respond(body, status) }) { install(HttpTimeout) }.use { http ->
                val failure = runCatching { GatewayJobClient(http).catalog(config) }.exceptionOrNull()
                assertEquals(expected, failure?.javaClass)
            }
        }
        HttpClient(MockEngine { respond("""{"error":{"code":"scope_not_granted"}}""", HttpStatusCode.NotFound) }) { install(HttpTimeout) }.use { http ->
            val failure = runCatching { GatewayJobClient(http).command(config, "original-command") }.exceptionOrNull() as GatewayAdmissionException
            assertEquals(GatewayAdmissionReason.MISSING_SCOPE, failure.reason)
            assertEquals(GatewayAdmissionPhase.RECONCILIATION, failure.phase)
            assertEquals(GatewayAdmissionDispatchState.UNKNOWN, failure.dispatchState)
        }
    }

    @Test fun exhaustedSafeReadsKeepTransportSeparateAndNeverSubmitOrChangeCommandIdentity() = runBlocking {
        var calls = 0
        var evictions = 0
        HttpClient(
            MockEngine { request ->
                calls++
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/v1/gateway/commands/original-command", request.url.encodedPath)
                throw EOFException("private upstream https://private.invalid/")
            }
        ) { install(HttpTimeout) }.use { http ->
            val failure = runCatching { GatewayJobClient(http, { evictions++ }).command(config, "original-command") }.exceptionOrNull() as GatewayAdmissionException
            assertEquals(GatewayAdmissionReason.TRANSPORT_INTERRUPTED, failure.reason)
            assertEquals(GatewayAdmissionRetryability.RECONCILE_ORIGINAL_COMMAND, failure.retryability)
            assertEquals(GatewayAdmissionPhase.RECONCILIATION, failure.phase)
            assertEquals(GatewayAdmissionDispatchState.UNKNOWN, failure.dispatchState)
            assertTrue(failure.message.orEmpty().contains("outcome remains unconfirmed"))
            assertNull(failure.httpStatus)
            assertNull(failure.cause)
            assertFalse(failure.stackTraceToString().contains("private.invalid"))
        }
        assertEquals(2, calls)
        assertEquals(1, evictions)
    }

    @Test fun cancellationAndTlsFailuresNeverBecomeReadRetries() = runBlocking {
        for (cause in listOf(CancellationException("caller stopped"), SSLException("private certificate detail"))) {
            var calls = 0
            var evictions = 0
            HttpClient(
                MockEngine {
                    calls++
                    throw cause
                }
            ) { install(HttpTimeout) }.use { http ->
                val failure = runCatching { GatewayJobClient(http, { evictions++ }).identity(config) }.exceptionOrNull()
                if (cause is CancellationException) {
                    assertTrue(failure is CancellationException)
                } else {
                    assertEquals(GatewayAdmissionReason.INVALID_CONNECTION, (failure as GatewayAdmissionException).reason)
                }
            }
            assertEquals(1, calls)
            assertEquals(0, evictions)
        }
    }

    @Test fun identityRequiresItsVersionedContractAndDoesNotExposeMalformedResponseContent() = runBlocking {
        for (body in listOf("""{"gateway_id":"old","principal_id":"owner"}""", """{"schema_version":"private-secret","protocol_version":1,"gateway_id":"old","principal_id":"owner"}""")) {
            HttpClient(MockEngine { respond(body) }) { install(HttpTimeout) }.use { http ->
                val failure = runCatching { GatewayJobClient(http).identity(config) }.exceptionOrNull() as GatewayAdmissionException
                assertEquals(GatewayAdmissionReason.INVALID_RESPONSE, failure.reason)
                assertFalse(failure.stackTraceToString().contains("private-secret"))
            }
        }
    }

    @Test fun sameVersionAndAdvertisedFeaturesCannotOverrideAnInstalledRouteDenial() = runBlocking {
        val paths = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                when {
                    request.url.encodedPath.endsWith("capabilities") -> respond(capabilities())
                    else -> respond("{}", HttpStatusCode.Forbidden, headersOf("X-Gateway-Admission-Error", "remote_surface_not_admitted"))
                }
            }
        ) { install(HttpTimeout) }.use { http ->
            val failure = runCatching { GatewayJobClient(http).verifySavedWorkContract(config) }.exceptionOrNull() as GatewayAdmissionException
            assertEquals(GatewayAdmissionReason.REMOTE_SURFACE_NOT_ADMITTED, failure.reason)
            assertEquals("15.0.0", failure.source?.gatewayVersion)
            assertEquals(sha, failure.source?.sourceCommit)
            assertEquals(listOf("/v1/gateway/capabilities", "/v1/gateway/identity"), paths)
        }
    }

    @Test fun matchingSourceAndAllThreeReadContractsStillDoNotVerifyGenerationEngineeringOrGraph() = runBlocking {
        val paths = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertNull(request.headers["Authorization"])
                paths += request.url.encodedPath
                when {
                    request.url.encodedPath.endsWith("capabilities") -> respond(capabilities())
                    request.url.encodedPath.endsWith("identity") -> respond(identity)
                    request.url.encodedPath.endsWith("conversations") -> {
                        assertEquals("1", request.url.parameters["limit"])
                        respond(conversations)
                    }
                    request.url.encodedPath.endsWith("job-catalog") -> {
                        assertEquals("1", request.url.parameters["limit"])
                        respond(catalog)
                    }
                    else -> error("Unexpected route")
                }
            }
        ) { install(HttpTimeout) }.use { http ->
            val result = GatewayJobClient(http).verifySavedWorkContract(config, sha)
            assertEquals(true, result.matchesExpectedSource)
            assertEquals("network:shared", result.identity.principalId)
            assertEquals(GatewayActionReadiness.VERIFIED, result.readiness.identity)
            assertEquals(GatewayActionReadiness.VERIFIED, result.readiness.conversations)
            assertEquals(GatewayActionReadiness.VERIFIED, result.readiness.jobs)
            assertEquals(GatewayActionReadiness.NOT_VERIFIED, result.readiness.engineering)
            assertEquals(GatewayActionReadiness.NOT_VERIFIED, result.readiness.graph)
            assertEquals(4, paths.size)
            assertFalse(paths.any { it.contains("completions") || it.contains("/models") || it.contains("/engineering") })
        }
    }

    @Test fun aChangedAccountAcrossReadSurfacesCannotBecomeVerifiedActionReadiness() = runBlocking {
        HttpClient(
            MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("capabilities") -> respond(capabilities())
                    request.url.encodedPath.endsWith("identity") -> respond(identity)
                    request.url.encodedPath.endsWith("conversations") -> respond(conversations.replace("network:shared", "other-owner"))
                    else -> respond(catalog)
                }
            }
        ) { install(HttpTimeout) }.use { http ->
            val failure = runCatching { GatewayJobClient(http).verifySavedWorkContract(config) }.exceptionOrNull() as GatewayAdmissionException
            assertEquals(GatewayAdmissionReason.IDENTITY_MISMATCH, failure.reason)
        }
    }

    @Test fun sourceMismatchStopsBeforeActionRouteChecksEvenWhenTheVersionLabelMatches() = runBlocking {
        for (source in listOf("b".repeat(40), "private-source-value")) {
            var calls = 0
            HttpClient(
                MockEngine {
                    calls++
                    respond(capabilities(source))
                }
            ) { install(HttpTimeout) }.use { http ->
                val failure = runCatching { GatewayJobClient(http).verifySavedWorkContract(config, sha) }.exceptionOrNull() as GatewayAdmissionException
                assertEquals(GatewayAdmissionReason.SOURCE_MISMATCH, failure.reason)
                assertEquals("15.0.0", failure.source?.gatewayVersion)
                assertFalse(failure.toString().contains("private-source-value"))
                if (source == "private-source-value") assertNull(failure.source?.sourceCommit)
            }
            assertEquals(1, calls)
        }
    }

    @Test fun terminalDetailsRetainSafeReconciliationEvidenceWithoutRawResponseData() {
        val failure = gatewayAdmissionFailure(503, GatewayAdmissionSurface.COMMAND, null, "private token and endpoint")
            .withSource(GatewayAdmissionSource("15.0.0", sha))
        val details = failure.safeDetails()
        assertEquals("RECONCILIATION", details["phase"])
        assertEquals("UNKNOWN", details["dependent_dispatch_state"])
        assertEquals("RECONCILE_ORIGINAL_COMMAND", details["retryability"])
        assertEquals("503", details["http_status"])
        assertEquals(sha, details["gateway_source_commit"])
        assertFalse(details.containsKey("admission_code"))
        assertFalse(details.toString().contains("private"))
        assertFalse(failure.toString().contains("private"))
        val precheck = gatewayAdmissionFailure(403, GatewayAdmissionSurface.IDENTITY, "remote_surface_not_admitted")
        assertEquals("PRECHECK", precheck.safeDetails()["phase"])
        assertEquals("NOT_DISPATCHED", precheck.safeDetails()["dependent_dispatch_state"])
        assertEquals("remote_surface_not_admitted", precheck.safeDetails()["admission_code"])
    }
}
