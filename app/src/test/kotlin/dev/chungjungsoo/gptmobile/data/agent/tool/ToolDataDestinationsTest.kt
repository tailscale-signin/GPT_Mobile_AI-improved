package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataCategory
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataContext
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance
import dev.chungjungsoo.gptmobile.data.permissions.ProvenanceGuardedTool
import dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ToolDataDestinationsTest {
    private val memory = PersonalDataProvenance(
        sourceToolId = "github_engineering_memory",
        sourceProfileUid = "local",
        sourceRef = "repository-memory",
        categories = setOf(PersonalDataCategory.PRIVATE_CONTENT),
        retentionChatId = 7,
        sourceConnectionUid = "github-connection",
        localOnly = true
    )

    private fun tool(name: String, connection: String? = null, native: Boolean = false, capabilities: Set<AgentToolCapability> = emptySet(), execute: () -> Unit = {}): ResolvedAgentTool {
        val implementation = object : AgentTool {
            override val definition = AgentToolDefinition(name, "Test implementation", JsonObject(emptyMap()), capabilities = capabilities)
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                execute()
                return AgentToolResult(callId, ToolResultContent.Text("Repository read"), false)
            }
        }
        return ResolvedAgentTool(implementation, connection, if (connection == null) "GitHub decisions" else "GitHub", name, name, nativeGitHub = native)
    }

    @Test fun hydratedRepositoryMemoryAdmitsLocalDecisionAndAuthorizedGithubReadForLocalClients() = runBlocking {
        for (type in listOf(ClientType.LLAMA, ClientType.OLLAMA, ClientType.LITERT_LM)) {
            val local = PlatformV2(uid = "local", name = "Local", compatibleType = type, apiUrl = "http://127.0.0.1:8080/v1", model = "local")
            // Catalog resolution precedes engineering memory hydration in the real preparation path.
            val context = PersonalDataContext(7, null, authorizedLocalOnlyGitHubConnectionUid = "github-connection")
            val decision = tool("github_decide", capabilities = setOf(AgentToolCapability.USER_DECISION))
            var reads = 0
            val github = tool("github_read", "github-connection", native = true) { reads++ }
            assertNull(decision.externalServiceId())
            assertEquals("github-connection:github_read", github.externalServiceId())
            withContext(context) {
                context.add(listOf(memory))
                validateGitHubToolDestinations(local, listOf(decision, github))
                context.validateModel(local)
                val guarded = ProvenanceGuardedTool(github.tool, local, requireNotNull(github.externalServiceId()), personal = false, external = true) { null }
                assertFalse(guarded.execute("read", JsonObject(emptyMap())).isError)
            }
            assertEquals(1, reads)
        }
    }

    @Test fun externalGithubCallsRemainBlockedForUnrelatedPrivateContext() = runBlocking {
        val local = PlatformV2(uid = "local", name = "Local", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8080/v1")
        for (label in listOf(memory.copy(sourceToolId = "memory"), memory.copy(sourceConnectionUid = "other"), memory.copy(retentionChatId = 8))) {
            var dispatched = false
            withContext(PersonalDataContext(7, null, listOf(label), "github-connection")) {
                val error = runCatching {
                    validateGitHubToolDestinations(local, listOf(tool("github_decide"), tool("github_read", "github-connection", native = true)))
                    dispatched = true
                }.exceptionOrNull()
                assertEquals("PERSONAL_DATA_LOCAL_ONLY", (error as? ToolAuthorizationRejected)?.code)
            }
            assertFalse(dispatched)
        }
    }

    @Test fun localDecisionDoesNotGrantAnExternalToolPermission() = runBlocking {
        val local = PlatformV2(uid = "local", name = "Local", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8080/v1")
        withContext(PersonalDataContext(7, null, listOf(memory), "github-connection")) {
            val error = runCatching { validateGitHubToolDestinations(local, listOf(tool("github_decide", "other-connection"))) }.exceptionOrNull()
            assertEquals("PERSONAL_DATA_LOCAL_ONLY", (error as? ToolAuthorizationRejected)?.code)
        }
        assertEquals("web_search", tool("web_search", capabilities = setOf(AgentToolCapability.WEB_SEARCH)).externalServiceId())
        assertEquals("github_read", tool("github_read", native = true).externalServiceId())
    }
}
