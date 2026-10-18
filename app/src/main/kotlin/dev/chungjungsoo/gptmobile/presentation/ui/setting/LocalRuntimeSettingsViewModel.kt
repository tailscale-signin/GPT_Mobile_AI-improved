package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.localmodel.resolveLocalModelSelection
import dev.chungjungsoo.gptmobile.data.localruntime.LocalAccelerators
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.localruntime.QnnEnvironment
import dev.chungjungsoo.gptmobile.data.localruntime.removeModelFilesIfIdle
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import dev.chungjungsoo.gptmobile.data.model.SpeculativeDecodingMode
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@HiltViewModel
class LocalRuntimeSettingsViewModel @Inject constructor(
    private val repository: SettingRepository,
    private val runtime: LocalRuntime,
    private val models: LocalModelRepository,
    private val chatRepository: dev.chungjungsoo.gptmobile.data.repository.ChatRepository,
    private val comparison: dev.chungjungsoo.gptmobile.data.benchmark.LocalRuntimeBenchmarkSuite,
    private val benchmarkStore: dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    val settings = repository.observeFeatureSettings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppFeatureSettings())
    val backend = repository.observeLocalRuntimeBackend().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LocalRuntimeBackend.DEFAULT)
    val profiles = repository.observePlatformV2s().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _qnnAvailable = MutableStateFlow(false)
    val qnnAvailable = _qnnAvailable.asStateFlow()
    val comparisonProgress = comparison.progress
    val comparisonError = comparison.error
    val runtimeTrials = benchmarkStore.runtimeTrials
    val qualifications = benchmarkStore.runtimeQualifications
    val active = runtime.state
    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val ramGb = runtime.deviceRamGb
    val soc = Build.SOC_MODEL.orEmpty()
    private val updates = Mutex()
    private val _hardware = MutableStateFlow(runtime.getHardwareState())
    val hardware = _hardware.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()
    private val _npuStatus = MutableStateFlow("Checking NPU prerequisites…")
    val npuStatus = _npuStatus.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init {
        refresh()
        viewModelScope.launch { benchmarkStore.load() }
    }

    fun testLocalAssist() = perform {
        _status.value = chatRepository.testLocalAssist()
    }

    fun refresh() {
        viewModelScope.launch {
            _hardware.value = runtime.getHardwareState()
            try {
                val probe = withContext(Dispatchers.IO) { QnnEnvironment.initialize(context) }
                _qnnAvailable.value = probe.isReady
                _npuStatus.value = if (probe.isReady) {
                    "Prerequisites present · ${probe.processAbi} · QNN is not yet verified on this phone"
                } else {
                    probe.errorMessage ?: "NPU unavailable for this device or build"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _npuStatus.value = "NPU prerequisites could not be checked; refresh to retry"
            }
        }
    }

    fun updateAssist(change: (dev.chungjungsoo.gptmobile.data.assist.LocalAssistPolicy) -> dev.chungjungsoo.gptmobile.data.assist.LocalAssistPolicy) = perform {
        repository.mutateFeatureSettings {
            val next = change(it.localAssistPolicy).validated()
            if (next != it.localAssistPolicy) dev.chungjungsoo.gptmobile.data.assist.AssistReadinessResolver.publish(dev.chungjungsoo.gptmobile.data.assist.AssistReadiness.CHECKING, "Selected helper changed; readiness requires verification", next.profileUid)
            it.copy(localAssistPolicy = next)
        }
    }
    fun pinProfile(profile: PlatformV2) = perform { repository.updatePlatformV2(profile.copy(isFavorite = !profile.isFavorite)) }
    fun duplicateProfile(profile: PlatformV2) = perform { repository.addPlatformV2(profile.copy(id = 0, uid = java.util.UUID.randomUUID().toString(), name = "${profile.name} (copy)", isFavorite = false)) }
    suspend fun deleteModelSafely(id: String) {
        dev.chungjungsoo.gptmobile.data.localmodel.LocalModelDownloadPaths.requireValidPathSegments(id)
        val roots = listOfNotNull(context.filesDir, context.getExternalFilesDir(null)).map { java.io.File(it, "models/$id") }
        runtime.removeModelFilesIfIdle(roots) { models.deleteModel(id) }
    }
    fun deleteProfile(profile: PlatformV2) = perform { repository.deletePlatformV2(profile) }
    fun setMemoryReserve(value: Int) = perform { repository.mutateFeatureSettings { it.copy(localMemoryReserveMb = value.coerceIn(256, 4096)) } }

    fun selectBackend(value: LocalRuntimeBackend) = perform {
        require(value != LocalRuntimeBackend.QUALCOMM_QNN || _qnnAvailable.value) { "NPU is unavailable on this device" }
        repository.updateLocalRuntimeBackend(value)
    }
    fun prepareSelectedLocalModel(enabled: Boolean) = perform {
        repository.mutateFeatureSettings { it.copy(readiness = it.readiness.copy(prepareSelectedLocalModel = enabled)) }
    }
    fun updateTuning(
        threads: Int? = null,
        ggufBatch: Int? = null,
        ggufMicroBatch: Int? = null,
        ggufPromptThreads: Int? = null,
        cache: Boolean? = null,
        idle: Int? = null,
        fallback: Boolean? = null,
        speculative: SpeculativeDecodingMode? = null,
        nativeMetrics: Boolean? = null,
        assist: Boolean? = null,
        policy: dev.chungjungsoo.gptmobile.data.localruntime.LocalExecutionPolicy? = null
    ) = perform {
        updates.withLock {
            repository.mutateFeatureSettings { current ->
                current.copy(
                    localExecutionPolicy = policy ?: current.localExecutionPolicy,
                    localCpuThreads = threads?.coerceIn(0, cores) ?: current.localCpuThreads,
                    ggufBatchTokens = ggufBatch?.takeIf { it in setOf(64, 128, 256) } ?: current.ggufBatchTokens,
                    ggufMicroBatchTokens = ggufMicroBatch?.takeIf { it in setOf(64, 128, 256) } ?: current.ggufMicroBatchTokens,
                    ggufPromptThreads = ggufPromptThreads?.coerceIn(0, cores) ?: current.ggufPromptThreads,
                    localModelCache = cache ?: current.localModelCache,
                    localSpeculativeDecoding = speculative ?: current.localSpeculativeDecoding,
                    localNativeMetrics = nativeMetrics ?: current.localNativeMetrics,
                    localAssist = assist ?: current.localAssist,
                    localIdleMinutes = idle?.coerceIn(0, 60) ?: current.localIdleMinutes,
                    qnnAutomaticFallback = fallback ?: current.qnnAutomaticFallback
                )
            }
        }
        _status.value = "Saved. Performance changes apply to the next model load."
    }
    fun compareProfile(uid: String, sustained: Boolean) = perform {
        val profile = repository.fetchPlatformV2s().first { it.uid == uid && it.compatibleType == ClientType.LITERT_LM }
        val selected = models.resolveLocalModelSelection(profile.model, profile.accelerator)
        val metadata = runtime.inspectModel(selected.path)
        val accelerator = LocalAccelerators.normalize(profile.accelerator)
        val base = dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineSpec(
            modelPath = selected.path,
            accelerator = accelerator,
            maxTokens = minOf(metadata?.contextTokens ?: 2048, 4096),
            speculativeDecoding = false,
            nativeMetricsEnabled = true,
            tuningLocked = true
        )
        val backends = metadata?.backends.orEmpty().ifEmpty { setOf(accelerator) }
        val candidates = backends.sorted().filter { it in setOf("cpu", "gpu", "npu") }.flatMap { backend ->
            val candidate = base.copy(accelerator = backend)
            val ordinary = dev.chungjungsoo.gptmobile.data.benchmark.LocalBenchmarkCandidate("${profile.name} / ${backend.uppercase()} / MTP off", candidate)
            if (metadata?.speculative == true && backend == "gpu") listOf(ordinary, dev.chungjungsoo.gptmobile.data.benchmark.LocalBenchmarkCandidate("${profile.name} / GPU / MTP requested", candidate.copy(speculativeDecoding = true))) else listOf(ordinary)
        }
        check(comparison.start(candidates, sustained)) { "Another benchmark is already running" }
        _status.value = "Comparison started. It continues while you use other screens; Stop remains available in the benchmark notification."
    }
    fun stopComparison() = comparison.stop()

    fun verifyProfile(uid: String) = perform {
        val profile = repository.fetchPlatformV2s().first { it.uid == uid && it.compatibleType == ClientType.LITERT_LM }
        val selected = models.resolveLocalModelSelection(profile.model, profile.accelerator)
        _status.value = "Verifying the installed model and selected backend…"
        val verified = runtime.tryRunExclusive {
            val probe = dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineSpec(selected.path, LocalAccelerators.normalize(profile.accelerator), 2048, tuningLocked = true)
            allowVerification(probe)
            val metadata = inspectModel(selected.path)
            val spec = probe.copy(maxTokens = metadata?.takeIf { !it.dynamicContext }?.contextTokens ?: minOf(metadata?.contextTokens ?: 2048, 4096))
            metadata?.validate(spec)
            allowVerification(spec)
            loadEngine(spec)
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("LocalModel", "LOAD_VERIFICATION · outcome=PASSED · requested=${spec.accelerator} · effective=${loadedEngineSpec()?.accelerator ?: "unknown"} · inferenceQualified=false")
            true
        }
        _status.value = if (verified == true) "Model loaded successfully and is ready for a test response. Load verification alone does not qualify performance or NPU execution." else "Local model is busy. Try Verify load after the current response finishes."
    }

    fun clearRuntimeEvidence() = perform { benchmarkStore.clearRuntimeEvidence() }

    fun releaseIdleMemory() = perform {
        _status.value = if (runtime.unloadIfIdle(0L)) "Idle model memory released" else "No idle model to release; active responses are kept running"
    }
    fun createProfile(entry: CatalogEntry, onCreated: (String) -> Unit) = perform {
        val modelPath = checkNotNull(models.resolveDownloadedPath(entry.id)) { "Download this model first" }
        val capabilities = runtime.inspectModel(modelPath)
        val declared = capabilities?.backends.orEmpty()
        val inspected = if (declared.isEmpty()) {
            entry
        } else {
            entry.copy(
                supportedAccelerators = if (entry.supportedAccelerators.isEmpty()) declared.sorted() else entry.supportedAccelerators.filter { it in declared },
                socToModelFiles = if (entry.socToModelFiles.isEmpty() && "npu" in declared && !capabilities?.soc.isNullOrBlank()) {
                    mapOf(requireNotNull(capabilities?.soc) to dev.chungjungsoo.gptmobile.data.catalog.SocVariant(modelFile = java.io.File(modelPath).name, contextSize = capabilities?.contextTokens ?: entry.maxContextTokens))
                } else {
                    entry.socToModelFiles
                }
            )
        }
        val support = dev.chungjungsoo.gptmobile.data.catalog.LocalCatalogCompatibilityResolver.resolve(inspected, dev.chungjungsoo.gptmobile.BuildConfig.GENIEX_ENABLED, soc, installed = true)
        require(support.reasons.isEmpty()) { support.reasons.joinToString("; ") }
        dev.chungjungsoo.gptmobile.data.localmodel.LocalModelCompatibility.installedPackageIssue(entry.downloadUrl, modelPath)?.let { error("Blocked package: $it") }
        val existing = repository.fetchPlatformV2s().firstOrNull { it.compatibleType == ClientType.LITERT_LM && it.model == entry.id }
        if (existing != null) {
            onCreated(existing.uid)
            return@perform
        }
        check(LocalAccelerators.choices(inspected, soc).any { it.enabled }) { "This package does not match this phone. Choose a compatible download in the marketplace." }
        val profile = PlatformV2(
            name = entry.displayName,
            compatibleType = ClientType.LITERT_LM,
            model = entry.id,
            accelerator = LocalAccelerators.defaultFrom(inspected.supportedAccelerators, inspected.socToModelFiles, soc),
            maxTokens = minOf(entry.defaultConfig.maxTokens, capabilities?.contextTokens ?: entry.defaultConfig.maxTokens).coerceAtLeast(1),
            temperature = entry.defaultConfig.temperature,
            topP = entry.defaultConfig.topP,
            topK = entry.defaultConfig.topK,
            // Native tool support and the app prompt bridge are separate capabilities.
            // Explicit user denials on existing profiles are never migrated here.
            disableAllTools = false
        )
        repository.addPlatformV2(profile)
        _status.value = "AI profile created"
        onCreated(profile.uid)
    }
    private fun perform(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _status.value = error.message ?: "Could not apply this change. Please try again."
            } finally {
                _busy.value = false
            }
        }
    }
}
