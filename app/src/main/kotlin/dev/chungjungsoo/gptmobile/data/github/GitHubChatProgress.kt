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
internal class GitHubChatProgress {
    private var activity = "Getting ready to work on your request."

    fun updates(): Flow<ApiState.ActivitySummary> = flow {
        while (true) {
            delay(HEARTBEAT_MILLIS)
            emit(ApiState.ActivitySummary(heartbeat()))
        }
    }

    @Synchronized fun observe(state: ApiState) {
        activity = when (state) {
            is ApiState.GatewayProgressChanged -> when (state.progress.event) {
                "tool_started", "tool_formulating" -> "Looking through the repository."
                "tool_completed" -> "Reviewing what I found."
                "tool_failed" -> "Checking how to continue after a problem."
                else -> activity
            }
            is ApiState.Success -> "Putting the response together."
            is ApiState.GitHubRoundStarted -> "Reviewing the work so far."
            is ApiState.Thinking -> "Working through your request."
            else -> activity
        }
    }

    @Synchronized fun heartbeat(): String = activity

    companion object {
        const val HEARTBEAT_MILLIS = 5_000L
        fun milestone(name: String, failed: Boolean, shared: Boolean): String = when {
            name == "github_decide" && !failed -> "Your choice is saved. Continuing with your direction."
            failed -> "A step needs attention. Checking how to continue."
            shared -> "Using the information already gathered."
            name in setOf("github_read", "github_search", "github_context") -> "Repository information is ready to review."
            name in setOf("github_write", "github_stage_patch") -> "The change request has a result to review."
            name == "github_ci" || name == "github_watch_checks" -> "Checking the latest build and test results."
            name == "github_pr" -> "Reviewing the pull request update."
            else -> "Reviewing the latest result and the next step."
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
