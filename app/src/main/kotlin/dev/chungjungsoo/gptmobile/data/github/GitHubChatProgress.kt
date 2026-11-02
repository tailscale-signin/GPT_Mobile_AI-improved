package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Public observable activity only; no model invocation is needed for the heartbeat. */
internal class GitHubChatProgress(task: String = "") {
    private val introduction = GitHubProgressPresentation.introduction(task)
    private var activity = "Preparing to inspect the relevant repository details."
    private var finished: String? = null
    private var purpose = "I’m identifying which repository information is needed before choosing the next operation."
    private var completedSteps = 0
    private val completedCallIds = mutableSetOf<String>()

    fun updates(): Flow<ApiState.ActivitySummary> = flow {
        emit(ApiState.ActivitySummary(introduction, preview = preview()))
        while (true) {
            delay(HEARTBEAT_MILLIS)
            emit(update())
        }
    }

    @Synchronized fun observe(state: ApiState) {
        activity = when (state) {
            is ApiState.GatewayProgressChanged -> when (state.progress.event) {
                "tool_formulating" -> "Preparing the next repository operation."
                "tool_started" -> {
                    purpose = activityPurpose(state.progress.toolName.orEmpty())
                    currentActivity(state.progress.toolName.orEmpty()) + sourceFocus(state.progress.toolArgs)
                }
                "tool_completed", "tool_finished" -> {
                    val callId = state.progress.toolCallId
                    if (callId == null || completedCallIds.add(callId)) completedSteps++
                    finished = milestone(state.progress.toolName.orEmpty(), false, false)
                    "Reviewing the latest result and deciding what to check next."
                }
                "tool_failed" -> {
                    finished = "A step needs attention."
                    purpose = "I’m checking the failure and available alternatives before deciding whether another operation is appropriate."
                    "Checking how to continue after the problem."
                }
                else -> activity
            }
            is ApiState.Success -> {
                purpose = "I’m organizing the findings, separating confirmed results from work that still needs verification."
                "Putting the response together."
            }
            is ApiState.GitHubRoundStarted -> activity
            is ApiState.Thinking -> if (activity.startsWith("Preparing")) "Examining the request and planning the relevant checks." else activity
            else -> activity
        }
    }

    @Synchronized fun heartbeat(): String = buildString {
        finished?.let { append(it).append(' ') }
        append(activity).append(' ').append(purpose)
        if (completedSteps > 0) append(" Results received from ").append(completedSteps).append(if (completedSteps == 1) " operation." else " operations.")
    }

    @Synchronized private fun preview(): String = activity

    @Synchronized private fun update(): ApiState.ActivitySummary = ApiState.ActivitySummary(heartbeat(), preview = activity).also { finished = null }

    companion object {
        const val HEARTBEAT_MILLIS = 20_000L
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
            val path = (
                (ranges?.firstOrNull() as? kotlinx.serialization.json.JsonObject)?.get("path")
                    ?: args?.get("path")
                ) as? kotlinx.serialization.json.JsonPrimitive
            return path?.content?.takeIf { it.isNotBlank() }?.let { " Requested file: ${GitHubProgressPresentation.taskLabel(it)}." }.orEmpty()
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

        fun milestone(toolName: String, failed: Boolean, shared: Boolean): String {
            val name = toolName.substringBefore("__")
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
