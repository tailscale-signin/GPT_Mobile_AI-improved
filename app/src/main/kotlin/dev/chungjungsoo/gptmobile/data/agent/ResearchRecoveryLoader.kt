package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.tool.selectionId
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore

/** A separate coroutine stage keeps recovery's exception table out of provider preparation. */
internal class ResearchRecoveryLoader(private val agentPersistenceDao: AgentPersistenceDao, private val settingRepository: SettingRepository, private val researchSessions: ResearchSessionStore?) {
    suspend fun load(platform: PlatformV2, runId: String, effectiveUserMessages: List<MessageV2>, savedUserTurns: List<MessageV2>, taskRecovery: ResolvedTaskRecovery?, allowedTools: List<ResolvedAgentTool>, policy: ModelDelegationSettings, sharingEnabled: Boolean, dispatchPolicyRevision: String, onBlocked: (RecoveryBlock) -> Unit): ResponseRecoveryContext? = if (runId.startsWith("combined-synthesis:")) {
        null
    } else {
        effectiveUserMessages.lastOrNull()?.takeIf { it.chatId > 0 }?.let { latest ->
            val userIds = (if (taskRecovery != null) savedUserTurns.dropWhile { it.id != taskRecovery.sourceMessageId } else effectiveUserMessages.takeLast(1))
                .map { it.id }.distinct().filter { it > 0 }
            val completedRecoveryRuns = if (taskRecovery != null) agentPersistenceDao.getCompletedRuns(latest.chatId).filter { it.userMessageId in userIds } else emptyList()
            val candidateRuns = (agentPersistenceDao.getIncompleteRuns(latest.chatId, userIds) + completedRecoveryRuns)
                .filter { it.runId != runId }.distinctBy { it.runId }.sortedBy { it.createdAt }
            val allowed = allowedTools.map { it.realToolName to it.connectionUid }.toSet()
            val savedEvents = if (candidateRuns.isEmpty()) emptyList() else agentPersistenceDao.getToolEvents(candidateRuns.map { it.runId })
            val deniedRuns = savedEvents.filter { event ->
                (event.toolName to event.connectionUidSnapshot) !in allowed &&
                    event.toolName != "read_recovery_context" ||
                    dev.chungjungsoo.gptmobile.data.health.HealthConnectTools.isHealthTool(event.toolName)
            }.map { it.runId }.toSet()
            // A response may contain copied tool data. Withhold the whole response if
            // any contributing connection is no longer authorized, including health.
            val runs = candidateRuns.filter { it.runId !in deniedRuns }
            val events = savedEvents.filter { it.runId !in deniedRuns && (it.toolName to it.connectionUidSnapshot) in allowed }
            researchSessions?.loadChat(latest.chatId)
            val authorityTools = dev.chungjungsoo.gptmobile.data.agent.tool.aggregateWebSearch(allowedTools, true, true).filterNot { it.realToolName in setOf("delegate_to_model", "read_recovery_context") }
            val authority = dev.chungjungsoo.gptmobile.data.research.researchHash(policy.deepResearch.collectionPolicyKey() + authorityTools.map { it.selectionId() }.sorted().joinToString())
            val scope = if (sharingEnabled && authorityTools.all { it.connectionUid == null }) authority else "$authority:${platform.uid}"
            val root = dev.chungjungsoo.gptmobile.data.agent.rootWorkId(savedUserTurns, runId)
            val retainedResearch = researchSessions?.sessions?.value?.values.orEmpty().filter {
                it.chatId == latest.chatId && it.turnId == root && it.authority == scope && it.runId !in deniedRuns
            }.map { it.snapshot }
            if (runs.isEmpty() && retainedResearch.isEmpty()) {
                null
            } else {
                val recoveryProfile = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == platform.uid }
                val recoveryRoom = agentPersistenceDao.getChatRoom(latest.chatId)
                val recoveryContext = dev.chungjungsoo.gptmobile.data.agent.ResponseRecoveryContext(
                    runs,
                    agentPersistenceDao.getMessages(latest.chatId),
                    events,
                    retainedResearch,
                    rootWorkId = root.takeIf { taskRecovery != null },
                    blockedRootWorkId = root,
                    generationId = runId,
                    destinationIdentity = dev.chungjungsoo.gptmobile.data.agent.recoveryDestinationIdentity(platform),
                    policyRevision = dispatchPolicyRevision,
                    lastAction = taskRecovery?.action?.name ?: "CONTINUE_RUN",
                    accessDecision = {
                        val currentProfile = settingRepository.fetchPlatformV2s().firstOrNull { it.uid == platform.uid }
                        val currentRoom = agentPersistenceDao.getChatRoom(latest.chatId)
                        val dependencies = allowedTools.filter { tool -> events.any { event -> event.toolName == tool.realToolName && event.connectionUidSnapshot == tool.connectionUid } }
                        val rejected = dependencies.filter { it.canReuseResult?.invoke() == false }
                        val reason = when {
                            recoveryProfile == null || currentProfile == null || !currentProfile.enabled -> "PROFILE_DISABLED"
                            !dev.chungjungsoo.gptmobile.data.agent.sameRecoveryDestination(recoveryProfile, currentProfile) -> "DESTINATION_CHANGED"
                            recoveryRoom == null || currentRoom == null || currentRoom.isTemporary != recoveryRoom.isTemporary -> "CONVERSATION_UNAVAILABLE"
                            platform.uid !in currentRoom.activePlatform -> "PROFILE_NOT_IN_CONVERSATION"
                            rejected.isNotEmpty() -> "SOURCE_PERMISSION_REVOKED"
                            else -> "AUTHORIZED"
                        }
                        if (reason != "AUTHORIZED") AppLogRecorder.record("Recovery", "RECOVERY_BLOCKED · runId=$runId · reason=$reason · dependencies=${rejected.size} · dispatched=false", "W")
                        dev.chungjungsoo.gptmobile.data.agent.RecoveryAccessDecision(reason == "AUTHORIZED", reason, rejected.map { "tool:${it.selectionId()}" })
                    }
                )
                if (recoveryContext.isAuthorized()) {
                    recoveryContext
                } else {
                    onBlocked(recoveryContext.blocked())
                    null
                }
            }
        }
    }
}
