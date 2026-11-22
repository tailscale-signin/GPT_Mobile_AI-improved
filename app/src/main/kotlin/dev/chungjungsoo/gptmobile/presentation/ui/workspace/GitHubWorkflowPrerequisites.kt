package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import dev.chungjungsoo.gptmobile.data.agent.ProviderWaitDeadline
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent

internal data class GitHubAuthorityReview(
    val profileUid: String,
    val profileName: String,
    val connectionNames: List<String>,
    val unavailableConnections: Int
)

/** A replaced action stays revoked even after returning to the same chat, run and fence. */
internal class GitHubPrerequisiteOperation {
    class Token(val runId: String, val fence: Long)

    @Volatile private var current: Token? = null
    fun begin(run: AgentRun): Token = Token(run.runId, run.ownershipFence).also { current = it }
    fun owns(token: Token): Boolean = current === token
    fun clear() {
        current = null
    }
}

/** These controls only inspect prerequisites or reschedule an already-owned wait. */
internal object GitHubWorkflowPrerequisites {
    fun providerDeadline(run: AgentRun, deadline: ProviderWaitDeadline?, effectiveAction: String?): ProviderWaitDeadline? =
        deadline?.takeIf {
            run.status == AgentRunStatus.WAITING_PROVIDER_LIMIT &&
                it.runId == run.runId &&
                it.fence == run.ownershipFence &&
                it.retryAt > 0 &&
                effectiveAction in setOf(null, "WAIT_PROVIDER_DEADLINE")
        }

    suspend fun authorityReview(
        expected: AgentRun,
        loadRun: suspend (String) -> AgentRun?,
        loadMessage: suspend (Int) -> MessageV2?,
        loadEvents: suspend (String) -> List<ToolEvent>,
        loadProfiles: suspend () -> List<PlatformV2>,
        loadConnection: suspend (String) -> ToolConnection?
    ): GitHubAuthorityReview? {
        val firstMessage = loadMessage(expected.assistantMessageId) ?: return null
        fun owns(run: AgentRun?, message: MessageV2?): Boolean = run != null &&
            message != null &&
            GitHubWorkflowRecovery.sameAttempt(expected, run) &&
            run.providerSnapshot == expected.providerSnapshot &&
            message.id == expected.assistantMessageId &&
            message.chatId == expected.chatId &&
            message.currentRunId == expected.runId &&
            message.ownershipFence == expected.ownershipFence &&
            message.platformType == firstMessage.platformType
        if (!owns(loadRun(expected.runId), firstMessage)) return null
        fun identities(events: List<ToolEvent>) = events.filter { it.runId == expected.runId }
            .mapNotNull { it.connectionUidSnapshot?.takeIf(String::isNotBlank) }.distinct().sorted()
        val connections = identities(loadEvents(expected.runId))
        if (connections.size > 16) return null
        val profile = loadProfiles().singleOrNull { it.uid == expected.profileUid } ?: return null
        var unavailable = 0
        val names = connections.mapNotNull { uid ->
            val connection = loadConnection(uid)?.takeIf { it.connectionUid == uid }
            if (connection == null) unavailable++
            connection?.name
        }
        // Configuration reads may suspend; a different owner or connection set cannot inherit this route.
        if (!owns(loadRun(expected.runId), loadMessage(expected.assistantMessageId)) || identities(loadEvents(expected.runId)) != connections) return null
        return GitHubAuthorityReview(profile.uid, profile.name, names, unavailable)
    }
}
