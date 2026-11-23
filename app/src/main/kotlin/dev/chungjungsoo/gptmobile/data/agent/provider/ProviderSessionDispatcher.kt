package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProgressDeadlineSettings
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType

/** Local-only preparation stays lazy: a cloud request must never bind or load a local tool. */
internal data class LocalProviderSessionOptions(
    val tools: List<AgentTool> = emptyList(),
    val fallbackSystemPrompt: String? = null,
    val progressSettings: ProgressDeadlineSettings = ProgressDeadlineSettings(),
    val advertisedTools: List<AgentToolDefinition>? = null,
    val githubMode: Boolean = false
)

internal fun interface RemoteProviderSessionFactory {
    suspend fun open(turns: List<ConversationTurn>, platform: PlatformV2, constraints: RequestConstraints): AgentProviderSession
}

internal fun interface LocalProviderSessionFactory {
    suspend fun open(turns: List<ConversationTurn>, platform: PlatformV2, constraints: RequestConstraints, options: LocalProviderSessionOptions): AgentProviderSession
}

/**
 * One selected-provider boundary for primary, research, delegate and benchmark sessions.
 * Authorization, accounting and recovery remain with the caller. This boundary never
 * falls back to another model or rewrites the accepted request's constraints.
 */
internal class ProviderSessionDispatcher(
    private val openAI: RemoteProviderSessionFactory,
    private val compatible: RemoteProviderSessionFactory,
    private val anthropic: RemoteProviderSessionFactory,
    private val gemini: RemoteProviderSessionFactory,
    private val local: LocalProviderSessionFactory
) {
    suspend fun openSession(
        turns: List<ConversationTurn>,
        platform: PlatformV2,
        constraints: RequestConstraints,
        localOptions: suspend () -> LocalProviderSessionOptions = { LocalProviderSessionOptions() }
    ): AgentProviderSession = when (platform.compatibleType) {
        ClientType.OPENAI -> openAI.open(turns, platform, constraints)
        ClientType.NVIDIA, ClientType.GROQ, ClientType.OLLAMA, ClientType.OPENROUTER, ClientType.CUSTOM, ClientType.LLAMA, ClientType.FREE -> compatible.open(turns, platform, constraints)
        ClientType.ANTHROPIC -> anthropic.open(turns, platform, constraints)
        ClientType.GOOGLE -> gemini.open(turns, platform, constraints)
        ClientType.LITERT_LM -> local.open(turns, platform, constraints, localOptions())
    }
}
