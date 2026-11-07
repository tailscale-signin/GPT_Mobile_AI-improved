package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.agent.tool.McpClientManager
import dev.chungjungsoo.gptmobile.data.agent.tool.McpConnectionHealth
import dev.chungjungsoo.gptmobile.data.agent.tool.McpConnectionHealthState
import dev.chungjungsoo.gptmobile.data.agent.tool.McpProtocolConfigurationException
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.catalog.MarketplaceRuntime
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.marketplace.MarketplacePackageStore
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceRegistry
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import dev.chungjungsoo.gptmobile.data.network.ConnectionHealth
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPIImpl
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilityDiscoveryException
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditPluginServicesTest {
    @Test
    fun localInventoryAccountsForBuiltInsAndUninstalledPackagesWithoutCredentialsOrNetwork() = runBlocking {
        val setup = Setup()
        val plan = setup.source.plan(AppAuditOptions())
        val configurations = plan.filter { it.probeId == AppAuditPluginProbeSource.CONFIGURATION }
        assertTrue(configurations.any { it.componentId == "calculator" && it.prerequisite == AppAuditPrerequisite.READY })
        assertTrue(configurations.any { it.componentId == "voice_chat" && it.reasonCode == "PLUGIN_DISABLED" })
        for (entry in GitHubMarketplaceCatalog.packages.filter { it.runtime == MarketplaceRuntime.NATIVE }) {
            assertTrue(configurations.any { it.componentId == "marketplace:${entry.id}" && it.reasonCode == "PLUGIN_NOT_INSTALLED" })
        }
        assertTrue(plan.any { it.componentId == "health_connect" && it.reasonCode == "PLUGIN_EXECUTION_NOT_EXERCISED" })
        assertTrue(plan.any { it.componentId == "read_files" && it.reasonCode == "PLUGIN_EXECUTION_NOT_EXERCISED" && it.prerequisite == AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED })
        assertTrue(configurations.size >= ToolServiceCatalog.integrated.size)
        coVerify(exactly = 0) { setup.settings.fetchPlatformV2s() }
        coVerify(exactly = 1) { setup.native.load() }
        coVerify(exactly = 1) { setup.packages.downloadedIds() }
        confirmVerified(setup.native, setup.packages, setup.resolver, setup.gateway)
    }

    @Test
    fun configuredMcpDiscoveryUsesForcedAwaitedCatalogueOnlyAfterMarker() = runBlocking {
        val setup = Setup()
        val connection = connection()
        var marked = false
        setup.configure(listOf(connection))
        coEvery { setup.resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true) } coAnswers {
            assertTrue(marked)
            emptyList()
        }
        val entry = setup.source.plan(AppAuditOptions(allowNetworkReads = true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
        val result = setup.source.execute(entry, context { marked = true })
        assertEquals(AppAuditOutcome.PASS, result.outcome)
        assertEquals("MCP_EMPTY_CATALOG", result.reasonCode)
        assertEquals(AppAuditEvidenceKind.LIVE_METADATA, result.evidenceKind)
        assertEquals(AppAuditDispatchEvidence.CONFIRMED, result.dispatchEvidence)
        coVerify(exactly = 1) { setup.resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true) }
        confirmVerified(setup.resolver)
        // Revalidation targets one connection and does not repeatedly verify every package.
        coVerify(exactly = 1) { setup.packages.downloadedIds() }
        confirmVerified(setup.packages)
    }

    @Test
    fun permanentMcpGatePreservesCodeWithoutDiscoveryRepairOrNetworkBudget() = runBlocking {
        val setup = Setup()
        setup.configure(listOf(connection()))
        every { setup.mcp.healthSnapshot(any()) } returns McpConnectionHealth(
            state = McpConnectionHealthState.UNREACHABLE,
            lastFailureAtMs = 1,
            nextRetryAtMs = Long.MAX_VALUE,
            lastErrorCode = "MCP_PROTOCOL_VERSION_UNSUPPORTED",
            lastError = "PRIVATE_SERVER_VALUE",
            connectionHealth = ConnectionHealth.HTTP_ERROR
        )
        var markers = 0
        val entry = setup.source.plan(AppAuditOptions(allowNetworkReads = true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
        val result = setup.source.execute(entry, context { markers++ })
        assertEquals(AppAuditOutcome.BLOCKED, result.outcome)
        assertEquals("MCP_PROTOCOL_VERSION_UNSUPPORTED", result.reasonCode)
        assertEquals(false, result.retryable)
        assertEquals(AppAuditObservationSource.CACHE, result.observationSource)
        assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, result.dispatchEvidence)
        assertFalse(result.toString().contains("PRIVATE_SERVER_VALUE"))
        assertEquals(0, markers)
        confirmVerified(setup.resolver)
    }

    @Test
    fun protocolFailureReturnsSafeCodeAndNeverOriginalServerDetail() = runBlocking {
        val setup = Setup()
        val connection = connection()
        setup.configure(listOf(connection))
        coEvery { setup.resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true) } throws McpProtocolConfigurationException("MCP_DISCOVERY_SCHEMA_INVALID", "PRIVATE_SERVER_VALUE")
        val entry = setup.source.plan(AppAuditOptions(allowNetworkReads = true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
        val result = setup.source.execute(entry, context())
        assertEquals(AppAuditOutcome.FAIL, result.outcome)
        assertEquals("MCP_DISCOVERY_SCHEMA_INVALID", result.reasonCode)
        assertEquals(false, result.retryable)
        assertFalse(result.toString().contains("PRIVATE_SERVER_VALUE"))
    }

    @Test
    fun disabledMcpAndMissingCredentialRemainVisibleWithoutDiscovery() = runBlocking {
        val setup = Setup()
        setup.configure(listOf(connection(), connection("credential-missing").copy(authType = ToolConnectionAuthType.BEARER)))
        coEvery { setup.settings.getFeatureSettings() } returns AppFeatureSettings(remoteMcpConnections = false)
        val plan = setup.source.plan(AppAuditOptions(allowNetworkReads = true))
        val discoveries = plan.filter { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
        assertEquals(2, discoveries.size)
        assertTrue(discoveries.any { it.reasonCode == "PLUGIN_DISABLED" })
        assertTrue(discoveries.any { it.reasonCode == "PLUGIN_NOT_CONFIGURED" })
        discoveries.forEach { entry -> assertTrue(setup.source.execute(entry, context()).outcome in setOf(AppAuditOutcome.BLOCKED, AppAuditOutcome.UNAVAILABLE)) }
        confirmVerified(setup.resolver)
    }

    @Test
    fun actualLegacyAndCurrentRoutesDecodeOnExactSelectedProfileWithoutInference() = runBlocking {
        for (fixtureName in listOf("gateway-v15-capabilities-legacy-mixed.json", "gateway-v15-capabilities-full.json")) {
            val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream(fixtureName)).bufferedReader().use { it.readText() }
            var calls = 0
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals("GET", request.method.value)
                    assertEquals("127.0.0.1", request.url.host)
                    assertEquals(8097, request.url.port)
                    assertEquals("/private/v1/gateway/capabilities", request.url.encodedPath)
                    assertEquals("Bearer private-credential", request.headers["Authorization"])
                    respond(fixture, HttpStatusCode.OK)
                }
            ).use { client ->
                val network = mockk<NetworkClient>()
                every { network() } returns client
                val setup = Setup(GatewayAPIImpl(network))
                val profile = profile()
                coEvery { setup.settings.fetchPlatformV2s() } returns listOf(profile, profile.copy(uid = "other", apiUrl = "http://127.0.0.1:9999/v1"))
                val entry = setup.source.plan(AppAuditOptions(true, listOf(profile.uid))).single { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
                val result = setup.source.execute(entry, context())
                assertEquals(AppAuditOutcome.PASS, result.outcome)
                assertEquals("GATEWAY_CAPABILITY_VERIFIED", result.reasonCode)
                assertEquals(AppAuditDispatchEvidence.CONFIRMED, result.dispatchEvidence)
                assertEquals(AppAuditEvidenceKind.LIVE_METADATA, result.evidenceKind)
                assertEquals(1, calls)
                assertFalse(result.toString().contains("private-credential"))
            }
        }
    }

    @Test
    fun removedOrIneligibleSelectionCannotChooseAnotherProfile() = runBlocking {
        val setup = Setup()
        coEvery { setup.settings.fetchPlatformV2s() } returns listOf(profile().copy(uid = "other"), profile().copy(uid = "cloud", apiUrl = "https://cloud.example/v1"))
        val entries = setup.source.plan(AppAuditOptions(true, listOf("missing", "cloud"))).filter { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
        assertEquals(2, entries.size)
        assertEquals(setOf("GATEWAY_PROFILE_UNAVAILABLE", "GATEWAY_PROFILE_UNSUPPORTED"), entries.map { it.reasonCode }.toSet())
        entries.forEach { assertEquals(AppAuditOutcome.UNAVAILABLE, setup.source.execute(it, context()).outcome) }
        confirmVerified(setup.gateway)
    }

    @Test
    fun gatewaySchemaAndTlsFailuresKeepTypedReasonsWithoutPrivateMessages() = runBlocking {
        val setup = Setup()
        coEvery { setup.settings.fetchPlatformV2s() } returns listOf(profile())
        val entry = setup.source.plan(AppAuditOptions(true, listOf("selected"))).single { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
        coEvery { setup.gateway.getCapabilities(any()) } throws GatewayCapabilityDiscoveryException("PRIVATE_DECODER_VALUE", decodeReason = "EXPECTED_INTEGER", fieldPath = "PRIVATE_KEY")
        val decode = setup.source.execute(entry, context())
        assertEquals("GATEWAY_CAPABILITY_DECODE_EXPECTED_INTEGER", decode.reasonCode)
        assertEquals(AppAuditDispatchEvidence.CONFIRMED, decode.dispatchEvidence)
        assertFalse(decode.toString().contains("PRIVATE"))
        coEvery { setup.gateway.getCapabilities(any()) } throws SSLHandshakeException("PRIVATE_CERTIFICATE_VALUE")
        val tls = setup.source.execute(entry, context())
        assertEquals("TLS_FAILURE", tls.reasonCode)
        assertEquals(false, tls.retryable)
        assertEquals(AppAuditDispatchEvidence.UNKNOWN, tls.dispatchEvidence)
        assertFalse(tls.toString().contains("PRIVATE"))
    }

    @Test
    fun gatewayCancellationPropagatesWithoutRetry() = runBlocking {
        val setup = Setup()
        coEvery { setup.settings.fetchPlatformV2s() } returns listOf(profile())
        coEvery { setup.gateway.getCapabilities(any()) } throws CancellationException("caller canceled")
        val entry = setup.source.plan(AppAuditOptions(true, listOf("selected"))).single { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
        val failure = runCatching { setup.source.execute(entry, context()) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("caller canceled", failure?.message)
        coVerify(exactly = 1) { setup.gateway.getCapabilities(any()) }
    }

    private class Setup(val gateway: GatewayAPI = mockk()) {
        val settings = mockk<SettingRepository>()
        val connections = mockk<ToolConnectionRepository>()
        val native = mockk<NativeMarketplaceRegistry>()
        val packages = mockk<MarketplacePackageStore>()
        val resolver = mockk<AgentToolResolver>()
        val mcp = mockk<McpClientManager>()
        val services = AppAuditPluginServices(settings, connections, native, packages, resolver, mcp, gateway)
        val source = AppAuditPluginProbeSource(services::inventory, services::target)

        init {
            coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
            coEvery { native.load() } returns emptyMap()
            coEvery { packages.downloadedIds() } returns emptySet()
            coEvery { connections.listConnections() } returns emptyList()
            every { mcp.healthSnapshot(any()) } returns McpConnectionHealth()
        }

        fun configure(values: List<ToolConnection>) {
            coEvery { connections.listConnections() } returns values
        }
    }

    private fun connection(uid: String = "configured") = ToolConnection(uid, "PRIVATE_LABEL", "manual", ToolConnectionType.MCP, "https://private.example/mcp?key=PRIVATE_QUERY", ToolConnectionAuthType.NONE, null, null)

    private fun profile() = PlatformV2(uid = "selected", name = "PRIVATE_PROFILE", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8097/private/v1", token = "private-credential", enabled = true)

    private fun context(marker: suspend () -> Unit = {}) = AppAuditProbeContext(AppAuditLimits(), marker)
}
