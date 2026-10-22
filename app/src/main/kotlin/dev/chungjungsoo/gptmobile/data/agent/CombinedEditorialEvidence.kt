package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Model-facing provenance is compact; immutable snapshot records remain the audit authority. */
internal fun compactEditorialEvidence(ledger: dev.chungjungsoo.gptmobile.data.agent.CombinedEvidenceLedger) = buildJsonObject {
    put("version", ledger.version)
    put(
        "records",
        JsonArray(
            ledger.records.map { record ->
                buildJsonObject {
                    put("id", record.id)
                    put("url", record.url)
                    put("title", record.title)
                    put("publishedAt", record.publishedAt)
                    put("retrieval", record.retrieval)
                    record.work?.let { work ->
                        put(
                            "work",
                            buildJsonObject {
                                put("id", work.id)
                                put("independenceGroup", work.independenceGroup)
                                put("doi", work.doi)
                                put("pmid", work.pmid)
                                put("pmcid", work.pmcid)
                                put("version", work.version)
                            }
                        )
                    }
                    val excerpts = record.retainedExcerpts.distinctBy { it.digest to it.retrieval }.take(3)
                    put(
                        "excerpts",
                        JsonArray(
                            excerpts.map { excerpt ->
                                buildJsonObject {
                                    put("id", excerpt.id)
                                    put("text", excerpt.text.take(1600))
                                    put("truncated", excerpt.text.length > 1600)
                                    put("retrieval", excerpt.retrieval)
                                }
                            }
                        )
                    )
                    if (excerpts.isEmpty() && record.passage.isNotBlank()) put("passage", record.passage.take(1600))
                }
            }
        )
    )
    put("failedTools", JsonArray(ledger.failedTools.map(::JsonPrimitive)))
    put("trustedDateObservation", buildJsonObject { ledger.trustedDateObservation.forEach { (key, value) -> put(key, value) } })
    put(
        "supportEdges",
        JsonArray(
            ledger.supportEdges.map { edge ->
                buildJsonObject {
                    put("claimId", edge.claimId)
                    put("evidenceId", edge.evidenceId)
                    put("excerptId", edge.excerptId)
                    put("relation", edge.relation)
                    put("applicability", JsonArray(edge.applicability.map(::JsonPrimitive)))
                    put("limitations", JsonArray(edge.limitations.map(::JsonPrimitive)))
                }
            }
        )
    )
}
