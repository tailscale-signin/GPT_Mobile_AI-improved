package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.network.isLocalPrivacyPolicyRejection
import kotlinx.coroutines.CancellationException

/** Closed local stages, never source text, task arguments, exception messages or filesystem paths. */
internal enum class PhoneTaskContextPhase {
    TREE_SELECTION,
    PACKET_CACHE_READ,
    SYMBOL_CATALOG,
    SOURCE_LOAD,
    MANIFEST_RANKING,
    SOURCE_GRAPH,
    SOURCE_RANGES,
    SEMANTIC_RANKING,
    PACKET_ASSEMBLY,
    PACKET_CACHE_WRITE
}

internal enum class PhoneTaskContextReason { LOCAL_INVARIANT_REJECTED, LOCAL_STATE_INVALID, LOCAL_RUNTIME_FAILURE }

internal class PhoneTaskContextFailure(val phase: PhoneTaskContextPhase, val reason: PhoneTaskContextReason, cause: Exception) : IllegalStateException("The local task context could not be prepared at ${phase.name}.", cause)

/** Preserve typed identity, policy, transport and cancellation failures at their original boundary. */
internal suspend fun <T> taskContextPhase(phase: PhoneTaskContextPhase, work: suspend () -> T): T = try {
    work()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    if (failure is PhoneTaskContextFailure ||
        failure is GitHubBlobContentFailure ||
        failure is GitHubRequestRejected ||
        failure is GitHubCooldownActive ||
        failure is GitHubReadRequestBudgetExceeded ||
        failure is SecurityException ||
        failure is GatewayGraphOperationException ||
        failure.isLocalPrivacyPolicyRejection() ||
        graphFailureCode(failure) != "GRAPH_OPERATION_FAILED"
    ) {
        throw failure
    }
    val reason = when (failure) {
        is IllegalArgumentException -> PhoneTaskContextReason.LOCAL_INVARIANT_REJECTED
        is IllegalStateException -> PhoneTaskContextReason.LOCAL_STATE_INVALID
        else -> PhoneTaskContextReason.LOCAL_RUNTIME_FAILURE
    }
    throw PhoneTaskContextFailure(phase, reason, failure)
}
