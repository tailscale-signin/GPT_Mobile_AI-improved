package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.github.GitHubChatDecisions
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubCopilotConversationPreference
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCopilotToolTest {
    private val context = GitHubConversationContext("connection", "owner", "repo", "main", "a".repeat(40), "remote-profile")
    private val enabled = AppFeatureSettings(
        conversationGitHub = mapOf(42 to context),
        conversationIdentities = mapOf(42 to "chat-42"),
        pluginExecution = mapOf(ToolPluginId.GITHUB to PluginExecutionSettings(githubCopilotEnabled = true)),
        githubCopilotConversations = mapOf(42 to GitHubCopilotConversationPreference("chat-42", GitHubChatDecisions.workspaceKey(context), true))
    )
    private val disabledConversation = enabled.copy(
        githubCopilotConversations = mapOf(42 to enabled.githubCopilotConversations.getValue(42).copy(enabled = false))
    )
    private val request = buildJsonObject {
        put("action", "copilot_create_task")
        put("request_id", "request-42")
        put("prompt", "Implement the approved change.")
        put("base_ref", "main")
        put("expected_base_sha", "a".repeat(40))
    }

    @Test fun defaultOnAvailabilityWaitsForAnExplicitTaskAndUsesTheSelectedController() = runTest {
        val defaults = enabled.copy(pluginExecution = emptyMap(), githubCopilotConversations = emptyMap())
        var directPosts = 0
        var gatewaySubmissions = 0
        HttpClient(
            MockEngine { req ->
                if (req.method == HttpMethod.Post) {
                    directPosts++
                    respond("""{"id":"direct-task","state":"queued"}""")
                } else {
                    respond("""{"object":{"type":"commit","sha":"${"a".repeat(40)}"}}""")
                }
            }
        ).use { http ->
            val direct = tool(http, defaults)
            val gateway = tool(http, defaults, owner = "GATEWAY", backend = { action, _ ->
                assertEquals("copilot_create_task", action)
                gatewaySubmissions++
                gatewayReceipt()
            })
            assertEquals(0, directPosts)
            assertEquals(0, gatewaySubmissions)
            assertFalse(direct.execute("direct", request).isError)
            assertEquals(1, directPosts)
            assertEquals(0, gatewaySubmissions)
            assertFalse(gateway.execute("gateway", request).isError)
            assertEquals(1, directPosts)
            assertEquals(1, gatewaySubmissions)
        }
    }

    @Test fun creationHonorsLiveOptOutsAndConversationBindingForDirectAndGatewayOwners() = runTest {
        var network = 0
        var gateway = 0
        HttpClient(
            MockEngine {
                network++
                error("Disabled Copilot cannot contact GitHub")
            }
        ).use { http ->
            val deniedSettings = listOf(AppFeatureSettings(), disabledConversation, enabled.copy(pluginExecution = mapOf(ToolPluginId.GITHUB to PluginExecutionSettings(githubCopilotEnabled = false))))
            for (settings in deniedSettings) {
                for (owner in listOf("ANDROID_DIRECT", "GATEWAY")) {
                    val backend: (suspend (String, JsonObject) -> JsonObject)? = if (owner == "GATEWAY") {
                        (
                            { _, _ ->
                                gateway++
                                gatewayReceipt()
                            }
                            )
                    } else {
                        null
                    }
                    val tool = tool(http, settings, backend = backend, owner = owner)
                    val result = tool.execute("disabled-$owner", request)
                    assertTrue(result.isError)
                    assertEquals("GITHUB_COPILOT_DISABLED", result.errorCode)
                    assertFalse(result.dispatched)
                }
            }
            val revoked = GitHubTool(
                "token",
                http,
                connectionUid = "connection",
                conversationId = "42",
                repositoryScope = context,
                featureSettings = enabled,
                copilotSettings = { disabledConversation }
            ).operations().single { it.definition.name == "github_pr" }.execute("revoked-after-planning", request)
            assertEquals("GITHUB_COPILOT_DISABLED", revoked.errorCode)
            assertEquals(0, network)
            assertEquals(0, gateway)
        }
    }

    @Test fun retainedPayloadCannotSwitchOwnerGatewayRouteOrRequestId() = runTest {
        var gateway = 0
        var direct = 0
        val store = GitHubRepositoryStateStore()
        HttpClient(
            MockEngine {
                direct++
                error("An owner switch must not contact GitHub")
            }
        ).use { http ->
            val backend: suspend (String, JsonObject) -> JsonObject = { _, _ ->
                gateway++
                gatewayReceipt()
            }
            val accepted = tool(http, enabled, store, backend, "GATEWAY", "gateway-a").execute("first", request)
            assertFalse(accepted.isError)
            val changedId = JsonObject(request + ("request_id" to JsonPrimitive("attempted-new-id")))
            val native = tool(http, enabled, store).execute("native-retry", changedId)
            assertEquals("GITHUB_COPILOT_ROUTE_MISMATCH", native.errorCode)
            assertFalse(native.dispatched)
            val otherGateway = tool(http, enabled, store, backend, "GATEWAY", "gateway-b").execute("other-gateway", request)
            assertEquals("GITHUB_COPILOT_ROUTE_MISMATCH", otherGateway.errorCode)
            assertFalse(otherGateway.dispatched)
            assertEquals(1, gateway)
            assertEquals(0, direct)
        }
    }

    @Test fun changedLiveOwnerRejectsCapturedDirectAndGatewayRoutesBeforeDispatch() = runTest {
        var network = 0
        var gateway = 0
        HttpClient(
            MockEngine {
                network++
                error("A stale captured route cannot dispatch")
            }
        ).use { http ->
            val backend: suspend (String, JsonObject) -> JsonObject = { _, _ ->
                gateway++
                gatewayReceipt()
            }
            for (owner in listOf("ANDROID_DIRECT", "GATEWAY")) {
                var routingValid = true
                val prepared = tool(http, enabled, backend = backend.takeIf { owner == "GATEWAY" }, owner = owner, routingValid = { routingValid })
                routingValid = false
                val denied = prepared.execute("owner-changed", request)
                assertEquals("GITHUB_COPILOT_ROUTE_MISMATCH", denied.errorCode)
                assertFalse(denied.dispatched)
            }
            assertEquals(0, network)
            assertEquals(0, gateway)
        }
    }

    @Test fun revocationDuringRefPreflightPreventsPostAndKnownUnsentClaimCanResume() = runTest {
        var live = enabled
        var revoke = true
        var posts = 0
        HttpClient(
            MockEngine { req ->
                if (req.method == HttpMethod.Post) {
                    posts++
                    respond("""{"id":"accepted-after-renewed-consent","state":"queued"}""")
                } else {
                    if (revoke) live = disabledConversation
                    respond("""{"object":{"type":"commit","sha":"${"a".repeat(40)}"}}""")
                }
            }
        ).use { http ->
            val prepared = tool(http, enabled, liveSettings = { live })
            val denied = prepared.execute("revoked-during-read", request)
            assertEquals("GITHUB_COPILOT_DISABLED", denied.errorCode)
            assertEquals(0, posts)
            revoke = false
            live = enabled
            assertFalse(prepared.execute("renewed-consent", request).isError)
            assertEquals(1, posts)
        }
    }

    @Test fun genericCopilotReviewerEnrollmentRequiresBoundAuthorityAndHonorsLivePreflightRevocation() = runTest {
        var live = enabled
        var reads = 0
        var writes = 0
        val review = buildJsonObject {
            put("action", "request_reviewers")
            put("pull_number", 1)
            put("expected_head_sha", "a".repeat(40))
            put("reviewers", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("copilot-pull-request-reviewer[bot]"))))
        }
        HttpClient(
            MockEngine { req ->
                if (req.method == HttpMethod.Get) reads++ else writes++
                live = disabledConversation
                respond("""{"head":{"sha":"${"a".repeat(40)}"}}""")
            }
        ).use { http ->
            val disabled = tool(http, AppFeatureSettings()).execute("default-off-reviewer", review)
            assertEquals("GITHUB_COPILOT_DISABLED", disabled.errorCode)
            assertEquals(0, reads)
            assertEquals(0, writes)
            val revoked = tool(http, enabled, liveSettings = { live }).execute("reviewer-revoked-during-preflight", review)
            assertEquals("GITHUB_COPILOT_DISABLED", revoked.errorCode)
            assertEquals(1, reads)
            assertEquals(0, writes)
        }
    }

    @Test fun statusRemainsReadOnlyAndAvailableAfterConsentIsRevoked() = runTest {
        var reads = 0
        HttpClient(
            MockEngine { req ->
                assertEquals(HttpMethod.Get, req.method)
                assertEquals("/agents/repos/owner/repo/tasks/existing-task", req.url.encodedPath)
                reads++
                respond("""{"id":"existing-task","state":"in_progress"}""")
            }
        ).use { http ->
            val result = tool(http, AppFeatureSettings()).execute(
                "status-after-revocation",
                buildJsonObject {
                    put("action", "copilot_task_status")
                    put("task_id", "existing-task")
                }
            )
            assertFalse(result.isError)
            assertEquals(1, reads)
        }
    }

    @Test fun gatewayListUsesSelectedOwnerWhileCopilotIsOffAndRejectsUnownedResponses() = runTest {
        var calls = 0
        var verified = true
        HttpClient(MockEngine { error("Gateway discovery must not fall back to native GitHub") }).use { http ->
            val backend: suspend (String, JsonObject) -> JsonObject = { action, _ ->
                assertEquals("copilot_tasks", action)
                calls++
                buildJsonObject {
                    if (verified) {
                        put("execution_owner", "GITHUB_CLOUD")
                        put("orchestration_owner", "GATEWAY")
                    }
                    put("tasks", kotlinx.serialization.json.JsonArray(emptyList()))
                }
            }
            val tool = tool(http, AppFeatureSettings(), backend = backend, owner = "GATEWAY")
            val args = buildJsonObject { put("action", "copilot_tasks") }
            assertFalse(tool.execute("gateway-list", args).isError)
            verified = false
            assertTrue(tool.execute("unowned-list", args).isError)
            assertEquals(2, calls)
        }
    }

    @Test fun gatewayReadAndCreationUseHostAuthorityAfterPhoneGitHubCredentialExpires() = runTest {
        var phoneRefreshes = 0
        var nativeCalls = 0
        val gatewayActions = mutableListOf<String>()
        HttpClient(
            MockEngine {
                nativeCalls++
                error("Gateway work must not send the phone credential")
            }
        ).use { http ->
            val native = GitHubTool(
                "", http, connectionUid = "connection", conversationId = "42", repositoryScope = context, featureSettings = enabled,
                credentialAdmissionFailed = true,
                credentialProvider = {
                    phoneRefreshes++
                    error("Phone GitHub OAuth expired")
                },
                copilotOrchestrationOwner = "GATEWAY", copilotRouteIdentity = "host-owned-account",
                copilotBackend = { action, _ ->
                    gatewayActions += action
                    gatewayReceipt()
                }
            )
            val pr = native.operations().single { it.definition.name == "github_pr" }
            val resumed = pr.execute(
                "host-resume",
                buildJsonObject {
                    put("action", "copilot_task_status")
                    put("gateway_job_id", "saved-gateway-job")
                }
            )
            assertFalse(resumed.isError)
            assertFalse(pr.execute("host-create", request).isError)
            assertEquals(listOf("copilot_task_status", "copilot_create_task"), gatewayActions)
            assertEquals(0, phoneRefreshes)
            assertEquals(0, nativeCalls)
            // The exception applies only to explicit host-owned Copilot actions.
            assertTrue(runCatching { native.execute("native-read", buildJsonObject { put("action", "get_repository") }) }.isFailure)
            assertEquals(1, phoneRefreshes)
            assertEquals(0, nativeCalls)
        }
    }

    @Test fun canonicalFacadeRejectsMissingIdentityAndAmbiguousResumeBeforeBackend() = runTest {
        var network = 0
        HttpClient(
            MockEngine {
                network++
                error("Invalid Copilot arguments cannot dispatch")
            }
        ).use { http ->
            val tool = tool(http, enabled)
            val invalid = listOf(
                JsonObject(request - "request_id"),
                JsonObject(request - "expected_base_sha"),
                JsonObject(request + ("head_ref" to JsonPrimitive("feature"))),
                JsonObject(request + ("title" to JsonPrimitive("Ignored PR title"))),
                buildJsonObject {
                    put("action", "copilot_task_status")
                    put("task_id", "task")
                    put("request_id", "request")
                },
                buildJsonObject {
                    put("action", "copilot_tasks")
                    put("prompt", "Do not create from a read")
                }
            )
            invalid.forEach { args ->
                val denied = tool.execute("invalid", args)
                assertTrue(args.toString(), denied.isError)
                assertFalse(args.toString(), denied.dispatched)
            }
            assertEquals(0, network)
        }
    }

    private fun tool(
        http: HttpClient,
        settings: AppFeatureSettings,
        store: GitHubRepositoryStateStore = GitHubRepositoryStateStore(),
        backend: (suspend (String, JsonObject) -> JsonObject)? = null,
        owner: String = "ANDROID_DIRECT",
        route: String = "gateway-a",
        routingValid: suspend () -> Boolean = { true },
        liveSettings: (suspend () -> AppFeatureSettings)? = null
    ) = GitHubTool(
        "token", http, connectionUid = "connection", conversationId = "42", taskId = "turn", repositoryScope = context, featureSettings = settings,
        stateStore = store, durableIdentity = "account", copilotBackend = backend, copilotOrchestrationOwner = owner, copilotRouteIdentity = route,
        copilotRoutingValid = routingValid, copilotSettings = liveSettings
    ).operations().single { it.definition.name == "github_pr" }

    private fun gatewayReceipt() = buildJsonObject {
        put("execution_owner", "GITHUB_CLOUD")
        put("orchestration_owner", "GATEWAY")
        put("gateway_job_id", "saved-gateway-job")
        put("status", "REMOTE_ACCEPTED")
    }
}
