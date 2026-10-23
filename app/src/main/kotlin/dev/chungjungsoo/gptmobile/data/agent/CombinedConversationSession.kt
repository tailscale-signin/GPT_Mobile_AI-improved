package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.presentation.ui.chat.CombinedContributionQuality
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object CombinedConversationSession {
    data class Prepared(val answer: String, val session: AgentProviderSession)
    fun create(snapshot: CombinedInputSnapshot): AgentProviderSession? = prepare(snapshot)?.session
    fun prepare(snapshot: CombinedInputSnapshot): Prepared? {
        if (snapshot.combined?.requested?.singlePass == true) return null
        val intent = CurrentRequestContract.from(snapshot.userMessage.content).intent
        if (intent !in setOf("GREETING", "TEST", "PROVIDER_TEST")) return null
        val source = snapshot.sources.firstOrNull { CombinedContributionQuality.currentRequestContribution(snapshot.userMessage.content, it.content).isNotBlank() } ?: return null
        val text = if (intent == "PROVIDER_TEST") {
            val participants = snapshot.combined?.requested?.participantUids ?: snapshot.sources.map { it.platformUid }
            "Provider test results:\n\n" + participants.joinToString("\n") { uid ->
                val response = snapshot.sources.firstOrNull { it.platformUid == uid }
                "- ${response?.platformName ?: uid}: " + when (response?.completionStatus) {
                    "COMPLETED" -> "acknowledgment received"
                    null -> "no usable acknowledgment retained"
                    else -> "partial acknowledgment retained; attempt ${response.completionStatus.lowercase()}"
                }
            }
        } else {
            CombinedContributionQuality.currentRequestContribution(snapshot.userMessage.content, source.content)
        }
        val contributors = if (intent == "PROVIDER_TEST") snapshot.sources.indices.map { "C${it + 1}" } else listOf("C${snapshot.sources.indexOf(source) + 1}")
        val passage = buildJsonObject {
            put("type", "passage")
            put("seq", 1)
            put("id", "P1")
            put("text", text)
            put("claims", JsonArray(emptyList()))
            put("contributors", JsonArray(contributors.map(::JsonPrimitive)))
            put("evidence", JsonArray(emptyList()))
        }.toString()
        return Prepared(
            text,
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    emit(ProviderEvent.Notice(if (intent == "PROVIDER_TEST") "Provider test results assembled from retained attempt outcomes; no additional writer request was needed." else "Brief acknowledgment from ${source.platformName}; no additional writer request was needed.", persistent = true))
                    emit(ProviderEvent.TextDelta(passage + "\n" + "{\"type\":\"end\",\"seq\":2,\"id\":\"END\",\"lastSeq\":1}"))
                    emit(ProviderEvent.Completed)
                }
            }
        )
    }
}
