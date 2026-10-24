package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** An editorial preference frozen with the exact prompt; a miss never starts another writer. */
@Serializable
data class CombinedLayoutContract(
    val version: Int = 1,
    val tableExpected: Boolean = false,
    val columns: List<String> = emptyList(),
    val sections: List<String> = emptyList(),
    val reason: String = "NARRATIVE",
    val instruction: String = "Use concise paragraphs and only meaningful headings."
) {
    fun checks(answer: String): List<CombinedAnswerCheck> = if (tableExpected && CombinedSourceClaims.tableRows(answer) < 1) {
        listOf(CombinedAnswerCheck("EXPECTED_COMPARISON_TABLE_MISSING", "LAYOUT", "FAIL", false, message = "The frozen layout expected a table. Preserve the answer and record this layout miss without rewriting it."))
    } else {
        listOf(CombinedAnswerCheck("FROZEN_LAYOUT", "LAYOUT", "PASS"))
    }

    companion object {
        fun from(request: String, sources: List<CombinedModelResponse>, policy: CombinedAnswerPolicy = CombinedAnswerPolicy.from(request)): CombinedLayoutContract {
            if (CurrentRequestContract.from(request).intent != "SUBSTANTIVE") return CombinedLayoutContract(reason = "BRIEF_CONVERSATION", instruction = "Reply briefly in one passage. No headings or table are needed.")
            val explicitNarrative = Regex("(?i)\\b(?:no tables?|without (?:a )?table|(?:one|single|a) paragraph|prose only|narrative|bullets?|bullet points?|numbered list)\\b").containsMatchIn(request)
            val explicitTable = policy.requiredFormat == "TABLE" && !explicitNarrative
            val comparable = CombinedClaimSemantics.records(sources).count { claim -> claim.occurrences.any { it.tableCells.isNotEmpty() } }
            val constrainedShort = policy.singleExample || (policy.maximumWords ?: policy.targetWords ?: Int.MAX_VALUE) < 80
            val expected = explicitTable || !explicitNarrative && !constrainedShort && (comparable >= 3 || (policy.comparative && (policy.requestedItems ?: sources.size) >= 3))
            return CombinedLayoutContract(
                tableExpected = expected,
                columns = if (expected) listOf("Topic", "Main point", "Qualification") else emptyList(),
                sections = policy.requiredTopics,
                reason = when {
                    explicitTable -> "USER_TABLE"
                    explicitNarrative -> "USER_FORMAT"
                    expected -> "COMPARABLE_RECORDS"
                    else -> "NARRATIVE"
                },
                instruction = if (expected) "Use one complete Markdown table for comparable records with two to four concise columns (prefer Topic, Main point, Qualification). Keep each value, unit, population, route and caveat together in its row. Put long explanations outside cells. Use few meaningful headings. Encode the entire validated table in one existing passage text field; do not invent JSON keys. Explicit user length and format limits take precedence." else "Use concise paragraphs and few meaningful headings. Preserve the requested format and length; no table is required."
            )
        }

        fun fromPrompt(prompt: String): CombinedLayoutContract? = runCatching {
            Json.parseToJsonElement(prompt).jsonObject["layout_contract"]?.let { Json.decodeFromJsonElement(serializer(), it) }
        }.getOrNull()
    }
}
