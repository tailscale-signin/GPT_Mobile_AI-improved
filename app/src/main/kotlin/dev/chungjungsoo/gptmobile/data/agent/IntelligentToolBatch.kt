package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import dev.chungjungsoo.gptmobile.data.research.ResearchTimeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** Scheduling metadata is local policy, never authority supplied by a model or a repository. */
internal data class ToolBatchNode(val call: ProviderEvent.ToolCall, val dependencies: Set<String>, val parallelRead: Boolean)

data class ToolBatchProgress(val completed: Int, val total: Int, val active: Int, val failed: Int, val concurrency: Int, val elapsedMillis: Long) {
    val message: String get() = "Batch $completed/$total · $active running · ${total - completed - active} queued" +
        if (failed > 0) " · $failed need attention" else ""
}

internal fun parallelBatchRead(call: ProviderEvent.ToolCall, tool: AgentTool?): Boolean {
    if (tool == null || AgentToolCapability.USER_DECISION in tool.definition.capabilities) return false
    val native = if (call.name == "github") call.arguments else GitHubOperationTool.nativeArguments(call.name, call.arguments)
    // Context resolution changes the selected branch/snapshot, even when pinned.
    if ((native?.get("action") as? JsonPrimitive)?.content in setOf("resolve_context", "task_context")) return false
    if (GitHubAuthorizationBoundary.isReplayableRead(call.name, call.arguments)) return true
    return ResearchTimeouts.isAcademicRead(call.name) ||
        call.name in setOf("web_search", "read_url", "current_date", "read_file_slice")
}

/** Unknown tools and writes are barriers; independent reads on either side retain model order. */
internal fun planIntelligentBatch(calls: List<ProviderEvent.ToolCall>, tools: Map<String, AgentTool>): List<ToolBatchNode> {
    require(calls.map { it.callId }.distinct().size == calls.size)
    val nodes = mutableListOf<ToolBatchNode>()
    var barrier: String? = null
    fun action(call: ProviderEvent.ToolCall) = (call.arguments["action"] as? JsonPrimitive)?.content ?: when (GitHubOperationTool.operationName(call.name)) {
        "github_read" -> "ranges"
        "github_pr" -> "context"
        "github_ci" -> "runs"
        "github_commit" -> "commit_staged_patch"
        else -> "default"
    }
    calls.forEach { call ->
        val tool = tools[call.name]
        val read = parallelBatchRead(call, tool)
        val dependencies = mutableSetOf<String>()
        barrier?.let(dependencies::add)
        if (!read) dependencies += nodes.map { it.call.callId }
        tool?.definition?.workflowLinks.orEmpty().filter { it.action == action(call) }.forEach { link ->
            nodes.lastOrNull { it.call.name == link.sourceTool && action(it.call) == link.sourceAction }?.let { dependencies += it.call.callId }
        }
        nodes += ToolBatchNode(call, dependencies, read)
        if (!read) barrier = call.callId
    }
    return nodes
}

/** Bounded AIMD window. It never exceeds the profile's existing tool budget. */
internal class AdaptiveBatchWindow(maximum: Int) {
    val maximum = maximum.coerceIn(1, 20)
    var concurrency = minOf(5, this.maximum)
        private set
    private var healthy = 0
    fun observe(failed: Boolean, elapsedMillis: Long) {
        if (failed || elapsedMillis >= 15_000) {
            concurrency = maxOf(1, concurrency / 2)
            healthy = 0
        } else if (++healthy >= concurrency) {
            concurrency = minOf(maximum, concurrency + 1)
            healthy = 0
        }
    }
}

/** Completion callbacks run on the collecting coroutine, never concurrently in child workers. */
internal class IntelligentToolBatch(private val window: AdaptiveBatchWindow, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    suspend fun execute(
        nodes: List<ToolBatchNode>,
        dispatch: suspend (ProviderEvent.ToolCall) -> AgentToolResult,
        completed: suspend (ProviderEvent.ToolCall, AgentToolResult) -> Unit,
        progress: suspend (ToolBatchProgress) -> Unit
    ): List<AgentToolResult> = coroutineScope {
        val batchScope = this
        require(nodes.size <= 1024 && nodes.map { it.call.callId }.distinct().size == nodes.size)
        val ids = nodes.mapTo(hashSetOf()) { it.call.callId }
        val barriers = nodes.filterNot { it.parallelRead }.mapTo(hashSetOf()) { it.call.callId }
        require(nodes.all { it.call.callId !in it.dependencies && ids.containsAll(it.dependencies) })
        // Validate the entire DAG before starting any operation.
        val checked = hashSetOf<String>()
        while (checked.size < nodes.size) {
            val ready = nodes.filter { it.call.callId !in checked && checked.containsAll(it.dependencies) }
            require(ready.isNotEmpty()) { "Tool batch has a dependency cycle." }
            checked += ready.map { it.call.callId }
        }
        data class Completion(val node: ToolBatchNode, val result: AgentToolResult, val elapsed: Long)
        val receipts = linkedMapOf<String, AgentToolResult>()
        val pending = nodes.toMutableList()
        val active = hashSetOf<String>()
        val channel = Channel<Completion>(window.maximum)
        val started = now()
        suspend fun report() = progress(ToolBatchProgress(receipts.size, nodes.size, active.size, receipts.values.count { it.isError }, window.concurrency, (now() - started).coerceAtLeast(0)))
        try {
            report()
            while (receipts.size < nodes.size) {
                for (node in pending.toList()) {
                    if (!receipts.keys.containsAll(node.dependencies)) continue
                    if (node.dependencies.any { receipts.getValue(it).isError }) {
                        val result = ToolResultEnvelope.error(node.call.callId, "DEPENDENCY_FAILED", "A prerequisite failed or has an unknown outcome. This operation was not dispatched. Replan using the retained receipts.")
                        receipts[node.call.callId] = result
                        pending.remove(node)
                        completed(node.call, result)
                        continue
                    }
                    if (active.size >= window.concurrency || active.any { it in barriers } || !node.parallelRead && active.isNotEmpty()) continue
                    pending.remove(node)
                    active += node.call.callId
                    launch {
                        try {
                            val start = now()
                            channel.send(Completion(node, dispatch(node.call), (now() - start).coerceAtLeast(0)))
                        } catch (cancelled: CancellationException) {
                            // A child cancelling itself does not cancel a coroutineScope.
                            // End the collector too; it cannot wait for this receipt.
                            batchScope.cancel(cancelled)
                            throw cancelled
                        }
                    }
                    if (!node.parallelRead) break
                }
                report()
                if (active.isEmpty()) {
                    check(pending.isEmpty()) { "Tool batch dependencies cannot advance." }
                    break
                }
                val next = channel.receive()
                active -= next.node.call.callId
                receipts[next.node.call.callId] = next.result
                if (next.node.parallelRead && !next.result.sharedResult) window.observe(next.result.isError, next.elapsed)
                completed(next.node.call, next.result)
                report()
            }
            nodes.map { receipts.getValue(it.call.callId) }
        } finally {
            channel.close()
        }
    }
}
