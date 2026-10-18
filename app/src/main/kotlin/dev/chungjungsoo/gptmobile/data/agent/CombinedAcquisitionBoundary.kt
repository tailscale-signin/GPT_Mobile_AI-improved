package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Physical academic transports also run outside an AgentRunner (for example durable examination). */
internal object CombinedAcquisitionBoundary {
    suspend fun context(): CombinedExecutionContext? = currentCoroutineContext()[CombinedToolDispatchScope]?.context
        ?: currentCoroutineContext()[CombinedRunScope]?.context

    suspend fun reserve(id: String, operations: Long, freePublicEndpoint: Boolean = false, store: CombinedTurnBudgetStore? = null): Ticket? {
        val inherited = context() ?: return null
        currentCoroutineContext().ensureActive()
        val budget = requireNotNull(store ?: CombinedTurnBudgetStore.installedOrNull()) { "Combined accounting is unavailable; no academic request was sent." }.open(inherited)
        val context = budget.assignedContext(inherited)
        when (val admission = budget.reserve(context, id, CombinedResourceQuota(toolOperations = operations), if (freePublicEndpoint) 0L else null)) {
            is CombinedBudgetAdmission.Denied -> throw CombinedBudgetExceeded(admission.reason)
            is CombinedBudgetAdmission.Granted -> Unit
        }
        return Ticket(budget, id, operations, freePublicEndpoint)
    }

    class Ticket(private val budget: CombinedTurnBudget, private val id: String, private val upper: Long, private val free: Boolean) {
        suspend fun finish(actual: Long? = null, dispatched: Boolean = true) = withContext(NonCancellable) {
            when {
                !dispatched -> budget.releaseNotDispatched(id)
                actual != null && actual >= 0 -> {
                    budget.settle(id, CombinedUsage(CombinedResourceQuota(toolOperations = actual), costMicros = if (free) 0 else null))
                    if (actual > upper) throw CombinedBudgetExceeded("Academic gateway exceeded its reserved physical-request allowance; actual usage was retained.")
                }
                else -> budget.unknown(id, "Academic request completion/accounting unavailable; upper reservation retained.")
            }
        }
    }
}
