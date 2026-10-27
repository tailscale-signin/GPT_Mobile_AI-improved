package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent

internal data class LocalContextPlan(
    val priorTurns: List<ConversationTurn>,
    val tools: List<AgentToolDefinition>,
    val toolResultBytes: Int,
    val estimatedPromptTokens: Long,
    val omittedTurns: Int,
    val omittedTools: Int,
    val toolDefinitionTokens: Long = 0,
    val outputReserveTokens: Int = 0,
    val toolSelectionReasons: Map<String, String> = emptyMap()
)

/**
 * A local engine's KV capacity covers the system prompt, chat template, tools,
 * tool replies AND generation. Output preferences are independent of this reservation.
 * Estimates are deliberately conservative; only the native tokenizer is authoritative.
 */
internal object LocalContextPlanner {
    fun plan(
        priorTurns: List<ConversationTurn>,
        currentUserPrompt: String,
        systemPrompt: String?,
        tools: List<AgentToolDefinition>,
        contextTokens: Int,
        outputLimit: Int?,
        imageCount: Int = 0,
        historyImageCount: (ConversationTurn) -> Int = { 0 },
        renderToolPrompt: ((List<AgentToolDefinition>) -> String)? = null,
        renderCapabilityPrompt: ((List<AgentToolDefinition>) -> String)? = null,
        explicitlyRequiredTools: Set<String> = emptySet()
    ): LocalContextPlan {
        require(contextTokens > 0)
        val currentRequiredNames = requiredToolNames(currentUserPrompt, tools) + explicitlyRequiredTools.intersect(tools.map { it.name }.toSet())
        // Follow only a contiguous, bounded chain of references to the previous
        // task. A new substantive request ends it; disabled tools are never added.
        val intentIndex = if (currentRequiredNames.isEmpty() && isToolFollowUp(currentUserPrompt)) {
            priorTurns.indices.reversed().take(6).firstOrNull { !isToolFollowUp(priorTurns[it].userMessage.effectiveContent()) }
        } else {
            null
        }
        val inheritedNames = intentIndex?.let { requiredToolNames(priorTurns[it].userMessage.effectiveContent(), tools) }.orEmpty()
        val requiredNames = currentRequiredNames + inheritedNames
        val relevantTools = relevantTools(currentUserPrompt, tools, requiredNames)
        val rankedTools = relevantTools.sortedWith(compareByDescending<AgentToolDefinition> { it.name in requiredNames }.thenByDescending { relevance(currentUserPrompt, it) }.thenBy { it.name })
        fun schemaCost(selected: List<AgentToolDefinition>): Long = (
            renderToolPrompt?.let { estimate(it(selected)) }
                ?: selected.sumOf { estimate(it.name) + estimate(it.description) + estimate(it.inputSchema.toString()) + 32 }
            ) +
            renderCapabilityPrompt?.let { estimate(it(selected)) }.orZero()
        // Tool definitions are charged below at their measured cost. Reserving another
        // tool block here double-counted the same tokens and caused local conversations
        // to silently receive zero tools on small-context models.
        val toolResultReserve = if (relevantTools.isEmpty()) 0 else maxOf(128, contextTokens / 4)
        val templateReserve = 256 // Matches the minimum uncertainty reserve at physical dispatch.
        // Reserve explicit result headroom for small local contexts; larger engines have
        // provider-level response budgeting and should not lose tools unnecessarily.
        var used = estimate(systemPrompt.orEmpty()) + estimate(currentUserPrompt) + imageCount.toLong() * IMAGE_TOKEN_ESTIMATE
        val requiredTools = rankedTools.filter { it.name in requiredNames }
        fun cost(turn: ConversationTurn): Long = estimate(turn.userMessage.effectiveContent()) +
            estimate(turn.assistantMessage?.effectiveContent().orEmpty()) + 32 +
            historyImageCount(turn).toLong() * IMAGE_TOKEN_ESTIMATE

        val safetyReserve = templateReserve + toolResultReserve + 1
        val requiredSchemaTokens = schemaCost(requiredTools)
        val baselineOutput = LocalOutputAllocation.reserve(contextTokens, used + requiredSchemaTokens, safetyReserve, outputLimit)
        val baselinePromptLimit = contextTokens.toLong() - baselineOutput - templateReserve - toolResultReserve
        var anchorTokens = 0L
        // Keep the history anchors that fit before optional schemas are considered.
        // Reserving a capability must borrow reply space rather than evict documents.
        val anchorIndices = (listOfNotNull(intentIndex.takeIf { inheritedNames.isNotEmpty() }) + listOf(0, priorTurns.lastIndex)).distinct()
        anchorIndices.filter { it in priorTurns.indices }.forEach { index ->
            val tokens = cost(priorTurns[index])
            if (used + requiredSchemaTokens + anchorTokens + tokens < baselinePromptLimit) anchorTokens += tokens
        }
        // An oversized output preference must not consume every optional schema slot.
        // Protect at least one usable capability before allocating the reply budget.
        val protectedTools = requiredTools.ifEmpty {
            rankedTools.take(1)
        }
        // A large reply preference must not reduce an otherwise usable catalog to
        // one tool. Keep history anchors and a modest reply before optional schemas;
        // the final reply allocation uses the remaining capacity. Small contexts
        // retain the conservative single-capability policy.
        val catalogTools = protectedTools.toMutableList()
        if (contextTokens >= 4096) {
            val minimumReply = minOf(baselineOutput, 512)
            rankedTools.filterNot { it in catalogTools }.forEach { tool ->
                if (used + anchorTokens + schemaCost(catalogTools + tool) + safetyReserve + minimumReply < contextTokens) {
                    catalogTools += tool
                }
            }
        }
        val outputReserve = LocalOutputAllocation.reserve(contextTokens, used + anchorTokens + schemaCost(catalogTools), safetyReserve, outputLimit)
        val promptLimit = contextTokens.toLong() - outputReserve - templateReserve - toolResultReserve
        require(used < promptLimit) {
            "This message and system instructions exceed this local model's $contextTokens-token context. " +
                "Shorten the message or system instructions, remove attachments, or choose a model with a larger context. " +
                "Max Output Tokens controls reply length; it does not increase model context."
        }

        val selectedTools = mutableListOf<AgentToolDefinition>()
        var toolTokens = schemaCost(emptyList())
        used += toolTokens
        fun admitTool(tool: AgentToolDefinition) {
            val tokens = schemaCost(selectedTools + tool) - toolTokens
            if (used + tokens < promptLimit) {
                used += tokens
                toolTokens += tokens
                selectedTools += tool
            }
        }
        protectedTools.forEach(::admitTool)
        val missingRequiredNames = requiredNames - selectedTools.map { it.name }.toSet()
        if (missingRequiredNames.isNotEmpty()) {
            val missingGitHubOperations = missingRequiredNames.mapNotNull { name ->
                dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(name)
            }.distinct()
            if (missingGitHubOperations.isNotEmpty()) {
                throw IllegalArgumentException(
                    "GITHUB_REQUIRED_TOOL_CONTEXT_BUDGET: GitHub operation schemas (${missingGitHubOperations.joinToString()}) do not fit this local model's $contextTokens-token context. Select a larger-context model or shorten the request, system instructions, and attached history. No GitHub write was dispatched."
                )
            }
            throw IllegalArgumentException("Required tool schemas do not fit this local context. Select fewer tools or a larger-context model; the app cannot complete this tool-dependent request without them.")
        }
        // Required capabilities take precedence, but optional schemas must not starve
        // the original goal or the most recent exchange (including extracted documents).
        val retainedIndices = mutableSetOf<Int>()
        fun admitTurn(index: Int) {
            if (index !in priorTurns.indices || index in retainedIndices) return
            val tokens = cost(priorTurns[index])
            if (used + tokens < promptLimit) {
                used += tokens
                retainedIndices += index
            }
        }
        anchorIndices.forEach(::admitTurn)
        require(inheritedNames.isEmpty() || intentIndex in retainedIndices) {
            "The earlier task needed for this follow-up does not fit the local context. Restate the request or choose a larger-context model."
        }
        rankedTools.filterNot { it in protectedTools }.forEach(::admitTool)
        for (index in priorTurns.indices.reversed()) {
            if (index in retainedIndices) continue
            if (used + cost(priorTurns[index]) >= promptLimit) break
            admitTurn(index)
        }
        val retained = retainedIndices.sorted().map { priorTurns[it] }
        return LocalContextPlan(
            retained,
            selectedTools,
            toolResultReserve * 2,
            used,
            priorTurns.size - retained.size,
            tools.size - selectedTools.size,
            toolTokens,
            outputReserve,
            tools.associate { tool ->
                tool.name to when {
                    tool !in relevantTools -> "not_needed_for_request"
                    tool !in selectedTools -> "context_budget"
                    tool.name in currentRequiredNames -> "required_current_request"
                    tool.name in inheritedNames -> "required_follow_up"
                    else -> "relevant_to_request"
                }
            }
        )
    }

    internal fun requiredToolNames(prompt: String, tools: List<AgentToolDefinition>): Set<String> {
        val lower = prompt.lowercase()
        val required = mutableSetOf<String>()
        tools.filter { tool -> Regex("(?<![a-z0-9_])" + Regex.escape(tool.name.lowercase()) + "(?![a-z0-9_])").containsMatchIn(lower) }.forEach { required += it.name }
        fun select(word: String) {
            val matching = tools.firstOrNull { it.name == word } ?: tools.firstOrNull {
                when (word) {
                    "web_search" -> it.name.endsWith("web_search")
                    "read_url" -> it.name.endsWith("read_url") || it.name.endsWith("fetch_url")
                    else -> it.name.endsWith("__$word")
                }
            }
            matching?.let { required += it.name }
        }
        val requestedGitHubOperations = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.taskTools(prompt)
        if (requestedGitHubOperations.isNotEmpty()) {
            tools.forEach { tool ->
                val operation = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(tool.name)
                if (operation in requestedGitHubOperations) required += tool.name
            }
        }
        if (Regex("nearby|nearest|closest|my location|current location").containsMatchIn(lower)) select("device_location")
        if ("airbnb" in lower) select("airbnb")
        if (Regex("read.*https?://|open.*https?://|crawl|read the page").containsMatchIn(lower)) select("read_url")
        if (Regex("search|research|look up|latest|browse|find online").containsMatchIn(lower)) select("web_search")
        // Research needs to inspect search hits as well as obtain snippets. Protect
        // the reader alongside search so a continuation can open its sources.
        if (Regex("search|research|browse|verify|sources|citations").containsMatchIn(lower)) {
            select("web_search")
            select("read_url")
        }
        if (Regex("read.*file|read.*document|open.*file|inspect.*file").containsMatchIn(lower)) select("read_file_slice")
        if (Regex("\\b(current (date|time)|today.?s date|what.?s the time|what time|tell me the time)\\b").containsMatchIn(lower)) select("current_date")
        return required
    }

    /** Relevance precedes capacity: spare context is not a reason to send schemas. */
    internal fun relevantTools(prompt: String, tools: List<AgentToolDefinition>, required: Set<String> = requiredToolNames(prompt, tools)): List<AgentToolDefinition> {
        if (dev.chungjungsoo.gptmobile.data.research.isResearchConversationOnly(prompt) && required.isEmpty()) return emptyList()
        val taskWords = Regex("[a-z0-9_]{3,}").findAll(prompt.lowercase()).map { it.value }
            .filterNot { it in setOf("the", "and", "for", "this", "that", "with", "please", "can", "could", "use", "what", "how", "write", "explain", "tell", "give", "about") }.toSet()
        val relevant = tools.filter { tool ->
            tool.name in required || taskWords.any { word -> tool.name.lowercase().split('_').any { it == word } }
        }
        val toolIntent = required.isNotEmpty() ||
            relevant.isNotEmpty() ||
            Regex("(?i)\\b(tools?|capabilities|available actions|what can you do|github|repository|repositories|calendar|email|remember|calculate|weather|prices?|current|today)\\b").containsMatchIn(prompt)
        val discovery = tools.firstOrNull { it.name == dev.chungjungsoo.gptmobile.data.agent.ProgressiveToolCatalog.NAME }
        val selected = relevant.filter { it.name in required } + relevant.filterNot { it.name in required || it == discovery }.take(3)
        val inventoryRequest = Regex("(?i)\\b(use (?:a |available |the )?tools?|available tools|what tools|what can you do|available actions|capabilities)\\b").containsMatchIn(prompt)
        val fallback = if (inventoryRequest && discovery == null && selected.isEmpty()) tools.take(3) else emptyList()
        return (selected + fallback + if (toolIntent && discovery != null) listOf(discovery) else emptyList()).distinctBy { it.name }
    }

    internal fun isToolFollowUp(prompt: String): Boolean = prompt.trim().lowercase().let { text ->
        text.length <= 160 &&
            Regex(
                "^(?:(?:please|can you)\\s+)?(?:tell me|answer it|what did you find|check again|try again|retry|continue|go on|do that|try it again|test tool[sa]?|test the tools|check the tools)[.!?\\s]*$"
            ).matches(text)
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun relevance(prompt: String, tool: AgentToolDefinition): Int {
        val words = Regex("[a-z]{3,}").findAll(prompt.lowercase()).map { it.value }.toSet()
        val descriptor = (tool.name + " " + tool.description).lowercase()
        return words.count { it in descriptor }
    }

    // JSON/tool templates and multilingual input cost more than English prose.
    private fun estimate(text: String): Long = (text.toByteArray(Charsets.UTF_8).size.toLong() + 1) / 2
    private const val IMAGE_TOKEN_ESTIMATE = 256
}
