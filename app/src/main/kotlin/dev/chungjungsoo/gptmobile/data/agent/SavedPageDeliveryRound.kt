package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Identify the specific missing receipt without including page contents, arguments or cursor text. */
internal fun JsonObjectBuilder.replaySource(exchanges: List<AgentToolExchange>, id: String?, expectedDigest: String? = null) {
    if (id == null) return
    val call = exchanges.asSequence().flatMap { it.calls.asSequence() }.firstOrNull { it.callId == id }
    val result = exchanges.asSequence().flatMap { it.results.asSequence() }.firstOrNull { it.callId == id }
    dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.identifierHash("call", id)?.let { put("replay_call_hash", it) }
    call?.name?.takeIf { it in dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.operations }?.let { put("operation", it) }
    val receipt = result?.executionReceipt
    val source = ((receipt?.get("saved_view_source_sha256") ?: receipt?.get("saved_view_source_hash")) as? kotlinx.serialization.json.JsonPrimitive)?.content
    source?.takeIf(ToolResultDeliveryProof::valid)?.let { put("replay_source_hash", it) }
    expectedDigest?.takeIf(ToolResultDeliveryProof::valid)?.let { put("replay_expected_body_hash", it) }
}

/** A bounded primary-model read; its provisional notes are checkpointed, never published as an answer. */
internal class SavedPageDeliveryRound private constructor(private val expectedProjection: String) : AbstractCoroutineContextElement(Key) {
    @Volatile private var freshRequestCompleted = false

    companion object Key : CoroutineContext.Key<SavedPageDeliveryRound> {
        fun blocked(message: String, stage: String, measurements: JsonObjectBuilder.() -> Unit = {}): ProviderEvent.Failed = ProviderEvent.Failed(
            message,
            errorCode = "REPLAY_EVIDENCE_REQUIRED",
            taskOutcome = "BLOCKED",
            failureDetails = buildJsonObject {
                put("failed_stage", stage)
                put("failure_origin", "APPLICATION")
                put("logical_continuation_allowed", false)
                if (stage in setOf("planning", "provider_admission")) put("dispatched", false)
                measurements()
            }
        )

        /** Called only around actual provider adapters, never around cached/decorated sessions. */
        fun providerRound(exchanges: List<AgentToolExchange>, events: Flow<ProviderEvent>): Flow<ProviderEvent> = flow {
            val read = currentCoroutineContext()[Key]
            if (read != null || SavedPageReading.present(exchanges)) dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore.requireUnprotectedTransport()
            var failed = false
            events.collect { event ->
                if (event is ProviderEvent.Failed) failed = true
                if (event == ProviderEvent.Completed && !failed && read != null && read.expectedProjection == SavedPageReading.projectionDigest(exchanges)) read.freshRequestCompleted = true
                emit(event)
            }
        }

        fun read(session: AgentProviderSession, exchanges: List<AgentToolExchange>, maxNoteBytes: Int = 1536, retainNotes: (String) -> Unit = {}): Flow<ProviderEvent> = flow {
            require(maxNoteBytes in 1..1536)
            val read = SavedPageDeliveryRound(SavedPageReading.projectionDigest(exchanges))
            val notes = StringBuilder()
            var failed = false
            var completed = false
            var reportedFailure = false
            var firstReason: String? = null
            fun reject(reason: String) {
                if (firstReason == null) firstReason = reason
                failed = true
            }
            session.streamRound(emptyList(), exchanges).flowOn(read).collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> {
                        if (event.lineage.origin !in setOf(OutputOrigin.MODEL_TEXT, OutputOrigin.UNKNOWN)) {
                            reject("UNSUPPORTED_OUTPUT")
                        } else {
                            if (notes.length.toLong() + event.text.length <= maxNoteBytes && !failed) {
                                notes.append(event.text)
                                if (notes.toString().toByteArray(Charsets.UTF_8).size > maxNoteBytes) reject("NOTE_LIMIT")
                            } else {
                                reject("NOTE_LIMIT")
                            }
                        }
                    }
                    is ProviderEvent.Failed -> {
                        failed = true
                        reportedFailure = true
                        emit(event)
                    }
                    is ProviderEvent.ToolCall, is ProviderEvent.ToolResult, is ProviderEvent.CombinedAnswer -> reject("UNSUPPORTED_OUTPUT")
                    ProviderEvent.Completed -> completed = true
                    is ProviderEvent.Usage, is ProviderEvent.RequestConfigured -> emit(event)
                    else -> Unit
                }
            }
            if (!failed && completed && read.freshRequestCompleted && notes.isNotBlank()) {
                retainNotes(notes.toString())
                emit(ProviderEvent.Completed)
            } else if (!reportedFailure) {
                val reason = when {
                    failed -> "The saved-page response contained unsupported output or exceeded its note allowance."
                    !completed || !read.freshRequestCompleted -> "The saved-page request has no matching fresh completion."
                    else -> "The saved-page response returned empty reading notes."
                }
                emit(
                    blocked("$reason Its pending pages remain checkpointed.", "response_stream") {
                        put("replay_missing_record", firstReason ?: if (!completed || !read.freshRequestCompleted) "FRESH_PROVIDER_COMPLETION" else "READING_NOTES")
                        val id = exchanges.firstOrNull()?.calls?.firstOrNull()?.callId
                        replaySource(exchanges, id)
                    }
                )
            }
        }
    }
}
