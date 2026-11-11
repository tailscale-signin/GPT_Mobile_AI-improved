package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Observed activity and explicitly selected-profile prose; no inference runs on the ticker. */
internal class GitHubChatProgress(task: String = "", private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val taskLabel = GitHubProgressPresentation.taskLabel(task)
    private var activity = "Locating the relevant repository evidence"
    private var nextStep = "connect the requested behavior to its implementation and choose the first useful check"
    private var completed = 0
    private var failures = 0
    private var skipped = 0
    private var blocked = 0
    private var lastPreviewAt = now()
    private var lastSummaryAt = lastPreviewAt
    private var initial = true
    private var summary = GitHubProgressPresentation.introduction(task)
    private val previews = mutableSetOf<String>()
    private val livePreviews = mutableMapOf<String, String>()
    private val recentSteps = ArrayDeque<String>()
    private var lastProblem: String? = null
    private var revision = 0L
    private var pendingSummary: ApiState.ActivitySummary? = null
    private var pendingSummaryRevision = -1L
    private var lastSummaryKey = ""
    private var lastSummaryRevision = -1L
    private var modelSummaryRetained = false
    private val terminalCalls = mutableSetOf<Pair<String, String>>()
    private val activeCalls = mutableSetOf<Pair<String, String>>()
    private val blockedCalls = mutableSetOf<Pair<String, String>>()
    private val observedProgress = linkedSetOf<Triple<String, Int, String>>()

    fun updates(): Flow<ApiState> = flow {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            tick().forEach { emit(it) }
            kotlinx.coroutines.delay(250)
        }
    }

    /** A single monotonic clock owns all public preview and summary admission. */
    @Synchronized internal fun tick(): List<ApiState> = buildList {
        val at = now()
        admitSummary(at, allowFallback = true)?.let(::add)
        val liveKey = GitHubProgressPresentation.liveKey(activity)
        if (liveKey != null) {
            if (admitCounter(liveKey, activity)) add(ApiState.ProgressCheckpoint(activity))
        } else if (activity.isNotBlank() && at - lastPreviewAt >= PREVIEW_INTERVAL_MS && previews.add(GitHubProgressPresentation.lineKey(activity))) {
            add(ApiState.ProgressCheckpoint(activity))
            if (previews.size > 256) previews.remove(previews.first())
            lastPreviewAt = at
        }
    }

    private fun admitSummary(at: Long, allowFallback: Boolean): ApiState.ActivitySummary? {
        if (!initial && at - lastSummaryAt < SUMMARY_INTERVAL_MS) return null
        val generated = pendingSummary.takeIf { it?.modelAuthored == true || pendingSummaryRevision == revision }
        pendingSummary = null
        val candidate = generated ?: if (allowFallback && !modelSummaryRetained && (initial || revision != lastSummaryRevision)) {
            ApiState.ActivitySummary(if (initial) summary else GitHubProgressNarrator.factualSummary(summarySnapshot()))
        } else {
            return null
        }
        lastSummaryRevision = revision
        val key = GitHubProgressPresentation.lineKey(candidate.text)
        if (key.isBlank() || key == lastSummaryKey) return null
        summary = candidate.text
        lastSummaryKey = key
        lastSummaryAt = at
        modelSummaryRetained = candidate.modelAuthored
        initial = false
        return candidate
    }

    private fun admitCounter(liveKey: String, text: String): Boolean {
        val key = GitHubProgressPresentation.lineKey(text)
        if (livePreviews[liveKey] == key) return false
        livePreviews[liveKey] = key
        return true
    }

    @Synchronized fun forward(event: ApiState): ApiState? {
        observe(event)
        return when (event) {
            is ApiState.ProgressCheckpoint -> event.takeIf {
                !event.modelAuthored && GitHubProgressPresentation.liveKey(event.text)?.let { admitCounter(it, event.text) } == true
            }
            is ApiState.ActivitySummary -> admitSummary(now(), allowFallback = false)
            else -> event
        }
    }

    @Synchronized fun observe(event: ApiState) {
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
                        terminalKey?.let(blockedCalls::remove)
                        terminalKey?.takeUnless { it in terminalCalls }?.let(activeCalls::add)
                        activity = operationSummary(name, progress.toolArgs)
                        nextStep = operation.next
                        revision++
                    }
                    "tool_failed" -> {
                        if (terminalKey != null && !terminalCalls.add(terminalKey)) return
                        terminalKey?.let(activeCalls::remove)
                        terminalKey?.let(blockedCalls::remove)
                        failures++
                        modelSummaryRetained = false
                        pendingSummary = null
                        activity = milestone(name, true, false, progress.toolArgs)
                        nextStep = "inspect the ${operation.subject} error and identify whether a corrected request or connection recovery is needed"
                        rememberStep(observation(name, true, false, progress.toolArgs), problem = true)
                    }
                    "tool_blocked" -> {
                        if (terminalKey in terminalCalls || terminalKey != null && !blockedCalls.add(terminalKey)) return
                        terminalKey?.let(activeCalls::remove)
                        blocked++
                        modelSummaryRetained = false
                        pendingSummary = null
                        activity = "${operation.subject.replaceFirstChar(Char::uppercase)} blocked${sourceFocus(progress.toolArgs).let { if (it.isBlank()) "" else " · $it" }}"
                        nextStep = "inspect the recorded blocker and identify the required approval, setup or correction before continuing"
                        rememberStep("The ${operation.subject}${focusClause(progress.toolArgs)} is blocked; the requested action has not been verified.", problem = true)
                    }
                    "tool_completed", "tool_finished", "tool_skipped" -> {
                        if (terminalKey != null && !terminalCalls.add(terminalKey)) return
                        terminalKey?.let(activeCalls::remove)
                        terminalKey?.let(blockedCalls::remove)
                        if (eventName == "tool_skipped") {
                            skipped++
                            modelSummaryRetained = false
                            pendingSummary = null
                            activity = "${operation.subject.replaceFirstChar(Char::uppercase)} skipped${sourceFocus(progress.toolArgs).let { if (it.isBlank()) "" else " · $it" }}"
                            nextStep = "check why the ${operation.subject} was skipped before treating it as validation evidence"
                            rememberStep("The ${operation.subject}${focusClause(progress.toolArgs)} was skipped; it did not provide an executed validation result.", problem = true)
                            return
                        }
                        completed++
                        activity = milestone(name, false, false, progress.toolArgs)
                        nextStep = operation.next
                        rememberStep(observation(name, false, false, progress.toolArgs))
                    }
                }
                val counter = listOfNotNull(progress.message, progress.ui?.title).firstOrNull { GitHubProgressPresentation.liveKey(it) != null }
                if (counter != null) activity = counter
            }
            is ApiState.ProgressCheckpoint -> if (!event.modelAuthored && event.text.isNotBlank() && !GitHubProgressPresentation.isGenericProgress(event.text)) {
                if (activity != event.text && GitHubProgressPresentation.liveKey(event.text) == null) revision++
                activity = event.text
            }
            is ApiState.ActivitySummary -> if (event.text.isNotBlank() && (event.modelAuthored || !modelSummaryRetained && pendingSummary?.modelAuthored != true && !GitHubProgressPresentation.isGenericProgress(event.text))) {
                pendingSummary = event
                pendingSummaryRevision = revision
            }
            is ApiState.Success -> {
                activity = "Putting the response together."
                nextStep = "present the supported findings and identify any remaining work"
            }
            else -> Unit
        }
    }

    @Synchronized fun heartbeat(): String = summary

    /** Only bounded public facts enter the optional prose request, never raw tool bodies or reasoning. */
    @Synchronized fun summarySnapshot(): GitHubProgressSnapshot = GitHubProgressSnapshot(
        revision,
        taskLabel,
        completed,
        failures,
        GitHubProgressPresentation.taskLabel(activity),
        recentSteps.takeLast(6).let { steps -> lastProblem?.takeUnless(steps::contains)?.let { listOf(it) + steps } ?: steps },
        GitHubProgressPresentation.taskLabel(nextStep),
        pending = activeCalls.size,
        skipped = skipped,
        blocked = blocked
    )

    private fun rememberStep(step: String, problem: Boolean = false) {
        if (problem) lastProblem = step.take(220)
        if (recentSteps.lastOrNull() != step) recentSteps.addLast(step.take(220))
        while (recentSteps.size > 8) recentSteps.removeFirst()
        revision++
    }

    companion object {
        const val PREVIEW_INTERVAL_MS = 3_000L
        const val SUMMARY_INTERVAL_MS = 45_000L
        private data class Operation(val preview: String, val subject: String, val next: String, val mutation: Boolean = false)

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
                value("issue_number") != null -> "Issue #${value("issue_number")}"
                value("branch") != null -> "branch ${value("branch")}"
                value("tag_name") != null -> "tag ${value("tag_name")}"
                else -> ""
            }
        }

        private fun focusClause(args: JsonObject?) = sourceFocus(args).let { if (it.isBlank()) "" else " for $it" }

        private fun operation(name: String, args: JsonObject?): Operation {
            val action = (args?.get("action") as? JsonPrimitive)?.contentOrNull.orEmpty()
            return when (name.substringBefore("__")) {
                "github_read" -> Operation("Reading source", "source read", "compare the implementation with the reported behavior and identify the next relevant source")
                "github_search" -> Operation("Searching repository", "repository search", "open the matching implementation and check how it connects to the requested behavior")
                "github_context" -> when (action) {
                    "checks" -> Operation("Checking build and test results", "check results", "separate failures from pending or skipped checks before deciding what needs repair")
                    "changes" -> Operation("Comparing branch changes", "branch comparison", "read the affected source and examine how the changes alter the requested behavior")
                    "resolve" -> Operation("Resolving branch revision", "reference lookup", "use the observed revision to keep subsequent source reads and checks consistent")
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
                    "workflows" -> Operation("Finding validation workflows", "workflow inventory", "select a workflow that actually covers the changed behavior before requesting validation")
                    "runs" -> Operation("Locating workflow run", "workflow run lookup", "match the run to the selected revision before interpreting its jobs and results")
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
                "github_release" -> Operation("Inspecting release artifacts", "release inspection", "check the relevant version and available artifacts against the requested release state")
                "github_decide" -> Operation("Waiting for your decision", "decision request", "use the recorded choice to select the authorized next step without repeating completed work")
                "web_search" -> Operation("Searching web sources", "web search", "read the relevant sources and compare their information with the repository evidence")
                "fetch_webpage" -> Operation("Reading web source", "web source read", "check how the source supports the investigation before relying on its conclusions")
                else -> Operation("Inspecting repository information", "repository operation", "connect the available result to the request and identify the next missing piece of evidence")
            }
        }

        private fun operationSummary(name: String, args: JsonObject?): String =
            operation(name, args).preview + sourceFocus(args).let { if (it.isBlank()) "" else " · $it" }

        private fun observation(name: String, failed: Boolean, shared: Boolean, args: JsonObject?): String {
            val operation = operation(name, args)
            val outcome = when {
                failed -> "failed"
                shared -> "is available from saved results"
                operation.mutation -> "returned a result; any remote change still needs verification"
                else -> "is available for review"
            }
            return "The ${operation.subject}${focusClause(args)} $outcome."
        }

        fun milestone(toolName: String, failed: Boolean, shared: Boolean, args: JsonObject? = null): String {
            val operation = operation(toolName, args)
            val outcome = when {
                failed -> "needs attention"
                shared -> "reused from saved results"
                operation.mutation -> "result available; verification pending"
                else -> "available for review"
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
