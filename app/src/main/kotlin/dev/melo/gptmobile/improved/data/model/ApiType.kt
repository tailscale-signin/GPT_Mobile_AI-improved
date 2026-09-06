package dev.melo.gptmobile.improved.data.model

enum class ApiType {
    OPENAI,
    ANTHROPIC,
    GOOGLE,
    GROQ,
    OLLAMA;

    companion object {
        fun getByValue(value: Int) = entries.firstOrNull { it.ordinal == value }
    }
}
