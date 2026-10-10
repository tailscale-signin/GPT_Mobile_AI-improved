package dev.chungjungsoo.gptmobile.data.localruntime

/** Defaults only: explicit profile sampler values always win. Formatting belongs to the SDK. */
internal object QwenProfilePolicy {
    fun isQwen3(modelId: String, path: String): Boolean = Regex("(?i)qwen3(?:[-_. ]|$)").containsMatchIn("$modelId ${path.substringAfterLast('/')}")

    fun sampler(thinking: Boolean): LocalSamplerConfig = if (thinking) {
        LocalSamplerConfig(topK = 20, topP = .95f, temperature = .6f)
    } else {
        LocalSamplerConfig(topK = 20, topP = .8f, temperature = .7f)
    }
}
