package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.assist.AssistHandoff
import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Complete source-linked packets; no marker-only receipt can stand in for missing evidence. */
internal object ResearchAssistanceHandoff {
    fun packets(snapshot: ResearchSnapshot, root: String): List<JsonObject> {
        val local = snapshot.modelEvents.filter { it.local && it.outcome == "SUCCESS" }.mapNotNull { it.invocationId }.toSet()
        return snapshot.claims.mapNotNull { claim ->
            val producers = setOf(claim.extractionInvocationId, claim.reviewInvocationId).filter { it in local }
            val source = snapshot.sources.firstOrNull { it.id == claim.sourceId } ?: return@mapNotNull null
            if (producers.isEmpty() || !claim.exactQuoteVerified) return@mapNotNull null
            val identity = researchHash(source.versionId + source.passage)
            buildJsonObject {
                put("artifactType", "research_claim")
                put("sourceId", source.id)
                put("sourceUrl", source.url)
                put("sourceVersion", source.versionId)
                put("claim", claim.json())
                put(
                    "assistHandoffs",
                    JsonArray(
                        producers.map { id ->
                            Json.parseToJsonElement(Json.encodeToString(AssistHandoff(artifactId = researchHash("$root|$id|${claim.json()}"), rootWorkId = root, snapshotHash = identity, producerInvocationId = id, selectedIds = listOf(source.id))))
                        }
                    )
                )
            }
        }.distinct()
    }

    fun withinBudget(packets: List<JsonObject>, tokenAllowance: Long): List<JsonObject> {
        var used = 0L
        return packets.filter { packet ->
            val cost = ContextBudgetService.estimate(packet.toString()).toLong() + 64
            if (used + cost > tokenAllowance.coerceAtLeast(0)) {
                false
            } else {
                used += cost
                true
            }
        }
    }
}
