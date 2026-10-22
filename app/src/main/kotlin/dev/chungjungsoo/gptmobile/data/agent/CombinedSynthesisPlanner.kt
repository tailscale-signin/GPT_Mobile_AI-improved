package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.canonicalCombinedAssistant
import dev.chungjungsoo.gptmobile.data.context.hasCombinedHistory
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.presentation.ui.chat.CombinedOwnerResolver
import dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedEditorialInstruction
import dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedSourcesWithEvidence
import dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedSynthesisInputs
import dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedSynthesisPrompt
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Application-owned handoff. The caller holds its chat gate through persistence and dispatch. */
class CombinedSynthesisPlanner @Inject constructor(
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository,
    private val researchSessionStore: ResearchSessionStore
) {
    suspend fun prepare(chatId: Int, userId: Int, rootBudgetId: String): AgentRunRequest? {
        val messages = chatRepository.fetchMessagesV2(chatId)
        val userMessage = messages.singleOrNull { it.id == userId && it.platformType == null } ?: return null
        val runs = chatRepository.observeAgentRuns(chatId).first().associateBy { it.runId }
        val allResponses = messages.filter { it.linkedMessageId == userId && it.platformType != null }
        if (allResponses.any { it.currentRunId?.startsWith(COMBINED_RUN_PREFIX) == true }) return null
        val acceptedRecords = allResponses.mapNotNull { runs[it.currentRunId] }.mapNotNull { CombinedAcceptedTurn.decode(it.synthesisSnapshot) }
        val accepted = acceptedRecords.firstOrNull { it.context.rootBudgetId == rootBudgetId } ?: return null
        if (acceptedRecords.any { it.context.rootBudgetId != rootBudgetId || it.context.requested.requestedPolicyDigest != accepted.context.requested.requestedPolicyDigest }) return null
        if (chatRepository.isCombinedTurnCancelled(rootBudgetId)) return null
        val responses = accepted.context.requested.participantUids.map { uid -> allResponses.singleOrNull { it.platformType == uid } ?: return null }
        if (responses.any { runs[it.currentRunId]?.status !in setOf("COMPLETED", "FAILED", "INTERRUPTED", "CANCELED") }) return null
        val profiles = settingRepository.fetchPlatformV2s()
        val sources = combinedSynthesisInputs(responses, runs, profiles.associate { it.uid to it.name }, accepted.context.requested.requestedModels, userMessage.content).orEmpty()
        val retainedPublicEvidence = if (sources.isEmpty() && accepted.context.requested.acceptedResearch != null) {
            researchSessionStore.exportTurn(chatId, setOf("$chatId:$userId")).any { session ->
                session.turnId == "$chatId:$userId" && dev.chungjungsoo.gptmobile.data.research.researchHash(session.snapshot.task.trim()) == dev.chungjungsoo.gptmobile.data.research.researchHash(userMessage.content.trim()) && session.snapshot.sources.any { it.readable && it.publicCapture && it.retrievalReceipt.isNotBlank() }
            }
        } else {
            false
        }
        if (sources.isEmpty() && !retainedPublicEvidence) {
            chatRepository.markCombinedMergeUnavailable(chatId, userId, rootBudgetId)
            return null
        }
        val taskRef = accepted.acceptedTask ?: accepted.context.acceptedTask ?: AcceptedTaskRef.from(userMessage)
        taskRef.validate(userMessage)
        val combined = accepted.context.copy(chatId = userMessage.chatId, acceptedTask = taskRef)
        val pinnedPrimary = combined.requested.preferredOwnerUid
        val acceptedUids = combined.requested.participantUids
        val writerOrder = CombinedOwnerResolver.eligibleOrder(
            sources,
            acceptedUids,
            pinnedPrimary,
            profiles.filter { it.enabled }.mapTo(hashSetOf()) { it.uid }
        ).ifEmpty { if (retainedPublicEvidence) acceptedUids.sortedWith(compareBy<String> { it != pinnedPrimary }.thenBy { it }) else emptyList() }
        val candidates = writerOrder.mapNotNull { uid ->
            profiles.firstOrNull { it.uid == uid && it.enabled }?.let { profile ->
                val capturedModel = sources.firstOrNull { it.platformUid == uid }?.modelName?.takeIf(String::isNotBlank)
                profile.copy(model = combined.requested.requestedModels[profile.uid] ?: profile.model).let {
                    it.copy(
                        model = capturedModel ?: it.model,
                        temperature = combined.requested.temperatureFor(it.uid, it.temperature)
                    )
                }
            }
        }
        val authorizedLead = candidates.filter { sources.none { it.localOnly } || it.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM }
            .filter { CombinedProviderEligibility.unavailableReason(it, userMessage.content, writer = true) == null }
            .sortedBy { CombinedWriterObservations.get(CombinedWriterObservations.key(it))?.reasoningOnlyAttempts ?: 0 }
            .firstOrNull()
        val leadPlatform = authorizedLead ?: profiles.firstOrNull { it.uid in acceptedUids && it.uid == pinnedPrimary } ?: profiles.firstOrNull { it.uid in acceptedUids }
        if (leadPlatform == null) {
            chatRepository.markCombinedMergeUnavailable(userMessage.chatId, userMessage.id, combined.rootBudgetId)
            return null
        }
        val leadUid = leadPlatform.uid
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "CombinedSynthesis",
            "OWNER_SELECTED · preferred=$pinnedPrimary · selected=$leadUid · reason=${if (leadUid == pinnedPrimary) "HEALTHY_PRIMARY" else "HEALTHY_SELECTED_FALLBACK"} · usable=${sources.size}"
        )
        val leadMessage = responses.singleOrNull { it.platformType == leadUid } ?: return null
        if (leadMessage.id <= 0) return null

        val synthesisRunId = COMBINED_RUN_PREFIX + UUID.randomUUID().toString()

        val normalizedResearchTask = dev.chungjungsoo.gptmobile.data.research.researchHash(userMessage.content.trim())
        val researchTurnId = "${leadMessage.chatId}:${userMessage.id}"
        val turnSessions = researchSessionStore.exportTurn(leadMessage.chatId, setOf(researchTurnId)).filter { session ->
            session.turnId == researchTurnId &&
                dev.chungjungsoo.gptmobile.data.research.researchHash(session.snapshot.task.trim()) == normalizedResearchTask
        }
        // Never widen a source partition just because a contributor finished later.
        val leadAuthority = turnSessions.lastOrNull { it.runId == leadMessage.currentRunId }?.authority
        val sharedEvidence = dev.chungjungsoo.gptmobile.data.research.ResearchCorpus.freezeAll(
            dev.chungjungsoo.gptmobile.data.research.authorizedResearchTransfer(turnSessions, leadAuthority)
        )
        val turnRuns = runs.values.filter { it.chatId == userMessage.chatId && it.userMessageId == userMessage.id && !it.runId.startsWith(COMBINED_RUN_PREFIX) }
        val turnRunIds = turnRuns.mapTo(hashSetOf()) { it.runId }
        // Read the committed event rows rather than racing the UI's asynchronous tool-event snapshot.
        val turnEvents = chatRepository.observeToolEvents(userMessage.chatId).first().filter { it.runId in turnRunIds }
        val evidenceLedger = withContext(Dispatchers.Default) {
            dev.chungjungsoo.gptmobile.data.agent.CombinedEvidenceLedger.freeze(sharedEvidence, turnEvents, turnRuns.associate { it.runId to it.profileUid })
        }
        val turnContext = dev.chungjungsoo.gptmobile.data.agent.CombinedTurnContext.capture(userMessage.createdAt)
        val attributedSources = combinedSourcesWithEvidence(sources, sharedEvidence).map { source ->
            val owned = evidenceLedger.records.filter { source.platformUid in it.profileUids || source.content.contains(it.url) }
            source.copy(sourceIds = (source.sourceIds + owned.map { it.id }).distinct(), evidenceRefs = (source.evidenceRefs + owned.map { it.url }).distinct())
        }
        val fullHistory = messages
        require(fullHistory.any { it.id == userMessage.id && it.platformType == null }) { "The Combined user turn is no longer available." }
        val priorUsers = fullHistory.filter { it.platformType == null }.takeWhile { it.id != userMessage.id }
        val historicalUsers = dev.chungjungsoo.gptmobile.data.agent.CombinedTaskContract.relevantHistoryIndices(userMessage.content, priorUsers.map { it.content }).map { priorUsers[it] }
        val historicalReplies = historicalUsers.map { prior -> fullHistory.filter { it.linkedMessageId == prior.id && it.platformType != null } }
        val selectedUids = acceptedUids
        val answerContract = dev.chungjungsoo.gptmobile.data.agent.CombinedAnswerPolicy.from(userMessage.content, historicalUsers.lastOrNull()?.content)
        val editorialContext = combined.forRole(dev.chungjungsoo.gptmobile.data.agent.CombinedRunRole.SYNTHESIS, leadUid)
        val targetRevisionId = UUID.randomUUID().toString()
        val deliveryProtocol = "PASSAGES_V3"
        val (synthesisPrompt, synthesisSnapshot) = withContext(Dispatchers.Default) {
            val prompt = combinedSynthesisPrompt(userMessage.content, attributedSources, sharedEvidence, evidenceLedger, turnContext, combined = editorialContext, answerContract = answerContract, deliveryProtocol = deliveryProtocol, targetRevisionId = targetRevisionId)
            val lineage = dev.chungjungsoo.gptmobile.data.agent.ContributionLineage.freeze(selectedUids, attributedSources)
            val inventory = dev.chungjungsoo.gptmobile.data.agent.CombinedClaimInventory.freeze(attributedSources)
            prompt to dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot(
                version = 6,
                deliveryProtocol = deliveryProtocol,
                evidence = evidenceLedger,
                turnContext = turnContext,
                userMessage = userMessage,
                history = historicalUsers,
                replies = historicalReplies,
                sources = attributedSources,
                prompt = prompt,
                profileUid = leadPlatform.uid,
                model = leadPlatform.model,
                temperature = leadPlatform.temperature,
                systemPrompt = leadPlatform.systemPrompt.orEmpty(),
                configKey = dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey(leadPlatform),
                lineage = lineage,
                claimInventory = inventory,
                combined = editorialContext,
                answerContract = answerContract,
                revisionId = targetRevisionId,
                acceptedTools = accepted.tools,
                snapshotId = UUID.randomUUID().toString(),
                selectedHistoryRevisionIds = historicalReplies.flatten().mapNotNull { it.effectiveRunId() }.distinct(),
                acceptedTask = taskRef,
                undeliveredHistoryTurnIds = historicalUsers.zip(historicalReplies).filter { (_, replies) -> replies.hasCombinedHistory() && replies.canonicalCombinedAssistant() == null }.map { it.first.id },
                writerAuthorityRevisions = profiles.filter { it.uid in acceptedUids && it.enabled }.associate { it.uid to dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority.profileRevision(it) },
                transferableEvidenceIds = evidenceLedger.records.filter { record -> turnSessions.any { session -> session.snapshot.sources.any { it.url == record.url && it.publicCapture && it.retrievalReceipt.isNotBlank() } } }.map { it.id },
                contributionsTransferable = attributedSources.none { it.localOnly } && turnSessions.all { session -> session.snapshot.sources.all { it.publicCapture && it.retrievalReceipt.isNotBlank() } }
            ).seal().encode()
        }
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "CombinedSynthesis",
            "SYNTHESIS_INPUT_FROZEN · runId=$synthesisRunId · sourceCount=${attributedSources.size} · profileUids=${attributedSources.map { it.platformUid }.distinct().joinToString(",")} · candidateUnits=${dev.chungjungsoo.gptmobile.data.agent.CombinedContentCoverage(attributedSources).units.size} · promptChars=${synthesisPrompt.length} · protocol=$deliveryProtocol"
        )
        val run = AgentRunDraft(
            runId = synthesisRunId,
            profileUid = leadPlatform.uid,
            providerSnapshot = leadPlatform.compatibleType.name,
            modelSnapshot = leadPlatform.model,
            synthesisKey = dev.chungjungsoo.gptmobile.data.research.researchHash(synthesisSnapshot),
            synthesisSnapshot = synthesisSnapshot,
            createdAt = System.currentTimeMillis() / 1000
        )

        if (chatRepository.isCombinedTurnCancelled(rootBudgetId)) return null
        val persisted = chatRepository.persistAgentRetry(
            PersistAgentRetryRequest(
                userMessage = userMessage,
                assistantMessage = leadMessage.copy(combinedSources = attributedSources),
                run = run,
                freezeCombinedSources = attributedSources.isNotEmpty()
            )
        )
        // The durable run now owns this answer slot. No automatic retry may
        // replace it, even if UI publication or provider dispatch subsequently fails.
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "CombinedSynthesis",
            "SYNTHESIS_RUN_PERSISTED · runId=$synthesisRunId · leadProfileUid=$leadUid · sourceCount=${attributedSources.size} · evidenceRecords=${evidenceLedger.records.size}"
        )

        val frozen = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<CombinedInputSnapshot>(synthesisSnapshot)
        if (authorizedLead == null) {
            chatRepository.publishCombinedEvidenceFallback(persisted.run.runId)
            return null
        }
        return AgentRunRequest(
            runId = persisted.run.runId,
            chatId = persisted.assistantMessage.chatId,
            assistantMessage = persisted.assistantMessage,
            platform = leadPlatform.copy(
                systemPrompt = listOfNotNull(leadPlatform.systemPrompt, combinedEditorialInstruction(deliveryProtocol)).joinToString("\n\n"),
                disableAllTools = true,
                disableLocalTools = true
            ),
            userMessages = historicalUsers + userMessage,
            assistantMessages = historicalReplies + listOf(emptyList()),
            chatToolConfig = accepted.tools.copy(
                allToolsDisabled = true,
                delegation = dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings(enabled = false),
                combined = editorialContext
            ),
            stage = frozen.bindAcceptedStage()
        )
    }

    suspend fun prepareFallback(chatId: Int, failedRunId: String): AgentRunRequest? {
        val runs = chatRepository.observeAgentRuns(chatId).first()
        val prior = runs.singleOrNull { it.runId == failedRunId } ?: return null
        if (!permitsCombinedWriterFallback(prior.status, prior.terminalError)) return null
        val raw = prior.synthesisSnapshot ?: return null
        if (dev.chungjungsoo.gptmobile.data.research.researchHash(raw) != prior.synthesisKey) return null
        val original = runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<CombinedInputSnapshot>(raw) }.getOrNull() ?: return null
        val policy = original.combined ?: return null
        if (!chatRepository.canRetryCombined(policy.rootBudgetId)) return null
        val messages = chatRepository.fetchMessagesV2(chatId)
        val owner = messages.singleOrNull { it.id == prior.assistantMessageId && it.currentRunId == prior.runId && it.ownershipFence == prior.ownershipFence } ?: return null
        val user = messages.singleOrNull { it.id == prior.userMessageId && it.platformType == null } ?: return null
        if (user != original.userMessage) return null
        val attempted = runs.filter { it.userMessageId == prior.userMessageId && it.synthesisKey != null }.mapTo(hashSetOf()) { it.profileUid }
        val profiles = settingRepository.fetchPlatformV2s()
        val next = policy.requested.participantUids.filterNot(attempted::contains).sorted().firstNotNullOfOrNull { uid ->
            profiles.singleOrNull { it.uid == uid && it.enabled && CombinedProviderEligibility.unavailableReason(it, original.userMessage.content, writer = true) == null }?.let { writer ->
                runCatching { writer to CombinedSuccessorSnapshot.prepare(original, failedRunId, writer) }.getOrNull()
            }
        } ?: return null
        val (writer, successor) = next
        val nextRaw = successor.encode()
        if (!chatRepository.canRetryCombined(policy.rootBudgetId)) return null
        val persisted = chatRepository.persistAgentRetry(
            PersistAgentRetryRequest(
                user,
                owner,
                AgentRunDraft(COMBINED_RUN_PREFIX + UUID.randomUUID().toString(), writer.uid, writer.compatibleType.name, successor.model, synthesisKey = dev.chungjungsoo.gptmobile.data.research.researchHash(nextRaw), synthesisSnapshot = nextRaw),
                successorOfRunId = failedRunId
            )
        )
        return AgentRunRequest(
            persisted.run.runId,
            chatId,
            persisted.assistantMessage,
            writer.copy(model = successor.model, temperature = successor.temperature, systemPrompt = listOf(successor.systemPrompt, combinedEditorialInstruction(successor.deliveryProtocol)).filter(String::isNotBlank).joinToString("\n\n"), disableAllTools = true, disableLocalTools = true),
            successor.history + successor.userMessage,
            successor.replies + listOf(emptyList()),
            requireNotNull(successor.acceptedTools).copy(allToolsDisabled = true, delegation = dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings(enabled = false), combined = successor.combined),
            successor.bindAcceptedStage()
        )
    }

    private companion object {
        const val COMBINED_RUN_PREFIX = "combined-synthesis:"
    }
}
