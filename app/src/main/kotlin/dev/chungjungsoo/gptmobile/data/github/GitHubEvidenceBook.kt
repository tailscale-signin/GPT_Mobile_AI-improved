package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Receipts survive replay compaction. Persistence uses the encrypted, append-only workspace journal. */
internal class GitHubEvidenceBook(
    private val binding: String,
    private val repository: String,
    private val branch: String,
    private val trustedTools: Set<String>,
    private val now: () -> Long = System::currentTimeMillis,
    private val load: suspend (String) -> JsonObject? = { null },
    private val save: suspend (String, JsonObject) -> Unit = { _, _ -> }
) {
    private val mutex = Mutex()
    private val records = linkedMapOf<String, JsonObject>()
    private var bytes = 0L
    private var epoch = 0L
    private var observedHead: String? = null
    private var overflow = false

    suspend fun observe(call: ProviderEvent.ToolCall, result: AgentToolResult) = mutex.withLock {
        val id = "ev_" + digest("$binding|${call.callId}").take(24)
        if (id in records) return@withLock
        val existing = load(id)
        if (existing != null) {
            check(existing["binding"] == JsonPrimitive(binding) && existing["call_id"] == JsonPrimitive(call.callId)) { "Evidence binding changed" }
            val savedEpoch = (existing["epoch"] as? JsonPrimitive)?.longOrNull ?: 0
            if (savedEpoch >= epoch && existing["immutable"] != JsonPrimitive(true)) {
                (existing["source_head_sha"] as? JsonPrimitive)?.contentOrNull?.let { observedHead = it }
            }
            epoch = maxOf(epoch, savedEpoch)
            retain(id, existing)
            return@withLock
        }
        val content = when (val body = result.retainedContent ?: result.content) {
            is ToolResultContent.Json -> body.value
            is ToolResultContent.Text -> runCatching { Json.parseToJsonElement(body.text) }.getOrElse { JsonPrimitive(body.text) }
            is ToolResultContent.ResourceLinks -> JsonNull
        }
        val operation = GitHubOperationTool.operationName(call.name) ?: "github".takeIf { call.name.substringBefore("__") == it }
        val trusted = call.name in trustedTools && operation != null
        val owner = (call.arguments["owner"] as? JsonPrimitive)?.contentOrNull
        val repo = (call.arguments["repo"] as? JsonPrimitive)?.contentOrNull
        val selected = owner == null && repo == null || "$owner/$repo".equals(repository, true)
        val targetBranch = (call.arguments["ref"] as? JsonPrimitive)?.contentOrNull ?: (call.arguments["branch"] as? JsonPrimitive)?.contentOrNull ?: branch
        val immutable = GitHubOperationTool.immutableRead(call.name, call.arguments)
        val native = if (operation == "github") call.arguments else GitHubOperationTool.nativeArguments(call.name, call.arguments)
        val action = (native?.get("action") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val mutation = action in GitHubWorkspaceClient.writeActions - "stage_patch" || action in setOf("create_branch", "update_file", "create_pull_request", "create_issue_comment", "dispatch_workflow", "rerun_workflow", "rerun_failed_jobs", "cancel_workflow")
        val root = (content as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
        val head = (
            listOf("head_sha", "commit_sha").firstNotNullOfOrNull { (root?.get(it) as? JsonPrimitive)?.contentOrNull }
                ?: ((root?.get("head") as? JsonObject)?.get("sha") as? JsonPrimitive)?.contentOrNull
            )
            ?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }
        if (trusted && selected && targetBranch == branch && !result.isError && !result.sharedResult) {
            if (mutation || !immutable && head != null && observedHead != null && head != observedHead) epoch++
            if (head != null && !immutable) observedHead = head
        }
        val receipt = buildJsonObject {
            put("schema", "github.evidence.v1")
            put("receipt_id", id)
            put("binding", binding)
            put("call_id", call.callId)
            put("tool", call.name)
            put("repository", if (selected) repository else "$owner/$repo")
            put("branch", targetBranch)
            put("arguments", call.arguments)
            put("observed_at_ms", if (result.sharedResult || !result.dispatched) 0 else now())
            put("epoch", epoch)
            put("source_head_sha", head?.let(::JsonPrimitive) ?: JsonNull)
            put("immutable", immutable)
            put("trusted", trusted && selected)
            put("success", !result.isError && result.errorCode == null)
            put("dispatched", result.dispatched)
            put("error_code", result.errorCode?.let(::JsonPrimitive) ?: JsonNull)
            put("result", content)
            put("result_sha256", digest(content.toString()))
        }
        check(records.size < 4096 && bytes + receipt.toString().encodeToByteArray().size <= 16 * 1024 * 1024) {
            overflow = true
            "Evidence capacity reached; no unverified response can be published"
        }
        try {
            save(id, receipt)
        } catch (failure: Exception) {
            overflow = true // A transient storage error must not turn an unpersisted receipt into proof.
            throw failure
        }
        retain(id, receipt)
    }

    private fun retain(id: String, value: JsonObject) {
        bytes += value.toString().encodeToByteArray().size
        if (bytes > 16 * 1024 * 1024 || records.size >= 4096) overflow = true else records[id] = value
    }

    suspend fun snapshot(): Pair<Map<String, JsonObject>, Long> = mutex.withLock {
        check(!overflow) { "Evidence exceeds the retained verification allowance" }
        records.toMap() to epoch
    }

    suspend fun retainVerdict(value: JsonObject) {
        save("verification_" + digest("$binding|$value").take(24), JsonObject(value + ("binding" to JsonPrimitive(binding))))
    }

    companion object {
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
