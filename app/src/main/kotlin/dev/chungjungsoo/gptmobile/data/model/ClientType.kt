package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ClientType {
    OPENAI,
    ANTHROPIC,
    GOOGLE,
    GROQ,
    OPENROUTER,
    OLLAMA,
    CUSTOM,
    LITERT_LM
}
