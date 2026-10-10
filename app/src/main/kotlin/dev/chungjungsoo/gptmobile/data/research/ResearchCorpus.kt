package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Frozen, bounded evidence passed to every Combined analyst in a single logical turn. */
data class ResearchCorpus(
    val snapshotVersion: Int,
    val sources: List<ResearchSource>,
    val claims: List<ResearchClaim>,
    val toolEvents: List<ResearchToolEvent>
) {
    fun json() = buildJsonObject {
        put("snapshotVersion", snapshotVersion)
        put("sources", JsonArray(sources.take(MAX_SOURCES).map { it.json() }))
        put("claims", JsonArray(claims.take(MAX_CLAIMS).map { it.json() }))
        put("toolEvents", JsonArray(toolEvents.takeLast(MAX_EVENTS).map { it.json() }))
    }

    companion object {
        private const val MAX_SOURCES = 120
        private const val MAX_CLAIMS = 30
        private const val MAX_EVENTS = 200

        fun freeze(snapshot: ResearchSnapshot): ResearchCorpus {
            val sources = snapshot.sources.take(MAX_SOURCES)
            val validClaims = validatedResearchClaims(
                snapshot.claims.map { claim ->
                    buildJsonObject {
                        put("text", claim.text)
                        put("sourceId", claim.sourceId)
                        put("quote", claim.quote)
                    }
                },
                sources
            ).map { valid ->
                snapshot.claims.firstOrNull { it.sourceId == valid.sourceId && it.text == valid.text && it.quote == valid.quote }
                    ?.takeIf { it.verdict in setOf("Supported", "Contradicted", "Insufficient") }
                    ?: valid
            }.take(MAX_CLAIMS)
            return ResearchCorpus(2, sources.toList(), validClaims.toList(), snapshot.toolEvents.takeLast(MAX_EVENTS).toList())
        }
    }
}
