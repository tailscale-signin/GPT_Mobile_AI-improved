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
    private val taskLabel = GitHubProgressPresentation.taskLabel(task)
    private val introduction = GitHubProgressPresentation.introduction(task)
    private var activity = "Preparing to inspect the relevant repository details."
    private var finished: String? = null

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
                "tool_started" -> currentActivity(state.progress.toolName.orEmpty())
                "tool_completed", "tool_finished" -> {
                    finished = milestone(state.progress.toolName.orEmpty(), false, false)
                    "Reviewing the latest result and deciding what to check next."
                }
                "tool_failed" -> {
                    finished = "A step needs attention."
                    "Checking how to continue after the problem."
                }
                else -> activity
            }
            is ApiState.Success -> "Putting the response together."
            is ApiState.GitHubRoundStarted -> "Reviewing the work so far."
            is ApiState.Thinking -> if (activity.startsWith("Preparing")) "Examining the request and planning the relevant checks." else activity
            else -> activity
        }
    }

    @Synchronized fun heartbeat(): String = buildString {
        append("For “$taskLabel”: ")
        finished?.let { append(it).append(' ') }
        append(activity)
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
