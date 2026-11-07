package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.agent.tool.MCP_OAUTH_SCHEME
import dev.chungjungsoo.gptmobile.data.agent.tool.McpBackoffException
import dev.chungjungsoo.gptmobile.data.agent.tool.McpClientManager
import dev.chungjungsoo.gptmobile.data.agent.tool.McpConnectionHealth
import dev.chungjungsoo.gptmobile.data.agent.tool.McpConnectionHealthState
import dev.chungjungsoo.gptmobile.data.agent.tool.McpOAuthClient
import dev.chungjungsoo.gptmobile.data.agent.tool.McpOAuthClientTest
import dev.chungjungsoo.gptmobile.data.agent.tool.McpOAuthCoordinator
import dev.chungjungsoo.gptmobile.data.agent.tool.McpOAuthCredential
import dev.chungjungsoo.gptmobile.data.database.dao.ToolConnectionDao
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.ktor.client.engine.cio.CIO
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolConnectionsViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun mcpProbeShowsTheTypedRecoveryActionAndRetainsTheOriginalError() = runBlocking {
        val connection = ToolConnection("mcp", "MCP", "mcp", ToolConnectionType.MCP, "https://server.example/mcp", ToolConnectionAuthType.NONE, null, null)
        val dao = FakeToolConnectionDao()
        dao.upsertConnection(connection)
        val snapshots = listOf(
            McpConnectionHealth(state = McpConnectionHealthState.UNREACHABLE, lastFailureAtMs = 1_000, nextRetryAtMs = Long.MAX_VALUE, lastErrorCode = "TLS_FAILURE", lastError = "TLS authentication handshake failed"),
            McpConnectionHealth(
                state = McpConnectionHealthState.UNREACHABLE,
                lastFailureAtMs = 1_000,
                nextRetryAtMs = Long.MAX_VALUE,
                lastErrorCode = "MCP_DISCOVERY_SCHEMA_INVALID",
                lastError = "supportedVersions must be nonempty",
                lastHttpStatus = 400,
                lastValidationRule = "PROTOCOL_VERSIONS_NONEMPTY_REQUIRED",
                lastValidationField = "supportedVersions",
                lastDispatchEvidence = "CONFIRMED",
                failureConfigurationRevision = "a".repeat(64)
            ),
            McpConnectionHealth(state = McpConnectionHealthState.AUTHENTICATION_REQUIRED, lastFailureAtMs = 1_000, nextRetryAtMs = Long.MAX_VALUE, lastErrorCode = "MCP_AUTHENTICATION_REQUIRED", lastError = "The saved credential was rejected"),
            McpConnectionHealth(state = McpConnectionHealthState.DEGRADED, lastFailureAtMs = 1_000, nextRetryAtMs = 30_000, lastErrorCode = "DNS_FAILED", lastError = "The MCP hostname could not be resolved")
        )
        var observed = snapshots.first()
        val manager = mockk<McpClientManager>()
        every { manager.healthSnapshot(connection.connectionUid) } answers { observed }
        val resolver = mockk<AgentToolResolver>()
        coEvery { resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true, passiveBackoff = false) } coAnswers {
            throw McpBackoffException(connection.connectionUid, requireNotNull(observed.nextRetryAtMs))
        }
        val settings = fakeSettingRepository()
        every { settings.observeFeatureSettings() } returns kotlinx.coroutines.flow.flowOf(AppFeatureSettings())
        val model = ToolConnectionsViewModel(dao, FakeSecretVault(), mockk(relaxed = true), manager, settings, agentToolResolver = resolver)
        try {
            withTimeout(5000) { model.uiState.first { it.connections.any { it.connectionUid == connection.connectionUid } } }
            for (snapshot in snapshots) {
                observed = snapshot
                model.probeConnection(connection)
                val state = withTimeout(5000) { model.uiState.first { it.connectionHealth[connection.connectionUid]?.errorCode == snapshot.lastErrorCode } }
                val shown = state.connectionHealth.getValue(connection.connectionUid)
                assertEquals(if (snapshot.state == McpConnectionHealthState.AUTHENTICATION_REQUIRED) ToolConnectionHealthStatus.LIMITED else ToolConnectionHealthStatus.OFFLINE, shown.status)
                assertEquals(snapshot.failureRetryable, shown.retryable)
                assertTrue(shown.message.contains(requireNotNull(snapshot.lastError)))
                assertTrue(shown.message.contains(requireNotNull(snapshot.lastErrorCode)))
                assertEquals(snapshot.lastFailureAtMs, shown.observedAtEpochMs)
                assertEquals(snapshot.failureConfigurationRevision, shown.configurationRevision)
                assertEquals(snapshot.lastHttpStatus, shown.httpStatus)
                assertEquals(snapshot.lastValidationRule, shown.validationRule)
                assertEquals(snapshot.lastValidationField, shown.validationField)
                if (snapshot.failureRetryable == false) {
                    assertTrue(shown.message.contains("reconnect"))
                    assertFalse(shown.message.contains("backoff", ignoreCase = true))
                    assertFalse(shown.message.contains(Long.MAX_VALUE.toString()))
                } else {
                    assertTrue(shown.message.contains("backoff to expire"))
                }
            }
            coVerify(exactly = 4) { resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true, passiveBackoff = false) }
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test fun mcpCheckRevalidatesTheSavedRowAfterEnteringIo() = runBlocking {
        val connection = mcpConnectionForCheck()
        val backing = FakeToolConnectionDao().apply { upsertConnection(connection) }
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val dao = object : ToolConnectionDao by backing {
            override suspend fun getConnection(connectionUid: String): ToolConnection? {
                readStarted.complete(Unit)
                releaseRead.await()
                return backing.getConnection(connectionUid)
            }
        }
        val resolver = mockk<AgentToolResolver>()
        val model = toolConnectionsModelForCheck(dao, resolver = resolver)
        try {
            withTimeout(5000) { model.uiState.first { it.connections == listOf(connection) } }
            model.probeConnection(connection)
            withTimeout(5000) { readStarted.await() }
            backing.upsertConnection(connection.copy(endpointUrl = "https://changed.example/mcp"))
            releaseRead.complete(Unit)
            val state = withTimeout(5000) { model.uiState.first { it.errorMessage?.contains("saved connection changed") == true } }
            assertNull(state.connectionHealth[connection.connectionUid])
            coVerify(exactly = 0) { resolver.discoverMcpTools(any(), any(), any(), any()) }
        } finally {
            releaseRead.complete(Unit)
            model.viewModelScope.cancel()
        }
    }

    @Test fun credentialReplacementAfterTheReviewedSnapshotPreventsMcpDispatch() = runBlocking {
        val connection = mcpConnectionForCheck().copy(authType = ToolConnectionAuthType.BEARER, secretRef = "connection_mcp")
        val dao = FakeToolConnectionDao().apply { upsertConnection(connection) }
        val vault = RevisionedConnectionTestVault().apply { put("connection_mcp", "first".encodeToByteArray()) }
        val resolver = mockk<AgentToolResolver>()
        val model = toolConnectionsModelForCheck(dao, vault, resolver)
        try {
            withTimeout(5000) { model.uiState.first { it.connections == listOf(connection) } }
            vault.put("connection_mcp", "replacement".encodeToByteArray())
            model.probeConnection(connection)
            val state = withTimeout(5000) { model.uiState.first { it.errorMessage?.contains("saved connection changed") == true } }
            assertNull(state.connectionHealth[connection.connectionUid])
            coVerify(exactly = 0) { resolver.discoverMcpTools(any(), any(), any(), any()) }
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test fun unknownCredentialRevisionProducesAnHonestLocalResult() = runBlocking {
        val connection = mcpConnectionForCheck().copy(authType = ToolConnectionAuthType.BEARER, secretRef = "connection_mcp")
        val dao = FakeToolConnectionDao().apply { upsertConnection(connection) }
        val vault = FakeSecretVault().apply { put("connection_mcp", "saved".encodeToByteArray()) }
        val resolver = mockk<AgentToolResolver>()
        val model = toolConnectionsModelForCheck(dao, vault, resolver)
        try {
            withTimeout(5000) { model.uiState.first { it.connections == listOf(connection) } }
            model.probeConnection(connection)
            val shown = model.uiState.value.connectionHealth.getValue(connection.connectionUid)
            assertEquals(ToolConnectionHealthStatus.LIMITED, shown.status)
            assertEquals("PLUGIN_CREDENTIAL_IDENTITY_UNAVAILABLE", shown.errorCode)
            assertEquals("NOT_DISPATCHED", shown.dispatchEvidence)
            assertNull(shown.configurationRevision)
            assertTrue(shown.message.contains("Save credentials or sign in"))
            coVerify(exactly = 0) { resolver.discoverMcpTools(any(), any(), any(), any()) }
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test fun aCompletedMcpCheckIsInvalidatedWhenTheSavedEndpointChanges() = runBlocking {
        val connection = mcpConnectionForCheck()
        val dao = FakeToolConnectionDao().apply { upsertConnection(connection) }
        val resolver = mockk<AgentToolResolver>()
        coEvery { resolver.discoverMcpTools(any(), any(), any(), any()) } returns emptyList()
        val model = toolConnectionsModelForCheck(dao, resolver = resolver)
        try {
            withTimeout(5000) { model.uiState.first { it.connections == listOf(connection) } }
            model.probeConnection(connection)
            withTimeout(5000) { model.uiState.first { it.connectionHealth[connection.connectionUid]?.toolCount == 0 } }
            val changed = connection.copy(endpointUrl = "https://changed.example/mcp")
            dao.upsertConnection(changed)
            model.refresh()
            val refreshed = withTimeout(5000) { model.uiState.first { it.connections == listOf(changed) } }
            assertNull(refreshed.connectionHealth[connection.connectionUid])
            coVerify(exactly = 1) { resolver.discoverMcpTools(any(), any(), any(), any()) }
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test fun anOlderMcpCheckCannotReplaceHealthFromNewCredentials() = runBlocking {
        val connection = mcpConnectionForCheck().copy(authType = ToolConnectionAuthType.BEARER, secretRef = "connection_mcp")
        val dao = FakeToolConnectionDao().apply { upsertConnection(connection) }
        val vault = RevisionedConnectionTestVault().apply { put("connection_mcp", "first".encodeToByteArray()) }
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val resolver = mockk<AgentToolResolver>()
        var calls = 0
        coEvery { resolver.discoverMcpTools(any(), any(), any(), any()) } coAnswers {
            if (++calls == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                throw IllegalStateException("Old endpoint failure")
            }
            emptyList()
        }
        val model = toolConnectionsModelForCheck(dao, vault, resolver)
        try {
            withTimeout(5000) { model.uiState.first { it.connections == listOf(connection) } }
            val existingJobs = model.viewModelScope.coroutineContext.job.children.toSet()
            model.probeConnection(connection)
            withTimeout(5000) { firstStarted.await() }
            val olderCheck = model.viewModelScope.coroutineContext.job.children.single { it !in existingJobs }
            vault.put("connection_mcp", "replacement".encodeToByteArray())
            model.refresh()
            withTimeout(5000) { model.uiState.first { it.connectionHealth[connection.connectionUid] == null } }
            model.probeConnection(connection)
            val newerHealth = withTimeout(5000) { model.uiState.first { it.connectionHealth[connection.connectionUid]?.toolCount == 0 } }
                .connectionHealth.getValue(connection.connectionUid)
            assertTrue(newerHealth.configurationRevision?.matches(Regex("[a-f0-9]{64}")) == true)
            releaseFirst.complete(Unit)
            withTimeout(5000) { olderCheck.join() }
            assertEquals(newerHealth, model.uiState.value.connectionHealth[connection.connectionUid])
            coVerify(exactly = 2) { resolver.discoverMcpTools(any(), any(), any(), any()) }
        } finally {
            releaseFirst.complete(Unit)
            model.viewModelScope.cancel()
        }
    }

    @Test fun anMcpResultIsDiscardedWhenItsCredentialChangesWithoutARefresh() = runBlocking {
        val connection = mcpConnectionForCheck().copy(authType = ToolConnectionAuthType.BEARER, secretRef = "connection_mcp")
        val dao = FakeToolConnectionDao().apply { upsertConnection(connection) }
        val vault = RevisionedConnectionTestVault().apply { put("connection_mcp", "first".encodeToByteArray()) }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolver = mockk<AgentToolResolver>()
        coEvery { resolver.discoverMcpTools(any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            release.await()
            emptyList()
        }
        val model = toolConnectionsModelForCheck(dao, vault, resolver)
        try {
            withTimeout(5000) { model.uiState.first { it.connections == listOf(connection) } }
            model.probeConnection(connection)
            withTimeout(5000) { started.await() }
            vault.put("connection_mcp", "replacement".encodeToByteArray())
            release.complete(Unit)
            val state = withTimeout(5000) { model.uiState.first { it.errorMessage?.contains("saved connection changed") == true } }
            assertNull(state.connectionHealth[connection.connectionUid])
            coVerify(exactly = 1) { resolver.discoverMcpTools(any(), any(), any(), any()) }
        } finally {
            release.complete(Unit)
            model.viewModelScope.cancel()
        }
    }

    @Test fun auditSavePersistsWithoutDiscoveryUntilTheExplicitCheck() = runBlocking {
        val dao = FakeToolConnectionDao()
        val vault = RevisionedConnectionTestVault()
        val resolver = mockk<AgentToolResolver>()
        coEvery { resolver.discoverMcpTools(any(), any(), any(), any()) } returns emptyList()
        val model = toolConnectionsModelForCheck(dao, vault, resolver)
        try {
            val saved = CompletableDeferred<Unit>()
            model.saveConnection(
                existing = null,
                provider = ToolConnectionsViewModel.providers.first { it.type == ToolConnectionType.MCP },
                name = "MCP", alias = "mcp", endpointUrl = "https://server.example/mcp",
                authType = ToolConnectionAuthType.BEARER, credential = "saved-token", oauthClientId = "",
                allowCleartext = false, clearCredential = false, onSuccess = { saved.complete(Unit) }, probeAfterSave = false
            )
            withTimeout(5000) { saved.await() }
            val connection = withTimeout(5000) { model.uiState.first { it.connections.size == 1 } }.connections.single()
            assertTrue(connection.secretRef != null)
            assertTrue(model.uiState.value.connectionHealth.isEmpty())
            coVerify(exactly = 0) { resolver.discoverMcpTools(any(), any(), any(), any()) }
            model.probeConnection(connection)
            withTimeout(5000) { model.uiState.first { it.connectionHealth[connection.connectionUid]?.toolCount == 0 } }
            coVerify(exactly = 1) { resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true, passiveBackoff = false) }

            val updated = CompletableDeferred<Unit>()
            model.saveConnection(
                existing = connection,
                provider = ToolConnectionsViewModel.providers.first { it.type == ToolConnectionType.MCP },
                name = "MCP", alias = "mcp", endpointUrl = "https://server.example/mcp",
                authType = ToolConnectionAuthType.BEARER, credential = "replacement-token", oauthClientId = "",
                allowCleartext = false, clearCredential = false, onSuccess = { updated.complete(Unit) }, probeAfterSave = false
            )
            withTimeout(5000) { updated.await() }
            assertNull(model.uiState.value.connectionHealth[connection.connectionUid])
            coVerify(exactly = 1) { resolver.discoverMcpTools(any(), any(), any(), any()) }
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test fun ordinarySaveChecksThePersistedCredentialReference() = runBlocking {
        val dao = FakeToolConnectionDao()
        val resolver = mockk<AgentToolResolver>()
        coEvery { resolver.discoverMcpTools(any(), any(), any(), any()) } returns emptyList()
        val model = toolConnectionsModelForCheck(dao, RevisionedConnectionTestVault(), resolver)
        try {
            model.saveConnection(
                existing = null,
                provider = ToolConnectionsViewModel.providers.first { it.type == ToolConnectionType.MCP },
                name = "MCP", alias = "mcp", endpointUrl = "https://server.example/mcp",
                authType = ToolConnectionAuthType.BEARER, credential = "saved-token", oauthClientId = "",
                allowCleartext = false, clearCredential = false
            )
            withTimeout(5000) { model.uiState.first { it.connectionHealth.values.any { health -> health.toolCount == 0 } } }
            val connection = dao.listConnections().single()
            assertTrue(connection.secretRef != null)
            coVerify(exactly = 1) { resolver.discoverMcpTools(connection, forceRefresh = true, awaitCompletion = true, passiveBackoff = false) }
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test fun anOlderSaveCannotAuthorizeACheckOfANewerReadOnlySave() = runBlocking {
        val connection = mcpConnectionForCheck()
        val dao = FakeToolConnectionDao().apply { upsertConnection(connection) }
        val firstCloseStarted = CompletableDeferred<Unit>()
        val releaseFirstClose = CompletableDeferred<Unit>()
        val firstSaved = CompletableDeferred<Unit>()
        val secondSaved = CompletableDeferred<Unit>()
        val manager = mockk<McpClientManager>(relaxed = true)
        var closes = 0
        coEvery { manager.close(connection.connectionUid) } coAnswers {
            if (++closes == 1) {
                firstCloseStarted.complete(Unit)
                releaseFirstClose.await()
            }
        }
        val resolver = mockk<AgentToolResolver>()
        val model = toolConnectionsModelForCheck(dao, resolver = resolver, manager = manager)
        try {
            withTimeout(5000) { model.uiState.first { it.connections == listOf(connection) } }
            model.saveConnection(
                existing = connection,
                provider = ToolConnectionsViewModel.providers.first { it.type == ToolConnectionType.MCP },
                name = "MCP", alias = "mcp", endpointUrl = "https://first.example/mcp",
                authType = ToolConnectionAuthType.NONE, credential = "", oauthClientId = "",
                allowCleartext = false, clearCredential = false, onSuccess = { firstSaved.complete(Unit) }
            )
            withTimeout(5000) { firstCloseStarted.await() }
            model.saveConnection(
                existing = requireNotNull(dao.getConnection(connection.connectionUid)),
                provider = ToolConnectionsViewModel.providers.first { it.type == ToolConnectionType.MCP },
                name = "MCP", alias = "mcp", endpointUrl = "https://second.example/mcp",
                authType = ToolConnectionAuthType.NONE, credential = "", oauthClientId = "",
                allowCleartext = false, clearCredential = false, onSuccess = { secondSaved.complete(Unit) }, probeAfterSave = false
            )
            withTimeout(5000) { secondSaved.await() }
            releaseFirstClose.complete(Unit)
            withTimeout(5000) { firstSaved.await() }
            assertEquals("https://second.example/mcp", dao.getConnection(connection.connectionUid)?.endpointUrl)
            assertNull(model.uiState.value.connectionHealth[connection.connectionUid])
            coVerify(exactly = 0) { resolver.discoverMcpTools(any(), any(), any(), any()) }
        } finally {
            releaseFirstClose.complete(Unit)
            model.viewModelScope.cancel()
        }
    }

    @Test fun auditSignInDoesNotDispatchForARowChangedDuringPermission() = runBlocking {
        val connection = mcpConnectionForCheck().copy(authType = ToolConnectionAuthType.OAUTH)
        val dao = FakeToolConnectionDao().apply { upsertConnection(connection) }
        val coordinator = mockk<McpOAuthCoordinator>()
        val model = toolConnectionsModelForCheck(dao, coordinator = coordinator)
        try {
            withTimeout(5000) { model.uiState.first { it.connections == listOf(connection) } }
            dao.upsertConnection(connection.copy(endpointUrl = "https://changed.example/mcp"))
            model.startOAuth(connection.connectionUid, expectedConnection = connection)
            val state = withTimeout(5000) { model.uiState.first { !it.isOAuthBusy && it.errorMessage?.contains("saved connection changed") == true } }
            assertTrue(state.errorMessage.orEmpty().contains("Refresh"))
            coVerify(exactly = 0) { coordinator.begin(any(), any(), any()) }
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test fun oauthAndBrowserErrorsAreRedactedBeforeTheyReachConnectionUi() = runBlocking {
        val connection = mcpConnectionForCheck().copy(authType = ToolConnectionAuthType.OAUTH)
        val dao = FakeToolConnectionDao().apply { upsertConnection(connection) }
        val coordinator = mockk<McpOAuthCoordinator>()
        val secret = "private-token-".repeat(300)
        val error = "OAuth failed at https://auth.example/callback?code=private-code&state=private-state Authorization: Bearer $secret"
        coEvery { coordinator.begin(any(), any(), any()) } throws IllegalStateException(error)
        val model = toolConnectionsModelForCheck(dao, coordinator = coordinator)
        try {
            withTimeout(5000) { model.uiState.first { it.connections == listOf(connection) } }
            model.startOAuth(connection.connectionUid, expectedConnection = connection)
            val state = withTimeout(5000) { model.uiState.first { !it.isOAuthBusy && it.errorMessage != null } }
            for (secretPart in listOf("private-code", "private-state", "private-token")) assertFalse(state.errorMessage.orEmpty().contains(secretPart))
            assertTrue(state.errorMessage.orEmpty().length <= 2048)
            coVerify(exactly = 1) { coordinator.begin(connection.connectionUid, any(), connection) }
            model.failOAuthLaunch(error)
            for (secretPart in listOf("private-code", "private-state", "private-token")) assertFalse(model.uiState.value.errorMessage.orEmpty().contains(secretPart))
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test fun firstRunGitHubSetupCreatesOneConnectionAndStartsSecureBrowserAuthorization() = runBlocking {
        val dao = FakeToolConnectionDao()
        val auth = mockk<dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator>()
        val features = kotlinx.coroutines.flow.MutableStateFlow(AppFeatureSettings())
        val settings = fakeSettingRepository()
        coEvery { settings.getFeatureSettings() } answers { features.value }
        coEvery { settings.mutateFeatureSettings(any()) } coAnswers {
            features.value = firstArg<(AppFeatureSettings) -> AppFeatureSettings>()(features.value)
            features.value
        }
        every { auth.canAuthorize(any()) } returns true
        coEvery { auth.pendingConnectionUid() } returns null
        coEvery { auth.canResumeAuthorization() } returns false
        coEvery { auth.connectedAccount(any()) } returns null
        coEvery { auth.begin(any()) } returns "https://github.com/login/oauth/authorize?client_id=public-project"
        val model = ToolConnectionsViewModel(dao, FakeSecretVault(), mockk(relaxed = true), mockk(relaxed = true), settings, gitHubPublicAuth = auth)
        try {
            val launch = async(start = CoroutineStart.UNDISPATCHED) { model.oauthLaunches.first() }
            model.connectGitHub("")
            model.connectGitHub("")
            assertTrue(withTimeout(5000) { launch.await() }.startsWith("https://github.com/login/oauth/authorize"))
            val pending = withTimeout(5000) { model.uiState.first { it.gitHubPublicSignInUid != null } }
            val connection = pending.connections.single()
            assertEquals("GITHUB", connection.type)
            assertEquals("OAUTH", connection.authType)
            assertEquals("https://api.github.com", connection.endpointUrl)
            assertNull(connection.secretRef)
            assertEquals(connection.connectionUid, pending.gitHubPublicSignInUid)
            model.connectGitHub("")
            coVerify(exactly = 1) { auth.begin(connection.connectionUid) }
            assertTrue(features.value.toolPluginStates[dev.chungjungsoo.gptmobile.data.model.ToolPluginId.GITHUB] != false)
            assertNull(gitHubModeConnection(pending))
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test fun missingPublicOAuthConfigurationDoesNotLeaveAnOrphanConnection() = runBlocking {
        val dao = FakeToolConnectionDao()
        val auth = mockk<dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator>(relaxed = true)
        every { auth.canAuthorize(any()) } returns false
        coEvery { auth.pendingConnectionUid() } returns null
        val model = ToolConnectionsViewModel(dao, FakeSecretVault(), mockk(relaxed = true), mockk(relaxed = true), fakeSettingRepository(), gitHubPublicAuth = auth)
        try {
            model.connectGitHub("not a client id")
            val failed = withTimeout(5000) { model.uiState.first { it.errorMessage != null && !it.isOAuthBusy } }
            assertTrue(failed.errorMessage.orEmpty().contains("OAuth client ID"))
            assertTrue(failed.connections.isEmpty())
            coVerify(exactly = 0) { auth.begin(any()) }
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test fun projectGitHubAuthorizationUsesBrowserAndRestoresConnectedAccount() = runBlocking {
        val dao = FakeToolConnectionDao()
        val vault = FakeSecretVault()
        val connection = ToolConnection("github", "GitHub", "github", "GITHUB", null, "OAUTH", null, null)
        dao.upsertConnection(connection)
        val auth = mockk<dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator>()
        val account = dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator.Account("1", "coder")
        val callback = "https://auth.example.org/oauth/github/complete?ticket=opaque-one-use-ticket&attempt=bound-attempt"
        var pending: String? = null
        var connected = false
        coEvery { auth.pendingConnectionUid() } answers { pending }
        coEvery { auth.canResumeAuthorization() } returns false
        coEvery { auth.connectedAccount("github") } answers { if (connected) account else null }
        every { auth.canAuthorize(connection) } returns true
        every { auth.acceptsCallback(callback) } returns true
        coEvery { auth.begin("github") } coAnswers {
            pending = "github"
            "https://github.com/login/oauth/authorize?client_id=public-project"
        }
        coEvery { auth.complete(callback) } coAnswers {
            pending = null
            connected = true
            account
        }
        val model = ToolConnectionsViewModel(dao, vault, mockk(relaxed = true), mockk(relaxed = true), fakeSettingRepository(), gitHubPublicAuth = auth)
        try {
            withTimeout(5000) { model.uiState.first { it.connections.isNotEmpty() } }
            val launch = async(start = CoroutineStart.UNDISPATCHED) { model.oauthLaunches.first() }
            model.startOAuth("github")
            assertTrue(withTimeout(5000) { launch.await() }.startsWith("https://github.com/login/oauth/authorize"))
            assertEquals("github", model.uiState.value.gitHubPublicSignInUid)
            assertEquals(null, model.uiState.value.gitHubSignIn)
            model.completeOAuthCallback(callback)
            val completed = withTimeout(5000) { model.uiState.first { it.gitHubAccounts["github"] == account && !it.isOAuthBusy } }
            assertEquals(null, completed.gitHubPublicSignInUid)
            coVerify(exactly = 1) { auth.complete(callback) }
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test fun retainedAuthorizationTicketCanFinishWithoutStartingAnotherBrowserAttempt() = runBlocking {
        val dao = FakeToolConnectionDao()
        dao.upsertConnection(ToolConnection("github", "GitHub", "github", "GITHUB", null, "OAUTH", null, null))
        val auth = mockk<dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator>()
        val account = dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator.Account("1", "coder")
        var connected = false
        coEvery { auth.pendingConnectionUid() } answers { if (connected) null else "github" }
        coEvery { auth.canResumeAuthorization() } answers { !connected }
        coEvery { auth.connectedAccount("github") } answers { account.takeIf { connected } }
        coEvery { auth.resumePendingAuthorization() } coAnswers {
            connected = true
            account
        }
        val model = ToolConnectionsViewModel(dao, FakeSecretVault(), mockk(relaxed = true), mockk(relaxed = true), fakeSettingRepository(), gitHubPublicAuth = auth)
        try {
            withTimeout(5000) { model.uiState.first { it.gitHubAuthorizationResumable } }
            model.resumeGitHubAuthorization()
            withTimeout(5000) { model.uiState.first { it.gitHubAccounts["github"] == account && !it.isOAuthBusy } }
            coVerify(exactly = 1) { auth.resumePendingAuthorization() }
            coVerify(exactly = 0) { auth.begin(any()) }
            assertFalse(model.uiState.value.gitHubAuthorizationResumable)
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test
    fun `provider metadata records the credential transport`() {
        val providers = ToolConnectionsViewModel.providers.associateBy { it.type }

        assertEquals(ToolConnectionAuthType.BEARER, providers.getValue(ToolConnectionType.FIRECRAWL).authType)
        assertEquals(ToolConnectionAuthType.BEARER, providers.getValue(ToolConnectionType.PERPLEXITY).authType)
        assertEquals(ToolConnectionAuthType.API_KEY, providers.getValue(ToolConnectionType.EXA).authType)
        assertEquals(ToolConnectionAuthType.API_KEY, providers.getValue(ToolConnectionType.BRAVE).authType)
        assertEquals(ToolConnectionAuthType.NONE, providers.getValue(ToolConnectionType.MCP).authType)
    }

    @Test
    fun `new connection setup starts with exactly the two high level paths`() {
        val flow = ToolConnectionSetupFlow()

        assertEquals(ToolConnectionSetupStep.CONNECTION_TYPE, flow.step)
        assertEquals(
            listOf(ToolConnectionSetupPath.WEB_SEARCH, ToolConnectionSetupPath.MCP_SERVER),
            ToolConnectionSetupPath.entries
        )
    }

    @Test
    fun `web search setup progresses through provider then relevant details`() {
        val provider = ToolConnectionsViewModel.providers.first { it.type == ToolConnectionType.EXA }

        val selected = ToolConnectionSetupFlow().selectPath(ToolConnectionSetupPath.WEB_SEARCH)
        val providerStep = selected.next()
        val detailsStep = providerStep.selectWebProvider(provider).next()

        assertEquals(ToolConnectionSetupStep.CONNECTION_TYPE, selected.step)
        assertEquals(ToolConnectionSetupPath.WEB_SEARCH, selected.path)
        assertEquals(ToolConnectionSetupStep.WEB_SEARCH_PROVIDER, providerStep.step)
        assertFalse(providerStep.canContinue)
        assertEquals(ToolConnectionSetupStep.DETAILS, detailsStep.step)
        assertEquals(provider, detailsStep.provider)
        assertTrue(detailsStep.isSaveStep)
        assertEquals(ToolConnectionSetupStep.WEB_SEARCH_PROVIDER, detailsStep.back().step)
        assertEquals(ToolConnectionSetupStep.CONNECTION_TYPE, providerStep.back().step)
    }

    @Test
    fun `MCP setup separates connection details from authentication`() {
        val selected = ToolConnectionSetupFlow().selectPath(ToolConnectionSetupPath.MCP_SERVER)
        val detailsStep = selected.next()
        val authenticationStep = detailsStep.next()

        assertEquals(ToolConnectionSetupStep.CONNECTION_TYPE, selected.step)
        assertEquals(ToolConnectionSetupPath.MCP_SERVER, selected.path)
        assertEquals(ToolConnectionSetupStep.DETAILS, detailsStep.step)
        assertEquals(ToolConnectionType.MCP, detailsStep.provider?.type)
        assertEquals(ToolConnectionSetupStep.AUTHENTICATION, authenticationStep.step)
        assertTrue(authenticationStep.isSaveStep)
        assertEquals(ToolConnectionSetupStep.DETAILS, authenticationStep.back().step)
        assertEquals(ToolConnectionSetupStep.CONNECTION_TYPE, detailsStep.back().step)
    }

    @Test
    fun `editing keeps a stored connection type instead of falling back to another provider`() {
        val stored = ToolConnection(
            connectionUid = "search-edit",
            name = "Search",
            alias = "search",
            type = ToolConnectionType.EXA,
            endpointUrl = "https://api.exa.ai/search",
            authType = ToolConnectionAuthType.API_KEY,
            secretRef = null,
            oauthClientId = null
        )

        val known = ToolConnectionSetupFlow.editing(stored)

        assertEquals(ToolConnectionType.EXA, known?.provider?.type)
        assertEquals(ToolConnectionSetupPath.WEB_SEARCH, known?.path)
        assertNull(ToolConnectionSetupFlow.editing(stored.copy(type = "UNSUPPORTED_PROVIDER")))
    }

    @Test
    fun `editing connection metadata preserves its saved tool permission policy`() {
        val stored = ToolConnection(
            connectionUid = "policy-edit",
            name = "Docs",
            alias = "docs",
            type = ToolConnectionType.MCP,
            endpointUrl = "https://example.org/mcp",
            authType = ToolConnectionAuthType.NONE,
            secretRef = null,
            oauthClientId = null,
            toolPolicy = "ASK_ALWAYS",
            approvedReadTools = "search_docs,read_page"
        )
        val edited = stored.copy(name = "Updated docs").preservingPolicyFrom(stored)

        assertEquals("Updated docs", edited.name)
        assertEquals("ASK_ALWAYS", edited.toolPolicy)
        assertEquals("search_docs,read_page", edited.approvedReadTools)
    }

    @Test
    fun `normalizeAlias keeps aliases lowercase model safe and validates boundaries`() {
        assertEquals("fire_crawl_1", ToolConnectionsViewModel.normalizeAlias(" Fire-Crawl 1 "))

        assertTrue(ToolConnectionsViewModel.isValidAlias("exa_search"))
        assertTrue(ToolConnectionsViewModel.isValidAlias("a1234567890123456789012345678901"))
        assertFalse(ToolConnectionsViewModel.isValidAlias(""))
        assertFalse(ToolConnectionsViewModel.isValidAlias("1exa"))
        assertFalse(ToolConnectionsViewModel.isValidAlias("exa-search"))
        assertFalse(ToolConnectionsViewModel.isValidAlias("a12345678901234567890123456789012"))
    }

    @Test
    fun `normalizeAlias is independent of the device locale`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            assertEquals("internal", ToolConnectionsViewModel.normalizeAlias("INTERNAL"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `credentialInput preserves blank edit credentials unless explicit clear is selected`() {
        assertNull(ToolConnectionsViewModel.credentialInput("   ", clearCredential = false))
        assertEquals("new-key", ToolConnectionsViewModel.credentialInput(" new-key ", clearCredential = false)?.decodeToString())
        assertNull(ToolConnectionsViewModel.credentialInput("new-key", clearCredential = true))
    }

    @Test
    fun `credential editor distinguishes keep replace clear and missing states`() {
        assertEquals(
            CredentialEditState.KEEP,
            credentialEditState(
                hasExistingCredential = true,
                canPreserveCredential = true,
                credential = "",
                clearCredential = false
            )
        )
        assertEquals(
            CredentialEditState.REPLACE,
            credentialEditState(
                hasExistingCredential = true,
                canPreserveCredential = true,
                credential = "replacement",
                clearCredential = false
            )
        )
        assertEquals(
            CredentialEditState.CLEAR,
            credentialEditState(
                hasExistingCredential = true,
                canPreserveCredential = true,
                credential = "",
                clearCredential = true
            )
        )
        assertEquals(
            CredentialEditState.MISSING,
            credentialEditState(
                hasExistingCredential = true,
                canPreserveCredential = false,
                credential = "",
                clearCredential = false
            )
        )
        assertEquals(
            CredentialEditState.MISSING,
            credentialEditState(
                hasExistingCredential = false,
                canPreserveCredential = false,
                credential = "",
                clearCredential = true
            )
        )
    }

    @Test
    fun `provider change with blank credential clears old credential`() {
        assertTrue(
            ToolConnectionsViewModel.shouldClearCredential(
                existingType = ToolConnectionType.FIRECRAWL,
                providerType = ToolConnectionType.EXA,
                credential = " ",
                clearCredential = false
            )
        )
        assertFalse(
            ToolConnectionsViewModel.shouldClearCredential(
                existingType = ToolConnectionType.FIRECRAWL,
                providerType = ToolConnectionType.EXA,
                credential = "new-key",
                clearCredential = false
            )
        )
        assertFalse(
            ToolConnectionsViewModel.shouldClearCredential(
                existingType = ToolConnectionType.FIRECRAWL,
                providerType = ToolConnectionType.FIRECRAWL,
                credential = " ",
                clearCredential = false
            )
        )
    }

    @Test
    fun `MCP endpoint schemes are case insensitive`() {
        assertTrue(ToolConnectionsViewModel.isValidMcpEndpoint("HTTPS://example.com/mcp", allowCleartext = false))
        assertTrue(ToolConnectionsViewModel.isValidMcpEndpoint("HTTP://example.com/mcp", allowCleartext = true))
        assertFalse(ToolConnectionsViewModel.isValidMcpEndpoint("HTTP://example.com/mcp", allowCleartext = false))
    }

    @Test
    fun `save completion runs after persistence succeeds`() = runTest {
        val dao = FakeToolConnectionDao()
        val vault = FakeSecretVault()
        val networkClient = NetworkClient(CIO)
        val manager = McpClientManager(networkClient())
        val repository = ToolConnectionRepository(dao, vault)
        val coordinator = McpOAuthCoordinator(McpOAuthClient(networkClient()), repository, vault, manager)
        val viewModel = ToolConnectionsViewModel(dao, vault, coordinator, manager, fakeSettingRepository())
        var completed = false

        viewModel.saveConnection(
            existing = null,
            provider = ToolConnectionsViewModel.providers.first { it.type == ToolConnectionType.FIRECRAWL },
            name = "Search",
            alias = "search",
            endpointUrl = "",
            authType = ToolConnectionAuthType.BEARER,
            credential = "key",
            oauthClientId = "",
            allowCleartext = false,
            clearCredential = false,
            onSuccess = { completed = true }
        )

        assertTrue(completed)
        assertEquals(1, dao.listConnections().size)
        manager.closeAll()
        networkClient().close()
    }

    @Test
    fun `browser launch failure survives an older refresh until explicit retry`() = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val connection = ToolConnection("mcp", "MCP", "mcp", ToolConnectionType.MCP, "https://example.org/mcp", ToolConnectionAuthType.NONE, null, null)
        val dao = object : ToolConnectionDao by FakeToolConnectionDao() {
            override suspend fun listConnections(): List<ToolConnection> {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
                return listOf(connection)
            }
        }
        val settings = fakeSettingRepository()
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(remoteMcpConnections = false)
        val viewModel = ToolConnectionsViewModel(dao, FakeSecretVault(), mockk(relaxed = true), mockk(relaxed = true), settings)
        try {
            withTimeout(5000) { refreshStarted.await() }
            viewModel.failOAuthLaunch()
            assertFalse(viewModel.uiState.value.isOAuthBusy)
            val error = "No browser is available for OAuth authorization."
            assertEquals(error, viewModel.uiState.value.errorMessage)

            releaseRefresh.complete(Unit)
            val refreshed = withTimeout(5000) { viewModel.uiState.first { it.connections.any { item -> item.connectionUid == connection.connectionUid } } }
            assertEquals(error, refreshed.errorMessage)

            viewModel.refresh()
            assertNull(viewModel.uiState.value.errorMessage)
        } finally {
            releaseRefresh.complete(Unit)
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `blank credential is cleared when saved metadata changes`() = runTest {
        val dao = FakeToolConnectionDao()
        val vault = FakeSecretVault()
        val networkClient = NetworkClient(CIO)
        val manager = McpClientManager(networkClient())
        val repository = ToolConnectionRepository(dao, vault)
        val coordinator = McpOAuthCoordinator(McpOAuthClient(networkClient()), repository, vault, manager)
        val existing = ToolConnection(
            connectionUid = "search-1",
            name = "Search",
            alias = "search",
            type = ToolConnectionType.FIRECRAWL,
            endpointUrl = "https://api.firecrawl.dev/v2/search",
            authType = ToolConnectionAuthType.BEARER,
            secretRef = "connection_search-1",
            oauthClientId = null
        )
        dao.upsertConnection(existing)
        vault.put("connection_search-1", "old-key".encodeToByteArray())
        val viewModel = ToolConnectionsViewModel(dao, vault, coordinator, manager, fakeSettingRepository())

        viewModel.saveConnection(
            existing = existing,
            provider = ToolConnectionsViewModel.providers.first { it.type == ToolConnectionType.PERPLEXITY },
            name = "Search",
            alias = "search",
            endpointUrl = "",
            authType = ToolConnectionAuthType.BEARER,
            credential = " ",
            oauthClientId = "",
            allowCleartext = false,
            clearCredential = false
        )

        assertNull(dao.getConnection("search-1")!!.secretRef)
        assertNull(vault.read("connection_search-1"))
        manager.closeAll()
        networkClient().close()
    }

    @Test
    fun `explicit credential clear removes saved secret without replacement`() = runTest {
        val dao = FakeToolConnectionDao()
        val vault = FakeSecretVault()
        val networkClient = NetworkClient(CIO)
        val manager = McpClientManager(networkClient())
        val repository = ToolConnectionRepository(dao, vault)
        val coordinator = McpOAuthCoordinator(McpOAuthClient(networkClient()), repository, vault, manager)
        val existing = ToolConnection(
            connectionUid = "search-clear",
            name = "Search",
            alias = "search",
            type = ToolConnectionType.FIRECRAWL,
            endpointUrl = "https://api.firecrawl.dev/v2/search",
            authType = ToolConnectionAuthType.BEARER,
            secretRef = "connection_search-clear",
            oauthClientId = null
        )
        dao.upsertConnection(existing)
        vault.put("connection_search-clear", "saved-secret".encodeToByteArray())
        val viewModel = ToolConnectionsViewModel(dao, vault, coordinator, manager, fakeSettingRepository())

        viewModel.saveConnection(
            existing = existing,
            provider = ToolConnectionsViewModel.providers.first { it.type == ToolConnectionType.FIRECRAWL },
            name = "Search",
            alias = "search",
            endpointUrl = "",
            authType = ToolConnectionAuthType.BEARER,
            credential = "",
            oauthClientId = "",
            allowCleartext = false,
            clearCredential = true
        )

        assertNull(dao.getConnection("search-clear")!!.secretRef)
        assertNull(vault.read("connection_search-clear"))
        manager.closeAll()
        networkClient().close()
    }

    @Test
    fun `explicit MCP bearer clear removes saved token`() = runTest {
        val dao = FakeToolConnectionDao()
        val vault = FakeSecretVault()
        val networkClient = NetworkClient(CIO)
        val manager = McpClientManager(networkClient())
        val repository = ToolConnectionRepository(dao, vault)
        val coordinator = McpOAuthCoordinator(McpOAuthClient(networkClient()), repository, vault, manager)
        val existing = ToolConnection(
            connectionUid = "mcp-clear",
            name = "MCP",
            alias = "mcp",
            type = ToolConnectionType.MCP,
            endpointUrl = "https://example.com/mcp",
            authType = ToolConnectionAuthType.BEARER,
            secretRef = "connection_mcp-clear",
            oauthClientId = null
        )
        dao.upsertConnection(existing)
        vault.put("connection_mcp-clear", "saved-token".encodeToByteArray())
        val viewModel = ToolConnectionsViewModel(dao, vault, coordinator, manager, fakeSettingRepository())

        viewModel.saveConnection(
            existing = existing,
            provider = ToolConnectionsViewModel.providers.first { it.type == ToolConnectionType.MCP },
            name = "MCP",
            alias = "mcp",
            endpointUrl = "https://example.com/mcp",
            authType = ToolConnectionAuthType.BEARER,
            credential = "",
            oauthClientId = "",
            allowCleartext = false,
            clearCredential = true
        )

        assertNull(dao.getConnection("mcp-clear")!!.secretRef)
        assertNull(vault.read("connection_mcp-clear"))
        manager.closeAll()
        networkClient().close()
    }

    @Test
    fun `OAuth launch and callback persist credential and refresh connection state`() = runBlocking {
        McpOAuthClientTest.OAuthFixtureServer().use { server ->
            val dao = FakeToolConnectionDao()
            val vault = FakeSecretVault()
            val repository = ToolConnectionRepository(dao, vault)
            val networkClient = NetworkClient(CIO)
            val manager = McpClientManager(networkClient())
            val coordinator = McpOAuthCoordinator(McpOAuthClient(networkClient()), repository, vault, manager)
            dao.upsertConnection(
                ToolConnection(
                    connectionUid = "connection-1",
                    name = "OAuth MCP",
                    alias = "oauth_mcp",
                    type = ToolConnectionType.MCP,
                    endpointUrl = server.mcpUrl,
                    authType = ToolConnectionAuthType.OAUTH,
                    secretRef = null,
                    oauthClientId = null,
                    allowCleartext = true
                )
            )
            val viewModel = ToolConnectionsViewModel(dao, vault, coordinator, manager, fakeSettingRepository())
            val launch = async(start = CoroutineStart.UNDISPATCHED) { viewModel.oauthLaunches.first() }

            viewModel.startOAuth("connection-1")
            val authorizationUri = launch.await()
            val state = URI(authorizationUri).rawQuery.formValues().getValue("state")
            val completion = async(start = CoroutineStart.UNDISPATCHED) {
                viewModel.uiState.first { uiState ->
                    !uiState.isOAuthBusy && uiState.connections.any { it.connectionUid == "connection-1" && it.secretRef != null }
                }
            }
            viewModel.completeOAuthCallback(
                "$MCP_OAUTH_SCHEME://oauth/mcp/connection-1?code=auth-code&state=$state"
            )
            completion.await()

            val saved = dao.getConnection("connection-1")!!
            val credentialBytes = vault.read(checkNotNull(saved.secretRef))!!
            val credential = try {
                NetworkClient.json.decodeFromString<McpOAuthCredential>(credentialBytes.decodeToString())
            } finally {
                credentialBytes.fill(0)
            }
            assertEquals("fixture-client", saved.oauthClientId)
            assertEquals("access-1", credential.accessToken)
            assertNull(viewModel.uiState.value.errorMessage)
            assertTrue(viewModel.uiState.value.connections.any { it.connectionUid == "connection-1" && it.secretRef != null })
            manager.closeAll()
            networkClient().close()
        }
    }

    @Test
    fun `OAuth launch is single flight`() = runBlocking {
        McpOAuthClientTest.OAuthFixtureServer().use { server ->
            val dao = FakeToolConnectionDao()
            val vault = FakeSecretVault()
            val repository = ToolConnectionRepository(dao, vault)
            val networkClient = NetworkClient(CIO)
            val manager = McpClientManager(networkClient())
            val coordinator = McpOAuthCoordinator(McpOAuthClient(networkClient()), repository, vault, manager)
            dao.upsertConnection(
                ToolConnection(
                    connectionUid = "connection-1",
                    name = "OAuth MCP",
                    alias = "oauth_mcp",
                    type = ToolConnectionType.MCP,
                    endpointUrl = server.mcpUrl,
                    authType = ToolConnectionAuthType.OAUTH,
                    secretRef = null,
                    oauthClientId = null,
                    allowCleartext = true
                )
            )
            val viewModel = ToolConnectionsViewModel(dao, vault, coordinator, manager, fakeSettingRepository())
            val launch = async(start = CoroutineStart.UNDISPATCHED) { viewModel.oauthLaunches.first() }

            viewModel.startOAuth("connection-1")
            viewModel.startOAuth("connection-1")
            launch.await()
            viewModel.uiState.first { !it.isOAuthBusy }

            assertEquals(1, server.protectedResourceRequests.get())
            manager.closeAll()
            networkClient().close()
        }
    }
}

private fun fakeSettingRepository(): SettingRepository = mockk(relaxed = true) {
    coEvery { getFeatureSettings() } returns AppFeatureSettings()
}

private fun mcpConnectionForCheck() = ToolConnection("mcp", "MCP", "mcp", ToolConnectionType.MCP, "https://server.example/mcp", ToolConnectionAuthType.NONE, null, null)

private fun toolConnectionsModelForCheck(
    dao: ToolConnectionDao,
    vault: SecretVault = FakeSecretVault(),
    resolver: AgentToolResolver? = null,
    coordinator: McpOAuthCoordinator = mockk(relaxed = true),
    manager: McpClientManager = mockk(relaxed = true)
): ToolConnectionsViewModel {
    val settings = fakeSettingRepository()
    every { settings.observeFeatureSettings() } returns kotlinx.coroutines.flow.flowOf(AppFeatureSettings())
    return ToolConnectionsViewModel(dao, vault, coordinator, manager, settings, agentToolResolver = resolver)
}

/** Opaque replacement identities model vault metadata without fingerprinting plaintext. */
private class RevisionedConnectionTestVault(private val backing: FakeSecretVault = FakeSecretVault()) : SecretVault by backing {
    private val revisions = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val replacements = java.util.concurrent.atomic.AtomicLong()
    override suspend fun put(secretRef: String, secret: ByteArray) {
        backing.put(secretRef, secret)
        revisions[secretRef] = replacements.incrementAndGet().toString(16).padStart(64, '0')
    }
    override suspend fun delete(secretRef: String) {
        backing.delete(secretRef)
        revisions.remove(secretRef)
    }
    override suspend fun recordRevision(secretRef: String): String? = revisions[secretRef]
}

private fun String.formValues(): Map<String, String> = split('&')
    .filter(String::isNotBlank)
    .associate { item ->
        val parts = item.split('=', limit = 2)
        URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
            URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
    }
