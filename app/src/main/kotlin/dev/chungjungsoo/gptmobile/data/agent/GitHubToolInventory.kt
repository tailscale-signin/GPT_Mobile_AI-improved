package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubProviderPolicy
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.githubOperationFamily
import dev.chungjungsoo.gptmobile.data.github.isGatewayRepositoryTool
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayToolInventory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/** Read-only views of registered contracts, admitted model schemas and independently observed Gateway tools. */
internal object GitHubToolInventory {
    data class Tool(
        val name: String,
        val family: String,
        val description: String,
        val actions: List<String>,
        val inputFields: List<String> = emptyList(),
        val sourceId: String? = null,
        val authorized: Boolean? = null,
        val enabled: Boolean? = null,
        val executableNow: Boolean? = null,
        val blockedReason: String? = null
    )

    val registered: List<Tool> by lazy {
        val github = AppFeatureInventory.gitHubCatalog
        val packaged = AppFeatureInventory.packagedNativeTools
        val packagedActions = AppFeatureInventory.actionsByTool(packaged.values)
        (AppFeatureInventory.toolProducers.keys + github.keys).sorted().map { name ->
            val definition = packaged[name]
            val description = AppFeatureInventory.gitHubDescriptions[name] ?: definition?.description.orEmpty()
            Tool(name, GitHubOperationTool.operationName(name) ?: name, description, github[name] ?: packagedActions[name].orEmpty(), definition?.let(::fields).orEmpty())
        }
    }

    private fun fields(definition: AgentToolDefinition): List<String> = (definition.inputSchema["properties"] as? JsonObject)?.keys.orEmpty().sorted()

    /** Read exactly the supplied, visible contracts. An alias collision cannot combine different schemas. */
    fun mounted(definitions: Collection<AgentToolDefinition>): List<Tool> {
        val visible = definitions.filter { it.modelVisible }.groupBy { it.name }.values.mapNotNull { group ->
            group.first().takeIf { first -> group.all { it == first } }
        }
        val actions = AppFeatureInventory.actionsByTool(visible)
        return visible.map { definition ->
            val family = when {
                AgentToolCapability.GITHUB in definition.capabilities -> GitHubProviderPolicy.equivalent(definition.name)
                definition.name.isGatewayRepositoryTool() -> definition.name.githubOperationFamily()
                else -> GitHubOperationTool.operationName(definition.name)
            }
            Tool(
                definition.name,
                family ?: definition.name,
                definition.description,
                actions[definition.name].orEmpty(),
                fields(definition),
                authorized = true
            )
        }
    }

    fun gateway(inventory: GatewayToolInventory): List<Tool> = inventory.tools.map { tool ->
        Tool(
            name = tool.name,
            family = tool.name,
            description = tool.description.orEmpty(),
            actions = emptyList(),
            inputFields = tool.inputFields,
            sourceId = tool.sourceId,
            authorized = tool.authorized,
            enabled = tool.enabled.takeUnless { inventory.scope == "legacy_gateway_mcp" },
            executableNow = tool.executableNow,
            blockedReason = tool.blockedReason
        )
    }

    data class Observation(
        val runId: String,
        val chatId: Int,
        val observedAtMillis: Long,
        val tools: List<Tool>,
        private val connectionUid: String,
        private val owner: String,
        private val repo: String,
        private val ref: String,
        private val profileUid: String
    ) {
        fun matches(chatId: Int, workspace: GitHubConversationContext): Boolean =
            this.chatId == chatId &&
                connectionUid == workspace.connectionUid &&
                profileUid == workspace.mainProfileUid &&
                owner.equals(workspace.owner, true) &&
                repo.equals(workspace.repo, true) &&
                ref == workspace.ref
    }

    private val recent = MutableStateFlow(emptyList<Observation>())
    val observations = recent.asStateFlow()

    fun forgetChat(chatId: Int) = recent.update { rows -> rows.filterNot { it.chatId == chatId } }

    suspend fun recordDiscovered(chatId: Int?, scope: GitHubConversationContext?, acquired: List<ResolvedAgentTool>) {
        if (chatId == null || scope == null || acquired.isEmpty()) return
        val runId = kotlinx.coroutines.currentCoroutineContext()[ToolRunContext]?.runId ?: return
        if (recent.value.none { it.runId == runId && it.matches(chatId, scope) }) return
        val sources = acquired.associate { it.modelToolName to it.connectionUid }
        val additional = mounted(acquired.map { it.tool.definition }).map { it.copy(description = "", sourceId = sources[it.name]) }
        if (additional.isEmpty()) return
        recent.update { rows ->
            rows.map { observed ->
                if (observed.runId == runId && observed.matches(chatId, scope)) {
                    observed.copy(observedAtMillis = System.currentTimeMillis(), tools = (additional + observed.tools).distinctBy(Tool::name))
                } else {
                    observed
                }
            }
        }
    }

    /** Metadata from actual admission, without executors, credentials, repository contents or remote descriptions. */
    fun record(runId: String, chatId: Int, scope: GitHubConversationContext, definitions: List<AgentToolDefinition>, sourceIds: Map<String, String?>) {
        if (chatId <= 0 || runId.isBlank()) return
        val tools = mounted(definitions).map { it.copy(description = "", sourceId = sourceIds[it.name]) }
        val observed = Observation(runId, chatId, System.currentTimeMillis(), tools, scope.connectionUid, scope.owner, scope.repo, scope.ref, scope.mainProfileUid)
        recent.update { rows -> (listOf(observed) + rows.filterNot { it.runId == runId }).take(8) }
    }
}
