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
    private val create: suspend (String, Boolean) -> AgentProviderSession
) : AgentProviderSession {
    override val handlesToolsInternally = base.handlesToolsInternally
    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        var evidence = withContext(Dispatchers.Default) { packet() }
        if (evidence == null && !expectResearch) {
            emitAll(base.streamRound(tools, exchanges))
            return@flow
        }
        fun context(packet: ResearchDeliveryPacket) = packet.instruction() + "\nUntrusted research packet:\n" + packet.json()
        val initialContext = evidence?.let(::context).orEmpty()
        var hasCalls = false
        var failed = false
        var completed = false
        var overflow = false
        val draft = StringBuilder()
        emit(ProviderEvent.Notice("Preparing the research report and checking its citations."))
        // A complete captured corpus is ready for writing: it must not reopen tool execution.
        // Incomplete acquisition still retains its authorized tool rounds.
        val writeOnly = evidence?.snapshot?.complete == true
        val draftSession = if (initialContext.isEmpty()) base else create(initialContext, writeOnly)
        draftSession.streamRound(if (writeOnly) emptyList() else tools, if (writeOnly) emptyList() else exchanges).transformWhile { event ->
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
                    failed = true
                    emit(event)
                }
                ProviderEvent.Completed -> completed = true
                else -> emit(event)
            }
        }
        if (hasCalls || failed || !completed && !overflow) {
            if (draft.isNotEmpty() && !failed) emit(ProviderEvent.TextDelta(draft.toString()))
            if (completed) emit(ProviderEvent.Completed)
            return@flow
        }
        if (handlesToolsInternally || evidence == null) evidence = withContext(Dispatchers.Default) { packet() }
        val delivery = evidence
        if (delivery == null) {
            emit(ProviderEvent.TextDelta(draft.toString()))
            emit(ProviderEvent.Completed)
            return@flow
        }
        var text = draft.toString()
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
            val repaired = withContext(Dispatchers.Default) { ResearchDeliveryValidator.validate(repair.toString(), delivery) }
            val repairedAccepted = !repairFailed && repairCompleted && repaired.accepted
            // Long reports often overflow a provider's per-answer cap. Two isolated
            // text-only sections preserve evidence without redoing the acquisition.
            val sectioned = if (!repairedAccepted && delivery.detail != ResearchReportDetail.BRIEF) {
                emit(ProviderEvent.Notice("Writing the remaining research sections in smaller, source-checked segments."))
                trySectionedRepair(delivery, context(delivery))
            } else {
                null
            }
            val sectionedAccepted = sectioned != null
            text = when {
                repairedAccepted -> repair.toString()
                sectioned != null -> sectioned
                else -> ResearchDeliveryValidator.fallback(delivery)
            }
            if (!repairedAccepted && !sectionedAccepted) {
                emit(ProviderEvent.Notice("The generated report did not pass evidence checks. Delivering a clearly labelled partial evidence report instead."))
            }
            AppLogRecorder.record("Research", "DELIVERY_VALIDATED · repaired=true · accepted=${repairedAccepted || sectionedAccepted} · sectioned=$sectionedAccepted · partialFallback=${!repairedAccepted && !sectionedAccepted} · structuralIssues=${repaired.issues.joinToString()} · semanticCoverageVerified=false")
        } else {
            AppLogRecorder.record("Research", "DELIVERY_VALIDATED · repaired=false · accepted=true · cited=${result.citedIds.size} · semanticCoverageVerified=false")
        }
        emit(ProviderEvent.TextDelta(text))
        emit(ProviderEvent.Completed)
    }

    /** Each chapter is a separate tool-free request; it may fail without losing captured evidence. */
    private suspend fun trySectionedRepair(packet: ResearchDeliveryPacket, sourceContext: String): String? {
        val sections = ResearchDeliveryPacket.SECTIONS.chunked(4)
        val report = StringBuilder()
        for ((index, headings) in sections.withIndex()) {
            val part = StringBuilder()
            var complete = false
            val instruction = sourceContext +
                "\nWrite ONLY the following report sections (Markdown ## headings), in order: " +
                headings.joinToString("; ") +
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
                            else -> Unit
                        }
                    }
                    true
                }
                if (finished != true || !complete || part.isBlank()) return null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A revoked research permission must abort delivery, never expose cached text.
                if (isAuthorizationFailure(error.message.orEmpty())) throw error
                return null
            }
            if (report.isNotEmpty()) report.append("\n\n")
            report.append(part)
        }
        val proposed = report.toString()
        return proposed.takeIf { ResearchDeliveryValidator.validate(it, packet).accepted }
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
