package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Only extractive, validated fields can produce a grounded, version-bound record. */
internal object ResearchOutcomeBuilder {
    fun build(version: ResearchWorkVersion, fields: List<StudyField>, passages: List<EvidencePassage>, at: Long): List<ResearchOutcome> {
        if (version.coverage in setOf(ArticleCoverage.METADATA_ONLY, ArticleCoverage.UNAVAILABLE)) return emptyList()
        val byId = passages.associateBy { it.passageId }
        return fields.groupBy { it.outcomeKey }.mapNotNull { (key, group) ->
            val supported = group.filter { field ->
                val ids = runCatching { Json.decodeFromString<List<String>>(field.passageIdsJson) }.getOrDefault(emptyList())
                field.missingness == Missingness.PRESENT &&
                    !field.value.isNullOrBlank() &&
                    ids.isNotEmpty() &&
                    ids.all { byId[it]?.versionId == version.versionId } &&
                    ids.any { field.value in byId.getValue(it).quote }
            }
            if (supported.none { it.name == "outcome" } || supported.none { it.name == "effect" }) return@mapNotNull null
            val reported = supported.filter { it.name != "limitations" }.sortedBy { it.name }.joinToString("; ") { "${it.name}: ${it.value}" }
            val limitations = supported.filter { it.name == "limitations" }.joinToString("; ") { it.value.orEmpty() }.ifBlank { "Limitations not reported in the extracted passages; source assessment remains incomplete." }
            val ids = supported.flatMap { Json.decodeFromString<List<String>>(it.passageIdsJson) }.distinct().sorted()
            ResearchOutcome(researchHash("${version.versionId}|$key|$reported|$limitations|$ids"), version.workId, version.versionId, key, reported, limitations, Json.encodeToString(ids), "GROUNDED", at)
        }
    }
}
