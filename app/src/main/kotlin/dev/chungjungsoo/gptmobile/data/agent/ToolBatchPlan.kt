package dev.chungjungsoo.gptmobile.data.agent

internal data class ToolBatchPlan(
    val executable: List<ProviderEvent.ToolCall>,
    val deferred: List<ProviderEvent.ToolCall>,
    val perToolSuppressedIds: Set<String>,
    val hostSuppressedIds: Set<String>,
    val disabledNames: Set<String>,
    val acceptedByName: Map<String, Int>,
    val unavailableIds: Set<String> = emptySet(),
    val dependencyDeferredIds: Set<String> = emptySet(),
    val repeatLimitSnapshots: Map<String, ToolRepeatAdmissionEvidence> = emptyMap(),
    val reusedReadIds: Set<String> = emptySet()
)

/** Pure dispatch planning, separated from the suspending execution/UI coordinator. */
internal fun planToolBatch(
    calls: List<ProviderEvent.ToolCall>,
    remaining: Int,
    availableNames: Set<String>,
    usedByName: Map<String, Int>,
    blockedScopes: Set<String>,
    sameToolLimit: Int,
    retainedCallIds: Set<String> = emptySet(),
    settledCallIds: Set<String> = emptySet(),
    refreshContextId: String? = null,
    reusableReadIds: Set<String> = emptySet(),
    scope: (ProviderEvent.ToolCall) -> String
): ToolBatchPlan {
    val executable = mutableListOf<ProviderEvent.ToolCall>()
    val deferred = mutableListOf<ProviderEvent.ToolCall>()
    val suppressed = mutableSetOf<String>()
    val unavailable = mutableSetOf<String>()
    val hostSuppressed = mutableSetOf<String>()
    val disabled = mutableSetOf<String>()
    val accepted = mutableMapOf<String, Int>()
    val repeatLimitSnapshots = mutableMapOf<String, ToolRepeatAdmissionEvidence>()
    val reusedReads = mutableSetOf<String>()
    var newlyAdmitted = 0
    calls.forEach { call ->
        if (call.callId in settledCallIds) {
            executable += call
            return@forEach
        }
        val repeatKey = toolRepeatKey(call)
        val sourceRead = repeatKey != call.name
        val priorAdmissions = usedByName[repeatKey] ?: 0
        val batchAdmissions = accepted[repeatKey] ?: 0
        val used = priorAdmissions + batchAdmissions
        val repeatLimit = if (sourceRead || Regex("(?i)context[ _-]*retrieve|search[ _-]*nodes|read[ _-]*graph|open[ _-]*nodes").containsMatchIn(call.name)) 3 else sameToolLimit
        val retained = call.callId in retainedCallIds
        when {
            call.name !in availableNames -> {
                deferred += call
                unavailable += call.callId
            }
            !retained && call.callId in reusableReadIds -> {
                deferred += call
                reusedReads += call.callId
            }
            !retained && scope(call) in blockedScopes -> {
                deferred += call
                hostSuppressed += call.callId
            }
            !retained && used >= repeatLimit && call.callId != refreshContextId -> {
                deferred += call
                suppressed += call.callId
                if (!sourceRead) disabled += call.name
                repeatLimitSnapshots[call.callId] = ToolRepeatAdmissionEvidence.capture(
                    call,
                    repeatKey,
                    ToolDependencyDiagnostics.RepeatLimitSnapshot(
                        if (sourceRead) ToolDependencyDiagnostics.RepeatScope.TOOL_NAME_AND_ARGUMENTS else ToolDependencyDiagnostics.RepeatScope.TOOL_NAME,
                        priorAdmissions,
                        batchAdmissions,
                        used,
                        repeatLimit
                    )
                )
            }
            !retained && newlyAdmitted >= remaining -> deferred += call
            else -> {
                executable += call
                if (!retained) {
                    newlyAdmitted++
                    accepted[repeatKey] = (accepted[repeatKey] ?: 0) + 1
                }
            }
        }
    }
    return ToolBatchPlan(executable, deferred, suppressed, hostSuppressed, disabled, accepted, unavailable, repeatLimitSnapshots = repeatLimitSnapshots.toMap(), reusedReadIds = reusedReads)
}

/** Reading independent pages is useful research, not a repeated invocation loop. */
internal fun toolRepeatKey(call: ProviderEvent.ToolCall): String {
    val name = call.name.substringAfterLast("__")
    val github = call.name == "github" || call.name.startsWith("github_") || call.name.substringBefore("__") in setOf("repository_graph", "repository_workspace") || name in setOf("get_file_contents", "read_code", "search_code", "search_repository", "read_ranges", "get_pull_request", "pull_request_read")
    val pageRead = name in setOf("read_recovery_context", "read_url", "firecrawl_scrape", "tavily_extract", "web_fetch_exa", "fetch", "read_doc", "browse", "browse_url", "web_fetch", "fetch_url", "read_webpage") || name.endsWith("_read_url") || name.endsWith("_web_fetch")
    if (!github && !pageRead) return call.name
    fun canonical(value: kotlinx.serialization.json.JsonElement): String = when (value) {
        is kotlinx.serialization.json.JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") { kotlinx.serialization.json.JsonPrimitive(it.key).toString() + ":" + canonical(it.value) }
        is kotlinx.serialization.json.JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
    val arguments = if (call.name.substringBefore("__") == "github_ci" &&
        (call.arguments["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content in setOf("run", "runs", "jobs", "job", "logs", "failure", "status", "workflows")
    ) {
        kotlinx.serialization.json.JsonObject(call.arguments - "response_detail")
    } else {
        call.arguments
    }
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(canonical(arguments).toByteArray())
    return call.name + (if (github) "|operation=" else ":") + hash.joinToString("") { "%02x".format(it) }
}

/** Accept only an unambiguous alias for an already-authorized capability; never expand grants. */
internal fun resolveGitHubReadAlias(call: ProviderEvent.ToolCall, availableNames: Set<String>): ProviderEvent.ToolCall {
    if (call.name in availableNames) return call
    val requested = when (call.name) {
        "github_pull_request", "github_pull_request__github_api" -> "github_pr"
        else -> call.name
    }
    if (requested !in setOf("github_context", "github_read", "github_search", "github_pr")) return call
    val canonical = availableNames.singleOrNull { it == requested || it.startsWith(requested + "__") } ?: return call
    return call.copy(name = canonical)
}
