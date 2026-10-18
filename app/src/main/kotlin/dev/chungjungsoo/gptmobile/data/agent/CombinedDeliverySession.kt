package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash
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

/** Coverage is diagnostic. Only a demonstrated instruction violation can trigger one editorial retry. */
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
    private val repair: suspend (String, List<CombinedContentCoverage.Unit>) -> AgentProviderSession
) : AgentProviderSession {
    override val handlesToolsInternally = false

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        val policy = CombinedAnswerPolicy.from(originalRequest)
        val coverage = withContext(Dispatchers.Default) { CombinedContentCoverage(sources) }
        val constrained = policy.maximumWords != null || policy.minimumWords != null || policy.targetWords != null || policy.singleExample || policy.requiresCurrentEvidence
        // Immutable published text is never rewritten. Constrained answers are checked before publication.
        val progressive = structured && !constrained && committedPrefix.isEmpty()
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "CombinedSynthesis",
            "SYNTHESIS_DELIVERY_STARTED · sourceCount=${sources.size} · profileUids=${sources.map { it.platformUid }.distinct().joinToString(",")} · candidateUnits=${coverage.units.size} · protocol=${if (structured) "PASSAGES_V2" else "PLAIN_TEXT"} · progressive=$progressive · constrained=$constrained"
        )
        data class Candidate(val text: String, val terminal: ProviderEvent?, val decoder: CombinedPassageDecoder?)
        suspend fun collect(candidate: AgentProviderSession, publish: Boolean): Candidate {
            val text = StringBuilder()
            val decoder = if (structured) CombinedPassageDecoder(sources, evidence, maxCharacters) else null
            var terminal: ProviderEvent? = null
            flow { emitAll(candidate.streamRound(emptyList(), emptyList())) }.catch { error ->
                if (error is CancellationException) throw error
                emit(ProviderEvent.Failed("Combined synthesis stream failed. The partial answer and original contributions are retained."))
            }.takeWhile { event ->
                when {
                    terminal is ProviderEvent.Failed -> false
                    event is ProviderEvent.TextDelta && text.length.toLong() + event.text.length > maxCharacters.toLong() * (if (structured) 3 else 1) -> {
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
                            if (deltas.isNotEmpty() && decoder != null) emit(ProviderEvent.CombinedQuality(CombinedQualityReport(passages = decoder.passages.toList(), turnContext = turnContext)))
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
        if (policy.requiresCurrentEvidence && evidence.records.none { it.readable && it.article && it.dated }) {
            val allowance = policy.maximumWords ?: policy.targetWords ?: Int.MAX_VALUE
            val answer = if (allowance < 12) "Unverified." else "I couldn’t verify the newest story from the available article evidence."
            val report = CombinedQualityReport(answerDigest = researchHash(answer), complete = true, status = "COMPLETED_WITH_ISSUES", wordCount = visibleWordCount(answer), issues = listOf("CURRENT_ARTICLE_EVIDENCE_UNAVAILABLE"), sourceUnits = coverage.units.size, lexicalMissing = coverage.units.size, turnContext = turnContext)
            emit(ProviderEvent.TextDelta(answer))
            emit(ProviderEvent.CombinedQuality(report))
            emit(ProviderEvent.Completed)
            return@flow
        }
        emit(ProviderEvent.Notice("Combining all contributions within your requested scope; tracking passages and citations."))
        var accepted = collect(base, progressive)
        validateAccess()
        if (!accepted.text.startsWith(committedPrefix)) {
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("CombinedSynthesis", "SYNTHESIS_REJECTED · reason=COMMITTED_PREFIX_MISMATCH · sourceCount=${sources.size}", "E")
            emit(ProviderEvent.Failed("Combined delivery cannot replace the committed prefix. Original contributions remain available."))
            return@flow
        }
        var violations = withContext(Dispatchers.Default) { policy.violations(accepted.text) }
        var coverageResult = withContext(Dispatchers.Default) { coverage.check(accepted.text) }
        // A target-length answer often looks complete to the model even when it omitted
        // whole requested eras. For broad, length-targeted prompts, give the editor the
        // unresolved source claims so it can repair omissions inside the user's word budget.
        // A request for one fact/example remains intentionally narrow.
        val repairCoverage = policy.targetWords != null && !policy.singleExample && coverageResult.missing.isNotEmpty()
        var repairs = 0
        if (!progressive &&
            committedPrefix.isEmpty() &&
            accepted.terminal == ProviderEvent.Completed &&
            (violations.isNotEmpty() || repairCoverage) &&
            maxRepairRounds > 0
        ) {
            emit(ProviderEvent.Notice("Checking the requested scope, length, and source coverage."))
            repairs = 1
            val repairSession = withTimeoutOrNull(90_000L) {
                try {
                    repair(accepted.text, if (repairCoverage) coverageResult.missing else emptyList())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            // The repair factory only prepares the provider session. Collect its stream outside
            // this factory timeout so coroutine-test dispatchers and provider progress deadlines
            // govern the response rather than a nested timeout around Flow collection.
            val rewritten = repairSession?.let { collect(it, false) }
            validateAccess()
            if (rewritten != null &&
                rewritten.text.isNotBlank() &&
                rewritten.terminal == ProviderEvent.Completed &&
                policy.violations(rewritten.text).isEmpty() &&
                rewritten.decoder?.issues.orEmpty().isEmpty()
            ) {
                // Replace only with a completed, accepted candidate. A failed or partial
                // repair must never erase the already completed original answer.
                accepted = rewritten
                violations = withContext(Dispatchers.Default) { policy.violations(rewritten.text) }
            } else {
                emit(ProviderEvent.Notice("The revision did not pass completion checks. The original answer was retained.", persistent = true))
            }
        }
        var ceilingEnforced = false
        val ceiling = policy.maximumWords
        if (!progressive && committedPrefix.isEmpty() && ceiling != null && visibleWordCount(accepted.text) > ceiling) {
            val bounded = withContext(Dispatchers.Default) { enforceCombinedWordCeiling(accepted.text, ceiling) }
            accepted = accepted.copy(text = bounded)
            violations = withContext(Dispatchers.Default) { policy.violations(bounded) }
            ceilingEnforced = true
        }
        val checked = withContext(Dispatchers.Default) { coverage.check(accepted.text) }
        onCoverage(checked, repairs > 0)
        val issues = (
            violations + accepted.decoder?.issues.orEmpty() + listOfNotNull("WORD_CEILING_ENFORCED_AFTER_REPAIR".takeIf { ceilingEnforced }) +
                listOfNotNull("LEXICAL_COVERAGE_UNRESOLVED_HINTS".takeIf { checked.missing.isNotEmpty() }, "UNVERIFIED_PASSAGES".takeIf { accepted.decoder?.passages?.any { it.evidenceStatus != "PASSAGE_SUPPORTED" } == true }, "MODEL_REPORTED_MISSING_CLAIMS".takeIf { accepted.decoder?.decisions?.any { it.disposition == "MISSING" } == true })
            ).distinct()
        val report = CombinedQualityReport(
            answerDigest = researchHash(accepted.text), complete = accepted.terminal == ProviderEvent.Completed,
            status = if (accepted.terminal != ProviderEvent.Completed) {
                "INTERRUPTED"
            } else if (issues.isEmpty()) {
                "COMPLETED_CHECKS_PASSED"
            } else {
                "COMPLETED_WITH_ISSUES"
            },
            wordCount = visibleWordCount(accepted.text), issues = issues,
            passages = accepted.decoder?.passages.orEmpty().filter { it.matches(accepted.text) }, decisions = accepted.decoder?.decisions.orEmpty().toList(),
            sourceUnits = checked.total, lexicalMissing = checked.missing.size,
            duplicatePassagesRemoved = accepted.decoder?.duplicatesRemoved ?: 0, repairRounds = repairs, turnContext = turnContext
        )
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "CombinedSynthesis",
            "SYNTHESIS_QUALITY · status=${report.status} · sourceCount=${sources.size} · candidateUnits=${checked.total} · exact=${checked.exactMatches} · paraphrase=${checked.paraphraseMatches} · tentative=${checked.tentativeMatches} · missing=${checked.missing.size} · passages=${report.passages.size} · decisions=${report.decisions.size} · duplicatesRemoved=${report.duplicatePassagesRemoved} · repairRounds=${report.repairRounds} · issues=${report.issues.joinToString(",").ifBlank { "none" }} · semanticCoverageVerified=false · factualAccuracyVerified=false"
        )
        validateAccess()
        if (!progressive && accepted.text.isNotBlank()) emit(ProviderEvent.TextDelta(accepted.text))
        emit(ProviderEvent.CombinedQuality(report))
        if (violations.isNotEmpty()) emit(ProviderEvent.Notice("The combined answer finished with an unmet instruction check. Details are available in Debug Mode.", persistent = true))
        if (accepted.text.isBlank()) {
            emit(ProviderEvent.Failed("Combined synthesis produced no usable answer records."))
        } else {
            emit(accepted.terminal as? ProviderEvent.Failed ?: if (accepted.terminal == ProviderEvent.Completed) ProviderEvent.Completed else ProviderEvent.Failed("Combined synthesis ended before completion."))
        }
    }
}
