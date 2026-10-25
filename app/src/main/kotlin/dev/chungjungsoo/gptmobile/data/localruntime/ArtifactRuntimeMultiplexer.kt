package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore
import dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime
import java.io.File

internal class ArtifactRuntimeMultiplexer(
    private val gguf: LocalRuntime,
    private val preview: LocalRuntime
) : LocalRuntime {
    override val isNativeOperationBusy: Boolean get() = gguf.isNativeOperationBusy || preview.isNativeOperationBusy
    private var active: LocalRuntime? = null
    private var lastRuntime: LocalRuntime? = null
    private var lastInspectionRuntime: LocalRuntime? = null
    override val latestInspectionReceipt get() = lastInspectionRuntime?.latestInspectionReceipt
    override val latestEngineLoadReceipt get() = (active ?: lastRuntime)?.latestEngineLoadReceipt
    override val latestExecutionReceipt get() = (active ?: lastRuntime)?.latestExecutionReceipt
    override val latestCleanupReceipt get() = (active ?: lastRuntime)?.latestCleanupReceipt
    override val executionIdentity get() = active?.executionIdentity ?: LocalRuntimeExecutionIdentity()

    private fun runtime(path: String): LocalRuntime =
        if (ArtifactManifestStore.read(File(path)).runtime ==
            ArtifactRuntime.LLAMA_CPP
        ) {
            gguf
        } else {
            preview
        }

    override suspend fun inspectModel(modelPath: String) = runtime(modelPath).let {
        lastInspectionRuntime = it
        it.inspectModel(modelPath)
    }
    override suspend fun inspectModel(spec: LocalEngineSpec) = runtime(spec.modelPath).let {
        lastInspectionRuntime = it
        it.inspectModel(spec)
    }

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        unloadEngine()
        runtime(spec.modelPath).also {
            lastRuntime = it
            lastInspectionRuntime = it
            it.loadEngine(spec)
            active = it
        }
    }

    override suspend fun isEngineLoaded(spec: LocalEngineSpec) = active?.isEngineLoaded(spec) == true

    override val latestAllocationObservation get() = active?.latestAllocationObservation
    override val latestBackendFallbackReason get() = active?.latestBackendFallbackReason

    override fun setLoadProgressObserver(observer: ((GgufNativeProgress) -> Unit)?) {
        gguf.setLoadProgressObserver(observer)
        preview.setLoadProgressObserver(observer)
    }

    override fun loadedEngineSpec() = active?.loadedEngineSpec()

    override fun hasOpenConversation() = active?.hasOpenConversation() == true

    override suspend fun createConversation(config: LocalConversationConfig) = checkNotNull(active).createConversation(config)

    override fun sendMessage(
        text: String,
        images: List<ByteArray>
    ) = checkNotNull(active).sendMessage(text, images)

    override fun sendMessageWithBudget(
        text: String,
        images: List<ByteArray>,
        safetyTokens: Int
    ) = checkNotNull(active).sendMessageWithBudget(text, images, safetyTokens)

    override suspend fun cleanupAfterRequest(origin: String): RuntimeCleanupReceipt =
        (active ?: lastRuntime)?.cleanupAfterRequest(origin) ?: RuntimeCleanupReceipt(origin, failureReason = "No observed runtime cleanup")

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
