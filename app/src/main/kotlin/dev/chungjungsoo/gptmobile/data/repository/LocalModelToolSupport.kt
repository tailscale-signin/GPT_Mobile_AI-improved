package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime

/** Tool schemas are only useful when the selected native artifact can dispatch tool calls. */
internal object LocalModelToolSupport {
    fun supportsTools(runtime: ArtifactRuntime): Boolean =
        runtime !in setOf(ArtifactRuntime.LLAMA_CPP, ArtifactRuntime.GENIEX_LLAMA_CPP)
}
