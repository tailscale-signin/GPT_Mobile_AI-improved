package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore
import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime
import java.io.File

internal class ArtifactRuntimeMultiplexer(private val gguf: LocalRuntime, private val preview: LocalRuntime) : LocalRuntime {
    private var active: LocalRuntime? = null
    private fun runtime(path: String): LocalRuntime = if (ArtifactManifestStore.read(File(path)).runtime == ArtifactRuntime.LLAMA_CPP) gguf else preview
    override suspend fun inspectModel(modelPath: String) = runtime(modelPath).inspectModel(modelPath)
    override suspend fun loadEngine(spec: LocalEngineSpec) {
        unloadEngine()
        runtime(spec.modelPath).also {
            it.loadEngine(spec)
            active = it
        }
    }
    override suspend fun isEngineLoaded(spec: LocalEngineSpec) = active?.isEngineLoaded(spec) == true
    override fun loadedEngineSpec() = active?.loadedEngineSpec()
    override fun hasOpenConversation() = active?.hasOpenConversation() == true
    override suspend fun createConversation(config: LocalConversationConfig) = checkNotNull(active).createConversation(config)
    override fun sendMessage(text: String, images: List<ByteArray>) = checkNotNull(active).sendMessage(text, images)
    override fun cancelActive() {
        active?.cancelActive()
    }
    override suspend fun closeConversation() {
        active?.closeConversation()
    }
    override suspend fun unloadEngine() {
        active = null
        try {
            gguf.unloadEngine()
        } finally {
            preview.unloadEngine()
        }
    }
    override suspend fun allowVerification(spec: LocalEngineSpec) = runtime(spec.modelPath).allowVerification(spec)
}
