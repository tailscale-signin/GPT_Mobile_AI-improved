package dev.melo.gptmobile.improved.data

import dev.melo.gptmobile.improved.R
import dev.melo.gptmobile.improved.data.model.ApiType

object ModelConstants {
    val openAiModels: List<String> = listOf(
        "gpt-5",
        "gpt-5-mini",
        "gpt-5-nano",
        "gpt-4.1",
        "gpt-4.1-mini",
        "gpt-4.1-nano",
        "gpt-4o",
        "gpt-4o-mini",
        "o1",
        "o1-mini",
        "o3-mini",
        "chatgpt-4o-latest",
        "gpt-4-turbo",
        "gpt-4",
        "gpt-3.5-turbo"
    )

    val googleModels: List<String> = listOf(
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        "gemini-2.0-flash-lite",
        "gemini-1.5-pro",
        "gemini-1.5-flash"
    )

    val anthropicModels: List<String> = listOf(
        "claude-3-7-sonnet-latest",
        "claude-3-5-sonnet-latest",
        "claude-3-5-haiku-latest",
        "claude-3-opus-latest",
        "claude-3-sonnet-20240229",
        "claude-3-haiku-20240307"
    )

    val openAiThinkingModels: List<String> = listOf(
        "o1",
        "o1-mini",
        "o3-mini"
    )

    val googleThinkingModels: List<String> = listOf(
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "gemini-2.0-flash-thinking-exp"
    )

    val anthropicThinkingModels: List<String> = listOf(
        "claude-3-7-sonnet-latest"
    )

    val apiTypeList: List<ApiType> = listOf(
        ApiType("OpenAI", openAiModels, R.string.openai_description, R.drawable.openai, R.string.openai_help_url),
        ApiType("Google", googleModels, R.string.gemini_description, R.drawable.google, R.string.gemini_help_url),
        ApiType("Anthropic", anthropicModels, R.string.anthropic_description, R.drawable.anthropic, R.string.anthropic_help_url),
        ApiType("Ollama", listOf(), R.string.ollama_description, R.drawable.ollama, R.string.ollama_help_url),
        ApiType("OpenAI Compatible", listOf(), R.string.openai_compatible_description, R.drawable.openai, R.string.openai_compatible_help_url),
        ApiType("LM Studio", listOf(), R.string.lmstudio_description, R.drawable.lmstudio, R.string.lmstudio_help_url),
        ApiType("vLLM", listOf(), R.string.vllm_description, R.drawable.vllm, R.string.vllm_help_url),
        ApiType("GGUF Model", listOf(), R.string.gguf_description, R.drawable.ai, R.string.gguf_help_url),
        ApiType("Local Runtime", listOf(), R.string.local_runtime_description, R.drawable.ai, R.string.local_runtime_help_url)
    )
}
