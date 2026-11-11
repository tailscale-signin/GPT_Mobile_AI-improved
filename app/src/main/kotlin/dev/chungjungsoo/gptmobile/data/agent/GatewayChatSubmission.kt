package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRequestRejected
import dev.chungjungsoo.gptmobile.data.github.GatewayOwnedTurnPolicy
import dev.chungjungsoo.gptmobile.data.github.GitHubApiOrigins
import dev.chungjungsoo.gptmobile.data.github.GitHubAutonomousEngineeringPolicy
import dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Ordinary GitHub Send and the Jobs UI share the same durable command identity. */
@Singleton
class GatewayChatSubmission @Inject constructor(
    private val engineering: GatewayEngineeringRepository,
    private val conversations: GatewayConversationRepository,
    private val settings: SettingRepository,
    private val chats: ChatRepository,
    private val workspace: GatewayWorkspacePreflight
) {
    /** Null leaves the turn on its selected client inference route, before any engineering submission. */
    suspend fun submitIfSupported(request: AgentRunRequest): GatewayChatHandoff? {
        val tools = request.chatToolConfig
        if (!chats.researchPersistenceAllowed(request.chatId)) return null
        val features = settings.getFeatureSettings()
        // Explicit Gateway chats use their own frozen inference destination. A GitHub
        // engineering override cannot turn their queued ordinary turn into another job.
        if (request.chatId in features.conversationGateway) {
            conversations.conversationWriteBlocker(request.chatId)?.let { error(it) }
            return null
        }
        if (request.stage == null && tools?.combined == null) {
            engineering.conversationWriteBlocker(request.chatId)?.let { error(it) }
        }
        if (tools?.combined != null ||
            request.stage != null ||
            request.platform.disableAllTools ||
            request.platform.disableRemoteTools ||
            !GitHubAutonomousEngineeringPolicy.supportsSavedJobs(request.platform)
        ) {
            return null
        }
        val preferences = features.githubProfileOverrides[request.platform.uid] ?: GitHubChatProfileOverride()
        if (!preferences.githubTools || tools?.allToolsDisabled == true) return null
        val user = request.userMessages.lastOrNull() ?: return null
        val context = tools?.github
        val newJob = GatewayOwnedTurnPolicy.configured(request.platform, context, features) && GatewayOwnedTurnPolicy.startsNewJob(user.content)
        val existing = if (context == null) null else engineering.bindings(request.chatId).singleOrNull { it.request.requestId == request.runId }
        if (user.attachments.isEmpty() && (context == null || newJob || existing != null)) {
            try {
                engineering.submitBoundTurn(
                    request.chatId, request.platform.uid, request.platform.model, user.content, request.runId,
                    user.id, request.assistantMessage.id, request.runId, GatewayChatScope.from(user.content),
                    admissionAllowed = { request.gatewayAdmissionAllowed?.invoke() != false }
                )?.let { return GatewayChatHandoff(it.job != null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return retainedHandoff(request, failure)
            }
        }
        if (existing != null) return GatewayChatHandoff(existing.job != null)
        if (context == null || !newJob) return null
        if (!engineering.supportsDurableChat(request.platform.uid)) return null
        check(user.attachments.isEmpty()) { "Saved gateway GitHub jobs accept text requests. Remove attachments before starting this server job." }
        val base = workspace.defaultBranch(context)
        val taskScope = GatewayChatScope.from(user.content)
        check(taskScope != "implement" || !GatewayChatScope.forbidsCommit(user.content)) {
            "Gateway implementation jobs include commits. This request explicitly forbids them; revise the task or use a read/planning request."
        }
        return try {
            val accepted = engineering.submitTurn(
                chatId = request.chatId,
                context = context,
                base = base,
                branch = context.ref,
                objective = user.content,
                commandId = request.runId,
                userMessageId = user.id,
                assistantMessageId = request.assistantMessage.id,
                runId = request.runId,
                taskScope = taskScope,
                selectedModel = request.platform.model,
                admissionAllowed = { request.gatewayAdmissionAllowed?.invoke() != false }
            )
            GatewayChatHandoff(accepted.job != null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A saved command may have reached the host even when its reply was lost.
            // Its original identity is reconciled by the read-only observer, never a phone run.
            retainedHandoff(request, failure)
        }
    }

    private suspend fun retainedHandoff(request: AgentRunRequest, failure: Exception): GatewayChatHandoff {
        val retained = engineering.bindings(request.chatId).singleOrNull { it.request.requestId == request.runId }
        if (retained?.job != null) return GatewayChatHandoff(true)
        if (failure is GatewayEngineeringRequestRejected && !failure.commandOutcomeUnknown) throw failure
        return if (retained != null) GatewayChatHandoff(false) else throw failure
    }

    suspend fun refresh(chatId: Int) {
        engineering.refreshChat(chatId)
    }

    suspend fun ownsRequest(runId: String): Boolean = engineering.bindings().any { it.localRunId == runId || it.request.requestId == runId }

    suspend fun hasUnconfirmedAdmission(chatId: Int, profileUid: String): Boolean = engineering.hasUnconfirmedAdmission(chatId, profileUid)

    suspend fun ownsConversation(chatId: Int): Boolean = engineering.ownsConversation(chatId)

    suspend fun conversationWriteBlocker(chatId: Int): String? = engineering.conversationWriteBlocker(chatId)

    suspend fun ordinaryCancellationConfig(run: AgentRun): ProviderRequestConfig? = conversations.ordinaryCancellationConfig(run)

    /** Only explicit Stop reaches this path; activity/service teardown never invokes it. */
    suspend fun cancel(runIds: Set<String>): List<AgentRunNotice> = engineering.bindings().filter { it.localRunId in runIds }.map { retained ->
        val message = try {
            val current = engineering.observe(retained.request.requestId)
            val job = current.job
            if (job == null) {
                "Stop is not confirmed. Reconnect to the gateway to check the saved command."
            } else if (job.terminal) {
                "The gateway job has already finished. Its saved result is available in Gateway."
            } else {
                engineering.control(retained.request.requestId, job, "cancel")
                "Stop was requested on the gateway. Check Jobs for its final state."
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "Stop is not confirmed. The gateway job may still be running; reconnect to check it."
        }
        AgentRunNotice(retained.chatId, requireNotNull(retained.localRunId), message, persistent = true)
    } + conversations.unconfirmedOrdinaryStops(runIds).map { (runId, chatId) ->
        AgentRunNotice(
            chatId,
            runId,
            "Stop is not confirmed because the submission reply was lost. Open Jobs to check and stop the saved gateway job when it becomes available.",
            persistent = true
        )
    }
}

data class GatewayChatHandoff(val accepted: Boolean) {
    val message: String get() = if (accepted) {
        "This job is saved on the gateway and can continue when the app closes. Open Jobs or Gateway to reconnect."
    } else {
        "The submission reply was not received. Its command is saved; reconnect to check whether the gateway accepted it."
    }
}

/** Reads the actual repository default; the selected working branch is never substituted. */
@Singleton
class GatewayWorkspacePreflight @Inject constructor(
    private val connections: ToolConnectionRepository,
    private val authentication: GitHubPublicAuthCoordinator
) {
    suspend fun defaultBranch(context: GitHubConversationContext): String {
        val connection = requireNotNull(connections.getConnection(context.connectionUid)) { "The saved GitHub connection is unavailable." }
        check(connection.type == "GITHUB") { "The saved connection is not a GitHub connection." }
        val token = if (connection.secretRef != null) authentication.accessToken(connection) else ""
        val metadata = GitHubWorkspaceClient(
            token,
            conditionalReads = false,
            connectionUid = context.connectionUid,
            apiOrigins = GitHubApiOrigins.fromEndpoint(connection.endpointUrl)
        ).execute(
            "get_repository",
            buildJsonObject {
                put("owner", context.owner)
                put("repo", context.repo)
            }
        ).jsonObject
        return requireNotNull(metadata["default_branch"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)) {
            "The repository's default branch could not be verified. Refresh the GitHub workspace before starting this job."
        }
    }
}

/** Ambiguous discussion remains read-only; planning and quoted examples do not grant writes. */
internal object GatewayChatScope {
    private val noCommits = Regex("\\b(?:(?:do not|don't|never)\\s+(?:commit|push)|without\\s+(?:commits|pushes|committing|pushing))\\b", RegexOption.IGNORE_CASE)

    fun forbidsCommit(request: String): Boolean = noCommits.containsMatchIn(request)

    fun from(request: String): String = GatewayOwnedTurnPolicy.scope(request)
}
