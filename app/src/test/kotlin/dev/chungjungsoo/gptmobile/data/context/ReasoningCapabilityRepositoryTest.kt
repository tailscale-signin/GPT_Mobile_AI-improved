package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReasoningCapabilityRepositoryTest {
    private val profile = PlatformV2(uid = "selected", name = "Fixture", compatibleType = ClientType.LLAMA, model = "exact-model", apiUrl = "https://fixture.invalid/v1", token = "synthetic-secret", enabled = true)

    @Test fun unverifiedGatewayControlCannotBecomeUnsupportedJustBecauseItsCapacityIsKnown() = runTest {
        val repository = ReasoningCapabilityRepository({ _, _ -> ReasoningCapabilityProbe(true, runtimeRevision = "gateway-runtime", controlStatus = ReasoningCapabilityStatus.UNVERIFIED) }, { 10L })
        val result = repository.check(profile, ReasoningMode.OFF)
        assertEquals(ReasoningCapabilityStatus.UNVERIFIED, result.status)
        assertEquals("REASONING_CAPABILITY_UNVERIFIED", result.safeReasonCode)
        assertTrue(result.routeVerified)
        assertFalse(result.permitsRequest)
        assertEquals(ReasoningMode.OFF, result.requested)
    }

    @Test fun expiredNetworkProofDoesNotRenewLongLivedMetadataFromAnotherModelOrTemplate() = kotlinx.coroutines.runBlocking {
        repeat(129) { index ->
            dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics.observeInstance(dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig("https://reasoning-cache-pressure-$index.example/v1", null, profileUid = "pressure-$index"), "a".repeat(32))
        }
        var now = 100L
        var reported = "exact-model"
        var template = "thinking"
        var metadataReads = 0
        var templateReads = 0
        val client = io.ktor.client.HttpClient(
            io.ktor.client.engine.mock.MockEngine { request ->
                val body = when (request.url.encodedPath) {
                    "/props" -> {
                        metadataReads++
                        """{"model_alias":"$reported","build_info":"freshness-fixture","default_generation_settings":{"n_ctx":8192},"chat_template":"$template"}"""
                    }
                    "/apply-template" -> {
                        templateReads++
                        val body = kotlinx.serialization.json.Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text) as kotlinx.serialization.json.JsonObject
                        val toggle = (body["chat_template_kwargs"] as kotlinx.serialization.json.JsonObject)["thinking"]
                        kotlinx.serialization.json.buildJsonObject {
                            put("prompt", kotlinx.serialization.json.JsonPrimitive("user: Template capability check.\nassistant:<think>\n" + if (toggle == kotlinx.serialization.json.JsonPrimitive(true)) "" else "\n</think>\n"))
                        }.toString()
                    }
                    else -> error("No inference is allowed by a capability check")
                }
                respond(body, headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
            }
        )
        val network = io.mockk.mockk<dev.chungjungsoo.gptmobile.data.network.NetworkClient>()
        io.mockk.every { network() } returns client
        io.mockk.every { network.gatewayReadClient() } returns client
        val gateway = io.mockk.mockk<dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI>()
        io.mockk.coEvery { gateway.getCapabilities(any(), forceRefresh = true) } returns null
        val repository = ReasoningCapabilityRepository(network, gateway) { now }
        try {
            val initial = repository.check(profile, ReasoningMode.OFF)
            assertTrue(initial.permitsRequest)
            assertEquals(1, metadataReads)
            assertEquals(2, templateReads)
            assertEquals(initial, repository.check(profile, ReasoningMode.OFF))
            reported = "replacement-model"
            now += 20_001
            val changedModel = repository.check(profile, ReasoningMode.OFF)
            assertEquals(ReasoningCapabilityStatus.UNVERIFIED, changedModel.status)
            assertFalse(changedModel.routeVerified)
            assertEquals(2, metadataReads)
            assertEquals(2, templateReads)
            reported = "exact-model"
            template = "no-toggle"
            now += 20_001
            val changedTemplate = repository.check(profile, ReasoningMode.OFF)
            assertEquals(ReasoningCapabilityStatus.UNSUPPORTED, changedTemplate.status)
            assertFalse(changedTemplate.permitsRequest)
            assertEquals(3, metadataReads)
            assertEquals(2, templateReads)
            io.mockk.coVerify(exactly = 3) { gateway.getCapabilities(any(), forceRefresh = true) }
        } finally {
            client.close()
        }
    }

    @Test fun identicalChecksCoalesceAndExplicitRefreshRequalifies() = runTest {
        var probes = 0
        val release = CompletableDeferred<Unit>()
        val repository = ReasoningCapabilityRepository({ _, _ ->
            probes++
            release.await()
            ReasoningCapabilityProbe(true, ReasoningTemplateControl.THINKING, "runtime-a")
        }, { testScheduler.currentTime })
        val first = async { repository.check(profile, ReasoningMode.OFF) }
        runCurrent()
        val second = async { repository.check(profile, ReasoningMode.OFF) }
        runCurrent()
        assertEquals(1, probes)
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertTrue(first.await().permitsRequest)
        repository.check(profile, ReasoningMode.OFF, forceRefresh = true)
        assertEquals(2, probes)
    }

    @Test fun reachableModelWithoutTemplateControlDoesNotQualifyStrictModes() = runTest {
        val repository = ReasoningCapabilityRepository({ _, _ -> ReasoningCapabilityProbe(true, runtimeRevision = "runtime-a") }, { 10L })
        for (mode in listOf(ReasoningMode.ON, ReasoningMode.OFF)) {
            val result = repository.check(profile, mode)
            assertEquals(ReasoningCapabilityStatus.UNSUPPORTED, result.status)
            assertEquals("REASONING_${mode}_UNSUPPORTED", result.safeReasonCode)
            assertTrue(result.routeVerified)
            assertFalse(result.permitsRequest)
        }
        assertTrue(repository.check(profile, ReasoningMode.AUTO).permitsRequest)
    }

    @Test fun autoSupportDoesNotClaimAnUnreachableRouteWorks() = runTest {
        val repository = ReasoningCapabilityRepository({ _, _ -> ReasoningCapabilityProbe(false) }, { 10L })
        assertEquals(ReasoningCapabilityStatus.UNVERIFIED, repository.check(profile, ReasoningMode.OFF).status)
        val auto = repository.check(profile, ReasoningMode.AUTO)
        assertTrue(auto.permitsRequest)
        assertFalse(auto.routeVerified)
    }

    @Test fun modelCredentialAndModeChangesCannotReuseEarlierQualification() = runTest {
        var probes = 0
        val repository = ReasoningCapabilityRepository({ _, _ ->
            probes++
            ReasoningCapabilityProbe(true, ReasoningTemplateControl.THINKING)
        }, { 100L })
        val initial = repository.check(profile, ReasoningMode.OFF)
        val model = repository.check(profile.copy(model = "different"), ReasoningMode.OFF)
        val credential = repository.check(profile.copy(model = "different", token = "replacement-secret"), ReasoningMode.OFF)
        val auto = repository.check(profile.copy(model = "different", token = "replacement-secret"), ReasoningMode.AUTO)
        assertEquals(4, probes)
        assertEquals(4, setOf(initial.configurationRevision, model.configurationRevision, credential.configurationRevision, auto.configurationRevision).size)
        assertFalse(auto.toString().contains("replacement-secret"))
        assertNotEquals(initial.model, model.model)
    }

    @Test fun expiredObservationsRequireAnotherProbeAndRetainRuntimeIdentity() = runTest {
        var now = 100L
        var runtime = "template-a"
        var probes = 0
        val repository = ReasoningCapabilityRepository({ _, _ ->
            probes++
            ReasoningCapabilityProbe(true, ReasoningTemplateControl.THINKING, runtime)
        }, { now })
        assertEquals("template-a", repository.check(profile, ReasoningMode.ON).runtimeRevision)
        runtime = "template-b"
        now += 20_001
        assertEquals("template-b", repository.check(profile, ReasoningMode.ON).runtimeRevision)
        assertEquals(2, probes)
    }
}
