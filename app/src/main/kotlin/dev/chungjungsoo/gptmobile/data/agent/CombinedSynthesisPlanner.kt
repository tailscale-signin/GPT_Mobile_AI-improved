package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveCombinedSources
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
        val sources = combinedSynthesisInputs(responses, runs, profiles.associate { it.uid to it.name }, accepted.context.requested.requestedModels)
        if (sources.isNullOrEmpty()) {
            chatRepository.markCombinedMergeUnavailable(chatId, userId, rootBudgetId)
            return null
        }
        val combined = accepted.context.copy(chatId = userMessage.chatId)
        val pinnedPrimary = combined.requested.preferredOwnerUid
        val acceptedUids = combined.requested.participantUids
        val candidates = CombinedOwnerResolver.eligibleOrder(
            sources,
            acceptedUids,
            pinnedPrimary,
            profiles.filter { it.enabled }.mapTo(hashSetOf()) { it.uid }
        ).mapNotNull { uid ->
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
        val leadPlatform = candidates.filter { sources.none { it.localOnly } || it.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM }.firstOrNull { platform ->
            if (platform.compatibleType != dev.chungjungsoo.gptmobile.data.model.ClientType.FREE) {
                true
            } else {
                val freeProvider = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.fromApiUrl(platform.apiUrl)
                    ?: return@firstOrNull false
                dev.chungjungsoo.gptmobile.data.network.FreeAiRequestLimiter.shared.cooldownUntilMillis(freeProvider) == null
            }
        }
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
        val turnSessions = researchSessionStore.exportChat(leadMessage.chatId).filter { session ->
            session.turnId == researchTurnId &&
                dev.chungjungsoo.gptmobile.data.research.researchHash(session.snapshot.task.trim()) == normalizedResearchTask
        }
        // Never widen a source partition just because a contributor finished later.
        val leadAuthority = turnSessions.lastOrNull { it.runId == leadMessage.currentRunId }?.authority
            ?: turnSessions.map { it.authority }.distinct().singleOrNull()
        val sharedEvidence = dev.chungjungsoo.gptmobile.data.research.ResearchCorpus.freezeAll(
            turnSessions.filter { it.authority == leadAuthority }.map { it.snapshot }
        )
        val turnRuns = runs.values.filter { it.chatId == userMessage.chatId && it.userMessageId == userMessage.id && !it.runId.startsWith(COMBINED_RUN_PREFIX) }
        val turnRunIds = turnRuns.mapTo(hashSetOf()) { it.runId }
        // Read the committed event rows rather than racing the UI's asynchronous tool-event snapshot.
        val turnEvents = chatRepository.observeToolEvents(userMessage.chatId).first().filter { it.runId in turnRunIds }
        val evidenceLedger = withContext(Dispatchers.Default) {
            dev.chungjungsoo.gptmobile.data.agent.CombinedEvidenceLedger.freeze(sharedEvidence, turnEvents, turnRuns.associate { it.runId to it.profileUid })
        }
        val turnContext = dev.chungjungsoo.gptmobile.data.agent.CombinedTurnContext.capture(userMessage.createdAt)
        var attributedSources = combinedSourcesWithEvidence(sources, sharedEvidence).map { source ->
            val owned = evidenceLedger.records.filter { source.platformUid in it.profileUids || source.content.contains(it.url) }
            source.copy(sourceIds = (source.sourceIds + owned.map { it.id }).distinct(), evidenceRefs = (source.evidenceRefs + owned.map { it.url }).distinct())
        }
        val fullHistory = messages
        require(fullHistory.any { it.id == userMessage.id && it.platformType == null }) { "The Combined user turn is no longer available." }
        val historicalUsers = fullHistory.filter { it.platformType == null }.takeWhile { it.id != userMessage.id }
        val historicalReplies = historicalUsers.map { prior -> fullHistory.filter { it.linkedMessageId == prior.id && it.platformType != null } }
        val inheritedOrigins = historicalReplies.flatten().flatMap { previous ->
            previous.effectiveCombinedSources().flatMap { source -> source.ancestryIds + "contribution:${source.sourceDigest.ifBlank { dev.chungjungsoo.gptmobile.data.research.researchHash(source.content) }}" }
        }.toSet()
        attributedSources = attributedSources.map { it.copy(ancestryIds = it.ancestryIds + inheritedOrigins) }
        val selectedUids = acceptedUids
        val answerContract = dev.chungjungsoo.gptmobile.data.agent.CombinedAnswerPolicy.from(userMessage.content)
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
                selectedHistoryRevisionIds = historicalReplies.flatten().mapNotNull { it.effectiveRunId() }.distinct()
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
                freezeCombinedSources = true
            )
        )
        // The durable run now owns this answer slot. No automatic retry may
        // replace it, even if UI publication or provider dispatch subsequently fails.
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "CombinedSynthesis",
            "SYNTHESIS_RUN_PERSISTED · runId=$synthesisRunId · leadProfileUid=$leadUid · sourceCount=${attributedSources.size} · evidenceRecords=${evidenceLedger.records.size}"
        )

        return AgentRunRequest(
            runId = persisted.run.runId,
            chatId = persisted.assistantMessage.chatId,
            assistantMessage = persisted.assistantMessage,
            platform = leadPlatform.copy(
                systemPrompt = listOfNotNull(leadPlatform.systemPrompt, combinedEditorialInstruction(deliveryProtocol)).joinToString("\n\n"),
                disableAllTools = true,
                disableLocalTools = true
            ),
            userMessages = historicalUsers + userMessage.copy(content = synthesisPrompt),
            assistantMessages = historicalReplies + listOf(emptyList()),
            chatToolConfig = accepted.tools.copy(
                allToolsDisabled = true,
                delegation = dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings(enabled = false),
                combined = editorialContext
            )
        )
    }

    private companion object {
        const val COMBINED_RUN_PREFIX = "combined-synthesis:"
    }
}
