package dev.melo.gptmobile.improved.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
enum class ClientType(val label: String) {
    OPENAI("OpenAI"),
    ANTHROPIC("Anthropic"),
    GOOGLE("Google"),
    OLLAMA("Ollama"),
    OPENAI_COMPATIBLE("OpenAI Compatible"),
    LM_STUDIO("LM Studio"),
    VLLM("vLLM"),
    LOCAL_RUNTIME("Local Runtime");

    val isCloud: Boolean
        get() = this == OPENAI || this == ANTHROPIC || this == GOOGLE

    val isSelfHosted: Boolean
        get() = this == OLLAMA || this == OPENAI_COMPATIBLE || this == LM_STUDIO || this == VLLM

    val isLocal: Boolean
        get() = this == LOCAL_RUNTIME

    companion object {
        fun fromLabel(label: String): ClientType {
            return entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: OPENAI
        }
    }
}
