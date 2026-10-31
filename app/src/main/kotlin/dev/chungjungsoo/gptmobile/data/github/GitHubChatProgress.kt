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
internal class GitHubChatProgress(private val task: String, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val started = now()
    private var activity = "Preparing the repository task"
    private var completed = 0
    private var failed = 0
    private var latest = ""

    fun updates(): Flow<ApiState.ActivitySummary> = flow {
        while (true) {
            delay(HEARTBEAT_MILLIS)
            emit(ApiState.ActivitySummary(heartbeat()))
        }
    }

    @Synchronized fun observe(state: ApiState) {
        when (state) {
            is ApiState.GatewayProgressChanged -> {
                activity = state.progress.message.orEmpty().take(240).ifBlank { activity }
                completed = maxOf(completed, state.progress.usefulToolCalls ?: 0)
                failed = maxOf(failed, state.progress.noProgress ?: 0)
            }
            is ApiState.ProgressCheckpoint -> if (state.text.isNotBlank()) latest = state.text.take(600)
            is ApiState.Success -> activity = "Writing the current response"
            is ApiState.GitHubRoundStarted -> activity = "Reviewing retained work and planning the next step"
            is ApiState.Thinking -> activity = "The model is working on the task"
            else -> Unit
        }
    }

    @Synchronized fun heartbeat(): String {
        val elapsed = ((now() - started).coerceAtLeast(0) / 1000)
        return buildString {
            append(task.lineSequence().firstOrNull().orEmpty().take(160).ifBlank { "GitHub task" })
            append("\n").append(activity).append(" · ").append(elapsed).append("s elapsed")
            if (completed + failed > 0) append("\n").append(completed).append(" useful tool results; ").append(failed).append(" steps without progress.")
            if (latest.isNotBlank()) append("\n").append(latest)
        }
    }

    companion object {
        const val HEARTBEAT_MILLIS = 5_000L
        fun milestone(name: String, failed: Boolean, shared: Boolean): String {
            val label = name.removePrefix("github_").replace('_', ' ').take(80)
            return when {
                name == "github_decide" && !failed -> "Your decision is saved. Continuing with your selected direction."
                failed -> "The $label step reported a problem. Its result is retained for recovery."
                shared -> "Retrieved a saved $label result without repeating the operation."
                else -> "Received the $label result. Reviewing the findings and next steps."
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
