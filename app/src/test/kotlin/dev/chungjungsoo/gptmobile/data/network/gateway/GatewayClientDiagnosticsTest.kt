package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayClientDiagnosticsTest {
    @Test fun serverPhysicalRequestIdentityIsDistinctFromTheLogicalOperationDigestAndStrictlyValidated() {
        val config = ProviderRequestConfig("https://physical-request.example/v1", null)
        val headers = mapOf("X-Gateway-Request-ID" to "logical-original-command", "X-Gateway-HTTP-Request-ID" to "a".repeat(32))
        val first = GatewayClientDiagnostics.responseFields(config, 503, headers::get)
        val second = GatewayClientDiagnostics.responseFields(config, 503, (headers + ("X-Gateway-HTTP-Request-ID" to "b".repeat(32)))::get)
        assertEquals("a".repeat(32), first["request_id"]?.jsonPrimitive?.content)
        assertEquals("b".repeat(32), second["request_id"]?.jsonPrimitive?.content)
        assertEquals(researchHash("logical-original-command"), first["operation_id"]?.jsonPrimitive?.content)
        assertEquals(first["operation_id"], second["operation_id"])
        val invalid = GatewayClientDiagnostics.responseFields(config, 503, (headers + ("X-Gateway-HTTP-Request-ID" to "private-host-and-secret"))::get)
        assertEquals(researchHash("logical-original-command"), invalid["request_id"]?.jsonPrimitive?.content)
        assertFalse(invalid.toString().contains("private-host"))
    }

    @Test fun metadataContainsVersionedDigestsAndPlatformCountersButNoPersonalText() {
        val config = ProviderRequestConfig("https://private-route.example/v1", "private token", profileUid = "private profile", requestRole = "custom sensitive role")
        val encoded = GatewayClientDiagnostics.metadata(config, "private model", 36, 44, 9)
        val data = Json.parseToJsonElement(encoded) as JsonObject
        assertEquals("1", data["schemaVersion"]?.jsonPrimitive?.content)
        assertEquals("36", data["androidSdk"]?.jsonPrimitive?.content)
        assertEquals("44", data["appVersionCode"]?.jsonPrimitive?.content)
        assertEquals("9", data["networkEpoch"]?.jsonPrimitive?.content)
        assertEquals("other", data["requestRole"]?.jsonPrimitive?.content)
        assertEquals(researchHash("private profile"), data["profileDigest"]?.jsonPrimitive?.content)
        assertEquals(researchHash("private model"), data["modelDigest"]?.jsonPrimitive?.content)
        assertFalse(encoded.contains("private"))
        assertFalse(encoded.contains("sensitive"))
    }

    @Test fun disabledDiagnosticsRemoveEvenAnInjectedClientHeader() {
        val original = LocalDiagnosticsPolicy.enabled
        try {
            LocalDiagnosticsPolicy.setEnabled(false)
            val config = ProviderRequestConfig("https://debug-off.example/v1", null, providerType = "LLAMA", extraHeaders = mapOf("x-gateway-client-diagnostics" to "secret", "Accept" to "application/json"))
            assertEquals(mapOf("Accept" to "application/json"), GatewayClientDiagnostics.withHeaders(config, "selected").extraHeaders)
        } finally {
            LocalDiagnosticsPolicy.setEnabled(original)
        }
    }

    @Test fun observedRestartsAreSharedAcrossProfilesButIsolatedByCredentialAndCustomAuthentication() {
        val first = ProviderRequestConfig("https://runtime-epoch.example/v1", "token", profileUid = "first")
        val second = first.copy(profileUid = "second")
        val before = GatewayClientDiagnostics.capabilityEpoch(first)
        GatewayClientDiagnostics.observeInstance(first, "a".repeat(32))
        assertEquals("a".repeat(32), GatewayClientDiagnostics.runtimeEpoch(second))
        val observed = GatewayClientDiagnostics.capabilityEpoch(first)
        assertTrue(observed >= before)
        GatewayClientDiagnostics.observeInstance(second, "a".repeat(32))
        assertEquals(observed, GatewayClientDiagnostics.capabilityEpoch(first))
        assertEquals(observed, GatewayClientDiagnostics.capabilityEpoch(second))
        GatewayClientDiagnostics.observeInstance(second, "b".repeat(32))
        assertTrue(GatewayClientDiagnostics.capabilityEpoch(first) > observed)
        GatewayClientDiagnostics.observeInstance(second, "raw secret")
        assertEquals("b".repeat(32), GatewayClientDiagnostics.runtimeEpoch(first))
        assertEquals("unobserved", GatewayClientDiagnostics.runtimeEpoch(first.copy(token = "different")))
        assertEquals("unobserved", GatewayClientDiagnostics.runtimeEpoch(first.copy(extraHeaders = mapOf("X-Api-Key" to "different"))))
        assertNotEquals(GatewayClientDiagnostics.routeIdentity(first), GatewayClientDiagnostics.routeIdentity(first.copy(apiUrl = "https://other-runtime.example/v1")))
    }

    @Test fun firstObservationUnderCachePressureAdvancesConservativelyThenKeepsTheSameInstanceStable() {
        repeat(129) { index ->
            GatewayClientDiagnostics.observeInstance(ProviderRequestConfig("https://initial-epoch-pressure-$index.example/v1", null, profileUid = "initial-epoch-pressure-$index"), "c".repeat(32))
        }
        val config = ProviderRequestConfig("https://initial-epoch-after-eviction.example/v1", "credential", profileUid = "initial-epoch-after-eviction")
        val before = GatewayClientDiagnostics.capabilityEpoch(config)
        GatewayClientDiagnostics.observeInstance(config, "d".repeat(32))
        val first = GatewayClientDiagnostics.capabilityEpoch(config)
        assertTrue(first > before)
        GatewayClientDiagnostics.observeInstance(config, "d".repeat(32))
        assertEquals(first, GatewayClientDiagnostics.capabilityEpoch(config))
        GatewayClientDiagnostics.observeInstance(config, "e".repeat(32))
        assertTrue(GatewayClientDiagnostics.capabilityEpoch(config) > first)
    }

    @Test fun knownReviewStagesHaveCanonicalMetadataInsteadOfArbitraryRoleText() {
        for ((role, expected) in listOf("github-review-preflight" to "review", "github-review-repair" to "review", "github-claim-extraction" to "github", "github-generation" to "github")) {
            val config = ProviderRequestConfig("https://roles.example/v1", null, requestRole = role)
            val data = Json.parseToJsonElement(GatewayClientDiagnostics.metadata(config, "selected", 36, 1, 0)) as JsonObject
            assertEquals(expected, data["requestRole"]?.jsonPrimitive?.content)
        }
    }

    @Test fun dispatchRechecksDebugAndTemporaryConversationGatesForAnAlreadyPreparedHeader() {
        val original = LocalDiagnosticsPolicy.enabled
        val config = ProviderRequestConfig("https://privacy.example/v1", null, extraHeaders = mapOf(GatewayClientDiagnostics.CLIENT_HEADER to "{\"schemaVersion\":1}", "X-Api-Key" to "credential"))
        try {
            LocalDiagnosticsPolicy.setEnabled(true)
            assertTrue(GatewayClientDiagnostics.requestHeaders(config).containsKey(GatewayClientDiagnostics.CLIENT_HEADER))
            LocalDiagnosticsPolicy.setEnabled(false)
            assertEquals(mapOf("X-Api-Key" to "credential"), GatewayClientDiagnostics.requestHeaders(config))
            LocalDiagnosticsPolicy.setEnabled(true)
            AppLogRecorder.setPrivateSession("gateway-private-test", true)
            assertEquals(mapOf("X-Api-Key" to "credential"), GatewayClientDiagnostics.requestHeaders(config))
            assertFalse(GatewayClientDiagnostics.withHeaders(config.copy(providerType = "LLAMA"), "selected").extraHeaders.containsKey(GatewayClientDiagnostics.CLIENT_HEADER))
        } finally {
            AppLogRecorder.setPrivateSession("gateway-private-test", false)
            LocalDiagnosticsPolicy.setEnabled(original)
        }
    }

    @Test fun profileBindingsObserveSharedRestartsWithoutReadingCredentialsAndEvictionCannotReuseAnOldEpoch() {
        val first = ProviderRequestConfig("https://bound-epoch.example/v1", "credential", profileUid = "bound-first")
        val second = first.copy(profileUid = "bound-second")
        GatewayClientDiagnostics.observeInstance(first, "1".repeat(32))
        GatewayClientDiagnostics.observeInstance(second, "1".repeat(32))
        val original = GatewayClientDiagnostics.profileCapabilityEpoch("bound-first", first.apiUrl)
        GatewayClientDiagnostics.observeInstance(second, "2".repeat(32))
        val changed = GatewayClientDiagnostics.profileCapabilityEpoch("bound-first", first.apiUrl)
        assertTrue(changed > original)
        assertEquals(changed, GatewayClientDiagnostics.profileCapabilityEpoch("bound-second", first.apiUrl))
        repeat(130) { index ->
            GatewayClientDiagnostics.observeInstance(ProviderRequestConfig("https://eviction-$index.example/v1", null, profileUid = "eviction-$index"), "3".repeat(32))
        }
        assertTrue(GatewayClientDiagnostics.profileCapabilityEpoch("bound-first", first.apiUrl) > changed)
        GatewayClientDiagnostics.observeInstance(first, "4".repeat(32))
        assertTrue(GatewayClientDiagnostics.profileCapabilityEpoch("bound-first", first.apiUrl) > changed)
    }

    @Test fun missingAcknowledgmentStaysUnknownEvenForAnHttpRejection() {
        val config = ProviderRequestConfig("https://ack.example/v1", null, extraHeaders = mapOf(GatewayClientDiagnostics.CLIENT_HEADER to "{}"))
        for (status in listOf(200, 401, 403, 500)) {
            val fields = GatewayClientDiagnostics.responseFields(config, status) { null }
            val attributes = fields["bounded_attributes"] as JsonObject
            assertEquals("NOT_CONFIRMED", attributes.diagnosticText("client_metadata_acknowledgment"))
            assertFalse(attributes.containsKey("client_metadata_accepted"))
            assertEquals("response_received", fields.diagnosticText("dispatch_state"))
            assertEquals("true", attributes["response_headers_received"].toString())
        }
    }

    @Test fun explicitAcceptanceAndNoRequestStayDistinctFromAdmissionRejection() {
        val plain = ProviderRequestConfig("https://ack.example/v1", null)
        val requested = plain.copy(extraHeaders = mapOf(GatewayClientDiagnostics.CLIENT_HEADER to "{}"))
        fun acknowledgment(config: ProviderRequestConfig, status: Int, headers: Map<String, String>): String? =
            (GatewayClientDiagnostics.responseFields(config, status) { headers[it] }["bounded_attributes"] as JsonObject).diagnosticText("client_metadata_acknowledgment")
        assertEquals("NOT_REQUESTED", acknowledgment(plain, 200, emptyMap()))
        assertEquals("ACCEPTED", acknowledgment(requested, 500, mapOf("X-Gateway-Client-Diagnostics-Accepted" to "1")))
        assertEquals("NOT_CONFIRMED", acknowledgment(requested, 200, mapOf("X-Gateway-Client-Diagnostics-Accepted" to "0")))
        for ((code, status) in mapOf("remote_surface_not_admitted" to 403, "scope_not_granted" to 403, "transport_identity_required" to 401, "duplicate_admission_header" to 400, "unknown_job" to 404)) {
            val headers = mapOf("X-Gateway-Admission-Error" to code)
            assertEquals("REJECTED_BEFORE_PROCESSING", acknowledgment(requested, status, headers))
            assertEquals("NOT_CONFIRMED", acknowledgment(requested, 500, headers))
        }
        assertEquals("NOT_CONFIRMED", acknowledgment(requested, 403, mapOf("X-Gateway-Admission-Error" to "conversation_storage_unavailable")))
        assertEquals("NOT_CONFIRMED", acknowledgment(requested, 403, mapOf("X-Gateway-Admission-Error" to "CONTINUITY_IDENTITY_MISMATCH")))
    }

    @Test fun admissionResponseProjectionRetainsOnlyBoundedContractValues() {
        val config = ProviderRequestConfig("https://private.example/v1", "private token", extraHeaders = mapOf(GatewayClientDiagnostics.CLIENT_HEADER to "{}"))
        val response = GatewayClientDiagnostics.responseFields(config, 403) { if (it == "X-Gateway-Admission-Error") "remote_surface_not_admitted" else null }
        val attributes = response["bounded_attributes"] as JsonObject
        assertEquals("remote_surface_not_admitted", attributes.diagnosticText("gateway_admission_code"))
        assertEquals("HTTP_ERROR", response.diagnosticText("error_code"))
        assertEquals("response_received", response.diagnosticText("dispatch_state"))
        assertFalse(response.toString().contains("private"))
        val unsafe = GatewayClientDiagnostics.responseFields(config, 403) { "https://private.example/token" }
        assertFalse(unsafe.toString().contains("private"))
        assertFalse((unsafe["bounded_attributes"] as JsonObject).containsKey("gateway_admission_code"))
        val unknown = GatewayDiagnosticEvent.fields(Json.parseToJsonElement("""{"bounded_attributes":{"client_metadata_acknowledgment":"private token","gateway_admission_code":"private token"}}""") as JsonObject)
        assertTrue((unknown["bounded_attributes"] as JsonObject).isEmpty())
    }
}
