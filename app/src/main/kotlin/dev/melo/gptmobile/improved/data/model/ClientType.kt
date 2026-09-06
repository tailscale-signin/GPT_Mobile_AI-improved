package dev.melo.gptmobile.improved.data.model

enum class ClientType {
    OPENAI,
    ANTHROPIC,
    GOOGLE,
    GROQ,
    OLLAMA,
    OPENROUTER,
    CUSTOM,
    LITERT_LM;

    companion object {
        fun getByValue(value: Int) = entries.firstOrNull { it.ordinal == value }
    }
}
