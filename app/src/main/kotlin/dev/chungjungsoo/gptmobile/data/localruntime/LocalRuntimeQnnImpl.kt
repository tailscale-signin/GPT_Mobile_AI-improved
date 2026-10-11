package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import dev.chungjungsoo.gptmobile.BuildConfig
import java.io.File
import kotlinx.coroutines.flow.Flow

internal interface QnnLoadGuard {
    fun beforeLoad(spec: LocalEngineSpec)
    fun loadFinished()
    fun allowVerification(spec: LocalEngineSpec) {
        error("Verification reset unavailable")
    }
}

/**
 * A native signal cannot be caught by Kotlin. Leave a marker only while QAIRT is
 * initializing or generating so the next process can quarantine the same crashing tuple.
 */
internal class QnnInitializationCrashGuard(context: Context) : QnnLoadGuard {
    private val app = context.applicationContext
    private val marker = File(app.noBackupFilesDir, "qnn_dispatch/native-init.marker")
    private val journal = NativeExitEvidence.journal(app, File(app.noBackupFilesDir, "qnn_dispatch/operations"))

    private fun signature(spec: LocalEngineSpec): String {
        val model = File(spec.modelPath)
        val dispatchDirectory = spec.litertDispatchLibDir?.let(::File) ?: File(app.applicationInfo.nativeLibraryDir)
        val dispatchHash = runCatching {
            dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.sha256(
                File(dispatchDirectory, "libLiteRtDispatch_Qualcomm.so")
            )
        }.getOrDefault("unavailable")
        return listOf(
            BuildConfig.LITERT_LM_VERSION,
            BuildConfig.QAIRT_VERSION,
            dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.journalIdentity(model, marker.parentFile!!),
            android.os.Build.SOC_MODEL,
            dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.sha256(android.os.Build.FINGERPRINT.orEmpty().toByteArray()),
            spec.accelerator,
            spec.maxTokens,
            dispatchHash
        ).joinToString("|")
    }

    override fun allowVerification(spec: LocalEngineSpec) {
        check(isInferenceProcess()) { "Verification reset requires the isolated worker" }
        journal.allowVerification(signature(spec))
    }

    override fun beforeLoad(spec: LocalEngineSpec) {
        val signature = signature(spec)
        if (marker.isFile) {
            // Migrate the previous marker, dropping app-install fields and paths.
            journal.quarantine(marker.readText().split('|').takeLast(8).take(6).joinToString("|"))
            check(marker.delete())
        }
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.checkpoint("QNN", "QNN_JOURNAL_INSPECT · ${journal.diagnostic()}")
        journal.before(signature)
    }

    override fun loadFinished() {
        journal.finished()
    }
}

/** Qualcomm NPU execution through LiteRT-LM's dispatch API. No hidden CPU/GPU fallback. */
internal class LocalRuntimeQnnImpl(
    private val runtime: LocalRuntime,
    private val loadGuard: QnnLoadGuard,
    private val probeEnvironment: () -> QnnEnvironment.QnnProbeStatus,
    private val enabled: Boolean = !BuildConfig.GENIEX_ENABLED
) : LocalRuntime {
    constructor(context: Context) : this(
        runtime = LocalRuntimeImpl(context),
        loadGuard = QnnInitializationCrashGuard(context),
        probeEnvironment = { QnnEnvironment.prepareForExecution(context) }
    )

    private var requestedSpec: LocalEngineSpec? = null
    private var dispatchedSpec: LocalEngineSpec? = null
    private var requestId = ""

    private suspend fun checkpoint(event: String, stage: QnnRuntimeStage, detail: String = "") {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            QnnExecutionEvidence.record(requestId, event, stage, detail)
        }
    }

    override suspend fun inspectModel(modelPath: String): LocalModelCapabilities? = runtime.inspectModel(modelPath)
    override suspend fun allowVerification(spec: LocalEngineSpec) {
        val verified = spec.copy(litertDispatchLibDir = probeEnvironment().dispatchDir)
        loadGuard.allowVerification(verified)
        runtime.allowVerification(verified)
    }

    override val deviceRamGb: Long get() = runtime.deviceRamGb
    override fun getHardwareState(): DeviceHardwareState = runtime.getHardwareState()
    override fun getAdaptiveThrottlingPolicy(): AdaptiveThrottlingPolicy = runtime.getAdaptiveThrottlingPolicy()
    override fun loadedEngineSpec(): LocalEngineSpec? = runtime.loadedEngineSpec() ?: dispatchedSpec

    override suspend fun loadEngine(spec: LocalEngineSpec) {
        requestId = java.util.UUID.randomUUID().toString()
        var stage = QnnRuntimeStage.DEVICE
        var guardEntered = false
        requestedSpec = null
        dispatchedSpec = null
        checkpoint("QNN_INIT_BEGIN", stage, "requested=npu · active=none")
        try {
            check(enabled) { "LiteRT/QNN is disabled in the GenieX preview APK; use the regular NPU build." }
            check(LocalAccelerators.normalize(spec.accelerator) == LocalAccelerators.NPU) { "QNN requires the NPU accelerator and a matching SoC model." }
            stage = QnnRuntimeStage.LIBRARY_FILES
            val probe = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { probeEnvironment() }
            checkpoint("QNN_PROBE_RESULT", stage, "prerequisitesReady=${probe.isReady} · soc=${probe.socModel} · abi=${probe.processAbi} · hashStatus=${probe.libraryDigestStatus} · executionQualified=false")
            if (!probe.isReady) {
                val code = when {
                    !probe.isQualcommDevice -> QnnFailureCode.UNSUPPORTED_SOC
                    probe.processAbi != "arm64-v8a" -> QnnFailureCode.WRONG_PROCESS_ABI
                    probe.missingLibraries.isNotEmpty() -> QnnFailureCode.LIBRARY_MISSING
                    probe.libraryDigestStatus == "TRUSTED_HASH_FAIL" -> QnnFailureCode.UNSAFE_ARTIFACT
                    probe.errorMessage.orEmpty().contains("No packaged Qualcomm", true) -> QnnFailureCode.UNSUPPORTED_SOC
                    else -> QnnFailureCode.UNKNOWN_NATIVE_ERROR
                }
                throw QnnRuntimeFailure(stage, code, probe.errorMessage ?: "Qualcomm NPU prerequisites unavailable")
            }
            val effectiveSpec = spec.copy(accelerator = LocalAccelerators.NPU, litertDispatchLibDir = probe.dispatchDir)
            stage = QnnRuntimeStage.CRASH_JOURNAL
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                loadGuard.beforeLoad(effectiveSpec)
                guardEntered = true
            }
            checkpoint("QNN_JOURNAL_RESULT", stage, "admitted=true")
            stage = QnnRuntimeStage.ENGINE_INIT
            checkpoint("QNN_ENGINE_INIT_BEGIN", stage, "maxContext=${spec.maxTokens} · vision=${spec.isVisionEnabled} · active=unverified")
            runtime.loadEngine(effectiveSpec)
            checkpoint("QNN_ENGINE_INIT_SUCCEEDED", stage, "active=npu · executionQualified=false")
            requestedSpec = spec
            dispatchedSpec = effectiveSpec
            checkpoint("QNN_INIT_TERMINAL", stage, "status=INITIALIZED · executionQualified=false")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { checkpoint("QNN_INIT_TERMINAL", stage, "status=CANCELLED") }
            throw cancelled
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            if (error is LocalModelConfigurationException) {
                checkpoint("QNN_INIT_TERMINAL", QnnRuntimeStage.MODEL_CONTEXT, "status=CONFIGURATION_REJECTED · nativeInitializationStarted=false · detail=${error.message}")
                throw error
            }
            val failure = classifyQnnFailure(stage, error)
            checkpoint("QNN_INIT_TERMINAL", failure.stage, "status=FAILED · code=${failure.code} · cause=${error.javaClass.simpleName} · nativeDetail=${failure.nativeDetail}")
            throw failure
        } finally {
            if (guardEntered) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) { loadGuard.loadFinished() }
        }
    }

    override suspend fun isEngineLoaded(spec: LocalEngineSpec): Boolean =
        requestedSpec == spec && dispatchedSpec?.let { runtime.isEngineLoaded(it) } == true

    override suspend fun createConversation(config: LocalConversationConfig) {
        guardedOperation(QnnRuntimeStage.CONVERSATION) { runtime.createConversation(config) }
    }

    private suspend fun <T> guardedOperation(stage: QnnRuntimeStage, action: suspend () -> T): T {
        val spec = checkNotNull(dispatchedSpec) { "NPU model is not loaded" }
        var admitted = false
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                loadGuard.beforeLoad(spec)
                admitted = true
            }
            checkpoint("QNN_${stage.name}", stage, "status=BEGIN")
            return action()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            if (error is NativeCancellationTimeoutException) {
                admitted = false
                throw error
            }
            val failure = classifyQnnFailure(if (admitted) stage else QnnRuntimeStage.CRASH_JOURNAL, error)
            checkpoint("QNN_INIT_TERMINAL", failure.stage, "status=FAILED · code=${failure.code} · nativeDetail=${failure.nativeDetail}")
            throw failure
        } finally {
            if (admitted) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) { loadGuard.loadFinished() }
        }
    }

    override fun sendMessage(text: String, images: List<ByteArray>): Flow<LocalRuntimeEvent> = kotlinx.coroutines.flow.flow {
        guardedOperation(QnnRuntimeStage.DECODE) {
            var nonempty = false
            var completed = false
            var failure: QnnRuntimeFailure? = null
            runtime.sendMessage(text, images).collect { event ->
                if (event is LocalRuntimeEvent.TextDelta && event.text.isNotBlank()) nonempty = true
                if (event is LocalRuntimeEvent.Done) completed = true
                if (event is LocalRuntimeEvent.Error) {
                    failure = classifyQnnFailure(QnnRuntimeStage.DECODE, event.cause ?: IllegalStateException(event.message))
                }
                emit(if (event is LocalRuntimeEvent.Error) LocalRuntimeEvent.Error(requireNotNull(failure).message.orEmpty(), failure) else event)
            }
            val activeNpu = runtime.loadedEngineSpec()?.accelerator?.let(LocalAccelerators::normalize) == LocalAccelerators.NPU
            checkpoint(
                "QNN_INIT_TERMINAL",
                QnnRuntimeStage.DECODE,
                if (nonempty && completed && failure == null && activeNpu) "status=QUALIFIED · active=npu · nonempty=true" else "status=NOT_QUALIFIED · nonempty=$nonempty · completed=$completed · code=${failure?.code ?: "NO_CONFIRMED_OUTPUT"} · nativeDetail=${failure?.nativeDetail ?: "native detail unavailable"}"
            )
        }
    }
    override fun cancelActive() = runtime.cancelActive()
    override fun hasOpenConversation(): Boolean = runtime.hasOpenConversation()
    override suspend fun closeConversation() = runtime.closeConversation()

    override suspend fun unloadEngine() {
        QnnExecutionEvidence.invalidate()
        requestedSpec = null
        dispatchedSpec = null
        runtime.unloadEngine()
    }
}
