package dev.chungjungsoo.gptmobile.data.accounting

import dev.chungjungsoo.gptmobile.data.agent.CombinedBudgetAdmission
import dev.chungjungsoo.gptmobile.data.agent.CombinedBudgetExceeded
import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import dev.chungjungsoo.gptmobile.data.agent.CombinedResourceQuota
import dev.chungjungsoo.gptmobile.data.agent.CombinedTurnBudget
import dev.chungjungsoo.gptmobile.data.agent.CombinedTurnBudgetStore
import dev.chungjungsoo.gptmobile.data.agent.CombinedUsage
import dev.chungjungsoo.gptmobile.data.context.RequestAdmissionReceipt
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Inherited at the physical boundary, so provider retries also honor whole-turn and daily caps. */
internal class CombinedInvocationAccounting(
    private val context: CombinedExecutionContext,
    private val store: CombinedTurnBudgetStore,
    private val price: ModelPrice?,
    private val dao: InvocationDao,
    private val logical: ModelInvocation,
    private val totalLimit: Int,
    private val spend: SpendBudgetSettings
) : AbstractCoroutineContextElement(Key) {
    private val attempts = CopyOnWriteArrayList<String>()
    private val physical = ConcurrentHashMap<String, ModelInvocation>()
    private var admittedBudget: CombinedTurnBudget? = null
    private val mutex = Mutex()

    suspend fun reserve(attemptId: String, receipt: RequestAdmissionReceipt?) = mutex.withLock {
        val actual = receipt ?: throw CombinedBudgetExceeded("Compiled request receipt is missing; no Combined inference was sent.")
        val output = actual.outputTokens ?: throw CombinedBudgetExceeded("This transport cannot enforce an output bound; Combined dispatch requires a bounded provider request.")
        require(output > 0) { "No output capacity remains; no Combined inference was sent." }
        val request = CombinedResourceQuota(Math.addExact(actual.promptTokens, actual.safetyTokens), output)
        require(request.inputTokens <= Int.MAX_VALUE && request.generatedTokens <= Int.MAX_VALUE) { "A physical request exceeds the supported accounting range." }
        val cost = price?.cost(request.inputTokens, output)
        val budget = admittedBudget ?: store.open(context).also { admittedBudget = it }
        when (val result = budget.reserve(context, attemptId, request, cost)) {
            is CombinedBudgetAdmission.Denied -> throw CombinedBudgetExceeded(result.reason)
            is CombinedBudgetAdmission.Granted -> Unit
        }
        // Replace the first provisional estimate, then create a distinct row for every retry.
        // reserve() excludes the old row atomically, so an updated quote is never charged twice.
        val row = logical.copy(
            id = if (attempts.isEmpty()) logical.id else attemptId,
            inputTokens = request.inputTokens.toInt(),
            outputTokens = output.toInt(),
            costMicros = cost,
            kind = if (attempts.isEmpty()) logical.kind else "${logical.kind}-physical-retry",
            startedAt = System.currentTimeMillis(),
            inputTokenSource = "compiled_request_reservation",
            outputTokenSource = "transmitted_output_reservation",
            requestAdmissionReceipts = Json.encodeToString(listOf(actual.copy(physicalAttemptId = attemptId)))
        )
        try {
            dao.reserve(row, totalLimit, spend)
            physical[attemptId] = row
            attempts += attemptId
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                budget.releaseNotDispatched(attemptId)
                dao.save(row.copy(inputTokens = 0, outputTokens = 0, costMicros = 0, status = "NOT_DISPATCHED"))
            }
            throw error
        }
    }

    suspend fun notDispatched(attemptId: String) = mutex.withLock {
        if (attempts.remove(attemptId)) {
            admittedBudget?.releaseNotDispatched(attemptId)
            physical.remove(attemptId)?.let { dao.save(it.copy(inputTokens = 0, outputTokens = 0, costMicros = 0, status = "NOT_DISPATCHED")) }
        }
    }

    suspend fun finish(observation: ModelInvocation, reasoning: Int?) = mutex.withLock {
        if (attempts.isEmpty()) {
            dao.save(observation.copy(inputTokens = 0, outputTokens = 0, observedInputTokens = null, observedOutputTokens = null, costMicros = 0, status = "NOT_DISPATCHED"))
            return@withLock
        }
        val budget = admittedBudget ?: return@withLock
        val input = observation.observedInputTokens
        val output = observation.observedOutputTokens
        val measured = input != null && output != null && observation.inputTokenSource in MEASURED && observation.outputTokenSource in MEASURED
        val separable = attempts.size == 1 && observation.status == "COMPLETED" && measured
        for (attemptId in attempts) {
            val row = physical[attemptId] ?: continue
            val result = if (separable) {
                val actualInput = requireNotNull(input)
                val actualOutput = requireNotNull(output)
                val actualCost = price?.cost(actualInput, actualOutput)
                budget.settle(attemptId, CombinedUsage(CombinedResourceQuota(actualInput, actualOutput), measuredInput = true, measuredOutput = true, reasoningOutputTokens = reasoning?.toLong(), costMicros = actualCost))
                row.copy(inputTokens = actualInput.toInt(), outputTokens = actualOutput.toInt(), observedInputTokens = actualInput, observedOutputTokens = actualOutput, estimated = false, inputTokenSource = observation.inputTokenSource, outputTokenSource = observation.outputTokenSource, costMicros = actualCost)
            } else {
                budget.unknown(attemptId, if (attempts.size > 1) "Physical usage cannot be separated from aggregated retry/continuation events; upper bound retained." else "Completion or measured usage unavailable (${observation.status}); upper bound retained.")
                row.copy(inputTokenSource = "reserved_unknown", outputTokenSource = "reserved_unknown", observedInputTokens = null, observedOutputTokens = null, estimated = true)
            }
            dao.save(
                result.copy(
                    status = observation.status,
                    durationMs = (System.currentTimeMillis() - row.startedAt).coerceAtLeast(0),
                    firstTokenMs = if (row.id == logical.id) observation.firstTokenMs else null,
                    actualRuntime = observation.actualRuntime,
                    finishReason = observation.finishReason,
                    consumedAssistInvocationIds = if (row.id == logical.id) observation.consumedAssistInvocationIds else null,
                    assistInclusionVerified = observation.assistInclusionVerified
                )
            )
        }
    }

    companion object Key : CoroutineContext.Key<CombinedInvocationAccounting> {
        private val MEASURED = setOf("provider_reported", "runtime_reported", "gateway_reported")
    }
}
