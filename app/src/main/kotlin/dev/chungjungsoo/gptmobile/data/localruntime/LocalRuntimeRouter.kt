package dev.chungjungsoo.gptmobile.data.localruntime

import android.util.Log
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/** Selects the runtime, owns QNN fallback, and publishes the engine that actually loaded. */
class LocalRuntimeRouter(
    private val settingRepository: SettingRepository? = null,
    private val qnnRuntime: LocalRuntime,
    private val liteRtRuntime: LocalRuntime,
    private val artifactRuntime: LocalRuntime? = null,
    private val settingsSnapshot: (() -> dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings)? = null,
    private val backendSnapshot: (() -> LocalRuntimeBackend)? = null
) : LocalRuntime {
    override val handlesEngineFallback = true
    private val _state = MutableStateFlow(LocalRuntimeState())
    override val state = _state.asStateFlow()

    @Volatile private var activeLoadedRuntime: LocalRuntime? = null
    private var requestedSpec: LocalEngineSpec? = null
    private var delegatedSpec: LocalEngineSpec? = null
    private var preferenceAtLoad: LocalRuntimeBackend? = null
    private var tuningAtLoad: LocalEngineTuning? = null

    override suspend fun inspectModel(modelPath: String): LocalModelCapabilities? = if (modelPath.endsWith("manifest.json")) {
        checkNotNull(artifactRuntime) { "This build does not include an artifact runtime" }.inspectModel(modelPath)
    } else {
        liteRtRuntime.inspectModel(modelPath)
    }

    override val deviceRamGb: Long get() = liteRtRuntime.deviceRamGb
    override fun getHardwareState(): DeviceHardwareState = liteRtRuntime.getHardwareState()
    override fun getAdaptiveThrottlingPolicy(): AdaptiveThrottlingPolicy =
        liteRtRuntime.getAdaptiveThrottlingPolicy()
    override fun loadedEngineSpec(): LocalEngineSpec? = state.value.engineSpec

    override suspend fun allowVerification(spec: LocalEngineSpec) {
        if (spec.modelPath.endsWith("manifest.json")) {
            checkNotNull(artifactRuntime).allowVerification(spec)
        } else if (spec.accelerator == LocalAccelerators.NPU) {
            qnnRuntime.allowVerification(spec)
        } else {
            liteRtRuntime.allowVerification(spec)
        }
    }

    override suspend fun loadEngine(requested: LocalEngineSpec) {
        if (isEngineLoaded(requested)) return
        val tuning = featureSettings().localEngineTuning()
        val spec = if (requested.tuningLocked) {
            requested
        } else {
            requested.copy(
                cpuThreads = tuning.cpuThreads,
                cacheEnabled = tuning.cacheEnabled,
                speculativeDecoding = tuning.speculativeDecoding,
                nativeMetricsEnabled = tuning.nativeMetricsEnabled
            )
        }
        val preferred = preferredBackend()
        unloadEngine()
        try {
            if (spec.modelPath.endsWith("manifest.json")) {
                val runtime = checkNotNull(artifactRuntime) { "This build does not include an artifact runtime" }
                dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCapabilityRegistry.requireRuntime(dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore.read(java.io.File(spec.modelPath)).runtime)
                runtime.loadEngine(spec)
                val family = dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore.read(java.io.File(spec.modelPath)).runtime
                activate(
                    runtime,
                    if (family == dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime.GENIEX_QAIRT) {
                        LocalRuntimeBackend.GENIEX_QAIRT
                    } else if (family == dev.chungjungsoo.gptmobile.data.localmodel.ArtifactRuntime.LLAMA_CPP || !dev.chungjungsoo.gptmobile.BuildConfig.GENIEX_ENABLED) {
                        LocalRuntimeBackend.LLAMA_CPP
                    } else {
                        LocalRuntimeBackend.GENIEX_LLAMA_CPP
                    },
                    requested,
                    spec,
                    preferred
                )
                return
            }
            if (LocalAccelerators.normalize(spec.accelerator) == LocalAccelerators.NPU) {
                try {
                    qnnRuntime.loadEngine(spec)
                    activate(qnnRuntime, LocalRuntimeBackend.QUALCOMM_QNN, requested, spec, preferred)
                    return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (error !is Exception && error !is LinkageError) throw error
                    qnnRuntime.unloadEngine()
                    if (error is LocalModelConfigurationException) throw error
                    if (dev.chungjungsoo.gptmobile.data.localmodel.LocalModelPackages.isCompiledNpuPackage(spec.modelPath)) {
                        throw LocalNpuPackageException(error)
                    }
                    if (!featureSettings().qnnAutomaticFallback) {
                        throw LocalRuntimeFallbackDisabledException(error)
                    }
                    Log.w(TAG, "QNN initialization failed; trying a compatible LiteRT-LM accelerator", error)
                    val fallback = loadLiteRt(spec.copy(accelerator = LocalAccelerators.GPU, litertDispatchLibDir = null), error)
                    activate(liteRtRuntime, LocalRuntimeBackend.LITERT_LM, requested, fallback, preferred, error.message)
                    return
                }
            }
            val actual = loadLiteRt(spec.copy(litertDispatchLibDir = null))
            activate(
                liteRtRuntime,
                LocalRuntimeBackend.LITERT_LM,
                requested,
                actual,
                preferred,
                if (actual.accelerator != spec.accelerator) "${spec.accelerator.uppercase()} initialization failed" else null
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) { unloadEngine() }
            throw error
        }
    }

    private suspend fun loadLiteRt(spec: LocalEngineSpec, prior: Throwable? = null): LocalEngineSpec {
        val metadata = liteRtRuntime.inspectModel(spec.modelPath)
        val accelerators = compatibleFallbacks(spec.accelerator, metadata?.backends)
        val attempts = mutableListOf<LocalLoadAttempt>()
        if (prior != null) attempts += LocalLoadAttempt.failed("npu", "initialize", prior)
        var primaryError: Throwable? = prior
        val excluded = listOf(spec.accelerator, LocalAccelerators.CPU).distinct().filter { it !in accelerators }
        val exclusions = excluded.map {
            LocalLoadAttempt(it, "capability_preflight", "UnsupportedBackend", "This package does not declare $it support. Download a separate compatible export.", skipped = true)
        }
        for (accelerator in accelerators) {
            val candidate = spec.copy(
                accelerator = accelerator,
                litertDispatchLibDir = spec.litertDispatchLibDir.takeIf { accelerator == LocalAccelerators.NPU }
            )
            try {
                liteRtRuntime.loadEngine(candidate)
                return candidate
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (error !is Exception && error !is LinkageError) throw error
                if (error is LocalModelConfigurationException) throw error
                if (primaryError == null) primaryError = error
                attempts += LocalLoadAttempt.failed(accelerator, "initialize", error)
                liteRtRuntime.unloadEngine()
            }
        }
        val failure = LocalLoadFailure(attempts + exclusions, primaryError)
        if (dev.chungjungsoo.gptmobile.data.localmodel.LocalModelPackages.isCompiledNpuPackage(spec.modelPath)) {
            throw LocalNpuPackageException(failure)
        }
        throw failure
    }

    private fun activate(
        runtime: LocalRuntime,
        backend: LocalRuntimeBackend,
        requested: LocalEngineSpec,
        delegated: LocalEngineSpec,
        preference: LocalRuntimeBackend,
        fallbackReason: String? = null
    ) {
        activeLoadedRuntime = runtime
        requestedSpec = requested
        delegatedSpec = delegated
        preferenceAtLoad = preference
        tuningAtLoad = LocalEngineTuning(delegated.cpuThreads, delegated.cacheEnabled, delegated.speculativeDecoding, delegated.nativeMetricsEnabled)
        _state.value = LocalRuntimeState(backend, runtime.loadedEngineSpec() ?: delegated, fallbackReason, preference)
    }

    override suspend fun isEngineLoaded(spec: LocalEngineSpec): Boolean {
        val runtime = activeLoadedRuntime ?: return false
        // Read the current preference so switching settings invalidates a warm engine.
        if (preferenceAtLoad != preferredBackend() ||
            requestedSpec?.engineConfiguration() != spec.engineConfiguration() ||
            (!spec.tuningLocked && tuningAtLoad != featureSettings().localEngineTuning())
        ) {
            return false
        }
        return delegatedSpec?.let { runtime.isEngineLoaded(it) } == true
    }

    override suspend fun createConversation(config: LocalConversationConfig) {
        checkNotNull(activeLoadedRuntime) { "Local engine is not loaded" }.createConversation(config)
    }

    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> =
        activeLoadedRuntime?.sendMessage(text, images) ?: flowOf(LocalRuntimeEvent.Error("Local engine is not loaded"))

    override fun cancelActive() {
        activeLoadedRuntime?.cancelActive()
    }

    override fun hasOpenConversation(): Boolean = activeLoadedRuntime?.hasOpenConversation() == true

    override suspend fun closeConversation() {
        activeLoadedRuntime?.closeConversation()
    }

    override suspend fun unloadEngine() {
        activeLoadedRuntime = null
        requestedSpec = null
        delegatedSpec = null
        preferenceAtLoad = null
        tuningAtLoad = null
        _state.value = LocalRuntimeState()
        artifactRuntime?.unloadEngine()
        try {
            qnnRuntime.unloadEngine()
        } finally {
            liteRtRuntime.unloadEngine()
        }
    }

    private suspend fun featureSettings() = settingsSnapshot?.invoke() ?: checkNotNull(settingRepository).getFeatureSettings()
    private suspend fun preferredBackend() = backendSnapshot?.invoke() ?: checkNotNull(settingRepository).getLocalRuntimeBackend()

    private companion object {
        const val TAG = "LocalRuntimeRouter"
    }
}

internal data class LocalEngineTuning(
    val cpuThreads: Int?,
    val cacheEnabled: Boolean,
    val speculativeDecoding: Boolean?,
    val nativeMetricsEnabled: Boolean
)

internal fun dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings.localEngineTuning(
    availableCores: Int = Runtime.getRuntime().availableProcessors()
): LocalEngineTuning = LocalEngineTuning(
    localCpuThreads.takeIf { it > 0 }?.coerceIn(1, availableCores.coerceIn(1, 64)),
    localModelCache,
    localSpeculativeDecoding.enabled,
    localNativeMetrics
)
