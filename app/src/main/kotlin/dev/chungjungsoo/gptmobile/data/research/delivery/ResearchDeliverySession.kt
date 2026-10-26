package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Only research delivery is buffered. Collection/tool rounds and ordinary chat retain streaming. */
internal class ResearchDeliverySession(
    private val base: AgentProviderSession,
    private val packet: suspend () -> ResearchDeliveryPacket?,
    private val expectResearch: Boolean = false,
    private val outputCap: Int? = null,
    private val saveReceipt: suspend (ResearchDeliveryReceipt) -> Unit = {},
    private val loadSections: suspend (String) -> Map<String, String> = { emptyMap() },
    private val saveSection: suspend (String, String, String) -> Unit = { _, _, _ -> },
    private val replaceDraft: suspend (String, String) -> Boolean = { _, _ -> false },
    private val validateAccess: suspend () -> Unit = {},
    private val prepareInitial: suspend (AgentProviderSession, String) -> AgentProviderSession = { session, _ -> session },
    private val create: suspend (String, Boolean) -> AgentProviderSession
) : AgentProviderSession {
    private var publishedDraft = ""
    private var preparedInitialSession: AgentProviderSession? = null
    override val handlesToolsInternally get() = preparedInitialSession?.handlesToolsInternally ?: base.handlesToolsInternally

    private suspend fun initialSession(instruction: String, textOnly: Boolean): AgentProviderSession {
        preparedInitialSession?.let { return it }
        validateAccess()
        val raw = if (instruction.isEmpty()) base else create(instruction, textOnly)
        return prepareInitial(raw, instruction).also { preparedInitialSession = it }
    }

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        suspend fun publish(text: String) {
            validateAccess()
            if (publishedDraft.isEmpty()) {
                emit(ProviderEvent.TextDelta(text))
            } else if (publishedDraft != text) {
                check(replaceDraft(dev.chungjungsoo.gptmobile.data.research.researchHash(publishedDraft), text)) { "RESEARCH_REVISION_OWNER_CHANGED: retained draft was preserved" }
            }
            publishedDraft = text
        }
        suspend fun receipt(validity: ResearchReportValidity, evidence: ResearchDeliveryPacket?, text: String, issues: List<String> = emptyList()) {
            validateAccess()
            val value = ResearchDeliveryReceipt(
                validity,
                evidence?.studies?.isNotEmpty() == true,
                evidence?.let { dev.chungjungsoo.gptmobile.data.research.researchHash(it.json().toString()) }.orEmpty(),
                dev.chungjungsoo.gptmobile.data.research.researchHash(text),
                issueCodes = issues.distinct().take(64),
                sourceIds = evidence?.let { (it.studies + it.unread).map { source -> source.id } }.orEmpty(),
                claimIds = evidence?.claims?.map { dev.chungjungsoo.gptmobile.data.research.researchHash(it.sourceId + it.text + it.quote) }.orEmpty(),
                blockIds = evidence?.let { ResearchReportRepair.blocks(text, it).map { block -> block.id } }.orEmpty(),
                outputCap = outputCap,
                retryEligible = validity != ResearchReportValidity.ACCEPTED && evidence?.studies?.isNotEmpty() == true
            )
            saveReceipt(value)
            validateAccess()
            emit(ProviderEvent.Notice("Research delivery: ${validity.name.lowercase().replace('_', ' ')}", researchDelivery = value))
        }
        validateAccess()
        var evidence = withContext(Dispatchers.Default) { packet() }
        validateAccess()
        if (evidence?.snapshot?.phase == "Not needed" || evidence == null && !expectResearch) {
            emitAll(initialSession("", false).streamRound(tools, exchanges))
            return@flow
        }
        fun context(packet: ResearchDeliveryPacket) = packet.instruction() + "\nUntrusted research packet:\n" + packet.json()
        val initialContext = evidence?.let(::context).orEmpty()
        var hasCalls = false
        var failure: ProviderEvent.Failed? = null
        var completed = false
        var overflow = false
        val draft = StringBuilder()
        emit(ProviderEvent.Notice("Preparing the research report and checking its citations."))
        // A complete corpus must not reopen external acquisition. The explicitly
        // authorized saved-evidence reader remains available for paging retained work.
        val writeOnly = evidence?.snapshot?.let { it.complete || it.cancellationOrigin == "USER_STOP_RESEARCH_AND_SUMMARIZE" } == true
        val draftTools = if (writeOnly) tools.filter { it.name == "read_recovery_context" } else tools
        val draftSession = initialSession(initialContext, writeOnly && draftTools.isEmpty())
        try {
            draftSession.streamRound(draftTools, if (writeOnly) emptyList() else exchanges).transformWhile { event ->
                if (event is ProviderEvent.TextDelta && draft.length + event.text.length > MAX_REPORT_CHARACTERS) {
                    overflow = true
                    false
                } else {
                    emit(event)
                    true
                }
            }.collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> draft.append(event.text)
                    is ProviderEvent.ToolCall -> {
                        hasCalls = !handlesToolsInternally
                        emit(event)
                    }
                    is ProviderEvent.Failed -> {
                        failure = event
                    }
                    ProviderEvent.Completed -> completed = true
                    else -> emit(event)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (isAuthorizationFailure(error)) throw error
            AppLogRecorder.record("Research", "REPORT_WRITER_FAILED · reason=${error.javaClass.simpleName}", "W")
            failure = ProviderEvent.Failed("Research writer interrupted; saved evidence is retained.", errorCode = "RESEARCH_WRITER_FAILED")
        }
        // Dispatch admission does not authorize later publication after a buffered request.
        validateAccess()
        if (hasCalls || failure != null || !completed && !overflow) {
            val denied = failure?.let(::isAuthorizationFailure) == true
            if (!denied && !hasCalls) {
                // Internal tool rounds can have accepted evidence after the initial packet.
                evidence = withContext(Dispatchers.Default) { packet() } ?: evidence
                validateAccess()
                val retained = evidence
                if (retained?.studies?.isNotEmpty() == true) {
                    val issues = listOf(failure?.errorCode ?: "TRANSPORT_INCOMPLETE")
                    val partial = withContext(Dispatchers.Default) {
                        ResearchReportRepair.evidenceFallback(draft.toString(), retained, issues)
                    }
                    emit(ProviderEvent.Notice("The writer stopped. Delivering saved evidence and source links as a partial report."))
                    publish(partial)
                    receipt(ResearchReportValidity.EVIDENCE_ONLY, retained, partial, issues)
                } else {
                    if (draft.isNotEmpty()) {
                        emit(ProviderEvent.Notice("Research draft is incomplete and has not passed delivery checks.", persistent = true))
                        publish(draft.toString())
                    }
                    receipt(ResearchReportValidity.INCOMPLETE, evidence, draft.toString(), listOf(failure?.errorCode ?: "TRANSPORT_INCOMPLETE"))
                }
            }
            failure?.let { emit(it) }
            if (completed && failure == null) emit(ProviderEvent.Completed)
            return@flow
        }
        if (handlesToolsInternally || evidence == null) evidence = withContext(Dispatchers.Default) { packet() }
        validateAccess()
        val delivery = evidence
        if (delivery == null && expectResearch) {
            receipt(ResearchReportValidity.INCOMPLETE, null, "", listOf("NO_ACQUISITION_PACKET"))
            emit(ProviderEvent.Failed("RESEARCH_PARTIAL: No acquisition packet was retained; resume source collection before report delivery."))
            return@flow
        }
        if (delivery == null) {
            publish(draft.toString())
            emit(ProviderEvent.Completed)
            return@flow
        }
        if (!delivery.snapshot.complete && delivery.snapshot.sources.none { it.readable }) {
            // Rewriting cannot turn unread leads into reviewed evidence. Preserve a
            // useful partial result without spending multiple writer/repair requests.
            validateAccess()
            val fallback = ResearchDeliveryValidator.fallback(delivery)
            publish(fallback)
            receipt(ResearchReportValidity.EVIDENCE_ONLY, delivery, fallback, listOf("NO_READABLE_SOURCES"))
            emit(ProviderEvent.Failed("RESEARCH_PARTIAL: No readable sources were acquired. Saved discovery leads remain available; retry collection when sources and network access are available."))
            return@flow
        }
        var text = ResearchReportReferences.render(draft.toString(), delivery)
        var partialFallback = false
        val result = withContext(Dispatchers.Default) { ResearchDeliveryValidator.validate(text, delivery) }
        var deliveryIssues = result.issues
        var repairRejection: ResearchRepairResult.Reason? = null
        if (!result.accepted || overflow) {
            emit(ProviderEvent.Notice("Checking omitted findings, numerical claims and source links."))
            val repair = StringBuilder()
            var repairFailed = false
            var repairCompleted = false
            var authorizationFailure: ProviderEvent.Failed? = null
            // One text-only repair; no discovery or external action is replayed.
            val instruction = context(delivery) + "\n" + ResearchReportRepair.instruction(text, delivery, result.issues)
            try {
                val finished = withTimeoutOrNull(45_000L) {
                    create(instruction, true).streamRound(emptyList(), emptyList()).transformWhile { event ->
                        if (event is ProviderEvent.ToolCall || event is ProviderEvent.TextDelta && repair.length + event.text.length > MAX_REPORT_CHARACTERS) {
                            repairFailed = true
                            repairRejection = if (event is ProviderEvent.ToolCall) ResearchRepairResult.Reason.UNEXPECTED_TOOL_CALL else ResearchRepairResult.Reason.RESPONSE_TOO_LARGE
                            false
                        } else {
                            emit(event)
                            true
                        }
                    }.collect { event ->
                        when (event) {
                            is ProviderEvent.TextDelta -> repair.append(event.text)
                            is ProviderEvent.Failed -> {
                                repairFailed = true
                                repairRejection = ResearchRepairResult.Reason.REQUEST_FAILED
                                if (isAuthorizationFailure(event)) authorizationFailure = event
                            }
                            ProviderEvent.Completed -> repairCompleted = true
                            else -> emit(event)
                        }
                    }
                    true
                }
                if (finished != true) {
                    repairFailed = true
                    repairRejection = ResearchRepairResult.Reason.TIMEOUT
                }
            } catch (cancelled: CancellationException) {
                AppLogRecorder.record("Research", "REPAIR_OUTCOME · reason=${ResearchRepairResult.Reason.CANCELLED.issueCode} · responseChars=${repair.length} · applied=false")
                throw cancelled
            } catch (failure: Exception) {
                if (isAuthorizationFailure(failure)) throw failure
                repairFailed = true
                repairRejection = ResearchRepairResult.Reason.REQUEST_FAILED
            }
            validateAccess()
            // A revoked permission must not reveal a cached evidence fallback after the failed dispatch.
            authorizationFailure?.let { denial ->
                emit(denial)
                return@flow
            }
            val application = if (repairFailed || !repairCompleted) {
                ResearchRepairResult.Rejected(repairRejection ?: ResearchRepairResult.Reason.INCOMPLETE_RESPONSE)
            } else {
                withContext(Dispatchers.Default) { ResearchReportRepair.applyResult(text, repair.toString(), delivery) }
            }
            repairRejection = (application as? ResearchRepairResult.Rejected)?.reason
            val applied = (application as? ResearchRepairResult.Applied)?.text
            val repairText = applied?.let { ResearchReportReferences.render(it, delivery) }
            // A rejected patch never becomes a synthetic empty report. The original
            // draft remains the fallback source, and the exact rejection is retained.
            val repaired = repairText?.let { candidate -> withContext(Dispatchers.Default) { ResearchDeliveryValidator.validate(candidate, delivery) } }
            val repairIssues = repairRejection?.let { listOf(it.issueCode) } ?: repaired?.issues.orEmpty()
            val repairedAccepted = repaired?.accepted == true
            deliveryIssues = (result.issues + repairIssues).distinct()
            // Long reports often overflow a provider's per-answer cap. Two isolated
            // text-only sections preserve evidence without redoing the acquisition.
            val sectioned = if (!repairedAccepted && delivery.detail != ResearchReportDetail.BRIEF) {
                emit(ProviderEvent.Notice("Writing the remaining research sections in smaller, source-checked segments."))
                trySectionedRepair(delivery, context(delivery), text)
            } else {
                null
            }
            val sectionedAccepted = sectioned != null
            validateAccess()
            text = when {
                repairedAccepted -> requireNotNull(repairText)
                sectioned != null -> sectioned
                else -> {
                    val digest = dev.chungjungsoo.gptmobile.data.research.researchHash(delivery.json().toString() + delivery.detail.name)
                    val saved = loadSections(digest).filter { (heading, body) -> heading in delivery.sections && reusableSection(body, delivery) }.values.filterNot { it in text }
                    ResearchReportRepair.evidenceFallback((listOf(text) + saved).filter(String::isNotBlank).joinToString("\n\n"), delivery, result.issues)
                }
            }
            if (!repairedAccepted && !sectionedAccepted) {
                partialFallback = true
                emit(ProviderEvent.Notice("The generated report did not pass evidence checks. Delivering a clearly labelled partial evidence report instead."))
            }
            AppLogRecorder.record("Research", "DELIVERY_VALIDATED · repaired=true · accepted=${repairedAccepted || sectionedAccepted} · sectioned=$sectionedAccepted · partialFallback=${!repairedAccepted && !sectionedAccepted} · initialIssues=${result.issues.joinToString()} · repairIssues=${repairIssues.joinToString()} · repairCompleted=$repairCompleted · repairFailed=$repairFailed · repairRejection=${repairRejection?.issueCode ?: "none"} · repairResponseChars=${repair.length} · repairApplied=${applied != null} · initialOverflow=$overflow · structuralIssues=${repaired?.issues.orEmpty().joinToString()} · semanticCoverageVerified=false")
        } else {
            AppLogRecorder.record("Research", "DELIVERY_VALIDATED · repaired=false · accepted=true · cited=${result.citedIds.size} · semanticCoverageVerified=false")
        }
        publish(text)
        receipt(if (partialFallback) ResearchReportValidity.EVIDENCE_ONLY else ResearchReportValidity.ACCEPTED, delivery, text, if (partialFallback) deliveryIssues else emptyList())
        if (partialFallback) {
            val correctionStatus = repairRejection?.let { " ${it.description}" }.orEmpty()
            emit(ProviderEvent.Failed("RESEARCH_PARTIAL: Evidence is retained, but the report did not pass its delivery checks.$correctionStatus Resume writing from saved evidence.", errorCode = "RESEARCH_PARTIAL"))
        } else {
            emit(ProviderEvent.Completed)
        }
    }

    /** Each chapter is a separate tool-free request; it may fail without losing captured evidence. */
    private suspend fun trySectionedRepair(packet: ResearchDeliveryPacket, sourceContext: String, previous: String): String? {
        validateAccess()
        val digest = dev.chungjungsoo.gptmobile.data.research.researchHash(packet.json().toString() + packet.detail.name)
        val reusable = (loadSections(digest) + reportSections(previous)).filter { (heading, body) -> heading in packet.sections && reusableSection(body, packet) }.toMutableMap()
        validateAccess()
        val sections = packet.sections.chunked(1)
        val report = StringBuilder()
        for ((index, headings) in sections.withIndex()) {
            val missing = headings.filterNot(reusable::containsKey)
            if (missing.isEmpty()) {
                if (report.isNotEmpty()) report.append("\n\n")
                report.append(headings.joinToString("\n\n") { reusable.getValue(it) })
                continue
            }
            val part = StringBuilder()
            var complete = false
            var truncated = false
            val instruction = sourceContext +
                "\nWrite ONLY the following report sections (Markdown ## headings), in order: " +
                missing.joinToString("; ") +
                ". This is chapter ${index + 1} of ${sections.size}. " +
                "Use only source IDs and URLs from the packet. Cite every factual paragraph using exact bracketed source IDs from the packet (for example, ${packet.studies.firstOrNull()?.id?.let { "[$it]" } ?: "[sourceId]"}). " +
                "For unread sources explicitly state that only metadata was available. " +
                "Do not call tools or write any sections assigned to another chapter."
            val finished = try {
                withTimeoutOrNull(90_000L) {
                    create(instruction, true).streamRound(emptyList(), emptyList()).collect { event ->
                        when (event) {
                            is ProviderEvent.TextDelta -> {
                                if (part.length + event.text.length > MAX_REPORT_CHARACTERS / 2) {
                                    throw IllegalStateException("Sectioned report output exceeded character limit")
                                }
                                part.append(event.text)
                            }
                            is ProviderEvent.ToolCall -> throw IllegalStateException("Sectioned writer attempted a tool call")
                            is ProviderEvent.Failed -> {
                                if (isAuthorizationFailure(event)) throw SecurityException(event.message)
                                throw IllegalStateException(event.message)
                            }
                            ProviderEvent.Completed -> complete = true
                            is ProviderEvent.Usage -> truncated = truncated || event.finishReason in setOf("length", "max_tokens", "output_limit")
                            else -> Unit
                        }
                    }
                    true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A revoked research permission must abort delivery, never expose cached text.
                if (isAuthorizationFailure(error)) throw error
                AppLogRecorder.record("Research", "REPORT_CHAPTER_FAILED · chapter=${index + 1} · reason=${error.javaClass.simpleName}", "W")
                return null
            }
            validateAccess()
            AppLogRecorder.record("Research", "REPORT_CHAPTER · chapter=${index + 1} · complete=$complete · truncated=$truncated · characters=${part.length} · dispatched=true")
            if (finished != true || !complete || truncated || part.isBlank()) return null
            val written = reportSections(part.toString())
            if (missing.any { heading -> written[heading]?.let { reusableSection(it, packet) } != true }) return null
            missing.forEach { heading ->
                val body = written.getValue(heading)
                validateAccess()
                saveSection(digest, heading, body)
                reusable[heading] = body
            }
            if (report.isNotEmpty()) report.append("\n\n")
            report.append(headings.joinToString("\n\n") { reusable.getValue(it) })
        }
        val proposed = report.toString()
        validateAccess()
        return proposed.takeIf { ResearchDeliveryValidator.validate(it, packet).accepted }
    }

    private fun reusableSection(body: String, packet: ResearchDeliveryPacket): Boolean {
        if (body.isBlank()) return false
        val issues = ResearchReportRepair.sectionIssues(body, packet)
        if (issues.isNotEmpty()) AppLogRecorder.record("Research", "REPORT_SECTION_REJECTED · issues=${issues.joinToString()}", "W")
        return issues.isEmpty()
    }

    private fun reportSections(text: String): Map<String, String> {
        val headings = Regex("(?m)^##[ \t]+([^\r\n]+)[ \t]*$").findAll(text).toList()
        return headings.mapIndexedNotNull { index, match ->
            val heading = match.groupValues[1].trim()
            if (headings.count { it.groupValues[1].trim() == heading } != 1) null else heading to text.substring(match.range.first, headings.getOrNull(index + 1)?.range?.first ?: text.length).trim()
        }.toMap()
    }

    companion object {
        private fun isAuthorizationFailure(event: ProviderEvent.Failed): Boolean = isAuthorizationFailure(event.errorCode.orEmpty()) || isAuthorizationFailure(event.message)

        private fun isAuthorizationFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(16).any {
            it is SecurityException || it is dev.chungjungsoo.gptmobile.data.agent.SavedEvidenceAccessDenied || isAuthorizationFailure(it.message.orEmpty())
        }

        private fun isAuthorizationFailure(message: String): Boolean = listOf(
            "DISPATCH_POLICY_CHANGED",
            "PERMISSION_DENIED",
            "SOURCE_PERMISSION_REVOKED",
            "MEMORY_STALE",
            "DOCUMENT_RECALL_STALE",
            "PROFILE_CONFIGURATION_CHANGED",
            "PROFILE_IDENTITY_CHANGED",
            "PROFILE_IDENTITY_AMBIGUOUS",
            "PROFILE_REMOVED",
            "PROFILE_DISABLED",
            "REQUEST_IDENTITY_CHANGED",
            "RESEARCH_RECEIPT_REJECTED",
            "RESEARCH_REVISION_OWNER_CHANGED"
        ).any { message.contains(it, ignoreCase = true) }
        private const val MAX_REPORT_CHARACTERS = 100_000
    }
}
