package dev.chungjungsoo.gptmobile.data.assist

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.localmodel.resolveLocalModelSelection
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntime
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One admission/recovery owner for warmup, explicit tests and actual task dispatch. */
@Singleton
class LocalAssistPreparation @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localRuntime: LocalRuntime,
    private val localModelRepository: LocalModelRepository,
    private val settingRepository: SettingRepository
) {
    @Volatile var recoveryRetryAtMillis: Long? = null
        private set
    private val assistAdmission = dev.chungjungsoo.gptmobile.data.localruntime.LocalWorkloadAdmission()
    private val assistPreparationFailures = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun eligible(target: PlatformV2, policy: dev.chungjungsoo.gptmobile.data.assist.LocalAssistPolicy): Boolean {
        fun status(state: dev.chungjungsoo.gptmobile.data.assist.AssistReadiness, reason: String) = dev.chungjungsoo.gptmobile.data.assist.AssistReadinessResolver.publish(state, reason, target.uid, modelLoaded = state == AssistReadiness.READY, requestedAccelerator = target.accelerator, observedAccelerator = localRuntime.state.value.engineSpec?.accelerator, observedBackend = localRuntime.state.value.backend?.displayName)
        val unavailable = dev.chungjungsoo.gptmobile.data.assist.AssistReadiness.SKIPPED
        recoveryRetryAtMillis = null
        assistAdmission.evaluate(localRuntime.getHardwareState(), dev.chungjungsoo.gptmobile.data.localruntime.LocalWorkloadAdmission.availableMemory(context), 128L * 1024 * 1024, settingRepository.getFeatureSettings().localMemoryReserveMb.coerceIn(256, 4096) * 1024L * 1024, optional = true)?.let {
            if (it.reason == dev.chungjungsoo.gptmobile.data.localruntime.LocalAdmissionReason.RECOVERING) recoveryRetryAtMillis = android.os.SystemClock.elapsedRealtime() + (it.remainingMillis ?: 30_000)
            return status(unavailable, it.message)
        }
        val selection = runCatching { localModelRepository.resolveLocalModelSelection(target.model, target.accelerator) }.getOrNull()
            ?: return status(unavailable, "Local Assist unavailable: selected model is not installed")
        val readable = withContext(Dispatchers.IO) { java.io.File(selection.path).let { it.isFile && it.canRead() && it.length() > 0 } }
        if (!readable) return status(unavailable, "Local Assist unavailable: selected artifact is missing or unreadable")
        val observed = localRuntime.state.value
        if (observed.lifecycle in setOf("CANCELLING", "SHUTDOWN")) return status(unavailable, "Local Assist waiting for acknowledged runtime cancellation")
        val record = localRuntime.admittedEngineSpec()
        if (record != null) {
            if (record.modelPath != selection.path || record.accelerator != dev.chungjungsoo.gptmobile.data.localruntime.LocalAccelerators.normalize(target.accelerator) || !localRuntime.isEngineLoaded(record)) return status(unavailable, "Local Assist skipped: another runtime configuration is loaded")
            if (observed.lifecycle == "FAILED" || observed.engineSpec == null || observed.backend == null) return status(unavailable, "Local Assist unavailable: loaded session health or execution backend is unverified")
            return status(dev.chungjungsoo.gptmobile.data.assist.AssistReadiness.READY, "Local Assist ready: requested ${record.accelerator}; observed ${observed.engineSpec.accelerator} (${observed.backend.displayName}); context ${observed.engineSpec.maxTokens}")
        }
        if (policy.preparation == dev.chungjungsoo.gptmobile.data.assist.AssistPreparation.WARM_ONLY) return status(unavailable, "Local Assist skipped: warm-only policy; model is not loaded")
        val preparationKey = "${target.uid}|${selection.path}|${target.accelerator}|$policy|${dev.chungjungsoo.gptmobile.data.localmodel.LocalModelInstallationEpoch.current()}"
        if ((assistPreparationFailures[preparationKey] ?: 0L) > android.os.SystemClock.elapsedRealtime()) return status(unavailable, "Local Assist preparation is cooling down after a failed load; select a smaller context or retry after recovery")
        status(dev.chungjungsoo.gptmobile.data.assist.AssistReadiness.PREPARING, "Preparing selected Local Assist model")
        val prepared = try {
            kotlinx.coroutines.withTimeoutOrNull(policy.validated().loadTimeoutSeconds * 1000L) {
                localRuntime.runExclusive {
                    val metadata = requireNotNull(inspectModel(selection.path)) { "Artifact capabilities are unavailable" }
                    val backend = dev.chungjungsoo.gptmobile.data.localruntime.LocalAccelerators.normalize(target.accelerator)
                    check(backend in metadata.backends) { "Selected backend is unsupported or unverified for this artifact" }
                    val capacity = requireNotNull(metadata.contextTokens?.takeIf { it > 0 }) { "Artifact context capacity is unverified" }
                    val requestedContext = policy.validated().contextTokens
                    val effectiveContext = if (metadata.dynamicContext == false) capacity else minOf(capacity, requestedContext)
                    val spec = dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineSpec(selection.path, backend, effectiveContext, workload = "assist", expectedOutputTokens = minOf(1024, (effectiveContext / 4).coerceAtLeast(64)))
                    metadata.validate(spec)
                    loadEngine(spec)
                    true
                }
            } == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: dev.chungjungsoo.gptmobile.data.localruntime.LocalArtifactIncompatibleException) {
            return status(unavailable, "Local Assist artifact is quarantined for this runtime/backend; import a compatible export or explicitly Verify it")
        } catch (_: dev.chungjungsoo.gptmobile.data.localruntime.LocalModelConfigurationException) {
            return status(unavailable, "Local Assist artifact does not support this device/runtime/backend configuration")
        } catch (failure: Exception) {
            AppLogRecorder.record("LocalModel", "ASSIST_PREPARATION_REJECTED · cause=${failure.javaClass.simpleName} · detail=${dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor.redact(failure.message.orEmpty()).take(500)}", "W")
            false
        }
        if (prepared) {
            assistPreparationFailures.remove(preparationKey)
        } else {
            if (assistPreparationFailures.size > 64) assistPreparationFailures.clear()
            assistPreparationFailures[preparationKey] = android.os.SystemClock.elapsedRealtime() + 30_000L
        }
        val active = localRuntime.admittedEngineSpec()
        val observedAfterLoad = localRuntime.state.value
        val verified = prepared && active != null && localRuntime.isEngineLoaded(active) && observedAfterLoad.backend != null && observedAfterLoad.engineSpec != null && observedAfterLoad.lifecycle !in setOf("FAILED", "CANCELLING", "SHUTDOWN")
        return if (verified) status(dev.chungjungsoo.gptmobile.data.assist.AssistReadiness.READY, "Local Assist loaded: requested ${target.accelerator}; observed ${observedAfterLoad.engineSpec?.accelerator} (${observedAfterLoad.backend?.displayName}); generation not yet tested") else status(unavailable, "Local Assist unavailable: preparation failed, timed out, or backend was not observed; see runtime diagnostics")
    }
}
