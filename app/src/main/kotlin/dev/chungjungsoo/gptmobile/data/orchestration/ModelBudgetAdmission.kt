package dev.chungjungsoo.gptmobile.data.orchestration

import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation

class ExecutionAllowanceReached(message: String) : IllegalStateException(message)

/** Evaluated inside InvocationDao's existing Room transaction. No second usage ledger. */
internal object ModelBudgetAdmission {
    fun check(existing: List<ModelInvocation>, candidate: ModelInvocation, policy: ExecutionPolicy) {
        if (!policy.sharedRunBudget) return
        policy.validate()
        val preparation = candidate.kind in setOf("assist", "delegate", "reviewer", "research", "benchmark")
        fun sum(rows: List<ModelInvocation>, value: (ModelInvocation) -> Long) = rows.fold(0L) { acc, row -> Math.addExact(acc, value(row)) }
        fun exceeds(used: Long, additional: Long, limit: Long?) = limit != null && (used > limit || additional > limit - used)
        val remote = existing.filter { it.provider != "LITERT_LM" }
        val local = existing.filter { it.provider == "LITERT_LM" }
        val finalReserve = if (preparation) policy.finalOutputReserve.toLong() else 0L
        val reason = when {
            candidate.outputTokens <= 0 && (if (candidate.provider == "LITERT_LM") policy.localGeneratedTokens else policy.remoteOutputTokens) != null -> "Set a finite model output cap before enforcing this shared token budget."
            existing.count { it.status == "RUNNING" } >= policy.concurrentModels -> "The shared model concurrency allowance is busy."
            policy.physicalModelRequests?.let { existing.size + 1 + (if (preparation) 1 else 0) > it } == true -> "The shared physical model request allowance is reserved or exhausted."
            candidate.provider != "LITERT_LM" && exceeds(sum(remote) { it.inputTokens.toLong() }, candidate.inputTokens.toLong(), policy.remoteInputTokens) -> "The shared remote input token allowance is exhausted."
            candidate.provider != "LITERT_LM" && exceeds(sum(remote) { it.outputTokens.toLong() }, candidate.outputTokens.toLong() + finalReserve, policy.remoteOutputTokens) -> "The shared remote output allowance is reserved for final writing or exhausted."
            candidate.provider == "LITERT_LM" && exceeds(sum(local) { it.outputTokens.toLong() }, candidate.outputTokens.toLong(), policy.localGeneratedTokens) -> "The shared local generated token allowance is exhausted."
            exceeds(sum(existing) { it.durationMs }, maxOf(1, candidate.durationMs), policy.modelActiveMillis) -> "The shared active model time allowance is exhausted."
            else -> null
        }
        if (reason != null) throw ExecutionAllowanceReached(reason)
    }
}
