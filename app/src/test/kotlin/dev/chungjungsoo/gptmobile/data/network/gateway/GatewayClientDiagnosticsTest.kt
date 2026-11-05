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
        LocalDiagnosticsPolicy.setEnabled(false)
        val config = ProviderRequestConfig("https://debug-off.example/v1", null, providerType = "LLAMA", extraHeaders = mapOf("x-gateway-client-diagnostics" to "secret", "Accept" to "application/json"))
        assertEquals(mapOf("Accept" to "application/json"), GatewayClientDiagnostics.withHeaders(config, "selected").extraHeaders)
    }

    @Test fun observedRestartsAreSharedAcrossProfilesButIsolatedByCredentialAndCustomAuthentication() {
        val first = ProviderRequestConfig("https://runtime-epoch.example/v1", "token", profileUid = "first")
        val second = first.copy(profileUid = "second")
        val before = GatewayClientDiagnostics.capabilityEpoch(first)
        GatewayClientDiagnostics.observeInstance(first, "a".repeat(32))
        assertEquals("a".repeat(32), GatewayClientDiagnostics.runtimeEpoch(second))
        assertEquals(before, GatewayClientDiagnostics.capabilityEpoch(first))
        GatewayClientDiagnostics.observeInstance(second, "b".repeat(32))
        assertTrue(GatewayClientDiagnostics.capabilityEpoch(first) > before)
        GatewayClientDiagnostics.observeInstance(second, "raw secret")
        assertEquals("b".repeat(32), GatewayClientDiagnostics.runtimeEpoch(first))
        assertEquals("unobserved", GatewayClientDiagnostics.runtimeEpoch(first.copy(token = "different")))
        assertEquals("unobserved", GatewayClientDiagnostics.runtimeEpoch(first.copy(extraHeaders = mapOf("X-Api-Key" to "different"))))
        assertNotEquals(GatewayClientDiagnostics.routeIdentity(first), GatewayClientDiagnostics.routeIdentity(first.copy(apiUrl = "https://other-runtime.example/v1")))
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
}
