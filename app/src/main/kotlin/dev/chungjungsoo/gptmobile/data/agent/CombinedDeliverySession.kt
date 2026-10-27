package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Delivery, instruction compliance and evidence support have independent, revision-bound outcomes. */
internal class CombinedDeliverySession(
    private val base: AgentProviderSession,
    private val sources: List<CombinedModelResponse>,
    private val maxCharacters: Int,
    private val committedPrefix: String = "",
    private val preserveSourceContent: Boolean = false,
    private val validateAccess: suspend () -> Unit,
    private val onStage: suspend (CombinedDeliveryPhase) -> Unit = {},
    private val onCoverage: suspend (CombinedContentCoverage.Result, Boolean) -> Unit = { _, _ -> },
    private val maxRepairRounds: Int = 1,
    private val localConversationAnswer: String? = null,
    private val originalRequest: String = "",
    private val evidence: CombinedEvidenceLedger = CombinedEvidenceLedger(),
    private val turnContext: CombinedTurnContext? = null,
    private val structured: Boolean = false,
    private val recordProtocol: Boolean = false,
    private val combined: CombinedExecutionContext? = null,
    private val answerContract: CombinedAnswerPolicy? = null,
    private val layoutContract: CombinedLayoutContract? = null,
    private val revisionId: String = "",
    private val researchObligations: List<CombinedResearchObligation> = emptyList(),
    private val verify: suspend (CombinedVerificationRequest) -> CombinedEvidenceLedger? = { null },
    private val replace: suspend (CombinedReplacementCandidate) -> Boolean = { false },
    private val repairDraft: (suspend (CombinedRepairRequest) -> AgentProviderSession)? = null,
    private val repair: suspend (String, List<CombinedContentCoverage.Unit>) -> AgentProviderSession
) : AgentProviderSession {
    override val handlesToolsInternally = false

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        val policy = answerContract ?: CombinedAnswerPolicy.from(originalRequest)
        var currentEvidence = evidence
        var currentRevision = revisionId.ifBlank { UUID.randomUUID().toString() }
        val coverage = withContext(Dispatchers.Default) { CombinedContentCoverage(sources, originalRequest, preserveSourceContent) }
        val constrained = policy.maximumWords != null || policy.minimumWords != null || policy.targetWords != null || policy.singleExample || policy.requiresCurrentEvidence || policy.requiredFormat != null || policy.task?.subjectConfidence == "EXPLICIT"
        val singlePass = combined?.requested?.singlePass == true
        val progressive = !singlePass && !preserveSourceContent && structured && !constrained && committedPrefix.isEmpty()
        AppLogRecorder.record(
            "CombinedSynthesis",
            "SYNTHESIS_DELIVERY_STARTED · sourceCount=${sources.size} · protocol=${if (recordProtocol) {
                "PASSAGES_V3"
            } else if (structured) {
                "PASSAGES_V2"
            } else {
                "PLAIN_TEXT"
            }} · progressive=$progressive · constrained=$constrained · revision=$currentRevision · policy=${combined?.requested?.requestedPolicyDigest.orEmpty()}"
        )
        data class Candidate(val text: String, val terminal: ProviderEvent?, val decoder: CombinedPassageDecoder?, val assembledLocally: Boolean = false, val usage: CombinedReportedUsage? = null)
        suspend fun collect(candidate: AgentProviderSession, publish: Boolean): Candidate {
            val text = StringBuilder()
            val decoder = if (structured) CombinedPassageDecoder(sources, currentEvidence, maxCharacters, strictRecords = recordProtocol) else null
            var terminal: ProviderEvent? = null
            var usage: CombinedReportedUsage? = null
            flow { emitAll(candidate.streamRound(emptyList(), emptyList())) }.catch { error ->
                if (error is CancellationException) throw error
                emit(ProviderEvent.Failed("Combined synthesis stream failed. The partial answer and original contributions are retained."))
            }.takeWhile { event ->
                when {
                    terminal is ProviderEvent.Failed -> false
                    event is ProviderEvent.TextDelta && text.length.toLong() + event.text.length > maxCharacters.toLong() * (if (structured) 4 else 1) + (if (structured) 65_536 else 0) -> {
                        terminal = ProviderEvent.Failed("COMBINED_DELIVERY_LIMIT: The configured delivery allowance was reached.")
                        false
                    }
                    event is ProviderEvent.ToolCall || event is ProviderEvent.ToolResult -> {
                        terminal = ProviderEvent.Failed("Combined editing cannot execute tools.")
                        false
                    }
                    else -> true
                }
            }.collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> {
                        text.append(event.text)
                        val deltas = withContext(Dispatchers.Default) { decoder?.accept(event.text) ?: listOf(event.text) }
                        if (publish) {
                            validateAccess()
                            deltas.filter(String::isNotEmpty).forEach { emit(ProviderEvent.TextDelta(it)) }
                            if (deltas.isNotEmpty() && decoder != null) emit(ProviderEvent.CombinedQuality(CombinedQualityReport(passages = decoder.passages.toList(), turnContext = turnContext, revisionId = currentRevision)))
                        }
                    }
                    is ProviderEvent.Usage -> {
                        if (event.inputTokens != null || event.outputTokens != null) {
                            usage = CombinedReportedUsage(event.inputTokens?.takeIf { it >= 0 } ?: usage?.inputTokens, event.outputTokens?.takeIf { it >= 0 } ?: usage?.outputTokens, if (event.inputTokens != null) event.inputSource else usage?.inputSource ?: "unknown", if (event.outputTokens != null) event.outputSource else usage?.outputSource ?: "unknown")
                        }
                        emit(event)
                    }
                    is ProviderEvent.Failed -> terminal = event
                    ProviderEvent.Completed -> if (terminal == null) terminal = event
                    else -> emit(event)
                }
            }
            val tail = withContext(Dispatchers.Default) { decoder?.accept("", final = true).orEmpty() }
            if (publish && tail.isNotEmpty()) {
                validateAccess()
                tail.forEach { emit(ProviderEvent.TextDelta(it)) }
            }
            val rawAnswer = decoder?.answer() ?: text.toString().substringBefore(COMBINED_RECOVERY_MARKER).trimEnd()
            // Structured passage offsets are part of the attribution contract; the
            // decoder owns their deduplication. Apply paragraph cleanup only to plain text.
            val answer = if (decoder == null) {
                CombinedTextDeduplicator.removeRepeatedParagraphs(rawAnswer)
            } else {
                rawAnswer
            }
            return Candidate(answer, terminal, decoder, usage = usage)
        }
        suspend fun phase(stage: CombinedDeliveryPhase) {
            onStage(stage)
            emit(ProviderEvent.Notice(stage.label))
        }
        phase(CombinedDeliveryPhase.VALIDATING)
        validateAccess()
        if (!singlePass && policy.requiresCurrentEvidence && currentEvidence.records.none { it.readable && it.article && it.dated }) {
            if (combined != null && !policy.noSearch) {
                val acquired = verify(CombinedVerificationRequest("", currentRevision, listOf(CombinedVerificationNeed("current-article", originalRequest, "A dated, readable article is required by the current-news request")), currentEvidence, originalRequest))
                validateAccess()
                if (acquired != null) currentEvidence = currentEvidence.mergedWith(acquired)
            }
            if (currentEvidence.records.none { it.readable && it.article && it.dated }) {
                val allowance = policy.maximumWords ?: policy.targetWords ?: Int.MAX_VALUE
                val answer = if (allowance < 12) "Unverified." else "I couldn’t verify the newest story from the available article evidence."
                val report = CombinedQualityReport(version = 6, answerDigest = researchHash(answer), complete = true, status = "COMPLETED_WITH_ISSUES", wordCount = visibleWordCount(answer), issues = listOf("CURRENT_ARTICLE_EVIDENCE_UNAVAILABLE"), sourceUnits = coverage.units.size, lexicalMissing = coverage.units.size, turnContext = turnContext, deliveredScopeStatus = "COMPLETED_WITH_LIMITS", verificationStatus = "EVIDENCE_UNAVAILABLE", revisionId = currentRevision, consideredProfileUids = sources.mapTo(linkedSetOf()) { it.platformUid }, finalAssessment = true, assessmentStage = "FINAL_DISPLAY", displayedCharacters = answer.length, displayableDraft = true, protocolComplete = true)
                emit(ProviderEvent.CombinedAnswer(answer, report))
                emit(ProviderEvent.Completed)
                return@flow
            }
        }
        phase(CombinedDeliveryPhase.COMBINING)
        var accepted = collect(base, progressive)
        validateAccess()
        val deferred = (accepted.terminal as? ProviderEvent.Failed)?.takeIf { combined?.requested?.appendContinuations == true && it.providerLimit?.knownNotAccepted == true }
        if (deferred != null) {
            // Validated records already live in the durable journal. A scheduled append
            // remains staged and cannot publish a partial fallback as the final answer.
            emit(deferred)
            return@flow
        }
        if (!accepted.text.startsWith(committedPrefix)) {
            emit(ProviderEvent.Failed("Combined continuation cannot replace the committed prefix without a new revision. Original contributions remain available."))
            return@flow
        }
        suspend fun assess(candidate: Candidate): CombinedFinalAssessment = withContext(Dispatchers.Default) {
            // Exact application-generated status text is checked against the frozen receipts.
            // Provider names with numbers are entity identities, not competing factual quantities.
            val result = if (localConversationAnswer != null && candidate.text == localConversationAnswer && CurrentRequestContract.from(originalRequest).intent in setOf("GREETING", "TEST", "PROVIDER_TEST")) {
                CombinedFinalAssessment(listOf(CombinedAnswerCheck("LOCAL_CONVERSATION_RECEIPTS", "DELIVERY", "PASS")), emptyList(), emptyList(), emptyList(), emptyList())
            } else {
                CombinedFinalCheck.check(candidate.text, sources, currentEvidence, policy, if (candidate.assembledLocally) emptyList() else candidate.decoder?.passages.orEmpty(), candidate.decoder?.decisions.orEmpty(), currentRevision)
            }
            val checked = result.copy(checks = result.checks + researchQuestionChecks(candidate.text, researchObligations) + layoutContract?.checks(candidate.text).orEmpty())
            val invalid = checked.receipts.filterNot { CombinedEvidenceVerifier.validateReceipt(candidate.text, currentRevision, currentEvidence, it) }
            if (invalid.isEmpty()) checked else checked.copy(checks = checked.checks + CombinedAnswerCheck("INVALID_REVISION_EVIDENCE_RECEIPT", "PROVENANCE", "FAIL", true, invalid.mapTo(linkedSetOf()) { it.claimId }, "A support receipt did not validate against this answer revision and retained evidence."))
        }
        phase(CombinedDeliveryPhase.CHECKING)
        var assessment = assess(accepted)
        var violations = withContext(Dispatchers.Default) { policy.violations(accepted.text) }
        var coverageResult = withContext(Dispatchers.Default) { coverage.check(accepted.text) }
        var repairs = 0
        var repairAccepted = 0
        var repairRejected = 0
        var correctionRequired = assessment.correctionRequired || assessment.requiredFailures.isNotEmpty()
        val rejections = mutableListOf<String>()
        val verificationAttempts = linkedSetOf<String>()
        var noProgress = 0
        var repairSkippedReason: String? = if (singlePass) "SINGLE_PASS_LOCAL_CHECKS_ONLY" else null

        suspend fun report(candidate: Candidate, checked: CombinedFinalAssessment, final: Boolean): CombinedQualityReport {
            val unresolvedResearch = checked.checks.any { it.category == "RESEARCH_SCOPE" && it.result != "PASS" }
            val providerComplete = candidate.terminal == ProviderEvent.Completed
            val material = correctionRequired || checked.correctionRequired || checked.requiredFailures.isNotEmpty() || checked.checks.any { it.material && it.result == "FAIL" }
            val issues = (violations + listOfNotNull("NO_FINAL_OUTPUT".takeIf { candidate.text.isBlank() }) + candidate.decoder?.issues.orEmpty() + checked.issues + listOfNotNull("LEXICAL_COVERAGE_NOT_EVALUATED".takeIf { !coverageResult.evaluated }, "LEXICAL_COVERAGE_UNRESOLVED_HINTS".takeIf { coverageResult.missing.isNotEmpty() })).distinct()
            val framingValid = candidate.decoder?.issues.orEmpty().none { it in COMBINED_FRAMING_FAILURES }
            val delivered = when {
                candidate.text.isBlank() -> "FAILED"
                !providerComplete || !framingValid || material || violations.isNotEmpty() || unresolvedResearch -> "PARTIAL"
                issues.any { it != "LEXICAL_COVERAGE_UNRESOLVED_HINTS" } || checked.verificationNeeds.isNotEmpty() -> "COMPLETED_WITH_LIMITS"
                else -> "COMPLETED"
            }
            val mapping = withContext(Dispatchers.Default) { CombinedAnswerMappings.get(candidate.text, sources) }
            val meaningfulClaimIds = CombinedContentIndexes.get(sources).records.flatMapTo(linkedSetOf()) { it.candidateIds }
            val semanticCoverageVerified = meaningfulClaimIds.isEmpty() ||
                checked.checks.none { it.id.startsWith("SEMANTIC_CONTRIBUTION_UNRESOLVED:") }
            val mappedPassages = CombinedAnswerMappings.passages(candidate.text, mapping, if (candidate.assembledLocally) emptyList() else candidate.decoder?.passages.orEmpty())
            val represented = mapping.links.flatMapTo(linkedSetOf()) { it.profileUids }
            return CombinedQualityReport(
                version = 6,
                answerDigest = researchHash(candidate.text),
                claimMapping = mapping,
                complete = candidate.text.isNotBlank() && providerComplete && framingValid && !material && violations.isEmpty() && !unresolvedResearch && checked.requiredFailures.isEmpty() && semanticCoverageVerified,
                status = when {
                    candidate.text.isBlank() -> "FAILED"
                    material -> "CORRECTION_REQUIRED"
                    !providerComplete -> "INTERRUPTED"
                    delivered == "PARTIAL" -> "PARTIAL"
                    issues.isEmpty() -> "COMPLETED_UNVERIFIED"
                    else -> "COMPLETED_WITH_ISSUES"
                },
                wordCount = visibleWordCount(candidate.text), issues = issues, writerUsage = candidate.usage,
                passages = mappedPassages.map { passage -> passage.copy(evidenceStatus = CombinedEvidenceVerifier.assess(passage.passageId, candidate.text.substring(passage.start, passage.end), currentEvidence, passage.sourceIds).result) },
                decisions = checked.decisions,
                sourceUnits = coverageResult.total, lexicalMissing = coverageResult.missing.size,
                duplicatePassagesRemoved = candidate.decoder?.duplicatesRemoved ?: 0,
                repairRounds = repairs, repairAccepted = repairAccepted, repairRejected = repairRejected,
                repairSkippedReason = repairSkippedReason, turnContext = turnContext,
                deliveredScopeStatus = if (final) {
                    delivered
                } else if (material) {
                    "PARTIAL"
                } else {
                    "CHECKING"
                },
                verificationStatus = when {
                    checked.checks.any { it.material && it.result == "FAIL" && it.category !in setOf("REQUEST", "SCOPE", "RESEARCH_SCOPE") } -> "CORRECTION_REQUIRED"
                    checked.verificationNeeds.isNotEmpty() -> "NEEDS_VERIFICATION"
                    checked.receipts.any { it.evidenceIds.isNotEmpty() } -> "PASSAGES_CHECKED_WITH_LIMITS"
                    else -> "UNVERIFIED"
                },
                correctionRequired = material, revisionId = currentRevision,
                verificationReceipts = checked.receipts, supportEdges = checked.supportEdges,
                checks = checked.checks, verificationNeeds = checked.verificationNeeds,
                repairRejections = rejections.toList(), consideredProfileUids = sources.mapTo(linkedSetOf()) { it.platformUid },
                requestedPolicyDigest = combined?.requested?.requestedPolicyDigest.orEmpty(), effectivePlanDigest = combined?.plan?.effectivePlanDigest.orEmpty(),
                includedProfileUids = if (singlePass) sources.mapTo(linkedSetOf()) { it.platformUid } else emptySet(),
                representedProfileUids = represented,
                finalAssessment = final,
                assessmentStage = if (final) "FINAL_DISPLAY" else "WRITER_DRAFT",
                displayedCharacters = candidate.text.length,
                fullyLinkedClaims = mapping.claims.count { it.fullyLinked },
                partialSourceLinks = mapping.claims.count { it.status == "PARTIAL" },
                unresolvedSourceLinks = mapping.claims.count { it.status in setOf("UNMATCHED", "NOT_EVALUATED") },
                displayableDraft = candidate.text.isNotBlank(),
                protocolComplete = providerComplete && framingValid,
                identityLinkStatus = if (candidate.decoder?.issues.orEmpty().any { it in setOf("UNKNOWN_CLAIM_REFERENCE", "UNKNOWN_PROFILE_REFERENCE") }) "REJECTED_REFERENCES" else "KNOWN_OCCURRENCES",
                semanticSupportStatus = if (represented.isEmpty()) "UNESTABLISHED" else "CONSERVATIVE_TEXT_LINKS_ONLY",
                semanticCoverageVerified = semanticCoverageVerified,
                lengthCompliance = if (violations.any { it.contains("WORD") || it.contains("LENGTH") }) "UNMET" else "MET",
                topicCoverage = if (checked.checks.any { it.category in setOf("SCOPE", "RESEARCH_SCOPE") && it.result != "PASS" }) "UNRESOLVED" else "LOCAL_CHECKS_PASSED",
                sourceLinkStatus = when {
                    !mapping.evaluated -> "NOT_FULLY_EVALUATED"
                    mapping.claims.isNotEmpty() && mapping.claims.all { it.fullyLinked } -> "ALL_CLAIMS_TEXT_LINKED"
                    represented.isEmpty() -> "UNESTABLISHED"
                    else -> "PARTIAL_TEXT_MATCHES"
                },
                sourceLinkRejections = checked.checks.filter { it.id.startsWith("UNESTABLISHED_FINAL_CLAIM_LINK") }.groupingBy { "CLAIM_OR_QUALIFIER_NOT_ALIGNED" }.eachCount()
            )
        }

        while (!singlePass && accepted.terminal == ProviderEvent.Completed) {
            validateAccess()
            correctionRequired = correctionRequired || assessment.correctionRequired || assessment.requiredFailures.isNotEmpty()
            if (correctionRequired) {
                emit(ProviderEvent.CombinedQuality(report(accepted, assessment, false)))
                emit(ProviderEvent.Notice("This draft has unresolved required checks or material support problems and needs correction. Its earlier assessment has been invalidated.", persistent = true))
            }
            val evidenceState = researchHash(currentEvidence.records.flatMap { record -> record.retainedExcerpts.map { record.id + ":" + it.digest + ":" + it.retrieval } }.sorted().joinToString("|"))
            val needs = assessment.verificationNeeds.filter { verificationAttempts.add(researchHash(it.proposition + "|" + it.reason + "|" + evidenceState)) }
            if (combined != null && !policy.noSearch && needs.isNotEmpty()) {
                val previousNeeds = assessment.verificationNeeds.size
                // Each request has a finite claim list. The controller authorizes actual work against the root budget.
                for (batch in needs.chunked(8)) {
                    validateAccess()
                    val acquired = verify(CombinedVerificationRequest(researchHash(accepted.text), currentRevision, batch, currentEvidence, originalRequest))
                    validateAccess()
                    if (acquired != null) currentEvidence = currentEvidence.mergedWith(acquired)
                }
                assessment = assess(accepted)
                correctionRequired = correctionRequired || assessment.correctionRequired || assessment.requiredFailures.isNotEmpty()
                // Continue only for a shrinking set of concrete unresolved claims. Merely opening
                // more pages does not justify another batch, and known errors proceed to revision.
                if (!correctionRequired && assessment.verificationNeeds.isNotEmpty() && assessment.verificationNeeds.size < previousNeeds) continue
            }
            val coverageRepair = repairs == 0 && policy.targetWords != null && !policy.singleExample && coverageResult.missing.isNotEmpty()
            val semanticRepair = assessment.requiredFailures.isNotEmpty() || combined != null && assessment.verificationNeeds.isNotEmpty()
            val framingRepair = accepted.decoder?.issues.orEmpty().any { it in COMBINED_FRAMING_FAILURES }
            if (violations.isEmpty() && !coverageRepair && !semanticRepair && !framingRepair && !correctionRequired) {
                repairSkippedReason = "NO_REMAINING_REQUIRED_CORRECTION"
                break
            }
            if (maxRepairRounds <= 0 || combined?.plan?.requiredCorrectionAllowed == false) {
                repairSkippedReason = "CORRECTION_CAPACITY_UNAVAILABLE"
                break
            }
            if (repairs >= maxRepairRounds || combined == null && (progressive || committedPrefix.isNotEmpty())) {
                repairSkippedReason = if (progressive || committedPrefix.isNotEmpty()) "LEGACY_REVISION_REQUIRES_NEW_OWNER" else "LEGACY_REPAIR_ALLOWANCE_REACHED"
                break
            }
            if (noProgress >= 2) {
                repairSkippedReason = "NO_PROGRESS_AFTER_REQUIRED_REVISIONS"
                break
            }
            emit(ProviderEvent.CombinedQuality(report(accepted, assessment, false)))
            emit(ProviderEvent.Notice("Revising the complete answer to address the remaining checks."))
            repairs++
            val previousDigest = researchHash(accepted.text)
            val previousFailures = assessment.hardFailures.toSet()
            val hints = if (coverageRepair) coverageResult.missing else emptyList()
            val requestedRepair = CombinedRepairRequest(accepted.text, policy, (violations + assessment.requiredFailures + assessment.verificationNeeds.map { "Verify or qualify: ${it.proposition} (${it.reason})" } + assessment.checks.filter { it.category == "RESEARCH_SCOPE" && it.result != "PASS" }.map { "Address or explicitly disclose the evidence limitation for: ${it.message}" } + accepted.decoder?.issues.orEmpty()).distinct(), currentEvidence, hints.map { it.text }, currentRevision, combined?.forRole(CombinedRunRole.REPAIR), targetRevisionId = UUID.randomUUID().toString())
            val repairSession = withTimeoutOrNull(90_000L) {
                try {
                    repairDraft?.invoke(requestedRepair) ?: repair(accepted.text, hints)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    rejections += "REPAIR_PREPARATION_FAILED:${error.javaClass.simpleName}"
                    null
                }
            }
            val rewritten = repairSession?.let { collect(it, false) }
            validateAccess()
            if (rewritten != null) {
                val progress = CombinedContinuationProgress.assess(accepted.text, rewritten.text)
                if (progress != CombinedContinuationProgress.Result.ADVANCED) {
                    repairRejected++
                    rejections += "REPLACEMENT_MADE_NO_TEXT_PROGRESS:${progress.name}"
                    repairSkippedReason = "NO_PROGRESS_AFTER_REQUIRED_REVISION"
                    AppLogRecorder.record("CombinedSynthesis", "COMBINED_REPAIR_NO_PROGRESS · reason=${progress.name} · priorCharacters=${accepted.text.length} · candidateCharacters=${rewritten.text.length}")
                    break
                }
            }
            val previousRevision = currentRevision
            currentRevision = requestedRepair.targetRevisionId
            val rewrittenChecks = rewritten?.let { assess(it) }
            val rewrittenViolations = rewritten?.let { policy.violations(it.text) }.orEmpty()
            val protocolFailures = rewritten?.decoder?.issues.orEmpty().filter { it in COMBINED_FRAMING_FAILURES }
            val newMaterial = rewrittenChecks?.checks.orEmpty().any { it.material && it.result == "FAIL" && it.id !in previousFailures } || rewrittenChecks?.requiredFailures.orEmpty().any { it !in assessment.requiredFailures }
            val addedVerificationNeeds = rewrittenChecks?.verificationNeeds.orEmpty().any { next ->
                assessment.verificationNeeds.none { previous ->
                    next.proposition == previous.proposition ||
                        CombinedClaimMatcher.match(previous.proposition, next.proposition) >= CombinedClaimMatcher.Match.PARAPHRASE &&
                        CombinedClaimMatcher.match(next.proposition, previous.proposition) >= CombinedClaimMatcher.Match.PARAPHRASE
                }
            }
            val canAccept = rewritten != null &&
                rewritten.text.isNotBlank() &&
                rewritten.terminal == ProviderEvent.Completed &&
                rewrittenViolations.isEmpty() &&
                protocolFailures.isEmpty() &&
                !newMaterial &&
                (!addedVerificationNeeds || "TASK_SUBJECT_MISMATCH" in previousFailures) &&
                rewrittenChecks != null &&
                rewrittenChecks.requiredFailures.size <= assessment.requiredFailures.size &&
                (assessment.requiredFailures.isEmpty() || rewrittenChecks.requiredFailures.size < assessment.requiredFailures.size || (rewritten.text.length > accepted.text.length && rewrittenChecks.requiredFailures.toSet() == assessment.requiredFailures.toSet())) &&
                rewrittenChecks.hardFailures.none { it !in previousFailures } &&
                rewrittenChecks.requiredFailures.none { it !in assessment.requiredFailures } &&
                (rewrittenChecks.verificationNeeds.size <= assessment.verificationNeeds.size || "TASK_SUBJECT_MISMATCH" in previousFailures)
            if (canAccept && rewritten != null && rewrittenChecks != null) {
                val nextCoverage = withContext(Dispatchers.Default) { coverage.check(rewritten.text) }
                val priorCorrection = correctionRequired
                correctionRequired = rewrittenChecks.correctionRequired || rewrittenChecks.requiredFailures.isNotEmpty()
                val oldCoverage = coverageResult
                coverageResult = nextCoverage
                val oldViolations = violations
                violations = rewrittenViolations
                val candidateReport = report(rewritten, rewrittenChecks, true)
                val needsAtomicReplacement = progressive || committedPrefix.isNotEmpty()
                val committed = !needsAtomicReplacement || replace(CombinedReplacementCandidate(previousRevision, previousDigest, currentRevision, rewritten.text, candidateReport, currentEvidence.copy(supportEdges = rewrittenChecks.supportEdges), requestedRepair.failedChecks.joinToString(", ")))
                validateAccess()
                if (committed) {
                    repairAccepted++
                    noProgress = if (researchHash(rewritten.text) == previousDigest || rewrittenChecks.hardFailures.toSet() == previousFailures && rewrittenChecks.verificationNeeds.size >= assessment.verificationNeeds.size) noProgress + 1 else 0
                    accepted = rewritten
                    assessment = rewrittenChecks
                } else {
                    correctionRequired = priorCorrection
                    coverageResult = oldCoverage
                    violations = oldViolations
                    currentRevision = previousRevision
                    repairRejected++
                    rejections += "REPLACEMENT_OWNER_COMPARE_AND_SET_REJECTED"
                    repairSkippedReason = "NEWER_REVISION_OR_UNAVAILABLE_REPLACEMENT_OWNER"
                    break
                }
            } else {
                currentRevision = previousRevision
                repairRejected++
                rejections += when {
                    rewritten == null -> "REPLACEMENT_UNAVAILABLE"
                    rewritten.terminal != ProviderEvent.Completed -> "REPLACEMENT_INCOMPLETE"
                    rewritten.text.isBlank() -> "REPLACEMENT_EMPTY"
                    newMaterial -> "REPLACEMENT_MATERIAL_CHECK_FAILED:${rewrittenChecks?.issues.orEmpty().joinToString(",") }"
                    rewrittenViolations.isNotEmpty() -> "REPLACEMENT_CONTRACT_FAILED:${rewrittenViolations.joinToString(",") }"
                    protocolFailures.isNotEmpty() -> "REPLACEMENT_PROTOCOL_FAILED:${protocolFailures.joinToString(",") }"
                    addedVerificationNeeds || rewrittenChecks != null && rewrittenChecks.verificationNeeds.size > assessment.verificationNeeds.size -> "REPLACEMENT_ADDED_VERIFICATION_NEEDS"
                    else -> "REPLACEMENT_ADDED_FAILED_CHECKS"
                }
                emit(ProviderEvent.Notice(if (correctionRequired) "The correction did not pass its checks. The retained draft remains marked as needing correction." else "The revision did not pass its checks. The earlier draft was retained.", persistent = true))
                val targetedFormatting = rewritten != null && rewritten.terminal == ProviderEvent.Completed && rewrittenViolations.isNotEmpty() && protocolFailures.isEmpty() && !newMaterial
                if (targetedFormatting && repairs < maxRepairRounds) {
                    noProgress++
                    continue
                }
                repairSkippedReason = rejections.lastOrNull() ?: "REPAIR_REJECTED"
                break
            }
        }
        val writerText = accepted.text
        val writerMapping = withContext(Dispatchers.Default) { CombinedAnswerMappings.get(writerText, sources) }
        val writerAssessment = CombinedAssessmentSnapshot(
            researchHash(writerText),
            currentRevision,
            visibleWordCount(writerText),
            assessment.checks,
            assessment.decisions,
            writerMapping.claims.count { it.status in setOf("UNMATCHED", "NOT_EVALUATED") },
            writerMapping.claims.count { it.status == "PARTIAL" },
            assessment.verificationNeeds
        )
        var deterministicFallback = false
        if (accepted.text.isBlank() && committedPrefix.isEmpty()) {
            validateAccess()
            val fallback = withContext(Dispatchers.Default) { deterministicCombinedFallback(sources, maxCharacters, policy.maximumWords) }
            if (fallback != null) {
                accepted = accepted.copy(text = fallback, decoder = null, assembledLocally = true)
                assessment = assess(accepted)
                violations = withContext(Dispatchers.Default) { policy.violations(fallback) }
                correctionRequired = true
                deterministicFallback = true
            }
        }
        var ceilingEnforced = false
        val ceiling = policy.maximumWords
        if (!recordProtocol && !progressive && committedPrefix.isEmpty() && ceiling != null && visibleWordCount(accepted.text) > ceiling) {
            val bounded = withContext(Dispatchers.Default) { enforceCombinedWordCeiling(accepted.text, ceiling) }
            accepted = accepted.copy(text = bounded, assembledLocally = true)
            violations = policy.violations(bounded)
            assessment = assess(accepted)
            correctionRequired = assessment.correctionRequired || assessment.requiredFailures.isNotEmpty()
            ceilingEnforced = true
        }
        coverageResult = withContext(Dispatchers.Default) { coverage.check(accepted.text) }
        onCoverage(coverageResult, repairAccepted > 0)
        var finalReport = report(accepted, assessment, true).copy(
            writerAssessment = writerAssessment,
            writerAnswerDigest = researchHash(writerText),
            writerCharacters = writerText.length,
            locallyAddedCharacters = (accepted.text.length - writerText.length).coerceAtLeast(0),
            locallyRemovedCharacters = (writerText.length - accepted.text.length).coerceAtLeast(0)
        )
        // A historical survey must not be marked complete if the displayed answer
        // omits periods required by the same outline used to plan synthesis.
        if (policy.task?.historicalSurvey == true && accepted.text.isNotBlank()) {
            val expectedPeriods = sources.flatMap { CombinedContentCoverage.sentences(it.content) }
                .filter(CombinedHistoryOutline::substantive)
                .flatMap { CombinedHistoryOutline.periods(it) }
                .toSet()
            val coveredPeriods = CombinedHistoryOutline.periods(accepted.text).toSet()
            val missingPeriods = expectedPeriods - coveredPeriods
            if (missingPeriods.isNotEmpty()) {
                finalReport = finalReport.copy(
                    complete = false,
                    status = "CORRECTION_REQUIRED",
                    deliveredScopeStatus = "PARTIAL",
                    correctionRequired = true,
                    topicCoverage = "UNRESOLVED",
                    issues = (finalReport.issues + "HISTORICAL_PERIOD_COVERAGE_INCOMPLETE").distinct()
                )
                AppLogRecorder.record(
                    "CombinedSynthesis",
                    "HISTORICAL_PERIOD_COVERAGE_INCOMPLETE · revision=$currentRevision · missingPeriods=${missingPeriods.sorted().map { CombinedHistoryOutline.labels[it] }}"
                )
            }
        }
        if (deterministicFallback) {
            finalReport = finalReport.copy(
                complete = false,
                status = "FALLBACK_SOURCE_COMPILATION",
                deliveredScopeStatus = "PARTIAL",
                correctionRequired = true,
                issues = (finalReport.issues + "WRITER_FAILED_SOURCE_COMPILATION" + "NO_FINAL_OUTPUT_FROM_WRITER").distinct()
            )
        }
        if (ceilingEnforced) finalReport = finalReport.copy(issues = (finalReport.issues + "WORD_CEILING_ENFORCED_AFTER_REPAIR").distinct())
        if (preserveSourceContent) {
            val accounting = withContext(Dispatchers.Default) { CombinedSourcePreservation.account(accepted.text, sources) }
            finalReport = finalReport.copy(
                sourceDispositions = accounting.dispositions,
                sourceAccountingStatus = "IMMUTABLE_ORIGINALS_AND_CLAIM_LINKS_V4"
            )
        }
        AppLogRecorder.record("CombinedSynthesis", "SYNTHESIS_QUALITY · status=${finalReport.status} · delivered=${finalReport.deliveredScopeStatus} · verification=${finalReport.verificationStatus} · revision=${finalReport.revisionId} · assessedDigest=${finalReport.answerDigest} · assessmentStage=${finalReport.assessmentStage} · wordCounter=${finalReport.wordCounterVersion} · words=${finalReport.wordCount} · writerDigest=${finalReport.writerAnswerDigest} · writerCharacters=${finalReport.writerCharacters} · locallyAddedCharacters=${finalReport.locallyAddedCharacters} · locallyRemovedCharacters=${finalReport.locallyRemovedCharacters} · displayedCharacters=${finalReport.displayedCharacters} · fullyLinkedClaims=${finalReport.fullyLinkedClaims} · partialSourceLinks=${finalReport.partialSourceLinks} · unresolvedSourceLinks=${finalReport.unresolvedSourceLinks} · repairs=$repairs · accepted=$repairAccepted · rejected=$repairRejected · rejectionReasons=${rejections.joinToString(";")} · stoppingReason=$repairSkippedReason · semanticCoverageVerified=${finalReport.semanticCoverageVerified} · factualAccuracyVerified=${finalReport.factualAccuracyVerified}")
        phase(CombinedDeliveryPhase.PUBLISHING)
        validateAccess()
        val linkedPassages = finalReport.passages.filter { it.semanticLinkStatus == "CONSERVATIVE_LINK_CHECKED" }
        val failedChecks = assessment.checks.filter { it.result != "PASS" }.map { it.id }
        fun counts(label: String, total: Int): String = "${label}Total=$total · ${label}Retained=${minOf(32, total)} · ${label}Omitted=${(total - 32).coerceAtLeast(0)}"
        AppLogRecorder.record("CombinedSynthesis", "SYNTHESIS_CHECK_DETAILS · revision=${finalReport.revisionId} · assessedDigest=${finalReport.answerDigest} · failedChecks=${failedChecks.take(32)} · ${counts("failedChecks", failedChecks.size)} · issues=${finalReport.issues.take(32)} · ${counts("issues", finalReport.issues.size)} · included=${sources.map { it.platformUid }.take(32)} · ${counts("included", sources.size)} · represented=${finalReport.representedProfileUids.take(32)} · ${counts("represented", finalReport.representedProfileUids.size)} · uniquePassages=${linkedPassages.count { it.profileUids.size == 1 }} · sharedPassages=${linkedPassages.count { it.profileUids.size > 1 }} · unlinkedPassages=${finalReport.passages.size - linkedPassages.size} · sourceRejections=${finalReport.sourceLinkRejections.entries.take(32).associate { it.toPair() }} · ${counts("sourceRejections", finalReport.sourceLinkRejections.size)} · length=${finalReport.lengthCompliance} · scope=${finalReport.topicCoverage} · delivery=${finalReport.deliveredScopeStatus} · facts=${finalReport.verificationStatus}")
        if (accepted.text.isNotBlank()) {
            emit(ProviderEvent.CombinedAnswer(accepted.text, finalReport))
        } else {
            emit(ProviderEvent.CombinedQuality(finalReport))
        }
        if (finalReport.issues.contains("HISTORICAL_PERIOD_COVERAGE_INCOMPLETE")) {
            emit(ProviderEvent.Notice("The retained historical survey is missing required periods. It remains a partial draft until those sections are completed and checked.", persistent = true))
        }
        if (violations.isNotEmpty()) emit(ProviderEvent.Notice(if (accepted.text.isBlank()) "Final assembly did not deliver answer text. Saved contributions and recovery actions remain available." else "The retained draft has an unmet instruction check.", persistent = true))
        if (finalReport.correctionRequired || finalReport.status == "CORRECTION_REQUIRED") emit(ProviderEvent.Notice("The retained draft requires correction; it is not a checked final answer.", persistent = true))
        if (accepted.text.isBlank()) {
            emit(accepted.terminal as? ProviderEvent.Failed ?: ProviderEvent.Failed("Combined synthesis produced no usable answer records.", errorCode = if (finalReport.issues.any { it in COMBINED_FRAMING_FAILURES }) "OUTPUT_PROTOCOL_INVALID" else "COMBINED_PARTIAL"))
        } else if (!finalReport.complete || finalReport.deliveredScopeStatus == "PARTIAL") {
            emit(
                accepted.terminal as? ProviderEvent.Failed ?: if (finalReport.issues.any { it in COMBINED_FRAMING_FAILURES }) {
                    ProviderEvent.Failed("OUTPUT_PROTOCOL_INVALID: The retained answer contains invalid delivery records.", errorCode = "OUTPUT_PROTOCOL_INVALID")
                } else if (finalReport.protocolComplete) {
                    ProviderEvent.Failed("COMBINED_REQUIREMENTS_UNMET: The answer finished, but needs editorial correction to meet the request.", errorCode = "COMBINED_REQUIREMENTS_UNMET", transportComplete = true)
                } else {
                    ProviderEvent.Failed("COMBINED_RECORD_INCOMPLETE: The retained answer has unfinished delivery.", errorCode = "COMBINED_RECORD_INCOMPLETE")
                }
            )
        } else {
            emit(accepted.terminal as? ProviderEvent.Failed ?: if (accepted.terminal == ProviderEvent.Completed) ProviderEvent.Completed else ProviderEvent.Failed("Combined synthesis ended before completion."))
        }
    }
}
