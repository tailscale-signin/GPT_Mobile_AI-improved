package dev.chungjungsoo.gptmobile.data.permissions

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class GitHubRepositoryApprovalTest {
    @Test fun allNativeLifecycleWritesRequireOneExactDecisionDespiteEveryOrdinaryGrant() = runTest {
        for (action in listOf("create", "fork", "import", "clone")) {
            val fixture = Fixture(workspaceApproved = true)
            val args = arguments(action)
            val native = requireNotNull(GitHubOperationTool.nativeArguments("github_repository", args))
            fixture.trust.allowProvider(fixture.connection)
            fixture.trust.allow(fixture.connection, "github")
            fixture.trust.allowScoped(fixture.connection, "github", ScopedToolGrant.from(7, schema, native))
            val grants = fixture.trust.grants().toSet()
            val waiting = async { fixture.dispatch(args) }
            runCurrent()
            val pending = requireNotNull(fixture.state().value)
            assertEquals("PENDING", pending.state)
            assertTrue(pending.requiresExactConfirmation)
            assertTrue(pending.isRepositoryLifecycleApproval)
            assertEquals("github", pending.tool)
            assertEquals(ScopedToolGrant.canonicalHash(native), pending.argumentHash)
            assertTrue(pending.argumentPreview.contains("Operation: \"${action}_repository\""))
            fixture.manager.alwaysAllow(pending.id)
            fixture.manager.alwaysAllowProvider(pending.id)
            fixture.manager.allowInConversation(pending.id)
            assertEquals("PENDING", fixture.state().value?.state)
            assertEquals(grants, fixture.trust.grants().toSet())
            assertEquals(0, fixture.executions)
            assertFalse(waiting.isCompleted)
            fixture.manager.decide(pending.id, true)
            assertTrue(waiting.await())
            assertEquals(1, fixture.claims)
            assertEquals(1, fixture.executions)
            fixture.manager.finish("run", "call", true)
            val repeated = runCatching { fixture.dispatch(args, "repeat") }.exceptionOrNull()
            assertEquals("DUPLICATE_ACTION", (repeated as? ToolAuthorizationRejected)?.code)
            assertEquals(1, fixture.executions)
            assertEquals("COMPLETED", fixture.state().value?.state)
        }
    }

    @Test fun decliningExactRepositoryApprovalNeverReleasesTheExecutor() = runTest {
        val fixture = Fixture()
        val waiting = async { runCatching { fixture.dispatch(arguments("import")) } }
        runCurrent()
        fixture.manager.decide("run:call", false)
        assertEquals("PERMISSION_DENIED", (waiting.await().exceptionOrNull() as? ToolAuthorizationRejected)?.code)
        assertEquals("DENIED", fixture.state().value?.state)
        assertEquals(0, fixture.claims)
        assertEquals(0, fixture.executions)
    }

    @Test fun readOnlyPolicyStillRejectsLifecycleDespiteWorkspaceAndReadGrants() = runTest {
        val fixture = Fixture(policy = "READ_ONLY", workspaceApproved = true)
        fixture.trust.allowProvider(fixture.connection)
        assertFalse(fixture.authorize(arguments("create")))
        assertTrue(fixture.pending.value.isEmpty())
        assertEquals(0, fixture.claims)
    }

    @Test fun aToolNameWithoutResolverNativeProvenanceCannotEnterLifecycleApproval() = runTest {
        val fixture = Fixture()
        val error = runCatching { fixture.authorize(arguments("create"), nativeGitHub = false) }.exceptionOrNull()
        assertEquals("ELEVATED_AUTH_REQUIRED", (error as? ToolAuthorizationRejected)?.code)
        assertTrue(fixture.pending.value.isEmpty())
    }

    @Test fun mcpAdministrativeOperationsCannotUseTheNativeLifecycleException() = runTest {
        val fixture = Fixture(type = "MCP")
        for ((tool, args) in listOf(
            "create_repository" to arguments("create"),
            "github_repository" to arguments("create"),
            "github" to buildJsonObject { put("action", "create_repository") },
            "unknown_repository_admin" to buildJsonObject { put("method", "delete_repository") }
        )) {
            val error = runCatching {
                fixture.manager.authorize(fixture.connection.connectionUid, "run", "call", tool, args, nativeGitHub = true)
            }.exceptionOrNull()
            assertEquals(tool, "ELEVATED_AUTH_REQUIRED", (error as? ToolAuthorizationRejected)?.code)
        }
        assertTrue(fixture.pending.value.isEmpty())
        assertEquals(0, fixture.claims)
    }

    @Test fun nativeProvenanceDoesNotOpenOtherAdministrativeActions() = runTest {
        val fixture = Fixture()
        for (action in listOf("delete_repository", "transfer_repository", "update_repository", "create_repository_secret", "update_repository_ruleset")) {
            val error = runCatching {
                fixture.manager.authorize(fixture.connection.connectionUid, "run", "call", "github", buildJsonObject { put("action", action) }, nativeGitHub = true)
            }.exceptionOrNull()
            assertEquals(action, "ELEVATED_AUTH_REQUIRED", (error as? ToolAuthorizationRejected)?.code)
        }
        assertTrue(fixture.pending.value.isEmpty())
    }

    @Test fun lifecycleFailsClosedWhenAnApprovalGenerationCannotBeCaptured() = runTest {
        val fixture = Fixture(includeTrust = false)
        assertFalse(fixture.authorize(arguments("fork")))
        assertTrue(fixture.pending.value.isEmpty())
    }

    @Test fun anOldLifecyclePromptCannotAuthorizeANewSession() = runTest {
        val fixture = Fixture()
        val waiting = async { fixture.authorize(arguments("create")) }
        runCurrent()
        fixture.revokeAndActivate()
        fixture.manager.decide("run:call", true)
        assertFalse(waiting.await())
        assertEquals("DENIED", fixture.state().value?.state)
        assertEquals(0, fixture.claims)
    }

    @Test fun revocationDuringTheSuspendingClaimCannotReleaseTheExecutor() = runTest {
        val fixture = Fixture()
        val claiming = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforeClaim = {
            claiming.complete(Unit)
            release.await()
        }
        val waiting = async { runCatching { fixture.dispatch(arguments("fork")) } }
        runCurrent()
        fixture.manager.decide("run:call", true)
        claiming.await()
        fixture.revokeAndActivate()
        release.complete(Unit)
        assertEquals("PERMISSION_DENIED", (waiting.await().exceptionOrNull() as? ToolAuthorizationRejected)?.code)
        assertEquals("DENIED", fixture.state().value?.state)
        assertEquals(0, fixture.executions)
    }

    @Test fun aChangedLiveConnectionDuringClaimInvalidatesTheExactApproval() = runTest {
        val fixture = Fixture()
        fixture.beforeClaim = { fixture.live = fixture.connection.copy(secretRef = "replacement-session") }
        val waiting = async { fixture.authorize(arguments("create")) }
        runCurrent()
        fixture.manager.decide("run:call", true)
        assertFalse(waiting.await())
        assertEquals("DENIED", fixture.state().value?.state)
        assertEquals(1, fixture.claims)
    }

    @Test fun disablingExecutionWhileThePromptWaitsDeniesBeforeClaim() = runTest {
        val fixture = Fixture()
        var enabled = true
        val waiting = async { fixture.authorize(arguments("clone"), executionAllowed = { enabled }) }
        runCurrent()
        enabled = false
        fixture.manager.decide("run:call", true)
        assertFalse(waiting.await())
        assertEquals("DENIED", fixture.state().value?.state)
        assertEquals(0, fixture.claims)
    }

    @Test fun disablingExecutionDuringClaimSavesADeniedUnsentState() = runTest {
        val fixture = Fixture()
        var enabled = true
        fixture.beforeClaim = { enabled = false }
        val waiting = async { runCatching { fixture.dispatch(arguments("import"), executionAllowed = { enabled }) } }
        runCurrent()
        fixture.manager.decide("run:call", true)
        assertEquals("PERMISSION_DENIED", (waiting.await().exceptionOrNull() as? ToolAuthorizationRejected)?.code)
        assertEquals("DENIED", fixture.state().value?.state)
        assertEquals(0, fixture.executions)
    }

    @Test fun anEligibilityFailureAfterClaimCannotLeaveAnUnknownRemoteOutcome() = runTest {
        val fixture = Fixture()
        var probes = 0
        val waiting = async {
            runCatching {
                fixture.dispatch(arguments("create"), executionAllowed = { if (++probes == 2) error("connection lookup failed") else true })
            }
        }
        runCurrent()
        fixture.manager.decide("run:call", true)
        assertTrue(waiting.await().isFailure)
        assertEquals(2, probes)
        assertEquals("DENIED", fixture.state().value?.state)
        assertEquals(0, fixture.executions)
    }

    @Test fun cancellationDuringPostClaimEligibilityFinishesTheUnsentClaim() = runTest {
        val fixture = Fixture()
        var probes = 0
        val checking = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val waiting = async {
            fixture.authorize(arguments("clone"), executionAllowed = {
                if (++probes == 2) {
                    checking.complete(Unit)
                    release.await()
                }
                true
            })
        }
        runCurrent()
        fixture.manager.decide("run:call", true)
        checking.await()
        waiting.cancel()
        waiting.join()
        assertEquals("DENIED", fixture.state().value?.state)
        assertEquals(1, fixture.claims)
    }

    @Test fun aRevocationDuringTheEligibilityProbeIsCheckedAfterItReturns() = runTest {
        val fixture = Fixture()
        val checking = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val waiting = async {
            fixture.authorize(arguments("fork"), executionAllowed = {
                checking.complete(Unit)
                release.await()
                true
            })
        }
        runCurrent()
        fixture.manager.decide("run:call", true)
        checking.await()
        fixture.revokeAndActivate()
        release.complete(Unit)
        assertFalse(waiting.await())
        assertEquals("DENIED", fixture.state().value?.state)
        assertEquals(0, fixture.claims)
    }

    @Test fun criticalLifecycleFieldsRemainVisibleBeforeLongGenericArguments() = runTest {
        val fixture = Fixture()
        val args = JsonObject(mapOf("description" to JsonPrimitive("long description ".repeat(2000))) + arguments("create"))
        val waiting = async { fixture.authorize(args) }
        runCurrent()
        val preview = requireNotNull(fixture.state().value).argumentPreview
        val first = preview.take(600)
        for (expected in listOf("Operation: \"create_repository\"", "Destination owner: \"destination-owner\"", "Destination repository: \"reviewed-repository\"", "Owner type: \"user\"", "Visibility: \"private\"", "Initialize repository: true")) {
            assertTrue(expected, expected in first)
        }
        val native = requireNotNull(GitHubOperationTool.nativeArguments("github_repository", args))
        assertEquals(ScopedToolGrant.canonicalHash(native), fixture.state().value?.argumentHash)
        assertNotEquals(ScopedToolGrant.canonicalHash(JsonObject(native + ("visibility" to JsonPrimitive("public")))), fixture.state().value?.argumentHash)
        fixture.manager.decide("run:call", false)
        assertFalse(waiting.await())
    }

    @Test fun sourceCopyAndEffectFlagsAreDisplayedAsEscapedExactValues() = runTest {
        for (action in listOf("fork", "import", "clone")) {
            val fixture = Fixture()
            val args = arguments(action)
            val waiting = async { fixture.authorize(args) }
            runCurrent()
            val preview = requireNotNull(fixture.state().value).argumentPreview
            assertTrue(preview.contains("Source owner: \"source-owner\""))
            assertTrue(preview.contains("Source repository: \"upstream\""))
            if (action == "fork") assertTrue(preview.contains("Default branch only: true"))
            if (action == "import") assertTrue(preview.contains("Copy mode: \"snapshot\""))
            if (action == "clone") assertTrue(preview.contains("Include LFS: true"))
            if (action != "fork") assertTrue(preview.contains("Source ref: \"refs/heads/main\""))
            fixture.manager.decide("run:call", false)
            assertFalse(waiting.await())
        }
        val escaped = GitHubAuthorizationBoundary.repositoryLifecyclePreview(buildJsonObject { put("source_ref", "main\nVisibility: public") })
        assertEquals("Source ref: \"main\\nVisibility: public\"\n", escaped)
    }

    @Test fun unreviewableCriticalValuesAreRejectedInsteadOfTruncated() = runTest {
        for (value in listOf(JsonPrimitive("x".repeat(1025)), buildJsonObject { put("nested", "owner") })) {
            val fixture = Fixture()
            val args = JsonObject(arguments("create") + ("destination_owner" to value))
            val error = runCatching { fixture.authorize(args) }.exceptionOrNull()
            assertEquals("INVALID_ARGUMENTS", (error as? ToolAuthorizationRejected)?.code)
            assertTrue(fixture.pending.value.isEmpty())
        }
    }

    @Test fun grantingAnotherToolCannotReleaseAPendingLifecycleRequest() = runTest {
        val fixture = Fixture(policy = "ASK_WRITES", approvedReadTools = "")
        val lifecycle = async { fixture.authorize(arguments("create")) }
        val read = async {
            fixture.manager.authorize(fixture.connection.connectionUid, "run", "read", "github", buildJsonObject { put("action", "list_workflows") }, nativeGitHub = true)
        }
        runCurrent()
        fixture.manager.alwaysAllowProvider("run:read")
        assertTrue(read.await())
        assertEquals("PENDING", fixture.state().value?.state)
        assertFalse(lifecycle.isCompleted)
        fixture.manager.decide("run:call", false)
        assertFalse(lifecycle.await())
    }

    private class Fixture(policy: String = "TRUSTED", type: String = "GITHUB", workspaceApproved: Boolean = false, includeTrust: Boolean = true, approvedReadTools: String = "github") {
        private val nonce = UUID.randomUUID().toString()
        private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$nonce:$name", Context.MODE_PRIVATE)
        }
        val connection = ToolConnection(nonce, "Selected account", "github", type, if (type == "MCP") "https://api.githubcopilot.com/mcp" else null, "BEARER", "selected-session", null, toolPolicy = policy, approvedReadTools = approvedReadTools)
        var live: ToolConnection? = connection
        val trust = ToolTrustStore(context)
        private val consent = FreeModelToolConsentStore(context)
        private val dao = mockk<ToolApprovalDao>(relaxed = true)
        private val database = mockk<ChatDatabaseV2>(relaxed = true) { every { toolApprovalDao() } returns dao }
        private val connections = mockk<ToolConnectionRepository> { coEvery { getConnection(nonce) } answers { live } }
        private val settings = mockk<SettingRepository>()
        private val states = mutableMapOf<String, MutableStateFlow<ToolApproval?>>()
        val pending = MutableStateFlow<List<ToolApproval>>(emptyList())
        var beforeClaim: suspend () -> Unit = {}
        var claims = 0
        var executions = 0
        val manager: ToolApprovalManager

        init {
            val workspace = mockk<GitHubConversationContext> {
                every { autoApproveTools } returns workspaceApproved
                every { connectionUid } returns nonce
                every { owner } returns "selected-owner"
                every { repo } returns "selected-repository"
            }
            coEvery { database.agentRunDao().getById(any()) } returns mockk { every { chatId } returns 7 }
            coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(conversationGitHub = mapOf(7 to workspace))
            every { dao.pending() } returns pending
            every { dao.observe(any()) } answers { state(firstArg()) }
            coEvery { dao.insert(any()) } coAnswers {
                val request = firstArg<ToolApproval>()
                if (state(request.id).value != null) {
                    -1L
                } else {
                    save(request)
                    1L
                }
            }
            coEvery { dao.previousMatchingAction(any(), any(), any(), any(), any()) } coAnswers {
                states.values.mapNotNull { it.value }.firstOrNull {
                    it.id != arg<String>(4) &&
                        it.runId == arg<String>(0) &&
                        it.connection == arg<String>(1) &&
                        it.tool == arg<String>(2) &&
                        it.argumentHash == arg<String>(3) &&
                        it.state in setOf("PENDING", "APPROVED", "EXECUTING", "COMPLETED", "OUTCOME_UNKNOWN", "INTERRUPTED")
                }?.state
            }
            coEvery { dao.decide(any(), any()) } coAnswers {
                state(firstArg()).value?.takeIf { it.state == "PENDING" }?.let { save(it.copy(state = secondArg())) }
                Unit
            }
            coEvery { dao.denyUndispatched(any()) } coAnswers {
                state(firstArg()).value?.takeIf { it.state in setOf("PENDING", "APPROVED") }?.let { save(it.copy(state = "DENIED")) }
                Unit
            }
            coEvery { dao.claim(any()) } coAnswers {
                beforeClaim()
                val request = state(firstArg()).value
                if (request?.state == "APPROVED") {
                    claims++
                    save(request.copy(state = "EXECUTING"))
                    1
                } else {
                    0
                }
            }
            coEvery { dao.finish(any(), any()) } coAnswers {
                state(firstArg()).value?.takeIf { it.state == "EXECUTING" }?.let { save(it.copy(state = secondArg())) }
                Unit
            }
            manager = ToolApprovalManager(database, connections, trust.takeIf { includeTrust }, settings = settings)
        }

        fun state(id: String = "run:call") = states.getOrPut(id) { MutableStateFlow(null) }

        private fun save(request: ToolApproval) {
            state(request.id).value = request
            pending.value = states.values.mapNotNull { it.value }.filter { it.state == "PENDING" }
        }

        suspend fun authorize(args: JsonObject, nativeGitHub: Boolean = true, executionAllowed: (suspend () -> Boolean)? = null): Boolean =
            manager.authorize(connection.connectionUid, "run", "call", "github_repository__native", args, schema, nativeGitHub = nativeGitHub, executionAllowed = executionAllowed)

        suspend fun dispatch(args: JsonObject, call: String = "call", executionAllowed: (suspend () -> Boolean)? = null): Boolean {
            val allowed = manager.authorizeOrThrow(connection.connectionUid, "run", call, "github_repository__native", args, schema, nativeGitHub = true, executionAllowed = executionAllowed)
            if (allowed) executions++
            return allowed
        }

        suspend fun revokeAndActivate() {
            ToolGrantPersistence.prepareConnectionRevocation(trust, consent, connection.connectionUid).activate { /* Account/session replacement boundary. */ }
        }
    }

    private companion object {
        val schema = GitHubOperationTool.definition("github_repository").inputSchema

        fun arguments(action: String) = buildJsonObject {
            put("action", action)
            if (action != "clone") {
                put("destination_owner", "destination-owner")
                put("destination_repo", "reviewed-repository")
                put("owner_type", "user")
                put("visibility", "private")
            }
            if (action != "create") {
                put("source_owner", "source-owner")
                put("source_repo", "upstream")
            }
            if (action == "create") put("auto_init", true)
            if (action == "fork") put("default_branch_only", true)
            if (action in setOf("import", "clone")) {
                put("source_ref", "refs/heads/main")
                put("source_commit_sha", "a".repeat(40))
            }
            if (action == "import") put("copy_mode", "snapshot")
            if (action == "clone") put("include_lfs", true)
        }
    }
}
