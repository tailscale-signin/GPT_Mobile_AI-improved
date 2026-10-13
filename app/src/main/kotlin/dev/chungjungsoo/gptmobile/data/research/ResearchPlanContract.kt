package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ResearchQuestion(val id: String, val text: String, val evidenceNeeded: String = "") {
    fun json() = buildJsonObject {
        put("id", id)
        put("text", text)
        put("evidenceNeeded", evidenceNeeded)
    }
}

data class ResearchQuery(val id: String, val text: String, val questionIds: List<String>)
data class ResearchPlanContract(val questions: List<ResearchQuestion>, val queries: List<ResearchQuery>, val noResearchNeeded: Boolean = false, val fallback: Boolean = false) {
    companion object {
        /** Explicit adapters for old strings, observed question objects, and versioned rows. */
        fun parse(value: JsonObject): ResearchPlanContract {
            val questions = parseQuestions(value["questions"] as? JsonArray ?: error("Missing questions array"))
            val rows = value["queries"] as? JsonArray ?: error("Missing queries array")
            require(rows.size <= 64) { "Too many research queries" }
            val queries = rows.mapIndexed { index, row ->
                val objectRow = row as? JsonObject
                val text = if (row is JsonPrimitive && row.isString) row.content else objectRow?.text("text").orEmpty().ifBlank { objectRow?.text("query").orEmpty() }
                require(text.isNotBlank() && text.length <= 500) { "Invalid query text" }
                val refs = objectRow?.strings("questionIds") ?: questions.map { it.id }
                require(refs.all { ref -> questions.any { it.id == ref } }) { "Query references an unknown question" }
                ResearchQuery(objectRow?.text("id").orEmpty().ifBlank { "QRY${index + 1}" }, text, refs)
            }
            val unnecessary = (value["noResearchNeeded"] as? JsonPrimitive)?.booleanOrNull == true
            require(questions.isNotEmpty() || unnecessary && queries.isEmpty()) { "Empty plan is not an explicit no-research decision" }
            require(queries.map { it.id }.distinct().size == queries.size)
            return ResearchPlanContract(questions, queries, unnecessary)
        }

        fun fallback(task: String) = ResearchPlanContract(
            listOf(ResearchQuestion("Q1", task.take(350).ifBlank { "Resolve the original task" }, "Relevant evidence with explicit source and limitations")),
            listOf(ResearchQuery("QRY1", task.take(500).ifBlank { "original task evidence" }, listOf("Q1"))),
            fallback = true
        )
    }
}

fun parseQuestions(rows: JsonArray): List<ResearchQuestion> {
    require(rows.size <= 64) { "Too many research questions" }
    return rows.mapIndexed { index, row ->
        val obj = row as? JsonObject
        val text = if (row is JsonPrimitive && row.isString) row.content else obj?.text("text").orEmpty().ifBlank { obj?.text("question").orEmpty() }
        val evidence = obj?.text("evidenceNeeded").orEmpty().ifBlank { obj?.text("evidence_needed").orEmpty() }
        val id = obj?.text("id").orEmpty().ifBlank { "Q${index + 1}" }
        require(text.isNotBlank() && text.length <= 1000 && evidence.length <= 2000 && id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid research question" }
        ResearchQuestion(id, text, evidence)
    }.also { require(it.map { q -> q.id }.distinct().size == it.size) { "Duplicate question IDs" } }
}
