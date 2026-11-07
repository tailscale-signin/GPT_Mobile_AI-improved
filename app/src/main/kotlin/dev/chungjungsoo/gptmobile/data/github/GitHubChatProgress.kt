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

/** Observed activity and explicitly selected-profile prose; no inference runs on the ticker. */
internal class GitHubChatProgress(task: String = "", private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val taskLabel = GitHubProgressPresentation.taskLabel(task)
    private var activity = "Preparing to inspect the relevant repository details."
    private var nextStep = "inspect the request and identify the relevant repository evidence"
    private var completed = 0
    private var failures = 0
    private var lastCompleted = ""
    private var lastPreviewAt = now()
    private var lastSummaryAt = lastPreviewAt
    private var initial = true
    private var summary = GitHubProgressPresentation.introduction(task)
    private val previews = mutableSetOf<String>()
    private val livePreviews = mutableMapOf<String, String>()
    private val recentSteps = ArrayDeque<String>()
    private var revision = 0L
    private var pendingSummary: ApiState.ActivitySummary? = null
    private var pendingSummaryRevision = -1L
    private var lastSummaryKey = ""
    private var lastSummaryRevision = -1L
    private val terminalCalls = mutableSetOf<Pair<String, String>>()
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
        if (initial || at - lastSummaryAt >= SUMMARY_INTERVAL_MS) {
            val generated = pendingSummary.takeIf { pendingSummaryRevision == revision }
            if (!initial && generated == null) {
                summary = buildString {
                    if (completed == 0) {
                        append("I have received the request; no repository operation has completed yet. ")
                    } else {
                        append("I have completed $completed repository operations. $lastCompleted ")
                    }
                    if (failures > 0) append("$failures operations need recovery. ")
                    val details = recentSteps.takeLast(3).filter { it != lastCompleted }
                    if (details.isNotEmpty()) append(details.joinToString(" ")).append(' ')
                    append("Next, I will $nextStep. Any remaining checks or blocked operations stay visible until their results can be verified.")
                }
            }
            if (!initial && generated != null) summary = generated.text
            val summaryKey = GitHubProgressPresentation.lineKey(summary)
            if (initial || (generated != null || revision != lastSummaryRevision) && summaryKey != lastSummaryKey) {
                add(if (!initial && generated != null) generated else ApiState.ActivitySummary(summary))
                lastSummaryKey = summaryKey
                lastSummaryRevision = revision
            }
            pendingSummary = null
            initial = false
            lastSummaryAt = at
        }
        val key = GitHubProgressPresentation.lineKey(activity)
        val liveKey = GitHubProgressPresentation.liveKey(activity)
        val changed = if (liveKey != null) livePreviews[liveKey] != key else key !in previews
        if (activity.isNotBlank() && at - lastPreviewAt >= PREVIEW_INTERVAL_MS && changed) {
            add(ApiState.ProgressCheckpoint(activity))
            if (liveKey != null) livePreviews[liveKey] = key else previews.add(key)
            if (previews.size > 256) previews.remove(previews.first())
            lastPreviewAt = at
        }
    }

    /** A completed selected-profile update is public before the next tool round starts. */
    @Synchronized fun forward(event: ApiState): ApiState? {
        observe(event)
        return when (event) {
            is ApiState.ProgressCheckpoint -> null
            is ApiState.ActivitySummary -> {
                val generated = pendingSummary.takeIf { pendingSummaryRevision == revision } ?: return null
                pendingSummary = null
                val key = GitHubProgressPresentation.lineKey(generated.text)
                if (key == lastSummaryKey) return null
                summary = generated.text
                lastSummaryKey = key
                lastSummaryRevision = revision
                lastSummaryAt = now()
                initial = false
                generated
            }
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
                when (progress.event) {
                    "tool_started" -> {
                        activity = operationSummary(name, progress.toolArgs)
                        nextStep = "finish this operation and compare its result with the remaining requirements"
                        revision++
                    }
                    "tool_failed" -> {
                        if (terminalKey != null && !terminalCalls.add(terminalKey)) return
                        failures++
                        activity = "Checking how to continue after the problem."
                        nextStep = "inspect the recorded failure and choose a bounded recovery action"
                        rememberStep(milestone(name, true, false, progress.toolArgs))
                    }
                    "tool_completed", "tool_finished" -> {
                        if (terminalKey != null && !terminalCalls.add(terminalKey)) return
                        completed++
                        activity = milestone(name, false, false, progress.toolArgs)
                        lastCompleted = activity
                        nextStep = "review these results and continue the remaining requested work"
                        rememberStep(activity)
                    }
                }
            }
            is ApiState.ProgressCheckpoint -> {
                if (activity != event.text) revision++
                activity = event.text
            }
            is ApiState.ActivitySummary -> if (event.modelAuthored && event.text.isNotBlank()) {
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
        recentSteps.takeLast(6),
        GitHubProgressPresentation.taskLabel(nextStep)
    )

    private fun rememberStep(step: String) {
        if (recentSteps.lastOrNull() != step) recentSteps.addLast(step.take(220))
        while (recentSteps.size > 8) recentSteps.removeFirst()
        revision++
    }

    companion object {
        const val PREVIEW_INTERVAL_MS = 3_000L
        const val SUMMARY_INTERVAL_MS = 15_000L
        private fun currentActivity(name: String): String = when (name.substringBefore("__")) {
            "web_search" -> "Searching the internet for relevant information."
            "fetch_webpage" -> "Reading the relevant web page."
            "github_search" -> "Searching for the code and files related to the task."
            "github_read" -> "Reading the relevant source files."
            "github_context" -> "Checking the repository, branch and available evidence."
            "github_ci", "github_watch_checks" -> "Inspecting the build and test results."
            "github_pr" -> "Checking the pull request and its current review state."
            "github_write", "github_stage_patch", "github_commit" -> "Working through the requested change and its safeguards."
            "github_decide" -> "Waiting for your direction before continuing."
            else -> "Looking through the repository information for the task."
        }

        private fun sourceFocus(args: kotlinx.serialization.json.JsonObject?): String {
            val ranges = args?.get("ranges") as? kotlinx.serialization.json.JsonArray
            val path = ((ranges?.firstOrNull() as? kotlinx.serialization.json.JsonObject)?.get("path") ?: args?.get("path")) as? kotlinx.serialization.json.JsonPrimitive
            fun value(key: String) = (args?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(GitHubProgressPresentation::taskLabel)
            return when {
                path != null -> "File: ${GitHubProgressPresentation.taskLabel(path.content)}. "
                value("job_id") != null -> "Job ${value("job_id")}. "
                value("run_id") != null -> "Run ${value("run_id")}. "
                value("pull_number") != null -> "Pull request #${value("pull_number")}. "
                value("branch") != null -> "Branch: ${value("branch")}. "
                value("tag_name") != null -> "Tag: ${value("tag_name")}. "
                value("commit_sha") != null -> "Revision ${value("commit_sha")?.take(12)}. "
                else -> ""
            }
        }

        private fun operationSummary(name: String, args: kotlinx.serialization.json.JsonObject?): String {
            val action = (args?.get("action") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val purpose = when (name.substringBefore("__") to action) {
                "github_ci" to "logs" -> "I’m reading the recorded job output and checking its observed result."
                "github_ci" to "jobs" -> "I’m identifying which jobs ran, failed or were skipped before opening their logs."
                "github_ci" to "workflows" -> "I’m finding the available workflows so validation uses a real workflow definition."
                "github_ci" to "runs" -> "I’m locating the workflow attempt associated with this revision."
                "github_context" to "resolve" -> "Resolving the selected reference so every later observation uses the same revision."
                "github_context" to "index" -> "Checking source-index coverage to see which files still need a direct read."
                "github_context" to "efficiency" -> "Inspecting cached reads and reuse to identify unnecessary repeat work."
                "github_context" to "capabilities" -> "Checking which repository operations are authorized before planning the remaining work."
                "github_context" to "recovery" -> "Matching saved work with the current repository state before resuming it."
                "github_context" to "commits" -> "Reading recent changes to establish how the selected branch reached its current state."
                "github_release" to "status" -> "Checking the release attached to this tag and whether its artifacts are available."
                "github_release" to "tags" -> "Finding existing tags before selecting a release to inspect."
                "github_release" to "list" -> "Looking through published releases for the version relevant to this request."
                "github_issue" to "list" -> "Looking for an existing issue that can supply the next read’s identifier."
                "github_context" to "checks" -> "I’m checking the selected commit’s results; pending and skipped checks remain separate from passes."
                "github_context" to "branches" -> "I’m comparing branch references to establish where the requested work belongs."
                "github_pr" to "reviews" -> "I’m reading review decisions to identify unresolved requirements."
                "github_pr" to "comments" -> "I’m reading the pull request discussion for outstanding questions and fixes."
                else -> activityPurpose(name)
            }
            return sourceFocus(args) + purpose
        }

        private fun activityPurpose(name: String): String = when (name.substringBefore("__")) {
            "github_search" -> "I’m locating existing paths and symbols so the next source read targets the relevant implementation."
            "github_read" -> "I’m tracing the implementation to identify where the behavior originates and what depends on it."
            "github_context" -> "I’m establishing the selected revision and repository state so later findings refer to the correct code."
            "github_ci", "github_watch_checks" -> "I’m separating executed checks from pending or skipped jobs and looking for actionable failures."
            "github_pr" -> "I’m checking the proposed changes and review requirements before deciding the next step."
            "github_issue" -> "I’m comparing the issue details with the current investigation to identify unresolved requirements."
            "github_write", "github_stage_patch", "github_commit" -> "I’m checking the requested changes against the current branch; completion still needs an accepted result."
            "web_search", "fetch_webpage" -> "I’m gathering supporting web sources and checking whether they address the repository issue."
            "github_decide" -> "The next operation depends on your choice; completed work remains available."
            else -> "I’m comparing the available information with the remaining requirements to select the next useful check."
        }

        fun milestone(toolName: String, failed: Boolean, shared: Boolean, args: kotlinx.serialization.json.JsonObject? = null): String {
            val name = toolName.substringBefore("__")
            val focus = sourceFocus(args).trim()
            if (focus.isNotBlank()) return if (failed) "$focus Operation failed; inspecting the cause." else "$focus Result received."
            return when {
                name == "github_decide" && !failed -> "Your choice is saved. Continuing with your direction."
                failed -> "A step needs attention. Checking how to continue."
                shared -> "Using the information already gathered."
                name in setOf("web_search", "fetch_webpage") -> "Web information is ready to review."
                name in setOf("github_read", "github_search", "github_context") -> "Repository information is ready to review."
                name in setOf("github_write", "github_stage_patch") -> "The change request has a result to review."
                name == "github_ci" || name == "github_watch_checks" -> "Checking the latest build and test results."
                name == "github_pr" -> "Reviewing the pull request update."
                else -> "Reviewing the latest result and the next step."
            }
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
