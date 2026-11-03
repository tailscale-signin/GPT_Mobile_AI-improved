package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Public observable activity changes only; no model invocation or periodic filler. */
internal class GitHubChatProgress(task: String = "") {
    private var activity = "Preparing to inspect the relevant repository details."
    private var summary = GitHubProgressPresentation.introduction(task)
    private val state = kotlinx.coroutines.flow.MutableStateFlow(ApiState.ActivitySummary(summary, preview = activity))

    fun updates(): Flow<ApiState.ActivitySummary> = state

    @Synchronized fun observe(event: ApiState) {
        when (event) {
            is ApiState.GatewayProgressChanged -> {
                val progress = event.progress
                val name = progress.toolName.orEmpty()
                when (progress.event) {
                    "tool_started" -> {
                        activity = currentActivity(name)
                        summary = operationSummary(name, progress.toolArgs)
                    }
                    "tool_failed" -> {
                        activity = "Checking how to continue after the problem."
                        summary = "A step needs attention. I’m checking its failure before choosing a recovery action."
                    }
                    // Completion events already supply a receipt-backed preview. Do not
                    // create a second paragraph repeating that preview or invent a finding.
                    "tool_completed", "tool_finished" -> activity = milestone(name, false, false, progress.toolArgs)
                }
            }
            is ApiState.Success -> {
                activity = "Putting the response together."
                summary = "Putting the response together. I’m separating confirmed findings from remaining work."
            }
            is ApiState.Thinking -> if (activity.startsWith("Preparing")) {
                activity = "Examining the request and planning the relevant checks."
                summary = "Examining the request and planning the relevant checks."
            }
            else -> return
        }
        state.value = ApiState.ActivitySummary(summary, preview = activity)
    }

    @Synchronized fun heartbeat(): String = summary

    companion object {
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
                "github_ci" to "logs" -> "I’m reading the job output to locate the failing step and its error."
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
            "web_search", "fetch_webpage" -> "I’m gathering supporting sources and checking whether they address the repository issue."
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
internal class GitHubProgressSession(private val delegate: AgentProviderSession, private val roundStarted: suspend () -> Unit) : AgentProviderSession by delegate {
    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        roundStarted()
        emitAll(delegate.streamRound(tools, exchanges))
    }
}
