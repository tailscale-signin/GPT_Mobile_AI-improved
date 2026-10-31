package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonFetchResult
import dev.chungjungsoo.gptmobile.data.amazon.AmazonHtmlProvider
import dev.chungjungsoo.gptmobile.data.database.dao.AgentToolBindingWithConnection
import dev.chungjungsoo.gptmobile.data.database.dao.ToolConnectionDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentToolBinding
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ProviderConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.dto.Platform
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceClient
import dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceRegistry
import dev.chungjungsoo.gptmobile.data.marketplace.NativePluginInstallation
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.repository.SecretMigrationError
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.ktor.client.engine.cio.CIO
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolResolverTest {
    @Test fun githubOverrideEnablesSearchWithoutChangingOrdinaryProfileAndRevokesLive() = runBlocking {
        val profile = PlatformV2(uid = "github-profile", name = "GitHub", disableAllTools = true, disableRemoteTools = true)
        val workspace = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), profile.uid)
        val settings = ResolverFakeSettingRepository(listOf(profile))
        settings.features = settings.features.withToolPluginEnabled(ToolPluginId.WEB_SEARCH, false)
        val resolver = resolver(settings = settings)
        assertTrue(resolver.resolve(profile.uid).isEmpty())
        val selected = dev.chungjungsoo.gptmobile.data.github.GitHubChatPolicy.tools(ChatMcpToolConfig(github = workspace), settings.features, profile.uid)
        val search = resolver.resolve(profile.uid, selected).single { it.connectionUid == null && it.realToolName == "web_search" }
        assertTrue(requireNotNull(search.canReuseResult).invoke())
        settings.features = settings.features.copy(githubProfileOverrides = mapOf(profile.uid to dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride(webSearch = false)))
        assertFalse(requireNotNull(search.canReuseResult).invoke())
        assertTrue(resolver.resolve(profile.uid).isEmpty())
    }

    @Test fun githubOverrideSupportsColdMcpDiscoveryAndRevokesSavedReadAuthority() = runBlocking {
        McpClientManagerTest.McpFixtureServer().use { server ->
            val profile = PlatformV2(uid = "github-profile", name = "GitHub", disableAllTools = true, disableRemoteTools = true)
            val workspace = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), profile.uid)
            val settings = ResolverFakeSettingRepository(listOf(profile))
            val connection = connection("selected-mcp", ToolConnectionType.MCP, server.url, authType = ToolConnectionAuthType.NONE, allowCleartext = true).copy(approvedReadTools = "echo")
            val dao = ResolverFakeToolConnectionDao()
            dao.bind(connection, binding(profile.uid, connection.connectionUid, "echo"))
            val vault = ResolverFakeSecretVault()
            val repository = ToolConnectionRepository(dao, vault)
            val network = NetworkClient(CIO)
            val manager = McpClientManager(network())
            try {
                val resolver = AgentToolResolver(repository, settings, vault, network, manager, McpOAuthCoordinator(McpOAuthClient(network()), repository, vault, manager), DeviceLocationTool(mockk(relaxed = true), mockk(relaxed = true)))
                val selection = dev.chungjungsoo.gptmobile.data.github.GitHubChatPolicy.tools(ChatMcpToolConfig(enabledToolIds = setOf(connection.connectionUid), github = workspace), settings.features, profile.uid)
                val message = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(id = 42, chatId = 7, content = "Use echo", platformType = null)
                val available = resolver.resolve(profile.uid, selection, message, cachedCataloguesOnly = true)
                assertFalse(available.any { it.realToolName == "echo" })
                val directory = available.single { it.dynamicTools != null }
                // Populate the initially cold connection deterministically before on-demand acquisition.
                assertTrue(resolver.discoverMcpTools(connection, awaitCompletion = true).any { it.name == "echo" })
                assertFalse(directory.tool.execute("discover", buildJsonObject { put("query", "echo") }).isError)
                val discovered = requireNotNull(directory.dynamicTools).invoke().single { it.realToolName == "echo" }
                assertTrue(requireNotNull(discovered.canReuseResult).invoke())
                assertTrue(resolver.resolve(profile.uid).isEmpty())
                settings.features = settings.features.withToolPluginEnabled(ToolPluginId.connection(connection.connectionUid), false)
                assertFalse(requireNotNull(discovered.canReuseResult).invoke())
                assertTrue(resolver.resolve(profile.uid).isEmpty())
            } finally {
                manager.closeAll()
            }
        }
    }

    @Test fun personalToolGrantUsesRequestedModelAndRetainsTheRegisteredCredentialFence() = runBlocking {
        McpClientManagerTest.McpFixtureServer().use { server ->
            val registered = FreeAiProvider.OVHCLOUD.applyTo(PlatformV2(uid = "override-profile", name = "Free")).copy(model = "registered-model")
            val requested = registered.copy(model = "chat-model")
            val profiles = mutableListOf(registered)
            val dao = ResolverFakeToolConnectionDao()
            dao.bind(connection("private", ToolConnectionType.MCP, endpointUrl = server.url, authType = ToolConnectionAuthType.NONE, allowCleartext = true), binding(registered.uid, "private", "echo"))
            val consent = mockk<dev.chungjungsoo.gptmobile.data.permissions.FreeModelToolConsentStore>()
            io.mockk.every { consent.isGranted(any<PlatformV2>(), any(), any()) } answers {
                firstArg<PlatformV2>() == requested && secondArg<String>() == "private:echo" && thirdArg<Int?>() == 7
            }
            val resolver = resolver(dao = dao, settings = ResolverFakeSettingRepository(profiles), consent = consent)
            val user = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(id = 42, chatId = 7, content = "Use the selected tool", platformType = null)
            val selection = ChatMcpToolConfig(allowAllByDefault = true)
            assertFalse(resolver.resolve(registered.uid, selection, user).any { it.realToolName == "echo" })
            assertFalse(resolver.resolve(registered.uid, selection, user, requestedModel = "different-model").any { it.realToolName == "echo" })
            val tool = resolver.resolve(registered.uid, selection, user, requestedModel = requested.model).single { it.realToolName == "echo" }
            assertTrue(requireNotNull(tool.canReuseResult).invoke())
            profiles[0] = registered.copy(token = "changed-credential")
            assertFalse(requireNotNull(tool.canReuseResult).invoke())
        }
    }

    @Test fun savedSearchAuthoritySurvivesDisplayEditsButRejectsPermissionRevocation() = runBlocking {
        val profile = PlatformV2(uid = "profile", name = "Primary")
        val settings = ResolverFakeSettingRepository(listOf(profile))
        val dao = ResolverFakeToolConnectionDao()
        dao.bind(connection("search", ToolConnectionType.EXA, secretRef = "secret"), binding("profile", "search", "web_search"))
        val resolved = resolver(dao, ResolverFakeSecretVault(mapOf("secret" to "token".encodeToByteArray())), settings).resolve("profile")
        val search = resolved.first { it.connectionUid == "search" }
        assertTrue(requireNotNull(search.canReuseResult).invoke())
        settings.features = settings.features.copy(debugShowRuntime = false, smoothStreaming = false)
        assertTrue(requireNotNull(search.canReuseResult).invoke())
        settings.features = settings.features.withToolPluginEnabled(ToolPluginId.WEB_SEARCH, false)
        assertTrue(requireNotNull(search.canReuseResult).invoke())
        val builtIn = resolved.first { it.connectionUid == null && it.realToolName == "web_search" }
        assertFalse(requireNotNull(builtIn.canReuseResult).invoke())
        settings.features = settings.features.withToolPluginEnabled("service:exa", false)
        assertFalse(requireNotNull(search.canReuseResult).invoke())
    }

    @Test fun healthToolsRequireIndependentOptInsStayPhoneOwnedAndHonorChatControls() = runBlocking {
        val profile = PlatformV2(uid = "health-profile", name = "Health", disableRemoteTools = true)
        val settings = ResolverFakeSettingRepository(listOf(profile))
        val reader = mockk<dev.chungjungsoo.gptmobile.data.health.HealthDataReader>()
        val consent = mockk<dev.chungjungsoo.gptmobile.data.health.HealthConnectConsentStore>()
        coEvery { consent.isAllowed() } returns false
        val resolver = resolver(settings = settings, healthReader = reader, healthConsent = consent)
        fun List<ResolvedAgentTool>.health() = filter { it.modelToolName.startsWith("health_connect_") }
        assertTrue(resolver.resolve(profile.uid).health().isEmpty())
        settings.features = settings.features.withToolPluginEnabled(ToolPluginId.HEALTH_CONNECT, true)
        assertTrue(resolver.resolve(profile.uid).health().isEmpty())
        settings.features = settings.features.withProfileToolPluginEnabled(profile.uid, ToolPluginId.HEALTH_CONNECT, true)
        val tools = resolver.resolve(profile.uid).health()
        assertEquals(9, tools.size)
        assertTrue(tools.all { !it.shareableReadOnly && it.connectionUid == null })
        assertTrue(tools.all { (it.tool as? dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool)?.executionOwner == dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner.CLIENT })
        assertEquals("ai_consent_required", tools.first { it.modelToolName.endsWith("summary") }.tool.execute("c", buildJsonObject {}).errorCode)
        assertTrue(resolver.resolve(profile.uid, ChatMcpToolConfig(allToolsDisabled = true)).health().isEmpty())
        val selected = resolver.resolve(profile.uid, ChatMcpToolConfig(disabledToolIds = setOf("health_connect_sleep"))).health()
        assertEquals(8, selected.size)
        settings.features = settings.features.withProfileToolPluginEnabled(profile.uid, ToolPluginId.HEALTH_CONNECT, false)
        assertEquals("plugin_disabled", tools.first().tool.execute("revoked", buildJsonObject {}).errorCode)
        val localDisabled = ResolverFakeSettingRepository(listOf(profile.copy(disableLocalTools = true)), settings.features.withProfileToolPluginEnabled(profile.uid, ToolPluginId.HEALTH_CONNECT, true))
        assertTrue(resolver(settings = localDisabled, healthReader = reader, healthConsent = consent).resolve(profile.uid).health().isEmpty())
    }

    @Test fun googleMcpApiKeyUsesDedicatedHeaderConfigurationAndClearsVaultBytes() = runBlocking {
        val vault = ResolverFakeSecretVault(mapOf("google-key" to "test-google-key".toByteArray()))
        val connection = connection("google", ToolConnectionType.MCP, "https://mapstools.googleapis.com/mcp", "google-key", ToolConnectionAuthType.API_KEY)
        val config = resolver(vault = vault).mcpConfig(connection)
        assertEquals(null, config.authorizationHeader)
        assertEquals("test-google-key", config.googleApiKey)
        assertTrue(vault.lastReadBytes!!.all { it == 0.toByte() })
    }

    @Test fun nativeAirbnbNeedsNoRemoteConnectionAndRechecksProfilePermissions() = runBlocking {
        val profile = PlatformV2(uid = "profile", name = "Research")
        val settings = ResolverFakeSettingRepository(listOf(profile))
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("Resolution must not perform a network request") })
        try {
            val resolver = resolver(settings = settings, airbnb = PublicAirbnbClient(http))
            assertFalse(resolver.resolve(profile.uid).any { it.modelToolName == "airbnb" })
            settings.features = settings.features.copy(remoteMcpConnections = false)
                .withToolPluginEnabled(ToolPluginId.AIRBNB, true).withProfileToolPluginEnabled(profile.uid, ToolPluginId.AIRBNB, true)
            val airbnb = resolver.resolve(profile.uid).single { it.modelToolName == "airbnb" }
            assertEquals(null, airbnb.connectionUid)
            assertTrue(requireNotNull(airbnb.canReuseResult).invoke())
            settings.features = settings.features.withProfileToolPluginEnabled(profile.uid, ToolPluginId.AIRBNB, false)
            assertFalse(requireNotNull(airbnb.canReuseResult).invoke())
        } finally {
            http.close()
        }
    }

    @Test fun newsRequiresGlobalAndProfileGrantsAndCachedResultsRecheckPermissions() = runBlocking {
        val profile = PlatformV2(uid = "profile", name = "Research")
        val settings = ResolverFakeSettingRepository(listOf(profile))
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("Resolution performs no news requests") })
        try {
            val resolver = resolver(settings = settings, news = PublicNewsClient(http))
            assertFalse(resolver.resolve(profile.uid).any { it.modelToolName == "news" })
            settings.features = settings.features.withToolPluginEnabled(ToolPluginId.NEWS, true).withProfileToolPluginEnabled(profile.uid, ToolPluginId.NEWS, true)
            val news = resolver.resolve(profile.uid).single { it.modelToolName == "news" }
            assertTrue(requireNotNull(news.canReuseResult).invoke())
            settings.features = settings.features.withProfileToolPluginEnabled(profile.uid, ToolPluginId.NEWS, false)
            assertFalse(requireNotNull(news.canReuseResult).invoke())
        } finally {
            http.close()
        }
    }

    @Test
    fun localAmazonToolsRemainAvailableWithRemoteToolsDisabledAndRespectLocalDisable() = runBlocking {
        val profile = PlatformV2(uid = "profile", name = "Research", disableRemoteTools = true)
        val features = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings().withToolPluginEnabled(ToolPluginId.AMAZON_FREE, true).withProfileToolPluginEnabled(profile.uid, ToolPluginId.AMAZON_FREE, true)
        val history = mockk<dev.chungjungsoo.gptmobile.data.amazon.AmazonHistoryRepository>()
        val settings = ResolverFakeSettingRepository(listOf(profile), features)
        val access = dev.chungjungsoo.gptmobile.data.amazon.AmazonAccessPolicy(settings)
        val local = resolver(settings = settings, history = history, access = access).resolve(profile.uid).filter { it.modelToolName in AmazonLocalTool.names }
        assertEquals(setOf(AmazonLocalTool.HISTORY), local.map { it.modelToolName }.toSet())
        assertFalse(local.any { it.modelToolName == AmazonLocalTool.WATCHES })
        assertTrue(local.all { !it.shareableReadOnly && it.connectionUid == null })
        val blocked = resolver(settings = ResolverFakeSettingRepository(listOf(profile.copy(disableLocalTools = true)), features), history = history, access = access).resolve(profile.uid)
        assertFalse(blocked.any { it.modelToolName in AmazonLocalTool.names })
    }

    @Test
    fun nativeAmazonRequiresIndependentGlobalAndProfileGrantsAndHonorsRemoteDisable() = runBlocking {
        val provider = mockk<AmazonHtmlProvider>()
        val profile = PlatformV2(uid = "profile", name = "Research")
        val settings = ResolverFakeSettingRepository(listOf(profile))
        assertFalse(resolver(settings = settings, amazonFree = provider).resolve(profile.uid).any { it.modelToolName in AmazonNativeTool.names })
        settings.features = settings.features.withToolPluginEnabled(ToolPluginId.AMAZON_FREE, true)
        assertFalse(resolver(settings = settings, amazonFree = provider).resolve(profile.uid).any { it.modelToolName in AmazonNativeTool.names })
        settings.features = settings.features.withProfileToolPluginEnabled(profile.uid, ToolPluginId.AMAZON_FREE, true).copy(remoteMcpConnections = false)
        val native = resolver(settings = settings, amazonFree = provider).resolve(profile.uid).filter { it.modelToolName in AmazonNativeTool.names }
        assertEquals(AmazonNativeTool.names, native.map { it.modelToolName }.toSet())
        assertEquals(setOf("amazon_search", "amazon_get_products"), native.map { it.realToolName }.toSet())
        assertTrue(native.all { !it.shareableReadOnly && it.isAmazonProductTool() })
        assertFalse(settings.features.isToolPluginEnabled(ToolPluginId.AMAZON_SEARCH))
        coEvery { provider.search(any(), any()) } returns AmazonFetchResult(emptyList())
        val search = native.single { it.modelToolName == AmazonNativeTool.SEARCH }.tool
        assertFalse(search.execute("read", buildJsonObject { put("query", "audio") }).isError)
        settings.features = settings.features.withToolPluginEnabled(ToolPluginId.AMAZON_FREE, false)
        assertTrue(search.execute("revoked", buildJsonObject { put("query", "audio") }).isError)
        val granted = settings.features.withToolPluginEnabled(ToolPluginId.AMAZON_FREE, true)
        for (blocked in listOf(profile.copy(disableRemoteTools = true), profile.copy(disableAllTools = true), profile.copy(enabled = false))) {
            val result = resolver(settings = ResolverFakeSettingRepository(listOf(blocked), granted), amazonFree = provider).resolve(profile.uid)
            assertFalse(result.any { it.modelToolName in AmazonNativeTool.names })
        }
    }

    @Test
    fun `installed enabled native adapters join conversation tools and respect disable switches`() = runBlocking {
        val registry = mockk<NativeMarketplaceRegistry>()
        val records = dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog.packages
            .filter { dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog.supports(it) }
            .associate { it.id to NativePluginInstallation(enabled = true, endpoint = "https://managed.example/search", credentialRef = "saved", endpoints = mapOf("geocode" to "https://managed.example/search", "restrooms" to "https://managed.example/interpreter")) }
        coEvery { registry.load() } returns records
        val client = mockk<NativeMarketplaceClient>()
        val features = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings().withToolPluginEnabled(ToolPluginId.GOOGLE_PLACES, true).withProfileToolPluginEnabled("profile", ToolPluginId.GOOGLE_PLACES, true)
        val resolver = resolver(settings = ResolverFakeSettingRepository(features = features), nativeRegistry = registry, nativeClient = client)
        val tools = resolver.resolve("profile").filter { it.connectionUid in records.keys }
        assertEquals(15, tools.size)
        assertEquals(15, tools.map { it.modelToolName }.toSet().size)
        assertEquals(3, tools.count { it.connectionUid == "optional-google-places" })
        assertFalse(resolver(nativeRegistry = registry, nativeClient = client).resolve("profile").any { it.connectionUid == "optional-google-places" })
        assertTrue(tools.all { it.shareableReadOnly })
        assertFalse(resolver.resolve("profile", ChatMcpToolConfig(allowAllByDefault = true).withToolDisabled("optional-refuge")).any { it.connectionUid == "optional-refuge" })
        coEvery { registry.load() } returns records.mapValues { (_, record) -> record.copy(enabled = false) }
        assertFalse(resolver.resolve("profile").any { it.connectionUid in records.keys })
        coEvery { registry.load() } throws java.io.IOException("corrupt optional registry")
        assertTrue(resolver.resolve("profile").any { it.realToolName == "current_date" })
        coEvery { registry.load() } returns records
        val local = PlatformV2(uid = "local", name = "Local", disableRemoteTools = true)
        val localTools = resolver(settings = ResolverFakeSettingRepository(listOf(local)), nativeRegistry = registry, nativeClient = client).resolve(local.uid)
        assertFalse(localTools.any { it.connectionUid in records.keys - "optional-universal-marketplace" })
        // Link generation is a local operation; the remote switch must not disable it.
        assertTrue(localTools.any { it.connectionUid == "optional-universal-marketplace" })
        val noLocal = local.copy(disableLocalTools = true)
        assertFalse(resolver(settings = ResolverFakeSettingRepository(listOf(noLocal)), nativeRegistry = registry, nativeClient = client).resolve(local.uid).any { it.connectionUid in records.keys })
    }

    @Test
    fun `Amazon tools are available without bindings and respect plugin and chat switches`() = runBlocking {
        val dao = ResolverFakeToolConnectionDao()
        dao.upsertConnection(connection("shopping", ToolConnectionType.AMAZON_SERPAPI))
        val tools = resolver(dao = dao, settings = ResolverFakeSettingRepository(features = amazonEnabled())).resolve("profile").filter { it.connectionUid == "shopping" }
        assertEquals(setOf("amazon_search__shopping", "amazon_get_products__shopping"), tools.map { it.modelToolName }.toSet())
        assertTrue(tools.all { it.shareableReadOnly && !it.isWebSearchEngine() })
        assertFalse(resolver(dao = dao, settings = ResolverFakeSettingRepository(features = amazonEnabled())).resolve("profile", ChatMcpToolConfig(allowAllByDefault = true).withToolDisabled("shopping")).any { it.connectionUid == "shopping" })
        val disabled = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(toolPluginStates = mapOf(ToolPluginId.AMAZON_SEARCH to false))
        assertFalse(resolver(dao = dao, settings = ResolverFakeSettingRepository(features = disabled)).resolve("profile").any { it.connectionUid == "shopping" })
        val disabledConnection = amazonEnabled().withToolPluginEnabled(ToolPluginId.connection("shopping"), false)
        assertFalse(resolver(dao = dao, settings = ResolverFakeSettingRepository(features = disabledConnection)).resolve("profile").any { it.connectionUid == "shopping" })
    }

    @Test
    fun `Amazon tools obey remote tool disable and report missing credentials without network access`() = runBlocking {
        val dao = ResolverFakeToolConnectionDao()
        dao.upsertConnection(connection("shopping", ToolConnectionType.AMAZON_SERPAPI))
        val profile = PlatformV2(uid = "profile", name = "Local only", disableRemoteTools = true)
        assertFalse(resolver(dao = dao, settings = ResolverFakeSettingRepository(listOf(profile), amazonEnabled())).resolve(profile.uid).any { it.connectionUid == "shopping" })
        val tool = resolver(dao = dao, settings = ResolverFakeSettingRepository(features = amazonEnabled())).resolve("profile").single { it.realToolName == AmazonSearchTool.SEARCH }
        val result = tool.tool.execute("missing-key", buildJsonObject { put("query", "headphones") })
        assertTrue(result.isError)
        assertTrue(result.content.toString().contains("SerpApi API key"))
    }

    @Test
    fun conversationDelegationCannotEnableDisabledGlobalPolicyAndCanRestrictEnabledDefaults() = runBlocking {
        val main = PlatformV2(uid = "main", name = "Main", compatibleType = ClientType.OPENAI)
        val helper = PlatformV2(uid = "helper", name = "Helper", compatibleType = ClientType.OLLAMA, apiUrl = "http://localhost:11434")
        val enabledForChat = ChatMcpToolConfig(delegation = dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings(true, helper.uid))
        val offByDefault = resolver(settings = ResolverFakeSettingRepository(listOf(main, helper)))
        val tools = offByDefault.resolve(main.uid, enabledForChat, delegate = { target, _, _ -> "Helper ${target.uid}" })
        assertFalse(tools.any { it.realToolName == "delegate_to_model" })
        assertFalse(offByDefault.resolve(main.uid, delegate = { _, _, _ -> "unused" }).any { it.realToolName == "delegate_to_model" })

        val enabledDefaults = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(delegation = dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings(enabled = true), profileBehavior = mapOf(main.uid to dev.chungjungsoo.gptmobile.data.model.ProfileBehaviorSettings(delegationEnabled = true)))
        val onByDefault = resolver(settings = ResolverFakeSettingRepository(listOf(main, helper), enabledDefaults))
        val delegate = onByDefault.resolve(main.uid, enabledForChat, delegate = { target, _, _ -> "Helper ${target.uid}" }).single { it.realToolName == "delegate_to_model" }
        assertFalse(delegate.tool.execute("chat-enabled", buildJsonObject { put("task", "Summarize") }).isError)
        val disabledForChat = enabledForChat.copy(delegation = dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings(false))
        assertFalse(onByDefault.resolve(main.uid, disabledForChat, delegate = { _, _, _ -> "unused" }).any { it.realToolName == "delegate_to_model" })
    }

    @Test
    fun `GitHub API connections are available without profile bindings and respect conversation options`() = runBlocking {
        val dao = ResolverFakeToolConnectionDao()
        dao.upsertConnection(connection("work", ToolConnectionType.GITHUB, endpointUrl = "https://api.github.com"))
        val tools = resolver(dao = dao).resolve("profile")
        val account = tools.single { it.connectionUid == "work" && it.realToolName == BuiltInAgentTool.GITHUB }
        assertEquals("github__work", account.modelToolName)
        assertEquals(BuiltInAgentTool.GITHUB, account.realToolName)
        assertFalse(account.shareableReadOnly)
        assertTrue(account.tool.definition.description.contains("Authenticated connection"))
        assertFalse(resolver(dao = dao).resolve("profile", ChatMcpToolConfig(allowAllByDefault = true).withToolDisabled("work")).any { it.connectionUid == "work" })
    }

    @Test
    fun `free MCP usage requires the grant for the exact profile and tool at runtime`() = runBlocking {
        McpClientManagerTest.McpFixtureServer().use { server ->
            val profile = FreeAiProvider.OVHCLOUD.applyTo(PlatformV2(uid = "free-profile", name = "Free"))
            val dao = ResolverFakeToolConnectionDao()
            dao.bind(connection("mcp-1", ToolConnectionType.MCP, endpointUrl = server.url, authType = ToolConnectionAuthType.NONE, allowCleartext = true), binding(profile.uid, "mcp-1", "echo"))
            val consent = mockk<dev.chungjungsoo.gptmobile.data.permissions.FreeModelToolConsentStore>()
            io.mockk.every { consent.isGranted(any<PlatformV2>(), any()) } returns false
            val resolver = resolver(dao = dao, settings = ResolverFakeSettingRepository(listOf(profile)), consent = consent)
            assertFalse(resolver.resolve(profile.uid).any { it.realToolName == "echo" })
            val all = ChatMcpToolConfig(allowAllByDefault = true)
            assertFalse(resolver.resolve(profile.uid, all).any { it.realToolName == "echo" })
            io.mockk.every { consent.isGranted(profile, "mcp-1:echo") } returns true
            assertTrue(resolver.resolve(profile.uid, all).any { it.realToolName == "echo" })
            assertFalse(resolver.resolve(profile.uid, all.withToolDisabled("mcp-1:echo")).any { it.realToolName == "echo" })
        }
    }

    @Test
    fun `Prohibited free routes retain public tools but cannot expose personal tools`() = runBlocking {
        val profile = FreeAiProvider.KILO.applyTo(PlatformV2(uid = "profile-1", name = "Free", compatibleType = ClientType.FREE))
        val dao = ResolverFakeToolConnectionDao()
        dao.bind(null, binding(profile.uid, null, BuiltInAgentTool.DEVICE_LOCATION))
        dao.bind(null, binding(profile.uid, null, BuiltInAgentTool.READ_FILE_SLICE))
        val vault = ResolverFakeSecretVault()
        val facts = dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository(vault, dev.chungjungsoo.gptmobile.data.rag.KnowledgeGraphEngine())
        facts.setEnabled(true)
        val resolved = resolver(dao, vault, ResolverFakeSettingRepository(listOf(profile)), facts).resolve(
            profile.uid,
            userMessage = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(content = "Remember that I prefer Kotlin", platformType = null),
            delegate = { _, _, _ -> error("Free profiles must not delegate") }
        )
        assertTrue(resolved.map { it.modelToolName }.containsAll(listOf("calculate_expression", "current_date", "read_url", "web_search")))
        assertFalse(resolved.any { it.modelToolName in setOf("device_location", "memory", "read_file_slice") })
        assertTrue(resolved.filter { it.realToolName == BuiltInAgentTool.GITHUB }.all { it.nativeGitHub && !it.nativeGitHubAuthenticated })
    }

    @Test
    fun `zero bindings resolves current date, calculate expression, github, read url, and web search tools`() = runBlocking {
        val resolver = resolver()

        val resolved = resolver.resolve("profile-1")

        assertEquals(
            withGitHubFacades(listOf("calculate_expression", "current_date", "github", "read_file_slice", "read_url", "web_search")),
            resolved.map { it.modelToolName }
        )
        assertEquals(null, resolved[0].connectionUid)
        assertEquals(null, resolved[0].connectionName)
        assertEquals(null, resolved[1].connectionUid)
        assertEquals(null, resolved[1].connectionName)
        assertEquals(null, resolved[2].connectionUid)
        assertEquals(null, resolved[2].connectionName)
        assertEquals(null, resolved[3].connectionUid)
        assertEquals(null, resolved[3].connectionName)
        assertEquals(null, resolved[4].connectionUid)
        assertEquals(null, resolved[4].connectionName)
        assertEquals(null, resolved[5].connectionUid)
        assertEquals(null, resolved[5].connectionName)
        assertEquals(WebSearchProvider.AUTO, resolved.single { it.modelToolName == "web_search" }.tool.webSearchConfig().provider)
    }

    @Test
    fun `integrated plugin states remove disabled built in tools from the model catalog`() = runBlocking {
        val features = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(
            toolPluginStates = mapOf(
                ToolPluginId.CALCULATOR to false,
                ToolPluginId.GITHUB to false,
                ToolPluginId.READ_URL to false
            )
        )
        val resolved = resolver(settings = ResolverFakeSettingRepository(features = features)).resolve("profile-1")

        assertEquals(
            listOf("current_date", "read_file_slice", "web_search"),
            resolved.map { it.modelToolName }
        )
    }

    @Test
    fun `configured native plugin can be disabled without anonymous fallback bypass`() = runBlocking {
        val dao = ResolverFakeToolConnectionDao()
        dao.upsertConnection(connection("work", ToolConnectionType.GITHUB, endpointUrl = "https://api.github.com"))
        val features = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(
            toolPluginStates = mapOf(ToolPluginId.connection("work") to false)
        )

        val resolved = resolver(dao = dao, settings = ResolverFakeSettingRepository(features = features)).resolve("profile-1")

        assertFalse(resolved.any { it.realToolName == BuiltInAgentTool.GITHUB })
    }

    @Test
    fun `current date returns deterministic local date time and zone`() = runBlocking {
        val tool = CurrentDateTool(
            Clock.fixed(Instant.parse("2026-08-13T03:04:05Z"), ZoneId.of("Asia/Tokyo"))
        )

        val result = tool.execute("call-date", buildJsonObject {})

        assertEquals("current_date", tool.definition.name)
        assertEquals(
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {})
                put("additionalProperties", false)
            },
            tool.definition.inputSchema
        )
        assertEquals("call-date", result.callId)
        assertEquals(false, result.isError)
        assertEquals(
            """{"date":"2026-08-13","time":"12:04:05","zone":"Asia/Tokyo"}""",
            (result.content as ToolResultContent.Json).value.toString()
        )
    }

    @Test
    fun `resolve filters bindings to exact profile`() = runBlocking {
        val dao = ResolverFakeToolConnectionDao()
        val resolver = resolver(dao)
        dao.bind(connection("search-1", ToolConnectionType.FIRECRAWL, secretRef = "secret-1"), binding("profile-2", "search-1", "web_search"))
        dao.bind(null, binding("profile-1", null, BuiltInAgentTool.DEVICE_LOCATION))

        val resolved = resolver.resolve("profile-1")

        assertEquals(
            withGitHubFacades(listOf("calculate_expression", "current_date", "device_location", "github", "read_file_slice", "read_url", "web_search")),
            resolved.map { it.tool.definition.name }
        )
        assertEquals(null, resolved.single { it.modelToolName == "device_location" }.connectionUid)
        assertEquals(WebSearchProvider.AUTO, resolved.single { it.modelToolName == "web_search" }.tool.webSearchConfig().provider)
    }

    @Test
    fun `search bindings map provider endpoints and wipe credential bytes`() = runBlocking {
        val cases = listOf(
            ToolConnectionType.FIRECRAWL to WebSearchProvider.FIRECRAWL to "https://api.firecrawl.dev/v2/search",
            ToolConnectionType.PERPLEXITY to WebSearchProvider.PERPLEXITY to "https://api.perplexity.ai/search",
            ToolConnectionType.EXA to WebSearchProvider.EXA to "https://api.exa.ai/search",
            ToolConnectionType.BRAVE to WebSearchProvider.BRAVE to "https://api.search.brave.com/res/v1/web/search"
        )

        cases.forEachIndexed { index, (providerCase, defaultEndpoint) ->
            val (connectionType, expectedProvider) = providerCase
            val dao = ResolverFakeToolConnectionDao()
            val vault = ResolverFakeSecretVault(mapOf("secret-$index" to "token-$index".encodeToByteArray()))
            val resolver = resolver(dao, vault)
            dao.bind(connection("search-$index", connectionType, endpointUrl = null, secretRef = "secret-$index"), binding("profile-1", "search-$index", "web_search"))

            val resolved = resolver.resolve("profile-1").single { it.realToolName == "web_search" && it.connectionUid != null }
            val config = resolved.tool.webSearchConfig()

            assertEquals("web_search", resolved.realToolName)
            assertEquals("web_search_search_$index", resolved.modelToolName)
            assertEquals("search-$index", resolved.connectionUid)
            assertEquals("Search $index", resolved.connectionName)
            assertEquals(expectedProvider, config.provider)
            assertEquals("token-$index", config.bearerToken)
            assertEquals(defaultEndpoint, config.endpointUrl)
            assertTrue(vault.lastReadBytes!!.all { it == 0.toByte() })
        }
    }

    @Test
    fun `search binding ignores stored endpoint to protect bearer credentials`() = runBlocking {
        val dao = ResolverFakeToolConnectionDao()
        val vault = ResolverFakeSecretVault(mapOf("secret-1" to "token".encodeToByteArray()))
        val resolver = resolver(dao, vault)
        dao.bind(
            connection("search-1", ToolConnectionType.EXA, endpointUrl = "https://search.example/custom", secretRef = "secret-1"),
            binding("profile-1", "search-1", "web_search")
        )

        val config = resolver.resolve("profile-1").single { it.realToolName == "web_search" && it.connectionUid != null }.tool.webSearchConfig()

        assertEquals("https://api.exa.ai/search", config.endpointUrl)
    }

    @Test
    fun `assigned search without credential returns bounded error without network`() = runBlocking {
        val dao = ResolverFakeToolConnectionDao()
        val resolver = resolver(dao)
        dao.bind(connection("search-1", ToolConnectionType.FIRECRAWL, secretRef = null), binding("profile-1", "search-1", "web_search"))

        val resolved = resolver.resolve("profile-1").single { it.realToolName == "web_search" && it.connectionUid != null }
        val result = resolved.tool.execute("call-1", buildJsonObject {})

        assertEquals("web_search_search_1", resolved.tool.definition.name)
        assertTrue(result.isError)
        val text = (result.content as ToolResultContent.Text).text
        assertTrue(text.contains("missing credential"))
        assertTrue(text.length <= 240)
        assertTrue(!text.contains("secret"))
        assertTrue(!text.contains("search-1"))
    }

    @Test
    fun `vault read failure aborts resolution instead of masking credential storage errors`() = runBlocking {
        val dao = ResolverFakeToolConnectionDao()
        val vault = ResolverFakeSecretVault(readError = IllegalStateException("vault unavailable"))
        val resolver = resolver(dao, vault)
        dao.bind(connection("search-1", ToolConnectionType.FIRECRAWL, secretRef = "secret-1"), binding("profile-1", "search-1", "web_search"))

        val error = runCatching { resolver.resolve("profile-1") }.exceptionOrNull()

        assertEquals("vault unavailable", error?.message)
    }

    @Test
    fun `read url binding exposes default read_url tool snapshot`() = runBlocking {
        val dao = ResolverFakeToolConnectionDao()
        val resolver = resolver(dao)
        dao.bind(null, binding("profile-1", null, BuiltInAgentTool.READ_URL))

        val resolved = resolver.resolve("profile-1").single { it.modelToolName == "read_url" }

        assertEquals(ReadUrlTool::class.java, resolved.tool.javaClass)
        assertEquals(null, resolved.connectionUid)
        assertEquals(null, resolved.connectionName)
        assertEquals("read_url", resolved.realToolName)
        assertEquals("read_url", resolved.modelToolName)
    }

    @Test
    fun `calculate expression binding exposes default calculator tool snapshot`() = runBlocking {
        val dao = ResolverFakeToolConnectionDao()
        val resolver = resolver(dao)
        dao.bind(null, binding("profile-1", null, BuiltInAgentTool.CALCULATE_EXPRESSION))

        val resolved = resolver.resolve("profile-1").single { it.modelToolName == "calculate_expression" }

        assertEquals(CalculatorTool::class.java, resolved.tool.javaClass)
        assertEquals(null, resolved.connectionUid)
        assertEquals(null, resolved.connectionName)
        assertEquals("calculate_expression", resolved.realToolName)
        assertEquals("calculate_expression", resolved.modelToolName)
    }

    @Test
    fun `orphan and unknown bindings are ignored while preserving built-in tools`() = runBlocking {
        val dao = ResolverFakeToolConnectionDao()
        val resolver = resolver(dao)
        dao.bind(null, binding("profile-1", "missing", "custom_missing"))
        dao.bind(connection("search-1", ToolConnectionType.FIRECRAWL, secretRef = "secret-1"), binding("profile-1", "search-1", "unknown_tool"))

        assertEquals(
            withGitHubFacades(listOf("calculate_expression", "current_date", "github", "read_file_slice", "read_url", "web_search")),
            resolver.resolve("profile-1").map { it.modelToolName }
        )
    }

    @Test
    fun `assigned MCP tool is namespaced and executable while unassigned tools stay hidden`() = runBlocking {
        McpClientManagerTest.McpFixtureServer().use { server ->
            val dao = ResolverFakeToolConnectionDao()
            val vault = ResolverFakeSecretVault()
            val repository = ToolConnectionRepository(dao, vault)
            val networkClient = NetworkClient(CIO)
            val manager = McpClientManager(networkClient())
            val resolver = AgentToolResolver(
                toolConnectionRepository = repository,
                settingRepository = ResolverFakeSettingRepository(),
                secretVault = vault,
                networkClient = networkClient,
                mcpClientManager = manager,
                mcpOAuthCoordinator = McpOAuthCoordinator(McpOAuthClient(networkClient()), repository, vault, manager),
                deviceLocationTool = DeviceLocationTool(mockk(relaxed = true), mockk(relaxed = true))
            )
            dao.bind(
                connection(
                    uid = "mcp-1",
                    type = ToolConnectionType.MCP,
                    endpointUrl = server.url,
                    authType = ToolConnectionAuthType.NONE,
                    allowCleartext = true
                ),
                binding("profile-1", "mcp-1", "echo")
            )

            val resolved = resolver.resolve("profile-1").single { it.connectionUid == "mcp-1" }
            val result = resolved.tool.execute("call-1", buildJsonObject { put("text", "hello") })

            assertEquals("mcp__mcp-1__echo", resolved.modelToolName)
            assertEquals("echo", resolved.realToolName)
            assertEquals("hello", (result.content as ToolResultContent.Text).text)
            manager.closeAll()
            networkClient().close()
        }
    }

    @Test
    fun `disabled and uninstalled MCP connections stop already resolved tools`() = runBlocking {
        McpClientManagerTest.McpFixtureServer().use { server ->
            val dao = ResolverFakeToolConnectionDao()
            val settings = ResolverFakeSettingRepository()
            dao.bind(connection("mcp-1", ToolConnectionType.MCP, endpointUrl = server.url, authType = ToolConnectionAuthType.NONE, allowCleartext = true), binding("profile", "mcp-1", "echo"))
            val resolver = resolver(dao = dao, settings = settings)
            val held = resolver.resolve("profile").single { it.connectionUid == "mcp-1" }
            settings.features = settings.features.withToolPluginEnabled(ToolPluginId.connection("mcp-1"), false)
            assertFalse(resolver.resolve("profile").any { it.connectionUid == "mcp-1" })
            assertTrue(held.tool.execute("disabled", buildJsonObject { put("text", "hello") }).isError)
            settings.features = settings.features.withToolPluginEnabled(ToolPluginId.connection("mcp-1"), true)
            dao.deleteConnectionByUid("mcp-1")
            assertTrue(held.tool.execute("removed", buildJsonObject { put("text", "hello") }).isError)
            assertFalse(resolver.resolve("profile").any { it.connectionUid == "mcp-1" })
        }
    }

    @Test
    fun `MCP bearer binding reads vault token and authenticates discovery and call`() = runBlocking {
        McpClientManagerTest.McpFixtureServer(acceptedAuthorization = "Bearer secret-token").use { server ->
            val dao = ResolverFakeToolConnectionDao()
            val vault = ResolverFakeSecretVault(mapOf("connection_mcp-1" to "secret-token".encodeToByteArray()))
            val repository = ToolConnectionRepository(dao, vault)
            val networkClient = NetworkClient(CIO)
            val manager = McpClientManager(networkClient())
            val resolver = AgentToolResolver(
                toolConnectionRepository = repository,
                settingRepository = ResolverFakeSettingRepository(),
                secretVault = vault,
                networkClient = networkClient,
                mcpClientManager = manager,
                mcpOAuthCoordinator = McpOAuthCoordinator(McpOAuthClient(networkClient()), repository, vault, manager),
                deviceLocationTool = DeviceLocationTool(mockk(relaxed = true), mockk(relaxed = true))
            )
            dao.bind(
                connection(
                    uid = "mcp-1",
                    type = ToolConnectionType.MCP,
                    endpointUrl = server.url,
                    secretRef = "connection_mcp-1",
                    authType = ToolConnectionAuthType.BEARER,
                    allowCleartext = true
                ),
                binding("profile-1", "mcp-1", "echo")
            )

            val tool = resolver.resolve("profile-1").single { it.connectionUid == "mcp-1" }.tool
            val result = tool.execute("call-1", buildJsonObject { put("text", "hello") })

            assertEquals("hello", (result.content as ToolResultContent.Text).text)
            assertTrue(server.authorizationHeaders.all { it == "Bearer secret-token" })
            assertTrue(vault.lastReadBytes!!.all { it == 0.toByte() })
            manager.closeAll()
            networkClient().close()
        }
    }

    @Test
    fun `MCP bearer binding rejects blank stored token before authorization header is built`() = runBlocking {
        val dao = ResolverFakeToolConnectionDao()
        val vault = ResolverFakeSecretVault(mapOf("connection_mcp-1" to " ".encodeToByteArray()))
        val resolver = resolver(dao, vault)
        dao.bind(
            connection(
                uid = "mcp-1",
                type = ToolConnectionType.MCP,
                endpointUrl = "https://example.com/mcp",
                secretRef = "connection_mcp-1",
                authType = ToolConnectionAuthType.BEARER
            ),
            binding("profile-1", "mcp-1", "echo")
        )

        val resolved = resolver.resolve("profile-1")

        assertEquals(
            withGitHubFacades(listOf("calculate_expression", "current_date", "github", "read_file_slice", "read_url", "web_search")),
            resolved.map { it.modelToolName }
        )
    }

    @Test
    fun `MCP OAuth 401 refreshes stored token reconnects and retries tool call once`() = runBlocking {
        McpClientManagerTest.McpFixtureServer(acceptedAuthorization = "Bearer access-1").use { server ->
            val dao = ResolverFakeToolConnectionDao()
            val credential = McpOAuthCredential(
                clientId = "client-1",
                tokenEndpoint = server.tokenUrl,
                resource = server.url,
                accessToken = "access-1",
                tokenType = "Bearer",
                refreshToken = "refresh-1"
            )
            val vault = ResolverFakeSecretVault(
                mapOf("connection_mcp-1" to NetworkClient.json.encodeToString(credential).encodeToByteArray())
            )
            val repository = ToolConnectionRepository(dao, vault)
            val networkClient = NetworkClient(CIO)
            val manager = McpClientManager(networkClient())
            val resolver = AgentToolResolver(
                toolConnectionRepository = repository,
                settingRepository = ResolverFakeSettingRepository(),
                secretVault = vault,
                networkClient = networkClient,
                mcpClientManager = manager,
                mcpOAuthCoordinator = McpOAuthCoordinator(McpOAuthClient(networkClient()), repository, vault, manager),
                deviceLocationTool = DeviceLocationTool(mockk(relaxed = true), mockk(relaxed = true))
            )
            dao.bind(
                connection(
                    uid = "mcp-1",
                    type = ToolConnectionType.MCP,
                    endpointUrl = server.url,
                    secretRef = "connection_mcp-1",
                    authType = ToolConnectionAuthType.OAUTH,
                    allowCleartext = true
                ),
                binding("profile-1", "mcp-1", "echo")
            )

            val tool = resolver.resolve("profile-1").single { it.connectionUid == "mcp-1" }.tool
            server.acceptedAuthorization = "Bearer access-2"
            val result = tool.execute("call-1", buildJsonObject { put("text", "hello") })
            val stored = NetworkClient.json.decodeFromString<McpOAuthCredential>(vault.value("connection_mcp-1")!!.decodeToString())

            assertEquals("hello", (result.content as ToolResultContent.Text).text)
            assertEquals(1, server.refreshRequests.get())
            assertEquals("access-2", stored.accessToken)
            assertTrue("Bearer access-1" in server.authorizationHeaders)
            assertEquals("Bearer access-2", server.authorizationHeaders.last())
            manager.closeAll()
            networkClient().close()
        }
    }

    @Test
    fun `one unavailable MCP connection does not hide tools from healthy connections`() = runBlocking {
        McpClientManagerTest.McpFixtureServer().use { server ->
            val dao = ResolverFakeToolConnectionDao()
            val vault = ResolverFakeSecretVault()
            val repository = ToolConnectionRepository(dao, vault)
            val networkClient = NetworkClient(CIO)
            val manager = McpClientManager(networkClient())
            val resolver = AgentToolResolver(
                toolConnectionRepository = repository,
                settingRepository = ResolverFakeSettingRepository(),
                secretVault = vault,
                networkClient = networkClient,
                mcpClientManager = manager,
                mcpOAuthCoordinator = McpOAuthCoordinator(McpOAuthClient(networkClient()), repository, vault, manager),
                deviceLocationTool = DeviceLocationTool(mockk(relaxed = true), mockk(relaxed = true))
            )
            dao.bind(
                connection("mcp-bad", ToolConnectionType.MCP, endpointUrl = "not-a-url", authType = ToolConnectionAuthType.NONE),
                binding("profile-1", "mcp-bad", "echo")
            )
            dao.bind(
                connection("mcp-good", ToolConnectionType.MCP, endpointUrl = server.url, authType = ToolConnectionAuthType.NONE, allowCleartext = true),
                binding("profile-1", "mcp-good", "echo")
            )

            val resolved = resolver.resolve("profile-1")

            assertEquals(
                withGitHubFacades(listOf("calculate_expression", "current_date", "github", "mcp__mcp-good__echo", "read_file_slice", "read_url", "web_search")),
                resolved.map { it.modelToolName }
            )
            manager.closeAll()
            networkClient().close()
        }
    }

    @Test
    fun `resolved search engines are deterministic and retain every distinct binding`() = runBlocking {
        val dao = ResolverFakeToolConnectionDao()
        val vault = ResolverFakeSecretVault(
            mapOf(
                "secret-a" to "token-a".encodeToByteArray(),
                "secret-b" to "token-b".encodeToByteArray()
            )
        )
        val resolver = resolver(dao, vault)
        dao.bind(connection("search-b", ToolConnectionType.EXA, secretRef = "secret-b"), binding("profile-1", "search-b", "web_search", bindingUid = "binding-b"))
        dao.bind(connection("search-a", ToolConnectionType.FIRECRAWL, secretRef = "secret-a"), binding("profile-1", "search-a", "web_search", bindingUid = "binding-a"))
        dao.bind(null, binding("profile-1", null, BuiltInAgentTool.READ_URL, bindingUid = "binding-read"))

        val resolved = resolver.resolve("profile-1")

        assertEquals(
            withGitHubFacades(listOf("calculate_expression", "current_date", "github", "read_file_slice", "read_url", "web_search", "web_search_search_a", "web_search_search_b")),
            resolved.map { it.modelToolName }
        )
        assertEquals("search-a", resolved.single { it.connectionUid == "search-a" }.connectionUid)
        assertEquals(WebSearchProvider.FIRECRAWL, resolved.single { it.connectionUid == "search-a" }.tool.webSearchConfig().provider)
    }

    @Test
    fun `resolve filters out tools disabled in chat tool config`() = runBlocking {
        val dao = ResolverFakeToolConnectionDao()
        val resolver = resolver(dao)

        // When read_url, github, and web_search are disabled in ChatMcpToolConfig
        val disabledConfig = ChatMcpToolConfig(
            disabledToolIds = setOf("read_url", "github", "web_search")
        )
        val resolvedWithDisabled = resolver.resolve("profile-1", chatToolConfig = disabledConfig)
        assertEquals(listOf("calculate_expression", "current_date", "read_file_slice"), resolvedWithDisabled.map { it.modelToolName })

        // When read_url is explicitly enabled and web_search and github are disabled
        val enabledConfig = ChatMcpToolConfig(
            disabledToolIds = setOf("github", "web_search")
        )
        val resolvedWithEnabled = resolver.resolve("profile-1", chatToolConfig = enabledConfig)
        assertEquals(listOf("calculate_expression", "current_date", "read_file_slice", "read_url"), resolvedWithEnabled.map { it.modelToolName })
    }

    @Test
    fun `only profile-selected marketplace searches join and the chat search switch covers them`() = runBlocking {
        val searchSchema = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
        val toolsResponse = """{"tools":[{"name":"firecrawl_search","inputSchema":$searchSchema},{"name":"brave_web_search","inputSchema":$searchSchema},{"name":"read_url","inputSchema":{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}}]}"""
        McpClientManagerTest.McpFixtureServer(toolsResponse = toolsResponse).use { server ->
            val dao = ResolverFakeToolConnectionDao()
            val vault = ResolverFakeSecretVault()
            val repository = ToolConnectionRepository(dao, vault)
            val networkClient = NetworkClient(CIO)
            val manager = McpClientManager(networkClient())
            try {
                val resolver = AgentToolResolver(
                    toolConnectionRepository = repository,
                    settingRepository = ResolverFakeSettingRepository(),
                    secretVault = vault,
                    networkClient = networkClient,
                    mcpClientManager = manager,
                    mcpOAuthCoordinator = McpOAuthCoordinator(McpOAuthClient(networkClient()), repository, vault, manager),
                    deviceLocationTool = DeviceLocationTool(mockk(relaxed = true), mockk(relaxed = true))
                )
                val connection = connection("marketplace", ToolConnectionType.MCP, endpointUrl = server.url, authType = ToolConnectionAuthType.NONE, allowCleartext = true)
                dao.bind(connection, binding("profile-1", "marketplace", "firecrawl_search", bindingUid = "search"))
                dao.bind(connection, binding("profile-1", "marketplace", "read_url", bindingUid = "reader"))
                val enabled = resolver.resolve("profile-1")
                assertTrue(enabled.any { it.realToolName == "firecrawl_search" && it.isWebSearchEngine() })
                assertFalse(enabled.any { it.realToolName == "brave_web_search" })
                val disabled = resolver.resolve("profile-1", chatToolConfig = ChatMcpToolConfig(disabledToolIds = setOf("web_search")))
                assertFalse(disabled.any { it.isWebSearchEngine() })
                assertTrue(disabled.any { it.connectionUid == "marketplace" && it.realToolName == "read_url" })
                val connectionDisabled = resolver.resolve("profile-1", chatToolConfig = ChatMcpToolConfig(disabledToolIds = setOf("marketplace")))
                assertFalse(connectionDisabled.any { it.connectionUid == "marketplace" })
            } finally {
                manager.closeAll()
                networkClient().close()
            }
        }
    }

    @Test
    fun `resolve filters out disabled MCP tools via connection-qualified name or real tool name`() = runBlocking {
        McpClientManagerTest.McpFixtureServer().use { server ->
            val dao = ResolverFakeToolConnectionDao()
            val vault = ResolverFakeSecretVault()
            val repository = ToolConnectionRepository(dao, vault)
            val networkClient = NetworkClient(CIO)
            val manager = McpClientManager(networkClient())
            val resolver = AgentToolResolver(
                toolConnectionRepository = repository,
                settingRepository = ResolverFakeSettingRepository(),
                secretVault = vault,
                networkClient = networkClient,
                mcpClientManager = manager,
                mcpOAuthCoordinator = McpOAuthCoordinator(McpOAuthClient(networkClient()), repository, vault, manager),
                deviceLocationTool = DeviceLocationTool(mockk(relaxed = true), mockk(relaxed = true))
            )
            dao.bind(
                connection(
                    uid = "mcp-1",
                    type = ToolConnectionType.MCP,
                    endpointUrl = server.url,
                    authType = ToolConnectionAuthType.NONE,
                    allowCleartext = true
                ),
                binding("profile-1", "mcp-1", "echo")
            )

            // Filter out by exact candidate ID "mcp-1:echo"
            val config1 = ChatMcpToolConfig(disabledToolIds = setOf("mcp-1:echo"))
            val resolved1 = resolver.resolve("profile-1", chatToolConfig = config1)
            assertEquals(withGitHubFacades(listOf("calculate_expression", "current_date", "github", "read_file_slice", "read_url", "web_search")), resolved1.map { it.modelToolName })

            // Filter out by modelToolName "mcp__mcp-1__echo"
            val config2 = ChatMcpToolConfig(disabledToolIds = setOf("mcp__mcp-1__echo"))
            val resolved2 = resolver.resolve("profile-1", chatToolConfig = config2)
            assertEquals(withGitHubFacades(listOf("calculate_expression", "current_date", "github", "read_file_slice", "read_url", "web_search")), resolved2.map { it.modelToolName })

            // Filter out by entire connection uid "mcp-1"
            val config3 = ChatMcpToolConfig(disabledToolIds = setOf("mcp-1"))
            val resolved3 = resolver.resolve("profile-1", chatToolConfig = config3)
            assertEquals(withGitHubFacades(listOf("calculate_expression", "current_date", "github", "read_file_slice", "read_url", "web_search")), resolved3.map { it.modelToolName })

            // Allowed when tool is enabled
            val configEnabled = ChatMcpToolConfig()
            val resolvedEnabled = resolver.resolve("profile-1", chatToolConfig = configEnabled)
            assertEquals(withGitHubFacades(listOf("calculate_expression", "current_date", "github", "mcp__mcp-1__echo", "read_file_slice", "read_url", "web_search")), resolvedEnabled.map { it.modelToolName })

            manager.closeAll()
            networkClient().close()
        }
    }

    @Test
    fun `Amazon remains absent until this profile explicitly opts in`() = runBlocking {
        val dao = ResolverFakeToolConnectionDao()
        dao.upsertConnection(connection("shopping", ToolConnectionType.AMAZON_SERPAPI))
        val settings = ResolverFakeSettingRepository()
        val resolver = resolver(dao = dao, settings = settings)
        assertFalse(resolver.resolve("profile").any { it.connectionUid == "shopping" })
        settings.features = settings.features.withToolPluginEnabled(ToolPluginId.AMAZON_SEARCH, true)
        assertFalse(resolver.resolve("profile").any { it.connectionUid == "shopping" })
        settings.features = settings.features.withProfileToolPluginEnabled("profile", ToolPluginId.AMAZON_SEARCH, true)
        assertEquals(2, resolver.resolve("profile").count { it.connectionUid == "shopping" })
        assertFalse(resolver.resolve("other").any { it.connectionUid == "shopping" })
        settings.features = settings.features.withToolPluginEnabled(ToolPluginId.AMAZON_SEARCH, false)
        assertFalse(resolver.resolve("profile").any { it.connectionUid == "shopping" })
    }

    @Test
    fun `Jan Nafta tools require Amazon activation and independent profile consent`() = runBlocking {
        val toolNames = listOf("search_products", "get_product", "get_price_history", "get_deals", "get_buy_link", "compare_marketplaces", "add_price_watch", "list_price_watches", "remove_price_watch")
        val toolsResponse = "{\"tools\":[${toolNames.joinToString(",") { "{\"name\":\"$it\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}" }}]}"
        McpClientManagerTest.McpFixtureServer(toolsResponse = toolsResponse).use { server ->
            val dao = ResolverFakeToolConnectionDao()
            val settings = ResolverFakeSettingRepository()
            val connection = connection("amazon", ToolConnectionType.MCP, endpointUrl = server.url, authType = ToolConnectionAuthType.NONE, allowCleartext = true)
                .copy(name = "Amazon Search · Jan Nafta MCP", alias = "amazon_jannafta")
            toolNames.forEach { name ->
                dao.bind(connection, binding("one", "amazon", name))
                dao.bind(connection, binding("two", "amazon", name))
            }
            val resolver = resolver(dao = dao, settings = settings)
            assertFalse(resolver.resolve("one").any { it.connectionUid == "amazon" })
            settings.features = settings.features.withToolPluginEnabled(ToolPluginId.AMAZON_SEARCH, true)
            assertFalse(resolver.resolve("one").any { it.connectionUid == "amazon" })
            assertTrue(server.methods.isEmpty())

            settings.features = settings.features.withProfileToolPluginEnabled("one", ToolPluginId.AMAZON_SEARCH, true)
            val available = resolver.resolve("one").filter { it.connectionUid == "amazon" }
            assertEquals(toolNames.toSet(), available.map { it.realToolName }.toSet())
            assertTrue(available.all { it.tool.definition.description.contains(AmazonJanNaftaTool.MARKER) })
            assertFalse(resolver.resolve("two").any { it.connectionUid == "amazon" })
            val held = available.single { it.realToolName == "list_price_watches" }.tool

            settings.features = settings.features.withProfileToolPluginEnabled("one", ToolPluginId.AMAZON_SEARCH, false)
                .withProfileToolPluginEnabled("two", ToolPluginId.AMAZON_SEARCH, true)
                .copy(pluginExecution = mapOf(ToolPluginId.AMAZON_SEARCH to PluginExecutionSettings(maxOutputCharacters = 1000)))
            assertTrue(held.execute("profile-disabled", buildJsonObject { put("text", "watches") }).isError)
            val other = resolver.resolve("two").single { it.realToolName == "list_price_watches" }.tool
            assertEquals("watches", (other.execute("other-profile", buildJsonObject { put("text", "watches") }).content as ToolResultContent.Text).text)
            val limited = (other.execute("bounded-history", buildJsonObject { put("text", "w".repeat(1500)) }).content as ToolResultContent.Text).text
            assertTrue(limited.startsWith("w".repeat(1000)))
            assertTrue(limited.length < 1500 && limited.contains("Output limited by this plugin's settings"))
            settings.features = settings.features.withToolPluginEnabled(ToolPluginId.AMAZON_SEARCH, false)
            assertTrue(other.execute("globally-disabled", buildJsonObject { put("text", "watches") }).isError)
            assertFalse(resolver.resolve("two").any { it.connectionUid == "amazon" })
        }
    }

    @Test
    fun `profile disables built in and bundled native tools independently`() = runBlocking {
        val registry = mockk<NativeMarketplaceRegistry>()
        val client = mockk<NativeMarketplaceClient>()
        coEvery { registry.load() } returns mapOf(
            "optional-openstreetmap" to NativePluginInstallation(
                enabled = true,
                endpoints = mapOf("geocode" to "https://maps.example/search", "restrooms" to "https://maps.example/interpreter")
            )
        )
        val settings = ResolverFakeSettingRepository(
            features = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings()
                .withProfileToolPluginEnabled("one", ToolPluginId.CALCULATOR, false)
                .withProfileToolPluginEnabled("one", ToolPluginId.nativeOperation("optional-openstreetmap", "geocode"), false)
        )
        val resolver = resolver(settings = settings, nativeRegistry = registry, nativeClient = client)
        val first = resolver.resolve("one")
        assertFalse(first.any { it.realToolName == "calculate_expression" || it.realToolName == "geocode" })
        assertTrue(first.any { it.realToolName == "restrooms" })
        val second = resolver.resolve("two")
        assertTrue(second.any { it.realToolName == "calculate_expression" })
        assertEquals(2, second.count { it.connectionUid == "optional-openstreetmap" })
        assertFalse(resolver.resolve("two", ChatMcpToolConfig().withToolDisabled("optional-nominatim")).any { it.realToolName == "geocode" })
        settings.features = settings.features.withProfileToolPluginEnabled("one", "service:openstreetmap", false)
        assertFalse(resolver.resolve("one").any { it.connectionUid == "optional-openstreetmap" })
        assertEquals(2, resolver.resolve("two").count { it.connectionUid == "optional-openstreetmap" })
    }

    @Test
    fun `profile service switch blocks already resolved MCP calls and preserves another profile`() = runBlocking {
        McpClientManagerTest.McpFixtureServer().use { server ->
            val dao = ResolverFakeToolConnectionDao()
            val settings = ResolverFakeSettingRepository()
            val connection = connection("mcp-1", ToolConnectionType.MCP, endpointUrl = server.url, authType = ToolConnectionAuthType.NONE, allowCleartext = true).copy(name = "Brave MCP")
            dao.bind(connection, binding("one", "mcp-1", "echo"))
            dao.bind(connection, binding("two", "mcp-1", "echo"))
            val resolver = resolver(dao = dao, settings = settings)
            val held = resolver.resolve("one").single { it.connectionUid == "mcp-1" }
            settings.features = settings.features.withProfileToolPluginEnabled("one", "service:brave", false)
            assertFalse(resolver.resolve("one").any { it.connectionUid == "mcp-1" })
            assertTrue(held.tool.execute("disabled", buildJsonObject { put("text", "hello") }).isError)
            val other = resolver.resolve("two").single { it.connectionUid == "mcp-1" }
            assertFalse(other.tool.execute("allowed", buildJsonObject { put("text", "hello") }).isError)
            settings.features = settings.features.withToolPluginEnabled("service:brave", false)
            assertFalse(resolver.resolve("two").any { it.connectionUid == "mcp-1" })
            assertTrue(other.tool.execute("global-off", buildJsonObject { put("text", "hello") }).isError)
        }
    }

    @Test
    fun `provider switches remain independent from similarly named built in plugins`() = runBlocking {
        val features = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings()
            .withToolPluginEnabled(ToolPluginId.WEB_SEARCH, false)
            .withToolPluginEnabled(ToolPluginId.READ_URL, false)
        val settings = ResolverFakeSettingRepository(features = features)
        val dao = ResolverFakeToolConnectionDao()
        dao.bind(connection("search", ToolConnectionType.EXA), binding("profile", "search", "web_search"))
        val resolver = resolver(dao = dao, settings = settings)
        assertTrue(resolver.resolve("profile").any { it.connectionUid == "search" })
        settings.features = features.withProfileToolPluginEnabled("profile", "service:exa", false)
        assertFalse(resolver.resolve("profile").any { it.connectionUid == "search" })

        McpClientManagerTest.McpFixtureServer(toolsResponse = """{"tools":[{"name":"read_url","description":"Read on the server","inputSchema":{"type":"object","properties":{}}}]}""").use { server ->
            dao.bind(connection("reader", ToolConnectionType.MCP, endpointUrl = server.url, authType = ToolConnectionAuthType.NONE, allowCleartext = true), binding("profile", "reader", "read_url"))
            val tools = resolver.resolve("profile")
            assertFalse(tools.any { it.modelToolName == "read_url" })
            assertTrue(tools.any { it.modelToolName == "mcp__reader__read_url" })
            settings.features = settings.features.withProfileToolPluginEnabled("profile", ToolPluginId.connection("reader"), false)
            assertFalse(resolver.resolve("profile").any { it.connectionUid == "reader" })
        }
    }

    private fun withGitHubFacades(names: List<String>): List<String> =
        if ("github" !in names) names else (names + GitHubOperationTool.names).distinct().sorted() + dev.chungjungsoo.gptmobile.data.agent.ProgressiveToolCatalog.NAME

    @Test fun nativeRecoveryUsesRegistryGrantsAndIgnoresUsageCounters() = runBlocking {
        val registry = mockk<NativeMarketplaceRegistry>()
        val client = mockk<NativeMarketplaceClient>()
        val profile = PlatformV2(uid = "profile", name = "Researcher")
        val settings = ResolverFakeSettingRepository(listOf(profile))
        var installation = NativePluginInstallation(enabled = true, endpoints = mapOf("geocode" to "https://maps.example/search", "restrooms" to "https://maps.example/interpreter"))
        coEvery { registry.load() } answers { mapOf("optional-openstreetmap" to installation) }
        val held = resolver(settings = settings, nativeRegistry = registry, nativeClient = client).resolve(profile.uid).single { it.realToolName == "geocode" }
        assertTrue(requireNotNull(held.canReuseResult).invoke())
        installation = installation.copy(usageCount = 8, nextRequestAt = 3000, usageDay = "2026-10-21")
        assertTrue(requireNotNull(held.canReuseResult).invoke())
        val accepted = installation
        installation = accepted.copy(endpoints = accepted.endpoints + ("geocode" to "https://another.example/search"))
        assertFalse(requireNotNull(held.canReuseResult).invoke())
        installation = accepted.copy(enabled = false)
        assertFalse(requireNotNull(held.canReuseResult).invoke())
        installation = accepted
        settings.features = settings.features.withProfileToolPluginEnabled(profile.uid, "service:openstreetmap", false)
        assertFalse(requireNotNull(held.canReuseResult).invoke())
    }

    @Test fun connectedSearchRecoveryRespectsItsProviderGrantIndependentlyOfBuiltInSearch() = runBlocking {
        val profile = PlatformV2(uid = "profile", name = "Researcher")
        val settings = ResolverFakeSettingRepository(listOf(profile), dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings().withToolPluginEnabled(ToolPluginId.WEB_SEARCH, false))
        val dao = ResolverFakeToolConnectionDao()
        dao.bind(connection("search", ToolConnectionType.EXA), binding(profile.uid, "search", "web_search"))
        val held = resolver(dao = dao, settings = settings).resolve(profile.uid).single { it.connectionUid == "search" }
        assertTrue(requireNotNull(held.canReuseResult).invoke())
        settings.features = settings.features.withProfileToolPluginEnabled(profile.uid, "service:exa", false)
        assertFalse(requireNotNull(held.canReuseResult).invoke())
    }

    @Test fun mcpSearchRecoveryStillRequiresApprovedReadAndUnchangedConnection() = runBlocking {
        val schema = """{"tools":[{"name":"web_search","description":"Search","inputSchema":{"type":"object","properties":{}}}]}"""
        McpClientManagerTest.McpFixtureServer(toolsResponse = schema).use { server ->
            val profile = PlatformV2(uid = "profile", name = "Researcher")
            val settings = ResolverFakeSettingRepository(listOf(profile))
            val dao = ResolverFakeToolConnectionDao()
            val connection = connection("mcp-search", ToolConnectionType.MCP, server.url, authType = ToolConnectionAuthType.NONE, allowCleartext = true).copy(approvedReadTools = "web_search")
            val binding = binding(profile.uid, connection.connectionUid, "web_search")
            dao.bind(connection, binding)
            val held = resolver(dao = dao, settings = settings).resolve(profile.uid).single { it.connectionUid == connection.connectionUid }
            assertTrue(requireNotNull(held.canReuseResult).invoke())
            dao.bind(connection.copy(approvedReadTools = ""), binding)
            assertFalse(requireNotNull(held.canReuseResult).invoke())
        }
    }

    @Test fun smallGithubSchemasFollowAcceptedRepositoryIntentAcrossShortCommandsOnlyWithinTheConversation() = runBlocking {
        val profile = PlatformV2(uid = "profile", name = "Writer")
        val settings = ResolverFakeSettingRepository(listOf(profile))
        val state = dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore()
        suspend fun advertised(text: String, chatId: Int = 7, discover: List<String> = emptyList()): Set<String> {
            val available = resolver(settings = settings, gitHubState = state).resolve(profile.uid, userMessage = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(id = text.hashCode().and(Int.MAX_VALUE), chatId = chatId, content = text, platformType = null))
            val directory = requireNotNull(available.firstNotNullOfOrNull { it.progressiveCatalog })
            if (discover.isNotEmpty()) {
                assertFalse(directory.execute("discover", buildJsonObject { put("names", JsonArray(discover.map(::JsonPrimitive))) }).isError)
            }
            return directory.selected(available.map { it.tool.definition }).mapNotNull { GitHubOperationTool.operationName(it.name) }.toSet()
        }
        assertEquals(setOf("github_context", "github_search", "github_read"), advertised("Inspect this repository"))
        assertEquals(setOf("github_context", "github_search", "github_read"), advertised("continue"))
        assertFalse("github_stage_patch" in advertised("fix it"))
        assertTrue("github_stage_patch" in advertised("fix it", discover = listOf("github_stage_patch")))
        assertFalse("github_commit" in advertised("create a branch"))
        assertTrue("github_commit" in advertised("create a branch", discover = listOf("github_commit")))
        assertFalse("github_commit" in advertised("push"))
        assertTrue("github_commit" in advertised("push", discover = listOf("github_commit")))
        assertTrue(advertised("hi").isEmpty())
        assertTrue(advertised("continue", chatId = 8).isEmpty())
    }

    @Test fun githubCredentialReplacementRevokesAnAlreadyResolvedToolEvenWhenMetadataIsUnchanged() = runBlocking {
        val profile = PlatformV2(uid = "profile", name = "Writer")
        val settings = ResolverFakeSettingRepository(listOf(profile))
        val dao = ResolverFakeToolConnectionDao()
        val connection = connection("github_api", ToolConnectionType.GITHUB, "https://api.github.com", "stable-reference")
        dao.upsertConnection(connection)
        val vault = ResolverFakeSecretVault(mapOf("stable-reference" to "first-token".encodeToByteArray()))
        val available = resolver(dao = dao, vault = vault, settings = settings).resolve(profile.uid, userMessage = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(id = 1, chatId = 7, content = "Fix the GitHub repository and push", platformType = null))
        val write = available.first { it.realToolName == "github_write" }
        assertTrue(requireNotNull(write.canReuseResult).invoke())
        vault.put("stable-reference", "replacement-token".encodeToByteArray())
        assertEquals(connection, dao.getConnection(connection.connectionUid))
        assertFalse(requireNotNull(write.canReuseResult).invoke())
        assertTrue(vault.lastReadBytes!!.all { it == 0.toByte() })
        vault.delete("stable-reference")
        assertFalse(requireNotNull(write.canReuseResult).invoke())
    }

    @Test fun longDiagnosticsRetainDiscoverableWritesAcrossContinuation() = runBlocking {
        val profile = PlatformV2(uid = "profile", name = "Writer")
        val settings = ResolverFakeSettingRepository(listOf(profile))
        val state = dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore()
        val dao = ResolverFakeToolConnectionDao()
        dao.upsertConnection(connection("github_api", ToolConnectionType.GITHUB, "https://api.github.com", "github-token"))
        val vault = ResolverFakeSecretVault(mapOf("github-token" to "test-user-token".encodeToByteArray()))
        val resolver = resolver(dao = dao, vault = vault, settings = settings, gitHubState = state)
        suspend fun advertised(text: String, chatId: Int = 7, discover: Boolean = false): Set<String> {
            val available = resolver.resolve(profile.uid, userMessage = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(id = text.hashCode().and(Int.MAX_VALUE), chatId = chatId, content = text, platformType = null))
            val directory = available.firstNotNullOfOrNull { it.progressiveCatalog } ?: return emptySet()
            if (discover) {
                assertFalse(directory.execute("discover", buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive("github_write__github_api"), JsonPrimitive("github_commit__github_api")))) }).isError)
            }
            return directory.selected(available.map { it.tool.definition }).filter { GitHubOperationTool.operationName(it.name) != null }.map { it.name }.toSet()
        }
        val task = "Inspect the GitHub repository diagnostic report.\n" + "A retained diagnostic observation. ".repeat(200) + "\nRepair the failures, commit the reviewed changes and push."
        assertTrue(task.indexOf("Repair") > 4096)
        val initial = advertised(task)
        assertFalse("github_write__github_api" in initial)
        assertFalse("github_commit__github_api" in initial)
        val discovered = advertised(task, discover = true)
        assertTrue("github_write__github_api" in discovered)
        assertTrue("github_commit__github_api" in discovered)
        val continued = advertised("continue", discover = true)
        assertTrue("github_write must remain discoverable after task compaction", "github_write__github_api" in continued)
        assertTrue("github_commit__github_api" in continued)
        assertTrue(advertised("continue", chatId = 8).isEmpty())
        settings.features = settings.features.withToolPluginEnabled(ToolPluginId.GITHUB, false)
        assertTrue(advertised("continue", discover = true).isEmpty())
    }

    @Test fun telemetryEnabledStillPublishesCompactGithubOperationsAndHonorsRevocation() = runBlocking {
        val profile = PlatformV2(uid = "profile", name = "Writer")
        val settings = ResolverFakeSettingRepository(listOf(profile))
        val resolver = resolver(settings = settings, statistics = mockk(relaxed = true))
        for (task in listOf("check repo status", "show git branches", "inspect commits")) {
            val tools = resolver.resolve(profile.uid, userMessage = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(content = task, platformType = null))
            assertTrue(tools.any { it.realToolName == "github_context" })
            val catalog = requireNotNull(tools.firstNotNullOfOrNull { it.progressiveCatalog })
            val schemas = catalog.selected(tools.map { it.tool.definition })
            assertTrue(schemas.any { it.name == "github_context" })
            assertFalse(schemas.any { it.name == "github" })
            assertTrue(schemas.sumOf { dev.chungjungsoo.gptmobile.data.agent.ProgressiveToolCatalog.schemaTokens(it) } < 2000)
        }
        settings.features = settings.features.withToolPluginEnabled(ToolPluginId.GITHUB, false)
        assertFalse(resolver.resolve(profile.uid).any { it.nativeGitHub })
    }

    @Test fun cachedAuthorizedOAuthCatalogIsResolvedBeforeCredentialRefreshAndRevocationStillWins() = runBlocking {
        McpClientManagerTest.McpFixtureServer().use { server ->
            val connection = connection("oauth-cache", ToolConnectionType.MCP, server.url, "unavailable-oauth", ToolConnectionAuthType.OAUTH, allowCleartext = true)
            val dao = ResolverFakeToolConnectionDao()
            dao.bind(connection, binding("profile", connection.connectionUid, "echo"))
            val network = NetworkClient(CIO)
            val manager = McpClientManager(network())
            try {
                val cached = manager.listTools(McpConnectionConfig(connection.connectionUid, server.url, true))
                assertTrue(cached.any { it.name == "echo" })
                val vault = ResolverFakeSecretVault(readError = IllegalStateException("OAuth credential acquisition must not run for cached schema discovery"))
                val repository = ToolConnectionRepository(dao, vault)
                val settings = ResolverFakeSettingRepository(listOf(PlatformV2(uid = "profile", name = "Writer")))
                val resolver = AgentToolResolver(repository, settings, vault, network, manager, McpOAuthCoordinator(McpOAuthClient(network()), repository, vault, manager), DeviceLocationTool(mockk(relaxed = true), mockk(relaxed = true)))
                assertEquals(cached, resolver.discoverMcpTools(connection))
                assertTrue(resolver.resolve("profile", userMessage = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(content = "Use echo", platformType = null), cachedCataloguesOnly = true).any { it.realToolName == "echo" })
                settings.features = settings.features.withToolPluginEnabled(ToolPluginId.connection(connection.connectionUid), false)
                assertFalse(resolver.resolve("profile", cachedCataloguesOnly = true).any { it.connectionUid == connection.connectionUid })
            } finally {
                manager.closeAll()
            }
        }
    }

    private fun amazonEnabled() = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings()
        .withToolPluginEnabled(ToolPluginId.AMAZON_SEARCH, true)
        .withProfileToolPluginEnabled("profile", ToolPluginId.AMAZON_SEARCH, true)

    private fun resolver(
        dao: ResolverFakeToolConnectionDao = ResolverFakeToolConnectionDao(),
        vault: ResolverFakeSecretVault = ResolverFakeSecretVault(),
        settings: SettingRepository = ResolverFakeSettingRepository(),
        facts: dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository? = null,
        consent: dev.chungjungsoo.gptmobile.data.permissions.FreeModelToolConsentStore? = null,
        nativeRegistry: NativeMarketplaceRegistry? = null,
        nativeClient: NativeMarketplaceClient? = null,
        amazonFree: AmazonHtmlProvider? = null,
        history: dev.chungjungsoo.gptmobile.data.amazon.AmazonHistoryRepository? = null,
        access: dev.chungjungsoo.gptmobile.data.amazon.AmazonAccessPolicy? = null,
        news: PublicNewsClient? = null,
        airbnb: PublicAirbnbClient? = null,
        healthReader: dev.chungjungsoo.gptmobile.data.health.HealthDataReader? = null,
        healthConsent: dev.chungjungsoo.gptmobile.data.health.HealthConnectConsentStore? = null,
        gitHubState: dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore? = null,
        statistics: dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsRepository? = null
    ): AgentToolResolver {
        val repository = ToolConnectionRepository(dao, vault)
        val networkClient = NetworkClient(CIO)
        val manager = McpClientManager(networkClient())
        return AgentToolResolver(
            toolConnectionRepository = repository,
            settingRepository = settings,
            secretVault = vault,
            networkClient = networkClient,
            mcpClientManager = manager,
            mcpOAuthCoordinator = McpOAuthCoordinator(McpOAuthClient(networkClient()), repository, vault, manager),
            deviceLocationTool = DeviceLocationTool(mockk(relaxed = true), mockk(relaxed = true)),
            factVault = facts,
            freeModelToolConsentStore = consent,
            nativeMarketplaceRegistry = nativeRegistry,
            nativeMarketplaceClient = nativeClient,
            amazonFreeProvider = amazonFree,
            amazonHistory = history,
            amazonAccess = access,
            publicNews = news,
            publicAirbnb = airbnb,
            healthReader = healthReader,
            healthConsent = healthConsent,
            gitHubRepositoryState = gitHubState,
            statistics = statistics
        )
    }

    private fun connection(
        uid: String,
        type: String,
        endpointUrl: String? = "https://$uid.example/search",
        secretRef: String? = null,
        authType: String = ToolConnectionAuthType.BEARER,
        allowCleartext: Boolean = false
    ) = ToolConnection(
        connectionUid = uid,
        name = "Search ${uid.substringAfterLast("-")}",
        alias = uid,
        type = type,
        endpointUrl = endpointUrl,
        authType = authType,
        secretRef = secretRef,
        oauthClientId = null,
        allowCleartext = allowCleartext
    )

    private fun binding(
        profileUid: String,
        connectionUid: String?,
        toolName: String,
        bindingUid: String = "$profileUid-${connectionUid ?: "builtin"}-$toolName"
    ) = AgentToolBinding(
        bindingUid = bindingUid,
        profileUid = profileUid,
        connectionUid = connectionUid,
        toolName = toolName
    )

    private fun AgentTool.webSearchConfig(): WebSearchProviderConfig {
        assertEquals(WebSearchTool::class.java, javaClass)
        val field = WebSearchTool::class.java.getDeclaredField("config")
        field.isAccessible = true
        return field.get(this) as WebSearchProviderConfig
    }
}

private class ResolverFakeSettingRepository(
    private val profiles: List<PlatformV2> = emptyList(),
    var features: dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings()
) : SettingRepository {
    override suspend fun getFeatureSettings() = features
    override fun observeFeatureSettings(): Flow<dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings> = flowOf(features)

    override suspend fun fetchProviderConnections(): List<ProviderConnection> = emptyList()
    override fun observeProviderConnections(): Flow<List<ProviderConnection>> = kotlinx.coroutines.flow.flowOf(emptyList())
    override suspend fun getProviderConnection(uid: String): ProviderConnection? = null
    override suspend fun addProviderConnection(connection: ProviderConnection, credential: String?): ProviderConnection = error("Provider writes are not used by this fixture")
    override suspend fun updateProviderConnection(connection: ProviderConnection, credential: String?): ProviderConnection = error("Provider writes are not used by this fixture")
    override suspend fun deleteProviderConnection(connection: ProviderConnection): Boolean = error("Provider writes are not used by this fixture")

    override suspend fun fetchPlatforms(): List<Platform> = emptyList()
    override suspend fun fetchPlatformV2s(): List<PlatformV2> = profiles
    override fun observePlatformV2s(): Flow<List<PlatformV2>> = flowOf(emptyList())
    override fun observePlatformV2ByUid(uid: String): Flow<PlatformV2?> = flowOf(profiles.firstOrNull { it.uid == uid })
    override suspend fun fetchThemes(): ThemeSetting = ThemeSetting()
    override suspend fun getLocalRuntimeBackend(): LocalRuntimeBackend = LocalRuntimeBackend.QUALCOMM_QNN
    override suspend fun updateLocalRuntimeBackend(backend: LocalRuntimeBackend) = Unit
    override suspend fun getDebugMode(): Boolean = false
    override suspend fun updateDebugMode(enabled: Boolean) = Unit
    override fun observeDebugMode(): Flow<Boolean> = flowOf(false)
    override suspend fun migrateToPlatformV2() = Unit
    override suspend fun migrateSecrets(): List<SecretMigrationError> = emptyList()
    override suspend fun updatePlatforms(platforms: List<Platform>) = Unit
    override suspend fun updateThemes(themeSetting: ThemeSetting) = Unit
    override suspend fun getFavoriteGroups(): List<String> = emptyList()
    override suspend fun saveFavoriteGroups(groups: List<String>) = Unit
    override fun observeFavoriteGroups(): Flow<List<String>> = flowOf(emptyList())
    override suspend fun getFavoriteMessageGroups(): Map<Int, String> = emptyMap()
    override suspend fun saveFavoriteMessageGroups(messageGroups: Map<Int, String>) = Unit
    override fun observeFavoriteMessageGroups(): Flow<Map<Int, String>> = flowOf(emptyMap())
    override suspend fun addPlatformV2(platform: PlatformV2) = Unit
    override suspend fun updatePlatformV2(platform: PlatformV2) = Unit
    override suspend fun deletePlatformV2(platform: PlatformV2) = Unit
    override suspend fun getPlatformV2ById(id: Int): PlatformV2? = null
    override suspend fun exportConfigurationJson(): String = "{}"
    override suspend fun importConfigurationJson(json: String): Result<Int> = Result.success(0)
}

private class ResolverFakeSecretVault(
    values: Map<String, ByteArray> = emptyMap(),
    private val readError: Throwable? = null
) : SecretVault {
    private val values = values.mapValues { it.value.copyOf() }.toMutableMap()
    var lastReadBytes: ByteArray? = null

    override suspend fun put(secretRef: String, secret: ByteArray) {
        values[secretRef] = secret.copyOf()
    }

    override suspend fun read(secretRef: String): ByteArray? {
        readError?.let { throw it }
        return values[secretRef]?.copyOf()?.also { lastReadBytes = it }
    }

    override suspend fun delete(secretRef: String) {
        values.remove(secretRef)?.fill(0)
    }

    fun value(secretRef: String): ByteArray? = values[secretRef]?.copyOf()
}

private class ResolverFakeToolConnectionDao : ToolConnectionDao {
    private val connections = mutableMapOf<String, ToolConnection>()
    private val bindings = mutableMapOf<String, AgentToolBinding>()

    fun bind(connection: ToolConnection?, binding: AgentToolBinding) {
        connection?.let { connections[it.connectionUid] = it }
        bindings[binding.bindingUid] = binding
    }

    override fun observeConnections(): kotlinx.coroutines.flow.Flow<List<ToolConnection>> = kotlinx.coroutines.flow.flow { emit(listConnections()) }

    override suspend fun listConnections(): List<ToolConnection> = connections.values.sortedWith(
        compareBy<ToolConnection> { it.name }
            .thenBy { it.alias }
            .thenBy { it.connectionUid }
    )

    override suspend fun getConnection(connectionUid: String): ToolConnection? = connections[connectionUid]

    override suspend fun getAllConnections(): List<ToolConnection> = listConnections()

    override suspend fun getConnectionsByUids(connectionUids: List<String>): List<ToolConnection> = connectionUids.mapNotNull(connections::get)

    override suspend fun upsertConnection(connection: ToolConnection) {
        connections[connection.connectionUid] = connection
    }

    override suspend fun deleteConnectionByUid(connectionUid: String) {
        connections.remove(connectionUid)
        bindings.values.removeAll { it.connectionUid == connectionUid }
    }

    override suspend fun listBindingsByProfile(profileUid: String): List<AgentToolBinding> = bindings.values
        .filter { it.profileUid == profileUid }
        .sortedWith(compareBy<AgentToolBinding> { it.toolName }.thenBy { it.connectionUid ?: "" }.thenBy { it.bindingUid })

    override suspend fun insertBinding(binding: AgentToolBinding) {
        bindings[binding.bindingUid] = binding
    }

    override suspend fun deleteConnectionToolBindingsForTypes(
        profileUid: String,
        toolName: String,
        connectionTypes: List<String>
    ) {
        bindings.values.removeAll { binding ->
            binding.profileUid == profileUid &&
                binding.toolName == toolName &&
                binding.connectionUid?.let { connections[it]?.type in connectionTypes } == true
        }
    }

    override suspend fun deleteBuiltInToolBinding(profileUid: String, toolName: String) {
        bindings.values.removeAll { it.profileUid == profileUid && it.toolName == toolName && it.connectionUid == null }
    }

    override suspend fun deleteConnectionBindingsForType(profileUid: String, connectionType: String) {
        bindings.values.removeAll { binding ->
            binding.profileUid == profileUid &&
                binding.connectionUid?.let { connections[it]?.type == connectionType } == true
        }
    }

    override suspend fun listBindingsWithConnections(profileUid: String): List<AgentToolBindingWithConnection> = listBindingsByProfile(profileUid).map { binding ->
        AgentToolBindingWithConnection(binding, binding.connectionUid?.let(connections::get))
    }
}
