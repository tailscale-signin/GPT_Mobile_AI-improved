package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Observed activity and explicitly selected-profile prose; no inference runs on the ticker. */
internal class GitHubChatProgress(task: String = "", private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val taskLabel = GitHubProgressPresentation.taskLabel(task)
    private val integrationCoverage = GitHubTaskCoverage.matches(task)
    private val previewSequence = GitHubPreviewSequence(GitHubProgressPresentation.taskFocus(task))
    private var activity = "Locating the relevant repository evidence"
    private var nextStep = "connect the requested behavior to its implementation and choose the first useful check"
    private var completed = 0
    private var failures = 0
    private var skipped = 0
    private var blocked = 0
    private var lastPreviewAt = now()
    private var lastSummaryAt = lastPreviewAt
    private var initial = true
    private var findingsPublished = false
    private var summary = GitHubProgressPresentation.introduction(task)
    private var lastPreviewKey = ""
    private val livePreviews = mutableMapOf<String, String>()
    private val recentSteps = ArrayDeque<String>()
    private data class Problem(val text: String, val requiredResolutions: Set<String>)
    private val activeProblems = linkedMapOf<String, Problem>()
    private data class MeaningfulTransition(val key: Triple<String, String, String>, val text: String)
    private val pendingTransitions = linkedMapOf<String, MeaningfulTransition>()
    private val activeTransitionKinds = linkedMapOf<String, String>()
    private val announcedTransitions = linkedSetOf<Triple<String, String, String>>()
    private var revision = 0L
    private var pendingSummary: ApiState.ActivitySummary? = null
    private var pendingSummaryRevision = -1L
    private var lastSummaryKey = ""
    private val publishedAuthoredSummaries = linkedSetOf<Pair<String, String>>()
    private val resourceStates = linkedMapOf<String, String>()
    private val sourceIdentities = linkedMapOf<String, String>()
    private var summaryEvidenceEpoch = 0L
    private val reportedFactKeys = linkedSetOf<String>()
    private var lastSummaryRevision = -1L

    // Preserve selected-profile findings until a new source-owned result or blocker arrives.
    private var selectedAuthoredSummary = false
    private val terminalCalls = mutableSetOf<Pair<String, String>>()
    private val activeCalls = mutableSetOf<Pair<String, String>>()
    private val blockedCalls = mutableSetOf<Pair<String, String>>()
    private val observedProgress = linkedSetOf<Triple<String, Int, String>>()
    private val resultObservations = linkedMapOf<String, ResultObservation>()
    private val savedResults = linkedSetOf<String>()
    private var responseStarted = false
    private var terminal = false

    fun updates(): Flow<ApiState> = flow {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            tick().forEach { emit(it) }
            kotlinx.coroutines.delay(250)
        }
    }

    /** A single monotonic clock owns all public preview and summary admission. */
    @Synchronized internal fun tick(): List<ApiState> = buildList {
        if (responseStarted || terminal) return@buildList
        val at = now()
        admitSummary(at, allowFallback = true)?.let(::add)
        val liveKey = GitHubProgressPresentation.liveKey(activity)
        if (liveKey != null) {
            if (admitCounter(liveKey, activity)) add(ApiState.ProgressCheckpoint(activity))
        } else if (activity.isNotBlank() && at - lastPreviewAt >= PREVIEW_INTERVAL_MS && GitHubProgressPresentation.lineKey(activity) != lastPreviewKey) {
            add(ApiState.ProgressCheckpoint(activity))
            lastPreviewKey = GitHubProgressPresentation.lineKey(activity)
            lastPreviewAt = at
        }
    }

    private fun admitSummary(at: Long, allowFallback: Boolean): ApiState.ActivitySummary? {
        if (pendingTransitions.isNotEmpty() && (initial || at - lastSummaryAt < summaryInterval(findingsPublished))) {
            return admitMeaningfulTransition()
        }
        if (!initial && at - lastSummaryAt < summaryInterval(findingsPublished)) return null
        if (!initial && pendingSummary == null && revision == lastSummaryRevision) return null
        val generated = pendingSummary.takeIf { it?.modelAuthored == true || pendingSummaryRevision == revision }
        pendingSummary = null
        // A timer or generic application summary cannot displace selected-profile findings.
        if (selectedAuthoredSummary && generated?.modelAuthored != true) return null
        val source = summarySnapshot()
        val factKeys = GitHubRichSummaryPresentation.factKeys(source)
        // A rotated bounded history, event replay, timer or changed "Next" label
        // cannot manufacture a new insight without a previously unreported fact.
        val allFactsReported = factKeys.isNotEmpty() && factKeys.all(reportedFactKeys::contains)
        val noNewFacts = generated == null && factKeys.isEmpty()
        // New analysis can come from already-read source. Receipt deduplication
        // governs factual fallbacks; authored explanations have their own history.
        if (!initial && generated?.modelAuthored != true && (allFactsReported || noNewFacts)) {
            lastSummaryRevision = revision
            return null
        }
        val candidate = generated ?: if (allowFallback && (initial || revision != lastSummaryRevision)) {
            ApiState.ActivitySummary(if (initial) summary else GitHubRichSummaryPresentation.render(source, reportedFactKeys))
        } else {
            return null
        }
        lastSummaryRevision = revision
        val key = GitHubProgressPresentation.summaryKey(candidate.text)
        val publication = key to summaryEvidenceKey(factKeys)
        if (key.isBlank() || key == lastSummaryKey || candidate.modelAuthored && publication in publishedAuthoredSummaries) return null
        val introduction = initial && generated == null
        summary = candidate.text
        lastSummaryKey = key
        lastSummaryAt = at
        initial = false
        selectedAuthoredSummary = candidate.modelAuthored
        if (candidate.modelAuthored) {
            publishedAuthoredSummaries.add(publication)
            while (publishedAuthoredSummaries.size > 256) publishedAuthoredSummaries.remove(publishedAuthoredSummaries.first())
        }
        acknowledgeTransitions()
        // Introduction is only a promise, not a completed observation.
        if (!introduction) {
            findingsPublished = true
            factKeys.forEach(reportedFactKeys::add)
            while (reportedFactKeys.size > 256) reportedFactKeys.remove(reportedFactKeys.first())
        }
        return candidate
    }

    /** Use saved call identity and typed state, never a timer, display text or counter change. */
    private fun queueTransition(call: Pair<String, String>?, scope: String, kind: String, text: String) {
        if (call == null) return
        val key = Triple(call.first, call.second, kind)
        if (key in announcedTransitions || kind != "RECOVERY" && activeTransitionKinds[scope] == kind) return
        summaryEvidenceEpoch++
        if (kind == "RECOVERY") activeTransitionKinds.remove(scope) else activeTransitionKinds[scope] = kind
        while (activeTransitionKinds.size > 64) activeTransitionKinds.remove(activeTransitionKinds.keys.first())
        pendingTransitions[scope] = MeaningfulTransition(key, text.take(320))
        while (pendingTransitions.size > 64) pendingTransitions.remove(pendingTransitions.keys.first())
    }

    private fun acknowledgeTransitions() {
        pendingTransitions.values.forEach { announcedTransitions.add(it.key) }
        pendingTransitions.clear()
        while (announcedTransitions.size > 4096) announcedTransitions.remove(announcedTransitions.first())
    }

    private fun admitMeaningfulTransition(): ApiState.ActivitySummary? {
        val transitions = pendingTransitions.values.toList()
        if (transitions.isEmpty()) return null
        val latest = transitions.takeLast(4)
        val text = latest.joinToString("\n") { if (latest.size == 1) it.text else "- ${it.text}" } +
            if (transitions.size > latest.size) "\nAdditional blocker and recovery observations remain in activity details." else ""
        acknowledgeTransitions()
        summary = text
        lastSummaryKey = GitHubProgressPresentation.summaryKey(text)
        initial = false
        selectedAuthoredSummary = false
        transitions.map { GitHubProgressPresentation.lineKey(it.text) }.forEach(reportedFactKeys::add)
        while (reportedFactKeys.size > 256) reportedFactKeys.remove(reportedFactKeys.first())
        // Immediate transitions do not postpone or replace the 45s/135s routine
        // cadence. Their fact keys still prevent duplicate routine narration.
        return ApiState.ActivitySummary(text, modelAuthored = false)
    }

    private fun admitCounter(liveKey: String, text: String): Boolean {
        val key = GitHubProgressPresentation.lineKey(text)
        if (livePreviews[liveKey] == key && lastPreviewKey == key) return false
        livePreviews[liveKey] = key
        lastPreviewKey = key
        return true
    }

    @Synchronized fun forward(event: ApiState): ApiState? {
        observe(event)
        if ((responseStarted || terminal) && (event is ApiState.ProgressCheckpoint || event is ApiState.ActivitySummary)) return null
        return when (event) {
            is ApiState.ProgressCheckpoint -> event.takeIf {
                !event.modelAuthored && GitHubProgressPresentation.liveKey(event.text)?.let { admitCounter(it, event.text) } == true
            }
            is ApiState.ActivitySummary -> admitSummary(now(), allowFallback = false)
            else -> event
        }
    }

    /** Consume existing bounded facts; never parse or hash a raw tool body on the progress path. */
    @Synchronized fun observeResult(call: ProviderEvent.ToolCall, result: AgentToolResult): String? {
        if (terminal || result.isError || call.callId != result.callId || "view_cursor" in call.arguments) return null
        val saved = result.sharedResult || result.executionReceipt?.get("read_source") == JsonPrimitive("RETAINED")
        if (saved) {
            savedResults += call.callId
            while (savedResults.size > 256) savedResults.remove(savedResults.first())
        }
        val receiptFacts = result.executionReceipt?.get("semantic_summary") as? JsonObject
        val facts = receiptFacts
            ?: listOf(result.retainedContent, result.content).firstNotNullOfOrNull { content ->
                (((content as? ToolResultContent.Json)?.value as? JsonObject)?.get("semantic_summary") as? JsonObject)
            } ?: return null
        if (facts["schema"] != JsonPrimitive("github.semantic-summary.v1") ||
            (facts["operation"] as? JsonPrimitive)?.content?.githubOperationFamily() != call.name.githubOperationFamily()
        ) {
            return null
        }
        val observation = semanticObservation(call.name, call.arguments, facts, saved)?.copy(
            sourceIdentity = if (!saved && receiptFacts === facts) capturedSourceIdentity(call, result.executionReceipt, facts) else null
        ) ?: return null
        resultObservations[call.callId] = observation
        while (resultObservations.size > 256) resultObservations.remove(resultObservations.keys.first())
        return observation.preview
    }

    @Synchronized fun observe(event: ApiState) {
        if (terminal) return
        if (responseStarted && (event is ApiState.ProgressCheckpoint || event is ApiState.ActivitySummary)) return
        when (event) {
            is ApiState.GatewayProgressChanged -> {
                val progress = event.progress
                val scope = progress.jobId.orEmpty()
                val replayKey = progress.sequence?.let { Triple(scope, it, progress.event.orEmpty()) }
                if (replayKey != null && !observedProgress.add(replayKey)) return
                if (observedProgress.size > 4_096) observedProgress.remove(observedProgress.first())
                val terminalKey = progress.toolCallId?.takeIf(String::isNotBlank)?.let { scope to it }
                val name = progress.toolName.orEmpty()
                val operation = operation(name, progress.toolArgs)
                val problemScope = problemScope(scope, name, progress.toolArgs)
                val eventName = if (progress.event in setOf("tool_finished", "tool_completed")) {
                    when (progress.status?.lowercase(java.util.Locale.ROOT)) {
                        "failed", "error" -> "tool_failed"
                        "blocked", "awaiting_approval", "waiting_for_approval" -> "tool_blocked"
                        "skipped" -> "tool_skipped"
                        else -> progress.event
                    }
                } else {
                    progress.event
                }
                when (eventName) {
                    "tool_started" -> {
                        if (terminalKey in terminalCalls) return
                        responseStarted = false
                        terminalKey?.let(blockedCalls::remove)
                        terminalKey?.takeUnless { it in terminalCalls }?.let(activeCalls::add)
                        previewSequence.offer(previewEvent(name, progress.toolArgs, GitHubPreviewPhase.STARTED))
                            ?.let { activity = it }
                        nextStep = nextFor(operation)
                        // Starts belong in the live preview. A finding needs an outcome.
                    }
                    "tool_failed" -> {
                        if (terminalKey != null && !terminalCalls.add(terminalKey)) return
                        resultObservations.remove(progress.toolCallId)
                        terminalKey?.let(activeCalls::remove)
                        terminalKey?.let(blockedCalls::remove)
                        failures++
                        pendingSummary = null
                        selectedAuthoredSummary = false
                        activity = milestone(name, true, false, progress.toolArgs)
                        nextStep = "inspect the ${operation.subject} error and identify whether a corrected request or connection recovery is needed"
                        val finding = observation(name, true, false, progress.toolArgs)
                        rememberStep(finding, problemScope = problemScope)
                        queueTransition(terminalKey, problemScope, "BLOCKER", finding)
                    }
                    "tool_blocked" -> {
                        if (terminalKey in terminalCalls || terminalKey != null && !blockedCalls.add(terminalKey)) return
                        resultObservations.remove(progress.toolCallId)
                        terminalKey?.let(activeCalls::remove)
                        blocked++
                        pendingSummary = null
                        selectedAuthoredSummary = false
                        activity = "${operation.subject.replaceFirstChar(Char::uppercase)} blocked${sourceFocus(progress.toolArgs).let { if (it.isBlank()) "" else " · $it" }}"
                        nextStep = "inspect the recorded blocker and identify the required approval, setup or correction before continuing"
                        val decision = progress.status?.lowercase(java.util.Locale.ROOT) in setOf("awaiting_approval", "waiting_for_approval")
                        val finding = if (decision) {
                            "The ${operation.subject}${focusClause(progress.toolArgs)} needs approval before continuing; the requested action has not been verified."
                        } else {
                            "The ${operation.subject}${focusClause(progress.toolArgs)} is blocked; the requested action has not been verified."
                        }
                        rememberStep(finding, problemScope = problemScope)
                        queueTransition(terminalKey, problemScope, if (decision) "DECISION" else "BLOCKER", finding)
                    }
                    "tool_completed", "tool_finished", "tool_skipped" -> {
                        if (terminalKey != null && !terminalCalls.add(terminalKey)) return
                        terminalKey?.let(activeCalls::remove)
                        terminalKey?.let(blockedCalls::remove)
                        if (eventName == "tool_skipped") {
                            resultObservations.remove(progress.toolCallId)
                            skipped++
                            pendingSummary = null
                            selectedAuthoredSummary = false
                            activity = "${operation.subject.replaceFirstChar(Char::uppercase)} skipped${sourceFocus(progress.toolArgs).let { if (it.isBlank()) "" else " · $it" }}"
                            nextStep = "check why the ${operation.subject} was skipped before treating it as validation evidence"
                            rememberStep("The ${operation.subject}${focusClause(progress.toolArgs)} was skipped; it did not provide an executed validation result.", problemScope = problemScope)
                            return
                        }
                        completed++
                        val observed = resultObservations.remove(progress.toolCallId)
                        val saved = savedResults.remove(progress.toolCallId)
                        val freshResult = observed?.saved != true && !saved
                        if (freshResult && observed != null) {
                            observed.sourceIdentity?.let { (source, identity) ->
                                if (sourceIdentities.put(source, identity) != identity) summaryEvidenceEpoch++
                                while (sourceIdentities.size > 256) sourceIdentities.remove(sourceIdentities.keys.first())
                            }
                            observed.resourceStates.forEach { (resource, text) ->
                                resourceStates.put(resource, text)?.takeIf { it != text }?.let { previous ->
                                    recentSteps.removeAll { it == previous }
                                    reportedFactKeys.remove(GitHubProgressPresentation.lineKey(text))
                                    summaryEvidenceEpoch++
                                }
                            }
                            while (resourceStates.size > 256) resourceStates.remove(resourceStates.keys.first())
                        }
                        if (observed?.problem != true && freshResult) {
                            resolveProblem(problemScope, observed?.resolvedConditions.orEmpty())?.let {
                                selectedAuthoredSummary = false
                                pendingSummary = null
                                val finding = "The ${operation.subject}${focusClause(progress.toolArgs)} is available again. The remaining task still needs verification."
                                rememberStep(finding)
                                queueTransition(terminalKey, problemScope, "RECOVERY", finding)
                            }
                        }
                        val sourceRead = name.githubOperationFamily() == "github_read" && sourceFocus(progress.toolArgs).isNotBlank()
                        if (observed != null || sourceRead) selectedAuthoredSummary = false
                        val nextPreview = observed?.preview ?: previewSequence.offer(
                            previewEvent(name, progress.toolArgs, GitHubPreviewPhase.RESULT)
                        )
                        nextPreview?.let { activity = it }
                        nextStep = nextFor(operation)
                        if (observed?.problem == true) {
                            pendingSummary = null
                            if (freshResult) queueTransition(terminalKey, problemScope, "BLOCKER", observed.summary)
                        }
                        if (observed != null || sourceRead || operation.mutation) {
                            // Saved facts remain visible as history but cannot replace a current
                            // blocker or change the fresh evidence required to resolve it.
                            val steps = observed?.summaries ?: listOf(observation(name, false, false, progress.toolArgs))
                            steps.forEach { step ->
                                rememberStep(step, problemScope = problemScope.takeIf { observed?.problem == true && freshResult }, requiredResolutions = observed?.unresolvedConditions.orEmpty(), resolvedConditions = observed?.resolvedConditions.orEmpty())
                            }
                        } else {
                            revision++
                        }
                    }
                }
                val counter = listOfNotNull(progress.message, progress.ui?.title).firstOrNull { GitHubProgressPresentation.liveKey(it) != null }
                if (counter != null) activity = counter
            }
            is ApiState.ProgressCheckpoint -> if (!event.modelAuthored && event.text.isNotBlank() && !GitHubProgressPresentation.isGenericProgress(event.text)) {
                if (activity != event.text && GitHubProgressPresentation.liveKey(event.text) == null) revision++
                activity = event.text
            }
            is ApiState.ActivitySummary -> if (!GitHubProgressPresentation.isEmptySummary(event.text) &&
                (
                    event.modelAuthored &&
                        authoredSummaryKey(event.text) !in publishedAuthoredSummaries ||
                        !event.modelAuthored &&
                        !selectedAuthoredSummary &&
                        pendingSummary?.modelAuthored != true
                    )
            ) {
                pendingSummary = event
                pendingSummaryRevision = revision
            }
            is ApiState.Success -> {
                if (event.textChunk.isNotBlank()) {
                    responseStarted = true
                    pendingSummary = null
                    pendingTransitions.clear()
                    activity = ""
                }
            }
            ApiState.GitHubRoundStarted -> {
                responseStarted = false
                activity = ""
            }
            is ApiState.Error, ApiState.Done -> {
                terminal = true
                pendingSummary = null
                activity = ""
                resultObservations.clear()
                savedResults.clear()
                pendingTransitions.clear()
            }
            else -> Unit
        }
    }

    @Synchronized fun heartbeat(): String = summary

    private fun authoredSummaryKey(text: String): Pair<String, String> =
        GitHubProgressPresentation.summaryKey(text) to summaryEvidenceKey(GitHubRichSummaryPresentation.factKeys(summarySnapshot()))

    private fun summaryEvidenceKey(facts: List<String>): String = "$summaryEvidenceEpoch:" + facts.sorted().joinToString("\u001f")

    /** Only bounded public facts enter the optional prose request, never raw tool bodies or reasoning. */
    @Synchronized fun summarySnapshot(): GitHubProgressSnapshot = GitHubProgressSnapshot(
        revision,
        taskLabel,
        completed,
        failures,
        GitHubProgressPresentation.taskLabel(activity),
        recentSteps.takeLast(6).let { steps -> activeProblems.values.toList().takeLast(4).map { it.text }.filterNot(steps::contains) + steps },
        GitHubProgressPresentation.taskLabel(nextStep),
        pending = activeCalls.size,
        skipped = skipped,
        blocked = blocked
    )

    private fun rememberStep(step: String, problemScope: String? = null, requiredResolutions: Set<String> = emptySet(), resolvedConditions: Set<String> = emptySet()) {
        if (problemScope != null) {
            val unresolved = activeProblems[problemScope]?.requiredResolutions.orEmpty() - resolvedConditions + requiredResolutions
            activeProblems.put(problemScope, Problem(step.take(320), unresolved))?.let { prior -> recentSteps.remove(prior.text) }
            while (activeProblems.size > 64) activeProblems.remove(activeProblems.keys.first())
        }
        if (recentSteps.lastOrNull() != step) recentSteps.addLast(step.take(320))
        while (recentSteps.size > 8) recentSteps.removeFirst()
        revision++
    }

    private fun resolveProblem(scope: String, resolvedConditions: Set<String>): Problem? {
        val problem = activeProblems[scope] ?: return null
        // A successful HTTP result alone cannot clear a failed check or unknown policy.
        if (!resolvedConditions.containsAll(problem.requiredResolutions)) return null
        activeProblems.remove(scope)
        recentSteps.remove(problem.text)
        return problem
    }

    private fun nextFor(operation: Operation): String = if (integrationCoverage) {
        "compare this operation with the integration criteria and identify the next admissible check"
    } else {
        operation.next
    }

    companion object {
        const val PREVIEW_INTERVAL_MS = 3_000L
        private const val FIRST_SUMMARY_INTERVAL_MS = 45_000L
        private const val FOLLOW_UP_SUMMARY_INTERVAL_MS = FIRST_SUMMARY_INTERVAL_MS * 3

        internal fun summaryInterval(hasPublishedFindings: Boolean): Long =
            if (hasPublishedFindings) FOLLOW_UP_SUMMARY_INTERVAL_MS else FIRST_SUMMARY_INTERVAL_MS
        private data class Operation(val preview: String, val subject: String, val next: String, val mutation: Boolean = false)
        private data class ResultObservation(
            val preview: String,
            val summary: String,
            val problem: Boolean = false,
            val saved: Boolean = false,
            val unresolvedConditions: Set<String> = emptySet(),
            val resolvedConditions: Set<String> = emptySet(),
            val summaries: List<String> = listOf(summary),
            val resourceStates: Map<String, String> = emptyMap(),
            val sourceIdentity: Pair<String, String>? = null
        )

        /** A locally captured identity changes preview deduplication, never source or completion authority. */
        private fun capturedSourceIdentity(call: ProviderEvent.ToolCall, receipt: JsonObject?, facts: JsonObject): Pair<String, String>? {
            if (call.name.githubOperationFamily() != "github_read" ||
                call.name.length > 256 ||
                receipt == null ||
                facts["hash_scope"] != JsonPrimitive("IMMUTABLE_SOURCE_RESULT") ||
                "source_read_verified" in facts &&
                facts["source_read_verified"] != JsonPrimitive(true) ||
                "read_source" in receipt &&
                receipt["read_source"] !in setOf(JsonPrimitive("LIVE"), JsonPrimitive("MIXED")) ||
                "parent_result_successful" in receipt &&
                receipt["parent_result_successful"] != JsonPrimitive(true) ||
                "requires_reconciliation" in receipt &&
                receipt["requires_reconciliation"] != JsonPrimitive(false) ||
                receipt["saved_view_source_call_id"] != JsonPrimitive(call.callId)
            ) {
                return null
            }
            fun text(source: JsonObject, key: String, limit: Int = 256): String? =
                (source[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= limit }
            val commit = text(facts, "commit_sha")?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: return null
            val digest = text(facts, "source_result_sha256")?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) } ?: return null
            if (!digest.equals(text(receipt, "saved_view_source_sha256"), ignoreCase = true)) return null
            val range = facts["source_range"] as? JsonObject ?: return null
            // The producer truncates paths at 256 characters. An exact identity cannot use that ambiguous boundary.
            val path = text(range, "path", 255) ?: return null
            val start = (range["start"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 } ?: return null
            val end = (range["end"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= start } ?: return null
            fun repository(fields: JsonObject): Pair<String?, String?>? {
                if (listOf("owner", "repo").any { it in fields && text(fields, it, 512) == null }) return null
                return runCatching { normalizeGitHubRepository(text(fields, "owner", 512), text(fields, "repo", 512)) }.getOrNull()
            }
            val requested = repository(call.arguments) ?: return null
            val context = receipt["resource_context"] as? JsonObject
            if ("resource_context" in receipt && context == null) return null
            val captured = if (context == null) requested else repository(context) ?: return null
            val owner = captured.first ?: return null
            val repo = captured.second ?: return null
            if (requested.first?.equals(owner, ignoreCase = true) == false || requested.second?.equals(repo, ignoreCase = true) == false) return null
            if ("path" in call.arguments && text(call.arguments, "path") != path) return null
            if ("commit_sha" in call.arguments && !commit.equals(text(call.arguments, "commit_sha"), ignoreCase = true)) return null
            val source = JsonArray(
                listOf(
                    JsonPrimitive(call.name),
                    JsonPrimitive(owner.lowercase(java.util.Locale.ROOT)),
                    JsonPrimitive(repo.lowercase(java.util.Locale.ROOT)),
                    JsonPrimitive(path),
                    JsonPrimitive(start),
                    JsonPrimitive(end)
                )
            ).toString()
            return source to "${commit.lowercase(java.util.Locale.ROOT)}:${digest.lowercase(java.util.Locale.ROOT)}"
        }

        private fun problemScope(job: String, name: String, args: JsonObject?): String {
            val canonical = GitHubActionIdentity.canonicalArguments(name, args ?: JsonObject(emptyMap()))
            val selectors = setOf("action", "owner", "repo", "ref", "branch", "commit_sha", "path", "ranges", "query", "pull_number", "pr_number", "issue_number", "run_id", "job_id", "tag_name", "release_id", "view_cursor")
            // Keep full source/revision identity; display labels are deliberately shortened
            // and can collide. Pagination/invalid optional fields do not change the resource.
            return JsonArray(listOf(JsonPrimitive(job), JsonPrimitive(name.githubOperationFamily()), JsonPrimitive(name.substringAfter("__", "")), JsonObject(canonical.filterKeys { it in selectors }.toSortedMap()))).toString()
        }

        private fun semanticObservation(name: String, args: JsonObject, facts: JsonObject, saved: Boolean): ResultObservation? {
            if (operation(name, args).mutation) return null
            fun count(source: JsonObject, key: String) = (source[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
            fun quantity(value: Int, noun: String) = "$value $noun" + if (value == 1) "" else "s"
            fun savedSummary(summary: String) = if (saved) "From saved results: ${summary.replaceFirstChar(Char::lowercase)} Current state still needs verification." else summary
            fun observation(preview: String, summary: String, problem: Boolean = false, unresolvedConditions: Set<String> = emptySet(), resolvedConditions: Set<String> = emptySet(), related: List<String> = emptyList()): ResultObservation = ResultObservation(
                (if (saved) "Saved results: " else "") + preview + sourceFocus(args).let { if (it.isBlank()) "" else " · $it" },
                savedSummary(summary),
                problem,
                saved,
                unresolvedConditions,
                resolvedConditions,
                (listOf(summary) + related).map(::savedSummary)
            )
            val range = facts["source_range"] as? JsonObject
            if (name.githubOperationFamily() == "github_read" && range != null) {
                val path = (range["path"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                val start = count(range, "start")
                val end = count(range, "end")
                if (path != null && start != null && start > 0 && end != null && end >= start) {
                    val file = GitHubProgressPresentation.taskLabel(path)
                    val location = if (start == end) "line $start" else "lines $start–$end"
                    return if (range["complete"] == JsonPrimitive(true)) {
                        observation("Read $location", "I have $location of $file.")
                    } else {
                        observation("Retrieved a source excerpt", "I have part of the requested range in $file.")
                    }
                }
            }
            val counts = facts["check_counts"] as? JsonObject
            if (counts != null) {
                val successful = count(counts, "successful")
                val skipped = count(counts, "skipped")
                val running = count(counts, "in_progress")
                val failed = count(counts, "failed")
                val other = count(counts, "other_completed")
                if (successful != null && skipped != null && running != null && failed != null && other != null) {
                    val values = "$successful successful, $skipped skipped, $running in progress, $failed failed" + if (other > 0) ", $other other completed" else ""
                    val coverage = if (facts["check_inventory_complete"] == JsonPrimitive(true)) "complete captured list" else "incomplete captured list"
                    val unknownPolicy = facts["required_checks_state"] == JsonPrimitive("UNKNOWN")
                    val policy = if (unknownPolicy) " Required-check policy is unavailable; merge readiness remains unknown." else ""
                    val unresolved = buildSet {
                        if (failed > 0) add("FAILED_CHECKS")
                        if (unknownPolicy) add("REQUIRED_POLICY")
                    }
                    val resolved = buildSet {
                        if (failed == 0 && facts["check_inventory_complete"] == JsonPrimitive(true)) add("FAILED_CHECKS")
                        if (facts["required_checks_state"] in setOf(JsonPrimitive("VERIFIED_NONE"), JsonPrimitive("VERIFIED_PRESENT"))) add("REQUIRED_POLICY")
                    }
                    return observation("Checks observed: $values", "Recorded checks${focusClause(args)}: $values ($coverage). These are existing results, not a new validation run.$policy", unresolved.isNotEmpty(), unresolved, resolved)
                }
            }
            val resources = (facts["resource_observations"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            val resourceScope = JsonObject(args.filterKeys { it == "owner" || it == "repo" }.toSortedMap()).toString()
            val resourceSummaries = resources.take(4).mapNotNull { resource ->
                val number = count(resource, "number")?.takeIf { it > 0 } ?: return@mapNotNull null
                val kind = when (resource["resource"]) {
                    JsonPrimitive("issue") -> "Issue"
                    JsonPrimitive("pull_request") -> "PR"
                    else -> return@mapNotNull null
                }
                val title = (resource["title"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let(GitHubProgressPresentation::taskLabel)
                val state = (resource["state"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("open", "closed", "merged") }
                if (title == null && state == null) return@mapNotNull null
                "$resourceScope:$kind #$number" to "$kind #$number${state?.let { " is $it" }.orEmpty()}${title?.let { ": “$it”" }.orEmpty()}."
            }.toMap()
            if (resourceSummaries.isNotEmpty()) {
                val summaries = resourceSummaries.values.toList()
                return observation(summaries.first(), summaries.first(), related = summaries.drop(1)).copy(resourceStates = resourceSummaries)
            }
            if (name.githubOperationFamily() == "github_search") {
                val returned = count(facts, "returned")
                val scanned = count(facts, "scanned_files")
                if (returned != null && scanned != null) {
                    val coverage = if (facts["search_complete"] == JsonPrimitive(true)) "The recorded search coverage is complete." else "Search coverage is incomplete; unscanned files remain unresolved."
                    return observation("${quantity(returned, "matching file")} in the search page", "This search page returned ${quantity(returned, "matching file")} after inspecting ${quantity(scanned, "file")}. $coverage")
                }
            }
            val issues = count(facts, "open_issue_count")
            val pulls = count(facts, "open_pull_request_count")
            if (issues != null || pulls != null) {
                val values = listOfNotNull(issues?.let { quantity(it, "open issue") }, pulls?.let { quantity(it, "open pull request") }).joinToString(" and ")
                return observation("Repository status: $values", "Repository status reports $values. Counts do not establish code or test health.")
            }
            count(facts, "workflow_definitions_observed")?.let { count ->
                val coverage = if (facts["workflow_inventory_complete"] == JsonPrimitive(true)) "complete captured inventory" else "incomplete captured inventory"
                return observation("${quantity(count, "workflow definition")} found", "The workflow inventory contains ${quantity(count, "definition")} ($coverage). Listing definitions does not execute validation.")
            }
            if (facts["required_checks_state"] == JsonPrimitive("UNKNOWN")) {
                return observation("Required-check policy is unavailable", "Required-check policy${focusClause(args)} is unavailable; merge readiness cannot be established.", problem = true, unresolvedConditions = setOf("REQUIRED_POLICY"))
            }
            if (facts["required_checks_state"] in setOf(JsonPrimitive("VERIFIED_NONE"), JsonPrimitive("VERIFIED_PRESENT"))) {
                return observation("Required-check policy is available", "Required-check policy${focusClause(args)} is available for the next readiness assessment.", resolvedConditions = setOf("REQUIRED_POLICY"))
            }
            count(facts, "changed_files_observed")?.let { count ->
                return observation("${quantity(count, "changed file")} observed", "The comparison${focusClause(args)} contains ${quantity(count, "observed changed file")}; source review remains unverified.")
            }
            count(facts, "open_pull_requests_observed")?.let { count ->
                val scope = if (facts["open_pull_request_inventory_complete"] == JsonPrimitive(true)) "the full list" else "this page"
                return observation("${quantity(count, "open pull request")} on $scope", "I found ${quantity(count, "open pull request")} in $scope.")
            }
            val revision = (facts["commit_sha"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }
            val branch = (facts["branch"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            if (name.githubOperationFamily() == "github_context" && revision != null && branch != null) {
                val target = GitHubProgressPresentation.taskLabel(branch)
                return observation("Located $target at ${revision.take(8)}", "The $target branch points to ${revision.take(8)}.")
            }
            return null
        }

        /** Only these bounded request identifiers enter public progress; result bodies stay private. */
        private fun sourceFocus(args: JsonObject?): String {
            fun value(key: String) = (args?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let(GitHubProgressPresentation::taskLabel)
            val ranges = args?.get("ranges") as? JsonArray
            val path = ((ranges?.firstOrNull() as? JsonObject)?.get("path") as? JsonPrimitive)?.contentOrNull ?: value("path")
            return when {
                !path.isNullOrBlank() -> GitHubProgressPresentation.taskLabel(path.replace('\\', '/').split('/').takeLast(2).joinToString("/"))
                value("job_id") != null -> "Job ${value("job_id")}"
                value("run_id") != null -> "Run ${value("run_id")}"
                value("pull_number") != null -> "PR #${value("pull_number")}"
                value("pr_number") != null -> "PR #${value("pr_number")}"
                value("issue_number") != null -> "Issue #${value("issue_number")}"
                value("branch") != null -> "branch ${value("branch")}"
                value("tag_name") != null -> "tag ${value("tag_name")}"
                value("release_id") != null -> "release ${value("release_id")}"
                value("commit_sha")?.matches(Regex("[a-fA-F0-9]{40}")) == true -> "commit ${value("commit_sha")?.take(8)}"
                (args?.get("page") as? JsonPrimitive)?.intOrNull?.let { it > 1 } == true -> "page ${value("page")}"
                else -> ""
            }
        }

        private fun focusClause(args: JsonObject?) = sourceFocus(args).let { if (it.isBlank()) "" else " for $it" }

        private fun operation(name: String, args: JsonObject?): Operation {
            val action = (GitHubActionIdentity.canonicalArguments(name, args ?: JsonObject(emptyMap()))["action"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            return when (name.githubOperationFamily()) {
                "github_read" -> Operation("Reading source", "source read", "compare the implementation with the reported behavior and identify the next relevant source")
                "github_search" -> Operation("Searching repository", "repository search", "open the matching implementation and check how it connects to the requested behavior")
                "github_context" -> when (action) {
                    "checks" -> Operation("Checking build and test results", "check results", "separate failures from pending or skipped checks before deciding what needs repair")
                    "changes" -> Operation("Comparing branch changes", "branch comparison", "read the affected source and examine how the changes alter the requested behavior")
                    "resolve" -> Operation("Resolving branch revision", "reference lookup", "use the observed revision to keep subsequent source reads and checks consistent")
                    "branch" -> Operation("Reading the branch head", "branch lookup", "match the branch head to the comparison")
                    "repository" -> Operation("Reading repository details", "repository lookup", "check the selected repository and default branch")
                    "commit" -> Operation("Inspecting the commit", "commit inspection", "follow the changes into the affected files")
                    "branches" -> Operation("Inspecting branches", "branch inventory", "identify the branch containing the requested work and establish its comparison base")
                    "index" -> Operation("Inspecting source coverage", "source index", "identify which relevant files need a direct read before drawing conclusions")
                    "efficiency" -> Operation("Reviewing saved reads", "saved-read inspection", "reuse available evidence and identify the remaining source reads that can advance the task")
                    "capabilities" -> Operation("Checking available tools", "authorized tool inventory", "match the available operations to the request and identify any required setup")
                    "recovery" -> Operation("Reconciling saved work", "saved-work inspection", "match saved results to the current state before resuming unfinished operations")
                    "commits" -> Operation("Reading recent changes", "commit history", "connect the recent changes to the affected implementation and choose a relevant comparison")
                    else -> Operation("Checking repository status", "repository status", "identify the relevant branch, outstanding work and available evidence for the request")
                }
                "github_ci", "github_watch_checks" -> when (action) {
                    "logs" -> Operation("Reading job log", "job log read", "identify the relevant diagnostic in the recorded output and compare it with the affected source")
                    "jobs" -> Operation("Inspecting workflow jobs", "workflow job inventory", "distinguish failed, pending and skipped jobs before selecting the relevant log")
                    "workflows" -> Operation("Listing workflow definitions", "workflow definition inventory", "follow unread pages when inventory is needed, then inspect actual runs and check results")
                    "definitions" -> Operation("Reading workflow definitions", "workflow source inspection", "identify the workflow configuration without treating it as executed validation")
                    "runs" -> Operation("Locating workflow run", "workflow run lookup", "match the run to the selected revision before interpreting its jobs and results")
                    "run", "status" -> Operation("Reading workflow run status", "workflow run", "match this attempt to its revision and jobs")
                    "artifacts" -> Operation("Looking up build artifacts", "artifact lookup", "match the artifacts to the workflow run")
                    "dispatch", "rerun", "cancel" -> Operation("Checking workflow request", "workflow request", "verify whether the requested workflow action was accepted and observe its resulting state", true)
                    else -> Operation("Inspecting build and test results", "build and test inspection", "separate observed failures from pending or skipped checks and identify the next relevant diagnostic")
                }
                "github_pr" -> when (action) {
                    "reviews" -> Operation("Reading PR reviews", "pull request reviews", "identify unresolved review requirements and compare them with the current changes")
                    "comments" -> Operation("Reading PR discussion", "pull request discussion", "connect outstanding questions to the affected source and existing validation evidence")
                    "list" -> Operation("Checking pull requests", "pull request inventory", "identify the pull request associated with the requested branch and inspect its changes")
                    "merge", "create", "update", "close" -> Operation("Checking PR request", "pull request action", "verify the requested action against the current pull request and its required checks", true)
                    else -> Operation("Inspecting PR changes", "pull request inspection", "compare the proposed changes with the requested behavior and outstanding review requirements")
                }
                "github_issue" -> Operation("Reading issue details", "issue inspection", "connect the reported symptoms and remaining requirements to the relevant implementation")
                "github_stage_patch" -> Operation("Preparing patch", "patch request", "review the proposed changes and verify their approval and publication state before claiming they were applied", true)
                "github_commit", "github_write" -> Operation("Checking change request", "change request", "verify whether the proposed change was accepted and then inspect the resulting branch state", true)
                "github_release" -> when (action) {
                    "tags", "tag" -> Operation("Checking release tags", "tag lookup", "match the tag to its commit")
                    "list" -> Operation("Finding release records", "release inventory", "inspect the record for the relevant version")
                    else -> Operation("Inspecting release artifacts", "release inspection", "check the relevant version and available artifacts against the requested release state")
                }
                "github_decide" -> Operation("Waiting for your decision", "decision request", "use the recorded choice to select the authorized next step without repeating completed work")
                "web_search" -> Operation("Searching web sources", "web search", "read the relevant sources and compare their information with the repository evidence")
                "fetch_webpage" -> Operation("Reading web source", "web source read", "check how the source supports the investigation before relying on its conclusions")
                else -> Operation("Inspecting repository information", "repository operation", "connect the available result to the request and identify the next missing piece of evidence")
            }
        }

        private fun previewEvent(name: String, args: JsonObject?, phase: GitHubPreviewPhase): GitHubPreviewEvent {
            val action = (GitHubActionIdentity.canonicalArguments(name, args ?: JsonObject(emptyMap()))["action"] as? JsonPrimitive)
                ?.contentOrNull.orEmpty()
            return GitHubPreviewEvent(name.githubOperationFamily(), action, sourceFocus(args), phase, operationSummary(name, args))
        }

        private fun operationSummary(name: String, args: JsonObject?): String =
            operation(name, args).preview + sourceFocus(args).let { if (it.isBlank()) "" else " · $it" }

        private fun observation(name: String, failed: Boolean, shared: Boolean, args: JsonObject?): String {
            val operation = operation(name, args)
            val outcome = when {
                failed -> "failed"
                shared -> "is available from saved results"
                operation.mutation -> "returned a result; any remote change still needs verification"
                else -> "returned a result for inspection"
            }
            return "The ${operation.subject}${focusClause(args)} $outcome."
        }

        fun milestone(toolName: String, failed: Boolean, shared: Boolean, args: JsonObject? = null): String {
            val operation = operation(toolName, args)
            val outcome = when {
                failed -> "needs attention"
                shared -> "reused from saved results"
                operation.mutation -> "result available; verification pending"
                else -> "result received"
            }
            return "${operation.subject.replaceFirstChar(Char::uppercase)} $outcome" + sourceFocus(args).let { if (it.isBlank()) "" else " · $it" }
        }
    }
}

/** Each round drafts fresh prose from retained results; earlier prose becomes public progress. */
internal class GitHubProgressSession(
    private val delegate: AgentProviderSession,
    private val beforeRound: suspend (List<AgentToolExchange>) -> Unit = {},
    private val roundStarted: suspend () -> Unit
) : AgentProviderSession by delegate {
    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        roundStarted()
        beforeRound(exchanges)
        emitAll(delegate.streamRound(tools, exchanges))
    }
}
