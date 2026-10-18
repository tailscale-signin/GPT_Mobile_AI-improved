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
    private val validateAccess: suspend () -> Unit,
    private val onCoverage: suspend (CombinedContentCoverage.Result, Boolean) -> Unit = { _, _ -> },
    private val maxRepairRounds: Int = 1,
    private val originalRequest: String = "",
    private val evidence: CombinedEvidenceLedger = CombinedEvidenceLedger(),
    private val turnContext: CombinedTurnContext? = null,
    private val structured: Boolean = false,
    private val combined: CombinedExecutionContext? = null,
    private val answerContract: CombinedAnswerPolicy? = null,
    private val revisionId: String = "",
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
        val coverage = withContext(Dispatchers.Default) { CombinedContentCoverage(sources) }
        val constrained = policy.maximumWords != null || policy.minimumWords != null || policy.targetWords != null || policy.singleExample || policy.requiresCurrentEvidence || policy.requiredFormat != null
        val progressive = structured && !constrained && committedPrefix.isEmpty()
        AppLogRecorder.record("CombinedSynthesis", "SYNTHESIS_DELIVERY_STARTED · sourceCount=${sources.size} · protocol=${if (structured) "PASSAGES_V2" else "PLAIN_TEXT"} · progressive=$progressive · constrained=$constrained · revision=$currentRevision · policy=${combined?.requested?.requestedPolicyDigest.orEmpty()}")
        data class Candidate(val text: String, val terminal: ProviderEvent?, val decoder: CombinedPassageDecoder?)
        suspend fun collect(candidate: AgentProviderSession, publish: Boolean): Candidate {
            val text = StringBuilder()
            val decoder = if (structured) CombinedPassageDecoder(sources, currentEvidence, maxCharacters) else null
            var terminal: ProviderEvent? = null
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
            val answer = decoder?.answer() ?: text.toString().substringBefore(COMBINED_RECOVERY_MARKER).trimEnd()
            return Candidate(answer, terminal, decoder)
        }
        validateAccess()
        if (policy.requiresCurrentEvidence && currentEvidence.records.none { it.readable && it.article && it.dated }) {
            if (combined != null && !policy.noSearch) {
                val acquired = verify(CombinedVerificationRequest("", currentRevision, listOf(CombinedVerificationNeed("current-article", originalRequest, "A dated, readable article is required by the current-news request")), currentEvidence, originalRequest))
                validateAccess()
                if (acquired != null) currentEvidence = currentEvidence.mergedWith(acquired)
            }
            if (currentEvidence.records.none { it.readable && it.article && it.dated }) {
                val allowance = policy.maximumWords ?: policy.targetWords ?: Int.MAX_VALUE
                val answer = if (allowance < 12) "Unverified." else "I couldn’t verify the newest story from the available article evidence."
                val report = CombinedQualityReport(answerDigest = researchHash(answer), complete = true, status = "COMPLETED_WITH_ISSUES", wordCount = visibleWordCount(answer), issues = listOf("CURRENT_ARTICLE_EVIDENCE_UNAVAILABLE"), sourceUnits = coverage.units.size, lexicalMissing = coverage.units.size, turnContext = turnContext, deliveredScopeStatus = "COMPLETED_WITH_LIMITS", verificationStatus = "EVIDENCE_UNAVAILABLE", revisionId = currentRevision, consideredProfileUids = sources.mapTo(linkedSetOf()) { it.platformUid })
                emit(ProviderEvent.TextDelta(answer))
                emit(ProviderEvent.CombinedQuality(report))
                emit(ProviderEvent.Completed)
                return@flow
            }
        }
        emit(ProviderEvent.Notice("Combining the contributions within your requested scope and checking their support."))
        var accepted = collect(base, progressive)
        validateAccess()
        if (!accepted.text.startsWith(committedPrefix)) {
            emit(ProviderEvent.Failed("Combined continuation cannot replace the committed prefix without a new revision. Original contributions remain available."))
            return@flow
        }
        suspend fun assess(candidate: Candidate): CombinedFinalAssessment = withContext(Dispatchers.Default) {
            val checked = CombinedFinalCheck.check(candidate.text, sources, currentEvidence, policy, candidate.decoder?.passages.orEmpty(), candidate.decoder?.decisions.orEmpty(), currentRevision)
            val invalid = checked.receipts.filterNot { CombinedEvidenceVerifier.validateReceipt(candidate.text, currentRevision, currentEvidence, it) }
            if (invalid.isEmpty()) checked else checked.copy(checks = checked.checks + CombinedAnswerCheck("INVALID_REVISION_EVIDENCE_RECEIPT", "PROVENANCE", "FAIL", true, invalid.mapTo(linkedSetOf()) { it.claimId }, "A support receipt did not validate against this answer revision and retained evidence."))
        }
        var assessment = assess(accepted)
        var violations = withContext(Dispatchers.Default) { policy.violations(accepted.text) }
        var coverageResult = withContext(Dispatchers.Default) { coverage.check(accepted.text) }
        var repairs = 0
        var repairAccepted = 0
        var repairRejected = 0
        var correctionRequired = assessment.correctionRequired
        var publishedReplacement = false
        val rejections = mutableListOf<String>()
        val verificationAttempts = linkedSetOf<String>()
        var noProgress = 0
        var repairSkippedReason: String? = null

        fun report(candidate: Candidate, checked: CombinedFinalAssessment, final: Boolean): CombinedQualityReport {
            val providerComplete = candidate.terminal == ProviderEvent.Completed
            val material = correctionRequired || checked.correctionRequired
            val issues = (violations + candidate.decoder?.issues.orEmpty() + checked.issues + listOfNotNull("LEXICAL_COVERAGE_UNRESOLVED_HINTS".takeIf { coverageResult.missing.isNotEmpty() })).distinct()
            val delivered = when {
                candidate.text.isBlank() -> "FAILED"
                !providerComplete || material || violations.isNotEmpty() -> "PARTIAL"
                issues.any { it != "LEXICAL_COVERAGE_UNRESOLVED_HINTS" } || checked.verificationNeeds.isNotEmpty() -> "COMPLETED_WITH_LIMITS"
                else -> "COMPLETED"
            }
            return CombinedQualityReport(
                answerDigest = researchHash(candidate.text),
                complete = providerComplete && !material,
                status = when {
                    material -> "CORRECTION_REQUIRED"
                    !providerComplete -> "INTERRUPTED"
                    issues.isEmpty() -> "COMPLETED_UNVERIFIED"
                    else -> "COMPLETED_WITH_ISSUES"
                },
                wordCount = visibleWordCount(candidate.text), issues = issues,
                passages = candidate.decoder?.passages.orEmpty().filter { it.matches(candidate.text) }.map { passage -> passage.copy(evidenceStatus = CombinedEvidenceVerifier.assess(passage.passageId, candidate.text.substring(passage.start, passage.end), currentEvidence, passage.sourceIds).result) },
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
                    material -> "CORRECTION_REQUIRED"
                    checked.verificationNeeds.isNotEmpty() -> "NEEDS_VERIFICATION"
                    checked.receipts.any { it.evidenceIds.isNotEmpty() } -> "PASSAGES_CHECKED_WITH_LIMITS"
                    else -> "UNVERIFIED"
                },
                correctionRequired = material, revisionId = currentRevision,
                verificationReceipts = checked.receipts, supportEdges = checked.supportEdges,
                checks = checked.checks, verificationNeeds = checked.verificationNeeds,
                repairRejections = rejections.toList(), consideredProfileUids = sources.mapTo(linkedSetOf()) { it.platformUid },
                requestedPolicyDigest = combined?.requested?.requestedPolicyDigest.orEmpty(), effectivePlanDigest = combined?.plan?.effectivePlanDigest.orEmpty()
            )
        }

        while (accepted.terminal == ProviderEvent.Completed) {
            validateAccess()
            correctionRequired = correctionRequired || assessment.correctionRequired
            if (correctionRequired) {
                emit(ProviderEvent.CombinedQuality(report(accepted, assessment, false)))
                emit(ProviderEvent.Notice("This draft has a material support problem and needs correction. Its earlier assessment has been invalidated.", persistent = true))
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
                correctionRequired = correctionRequired || assessment.correctionRequired
                // Continue only for a shrinking set of concrete unresolved claims. Merely opening
                // more pages does not justify another batch, and known errors proceed to revision.
                if (!correctionRequired && assessment.verificationNeeds.isNotEmpty() && assessment.verificationNeeds.size < previousNeeds) continue
            }
            val coverageRepair = repairs == 0 && policy.targetWords != null && !policy.singleExample && coverageResult.missing.isNotEmpty()
            val semanticRepair = combined != null && (assessment.hardFailures.isNotEmpty() || assessment.verificationNeeds.isNotEmpty())
            if (violations.isEmpty() && !coverageRepair && !semanticRepair && !correctionRequired) {
                repairSkippedReason = "NO_REMAINING_REQUIRED_CORRECTION"
                break
            }
            if (maxRepairRounds <= 0 || combined?.plan?.requiredCorrectionAllowed == false) {
                repairSkippedReason = "CORRECTION_CAPACITY_UNAVAILABLE"
                break
            }
            if (combined == null && (repairs >= maxRepairRounds || progressive || committedPrefix.isNotEmpty())) {
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
            val requestedRepair = CombinedRepairRequest(accepted.text, policy, (violations + assessment.issues + accepted.decoder?.issues.orEmpty()).distinct(), currentEvidence, hints.map { it.text }, currentRevision, combined?.forRole(CombinedRunRole.REPAIR))
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
            if (rewritten != null && researchHash(rewritten.text) == previousDigest) {
                repairRejected++
                rejections += "REPLACEMENT_MADE_NO_TEXT_PROGRESS"
                repairSkippedReason = "NO_PROGRESS_AFTER_REQUIRED_REVISION"
                break
            }
            val previousRevision = currentRevision
            currentRevision = UUID.randomUUID().toString()
            val rewrittenChecks = rewritten?.let { assess(it) }
            val rewrittenViolations = rewritten?.let { policy.violations(it.text) }.orEmpty()
            val protocolFailures = rewritten?.decoder?.issues.orEmpty().filter { it in setOf("INVALID_DELIVERY_RECORD", "UNKNOWN_SOURCE_REFERENCE", "UNRESOLVED_CITATION_URL", "INTERNAL_RECOVERY_MARKER_REJECTED") }
            val newMaterial = rewrittenChecks?.correctionRequired == true
            val canAccept = rewritten != null &&
                rewritten.text.isNotBlank() &&
                rewritten.terminal == ProviderEvent.Completed &&
                rewrittenViolations.isEmpty() &&
                protocolFailures.isEmpty() &&
                !newMaterial &&
                rewrittenChecks != null &&
                rewrittenChecks.hardFailures.none { it !in previousFailures }
            if (canAccept && rewritten != null && rewrittenChecks != null) {
                val nextCoverage = withContext(Dispatchers.Default) { coverage.check(rewritten.text) }
                val priorCorrection = correctionRequired
                correctionRequired = false
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
                    publishedReplacement = publishedReplacement || needsAtomicReplacement
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
                    else -> "REPLACEMENT_ADDED_FAILED_CHECKS"
                }
                emit(ProviderEvent.Notice(if (correctionRequired) "The correction did not pass its checks. The retained draft remains marked as needing correction." else "The revision did not pass its checks. The earlier draft was retained.", persistent = true))
                // Repeating an unavailable or unfinished provider call is not useful editorial progress.
                break
            }
        }
        var ceilingEnforced = false
        val ceiling = policy.maximumWords
        if (!progressive && committedPrefix.isEmpty() && ceiling != null && visibleWordCount(accepted.text) > ceiling) {
            val bounded = withContext(Dispatchers.Default) { enforceCombinedWordCeiling(accepted.text, ceiling) }
            accepted = accepted.copy(text = bounded)
            violations = policy.violations(bounded)
            assessment = assess(accepted)
            correctionRequired = correctionRequired || assessment.correctionRequired
            ceilingEnforced = true
        }
        if (combined != null && assessment.checks.any { it.id.startsWith("CONSEQUENTIAL_CLAIM_NEEDS_EVIDENCE:") }) correctionRequired = true
        coverageResult = withContext(Dispatchers.Default) { coverage.check(accepted.text) }
        onCoverage(coverageResult, repairAccepted > 0)
        var finalReport = report(accepted, assessment, true)
        if (ceilingEnforced) finalReport = finalReport.copy(issues = (finalReport.issues + "WORD_CEILING_ENFORCED_AFTER_REPAIR").distinct())
        AppLogRecorder.record("CombinedSynthesis", "SYNTHESIS_QUALITY · status=${finalReport.status} · delivered=${finalReport.deliveredScopeStatus} · verification=${finalReport.verificationStatus} · revision=${finalReport.revisionId} · wordCounter=${finalReport.wordCounterVersion} · words=${finalReport.wordCount} · repairs=$repairs · accepted=$repairAccepted · rejected=$repairRejected · semanticCoverageVerified=false · factualAccuracyVerified=false")
        validateAccess()
        if (!progressive && !publishedReplacement && accepted.text.isNotBlank()) emit(ProviderEvent.TextDelta(accepted.text))
        emit(ProviderEvent.CombinedQuality(finalReport))
        if (violations.isNotEmpty()) emit(ProviderEvent.Notice("The answer was delivered with an unmet instruction check.", persistent = true))
        if (correctionRequired) emit(ProviderEvent.Notice("The retained draft requires correction; it is not a checked final answer.", persistent = true))
        if (accepted.text.isBlank()) {
            emit(ProviderEvent.Failed("Combined synthesis produced no usable answer records."))
        } else {
            emit(accepted.terminal as? ProviderEvent.Failed ?: if (accepted.terminal == ProviderEvent.Completed) ProviderEvent.Completed else ProviderEvent.Failed("Combined synthesis ended before completion."))
        }
    }
}
