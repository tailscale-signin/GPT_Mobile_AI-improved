package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Stable provider replay identity shared by live checkpoints and legacy journal recovery. */
internal object GitHubProviderReplayEvidence {
    fun receipts(exchanges: List<AgentToolExchange>): List<ToolHandoffReceipt> = exchanges.flatMap { exchange ->
        exchange.calls.mapNotNull { call ->
            val result = exchange.results.firstOrNull { it.callId == call.callId } ?: return@mapNotNull null
            ToolHandoffReceipt.from(call, result, originalAvailable = false)
        }
    }

    fun digest(receipts: List<ToolHandoffReceipt>): String = GitHubEvidenceBook.digest(
        JsonArray(
            receipts.sortedBy { it.invocationFingerprint }.map { receipt ->
                buildJsonObject {
                    put("invocation", receipt.invocationFingerprint)
                    put("result", receipt.sourceResultSha256 ?: receipt.projectionSha256)
                    put("dispatched", receipt.dispatched)
                    put("requires_reconciliation", receipt.requiresReconciliation)
                    receipt.errorCode?.let { put("error", it) }
                }
            }
        ).toString()
    )
}
