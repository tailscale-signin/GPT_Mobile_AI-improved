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
    private val researchSessionStore: ResearchSessionStore,
    private val collectionDecisions: CombinedCollectionDecisionStore? = null
) {
    suspend fun prepare(chatId: Int, userId: Int, rootBudgetId: String, trigger: String = "COLLECTION_HANDOFF"): AgentRunRequest? {
        val messages = chatRepository.fetchMessagesV2(chatId)
        val userMessage = messages.singleOrNull { it.id == userId && it.platformType == null } ?: return null
        val runs = chatRepository.observeAgentRuns(chatId).first().associateBy { it.runId }
        val allResponses = messages.filter { it.linkedMessageId == userId && it.platformType != null && (it.currentRunId != null || it.content.isNotBlank()) }
        if (allResponses.any { it.currentRunId?.startsWith(COMBINED_RUN_PREFIX) == true }) return null
        val acceptedRecords = allResponses.mapNotNull { runs[it.currentRunId] }.mapNotNull { CombinedAcceptedTurn.decode(it.synthesisSnapshot) }
        val accepted = acceptedRecords.firstOrNull { it.context.rootBudgetId == rootBudgetId } ?: return null
        if (acceptedRecords.any { it.context.rootBudgetId != rootBudgetId || it.context.requested.requestedPolicyDigest != accepted.context.requested.requestedPolicyDigest }) return null
        if (chatRepository.isCombinedTurnCancelled(rootBudgetId)) return null
        val members = if (accepted.context.requested.singlePass) chatRepository.combinedActiveMembers(chatId) ?: accepted.context.requested.participantUids.toSet() else accepted.context.requested.participantUids.toSet()
        val excludedUids = if (accepted.context.requested.singlePass) collectionDecisions?.excludedProfiles(rootBudgetId).orEmpty() else emptySet()
        val activeUids = accepted.context.requested.participantUids.filter { it in members && it !in excludedUids }
        if (activeUids.isEmpty()) return null
        // A failed profile must not block synthesis from the profiles that did
        // produce a response. Some provider failures never persist a linked message
        // row at all (for example, early dispatch failures or rate-limit rejection).
        // Keep only unambiguous rows for active participants and let the per-profile
        // failure policy below classify missing/failed contributors independently.
        val responses = activeUids.mapNotNull { uid ->
            allResponses.filter { it.platformType == uid }.singleOrNull()
        }
        val missingResponseUids = activeUids.filterNot { uid -> responses.any { it.platformType == uid } }
        if (missingResponseUids.isNotEmpty()) {
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                "CombinedSynthesis",
                "COLLECTION_RESPONSES_MISSING · count=${missingResponseUids.size} · profileUids=${missingResponseUids.joinToString(",")}"
            )
        }
        if (responses.isEmpty()) {
            chatRepository.markCombinedMergeUnavailable(
                chatId,
                userId,
                rootBudgetId,
                "No unambiguous contributor responses were saved. Retry the unavailable profiles."
            )
            return null
        }
        if (responses.any { runs[it.currentRunId]?.status !in setOf("COMPLETED", "FAILED", "INTERRUPTED", "CANCELED") }) return null
        val profiles = settingRepository.fetchPlatformV2s()
        if (accepted.context.requested.singlePass && collectionDecisions != null) {
            val failures = dev.chungjungsoo.gptmobile.presentation.ui.chat.combinedProfileFailures(
                userId,
                userMessage.content,
                responses,
                runs,
                profiles.associate { it.uid to it.name },
                accepted.context.requested.requestedModels
            ).mapNotNull { it.attemptId }.toSet()
            if (collectionDecisions.pending(rootBudgetId, failures).isNotEmpty()) return null
        }
        val turnRuns = runs.values.filter { it.chatId == userMessage.chatId && it.userMessageId == userMessage.id && !it.runId.startsWith(COMBINED_RUN_PREFIX) }
        val turnRunIds = turnRuns.mapTo(hashSetOf()) { it.runId }
        val turnEvents = chatRepository.observeToolEvents(userMessage.chatId).first().filter { it.runId in turnRunIds }
        // Consent to one recipient is not consent to a later writer. Private tool
        // results stay local unless a separate transfer authority is introduced.
        val privateRuns = turnEvents.filter { event ->
            event.status == "COMPLETED" &&
                !event.isError &&
                !event.result.isNullOrBlank() &&
                (event.connectionUidSnapshot != null || event.toolName.startsWith("health_") || event.toolName in setOf("memory", "device_location", "query_health_data", "query_memory", "search_memory", "read_memory", "read_file", "read_file_slice", "health_query", "health_read_records"))
        }.mapTo(hashSetOf()) { it.runId }
        val privateProfiles = turnRuns.filter { it.runId in privateRuns }.mapTo(hashSetOf()) { it.profileUid }
        val sources = (
            combinedSynthesisInputs(responses, runs, profiles.associate { it.uid to it.name }, accepted.context.requested.requestedModels, userMessage.content, requireComplete = false)
                ?: emptyList()
            ).map { source -> source.copy(localOnly = source.localOnly || source.platformUid in privateProfiles, claimExtractionVersion = if (accepted.context.requested.singlePass) 4 else source.claimExtractionVersion) }
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
        val chosenWriter = collectionDecisions?.chosenWriter(rootBudgetId)?.takeIf { it in activeUids }
        if (chosenWriter == null && combined.requested.preferredOwnerUid in excludedUids) {
            chatRepository.markCombinedMergeUnavailable(chatId, userId, rootBudgetId, "The selected primary was excluded from this turn. Choose a remaining primary to combine the saved replies.")
            return null
        }
        val pinnedPrimary = chosenWriter ?: combined.requested.preferredOwnerUid?.takeIf { it in activeUids } ?: activeUids.first()
        val acceptedUids = activeUids
        val enabledUids = profiles.filter { it.enabled }.mapTo(hashSetOf()) { it.uid }
        val writerOrder = CombinedOwnerResolver.synthesisDispatchOrder(
            sources,
            acceptedUids,
            pinnedPrimary,
            enabledUids,
            singlePass = combined.requested.singlePass
        ).ifEmpty {
            if (retainedPublicEvidence) {
                acceptedUids.sortedWith(compareBy<String> { it != pinnedPrimary }.thenBy { it })
            } else {
                emptyList()
            }
        }
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
        val authorizedCandidates = candidates.filter { sources.none { it.localOnly } || it.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM }
        // A writer change is a user decision. Capacity is checked only after the complete
        // evidence/history envelope is assembled, so there is no second, smaller input plan.
        val authorizedLead = if (combined.requested.singlePass) {
            authorizedCandidates.firstOrNull { it.uid == pinnedPrimary }
        } else {
            CombinedWriterPolicy.rank(authorizedCandidates, userMessage.content, pinnedPrimary).firstOrNull()
        }
        var localAssemblyReason: String? = "PRIVATE_SOURCES_LOCAL_ASSEMBLY".takeIf { sources.any { it.localOnly } && authorizedLead == null }
        if (combined.requested.singlePass && authorizedLead == null && localAssemblyReason == null) {
            chatRepository.markCombinedMergeUnavailable(chatId, userId, rootBudgetId, "The selected primary is unavailable for final writing. Review the primary selection; saved contributions remain available.")
            return null
        }
        val leadPlatform = authorizedLead ?: profiles.firstOrNull { it.uid in acceptedUids && it.uid == pinnedPrimary } ?: profiles.firstOrNull { it.uid in acceptedUids }
        if (leadPlatform == null) {
            chatRepository.markCombinedMergeUnavailable(userMessage.chatId, userMessage.id, combined.rootBudgetId)
            return null
        }
        val leadUid = leadPlatform.uid
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "CombinedSynthesis",
            "OWNER_SELECTED · preferred=$pinnedPrimary · selected=$leadUid · reason=${if (leadUid == pinnedPrimary) "PREFERRED_PRIMARY" else "SELECTED_FALLBACK"} · primaryCollection=${if (responses.any { it.platformType == pinnedPrimary }) "SAVED" else "MISSING"} · usable=${sources.size}"
        )
        val leadMessage = responses.singleOrNull { it.platformType == leadUid }
        val evidenceAnchor = leadMessage ?: responses.firstOrNull() ?: return null
        if (evidenceAnchor.id <= 0) return null

        val synthesisRunId = COMBINED_RUN_PREFIX + UUID.randomUUID().toString()

        val normalizedResearchTask = dev.chungjungsoo.gptmobile.data.research.researchHash(userMessage.content.trim())
        val researchTurnId = "${evidenceAnchor.chatId}:${userMessage.id}"
        val turnSessions = researchSessionStore.exportTurn(evidenceAnchor.chatId, setOf(researchTurnId)).filter { session ->
            session.turnId == researchTurnId &&
                dev.chungjungsoo.gptmobile.data.research.researchHash(session.snapshot.task.trim()) == normalizedResearchTask
        }
        // Never widen a source partition just because a contributor finished later.
        // If the primary had no collection run, do not borrow another profile's authority.
        val leadAuthority = leadMessage?.let { primaryMessage ->
            turnSessions.lastOrNull { it.runId == primaryMessage.currentRunId }?.authority
        }
        val sharedEvidence = dev.chungjungsoo.gptmobile.data.research.ResearchCorpus.freezeAll(
            dev.chungjungsoo.gptmobile.data.research.authorizedResearchTransfer(turnSessions, leadAuthority)
        )
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
        val answerContract = dev.chungjungsoo.gptmobile.data.agent.CombinedAnswerPolicy.from(userMessage.content, historicalUsers.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.content })
        val permitsLocalAssembly = sources.isNotEmpty() && answerContract.maximumWords == null && answerContract.minimumWords == null && answerContract.targetWords == null && !answerContract.singleExample && answerContract.requiredFormat == null && answerContract.requestedItems == null
        if (localAssemblyReason != null && !permitsLocalAssembly) {
            chatRepository.markCombinedMergeUnavailable(chatId, userId, rootBudgetId, "Saved personal sources remain on this device. A local source assembly cannot satisfy the requested output constraints.")
            return null
        }
        val editorialContext = combined.forRole(dev.chungjungsoo.gptmobile.data.agent.CombinedRunRole.SYNTHESIS, leadUid)
        val targetRevisionId = UUID.randomUUID().toString()
        val deliveryProtocol = "PASSAGES_V3"
        val outputSettings = if (combined.requested.singlePass) settingRepository.getFeatureSettings().tokenBudget.normalized() else null
        val acknowledgedFailures = collectionDecisions?.decisions?.value?.get(rootBudgetId)?.acknowledgedAttempts.orEmpty()
        val assembled = try {
            val writerContext = if (combined.requested.singlePass) {
                if ((historicalUsers + historicalReplies.flatten() + userMessage).any { it.attachments.isNotEmpty() }) {
                    chatRepository.prepareCombinedWriterContext(historicalUsers, historicalReplies, userMessage, leadPlatform)
                } else {
                    CombinedWriterContext.capture(historicalUsers, historicalReplies, userMessage, leadPlatform)
                }
            } else {
                null
            }
            withContext(Dispatchers.Default) {
                val basePrompt = combinedSynthesisPrompt(userMessage.content, attributedSources, sharedEvidence, evidenceLedger, turnContext, combined = editorialContext, answerContract = answerContract, deliveryProtocol = deliveryProtocol, targetRevisionId = targetRevisionId)
                val prompt = writerContext?.bindPrompt(basePrompt, userMessage) ?: basePrompt
                val writerPlan = if (combined.requested.singlePass && localAssemblyReason == null) {
                    try {
                        CombinedWriterRequestPlan.freeze(
                            leadPlatform, userMessage.content, prompt,
                            combinedWriterSystemPrompt(leadPlatform.systemPrompt, deliveryProtocol, userMessage.content, combined.requested.appendContinuations),
                            writerContext?.historyText() ?: combinedWriterHistory(historicalUsers, historicalReplies, leadPlatform.uid),
                            deliveryProtocol,
                            editorialContext.plan?.finalOutputTokens ?: 0,
                            outputSettings?.outputTokens,
                            listOfNotNull(outputSettings?.contextTokens, outputSettings?.profileContextCeilings?.get(leadUid)).minOrNull(),
                            writerContext,
                            appendContinuations = combined.requested.appendContinuations
                        )
                    } catch (capacity: CombinedInputCapacityException) {
                        if (!permitsLocalAssembly) throw capacity
                        localAssemblyReason = "WRITER_CONTEXT_CAPACITY_LOCAL_ASSEMBLY"
                        null
                    }
                } else {
                    null
                }
                val lineage = dev.chungjungsoo.gptmobile.data.agent.ContributionLineage.freeze(selectedUids, attributedSources)
                val inventory = dev.chungjungsoo.gptmobile.data.agent.CombinedClaimInventory.freeze(attributedSources)
                prompt to dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot(
                    version = 6,
                    dispatchIdentity = if (combined.requested.singlePass) combinedDigest("$chatId:$userId:$rootBudgetId:$leadUid:${leadPlatform.model}:" + attributedSources.sortedBy { it.platformUid }.joinToString("|") { it.platformUid + ":" + combinedDigest(it.content) }) else "",
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
                    contributionsTransferable = attributedSources.none { it.localOnly } && turnSessions.all { session -> session.snapshot.sources.all { it.publicCapture && it.retrievalReceipt.isNotBlank() } },
                    writerRequestPlan = writerPlan,
                    acknowledgedFailureAttempts = acknowledgedFailures,
                    writerContext = writerContext,
                    localAssemblyReason = localAssemblyReason
                ).seal().encode()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: IllegalArgumentException) {
            chatRepository.markCombinedMergeUnavailable(chatId, userId, rootBudgetId, failure.message)
            return null
        } catch (failure: IllegalStateException) {
            chatRepository.markCombinedMergeUnavailable(chatId, userId, rootBudgetId, failure.message)
            return null
        }
        val (synthesisPrompt, synthesisSnapshot) = assembled
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "CombinedSynthesis",
            "SYNTHESIS_INPUT_FROZEN · trigger=${trigger.take(64)} · runId=$synthesisRunId · sourceCount=${attributedSources.size} · profileUids=${attributedSources.map { it.platformUid }.distinct().joinToString(",")} · candidateUnits=${CombinedClaimSemantics.records(attributedSources).size} · promptChars=${synthesisPrompt.length} · protocol=$deliveryProtocol"
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
        // Keep the primary's contributor row when it exists. If collection failed
        // before creating one, allocate a new assistant slot instead of either
        // abandoning synthesis or overwriting another profile's contribution.
        val assistantSlot = leadMessage?.copy(combinedSources = attributedSources)
            ?: dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(
                chatId = userMessage.chatId,
                content = "",
                platformType = leadUid,
                linkedMessageId = userMessage.id,
                combinedSources = attributedSources
            )
        val persisted = chatRepository.persistAgentRetry(
            PersistAgentRetryRequest(
                userMessage = userMessage,
                assistantMessage = assistantSlot,
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
        if (authorizedLead == null || frozen.localAssemblyReason != null) {
            chatRepository.publishCombinedEvidenceFallback(persisted.run.runId)
            return null
        }
        return AgentRunRequest(
            runId = persisted.run.runId,
            chatId = persisted.assistantMessage.chatId,
            assistantMessage = persisted.assistantMessage,
            platform = leadPlatform.copy(
                systemPrompt = combinedWriterSystemPrompt(leadPlatform.systemPrompt, deliveryProtocol, userMessage.content, combined.requested.appendContinuations),
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

    /** Called only by the durable wait coordinator after claiming the known-rejected cursor. */
    suspend fun prepareAppendResume(chatId: Int, priorRunId: String, decision: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision): AgentRunRequest? {
        if (!dev.chungjungsoo.gptmobile.BuildConfig.COMBINED_APPEND_CONTINUATIONS || !decision.knownNotAccepted || decision.retryAt > System.currentTimeMillis()) return null
        val prior = chatRepository.observeAgentRuns(chatId).first().singleOrNull { it.runId == priorRunId && it.status == "WAITING_PROVIDER_LIMIT" } ?: return null
        if (prior.profileUid != decision.profileUid || prior.modelSnapshot != decision.model) return null
        val profile = settingRepository.fetchPlatformV2s().singleOrNull { it.uid == prior.profileUid && it.enabled } ?: return null
        val resumed = chatRepository.prepareCombinedAppendResume(priorRunId, profile, decision) ?: return null
        val context = resumed.combined ?: return null
        if (!context.requested.appendContinuations || chatRepository.isCombinedTurnCancelled(context.rootBudgetId)) return null
        val messages = chatRepository.fetchMessagesV2(chatId)
        val owner = messages.singleOrNull { it.id == prior.assistantMessageId && it.currentRunId == priorRunId && it.ownershipFence == prior.ownershipFence } ?: return null
        val user = messages.singleOrNull { it.id == prior.userMessageId && it == resumed.userMessage } ?: return null
        val raw = resumed.encode()
        val persisted = chatRepository.persistAgentRetry(
            PersistAgentRetryRequest(
                user,
                owner,
                AgentRunDraft(COMBINED_RUN_PREFIX + UUID.randomUUID().toString(), profile.uid, prior.providerSnapshot, resumed.model, synthesisKey = dev.chungjungsoo.gptmobile.data.research.researchHash(raw), synthesisSnapshot = raw),
                resumeCombinedFrom = priorRunId,
                automaticAppendDecision = decision
            )
        )
        return AgentRunRequest(
            persisted.run.runId,
            chatId,
            persisted.assistantMessage,
            profile.copy(model = resumed.model, temperature = resumed.temperature, systemPrompt = combinedWriterSystemPrompt(resumed.systemPrompt, resumed.deliveryProtocol, resumed.userMessage.content, (resumed.writerRequestPlan?.version ?: 1) >= 2 && context.requested.appendContinuations), disableAllTools = true, disableLocalTools = true),
            resumed.history + resumed.userMessage,
            resumed.replies + listOf(emptyList()),
            requireNotNull(resumed.acceptedTools).copy(allToolsDisabled = true, delegation = dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings(enabled = false), combined = context),
            resumed.bindAcceptedStage()
        )
    }

    suspend fun prepareFallback(chatId: Int, failedRunId: String, userRequested: Boolean = false): AgentRunRequest? {
        val runs = chatRepository.observeAgentRuns(chatId).first()
        val prior = runs.singleOrNull { it.runId == failedRunId } ?: return null
        val raw = prior.synthesisSnapshot ?: return null
        if (dev.chungjungsoo.gptmobile.data.research.researchHash(raw) != prior.synthesisKey) return null
        val original = runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<CombinedInputSnapshot>(raw) }.getOrNull() ?: return null
        val policy = original.combined ?: return null
        if (prior.terminalError?.contains("QUEUE_JOB_PENDING") == true) return null
        if (policy.requested.singlePass && !userRequested) return null
        if (!policy.requested.singlePass && !chatRepository.canRetryCombined(policy.rootBudgetId)) return null
        val messages = chatRepository.fetchMessagesV2(chatId)
        val owner = messages.singleOrNull { it.id == prior.assistantMessageId && it.currentRunId == prior.runId && it.ownershipFence == prior.ownershipFence } ?: return null
        val failure = owner.timeline.lastOrNull { it.combinedFailure != null }?.combinedFailure
        if (userRequested && policy.requested.singlePass) {
            if (prior.status !in setOf("FAILED", "INTERRUPTED", "CANCELED", "COMPLETED")) return null
        } else if (prior.status !in setOf("FAILED", "INTERRUPTED") || !(failure?.rotateWriter ?: permitsCombinedWriterFallback(prior.status, prior.terminalError))) {
            return null
        }
        val user = messages.singleOrNull { it.id == prior.userMessageId && it.platformType == null } ?: return null
        if (user != original.userMessage) return null
        val attempted = runs.filter { it.userMessageId == prior.userMessageId && it.synthesisKey != null }.mapTo(hashSetOf()) { it.profileUid }
        val profiles = settingRepository.fetchPlatformV2s()
        val outputSettings = if (policy.requested.singlePass) settingRepository.getFeatureSettings().tokenBudget.normalized() else null
        val next = CombinedWriterPolicy.rank(profiles.filter { it.uid in policy.requested.participantUids && it.uid !in attempted && it.enabled }.map { it.copy(model = policy.requested.requestedModels[it.uid] ?: it.model) }, original.userMessage.content, policy.requested.preferredOwnerUid, dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(original.prompt), policy.requested.appendContinuations).firstNotNullOfOrNull { ranked ->
            val writer = profiles.single { it.uid == ranked.uid }
            runCatching { writer to CombinedSuccessorSnapshot.prepare(original, failedRunId, writer, outputSettings?.outputTokens, listOfNotNull(outputSettings?.contextTokens, outputSettings?.profileContextCeilings?.get(writer.uid)).minOrNull()) }.getOrNull()
        } ?: return null
        val (writer, successor) = next
        val nextRaw = successor.encode()
        if (!policy.requested.singlePass && !chatRepository.canRetryCombined(policy.rootBudgetId)) return null
        if (policy.requested.singlePass && policy.requested.acceptedResearch != null) {
            val root = original.acceptedTask?.rootWorkId ?: "${user.chatId}:${user.id}"
            researchSessionStore.execution(root, policy.persistBudget)?.takeIf { it.attempt.state.terminal }?.let {
                researchSessionStore.resumeExecution(root, successor.snapshotId, policy.persistBudget)
            }
        }
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
            writer.copy(model = successor.model, temperature = successor.temperature, systemPrompt = combinedWriterSystemPrompt(successor.systemPrompt, successor.deliveryProtocol, successor.userMessage.content, (successor.writerRequestPlan?.version ?: 1) >= 2 && successor.combined?.requested?.appendContinuations == true), disableAllTools = true, disableLocalTools = true),
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
