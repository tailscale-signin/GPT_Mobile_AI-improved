package dev.melo.gptmobile.improved.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
enum class ClientType(val label: String) {
    OPENAI("OpenAI"),
    ANTHROPIC("Anthropic"),
    GOOGLE("Google"),
    GROQ("Groq"),
    OLLAMA("Ollama"),
    OPENAI_COMPATIBLE("OpenAI Compatible"),
    LM_STUDIO("LM Studio"),
    VLLM("vLLM"),
    OPENROUTER("OpenRouter"),
    CUSTOM("Custom"),
    LITERT_LM("Local Runtime");

    val isCloud: Boolean
        get() = this == OPENAI || this == ANTHROPIC || this == GOOGLE || this == GROQ

    val isSelfHosted: Boolean
        get() = this == OLLAMA || this == OPENAI_COMPATIBLE || this == LM_STUDIO || this == VLLM

    val isLocal: Boolean
        get() = this == LITERT_LM

    companion object {
        fun fromLabel(label: String): ClientType {
            return entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: OPENAI
        }
    }
}
