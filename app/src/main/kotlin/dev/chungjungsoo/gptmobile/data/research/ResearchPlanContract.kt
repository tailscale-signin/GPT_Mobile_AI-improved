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
        fun parse(value: JsonObject, requiresEvidence: Boolean = false): ResearchPlanContract {
            require(value["schemaVersion"] == null || value.number("schemaVersion") == 1) { "Unsupported planner schema" }
            val questions = parseQuestions(value["questions"] as? JsonArray ?: error("Missing questions array"))
            val rows = value["queries"] as? JsonArray ?: error("Missing queries array")
            require(rows.size <= 64) { "Too many research queries" }
            val queries = rows.mapIndexed { index, row ->
                val objectRow = row as? JsonObject
                val text = if (row is JsonPrimitive && row.isString) row.content else objectRow?.planText("text").orEmpty().ifBlank { objectRow?.planText("query").orEmpty() }
                require(text.isNotBlank() && text.length <= 500) { "Invalid query text" }
                val refs = objectRow?.get("questionIds")?.let { references ->
                    require(references is JsonArray) { "Query questionIds must be an array" }
                    references.map { ref ->
                        require(ref is JsonPrimitive && ref.isString) { "Question references must be strings" }
                        ref.content
                    }
                } ?: questions.map { it.id }
                require(refs.isNotEmpty() && refs.distinct().size == refs.size) { "Query must reference distinct questions" }
                require(refs.all { ref -> questions.any { it.id == ref } }) { "Query references an unknown question" }
                val id = objectRow?.planText("id").orEmpty().ifBlank { "QRY${index + 1}" }
                require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid query ID" }
                ResearchQuery(id, text, refs)
            }
            val decision = value["noResearchNeeded"]
            require(decision == null || decision is JsonPrimitive && !decision.isString && decision.booleanOrNull != null) { "Invalid no-research decision" }
            val unnecessary = (decision as? JsonPrimitive)?.booleanOrNull == true
            require(!unnecessary || questions.isEmpty() && queries.isEmpty()) { "No-research decision conflicts with planned work" }
            require(!unnecessary || !requiresEvidence) { "Required evidence cannot be waived by the planner" }
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
        val text = if (row is JsonPrimitive && row.isString) row.content else obj?.planText("text").orEmpty().ifBlank { obj?.planText("question").orEmpty() }
        val evidence = obj?.planText("evidenceNeeded").orEmpty().ifBlank { obj?.planText("evidence_needed").orEmpty() }
        val id = obj?.planText("id").orEmpty().ifBlank { "Q${index + 1}" }
        require(text.isNotBlank() && text.length <= 1000 && evidence.length <= 2000 && id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid research question" }
        ResearchQuestion(id, text, evidence)
    }.also { require(it.map { q -> q.id }.distinct().size == it.size) { "Duplicate question IDs" } }
}

private fun JsonObject.planText(key: String): String {
    val value = get(key) ?: return ""
    require(value is JsonPrimitive && value.isString) { "$key must be a string" }
    return value.content
}
