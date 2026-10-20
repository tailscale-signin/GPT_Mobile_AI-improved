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
    private val loadSections: suspend (String) -> Map<String, String> = { emptyMap() },
    private val saveSection: suspend (String, String, String) -> Unit = { _, _, _ -> },
    private val replaceDraft: suspend (String, String) -> Boolean = { _, _ -> false },
    private val create: suspend (String, Boolean) -> AgentProviderSession
) : AgentProviderSession {
    private var publishedDraft = ""
    override val handlesToolsInternally = base.handlesToolsInternally
    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        suspend fun publish(text: String) {
            if (publishedDraft.isEmpty()) {
                emit(ProviderEvent.TextDelta(text))
            } else if (publishedDraft != text) {
                check(replaceDraft(dev.chungjungsoo.gptmobile.data.research.researchHash(publishedDraft), text)) { "RESEARCH_REVISION_OWNER_CHANGED: retained draft was preserved" }
            }
            publishedDraft = text
        }
        var evidence = withContext(Dispatchers.Default) { packet() }
        if (evidence?.snapshot?.phase == "Not needed" || evidence == null && !expectResearch) {
            emitAll(base.streamRound(tools, exchanges))
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
        val draftSession = if (initialContext.isEmpty()) base else create(initialContext, writeOnly && draftTools.isEmpty())
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
        if (hasCalls || failure != null || !completed && !overflow) {
            val denied = failure?.message?.let(::isAuthorizationFailure) == true
            if (draft.isNotEmpty() && !denied && !hasCalls) {
                emit(ProviderEvent.Notice("Research draft is incomplete and has not passed delivery checks.", persistent = true))
                publish(draft.toString())
            }
            failure?.let { emit(it) }
            if (completed && failure == null) emit(ProviderEvent.Completed)
            return@flow
        }
        if (handlesToolsInternally || evidence == null) evidence = withContext(Dispatchers.Default) { packet() }
        val delivery = evidence
        if (delivery == null && expectResearch) {
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
            publish(ResearchDeliveryValidator.fallback(delivery))
            emit(ProviderEvent.Failed("RESEARCH_PARTIAL: No readable sources were acquired. Saved discovery leads remain available; retry collection when sources and network access are available."))
            return@flow
        }
        var text = ResearchReportReferences.render(draft.toString(), delivery)
        var partialFallback = false
        val result = withContext(Dispatchers.Default) { ResearchDeliveryValidator.validate(text, delivery) }
        if (!result.accepted || overflow) {
            emit(ProviderEvent.Notice("Checking omitted findings, numerical claims and source links."))
            val repair = StringBuilder()
            var repairFailed = false
            var repairCompleted = false
            var authorizationFailure: ProviderEvent.Failed? = null
            // One text-only repair; no discovery or external action is replayed.
            val instruction = context(delivery) + "\nRevise the report once. Correct these delivery issues: ${result.issues.joinToString()}. Write the entire report with no tool calls.\nUntrusted previous draft:\n" + text.take(24_000)
            try {
                val finished = withTimeoutOrNull(45_000L) {
                    create(instruction, true).streamRound(emptyList(), emptyList()).transformWhile { event ->
                        if (event is ProviderEvent.ToolCall || event is ProviderEvent.TextDelta && repair.length + event.text.length > MAX_REPORT_CHARACTERS) {
                            repairFailed = true
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
                                if (isAuthorizationFailure(event.message)) authorizationFailure = event
                            }
                            ProviderEvent.Completed -> repairCompleted = true
                            else -> emit(event)
                        }
                    }
                    true
                }
                if (finished != true) repairFailed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (isAuthorizationFailure(failure.message.orEmpty())) throw failure
                repairFailed = true
            }
            // A revoked permission must not reveal a cached evidence fallback after the failed dispatch.
            authorizationFailure?.let { denial ->
                emit(denial)
                return@flow
            }
            val repairText = ResearchReportReferences.render(repair.toString(), delivery)
            val repaired = withContext(Dispatchers.Default) { ResearchDeliveryValidator.validate(repairText, delivery) }
            val repairedAccepted = !repairFailed && repairCompleted && repaired.accepted
            // Long reports often overflow a provider's per-answer cap. Two isolated
            // text-only sections preserve evidence without redoing the acquisition.
            val sectioned = if (!repairedAccepted && delivery.detail != ResearchReportDetail.BRIEF) {
                emit(ProviderEvent.Notice("Writing the remaining research sections in smaller, source-checked segments."))
                trySectionedRepair(delivery, context(delivery), text)
            } else {
                null
            }
            val sectionedAccepted = sectioned != null
            text = when {
                repairedAccepted -> repairText
                sectioned != null -> sectioned
                else -> ResearchDeliveryValidator.fallback(delivery)
            }
            if (!repairedAccepted && !sectionedAccepted) {
                partialFallback = true
                emit(ProviderEvent.Notice("The generated report did not pass evidence checks. Delivering a clearly labelled partial evidence report instead."))
            }
            AppLogRecorder.record("Research", "DELIVERY_VALIDATED · repaired=true · accepted=${repairedAccepted || sectionedAccepted} · sectioned=$sectionedAccepted · partialFallback=${!repairedAccepted && !sectionedAccepted} · initialIssues=${result.issues.joinToString()} · repairIssues=${repaired.issues.joinToString()} · repairCompleted=$repairCompleted · repairFailed=$repairFailed · initialOverflow=$overflow · structuralIssues=${repaired.issues.joinToString()} · semanticCoverageVerified=false")
        } else {
            AppLogRecorder.record("Research", "DELIVERY_VALIDATED · repaired=false · accepted=true · cited=${result.citedIds.size} · semanticCoverageVerified=false")
        }
        publish(text)
        if (partialFallback) {
            emit(ProviderEvent.Failed("RESEARCH_PARTIAL: Evidence is retained, but the report did not pass its delivery checks. Resume writing from saved evidence."))
        } else {
            emit(ProviderEvent.Completed)
        }
    }

    /** Each chapter is a separate tool-free request; it may fail without losing captured evidence. */
    private suspend fun trySectionedRepair(packet: ResearchDeliveryPacket, sourceContext: String, previous: String): String? {
        val digest = dev.chungjungsoo.gptmobile.data.research.researchHash(packet.json().toString() + packet.detail.name)
        val reusable = (loadSections(digest) + reportSections(previous)).filter { (heading, body) -> heading in packet.sections && reusableSection(body, packet) }.toMutableMap()
        val sections = packet.sections.chunked(4)
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
                "Use only source IDs and URLs from the packet. Cite scientific paragraphs with [S-id] markers. " +
                "For unread sources explicitly state that only metadata was available. " +
                "Do not call tools or write any sections assigned to another chapter."
            try {
                val finished = withTimeoutOrNull(30_000L) {
                    create(instruction, true).streamRound(emptyList(), emptyList()).collect { event ->
                        when (event) {
                            is ProviderEvent.TextDelta -> {
                                if (part.length + event.text.length > MAX_REPORT_CHARACTERS / 2) {
                                    throw IllegalStateException("Sectioned report output exceeded character limit")
                                }
                                part.append(event.text)
                            }
                            is ProviderEvent.ToolCall -> throw IllegalStateException("Sectioned writer attempted a tool call")
                            is ProviderEvent.Failed -> throw IllegalStateException(event.message)
                            ProviderEvent.Completed -> complete = true
                            is ProviderEvent.Usage -> truncated = truncated || event.finishReason in setOf("length", "max_tokens", "output_limit")
                            else -> Unit
                        }
                    }
                    true
                }
                AppLogRecorder.record("Research", "REPORT_CHAPTER · chapter=${index + 1} · complete=$complete · truncated=$truncated · characters=${part.length} · dispatched=true")
                if (finished != true || !complete || truncated || part.isBlank()) return null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A revoked research permission must abort delivery, never expose cached text.
                if (isAuthorizationFailure(error.message.orEmpty())) throw error
                AppLogRecorder.record("Research", "REPORT_CHAPTER_FAILED · chapter=${index + 1} · reason=${error.javaClass.simpleName}", "W")
                return null
            }
            val written = reportSections(part.toString())
            if (missing.any { heading -> written[heading]?.let { reusableSection(it, packet) } != true }) return null
            missing.forEach { heading ->
                val body = written.getValue(heading)
                saveSection(digest, heading, body)
                reusable[heading] = body
            }
            if (report.isNotEmpty()) report.append("\n\n")
            report.append(headings.joinToString("\n\n") { reusable.getValue(it) })
        }
        val proposed = report.toString()
        return proposed.takeIf { ResearchDeliveryValidator.validate(it, packet).accepted }
    }

    private fun reusableSection(body: String, packet: ResearchDeliveryPacket): Boolean {
        if (body.isBlank()) return false
        val global = listOf("MISSING_SECTION:", "OMITTED_LEADING_STUDY:", "OMITTED_UNREAD_LEAD:", "DIRECT_REFERENCE_MISSING:", "PARTIAL_COVERAGE_NOT_DISCLOSED", "REVIEW_STATUS_NOT_DISCLOSED")
        val issues = ResearchDeliveryValidator.validate(body, packet).issues.filter { issue -> global.none(issue::startsWith) }
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
        private fun isAuthorizationFailure(message: String): Boolean = listOf(
            "DISPATCH_POLICY_CHANGED",
            "PERMISSION_DENIED",
            "SOURCE_PERMISSION_REVOKED",
            "MEMORY_STALE"
        ).any { message.contains(it, ignoreCase = true) }
        private const val MAX_REPORT_CHARACTERS = 100_000
    }
}
