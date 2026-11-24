package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Preserve the accepted wording while giving each explicit deliverable its own proof obligation. */
internal object GitHubAcceptanceContract {
    enum class Producer { COMPARISON_OBSERVATION, SOURCE_OBSERVATION, DEFECT_ANALYSIS, REVISION_AND_TESTS, REGRESSION_AND_TESTS, VALIDATION_JOBS, DIFF_REVIEW, DRAFT_PULL_REQUEST, EVIDENCE_REPORT, REQUEST_SPECIFIC }

    data class Criterion(
        val text: String,
        val sourceStart: Int?,
        val sourceEnd: Int?,
        val producer: Producer,
        val dependencies: List<String>
    ) {
        val id: String get() = GitHubRequestCompletion.id(text)

        fun json(): JsonObject = buildJsonObject {
            put("id", id)
            put("text", text)
            put("evidence_producer", producer.name)
            put("depends_on", JsonArray(dependencies.map(::JsonPrimitive)))
            if (sourceStart != null && sourceEnd != null) {
                put(
                    "source_span",
                    buildJsonObject {
                        put("start", sourceStart)
                        put("end_exclusive", sourceEnd)
                        put("unit", "UTF16_CODE_UNITS")
                    }
                )
            } else {
                put("source", "DERIVED_POLICY_OBLIGATION")
            }
        }
    }

    private const val VERB = "(?:inspect|read|identify|implement|fix|repair|add|run|validate|review|prepare|report|create|update|test|publish|merge|deploy|delete|remove|push|commit|install|cancel|rerun|apply)"
    private val start = Regex("(?i)^(?:please\\s+)?$VERB\\b")
    private val politePrefix = Regex("(?i)^please\\s+")
    private val separator = Regex("(?i)(?:[.;!?]\\s+|,\\s+(?:and\\s+)?|\\s+(?:and(?:\\s+then)?|then|also)\\s+)(?=(?:please\\s+)?$VERB\\b)")
    private val conditional = Regex("(?i)\\b(?:if|unless|until|when|provided that|only after)\\b")
    private val quoted = Regex("(?s)```.*?(?:```|\\z)|~~~.*?(?:~~~|\\z)|`[^`\\r\\n]*`|\"(?:\\\\.|[^\"\\\\])*\"|“[^”]*”|‘[^’]*’|(?<![\\p{L}\\p{N}])'[^'\\r\\n]*'")

    /** Ambiguous conditionals and quoted instructions stay intact; decomposition never grants authority. */
    fun split(text: String): List<String> {
        val masked = text.replace(quoted) { " ".repeat(it.value.length) }
        if (!start.containsMatchIn(masked) || conditional.containsMatchIn(masked) || masked.trimStart().startsWith(">")) return listOf(text)
        val cuts = separator.findAll(masked).toList()
        if (cuts.isEmpty()) return listOf(text)
        var position = 0
        val parts = cuts.map { cut ->
            text.substring(position, cut.range.first).trim().also { position = cut.range.last + 1 }
        } + text.substring(position).trim()
        return parts.takeIf { it.all { part -> start.containsMatchIn(part.replace(quoted) { " ".repeat(it.value.length) }) } } ?: listOf(text)
    }

    fun producer(text: String): Producer {
        val plain = text.replace(quoted) { " ".repeat(it.value.length) }.trim().removePrefix("- ").replaceFirst(politePrefix, "")
        if (conditional.containsMatchIn(plain) || split(text).size > 1) return Producer.REQUEST_SPECIFIC
        return when {
            GitHubReviewComparison.comparisonOnly(plain) -> Producer.COMPARISON_OBSERVATION
            Regex("(?i)^(?:inspect|read)\\b.*\\b(?:instructions?|source|code|tests?|files?)\\b").containsMatchIn(plain) -> Producer.SOURCE_OBSERVATION
            Regex("(?i)^identify\\b.*\\b(?:issue|bug|defect|correctness)\\b").containsMatchIn(plain) -> Producer.DEFECT_ANALYSIS
            Regex("(?i)^add\\b.*\\b(?:regression|tests?)\\b").containsMatchIn(plain) -> Producer.REGRESSION_AND_TESTS
            Regex("(?i)^(?:implement|fix|repair)\\b").containsMatchIn(plain) -> Producer.REVISION_AND_TESTS
            Regex("(?i)^(?:run|validate|test)\\b.*\\b(?:validation|tests?|checks?|build)\\b").containsMatchIn(plain) -> Producer.VALIDATION_JOBS
            Regex("(?i)^review\\b.*\\b(?:diff|changes)\\b").containsMatchIn(plain) -> Producer.DIFF_REVIEW
            Regex("(?i)^(?:prepare|create)\\s+(?:a |the )?draft (?:pull request|pr)\\b").containsMatchIn(plain) -> Producer.DRAFT_PULL_REQUEST
            Regex("(?i)^report\\b.*\\b(?:changes|validation|results)\\b").containsMatchIn(plain) -> Producer.EVIDENCE_REPORT
            else -> Producer.REQUEST_SPECIFIC
        }
    }

    fun criteria(task: String, requirements: List<String>): List<Criterion> {
        val request = GitHubTaskContinuity.request(task)
        val previous = mutableListOf<Criterion>()
        return requirements.map { text ->
            val location = request.indexOf(text).takeIf { it >= 0 }
            val producer = producer(text)
            val dependencies = when (producer) {
                Producer.DEFECT_ANALYSIS -> previous.filter { it.producer in setOf(Producer.COMPARISON_OBSERVATION, Producer.SOURCE_OBSERVATION) }
                Producer.REVISION_AND_TESTS -> previous.filter { it.producer == Producer.DEFECT_ANALYSIS }
                Producer.REGRESSION_AND_TESTS, Producer.VALIDATION_JOBS, Producer.DIFF_REVIEW, Producer.DRAFT_PULL_REQUEST ->
                    previous.filter { it.producer in setOf(Producer.REVISION_AND_TESTS, Producer.REGRESSION_AND_TESTS) }
                Producer.EVIDENCE_REPORT -> previous
                else -> emptyList()
            }.map(Criterion::id)
            Criterion(text, location, location?.plus(text.length), producer, dependencies).also(previous::add)
        }
    }

    fun json(task: String, requirements: List<String>): JsonObject = buildJsonObject {
        val request = GitHubTaskContinuity.request(task)
        put("schema", "github.acceptance-contract.v1")
        put("request_digest", GitHubEvidenceBook.digest(request))
        put("original_request", request)
        put("criteria", JsonArray(criteria(task, requirements).map(Criterion::json)))
        put("constraint_scope", "The entire accepted request, authorization, budgets and deadline apply to every criterion; splitting does not broaden authority or renew limits.")
    }

    /** Add a compiled view of a legacy frozen request without changing its accepted limits or wording. */
    fun refineRetained(contract: JsonObject): JsonObject {
        val request = (contract["requested_actions"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return contract
        if (contract["request_digest"] != JsonPrimitive(GitHubEvidenceBook.digest(request))) return contract
        val compiled = contract["acceptance_contract"]
        val accepted = contract["acceptance_criteria"] as? JsonArray
        if (accepted != null && accepted.all { it is JsonPrimitive && it.isString }) {
            val old = accepted.map { (it as JsonPrimitive).content }
            val standaloneHeadings = GitHubTaskContinuity.request(request).lineSequence().map(String::trim)
                .filter { GitHubRequestCompletion.sectionTitle(it) != null }.toSet()
            val requested = GitHubRequestCompletion.requirements(request).toSet()
            // A bullet can name an actual deliverable with the same text as a
            // heading. Only remove a legacy heading that has no substantive use.
            val retained = old.filterNot { it in standaloneHeadings && it !in requested }
            if (retained.isNotEmpty() && retained.size < old.size && (compiled == null || compiled == json(request, old))) {
                return JsonObject(contract + mapOf("acceptance_criteria" to JsonArray(retained.map(::JsonPrimitive)), "acceptance_contract" to json(request, retained)))
            }
        }
        if (compiled != null) return contract
        return JsonObject(contract + ("acceptance_contract" to json(request, GitHubRequestCompletion.requirements(request))))
    }
}
