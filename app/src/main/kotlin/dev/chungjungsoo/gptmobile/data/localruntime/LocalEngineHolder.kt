package dev.chungjungsoo.gptmobile.data.localruntime

import android.os.SystemClock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

/**
 * Thread-safe lifecycle holder for the native LiteRT model instance.
 *
 * Supports warm engine retention across conversational sessions when using the same
 * model specification, preventing reinitialization overhead (2-5s latency savings)
 * especially beneficial for high-RAM flagship devices.
 *
 * Provides idle timeout auto-unload to safely release native model memory after periods
 * of inactivity, protecting background system memory.
 */
class LocalEngineHolder(
    private val delegate: LocalRuntime,
    private val timeProvider: () -> Long = { SystemClock.elapsedRealtime() }
) : LocalRuntime {
    override val processPssBytes get() = delegate.processPssBytes
    override val handlesEngineFallback get() = delegate.handlesEngineFallback
    override val state get() = delegate.state
    override val latestInspectionReceipt get() = delegate.latestInspectionReceipt
    override val latestEngineLoadReceipt get() = delegate.latestEngineLoadReceipt
    override val latestExecutionReceipt get() = delegate.latestExecutionReceipt
    override val latestCleanupReceipt get() = cleanupQuarantine ?: delegate.latestCleanupReceipt

    @Volatile private var cleanupQuarantine: RuntimeCleanupReceipt? = null

    private fun refreshCleanupQuarantine() {
        val blocked = cleanupQuarantine ?: return
        val fresh = delegate.latestCleanupReceipt
        if (fresh?.safeForSuccessor == true &&
            fresh.observedAtElapsedMs != null &&
            blocked.observedAtElapsedMs != null &&
            fresh.observedAtElapsedMs >= blocked.observedAtElapsedMs &&
            fresh !== blocked &&
            (blocked.workerGenerationId == null || fresh.workerGenerationId == blocked.workerGenerationId)
        ) {
            cleanupQuarantine = null
        }
    }

    private fun requireSafeAdmission() {
        refreshCleanupQuarantine()
        val blocked = cleanupQuarantine ?: return
        error("NATIVE_SESSION_QUARANTINED: ${blocked.failureReason ?: "cleanup has not been confirmed"}")
    }

    override fun admittedEngineSpec(): LocalEngineSpec? = loadedSpec

    override fun loadedEngineSpec(): LocalEngineSpec? = delegate.loadedEngineSpec()

    private val mutex = FairNativeLock(timeProvider)
    private val queued =
        java.util.concurrent.atomic
            .AtomicInteger()
    private var loadedSpec: LocalEngineSpec? = null

    @Volatile
    private var lastAccessedElapsedRealtimeMs: Long = 0L

    override suspend fun preflightModel(
        modelPath: String,
        accelerator: String?
    ) {
        requireSafeAdmission()
        delegate.preflightModel(modelPath, accelerator)
    }

    override suspend fun inspectModel(modelPath: String): LocalModelCapabilities? {
        val queuedAt = timeProvider()
        return withGenerationLock {
            requireSafeAdmission()
            val acquiredAt = timeProvider()
            delegate.inspectModel(modelPath).also {
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                    "LocalModel",
                    "LOCAL_INSPECTION_TIMING · leaseWaitMs=${acquiredAt - queuedAt} · inspectionMs=${timeProvider() - acquiredAt}"
                )
            }
        }
    }

    override suspend fun inspectModel(spec: LocalEngineSpec): LocalModelCapabilities? {
        val queuedAt = timeProvider()
        return withGenerationLock {
            requireSafeAdmission()
            val acquiredAt = timeProvider()
            delegate.inspectModel(spec).also {
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                    "LocalModel",
                    "LOCAL_INSPECTION_TIMING · leaseWaitMs=${acquiredAt - queuedAt} · inspectionMs=${timeProvider() - acquiredAt}"
                )
            }
        }
    }

    override suspend fun contextForRequest(
        profileUid: String,
        modelPath: String,
        maximum: Int
    ): Int =
        withGenerationLock {
            requireSafeAdmission()
            delegate.contextForRequest(profileUid, modelPath, maximum)
        }

    override suspend fun contextPolicyForRequest(profileUid: String): String = delegate.contextPolicyForRequest(profileUid)

    override val deviceRamGb: Long
        get() = delegate.deviceRamGb

    override fun getHardwareState(): DeviceHardwareState = delegate.getHardwareState()

    override fun getAdaptiveThrottlingPolicy(): AdaptiveThrottlingPolicy = delegate.getAdaptiveThrottlingPolicy()

    /**
     * Timestamp in elapsed realtime milliseconds when the engine was last used or loaded.
     */
    val lastAccessedTimestamp: Long
        get() = lastAccessedElapsedRealtimeMs

    override suspend fun allowVerification(spec: LocalEngineSpec) = withGenerationLock { delegate.allowVerification(spec) }

    override suspend fun loadEngine(spec: LocalEngineSpec) =
        withGenerationLock {
            requireSafeAdmission()
            markAccessed()
            if (loadedSpec == spec && delegate.isEngineLoaded(spec)) {
                LocalReuseDiagnostics.record("engine_hit")
                return@withGenerationLock
            }
            LocalReuseDiagnostics.record("engine_rebuild")
            if (loadedSpec != null) {
                delegate.closeConversation()
                if (!delegate.ownsEngineSwitching) delegate.unloadEngine()
                loadedSpec = null
            }
            try {
                delegate.loadEngine(spec)
                loadedSpec = spec
            } catch (error: Throwable) {
                loadedSpec = null
                withContext(NonCancellable) { delegate.unloadEngine() }
                throw error
            }
        }

    override suspend fun createConversation(config: LocalConversationConfig) =
        withGenerationLock {
            requireSafeAdmission()
            markAccessed()
            delegate.createConversation(config)
        }

    override fun sendMessage(
        text: String,
        images: List<ByteArray>
    ): Flow<LocalRuntimeEvent> = sendMessageWithBudget(text, images, 0)

    override fun sendMessageWithBudget(
        text: String,
        images: List<ByteArray>,
        safetyTokens: Int
    ): Flow<LocalRuntimeEvent> =
        channelFlow {
            withGenerationLock {
                requireSafeAdmission()
                markAccessed()
                delegate.sendMessageWithBudget(text, images, safetyTokens).collect {
                    markAccessed()
                    send(it)
                }
            }
        }

    override fun cancelActive() {
        delegate.cancelActive()
    }

    override fun quarantineAfterUnconfirmedCleanup(reason: String): RuntimeCleanupReceipt =
        delegate.quarantineAfterUnconfirmedCleanup(reason).copy(observedAtElapsedMs = timeProvider()).also { cleanupQuarantine = it }

    override suspend fun cleanupAfterRequest(origin: String): RuntimeCleanupReceipt =
        withGenerationLock {
            try {
                delegate.cleanupAfterRequest(origin).also { receipt ->
                    cleanupQuarantine = if (receipt.safeForSuccessor) null else receipt
                    if (receipt.engineReleased == true || receipt.workerExitConfirmed == true) loadedSpec = null
                    if (!receipt.safeForSuccessor) {
                        delegate.quarantineAfterUnconfirmedCleanup(
                            receipt.failureReason ?: "Cleanup is unconfirmed"
                        )
                    }
                }
            } catch (error: Throwable) {
                quarantineAfterUnconfirmedCleanup(error.message ?: "Cleanup interrupted")
                throw error
            }
        }

    override suspend fun closeConversation() =
        withGenerationLock {
            delegate.closeConversation()
        }

    override suspend fun unloadEngine() =
        InferenceAdmission.shared {
            delegate.cancelActive()
            withGenerationLock {
                delegate.closeConversation()
                delegate.unloadEngine()
                loadedSpec = null
                lastAccessedElapsedRealtimeMs = 0L
            }
        }

    /**
     * Unloads the engine if it has been idle for at least [idleThresholdMs].
     *
     * Returns true if the engine was unloaded due to inactivity, or false if it is still
     * active, already unloaded, or has not exceeded the idle threshold.
     */
    override suspend fun unloadIfIdle(idleThresholdMs: Long): Boolean =
        InferenceAdmission.tryShared {
            // Never cancel a running generation to enforce an idle timeout. Check the
            // timestamp and unload under the same lock as generation/model switching.
            if (!mutex.tryLock()) return@tryShared false
            try {
                val idleMs = timeProvider() - lastAccessedElapsedRealtimeMs
                if (loadedSpec == null || idleMs < idleThresholdMs) {
                    false
                } else {
                    delegate.unloadEngine()
                    loadedSpec = null
                    lastAccessedElapsedRealtimeMs = 0L
                    true
                }
            } finally {
                mutex.unlock()
            }
        } ?: false

    override suspend fun isEngineLoaded(spec: LocalEngineSpec): Boolean = loadedSpec == spec && delegate.isEngineLoaded(spec)

    override fun hasOpenConversation(): Boolean = delegate.hasOpenConversation()

    override val isBusy: Boolean get() {
        refreshCleanupQuarantine()
        return cleanupQuarantine != null || mutex.isLocked || delegate.isBusy || InferenceAdmission.isExclusiveBusy
    }

    override val isNativeOperationBusy: Boolean get() {
        refreshCleanupQuarantine()
        return cleanupQuarantine != null ||
            delegate.isNativeOperationBusy
    }
    override val executionIdentity get() = delegate.executionIdentity

    override suspend fun <T> tryRunExclusive(block: suspend LocalRuntime.() -> T): T? =
        InferenceAdmission.tryShared {
            if (coroutineContext[GenerationLock] != null || !mutex.tryLock()) return@tryShared null
            try {
                withContext(GenerationLock()) { block(this@LocalEngineHolder) }
            } finally {
                mutex.unlock()
            }
        }

    override suspend fun <T> runExclusive(block: suspend LocalRuntime.() -> T): T =
        withGenerationLock {
            markAccessed()
            block(this)
        }

    override fun <T> runExclusiveFlow(
        onContended: suspend () -> Unit,
        block: suspend LocalRuntime.() -> Flow<T>
    ): Flow<T> =
        channelFlow {
            if (coroutineContext[GenerationLock] != null) {
                markAccessed()
                block(this@LocalEngineHolder).collect { send(it) }
                return@channelFlow
            }
            acquireGenerationLock(onContended)
            try {
                withContext(GenerationLock()) {
                    markAccessed()
                    block(this@LocalEngineHolder).collect { send(it) }
                }
            } finally {
                mutex.unlock()
            }
        }.let { InferenceAdmission.sharedFlow(it) }

    private fun markAccessed() {
        lastAccessedElapsedRealtimeMs = timeProvider()
    }

    private suspend fun <T> withGenerationLock(block: suspend () -> T): T =
        InferenceAdmission.shared {
            if (coroutineContext[GenerationLock] != null) {
                return@shared block()
            }
            acquireGenerationLock()
            try {
                withContext(GenerationLock()) { block() }
            } finally {
                mutex.unlock()
            }
        }

    /** Cancellation removes only this waiter; visible priority has bounded bypass and aging. */
    private suspend fun acquireGenerationLock(onContended: suspend () -> Unit = {}) {
        if (mutex.tryLock()) return
        val position = queued.incrementAndGet()
        var acquired = false
        try {
            if (position > 128) throw NativeAdmissionRejected("NATIVE_QUEUE_FULL")
            onContended()
            val admitted =
                kotlinx.coroutines.withTimeoutOrNull(180_000L) {
                    mutex.lock()
                    acquired = true
                    true
                }
            if (admitted != true) throw NativeAdmissionRejected("NATIVE_QUEUE_DEADLINE")
        } catch (error: Throwable) {
            if (acquired) mutex.unlock()
            throw error
        } finally {
            queued.decrementAndGet()
        }
    }

    companion object {
        /** Default idle duration (10 minutes) after which an inactive engine is eligible for unloading. */
        const val DEFAULT_IDLE_UNLOAD_TIMEOUT_MS = 10 * 60 * 1000L
    }
}

private class GenerationLock : AbstractCoroutineContextElement(GenerationLock) {
    companion object Key : CoroutineContext.Key<GenerationLock>
}
