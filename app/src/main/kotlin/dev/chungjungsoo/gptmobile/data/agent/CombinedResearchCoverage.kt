package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Structural question coverage is separate from factual verification; source support is checked elsewhere. */
internal data class CombinedResearchObligation(val id: String, val question: String, val sourceIds: Set<String>) {
    companion object {
        fun fromPrompt(prompt: String): List<CombinedResearchObligation> = runCatching {
            val root = Json.parseToJsonElement(prompt) as JsonObject
            (root["question_obligations"] as? JsonArray).orEmpty().mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                // General answer requirements are validated by CombinedFinalCheck;
                // they do not require a research citation for a greeting or a list.
                if ((row["kind"] as? JsonPrimitive)?.content == "REQUEST_CONTRACT") return@mapNotNull null
                val id = (row["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val question = (row["question"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CombinedResearchObligation(id, question, (row["source_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet())
            }
        }.getOrDefault(emptyList())
    }
}

internal fun researchQuestionChecks(answer: String, obligations: List<CombinedResearchObligation>): List<CombinedAnswerCheck> {
    val paragraphs = answer.split(Regex("\\n\\s*\\n"))
    return obligations.map { obligation ->
        val terms = CombinedClaimMatcher.fingerprint(obligation.question).terms
        val located = terms.isNotEmpty() &&
            paragraphs.any { paragraph ->
                val found = CombinedClaimMatcher.fingerprint(paragraph).terms.intersect(terms).size
                val limitation = Regex("(?i)\\b(?:unresolved|unknown|not established|insufficient evidence|unable to determine|cannot determine|no (?:reliable |direct )?evidence|evidence (?:is |remains )?unavailable)\\b").containsMatchIn(paragraph)
                val cited = obligation.sourceIds.any { "[$it]" in paragraph || Regex("\\[[^]\\n]*\\b${Regex.escape(it)}\\b[^]\\n]*]").containsMatchIn(paragraph) }
                found >= maxOf(1, kotlin.math.ceil(terms.size * 0.6).toInt()) &&
                    (limitation || paragraph.length > obligation.question.length + 20 && cited)
            }
        CombinedAnswerCheck("RESEARCH_QUESTION_UNRESOLVED:${obligation.id}", "RESEARCH_SCOPE", if (located) "PASS" else "FAIL", message = obligation.question)
    }
}
