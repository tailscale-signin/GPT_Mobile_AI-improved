package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** An observed rejection proves this request did not apply; a lost response does not. */
internal class GitHubRequestRejected(val status: Int, message: String) : IllegalStateException(message)

internal class GitHubExecutionBlocked(val checkpoint: JsonObject) :
    IllegalStateException(
        "${checkpoint["status"]}: inspect the saved execution checkpoint and reconcile remote state before continuing. No request was dispatched."
    )

/** Atomic, credential/task-scoped claims. An uncertain write is reconciled, never replayed. */
internal class GitHubExecutionJournal(
    private val store: GitHubRepositoryStateStore,
    private val scope: String,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun run(action: String, arguments: JsonObject, singleHttpRequest: Boolean = false, block: suspend () -> JsonElement): JsonElement {
        val identity = GitHubRepositoryStateStore.digest("$scope|$action|${canonical(arguments)}")
        val claim = UUID.randomUUID().toString()
        val row = store.update(scope, "execution:$identity") { previous ->
            if (previous != null && previous.text("status") != "REJECTED") {
                previous
            } else {
                buildJsonObject {
                    put("schema_version", 1)
                    put("execution_id", identity)
                    put("correlation_id", claim)
                    put("trace_id", claim)
                    put("checkpoint_sequence", ((previous?.get("checkpoint_sequence") as? JsonPrimitive)?.intOrNull ?: 0) + 1)
                    put("action", action)
                    put("status", "RUNNING")
                    put("attempt", (previous?.get("attempt") as? JsonPrimitive)?.intOrNull?.plus(1) ?: 1)
                    put("started_at_epoch_ms", now())
                    put("updated_at_epoch_ms", now())
                }
            }
        }
        if (row.text("correlation_id") != claim) {
            return if (row.text("status") == "COMPLETED" && row["result"] != null) {
                decorate(row.getValue("result"), row, replayed = true)
            } else {
                buildJsonObject {
                    put("schema", "github.execution.v1")
                    put("status", if (row.text("status") == "RUNNING") "EXECUTION_IN_PROGRESS" else "RECONCILE_REQUIRED")
                    put("execution", metadata(row))
                    put("dispatched", false)
                    put("message", "This execution already has a publication claim. Inspect the saved stage/remote receipt before continuing; no write was repeated. A process restart does not release an uncertain claim.")
                }
            }
        }
        try {
            remember(row)
            val result = block()
            val retained = if (result.toString().toByteArray().size <= 128 * 1024) {
                result
            } else {
                buildJsonObject {
                    put("receipt_bounded", true)
                    (result as? JsonObject)?.filterKeys { it in RECEIPT_KEYS }?.forEach { (key, value) ->
                        if (value.toString().toByteArray().size <= 2048) put(key, value)
                    }
                    put("message", "The original write completed. Only its bounded receipt is retained; inspect the remote object for additional details.")
                }
            }
            val completed = finish(identity, claim, "COMPLETED", retained, GitHubRepositoryStateStore.digest(canonical(result).toString()))
            return decorate(result, completed, replayed = false)
        } catch (failure: Throwable) {
            // Cancellation can arrive after GitHub applied a write. Persist that uncertainty
            // even when the caller is cancelled, without storing messages/source/credentials.
            withContext(NonCancellable) {
                // A completed receipt is authoritative even if the secondary context
                // index could not be refreshed. Never downgrade it to uncertainty.
                if (store.read(scope, "execution:$identity")?.text("status") == "RUNNING") {
                    try {
                        val rejected = failure is GitHubRequestRejected ||
                            failure is GitHubRequestCircuit.Open ||
                            singleHttpRequest &&
                            failure is GitHubRequestScheduler.Capacity
                        finish(identity, claim, if (rejected) "REJECTED" else "RECONCILE_REQUIRED")
                    } catch (checkpointFailure: Throwable) {
                        failure.addSuppressed(checkpointFailure)
                    }
                }
            }
            if (failure is CancellationException) throw failure
            throw failure
        }
    }

    suspend fun context(): JsonObject {
        val started = System.nanoTime()
        val context = store.read(scope, "execution-context") ?: buildJsonObject {
            put("schema_version", 1)
            put("executions", JsonArray(emptyList()))
        }
        val rows = (context["executions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val unresolved = rows.filter { it.text("status") in setOf("RUNNING", "RECONCILE_REQUIRED") }
        val currentTime = now()
        val oldestAge = unresolved.mapNotNull { (it["started_at_epoch_ms"] as? JsonPrimitive)?.longOrNull }
            .minOrNull()?.let { (currentTime - it).coerceAtLeast(0) }
        return buildJsonObject {
            context.forEach { (key, value) -> put(key, value) }
            put(
                "recovery_metrics",
                buildJsonObject {
                    put("schema_version", 1)
                    put("retained_execution_count", rows.size)
                    put("completed_count", rows.count { it.text("status") == "COMPLETED" })
                    put("running_count", rows.count { it.text("status") == "RUNNING" })
                    put("reconciliation_required_count", rows.count { it.text("status") == "RECONCILE_REQUIRED" })
                    oldestAge?.let { put("oldest_unresolved_age_ms", it) }
                    put("context_load_duration_ms", ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0))
                    put("rto_status", "NOT_CONFIGURED")
                    put("coverage", "Latest 32 retained task-scoped checkpoints only. Load duration is not end-to-end recovery time; unknown or evicted executions are not counted.")
                }
            )
        }
    }

    private suspend fun finish(identity: String, claim: String, status: String, result: JsonElement? = null, outputHash: String? = null): JsonObject {
        val row = store.update(scope, "execution:$identity") { previous ->
            check(previous?.text("correlation_id") == claim && previous.text("status") == "RUNNING") { "Execution claim changed; reconcile the retained checkpoint." }
            buildJsonObject {
                previous.forEach { (key, value) -> put(key, value) }
                put("status", status)
                put("checkpoint_sequence", (previous["checkpoint_sequence"] as? JsonPrimitive)?.intOrNull?.plus(1) ?: 2)
                put("updated_at_epoch_ms", now())
                result?.let {
                    put("result", it)
                    put("output_hash", outputHash ?: GitHubRepositoryStateStore.digest(canonical(it).toString()))
                    put("output_hash_covers", "original_response")
                }
            }
        }
        remember(row)
        return row
    }

    private suspend fun remember(row: JsonObject) {
        store.update(scope, "execution-context") { previous ->
            val rows = (previous?.get("executions") as? JsonArray).orEmpty().filter { it.jsonObject.text("execution_id") != row.text("execution_id") }
            buildJsonObject {
                put("schema_version", 1)
                put("executions", JsonArray((rows + metadata(row)).takeLast(32)))
                put("retention", "Latest 32 task-scoped execution checkpoints; source arguments and credentials are excluded.")
            }
        }
        AppLogRecorder.record("GitHubExecution", metadata(row).toString())
    }

    private fun decorate(value: JsonElement, row: JsonObject, replayed: Boolean): JsonObject = buildJsonObject {
        if (value is JsonObject) value.forEach { (key, item) -> put(key, item) } else put("data", value)
        put(
            "execution",
            buildJsonObject {
                metadata(row).forEach { (key, item) -> put(key, item) }
                put("replayed_receipt", replayed)
            }
        )
    }

    private fun metadata(row: JsonObject) = JsonObject(row.filterKeys { it != "result" })

    companion object {
        private val RECEIPT_KEYS = setOf("id", "sha", "node_id", "number", "html_url", "url", "status", "stage_id", "task_id", "engineering_task_id", "expected_head_sha", "branch", "committed")
        internal fun canonical(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(value.map(::canonical))
            else -> value
        }
        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()
    }
}
