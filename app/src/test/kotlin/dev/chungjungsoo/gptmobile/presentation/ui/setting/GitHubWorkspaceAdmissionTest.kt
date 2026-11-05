package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceDraftStore
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubWorkspaceAdmissionTest {
    private lateinit var model: GitHubWorkspaceViewModel
    private lateinit var drafts: GitHubWorkspaceDraftStore
    private lateinit var connections: ToolConnectionRepository
    private lateinit var vault: SecretVault
    private lateinit var auth: GitHubPublicAuthCoordinator
    private lateinit var settings: SettingRepository
    private lateinit var state: MutableStateFlow<GitHubWorkspaceState>

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        drafts = mockk(relaxed = true)
        connections = mockk()
        vault = mockk()
        auth = mockk()
        settings = mockk()
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
        model = GitHubWorkspaceViewModel(connections, vault, mockk(), drafts, settings, gitHubPublicAuth = auth)
        @Suppress("UNCHECKED_CAST")
        state = GitHubWorkspaceViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(model) as MutableStateFlow<GitHubWorkspaceState>
        state.value = GitHubWorkspaceState(selection = GitHubRepositoryContext("owner", "repo", "main"), headSha = "a".repeat(40), error = "HTTP 401")
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun aFailedLazySectionDoesNotAutomaticallyRedispatchAfterBusyBecomesFalse() {
        repeat(20) {
            for (section in listOf(0, 2, 3)) model.onSectionVisible(section)
            assertFalse(model.state.value.busy)
        }
    }

    @Test fun explicitRefreshAndErrorDismissalCanAdmitAnIntentionalRetry() {
        model.refreshActivity()
        assertTrue(model.state.value.busy)
        assertNull(model.state.value.error)
    }

    @Test fun obsoleteAutosaveCannotOverwriteTheCloseSnapshotEvenWithALaterSequence() = runBlocking {
        model.viewModelScope.cancel()
        val connection = ToolConnection("connection", "GitHub", "github", "GITHUB", null, "BEARER", null, null)
        val old = state.value.copy(connection = connection, editorDrafts = mapOf("File.kt" to "older edit"))
        val current = old.copy(editorDrafts = mapOf("File.kt" to "latest edit"), busy = true)
        state.value = current
        invokeSuspending("persist", pending(old, 100))
        coVerify(exactly = 0) { drafts.write(any(), any()) }
        invokeSuspending("persist", pending(current, 2))
        state.value = GitHubWorkspaceState()
        invokeSuspending("persist", pending(old, 101))
        coVerify(exactly = 1) { drafts.write("connection", match { "latest edit" in it && "older edit" !in it }) }
        coVerify(exactly = 1) { drafts.write(any(), any()) }
    }

    @Test fun queuedAutosaveCannotReplaceProtectedRecoveryBeforeItsDraftReachesTheDisplayedState() = runBlocking {
        model.viewModelScope.cancel()
        authorize()
        val empty = state.value
        val queued = pending(empty, 100)
        state.value = empty.copy(busy = true)
        invokeSuspending("persistDraft", pending(state.value, 101), true)
        state.value = empty
        val saved = empty.copy(staged = listOf(GitHubStagedFile("Code.kt", "old", "saved edit")), editorDrafts = mapOf("Other.kt" to "unsaved text"))
        val value = GitHubWorkspaceViewModel::class.java.getDeclaredMethod("draft", GitHubWorkspaceState::class.java).apply { isAccessible = true }.invoke(model, saved) as Pair<*, *>
        GitHubWorkspaceViewModel::class.java.getDeclaredField("pendingRecovery").apply { isAccessible = true }.set(model, value.second)
        invokeSuspending("persistDraft", queued, true)
        coVerify(exactly = 0) { drafts.write(any(), any()) }
        state.value = saved.copy(busy = true)
        invokeSuspending("persist", pending(state.value, 102))
        coVerify(exactly = 1) { drafts.write("connection", match { "saved edit" in it && "unsaved text" in it }) }
        coVerify(exactly = 1) { drafts.write(any(), any()) }
    }

    @Test fun anUnfinishedOrFailedDraftReadCannotBeOverwrittenByAutosaveOrClose() = runBlocking {
        model.viewModelScope.cancel()
        authorize()
        GitHubWorkspaceViewModel::class.java.getDeclaredField("draftRestorationPending").apply { isAccessible = true }.set(model, true)
        invokeSuspending("persistDraft", pending(state.value, 100), true)
        invokeSuspending("persist", pending(state.value, 101))
        coVerify(exactly = 0) { drafts.write(any(), any()) }
    }

    @Test fun refreshingTheHeadInvalidatesCardsAndContinuationsFromThePreviousSnapshot() = runBlocking {
        val client = authorize()
        val old = buildJsonObject {
            put("commit_sha", "a".repeat(40))
            put("continuation", "old cursor")
        }
        state.value = state.value.copy(searchResult = old, ciDigest = old, changedFiles = old, pullPackage = old, pullDetail = "old pull review")
        coEvery { client.execute("resolve_context", any()) } returns buildJsonObject { put("commit_sha", "b".repeat(40)) }
        GitHubWorkspaceViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.set(model, client)
        invokeSuspending("loadHeadAndDirectory")
        org.junit.Assert.assertEquals("b".repeat(40), model.state.value.headSha)
        assertNull(model.state.value.searchResult)
        assertNull(model.state.value.ciDigest)
        assertNull(model.state.value.changedFiles)
        assertNull(model.state.value.pullPackage)
        assertNull(model.state.value.pullDetail)
    }

    @Test fun errorDismissalAllowsTheNextSectionNavigationToTryAgain() {
        model.clearError()
        model.onSectionVisible(0)
        assertTrue(model.state.value.busy)
    }

    @Test fun removedConnectionsCannotDispatchReadsOrWritesAndKeepTheDraft() = runBlocking {
        val client = authorize()
        val draft = mapOf("File.kt" to "keep this edit")
        state.value = state.value.copy(editorDrafts = draft)
        coEvery { connections.getConnection("connection") } returns null
        assertTrue(rejected("api", "get_repository", buildJsonObject {}).message.orEmpty().contains("removed"))
        assertTrue(rejected("api", "commit_files", buildJsonObject {}).message.orEmpty().contains("removed"))
        coVerify(exactly = 0) { client.execute(any(), any()) }
        org.junit.Assert.assertEquals(draft, state.value.editorDrafts)
    }

    @Test fun currentReadOnlyPolicyBlocksBothMutationDispatchersButAllowsReads() = runBlocking {
        val client = authorize()
        val tool = mockk<GitHubTool>()
        GitHubWorkspaceViewModel::class.java.getDeclaredField("tool").apply { isAccessible = true }.set(model, tool)
        coEvery { connections.getConnection("connection") } returns requireNotNull(state.value.connection).copy(toolPolicy = "READ_ONLY")
        assertTrue(rejected("api", "commit_files", buildJsonObject {}).message.orEmpty().contains("policy"))
        assertTrue(rejected("toolRequest", "create_branch", buildJsonObject {}).message.orEmpty().contains("policy"))
        invokeSuspending("requireActiveConnection", false)
        org.junit.Assert.assertEquals("READ_ONLY", state.value.connection?.toolPolicy)
        // The policy change creates a fresh transport; bind its execution at the HTTP boundary.
        GitHubWorkspaceViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.set(model, client)
        coEvery { client.execute("get_repository", any()) } returns buildJsonObject { put("name", "repo") }
        invokeSuspending("api", "get_repository", buildJsonObject {})
        coVerify(exactly = 1) { client.execute("get_repository", any()) }
        coVerify(exactly = 0) { client.execute("commit_files", any()) }
        coVerify(exactly = 0) { tool.execute(any(), any()) }
    }

    @Test fun verifiedSameAccountRotationRecreatesTransportWithoutLosingDrafts() = runBlocking {
        val originalClient = authorize()
        val draft = mapOf("File.kt" to "unsaved change")
        val staged = listOf(GitHubStagedFile("Other.kt", "old", "new"))
        state.value = state.value.copy(editorDrafts = draft, staged = staged)
        for (reference in listOf("credential", "rotated-secret")) {
            val current = requireNotNull(state.value.connection).copy(secretRef = reference)
            coEvery { connections.getConnection("connection") } returns current
            coEvery { auth.accessToken(current) } returns "replacement-$reference"
            invokeSuspending("requireActiveConnection", false)
            org.junit.Assert.assertNotSame(originalClient, GitHubWorkspaceViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.get(model))
            org.junit.Assert.assertEquals(current, state.value.connection)
            org.junit.Assert.assertEquals(draft, state.value.editorDrafts)
            org.junit.Assert.assertEquals(staged, state.value.staged)
        }
        coVerify(exactly = 0) { originalClient.execute(any(), any()) }
    }

    @Test fun aDifferentVerifiedAccountCannotReuseTheOpenedWorkspace() = runBlocking {
        val client = authorize()
        coEvery { auth.durableIdentity("connection") } returns "different-account"
        assertTrue(rejected("api", "get_repository", buildJsonObject {}).message.orEmpty().contains("account changed"))
        coVerify(exactly = 0) { client.execute(any(), any()) }
    }

    @Test fun changedConnectionIdentityCannotReuseTheOpenedCredential() = runBlocking {
        val client = authorize()
        val original = requireNotNull(state.value.connection)
        for (changed in listOf(original.copy(type = "MCP"), original.copy(authType = "OAUTH"), original.copy(endpointUrl = "https://different.example"))) {
            coEvery { connections.getConnection("connection") } returns changed
            assertTrue(rejected("api", "get_repository", buildJsonObject {}).message.orEmpty().contains("Reopen"))
        }
        coVerify(exactly = 0) { client.execute(any(), any()) }
    }

    @Test fun unchangedVerifiedCredentialsPermitAnAuthorizedMutation() = runBlocking {
        val client = authorize()
        coEvery { client.execute("commit_files", any()) } returns buildJsonObject { put("sha", "b".repeat(40)) }
        invokeSuspending("api", "commit_files", buildJsonObject {})
        coVerify(exactly = 1) { client.execute("commit_files", any()) }
        coVerify(exactly = 1) { auth.accessToken(requireNotNull(state.value.connection)) }
    }

    @Test fun appConnectionsCannotListOrRestoreRepositoriesWithoutAnInstallation() = runBlocking {
        val client = authorize()
        state.value = state.value.copy(appAuthorization = true, installationId = null)
        invokeSuspending("loadRepositories", 1)
        assertTrue(rejected("selectRepositoryInternal", "owner", "repo", "main", false).message.orEmpty().contains("installation"))
        coVerify(exactly = 0) { client.execute(any(), any()) }
    }

    @Test fun changingInstallationClearsThePreviousRepositoryAndUsesTheScopedEndpoint() = runBlocking {
        val client = authorize()
        val active = Json.parseToJsonElement("""{"id":42,"account":{"login":"team"},"suspended_at":null}""").jsonObject
        val suspended = Json.parseToJsonElement("""{"id":43,"suspended_at":"2026-10-28T00:00:00Z"}""").jsonObject
        state.value = state.value.copy(appAuthorization = true, installations = listOf(active, suspended), installationId = "41", staged = emptyList(), editorDrafts = emptyMap())
        assertTrue(rejected("selectInstallationInternal", "43").message.orEmpty().contains("active installation"))
        coVerify(exactly = 0) { client.execute(any(), any()) }
        coEvery { client.execute("list_installation_repositories", any()) } returns Json.parseToJsonElement("""{"items":[{"full_name":"team/allowed"}],"has_more":false}""")
        invokeSuspending("selectInstallationInternal", "42")
        assertNull(state.value.selection)
        assertTrue(state.value.headSha.isBlank())
        org.junit.Assert.assertEquals("42", state.value.installationId)
        org.junit.Assert.assertEquals(1, state.value.repositories.size)
        coVerify(exactly = 1) { client.execute("list_installation_repositories", match { it["installation_id"].toString() == "\"42\"" }) }
        assertTrue(rejected("selectRepositoryInternal", "other", "repository", "main", false).message.orEmpty().contains("installation"))
        coVerify(exactly = 0) { client.execute("get_repository", any()) }
    }

    @Test fun repositoryPushMembershipDoesNotOverrideReadOnlyAppContentsPermission() = runBlocking {
        val client = authorize()
        val installation = Json.parseToJsonElement("""{"id":42,"permissions":{"contents":"read"}}""").jsonObject
        val repository = Json.parseToJsonElement("""{"full_name":"owner/repo","default_branch":"main","permissions":{"push":true}}""").jsonObject
        state.value = state.value.copy(appAuthorization = true, installationId = "42", installations = listOf(installation), repositories = listOf(repository))
        coEvery { client.execute("get_repository", any()) } returns repository
        coEvery { client.execute("list_branches", any()) } returns Json.parseToJsonElement("""{"items":[{"name":"main"}],"has_more":false}""")
        coEvery { client.execute("resolve_context", any()) } returns buildJsonObject { put("commit_sha", "b".repeat(40)) }
        invokeSuspending("selectRepositoryInternal", "owner", "repo", "main", false)
        assertFalse(state.value.canWrite)
        org.junit.Assert.assertEquals("owner/repo", state.value.selection?.fullName)
    }

    @Test fun aSavedDraftCannotBeOverwrittenWhileChoosingAnotherInstallationRepository() = runBlocking {
        val client = authorize()
        val saved = state.value.copy(staged = listOf(GitHubStagedFile("Code.kt", "old", "saved edit")))
        val value = GitHubWorkspaceViewModel::class.java.getDeclaredMethod("draft", GitHubWorkspaceState::class.java).apply { isAccessible = true }.invoke(model, saved) as Pair<*, *>
        GitHubWorkspaceViewModel::class.java.getDeclaredField("pendingRecovery").apply { isAccessible = true }.set(model, value.second)
        state.value = state.value.copy(selection = null, recoveryRepository = "owner/repo", appAuthorization = true, installationId = "42", repositories = listOf(Json.parseToJsonElement("""{"full_name":"other/repository"}""").jsonObject))
        assertTrue(rejected("selectRepositoryInternal", "other", "repository", null, false).message.orEmpty().contains("Restore owner/repo"))
        coVerify(exactly = 0) { client.execute(any(), any()) }
        coVerify(exactly = 0) { drafts.write(any(), any()) }
        org.junit.Assert.assertEquals("owner/repo", state.value.recoveryRepository)
    }

    @Test fun returningToASavedRepositoryDoesNotLoadOptionalInventoriesOrHistory() = runBlocking {
        val client = authorize()
        val repository = Json.parseToJsonElement("""{"full_name":"owner/repo","default_branch":"main","permissions":{"push":true}}""").jsonObject
        coEvery { client.execute("get_repository", any()) } returns repository
        coEvery { client.execute("resolve_context", any()) } returns buildJsonObject { put("commit_sha", "b".repeat(40)) }
        invokeSuspending("selectRepositoryInternal", "owner", "repo", "main", true)
        org.junit.Assert.assertEquals("b".repeat(40), state.value.headSha)
        assertTrue(state.value.canWrite)
        coVerify(exactly = 0) { client.execute("get_account", any()) }
        coVerify(exactly = 0) { client.execute("list_repositories", any()) }
        coVerify(exactly = 0) { client.execute("list_branches", any()) }
        coVerify(exactly = 0) { client.execute("list_commits", any()) }
    }

    @Test fun restoringAnAppRepositoryDoesNotInventMissingInstallationWriteAccess() = runBlocking {
        val client = authorize()
        state.value = state.value.copy(appAuthorization = true, installationId = null)
        coEvery { client.execute("get_repository", any()) } returns Json.parseToJsonElement("""{"default_branch":"main","permissions":{"push":true}}""")
        coEvery { client.execute("resolve_context", any()) } returns buildJsonObject { put("commit_sha", "b".repeat(40)) }
        invokeSuspending("selectRepositoryInternal", "owner", "repo", "main", true)
        assertFalse(state.value.canWrite)
        assertTrue(rejected("selectRepositoryInternal", "other", "repository", "main", true).message.orEmpty().contains("installation"))
    }

    @Test fun failedHeadRefreshPreservesTheRestoredDraftAndItsOriginalBase() = runBlocking {
        val client = authorize()
        val saved = state.value.copy(staged = listOf(GitHubStagedFile("Code.kt", "old", "saved edit")), editorDrafts = mapOf("Other.kt" to "unsaved text"))
        val value = GitHubWorkspaceViewModel::class.java.getDeclaredMethod("draft", GitHubWorkspaceState::class.java).apply { isAccessible = true }.invoke(model, saved) as Pair<*, *>
        GitHubWorkspaceViewModel::class.java.getDeclaredField("pendingRecovery").apply { isAccessible = true }.set(model, value.second)
        coEvery { client.execute("get_repository", any()) } returns Json.parseToJsonElement("""{"default_branch":"main","permissions":{"push":true}}""")
        coEvery { client.execute("resolve_context", any()) } throws IllegalStateException("Offline")
        assertTrue(rejected("selectRepositoryInternal", "owner", "repo", "main", true).message.orEmpty().contains("Offline"))
        org.junit.Assert.assertEquals(saved.staged, state.value.staged)
        org.junit.Assert.assertEquals(saved.editorDrafts, state.value.editorDrafts)
        org.junit.Assert.assertEquals(saved.headSha, state.value.headSha)
        coVerify(exactly = 0) { drafts.write(any(), any()) }
        assertTrue(state.value.notice.orEmpty().contains("preserved"))
    }

    @Test fun initialRepositorySetupDefersTheChatUntilTheUserCompletesIt() = runBlocking {
        val client = authorize()
        state.value = state.value.copy(setupRequired = true, busy = false)
        coEvery { client.execute("get_repository", any()) } returns Json.parseToJsonElement("""{"default_branch":"main","permissions":{"push":true}}""")
        coEvery { client.execute("resolve_context", any()) } returns buildJsonObject { put("commit_sha", "b".repeat(40)) }
        coEvery { client.execute("list_branches", any()) } returns Json.parseToJsonElement("""{"items":[{"name":"main"}],"has_more":false}""")
        invokeSuspending("selectRepositoryInternal", "owner", "repo", "main", false)
        assertTrue(state.value.setupRequired)
        assertTrue(state.value.branchesLoaded)
        org.junit.Assert.assertEquals(listOf("main"), state.value.branches)
    }

    private fun authorize(): GitHubWorkspaceClient {
        val connection = ToolConnection("connection", "GitHub", "github", "GITHUB", null, "BEARER", "credential", null)
        state.value = state.value.copy(connection = connection, canWrite = true)
        coEvery { connections.getConnection(connection.connectionUid) } returns connection
        coEvery { auth.accessToken(any()) } returns "workspace-token"
        coEvery { auth.durableIdentity("connection") } returns "verified-account-connection"
        coEvery { auth.legacyCredentialIdentities("connection") } returns emptyList()
        GitHubWorkspaceViewModel::class.java.getDeclaredField("authorizationIdentity").apply { isAccessible = true }.set(model, "verified-account-connection")
        GitHubWorkspaceViewModel::class.java.getDeclaredField("credentialDigest").apply { isAccessible = true }.set(model, MessageDigest.getInstance("SHA-256").digest("workspace-token".encodeToByteArray()))
        return mockk<GitHubWorkspaceClient>().also { client ->
            GitHubWorkspaceViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.set(model, client)
        }
    }

    private suspend fun rejected(name: String, vararg arguments: Any?): Throwable {
        val error = runCatching { invokeSuspending(name, *arguments) }.exceptionOrNull() ?: throw AssertionError("Expected dispatch rejection")
        return (error as? InvocationTargetException)?.targetException ?: error
    }

    private fun pending(snapshot: GitHubWorkspaceState, sequence: Long): Any {
        val value = GitHubWorkspaceViewModel::class.java.getDeclaredMethod("draft", GitHubWorkspaceState::class.java).apply { isAccessible = true }.invoke(model, snapshot) as Pair<*, *>
        val type = GitHubWorkspaceViewModel::class.java.declaredClasses.single { it.simpleName == "PendingDraft" }
        return type.declaredConstructors.single { it.parameterCount == 3 }.apply { isAccessible = true }.newInstance(sequence, value.first, value.second)
    }

    private suspend fun invokeSuspending(name: String, vararg arguments: Any?): Any? = suspendCoroutineUninterceptedOrReturn { continuation ->
        GitHubWorkspaceViewModel::class.java.declaredMethods.single { it.name == name && it.parameterCount == arguments.size + 1 }
            .apply { isAccessible = true }.invoke(model, *arrayOf(*arguments, continuation))
    }
}
