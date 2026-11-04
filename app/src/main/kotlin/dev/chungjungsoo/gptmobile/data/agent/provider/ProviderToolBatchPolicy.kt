package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.model.ClientType

/** Bound generation before the parser's 128-slot emergency guard can be reached. */
internal object ProviderToolBatchPolicy {
    const val PREFERRED_MAX_CALLS = 8
    const val INSTRUCTIONS = "Return at most 8 tool calls in one response. Wait for their results before requesting the next batch. Never expand a repository inventory or pagination sequence into a single large tool batch. These are per-response limits; continue the requested task within its remaining total tool budget."

    /** Emit native controls only on provider protocols that implement this parameter. */
    fun parallelToolCalls(provider: ClientType, hasTools: Boolean): Boolean? =
        if (hasTools && provider in setOf(ClientType.OPENAI, ClientType.OPENROUTER, ClientType.LLAMA, ClientType.GROQ)) false else null
}
