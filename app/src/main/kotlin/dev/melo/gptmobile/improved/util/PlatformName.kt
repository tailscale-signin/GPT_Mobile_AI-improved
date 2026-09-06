package dev.melo.gptmobile.improved.util

import android.content.Context
import dev.melo.gptmobile.improved.R
import dev.melo.gptmobile.improved.data.model.ClientType

fun getPlatformName(context: Context, platform: ClientType): String = when (platform) {
    ClientType.OPENAI -> context.getString(R.string.openai)
    ClientType.ANTHROPIC -> context.getString(R.string.anthropic)
    ClientType.GEMINI -> context.getString(R.string.gemini)
    ClientType.DEEPSEEK -> context.getString(R.string.deepseek)
    ClientType.OPENROUTER -> context.getString(R.string.openrouter)
    ClientType.GROQ -> context.getString(R.string.groq)
    ClientType.GITHUB -> context.getString(R.string.github)
    ClientType.PERPLEXITY -> context.getString(R.string.perplexity)
    ClientType.LOCAL_ON_DEVICE -> context.getString(R.string.local_on_device)
    ClientType.OLLAMA -> context.getString(R.string.ollama)
    ClientType.LLAMACPP -> context.getString(R.string.llamacpp)
}
