package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceDraftStore
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
import kotlinx.serialization.json.buildJsonObject
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
    private lateinit var state: MutableStateFlow<GitHubWorkspaceState>

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        drafts = mockk(relaxed = true)
        connections = mockk()
        vault = mockk()
        model = GitHubWorkspaceViewModel(connections, vault, mockk(), drafts, mockk())
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
        coEvery { client.execute("get_repository", any()) } returns buildJsonObject { put("name", "repo") }
        invokeSuspending("api", "get_repository", buildJsonObject {})
        coVerify(exactly = 1) { client.execute("get_repository", any()) }
        coVerify(exactly = 0) { client.execute("commit_files", any()) }
        coVerify(exactly = 0) { tool.execute(any(), any()) }
    }

    @Test fun sameReferenceCredentialRotationRequiresReopenAndWipesFreshBytes() = runBlocking {
        val client = authorize()
        val rotated = "replacement-token".encodeToByteArray()
        val draft = mapOf("File.kt" to "unsaved change")
        state.value = state.value.copy(editorDrafts = draft)
        coEvery { vault.read("credential") } returns rotated
        assertTrue(rejected("api", "commit_files", buildJsonObject {}).message.orEmpty().contains("credentials changed"))
        assertTrue(rotated.all { it == 0.toByte() })
        coVerify(exactly = 0) { client.execute(any(), any()) }
        org.junit.Assert.assertEquals(draft, state.value.editorDrafts)
    }

    @Test fun changedConnectionIdentityCannotReuseTheOpenedCredential() = runBlocking {
        val client = authorize()
        val original = requireNotNull(state.value.connection)
        for (changed in listOf(original.copy(type = "MCP"), original.copy(secretRef = "other"), original.copy(authType = "OAUTH"), original.copy(endpointUrl = "https://different.example"))) {
            coEvery { connections.getConnection("connection") } returns changed
            assertTrue(rejected("api", "get_repository", buildJsonObject {}).message.orEmpty().contains("Reopen"))
        }
        coVerify(exactly = 0) { client.execute(any(), any()) }
    }

    @Test fun unchangedCredentialsPermitAnAuthorizedMutationAndAreWipedAfterChecking() = runBlocking {
        val client = authorize()
        val fresh = "workspace-token".encodeToByteArray()
        coEvery { vault.read("credential") } returns fresh
        coEvery { client.execute("commit_files", any()) } returns buildJsonObject { put("sha", "b".repeat(40)) }
        invokeSuspending("api", "commit_files", buildJsonObject {})
        coVerify(exactly = 1) { client.execute("commit_files", any()) }
        assertTrue(fresh.all { it == 0.toByte() })
    }

    private fun authorize(): GitHubWorkspaceClient {
        val connection = ToolConnection("connection", "GitHub", "github", "GITHUB", null, "BEARER", "credential", null)
        state.value = state.value.copy(connection = connection, canWrite = true)
        coEvery { connections.getConnection(connection.connectionUid) } returns connection
        coEvery { vault.read("credential") } answers { "workspace-token".encodeToByteArray() }
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
