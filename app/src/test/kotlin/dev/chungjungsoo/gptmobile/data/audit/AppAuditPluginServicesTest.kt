package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.agent.tool.McpClientManager
import dev.chungjungsoo.gptmobile.data.agent.tool.McpConnectionHealth
import dev.chungjungsoo.gptmobile.data.agent.tool.McpConnectionHealthState
import dev.chungjungsoo.gptmobile.data.agent.tool.McpDiscoveryOAuthRefreshException
import dev.chungjungsoo.gptmobile.data.agent.tool.McpLocalValidationException
import dev.chungjungsoo.gptmobile.data.agent.tool.McpLocalValidationRule
import dev.chungjungsoo.gptmobile.data.agent.tool.McpProtocolConfigurationException
import dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.catalog.MarketplaceRuntime
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.marketplace.MarketplacePackageStore
import dev.chungjungsoo.gptmobile.data.marketplace.NativeEndpointConfig
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceRegistry
import dev.chungjungsoo.gptmobile.data.marketplace.NativePluginInstallation
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import dev.chungjungsoo.gptmobile.data.network.ConnectionHealth
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPI
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAPIImpl
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilityDiscoveryException
import dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.EndpointSecrets
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import dev.chungjungsoo.gptmobile.data.security.SecretVaultException
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditPluginServicesTest {
    @Test
    fun unchangedFrozenPlanSurvivesNewServiceAndProcessLocalGeneration() = runBlocking {
        val setup = Setup()
        val entry = setup.source.plan(AppAuditOptions()).single { it.componentId == "calculator" && it.probeId == AppAuditPluginProbeSource.CONFIGURATION }
        ReadinessInvalidation.advance()
        val restarted = Setup()
        val result = restarted.source.execute(entry, context())
        assertEquals(AppAuditOutcome.PASS, result.outcome)
        assertEquals("PLUGIN_CONFIGURATION_ONLY", result.reasonCode)
        assertFalse(result.configChanged)
    }

    @Test
    fun stableCredentialRecordsAdmitFrozenMcpAndGatewayPlansAfterRecreation() = runBlocking {
        val connection = connection().copy(authType = ToolConnectionAuthType.BEARER, secretRef = "connection_configured")
        val setup = Setup()
        setup.configure(listOf(connection))
        coEvery { setup.settings.fetchPlatformV2s() } returns listOf(profile())
        val entries = setup.source.plan(AppAuditOptions(true, listOf("selected"))).filter { it.safetyClass == AppAuditSafetyClass.NETWORK_READ }
        ReadinessInvalidation.advance()
        val restarted = Setup()
        restarted.configure(listOf(connection))
        coEvery { restarted.settings.fetchPlatformV2s() } returns listOf(profile())
        coEvery { restarted.resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true) } returns emptyList()
        coEvery { restarted.gateway.getCapabilities(any(), forceRefresh = true) } returns null
        var markers = 0
        val results = entries.map { restarted.source.execute(it, context { markers++ }) }
        assertEquals(2, results.size)
        assertEquals(2, markers)
        assertEquals(setOf("MCP_EMPTY_CATALOG", "GATEWAY_NOT_SUPPORTED"), results.map { it.reasonCode }.toSet())
        assertTrue(results.all { it.dispatchEvidence == AppAuditDispatchEvidence.CONFIRMED && !it.configChanged })
        coVerify(exactly = 0) { restarted.vault.read(any()) }
        coVerify(exactly = 0) { restarted.vault.put(any(), any()) }
    }

    @Test
    fun blankGatewayTokensWithoutReferencesUseActualUnauthenticatedCapabilityRoute() = runBlocking {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("gateway-v15-capabilities-full.json")).bufferedReader().use { it.readText() }
        for (token in listOf(null, "", " \t\r\n")) {
            var calls = 0
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals("GET", request.method.value)
                    assertEquals("/private/v1/gateway/capabilities", request.url.encodedPath)
                    assertEquals(null, request.headers["Authorization"])
                    respond(fixture, HttpStatusCode.OK)
                }
            ).use { client ->
                val network = mockk<NetworkClient>()
                every { network() } returns client
                every { network.gatewayReadClient() } returns client
                val setup = Setup(GatewayAPIImpl(network))
                coEvery { setup.settings.fetchPlatformV2s() } returns listOf(profile().copy(token = token, secretRef = null))
                val entry = setup.source.plan(AppAuditOptions(true, listOf("selected"))).single { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
                assertEquals(AppAuditPrerequisite.READY, entry.prerequisite)
                val result = setup.source.execute(entry, context())
                assertEquals("GATEWAY_CAPABILITY_VERIFIED", result.reasonCode)
                assertEquals(AppAuditDispatchEvidence.CONFIRMED, result.dispatchEvidence)
                assertEquals(1, calls)
                confirmVerified(setup.vault)
            }
        }
    }

    @Test
    fun mcpNoneIgnoresRetainedAuthReferenceButStillBindsProtectedEndpoint() = runBlocking {
        for (protectedEndpoint in listOf(false, true)) {
            val setup = Setup()
            val connection = connection().copy(
                secretRef = "unused_auth_ref",
                endpointUrl = if (protectedEndpoint) EndpointSecrets.masked("https://private.example/mcp?key=PRIVATE_QUERY") else connection().endpointUrl
            )
            coEvery { setup.vault.recordRevision("unused_auth_ref") } returns null
            coEvery { setup.vault.recordRevision("other_unused_auth_ref") } returns null
            setup.configure(listOf(connection))
            val entry = setup.source.plan(AppAuditOptions(true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
            assertEquals(AppAuditPrerequisite.READY, entry.prerequisite)
            val current = connection.copy(secretRef = "other_unused_auth_ref")
            setup.configure(listOf(current))
            coEvery { setup.resolver.discoverMcpTools(current, forceRefresh = true, awaitCompletion = true) } returns emptyList()
            assertEquals("MCP_EMPTY_CATALOG", setup.source.execute(entry, context()).reasonCode)
            coVerify(exactly = 0) { setup.vault.recordRevision("unused_auth_ref") }
            coVerify(exactly = 0) { setup.vault.recordRevision("other_unused_auth_ref") }
            if (protectedEndpoint) {
                coEvery { setup.vault.recordRevision(EndpointSecrets.reference(current.connectionUid)) } returns "b".repeat(64)
                var markers = 0
                val changed = setup.source.execute(entry, context { markers++ })
                assertEquals("PLUGIN_CONFIGURATION_CHANGED", changed.reasonCode)
                assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, changed.dispatchEvidence)
                assertEquals(0, markers)
            }
            coVerify(exactly = 1) { setup.resolver.discoverMcpTools(current, forceRefresh = true, awaitCompletion = true) }
        }
    }

    @Test
    fun sameReferenceCredentialOrProtectedEndpointReplacementBlocksFrozenMcpBeforeMarker() = runBlocking {
        for (authType in listOf(ToolConnectionAuthType.BEARER, ToolConnectionAuthType.OAUTH, ToolConnectionAuthType.NONE)) {
            val setup = Setup()
            val reference = if (authType == ToolConnectionAuthType.NONE) EndpointSecrets.reference("configured") else "connection_configured"
            val connection = connection().copy(
                authType = authType,
                secretRef = reference.takeUnless { authType == ToolConnectionAuthType.NONE },
                endpointUrl = if (authType == ToolConnectionAuthType.NONE) EndpointSecrets.masked("https://private.example/mcp?key=PRIVATE_QUERY") else connection().endpointUrl
            )
            setup.configure(listOf(connection))
            val entry = setup.source.plan(AppAuditOptions(true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
            // Neither the reference, saved descriptor nor process counter changes.
            coEvery { setup.vault.recordRevision(reference) } returns "b".repeat(64)
            var markers = 0
            val result = setup.source.execute(entry, context { markers++ })
            assertEquals("PLUGIN_CONFIGURATION_CHANGED", result.reasonCode)
            assertTrue(result.configChanged)
            assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, result.dispatchEvidence)
            assertEquals(0, markers)
            confirmVerified(setup.resolver)
        }
    }

    @Test
    fun providerOwnedGatewayCredentialReplacementBlocksEvenWhenProfileDescriptorIsUnchanged() = runBlocking {
        for (providerOwned in listOf(false, true)) {
            val setup = Setup()
            val profile = profile().copy(providerConnectionUid = "provider".takeIf { providerOwned })
            coEvery { setup.settings.fetchPlatformV2s() } returns listOf(profile)
            if (providerOwned) {
                coEvery { setup.settings.getProviderConnection("provider") } returns ProviderConnection(uid = "provider", name = "PRIVATE_PROVIDER", compatibleType = ClientType.LLAMA, apiUrl = profile.apiUrl, secretRef = profile.secretRef)
            }
            val entry = setup.source.plan(AppAuditOptions(true, listOf(profile.uid))).single { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
            coEvery { setup.vault.recordRevision(requireNotNull(profile.secretRef)) } returns "b".repeat(64)
            var markers = 0
            val result = setup.source.execute(entry, context { markers++ })
            assertEquals(AppAuditOutcome.BLOCKED, result.outcome)
            assertEquals("PLUGIN_CONFIGURATION_CHANGED", result.reasonCode)
            assertEquals(0, markers)
            confirmVerified(setup.gateway)
        }
    }

    @Test
    fun nativeCredentialAndEndpointRecordsAreBoundWithoutReadingPlaintext() = runBlocking {
        val entry = GitHubMarketplaceCatalog.packages.first { it.runtime == MarketplaceRuntime.NATIVE && NativeMarketplaceCatalog.requiresKey(it) }
        for (reference in listOf("native_credential", "native_endpoint")) {
            val setup = Setup()
            val installation = NativePluginInstallation(enabled = true, endpoint = "https://private.example/v1", credentialRef = "native_credential", endpointConfigs = mapOf("configured" to NativeEndpointConfig("https://private.example/mcp", secretRef = "native_endpoint")))
            coEvery { setup.native.load() } returns mapOf(entry.id to installation)
            coEvery { setup.packages.downloadedIds() } returns setOf(entry.id)
            coEvery { setup.packages.exportBytes(entry) } returns byteArrayOf(1)
            val planned = setup.source.plan(AppAuditOptions()).single { it.componentId == "marketplace:${entry.id}" && it.probeId == AppAuditPluginProbeSource.CONFIGURATION }
            assertEquals(AppAuditPrerequisite.READY, planned.prerequisite)
            coEvery { setup.vault.recordRevision(reference) } returns "b".repeat(64)
            val result = setup.source.execute(planned, context())
            assertEquals("PLUGIN_CONFIGURATION_CHANGED", result.reasonCode)
            coVerify(exactly = 0) { setup.vault.read(any()) }
        }
    }

    @Test
    fun missingUnsupportedOrInvalidCredentialIdentityIsExplicitAndCannotDispatch() = runBlocking {
        for (failureKind in listOf("missing", "unsupported", "invalid", "unavailable")) {
            val setup = Setup()
            setup.configure(listOf(connection().copy(authType = ToolConnectionAuthType.BEARER, secretRef = "connection_configured")))
            when (failureKind) {
                "invalid" -> coEvery { setup.vault.recordRevision(any()) } returns "PRIVATE_UNTRUSTED_IDENTITY"
                "unavailable" -> coEvery { setup.vault.recordRevision(any()) } throws SecretVaultException("PRIVATE_VAULT_DETAIL")
                else -> coEvery { setup.vault.recordRevision(any()) } returns null
            }
            val entry = setup.source.plan(AppAuditOptions(true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
            assertEquals("PLUGIN_CREDENTIAL_IDENTITY_UNAVAILABLE", entry.reasonCode)
            var markers = 0
            val result = setup.source.execute(entry, context { markers++ })
            assertEquals(AppAuditOutcome.UNAVAILABLE, result.outcome)
            assertEquals("PLUGIN_CREDENTIAL_IDENTITY_UNAVAILABLE", result.reasonCode)
            assertEquals(0, markers)
            assertFalse(result.toString().contains("PRIVATE"))
            confirmVerified(setup.resolver)
        }
    }

    @Test
    fun untrackedPlaintextOrStaleProviderOwnerCannotAdmitGatewayRead() = runBlocking {
        for (scenario in listOf("plaintext", "missing-owner", "changed-owner")) {
            val setup = Setup()
            val profile = if (scenario == "plaintext") profile().copy(secretRef = null) else profile().copy(providerConnectionUid = "provider")
            coEvery { setup.settings.fetchPlatformV2s() } returns listOf(profile)
            coEvery { setup.settings.getProviderConnection("provider") } returns if (scenario == "changed-owner") ProviderConnection(uid = "provider", name = "PRIVATE", compatibleType = ClientType.LLAMA, apiUrl = profile.apiUrl, secretRef = "replacement") else null
            val entry = setup.source.plan(AppAuditOptions(true, listOf(profile.uid))).single { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
            assertEquals("PLUGIN_CREDENTIAL_IDENTITY_UNAVAILABLE", entry.reasonCode)
            var markers = 0
            assertEquals(AppAuditOutcome.UNAVAILABLE, setup.source.execute(entry, context { markers++ }).outcome)
            assertEquals(0, markers)
            confirmVerified(setup.gateway)
        }
    }

    @Test
    fun transientGenerationChangeDuringMarkerStillBlocksAnOtherwiseEqualFingerprint() = runBlocking {
        val setup = Setup()
        setup.configure(listOf(connection()))
        val entry = setup.source.plan(AppAuditOptions(true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
        val result = setup.source.execute(entry, context { ReadinessInvalidation.advance() })
        assertEquals(AppAuditOutcome.BLOCKED, result.outcome)
        assertEquals("PLUGIN_CONFIGURATION_CHANGED", result.reasonCode)
        assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, result.dispatchEvidence)
        confirmVerified(setup.resolver)
    }

    @Test
    fun snapshotMutationCannotCreateOrAdmitAPlanFromMixedConfiguration() = runBlocking {
        val setup = Setup()
        val connection = connection()
        setup.configure(listOf(connection))
        val entry = setup.source.plan(AppAuditOptions(true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
        coEvery { setup.connections.listConnections() } coAnswers {
            ReadinessInvalidation.advance()
            listOf(connection)
        }
        assertTrue(runCatching { setup.source.plan(AppAuditOptions(true)) }.exceptionOrNull() is IllegalStateException)
        var markers = 0
        val result = setup.source.execute(entry, context { markers++ })
        assertEquals("PLUGIN_CONFIGURATION_CHANGED", result.reasonCode)
        assertEquals(0, markers)
        confirmVerified(setup.resolver)
    }

    @Test
    fun inFlightGenerationChangePreservesObservedMetadataAndUnknownTlsReceipts() = runBlocking {
        for (tlsFailure in listOf(false, true)) {
            val setup = Setup()
            coEvery { setup.settings.fetchPlatformV2s() } returns listOf(profile())
            val entry = setup.source.plan(AppAuditOptions(true, listOf("selected"))).single { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
            coEvery { setup.gateway.getCapabilities(any(), forceRefresh = true) } coAnswers {
                ReadinessInvalidation.advance()
                if (tlsFailure) throw SSLHandshakeException("PRIVATE_CERTIFICATE")
                null
            }
            val result = setup.source.execute(entry, context())
            assertEquals(if (tlsFailure) "TLS_FAILURE" else "GATEWAY_NOT_SUPPORTED", result.reasonCode)
            assertEquals(if (tlsFailure) AppAuditOutcome.FAIL else AppAuditOutcome.UNAVAILABLE, result.outcome)
            assertEquals(if (tlsFailure) AppAuditDispatchEvidence.UNKNOWN else AppAuditDispatchEvidence.CONFIRMED, result.dispatchEvidence)
            assertEquals(AppAuditEvidenceKind.LIVE_METADATA, result.evidenceKind)
            assertEquals(AppAuditObservationSource.LIVE, result.observationSource)
            assertTrue(result.configChanged)
            coVerify(exactly = 1) { setup.gateway.getCapabilities(any(), forceRefresh = true) }
        }
    }

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
    fun authenticationGatePreservesCodeWithoutDiscoveryRepairOrNetworkBudget() = runBlocking {
        val setup = Setup()
        setup.configure(listOf(connection()))
        every { setup.mcp.healthSnapshot(any()) } returns McpConnectionHealth(
            state = McpConnectionHealthState.AUTHENTICATION_REQUIRED,
            lastFailureAtMs = 1,
            nextRetryAtMs = Long.MAX_VALUE,
            lastErrorCode = "MCP_AUTHENTICATION_REQUIRED",
            lastError = "PRIVATE_SERVER_VALUE",
            connectionHealth = ConnectionHealth.AUTHENTICATION_FAILED
        )
        var markers = 0
        val entry = setup.source.plan(AppAuditOptions(allowNetworkReads = true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
        val result = setup.source.execute(entry, context { markers++ })
        assertEquals(AppAuditOutcome.BLOCKED, result.outcome)
        assertEquals("MCP_AUTHENTICATION_REQUIRED", result.reasonCode)
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
        coEvery { setup.resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true) } throws McpProtocolConfigurationException("MCP_DISCOVERY_SCHEMA_INVALID", "PRIVATE_SERVER_VALUE", 200, "PROTOCOL_VERSIONS_ARRAY_REQUIRED", "supportedVersions")
        val entry = setup.source.plan(AppAuditOptions(allowNetworkReads = true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
        val result = setup.source.execute(entry, context())
        assertEquals(AppAuditOutcome.FAIL, result.outcome)
        assertEquals("MCP_DISCOVERY_SCHEMA_INVALID", result.reasonCode)
        assertEquals(false, result.retryable)
        assertEquals(200, result.httpStatus)
        assertEquals("protocol_negotiation", result.failurePhase)
        assertEquals("PROTOCOL_CONFIGURATION", result.failureCategory)
        assertEquals("PROTOCOL_VERSIONS_ARRAY_REQUIRED", result.validationRule)
        assertEquals("supportedVersions", result.validationField)
        assertEquals(AppAuditDispatchEvidence.CONFIRMED, result.dispatchEvidence)
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
                every { network.gatewayReadClient() } returns client
                val setup = Setup(GatewayAPIImpl(network))
                val profile = profile()
                coEvery { setup.settings.fetchPlatformV2s() } returns listOf(profile, profile.copy(uid = "other", apiUrl = "http://127.0.0.1:9999/v1"))
                val entry = setup.source.plan(AppAuditOptions(true, listOf(profile.uid))).single { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
                val before = System.currentTimeMillis()
                val result = setup.source.execute(entry, context())
                assertEquals(AppAuditOutcome.PASS, result.outcome)
                assertEquals("GATEWAY_CAPABILITY_VERIFIED", result.reasonCode)
                assertEquals(AppAuditDispatchEvidence.CONFIRMED, result.dispatchEvidence)
                assertEquals(AppAuditEvidenceKind.LIVE_METADATA, result.evidenceKind)
                assertEquals(entry.configurationRevision, result.configurationRevision)
                assertTrue(requireNotNull(result.observedAtEpochMs) >= before)
                assertEquals(AppAuditRemoteIdentitySource.DECLARED_CAPABILITIES, result.remoteIdentity?.source)
                assertEquals("15.0.0", result.remoteIdentity?.version)
                assertEquals("0".repeat(40), result.remoteIdentity?.sourceCommit)
                assertEquals(1, calls)
                assertFalse(result.toString().contains("private-credential"))
            }
        }
    }

    @Test
    fun nonGatewayModelSelectionsDoNotCreateGatewayChecksAndOldGatewayPlansCannotChooseAnotherProfile() = runBlocking {
        val setup = Setup()
        coEvery { setup.settings.fetchPlatformV2s() } returns listOf(profile())
        val frozen = setup.source.plan(AppAuditOptions(true, listOf("selected"))).single { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
        coEvery { setup.settings.fetchPlatformV2s() } returns listOf(profile().copy(uid = "other"), profile().copy(uid = "cloud", apiUrl = "https://cloud.example/v1"))
        val entries = setup.source.plan(AppAuditOptions(true, listOf("missing", "cloud"))).filter { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
        assertTrue(entries.isEmpty())
        assertEquals("PLUGIN_CONFIGURATION_CHANGED", setup.source.execute(frozen, context()).reasonCode)
        confirmVerified(setup.gateway)
    }

    @Test
    fun explicitGatewayAuditRefreshesUnsupportedCacheAndRetainsActualDispatchEvidence() = runBlocking {
        for (warmStatus in listOf(HttpStatusCode.NotFound, HttpStatusCode.NotImplemented)) {
            for (recovers in listOf(false, true)) {
                val profile = profile().copy(apiUrl = "http://127.0.0.1:${29_000 + warmStatus.value + if (recovers) 1_000 else 0}/private/v1")
                val config = ProviderRequestConfig(profile.apiUrl, profile.token, profileUid = profile.uid)
                var calls = 0
                var marked = false
                HttpClient(
                    MockEngine { request ->
                        calls++
                        assertEquals("GET", request.method.value)
                        assertEquals("/private/v1/gateway/capabilities", request.url.encodedPath)
                        assertEquals("127.0.0.1", request.url.host)
                        assertEquals(java.net.URI(profile.apiUrl).port, request.url.port)
                        assertEquals("Bearer private-credential", request.headers["Authorization"])
                        if (calls > 1) assertTrue("The audit marker must precede its fresh network read", marked)
                        if (calls > 1 && recovers) {
                            respond("""{"gatewayVersion":"15.0.0","contractVersion":1}""", HttpStatusCode.OK)
                        } else {
                            respond("not supported", warmStatus)
                        }
                    }
                ).use { client ->
                    val network = mockk<NetworkClient>()
                    every { network() } returns client
                    every { network.gatewayReadClient() } returns client
                    val gateway = GatewayAPIImpl(network)
                    repeat(2) { assertNull(gateway.getCapabilities(config)) }
                    assertEquals("Ordinary calls reuse the unsupported route observation", 1, calls)

                    val setup = Setup(gateway)
                    coEvery { setup.settings.fetchPlatformV2s() } returns listOf(profile)
                    val entry = setup.source.plan(AppAuditOptions(true, listOf(profile.uid))).single { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
                    val result = setup.source.execute(entry, context { marked = true })
                    assertEquals("A live audit must perform a new capability read", 2, calls)
                    assertEquals(if (recovers) AppAuditOutcome.PASS else AppAuditOutcome.UNAVAILABLE, result.outcome)
                    assertEquals(if (recovers) "GATEWAY_CAPABILITY_VERIFIED" else "GATEWAY_NOT_SUPPORTED", result.reasonCode)
                    assertEquals(AppAuditObservationSource.LIVE, result.observationSource)
                    assertEquals(AppAuditEvidenceKind.LIVE_METADATA, result.evidenceKind)
                    assertEquals(AppAuditDispatchEvidence.CONFIRMED, result.dispatchEvidence)
                    assertFalse(result.toString().contains("private-credential"))

                    val following = gateway.getCapabilities(config)
                    if (recovers) {
                        assertEquals("15.0.0", following?.gatewayVersion)
                        assertEquals("A successful refresh clears the unsupported observation", 3, calls)
                    } else {
                        assertNull(following)
                        assertEquals("An unsupported refresh remains cached for ordinary calls", 2, calls)
                    }
                }
            }
        }
    }

    @Test
    fun explicitGatewayAuditCannotReuseUnsupportedStatusAfterFreshAuthenticationFailure() = runBlocking {
        val profile = profile().copy(apiUrl = "http://127.0.0.1:31545/private/v1")
        val config = ProviderRequestConfig(profile.apiUrl, profile.token, profileUid = profile.uid)
        var calls = 0
        HttpClient(
            MockEngine { request ->
                calls++
                assertEquals("Bearer private-credential", request.headers["Authorization"])
                respond("private response", if (calls == 1) HttpStatusCode.NotFound else HttpStatusCode.Forbidden)
            }
        ).use { client ->
            val network = mockk<NetworkClient>()
            every { network() } returns client
            every { network.gatewayReadClient() } returns client
            val gateway = GatewayAPIImpl(network)
            assertNull(gateway.getCapabilities(config))
            val setup = Setup(gateway)
            coEvery { setup.settings.fetchPlatformV2s() } returns listOf(profile)
            val entry = setup.source.plan(AppAuditOptions(true, listOf(profile.uid))).single { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
            val result = setup.source.execute(entry, context())
            assertEquals(AppAuditOutcome.FAIL, result.outcome)
            assertEquals(AppAuditDispatchEvidence.CONFIRMED, result.dispatchEvidence)
            assertEquals(403, result.httpStatus)
            assertEquals(entry.configurationRevision, result.configurationRevision)
            assertTrue(requireNotNull(result.observedAtEpochMs) > 0)
            assertNull(result.remoteIdentity)
            assertEquals(2, calls)
            assertTrue(runCatching { gateway.getCapabilities(config) }.exceptionOrNull() is GatewayCapabilityDiscoveryException)
            assertEquals("A fresh authentication failure cannot restore a previous unsupported-route shortcut", 3, calls)
            assertFalse(result.toString().contains("private response"))
        }
    }

    @Test
    fun gatewaySchemaAndTlsFailuresKeepTypedReasonsWithoutPrivateMessages() = runBlocking {
        val setup = Setup()
        coEvery { setup.settings.fetchPlatformV2s() } returns listOf(profile())
        val entry = setup.source.plan(AppAuditOptions(true, listOf("selected"))).single { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
        coEvery { setup.gateway.getCapabilities(any(), forceRefresh = true) } throws GatewayCapabilityDiscoveryException("PRIVATE_DECODER_VALUE", decodeReason = "EXPECTED_INTEGER", fieldPath = "PRIVATE_KEY")
        val decode = setup.source.execute(entry, context())
        assertEquals("GATEWAY_CAPABILITY_DECODE_EXPECTED_INTEGER", decode.reasonCode)
        assertEquals(AppAuditDispatchEvidence.CONFIRMED, decode.dispatchEvidence)
        assertFalse(decode.toString().contains("PRIVATE"))
        coEvery { setup.gateway.getCapabilities(any(), forceRefresh = true) } throws SSLHandshakeException("PRIVATE_CERTIFICATE_VALUE")
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
        coEvery { setup.gateway.getCapabilities(any(), forceRefresh = true) } throws CancellationException("caller canceled")
        val entry = setup.source.plan(AppAuditOptions(true, listOf("selected"))).single { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
        val failure = runCatching { setup.source.execute(entry, context()) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("caller canceled", failure?.message)
        coVerify(exactly = 1) { setup.gateway.getCapabilities(any(), forceRefresh = true) }
    }

    @Test
    fun explicitNetworkAuditRechecksCachedSchemaAndDiscovery404WithoutWeakeningValidation() = runBlocking {
        for ((code, status, rule, field) in listOf(
            listOf("MCP_DISCOVERY_SCHEMA_INVALID", "200", "PROTOCOL_VERSIONS_ARRAY_REQUIRED", "supportedVersions"),
            listOf("MCP_DISCOVERY_CONFIGURATION", "404", "DISCOVERY_REQUEST_ACCEPTANCE_REQUIRED", "server_discover")
        )) {
            val setup = Setup()
            val connection = connection()
            setup.configure(listOf(connection))
            val entry = setup.source.plan(AppAuditOptions(true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
            var health = McpConnectionHealth(
                state = McpConnectionHealthState.UNREACHABLE, lastFailureAtMs = 7, nextRetryAtMs = Long.MAX_VALUE,
                lastErrorCode = code, lastError = "PRIVATE_SERVER_VALUE",
                failedStage = "protocol_negotiation", failureConfigurationRevision = entry.configurationRevision,
                lastHttpStatus = status.toInt(), lastValidationRule = rule, lastValidationField = field, lastDispatchEvidence = "CONFIRMED"
            )
            every { setup.mcp.healthSnapshot(connection.connectionUid) } answers { health }
            var markers = 0
            coEvery { setup.resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true) } coAnswers {
                assertEquals(1, markers)
                health = health.copy(lastFailureAtMs = System.currentTimeMillis())
                throw McpProtocolConfigurationException(code, "PRIVATE_SERVER_VALUE", status.toInt(), rule, field)
            }
            val before = System.currentTimeMillis()
            val observation = setup.source.execute(entry, context { markers++ })
            assertEquals(AppAuditOutcome.FAIL, observation.outcome)
            assertEquals(code, observation.reasonCode)
            assertEquals(AppAuditObservationSource.LIVE, observation.observationSource)
            assertEquals(AppAuditDispatchEvidence.CONFIRMED, observation.dispatchEvidence)
            assertTrue(requireNotNull(observation.observedAtEpochMs) >= before)
            assertEquals(entry.configurationRevision, observation.configurationRevision)
            assertEquals("protocol_negotiation", observation.failurePhase)
            assertEquals("PROTOCOL_CONFIGURATION", observation.failureCategory)
            assertEquals(rule, observation.validationRule)
            assertEquals(field, observation.validationField)
            assertEquals(status.toInt(), observation.httpStatus)
            assertEquals(false, observation.retryable)
            assertFalse(observation.toString().contains("PRIVATE_SERVER_VALUE"))
            assertEquals(1, markers)
            coVerify(exactly = 1) { setup.resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true) }
            confirmVerified(setup.resolver)
        }
    }

    @Test
    fun authenticationAppearingAtAdmissionCannotBeBypassedByContractRecheck() = runBlocking {
        val setup = Setup()
        val connection = connection()
        setup.configure(listOf(connection))
        val entry = setup.source.plan(AppAuditOptions(true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
        var health = McpConnectionHealth(
            state = McpConnectionHealthState.UNREACHABLE,
            lastFailureAtMs = 7,
            nextRetryAtMs = Long.MAX_VALUE,
            lastErrorCode = "MCP_DISCOVERY_CONFIGURATION",
            failureConfigurationRevision = entry.configurationRevision
        )
        every { setup.mcp.healthSnapshot(connection.connectionUid) } answers { health }
        val observation = setup.source.execute(
            entry,
            context {
                health = health.copy(state = McpConnectionHealthState.AUTHENTICATION_REQUIRED, lastErrorCode = "MCP_AUTHENTICATION_REQUIRED", lastHttpStatus = 401)
            }
        )
        assertEquals(AppAuditOutcome.BLOCKED, observation.outcome)
        assertEquals("MCP_AUTHENTICATION_REQUIRED", observation.reasonCode)
        assertEquals(AppAuditObservationSource.CACHE, observation.observationSource)
        assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, observation.dispatchEvidence)
        assertEquals(7L, observation.observedAtEpochMs)
        confirmVerified(setup.resolver)
    }

    @Test
    fun repairedContractCanRecoverThroughOneExplicitFreshAuditRead() = runBlocking {
        val setup = Setup()
        val connection = connection()
        setup.configure(listOf(connection))
        every { setup.mcp.healthSnapshot(connection.connectionUid) } returns McpConnectionHealth(
            state = McpConnectionHealthState.UNREACHABLE,
            lastFailureAtMs = 7,
            nextRetryAtMs = Long.MAX_VALUE,
            lastErrorCode = "MCP_PROTOCOL_VERSION_UNSUPPORTED"
        )
        coEvery { setup.resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true) } returns emptyList()
        val entry = setup.source.plan(AppAuditOptions(true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
        val observation = setup.source.execute(entry, context())
        assertEquals(AppAuditOutcome.PASS, observation.outcome)
        assertEquals(AppAuditObservationSource.LIVE, observation.observationSource)
        assertEquals(AppAuditDispatchEvidence.CONFIRMED, observation.dispatchEvidence)
        coVerify(exactly = 1) { setup.resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true) }
    }

    @Test
    fun cachedFailureForAnotherConfigurationCannotBlockAnExplicitNewPlan() = runBlocking {
        val setup = Setup()
        val connection = connection()
        setup.configure(listOf(connection))
        every { setup.mcp.healthSnapshot(connection.connectionUid) } returns McpConnectionHealth(
            state = McpConnectionHealthState.UNREACHABLE,
            lastFailureAtMs = 1,
            nextRetryAtMs = Long.MAX_VALUE,
            lastErrorCode = "MCP_DISCOVERY_SCHEMA_INVALID",
            failureConfigurationRevision = "0".repeat(64)
        )
        coEvery { setup.resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true) } returns emptyList()
        val entry = setup.source.plan(AppAuditOptions(true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
        var markers = 0
        val observation = setup.source.execute(entry, context { markers++ })
        assertEquals(1, markers)
        assertEquals(AppAuditOutcome.PASS, observation.outcome)
        assertEquals(AppAuditObservationSource.LIVE, observation.observationSource)
        assertEquals(AppAuditDispatchEvidence.CONFIRMED, observation.dispatchEvidence)
        assertEquals(entry.configurationRevision, observation.configurationRevision)
        coVerify(exactly = 1) { setup.resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true) }
    }

    @Test
    fun localValidationAfterAdmissionDoesNotInheritAnOlderHttpResponseOrClaimRemoteDispatch() = runBlocking {
        val setup = Setup()
        val connection = connection()
        setup.configure(listOf(connection))
        every { setup.mcp.healthSnapshot(connection.connectionUid) } returns McpConnectionHealth(
            state = McpConnectionHealthState.DEGRADED,
            lastFailureAtMs = 1,
            lastErrorCode = "MCP_LOCAL_CREDENTIAL_INVALID",
            lastHttpStatus = 403,
            failedStage = "execution",
            failureConfigurationRevision = "0".repeat(64),
            lastDispatchEvidence = "CONFIRMED"
        )
        coEvery { setup.resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true) } throws McpLocalValidationException(McpLocalValidationRule.CREDENTIAL_FORMAT_INVALID)
        val entry = setup.source.plan(AppAuditOptions(true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
        val before = System.currentTimeMillis()
        var markers = 0
        val observation = setup.source.execute(entry, context { markers++ })
        assertEquals(1, markers)
        assertEquals(AppAuditOutcome.FAIL, observation.outcome)
        assertEquals("MCP_LOCAL_CREDENTIAL_INVALID", observation.reasonCode)
        assertEquals(AppAuditObservationSource.NONE, observation.observationSource)
        assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, observation.dispatchEvidence)
        assertEquals("local_validation", observation.failurePhase)
        assertEquals("LOCAL_VALIDATION", observation.failureCategory)
        assertEquals("CREDENTIAL_FORMAT_INVALID", observation.validationRule)
        assertEquals("credential", observation.validationField)
        assertEquals(false, observation.retryable)
        assertNull(observation.httpStatus)
        assertTrue(requireNotNull(observation.observedAtEpochMs) >= before)
        assertEquals(entry.configurationRevision, observation.configurationRevision)
    }

    @Test
    fun localCredentialFailureDuringOAuthRefreshKeepsTheFirstConfirmedAuthenticationResponse() = runBlocking {
        val setup = Setup()
        val connection = connection()
        setup.configure(listOf(connection))
        coEvery { setup.resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true) } throws McpDiscoveryOAuthRefreshException(McpLocalValidationRule.CREDENTIAL_UNAVAILABLE)
        val entry = setup.source.plan(AppAuditOptions(true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
        val observation = setup.source.execute(entry, context())
        assertEquals("MCP_AUTHENTICATION_REQUIRED", observation.reasonCode)
        assertEquals(AppAuditObservationSource.LIVE, observation.observationSource)
        assertEquals(AppAuditDispatchEvidence.CONFIRMED, observation.dispatchEvidence)
        assertEquals(401, observation.httpStatus)
        assertEquals("oauth_refresh", observation.failurePhase)
        assertEquals("AUTHENTICATION_REQUIRED", observation.failureCategory)
        assertEquals("CREDENTIAL_UNAVAILABLE", observation.validationRule)
        assertEquals(false, observation.retryable)
        assertNull(observation.configurationRevision)
    }

    @Test
    fun credentialRotationDuringDiscoveryCannotAttributeTheResultToTheOriginalConfiguration() = runBlocking {
        val setup = Setup()
        val connection = connection().copy(authType = ToolConnectionAuthType.BEARER, secretRef = "connection_configured")
        var revision = "a".repeat(64)
        setup.configure(listOf(connection))
        coEvery { setup.vault.recordRevision("connection_configured") } answers { revision }
        coEvery { setup.resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true) } coAnswers {
            revision = "b".repeat(64)
            emptyList()
        }
        val entry = setup.source.plan(AppAuditOptions(true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
        val observation = setup.source.execute(entry, context())
        assertEquals(AppAuditOutcome.PASS, observation.outcome)
        assertTrue(observation.configChanged)
        assertNull(observation.configurationRevision)
        val run = AppAuditRun("rotation", 1, "a".repeat(64), "b".repeat(40), "0.9.66.6", "debug", "c".repeat(64), 1, 1, listOf(entry), results = listOf(AppAuditResult(entry.id, observation, 1, 2, 1, true)))
        assertNull(appAuditScore(run).score)
    }

    private class Setup(val gateway: GatewayAPI = mockk()) {
        val settings = mockk<SettingRepository>()
        val connections = mockk<ToolConnectionRepository>()
        val native = mockk<NativeMarketplaceRegistry>()
        val packages = mockk<MarketplacePackageStore>()
        val resolver = mockk<AgentToolResolver>()
        val mcp = mockk<McpClientManager>()
        val vault = mockk<SecretVault>()
        val services = AppAuditPluginServices(settings, connections, native, packages, resolver, mcp, gateway, vault)
        val source = AppAuditPluginProbeSource(services::inventory, services::target)

        init {
            coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
            coEvery { native.load() } returns emptyMap()
            coEvery { packages.downloadedIds() } returns emptySet()
            coEvery { connections.listConnections() } returns emptyList()
            every { mcp.healthSnapshot(any()) } returns McpConnectionHealth()
            coEvery { vault.recordRevision(any()) } returns "a".repeat(64)
        }

        fun configure(values: List<ToolConnection>) {
            coEvery { connections.listConnections() } returns values
        }
    }

    private fun connection(uid: String = "configured") = ToolConnection(uid, "PRIVATE_LABEL", "manual", ToolConnectionType.MCP, "https://private.example/mcp", ToolConnectionAuthType.NONE, null, null)

    private fun profile() = PlatformV2(uid = "selected", name = "PRIVATE_PROFILE", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8097/private/v1", token = "private-credential", secretRef = "profile_selected", enabled = true)

    private fun context(marker: suspend () -> Unit = {}) = AppAuditProbeContext(AppAuditLimits(), marker)
}
