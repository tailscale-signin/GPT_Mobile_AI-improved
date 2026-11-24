package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Keep executable continuation arguments intact when replaying a large GitHub result. */
internal object GitHubReplayRecovery {
    private const val OMITTED_SAVED_RESULT = "GitHub result omitted from replay: its continuation exceeds the available replay budget. Do not invent a cursor or repeat a mutation; inspect retained evidence or request a narrower immutable read."
    private data class CapacityRefusal(val page: String, val bytes: Int, val source: String, val scope: String, val recovery: JsonObject?)

    /** Keep recovery allowance, native reads and delivery witnesses outside the evictable provider replay. */
    class State(calls: Collection<ProviderEvent.ToolCall> = emptyList(), results: Collection<AgentToolResult> = emptyList()) {
        private data class VerifiedRead(val call: ProviderEvent.ToolCall, val page: String, val resultIdentity: JsonObject, val deliveryDigest: String?)
        private val automaticCalls = mutableSetOf<String>()
        private val readPages = mutableMapOf<String, String>()
        private val verifiedReads = mutableMapOf<String, VerifiedRead>()
        private val capacityRefusals = mutableMapOf<String, Pair<ProviderEvent.ToolCall, CapacityRefusal>>()
        private val selectedReads = linkedMapOf<String, Pair<String, JsonObject>>()
        private val remoteReads = mutableSetOf<String>()
        private val deliveredReads = GitHubSavedReadProgress()

        init {
            record(calls, results)
        }

        fun record(calls: Collection<ProviderEvent.ToolCall>, results: Collection<AgentToolResult>? = null) {
            val completed = results?.groupBy { it.callId }
            calls.forEach { call ->
                if (call.callId.startsWith("saved-result-consume-") || call.callId.startsWith("remote-result-consume-")) automaticCalls += call.callId
                remoteReads += remoteKey(call.name, call.arguments)
                if (completed == null) {
                    if (capacityRefusals[call.callId]?.first?.let { it != call } == true) capacityRefusals.remove(call.callId)
                    if (verifiedReads[call.callId]?.call?.let { it != call } == true) {
                        verifiedReads.remove(call.callId)
                        readPages.remove(call.callId)
                    }
                    // Availability checks may plan the same call repeatedly. Only
                    // recording that exact selected invocation reserves its page.
                    if (selectedReads[call.callId] == (call.name to call.arguments)) {
                        GitHubSavedReadProgress.cursor(call.arguments)?.let { readPages.putIfAbsent(call.callId, pageKey(call.name, it.value)) }
                    }
                } else {
                    selectedReads.remove(call.callId)
                    val result = completed[call.callId]?.singleOrNull()
                    val capacity = result?.let { savedPageCapacityRefusal(call, it) }
                    if (capacity == null) {
                        capacityRefusals.remove(call.callId)
                    } else {
                        val previousCapacity = capacityRefusals[call.callId]?.takeIf {
                            it.first == call && it.second.copy(recovery = null) == capacity.copy(recovery = null)
                        }?.second
                        capacityRefusals[call.callId] = call to capacity.copy(recovery = capacity.recovery ?: previousCapacity?.recovery)
                    }
                    val page = result?.let { savedPageKey(call, it) }
                    val previous = verifiedReads[call.callId]?.takeIf {
                        result != null && it.call == call && it.resultIdentity == savedResultIdentity(result) && ordinarySavedProjection(call, result)
                    }
                    // A refusal releases only this call's provisional reservation;
                    // another successful native read of the same page remains admitted.
                    when {
                        page != null -> {
                            readPages[call.callId] = page
                            val body = envelope(requireNotNull(result).content)
                            val completeBody = body?.get("schema") == JsonPrimitive("github.operation.v1") &&
                                body["replay_compacted"] != JsonPrimitive(true) &&
                                body["observationTruncated"] != JsonPrimitive(true)
                            val deliveryDigest = if (completeBody) ToolResultDeliveryProof.digest(result.content) else previous?.deliveryDigest
                            verifiedReads[call.callId] = VerifiedRead(call, page, savedResultIdentity(result), deliveryDigest)
                        }
                        previous != null -> readPages[call.callId] = previous.page
                        else -> {
                            readPages.remove(call.callId)
                            verifiedReads.remove(call.callId)
                        }
                    }
                }
            }
        }

        fun exhausted() = automaticCalls.size >= 32
        fun hasRead(name: String, cursor: String) = pageKey(name, cursor) in readPages.values
        fun hasAttempt(callId: String) = callId in automaticCalls
        fun capacityRefused(name: String, cursor: String): Boolean {
            val parsed = GitHubSavedReadProgress.cursor(cursor) ?: return false
            val page = pageKey(name, cursor)
            return capacityRefusals.values.any { it.second.page == page && parsed.bytes <= it.second.bytes }
        }
        fun capacityRecoveries(): List<Pair<ProviderEvent.ToolCall, JsonObject>> = capacityRefusals.values.mapNotNull { (call, refusal) ->
            refusal.recovery?.let { call to buildJsonObject { put("recovery_call", it) } }
        }
        fun select(call: ProviderEvent.ToolCall) {
            selectedReads[call.callId] = call.name to call.arguments
            while (selectedReads.size > 256) selectedReads.remove(selectedReads.keys.first())
        }
        fun hasRemoteRead(name: String, args: JsonObject) = remoteKey(name, args) in remoteReads
        fun observeDelivered(exchanges: List<AgentToolExchange>, deliveredResultDigests: Map<String, String>, cache: ToolExchangeCompactor.RenderCache? = null) = deliveredReads.observe(exchanges, deliveredResultDigests, cache)
        fun advanceSavedRead(call: ProviderEvent.ToolCall, tool: AgentToolDefinition?) = deliveredReads.advance(call, tool)

        /** Native admission and provider delivery are separate facts. Projections cannot replace the full-body witness. */
        fun pendingSavedPageDeliveries(exchanges: List<AgentToolExchange>, deliveredResultDigests: Map<String, String>): Map<String, String?> {
            exchanges.forEach { record(it.calls, it.results) }
            val deliveredPages = verifiedReads.filter { (id, read) -> read.deliveryDigest != null && deliveredResultDigests[id] == read.deliveryDigest }
                .values.mapNotNull(::immutablePageIdentity).toSet()
            // Legacy restored projections may establish native admission without
            // retaining the original body. An unknown digest must block delivery,
            // even if that projection has its own successful provider receipt.
            // An exact later reread may satisfy the same immutable page obligation;
            // this does not mark the older call or its unknown body as delivered.
            val pending = verifiedReads.filter { (id, read) ->
                (read.deliveryDigest == null || deliveredResultDigests[id] != read.deliveryDigest) && immutablePageIdentity(read) !in deliveredPages
            }
            if (pending.size < 2) return pending.mapValues { it.value.deliveryDigest }
            val bodies = exchanges.flatMap { it.results }.associateBy { it.callId }
            val witnesses = mutableMapOf<List<String>, String>()
            for ((id, read) in pending) {
                val identity = immutablePageIdentity(read) ?: continue
                val body = bodies[id]?.content ?: continue
                if (read.deliveryDigest != null &&
                    envelope(body)?.get("schema") == JsonPrimitive("github.operation.v1") &&
                    ToolResultDeliveryProof.digest(body) == read.deliveryDigest
                ) {
                    witnesses[identity] = id
                }
            }
            return pending.filter { (id, read) -> immutablePageIdentity(read)?.let(witnesses::get)?.let { it == id } != false }
                .mapValues { (_, read) -> read.deliveryDigest }
        }

        fun restoreMissingPage(pending: Map<String, String?>, exchanges: List<AgentToolExchange>, tools: List<AgentToolDefinition>, pageBudget: Int = Int.MAX_VALUE): ProviderEvent.ToolCall? {
            val bodies = exchanges.flatMap { it.results }.associateBy { it.callId }
            for ((id, digest) in pending) {
                val read = verifiedReads[id] ?: continue
                val identity = immutablePageIdentity(read) ?: continue
                val capacity = GitHubSavedReadProgress.cursor(identity[1])?.bytes ?: continue
                if ((capacity.toLong() + 2) / 3 + 512 > pageBudget) continue
                val body = bodies[id]?.content
                if (digest != null && body != null && ToolResultDeliveryProof.digest(body) == digest) continue
                val retryId = "saved-result-consume-restore-" + dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest(identity.joinToString("|")).take(24)
                if (hasAttempt(retryId) || exhausted()) continue
                val tool = tools.singleOrNull { it.modelVisible && it.name == read.call.name } ?: continue
                val args = read.call.arguments
                if (ToolArgumentValidator.errors(args, tool.inputSchema).isEmpty()) {
                    return ProviderEvent.ToolCall(retryId, tool.name, args)
                }
            }
            return null
        }

        private fun immutablePageIdentity(read: VerifiedRead): List<String>? {
            val receipt = read.resultIdentity["execution_receipt"] as? JsonObject ?: return null
            val cursor = (read.call.arguments["view_cursor"] as? JsonPrimitive)?.content ?: return null
            val source = ((receipt["saved_view_source_sha256"] ?: receipt["saved_view_source_hash"]) as? JsonPrimitive)?.content ?: return null
            val scope = (receipt["saved_view_scope_hash"] as? JsonPrimitive)?.content ?: return null
            if (!ToolResultDeliveryProof.valid(source) || !ToolResultDeliveryProof.valid(scope)) return null
            // The complete cursor includes its byte size; a shorter page at the
            // same offset cannot satisfy a larger original page's obligation.
            return listOf(read.call.name, cursor, source, scope)
        }

        private fun remoteKey(name: String, args: JsonObject) = remoteReadKey(name, args)
    }

    /** A display projection may retain an existing witness, never establish one. */
    private fun ordinarySavedProjection(call: ProviderEvent.ToolCall, result: AgentToolResult): Boolean {
        if (ToolExchangeCompactor.isReplayPlaceholder(result.content) || (result.content as? ToolResultContent.Text)?.text == OMITTED_SAVED_RESULT) return true
        val body = envelope(result.content) ?: return false
        val operation = GitHubOperationTool.operationName(call.name) ?: return false
        val summary = body["schema"] == JsonPrimitive("github.evidence-summary.v1")
        if (!summary && (body["schema"] != JsonPrimitive("github.operation.v1") || body["replay_compacted"] != JsonPrimitive(true))) return false
        if (body["previously_delivered"] !in listOf(JsonPrimitive(true), JsonPrimitive(false))) return false
        if (((!summary || "operation" in body) && body["operation"] != JsonPrimitive(operation)) || body["page_capacity_exceeded"] == JsonPrimitive(true) || body["observationTruncated"] == JsonPrimitive(true)) return false
        val value = (call.arguments["view_cursor"] as? JsonPrimitive)?.content ?: return false
        val cursor = GitHubSavedReadProgress.cursor(value) ?: return false
        val receipt = result.executionReceipt ?: return false
        if ("source_sha256" in body && body["source_sha256"] != (receipt["saved_view_source_sha256"] ?: receipt["saved_view_source_hash"])) return false
        if ("fragment_offset" in body && (body["fragment_offset"] as? JsonPrimitive)?.intOrNull != cursor.offset) return false
        if ("recovery_call" in body) {
            val recovery = (body["recovery_call"] as? JsonObject)?.let { GitHubSavedReadProgress.recipeCursor(it, call.name) } ?: return false
            if (recovery.view != cursor.view || recovery.offset != cursor.offset) return false
        }
        return true
    }

    private fun savedResultIdentity(result: AgentToolResult) = buildJsonObject {
        put("call_id", result.callId)
        put("is_error", result.isError)
        put("dispatched", result.dispatched)
        result.errorCode?.let { put("error_code", it) }
        result.executionReceipt?.let { put("execution_receipt", it) }
        put("shared_result", result.sharedResult)
        put("tool_call_budget_exhausted", result.toolCallBudgetExhausted)
        put("output_budget_exhausted", result.outputBudgetExhausted)
    }

    // Only the native result can prove that the exact issued handle and any
    // carried repository, action or revision selectors were admitted together.
    private fun savedPageKey(call: ProviderEvent.ToolCall, result: AgentToolResult): String? {
        val operation = GitHubOperationTool.operationName(call.name) ?: return null
        val value = (call.arguments["view_cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val cursor = GitHubSavedReadProgress.cursor(value) ?: return null
        val receipt = result.executionReceipt ?: return null
        if (!GitHubResultViews.SAVED_CONTEXT_KEYS.containsAll(call.arguments.keys - "view_cursor") ||
            result.callId != call.callId ||
            result.isError ||
            result.outputBudgetExhausted ||
            result.toolCallBudgetExhausted ||
            result.dispatched ||
            result.errorCode != null ||
            listOf("requires_reconciliation", "requiresReconciliation").any { receipt[it] == JsonPrimitive(true) }
        ) {
            return null
        }
        fun text(key: String) = (receipt[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val source = text("saved_view_source_sha256") ?: text("saved_view_source_hash") ?: return null
        if (!source.matches(Regex("[a-f0-9]{64}")) ||
            listOf("saved_view_source_sha256", "saved_view_source_hash").any { it in receipt && text(it) != source }
        ) {
            return null
        }
        val expectedHash = dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest("github.saved-view.cursor.v1\u0000${cursor.value}")
        if (receipt["saved_view_cursor_issued"] == JsonPrimitive(false) ||
            receipt["saved_view_expired"] == JsonPrimitive(true) ||
            ("saved_view_cursor_hash" in receipt && text("saved_view_cursor_hash") != expectedHash) ||
            ("saved_view_owner_operation" in receipt && text("saved_view_owner_operation") != operation)
        ) {
            return null
        }
        val issued = receipt["saved_view_cursor_issued"] == JsonPrimitive(true) &&
            text("saved_view_cursor_hash") == expectedHash &&
            text("saved_view_owner_operation") == operation
        val retained = text("saved_view_id") == cursor.view &&
            JsonPrimitive(cursor.value) in (receipt["saved_view_cursor_ids"] as? JsonArray).orEmpty()
        if (!issued && !retained) return null
        val body = envelope(result.content)
        val fullPage = body?.get("schema") == JsonPrimitive("github.operation.v1") && body["replay_compacted"] != JsonPrimitive(true)
        // Older wrapper proofs include newly issued continuation handles too.
        // Without an exact consumed-handle hash, the full page must identify the
        // offset actually read; membership in the issuance list is insufficient.
        if (!issued && !fullPage) return null
        if (fullPage) {
            if (body["operation"] != JsonPrimitive(operation) || body["page_capacity_exceeded"] == JsonPrimitive(true) || body["observationTruncated"] == JsonPrimitive(true)) return null
            if ("source_sha256" in body && body["source_sha256"] != JsonPrimitive(source)) return null
            val recovery = (body["recovery_call"] as? JsonObject)?.let { GitHubSavedReadProgress.recipeCursor(it, call.name) } ?: return null
            if (recovery.view != cursor.view || recovery.offset != cursor.offset) return null
            if (body["source_page"] != JsonPrimitive(true) && (body["fragment_offset"] as? JsonPrimitive)?.intOrNull != cursor.offset) return null
        }
        return pageKey(call.name, cursor.value)
    }

    private fun pageKey(name: String, cursor: String) = "$name:${cursor.split(':').take(2).joinToString(":")}"

    /** A native capacity refusal blocks only equally small automatic reads, never proves consumption. */
    private fun savedPageCapacityRefusal(call: ProviderEvent.ToolCall, result: AgentToolResult): CapacityRefusal? {
        val operation = GitHubOperationTool.operationName(call.name) ?: return null
        val value = (call.arguments["view_cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val cursor = GitHubSavedReadProgress.cursor(value) ?: return null
        val receipt = result.executionReceipt ?: return null
        if (!GitHubResultViews.SAVED_CONTEXT_KEYS.containsAll(call.arguments.keys - "view_cursor") ||
            result.callId != call.callId ||
            !result.isError ||
            result.dispatched ||
            !result.sharedResult ||
            result.errorCode != "SAVED_VIEW_PAGE_CAPACITY" ||
            listOf("requires_reconciliation", "requiresReconciliation").any { receipt[it] == JsonPrimitive(true) }
        ) {
            return null
        }
        fun text(key: String) = (receipt[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val source = text("saved_view_source_hash") ?: return null
        if (!source.matches(Regex("[a-f0-9]{64}")) ||
            text("saved_view_scope_hash")?.matches(Regex("[a-f0-9]{64}")) != true ||
            receipt["saved_view_cursor_issued"] != JsonPrimitive(true) ||
            receipt["saved_view_expired"] != JsonPrimitive(false) ||
            text("saved_view_owner_operation") != operation ||
            text("saved_view_cursor_hash") != dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest("github.saved-view.cursor.v1\u0000${cursor.value}") ||
            "saved_view_source_sha256" in receipt &&
            text("saved_view_source_sha256") != source
        ) {
            return null
        }
        val body = envelope(result.content)
        if (body == null) {
            if (!ToolExchangeCompactor.isReplayPlaceholder(result.content) && (result.content as? ToolResultContent.Text)?.text != OMITTED_SAVED_RESULT) return null
        } else {
            if (body["schema"] !in setOf(JsonPrimitive("github.operation.v1"), JsonPrimitive("github.evidence-summary.v1"))) return null
            val compacted = body["replay_compacted"] == JsonPrimitive(true) || body["schema"] == JsonPrimitive("github.evidence-summary.v1")
            if ((!compacted && (body["schema"] != JsonPrimitive("github.operation.v1") || body["page_capacity_exceeded"] != JsonPrimitive(true))) ||
                "page_capacity_exceeded" in body &&
                body["page_capacity_exceeded"] != JsonPrimitive(true) ||
                "operation" in body &&
                body["operation"] != JsonPrimitive(operation) ||
                "source_sha256" in body &&
                body["source_sha256"] != JsonPrimitive(source) ||
                "requested_page_bytes" in body &&
                body["requested_page_bytes"] != JsonPrimitive(cursor.bytes) ||
                "fragment_offset" in body &&
                body["fragment_offset"] != JsonPrimitive(cursor.offset)
            ) {
                return null
            }
        }
        val compactedRecovery = body?.takeIf {
            it["replay_compacted"] == JsonPrimitive(true) && it["previously_delivered"] == JsonPrimitive(false) && it["recovery_required"] == JsonPrimitive(true)
        }?.get("continuation_call") as? JsonObject
        val recovery = ((body?.get("recovery_call") as? JsonObject) ?: compactedRecovery)?.takeIf { recipe ->
            val corrected = GitHubSavedReadProgress.recipeCursor(recipe, call.name)
            corrected != null && corrected.view == cursor.view && corrected.offset == cursor.offset && corrected.bytes > cursor.bytes
        }
        return CapacityRefusal(pageKey(call.name, value), cursor.bytes, source, requireNotNull(text("saved_view_scope_hash")), recovery)
    }

    /** Advance only native-issued reads; exhaustive search is an explicit task policy. */
    fun nextRemoteRead(exchanges: List<AgentToolExchange>, tools: Collection<AgentToolDefinition>, state: State? = null, allowSearchContinuation: Boolean = false, searchTask: String? = null): ProviderEvent.ToolCall? {
        if (state?.exhausted() == true) return null
        val calls = exchanges.flatMap { it.calls }
        if (calls.count { it.callId.startsWith("remote-result-consume-") } >= 32) return null
        fun canonical(name: String, args: JsonObject) = dev.chungjungsoo.gptmobile.data.github.GitHubActionIdentity.canonicalArguments(name, args)
        fun scope(args: JsonObject): JsonObject {
            val normalized = (args - "page").toMutableMap()
            normalized.putIfAbsent("per_page", JsonPrimitive(30))
            for (key in listOf("owner", "repo")) {
                (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.let { normalized[key] = JsonPrimitive(it.content.lowercase(java.util.Locale.ROOT)) }
            }
            return JsonObject(normalized)
        }
        for (exchange in exchanges.asReversed()) {
            for (result in exchange.results.asReversed()) {
                if (result.isError) continue
                val issuer = exchange.calls.firstOrNull { it.callId == result.callId } ?: continue
                val original = canonical(issuer.name, issuer.arguments)
                if (allowSearchContinuation && GitHubOperationTool.operationName(issuer.name) == "github_search") {
                    if (searchTask != null && !dev.chungjungsoo.gptmobile.data.github.GitHubExecutionContinuation.requiresExhaustiveSearch(searchTask, original)) continue
                    val recipe = dev.chungjungsoo.gptmobile.data.github.GitHubSearchFrontier.continuation(issuer.name, original, result.executionReceipt) ?: continue
                    val args = recipe["arguments"] as? JsonObject ?: continue
                    val tool = tools.firstOrNull { it.name == issuer.name && it.modelVisible } ?: continue
                    if (ToolArgumentValidator.errors(args, tool.inputSchema).isNotEmpty() ||
                        state?.hasRemoteRead(issuer.name, args) == true ||
                        calls.any { it.name == issuer.name && remoteReadKey(it.name, it.arguments) == remoteReadKey(issuer.name, args) }
                    ) {
                        continue
                    }
                    val key = dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest(remoteReadKey(issuer.name, args)).take(24)
                    return ProviderEvent.ToolCall("remote-result-consume-$key", issuer.name, args)
                }
                if (GitHubOperationTool.operationName(issuer.name) != "github_ci" || original["action"] != JsonPrimitive("workflows")) continue
                val source = envelope(result.content)
                val recipes = listOfNotNull(source?.get("continuation_call") as? JsonObject) +
                    listOf(source?.get("remote_continuations"), result.executionReceipt?.get("retained_remote_continuations"))
                        .flatMap { (it as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>().mapNotNull { it["call"] as? JsonObject }
                val bound = workflowScope(original, result.executionReceipt)
                for (recipe in recipes) {
                    val name = (recipe["tool"] as? JsonPrimitive)?.content ?: continue
                    val args = (recipe["arguments"] as? JsonObject)?.let { canonical(name, it) } ?: continue
                    val page = (args["page"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue
                    val priorPage = (original["page"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
                    if (name != issuer.name || page != priorPage + 1 || scope(args) != scope(bound) || "view_cursor" in args) continue
                    val tool = tools.firstOrNull { it.name == name && it.modelVisible } ?: continue
                    if (ToolArgumentValidator.errors(args, tool.inputSchema).isNotEmpty() ||
                        state?.hasRemoteRead(name, args) == true ||
                        calls.any { it.name == name && scope(canonical(name, it.arguments)) == scope(args) && it.arguments["page"] == args["page"] }
                    ) {
                        continue
                    }
                    val key = dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest(remoteReadKey(name, args)).take(24)
                    return ProviderEvent.ToolCall("remote-result-consume-$key", name, args)
                }
            }
        }
        return null
    }

    private fun workflowScope(original: JsonObject, receipt: JsonObject?): JsonObject {
        if (receipt?.get("schema") != JsonPrimitive("github.dispatch.v1")) return original
        val resolved = receipt["resolved_arguments"] as? JsonObject ?: return original
        val resource = receipt["resource_context"] as? JsonObject ?: return original
        if (resolved["action"] != JsonPrimitive("list_workflows")) return original
        val defaults = buildMap {
            for (key in listOf("owner", "repo", "ref")) {
                if (key in original) continue
                val selected = resource[if (key == "ref") "selected_ref" else key] as? JsonPrimitive ?: continue
                val actual = resolved[key] ?: selected.takeIf { key == "ref" }
                val value = (actual as? JsonPrimitive)?.takeIf { it.isString } ?: continue
                val matches = if (key == "ref") value.content == selected.content else value.content.equals(selected.content, ignoreCase = true)
                if (matches) put(key, value)
            }
        }
        return JsonObject(original + defaults)
    }

    private fun remoteReadKey(name: String, args: JsonObject): String {
        val canonical = dev.chungjungsoo.gptmobile.data.github.GitHubActionIdentity.canonicalArguments(name, args)
        val normalized = if (GitHubOperationTool.operationName(name) == "github_ci" && canonical["action"] == JsonPrimitive("workflows")) {
            canonical + mapOf("page" to (canonical["page"] ?: JsonPrimitive(1)), "per_page" to (canonical["per_page"] ?: JsonPrimitive(30)))
        } else {
            canonical
        }
        return name + normalized.toSortedMap().toString()
    }

    /** Consume only an exact local read cursor issued by native replay recovery. */
    fun nextSavedRead(
        exchanges: List<AgentToolExchange>,
        tools: Collection<AgentToolDefinition>,
        maxReplayResultTokens: Int = 512,
        state: State? = null,
        admittedReplayTokens: Int = 0,
        maxOutputBytes: Int = 8192
    ): ProviderEvent.ToolCall? {
        exchanges.forEach { state?.record(it.calls, it.results) }
        val calls = exchanges.flatMap { it.calls }
        val maximumBytes = (maxReplayResultTokens.toLong().coerceAtLeast(0) * 3).coerceAtMost(8192).toInt()
        // A proven native capacity refusal may use more of the existing round's
        // allowance. Reserve room for its call, controls and other evidence; do
        // not enlarge the accepted context or tool-output budget.
        val capacityBytes = ((admittedReplayTokens.toLong() - 512).coerceAtLeast(0) * 3)
            .coerceAtMost(8192).coerceAtMost(maxOutputBytes.toLong().coerceAtLeast(0)).toInt()
        if (state?.exhausted() == true || calls.count { it.callId.startsWith("saved-result-consume-") } >= 32) return null
        val readPages = exchanges.flatMap { exchange ->
            val results = exchange.results.groupBy { it.callId }
            exchange.calls.mapNotNull { call -> results[call.callId]?.singleOrNull()?.let { savedPageKey(call, it) } }
        }.toSet()
        val capacityRefusals = exchanges.flatMap { exchange ->
            val results = exchange.results.groupBy { it.callId }
            exchange.calls.mapNotNull { call -> results[call.callId]?.singleOrNull()?.let { savedPageCapacityRefusal(call, it) } }
        }
        val recoverySources = exchanges.flatMap { exchange ->
            exchange.results.mapNotNull { result ->
                val call = exchange.calls.firstOrNull { it.callId == result.callId } ?: return@mapNotNull null
                // The evidence observer has saved the complete source before this
                // replay is built. Display fragments are not additional evidence.
                val source = envelope(result.content) ?: return@mapNotNull null
                val capacity = savedPageCapacityRefusal(call, result)
                val capacityRefused = capacity != null
                val unreadSource = source["source_page"] == JsonPrimitive(true) &&
                    source["recovery_required"] == JsonPrimitive(true) &&
                    source["previously_delivered"] == JsonPrimitive(false)
                if (result.executionReceipt?.get("retained_original_complete") == JsonPrimitive(true) && !unreadSource) return@mapNotNull null
                if (result.isError &&
                    !capacityRefused ||
                    !capacityRefused &&
                    source["schema"] != JsonPrimitive("github.repeat.v1") &&
                    !(source["replay_compacted"] == JsonPrimitive(true) && source["recovery_required"] == JsonPrimitive(true)) &&
                    !call.callId.startsWith("saved-result-consume-")
                ) {
                    return@mapNotNull null
                }
                val recoverySource = if (capacity != null) {
                    val recovery = capacity.recovery ?: return@mapNotNull null
                    buildJsonObject { put("recovery_call", recovery) }
                } else {
                    source
                }
                Triple(call, recoverySource, capacityRefused)
            }
        } + state?.capacityRecoveries().orEmpty().map { (call, source) -> Triple(call, source, true) }
        for ((issuer, source, capacityRefused) in recoverySources.asReversed()) {
            // A consumed page's recovery handle points back to that same page.
            // Advance its issued continuation directly; never consume every page twice.
            val fields = if (capacityRefused) {
                listOf("recovery_call")
            } else if (issuer.callId.startsWith("saved-result-consume-")) {
                listOf("continuation_call")
            } else {
                listOf("recovery_call", "continuation_call")
            }
            for (field in fields) {
                val primary = source[field] as? JsonObject ?: continue
                val primaryCursor = ((primary["arguments"] as? JsonObject)?.get("view_cursor") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val options = (source[field.removeSuffix("_call") + "_options"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                val recipes = (options + primary).filter { recipe ->
                    val cursor = ((recipe["arguments"] as? JsonObject)?.get("view_cursor") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@filter false
                    val parts = cursor.split(':')
                    val bytes = parts.getOrNull(2)?.toIntOrNull() ?: return@filter false
                    parts.size == 3 && bytes in 1024..(if (capacityRefused) maxOf(maximumBytes.coerceAtMost(maxOutputBytes), capacityBytes) else maximumBytes) && parts.take(2) == primaryCursor.split(':').take(2)
                }.sortedByDescending { recipe -> ((recipe["arguments"] as JsonObject)["view_cursor"] as JsonPrimitive).content.substringAfterLast(':').toInt() }
                for (recipe in recipes) {
                    val name = (recipe["tool"] as? JsonPrimitive)?.content ?: continue
                    val issuedArguments = recipe["arguments"] as? JsonObject ?: continue
                    if (GitHubSavedReadProgress.cursor(issuedArguments) == null || name != issuer.name || GitHubOperationTool.operationName(name) == null) continue
                    val tool = tools.firstOrNull { it.name == name && it.modelVisible } ?: continue
                    val proposed = ProviderEvent.ToolCall("saved-result-consume-planning", name, issuedArguments)
                    val arguments = state?.advanceSavedRead(proposed, tool)?.arguments ?: issuedArguments
                    val selectedCursor = GitHubSavedReadProgress.cursor(arguments) ?: continue
                    val cursor = selectedCursor.value
                    if (capacityRefused) {
                        val refusedCursor = GitHubSavedReadProgress.cursor(issuer.arguments) ?: continue
                        if (selectedCursor.view != refusedCursor.view || selectedCursor.offset != refusedCursor.offset || selectedCursor.bytes <= refusedCursor.bytes) continue
                    }
                    if (ToolArgumentValidator.errors(arguments, tool.inputSchema).isNotEmpty() ||
                        state?.hasRead(name, cursor) == true ||
                        state?.capacityRefused(name, cursor) == true ||
                        capacityRefusals.any { it.page == pageKey(name, cursor) && selectedCursor.bytes <= it.bytes } ||
                        pageKey(name, cursor) in readPages
                    ) {
                        continue
                    }
                    val key = dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest("$name:$cursor").take(24)
                    val baseId = "saved-result-consume-$key"
                    // Failed attempts remain within the same allowance even if
                    // their replay rows are evicted before an exact local retry.
                    var callId = baseId
                    var retry = 0
                    while (state?.hasAttempt(callId) == true || calls.any { it.callId == callId }) callId = "$baseId-${++retry}"
                    return ProviderEvent.ToolCall(callId, name, arguments).also { state?.select(it) }
                }
            }
        }
        return null
    }

    /** Native-admitted local pages get space until exact delivery, independently of the call ID's origin. */
    fun savedPageResultAllowances(exchanges: List<AgentToolExchange>, consumedCallIds: Set<String>, maxReplayTokens: Int, defaultResultTokens: Int): Map<String, Int> {
        val available = (maxReplayTokens.toLong() - 512).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (available == 0 || defaultResultTokens <= 0) return emptyMap()
        return buildMap {
            exchanges.forEach { exchange ->
                val results = exchange.results.groupBy { it.callId }
                exchange.calls.forEach callLoop@{ call ->
                    if (call.callId in consumedCallIds) return@callLoop
                    val result = results[call.callId]?.singleOrNull() ?: return@callLoop
                    if (savedPageKey(call, result) == null) return@callLoop
                    val body = envelope(result.content) ?: return@callLoop
                    if (body["schema"] != JsonPrimitive("github.operation.v1") ||
                        body["replay_compacted"] == JsonPrimitive(true) ||
                        body["observationTruncated"] == JsonPrimitive(true)
                    ) {
                        return@callLoop
                    }
                    val cursor = GitHubSavedReadProgress.cursor((call.arguments["view_cursor"] as? JsonPrimitive)?.content.orEmpty()) ?: return@callLoop
                    // Native cursor capacity precedes app-added instructions. The
                    // final unprojected body must fit too; allowance is not a delivery proof.
                    val bytes = maxOf(cursor.bytes.toLong(), ToolHandoffReceipt.payload(result.content).encodeToByteArray().size.toLong())
                    val required = ((bytes + 2) / 3).toInt()
                    if (required <= available) put(call.callId, required)
                }
            }
        }
    }

    fun envelope(content: ToolResultContent): JsonObject? = when (content) {
        is ToolResultContent.Json -> content.value as? JsonObject
        is ToolResultContent.Text -> runCatching { Json.parseToJsonElement(content.text) as? JsonObject }.getOrNull()
        else -> null
    }?.takeIf { (it["schema"] as? JsonPrimitive)?.content?.startsWith("github.") == true }

    fun compact(content: ToolResultContent, maxChars: Int, consumed: Boolean = false): ToolResultContent? {
        val source = envelope(content) ?: return null
        val controls = source.filterKeys { it in setOf("schema", "operation", "status", "local_page_complete", "continuation_call", "continuation_options", "remote_continuations", "correction_call", "source_recovery_call", "source_read_call", "previous_call_id", "metadata", "recovery_required", "recovery_action", "mutation_replay_allowed", "agent_instruction", "reason", "semantic_summary", "retained_original_complete", "source_result_sha256", "receipt_reference") }
        val result = controls.toMutableMap()
        result["replay_compacted"] = JsonPrimitive(true)
        result["previously_delivered"] = JsonPrimitive(consumed)
        // The omitted page may never have reached the model. Recover that exact
        // page before offering any cursor that skips past its unread records.
        val completeSourceCorrection = source["operation"] == JsonPrimitive("github_read") &&
            source["local_page_complete"] == JsonPrimitive(true) &&
            source["recovery_action"] == JsonPrimitive("RECOVER_SOURCE_EVIDENCE") &&
            source["correction_call"] is JsonObject
        (source["recovery_call"] as? JsonObject)?.takeUnless { consumed || completeSourceCorrection }?.let { recovery ->
            result["continuation_call"] = recovery
            result.remove("continuation_options")
            source["recovery_options"]?.let { result["continuation_options"] = it }
            result.remove("remote_continuations")
            result["local_page_complete"] = JsonPrimitive(false)
            result["recovery_required"] = JsonPrimitive(true)
        }
        // Optional larger pages must not crowd out the small, exact recovery call.
        if (JsonObject(result).toString().length > maxChars) result.remove("continuation_options")
        if (JsonObject(result).toString().length > maxChars && consumed && source["semantic_summary"] is JsonObject) {
            val summary = buildJsonObject {
                put("schema", "github.evidence-summary.v1")
                source["operation"]?.let { put("operation", it) }
                put("semantic_summary", requireNotNull(source["semantic_summary"]))
                source["receipt_reference"]?.let { put("receipt_reference", it) }
                put("previously_delivered", true)
            }
            if (summary.toString().length <= maxChars) return ToolResultContent.Json(summary)
        }
        // Never splice the middle of a cursor or a JSON argument object.
        if (JsonObject(result).toString().length > maxChars) {
            return ToolResultContent.Text(OMITTED_SAVED_RESULT)
        }
        if (source["source_page"] == JsonPrimitive(true)) {
            result["source_page"] = JsonPrimitive(true)
            source["commit_sha"]?.let { result["commit_sha"] = it }
            if (JsonObject(result).toString().length > maxChars) result.remove("commit_sha")
            // Preserve exact characters and their location even if the full file page was
            // evicted. The native recovery call still points at the start of the unread page.
            val rows = (source["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            val visible = mutableListOf<JsonObject>()
            for (row in rows) {
                val body = (row["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: break
                val start = (row["start_line"] as? JsonPrimitive)?.content?.toIntOrNull() ?: break
                fun excerpt(length: Int): JsonObject {
                    val prefix = body.take(length)
                    return JsonObject(
                        row + buildJsonObject {
                            put("content", prefix)
                            put("end_line", start + prefix.count { it == '\n' } - if (prefix.endsWith('\n')) 1 else 0)
                            if (length < body.length) put("range_end", false)
                        }
                    )
                }
                var low = 0
                var high = minOf(body.length, maxChars)
                var fit = -1
                while (low <= high) {
                    val mid = (low + high) ushr 1
                    val length = if (mid in 1 until body.length && body[mid].isLowSurrogate()) mid - 1 else mid
                    val trial = JsonObject(result + ("items" to JsonArray(visible + excerpt(length))))
                    if (trial.toString().length <= maxChars) {
                        fit = length
                        low = mid + 1
                    } else {
                        high = mid - 1
                    }
                }
                if (fit < 0 || fit == 0 && body.isNotEmpty()) break
                visible += excerpt(fit)
                if (fit < body.length) break
            }
            if (visible.isNotEmpty()) result["items"] = JsonArray(visible)
            if (JsonObject(result).toString().length <= maxChars) return ToolResultContent.Json(JsonObject(result))
            result.remove("source_page")
        }
        val preview = (source["data"] ?: source["fragments"] ?: source["message"])?.toString().orEmpty()
        val available = ((maxChars - JsonObject(result).toString().length - 32) / 6).coerceAtLeast(0)
        if (available > 0) result["unverified_preview"] = JsonPrimitive(preview.take(available))
        return ToolResultContent.Json(JsonObject(result))
    }

    fun repeated(call: ProviderEvent.ToolCall, exchanges: List<AgentToolExchange>, message: String): AgentToolResult? {
        val operation = GitHubOperationTool.operationName(call.name) ?: return null
        val key = toolRepeatKey(call)
        val candidates = exchanges.asReversed().mapNotNull { exchange ->
            val ids = exchange.calls.filter { toolRepeatKey(it) == key }.mapTo(mutableSetOf()) { it.callId }
            exchange.results.lastOrNull { it.callId in ids && (!it.isError || !it.dispatched && it.errorCode in deterministicErrors) }
        }
        // A suppression is a reference to an observation, never a new observation
        // to wrap. Prefer its retained source when that source is still in replay.
        var previous = candidates.firstOrNull() ?: run {
            // A refused read has no remote result. Keep its last actual blocker available
            // when the identical request reaches the repeat limit.
            if (!dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(call.name, call.arguments)) return null
            val failures = exchanges.flatMap { exchange ->
                val ids = exchange.calls.filter { toolRepeatKey(it) == key }.mapTo(mutableSetOf()) { it.callId }
                exchange.results.filter { it.callId in ids }
            }
            val hasUncertainOutcome = failures.any { result ->
                result.dispatched ||
                    !result.isError ||
                    result.errorCode in ToolResultEnvelope.unknownOutcomeCodes ||
                    listOf("requires_reconciliation", "requiresReconciliation").any { result.executionReceipt?.get(it) == JsonPrimitive(true) }
            }
            if (failures.isEmpty() || hasUncertainOutcome) return null
            val blocked = failures.lastOrNull { !it.sharedResult && it.errorCode != null && it.errorCode != "NOT_DISPATCHED" } ?: failures.last()
            return AgentToolResult(
                call.callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("schema", "github.repeat.v1")
                        put("operation", operation)
                        put("status", "REPEAT_LIMIT")
                        put("prior_attempt_count", failures.size)
                        put("previous_call_id", blocked.callId)
                        put("previous_error_code", blocked.errorCode)
                        put("message", if (blocked.errorCode == "NOT_DISPATCHED") "Earlier identical requests were rejected before dispatch. Resolve the prior rejection before retrying." else message)
                        envelope(blocked.content)?.let { prior ->
                            for (field in listOf("correction_call", "recovery_call", "source_recovery_call")) prior[field]?.let { put(field, it) }
                        }
                    }
                ),
                isError = true,
                errorCode = "NOT_DISPATCHED",
                dispatched = false
            )
        }
        val visited = mutableSetOf<String>()
        while (envelope(previous.content)?.get("schema") == JsonPrimitive("github.repeat.v1") && visited.add(previous.callId)) {
            val sourceId = (envelope(previous.content)?.get("previous_call_id") as? JsonPrimitive)?.content ?: break
            previous = candidates.firstOrNull { it.callId == sourceId && it.callId !in visited } ?: break
        }
        if (previous.isError) return currentGitHubInvocationReceipt(previous.copy(callId = call.callId, dispatched = false, sharedResult = true, retainedContent = null, traceContent = null, searchValidationEvidence = null))
        val original = envelope(previous.content)
        val repeated = original?.get("schema") == JsonPrimitive("github.repeat.v1")
        val sourceCallId = previous.executionReceipt?.get("source_observation_call_id")
            ?: original?.get("previous_call_id")?.takeIf { repeated }
            ?: JsonPrimitive(previous.callId)
        return currentGitHubInvocationReceipt(
            AgentToolResult(
                call.callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("schema", "github.repeat.v1")
                        put("operation", operation)
                        put("status", "OBSERVATION_REUSED")
                        put("message", message)
                        put("previous_call_id", sourceCallId)
                        (previous.executionReceipt?.get("semantic_summary") ?: original?.get("semantic_summary"))?.let { put("semantic_summary", it) }
                        previous.executionReceipt?.get("retained_original_complete")?.let { put("retained_original_complete", it) }
                        original?.get("continuation_call")?.let { put("continuation_call", it) }
                        original?.get("remote_continuations")?.let { put("remote_continuations", it) }
                        original?.get("recovery_call")?.let { put("recovery_call", it) }
                        original?.get("recovery_options")?.let { put("recovery_options", it) }
                        original?.get("continuation_options")?.let { put("continuation_options", it) }
                        original?.get("source_recovery_call")?.let { put("source_recovery_call", it) }
                        if (repeated) {
                            original?.get("retained_result_preview")?.let { put("retained_result_preview", it) }
                        } else {
                            put("retained_result_preview", previous.content.toString().take(1200))
                        }
                        put("recovery", "Use the exact continuation_call above when present. The earlier call already completed; no action was repeated. Use a different unread range for additional source evidence.")
                    }
                ),
                isError = false,
                dispatched = false,
                sharedResult = true,
                executionReceipt = previous.executionReceipt
            )
        )
    }

    private val deterministicErrors = setOf("EVIDENCE_UNAVAILABLE", "INVALID_ARGUMENTS", "SAVED_VIEW_UNAVAILABLE", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED")
}
