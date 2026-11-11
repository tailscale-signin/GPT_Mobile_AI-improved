package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.audit.AppAuditModelConfigurationReceipt
import dev.chungjungsoo.gptmobile.data.audit.AppAuditModelQualificationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

/** Only this guard's deadline becomes a binding rejection; owner cancellation keeps its authority. */
internal suspend fun benchmarkAuditBindingWithinDeadline(timeoutMs: Long = 10_000, validate: suspend () -> Unit) {
    try {
        withTimeout(timeoutMs) { validate() }
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw AppAuditModelQualificationException("AUDIT_MODEL_BINDING_UNAVAILABLE")
    }
}

/** Check every generation round, including tool continuations; never retry a rejected or interrupted round. */
internal class BenchmarkAuditModelSession(
    private val configurationRevision: String,
    private val validate: suspend () -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) {
    private var admitted = 0
    private var completed = 0
    private var failed: AppAuditModelQualificationException? = null
    private var observedAt = 0L

    val receipt: AppAuditModelConfigurationReceipt
        get() = AppAuditModelConfigurationReceipt(
            configurationRevision,
            admitted,
            completed,
            beforeGenerationVerified = admitted > 0 && failed?.beforeDispatch != true,
            afterGenerationVerified = admitted > 0 && completed == admitted && failed == null,
            observedAtEpochMs = observedAt,
            failureCode = failed?.code
        )

    private suspend fun verify(beforeDispatch: Boolean) {
        try {
            currentCoroutineContext().ensureActive()
            failed?.let { throw it }
            validate()
            observedAt = now()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure is BenchmarkStorageFailure) throw failure
            val problem = AppAuditModelQualificationException((failure as? AppAuditModelQualificationException)?.code ?: "AUDIT_MODEL_BINDING_UNAVAILABLE", beforeDispatch)
            failed = problem
            observedAt = now()
            throw problem
        }
    }

    suspend fun open(create: suspend () -> AgentProviderSession): AgentProviderSession {
        verify(beforeDispatch = true)
        val delegate = create()
        return object : AgentProviderSession by delegate {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                verify(beforeDispatch = true)
                admitted++
                delegate.streamRound(tools, exchanges).collect { emit(it) }
                verify(beforeDispatch = false)
                completed++
            }
        }
    }
}
