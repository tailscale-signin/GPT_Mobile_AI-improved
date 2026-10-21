package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
data class AppraisalJudgment(val domain: String, val concern: ScientificConcern, val rationale: String, val passageIds: List<String>, val applicability: String)

@Serializable
enum class ContentInspection { UNKNOWN, INSPECTED, UNAVAILABLE, NOT_APPLICABLE }

@Serializable
data class ArticleSectionCoverage(
    val inspected: Set<String> = emptySet(),
    val required: Set<String> = setOf("methods", "results", "limitations"),
    val omitted: Set<String> = emptySet(),
    val figuresAvailable: Boolean? = null,
    val supplementsAvailable: Boolean? = null,
    val figuresInspection: ContentInspection = ContentInspection.UNKNOWN,
    val supplementsInspection: ContentInspection = ContentInspection.UNKNOWN,
    val figuresReason: String = "",
    val supplementsReason: String = "",
    val tablesInspection: ContentInspection = ContentInspection.UNKNOWN,
    val tablesReason: String = ""
) {
    // Old availability booleans are not proof that content was actually inspected.
    val complete: Boolean get() = required.isNotEmpty() &&
        inspected.containsAll(required) &&
        omitted.isEmpty() &&
        sufficient(figuresInspection, figuresReason) &&
        sufficient(supplementsInspection, supplementsReason) &&
        sufficient(tablesInspection, tablesReason)
    private fun sufficient(state: ContentInspection, reason: String) = state == ContentInspection.INSPECTED || state == ContentInspection.NOT_APPLICABLE && reason.isNotBlank()
}

data class ValidatedScientificAppraisal(val passages: List<EvidencePassage>, val fields: List<StudyField>, val judgments: List<AppraisalJudgment>, val design: StudyDesign, val limitations: List<String>)

/** Original rubric: concern explanations, never a numerical probability of scientific truth. */
internal object ScientificAppraisalContract {
    val domains = listOf("Design and allocation", "Measurement", "Missing data", "Analysis and reporting", "Applicability", "Transparency")
    val fieldNames = setOf("randomized_n", "analyzed_n", "arm_n", "follow_up", "design", "population", "intervention", "comparator", "outcome", "effect", "uncertainty", "funding", "registration", "data_availability", "conflicts", "limitations")
    fun rubric(design: StudyDesign): Map<String, String> {
        val specific = when (design) {
            StudyDesign.RANDOMIZED_TRIAL -> "Random sequence, allocation concealment, attrition by arm and intention-to-treat analysis"
            StudyDesign.OBSERVATIONAL -> "Selection, confounding, exposure definition, temporal order and adjusted versus unadjusted estimates"
            StudyDesign.DIAGNOSTIC -> "Patient spectrum, prespecified thresholds, reference standard and verification bias"
            StudyDesign.SYSTEMATIC_REVIEW -> "Search coverage, inclusion decisions, study-family overlap, heterogeneity and missing results"
            StudyDesign.QUALITATIVE -> "Sampling rationale, researcher reflexivity, analytic process and supporting quotations"
            StudyDesign.MACHINE_LEARNING -> "Independent evaluation, data leakage, baseline comparability, external validity and reproducibility"
            StudyDesign.PROTOCOL -> "Planned methods only; no observed effect is established"
            StudyDesign.COMMENTARY -> "Interpretive commentary is not an original empirical study"
            StudyDesign.UNKNOWN -> "Study design is unknown; avoid causal or generalizability conclusions"
        }
        return domains.associateWith { domain -> if (domain == domains.first()) specific else "$domain: identify reported methods and limitations using exact version-linked passages; unknown remains unknown" }
    }
    private fun string(row: JsonObject, key: String): String? = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun strings(row: JsonObject, key: String): List<String>? {
        val values = row[key] as? JsonArray ?: return null
        val parsed = values.mapNotNull { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content }
        return parsed.takeIf { it.size == values.size }
    }
    private fun reportsSampleCount(quote: String, field: String, value: String): Boolean {
        // Conservative local evidence gate, not an entailment/review verdict. A price,
        // year, follow-up duration or substring of another count is not a sample size.
        val label = when (field) {
            "randomized_n" -> "(?:randomi[sz]ed|randomly assigned)"
            "analyzed_n" -> "(?:analy[sz]ed|analysis)"
            else -> "(?:arm|group|allocated)"
        }
        val count = "(?<![\\p{L}\\p{N}.,])${Regex.escape(value)}(?![\\p{L}\\p{N}]|[.,][0-9])"
        val subjects = "(?:participants|patients|subjects|individuals|people|records|samples|studies|cases|animals|mice|rats)"
        val methods = "$label(?:\\s+(?:sample|population))?"
        return listOf(
            "$methods\\s*[:(]?\\s*n\\s*=\\s*$count",
            "$methods\\s+(?:(?:included|comprised|contained|involved|was|were)\\s+)?(?:a total of\\s+)?$count\\s+$subjects\\b",
            "$count\\s+$subjects\\s+(?:(?:was|were)\\s+)?(?:included\\s+in\\s+the\\s+)?$label\\b"
        ).any { Regex("(?i)\\b$it").containsMatchIn(quote) }
    }
    private fun sourceSpelling(value: String?, passages: List<EvidencePassage>, ignoreCase: Boolean = true): String? {
        if (value.isNullOrBlank()) return value
        if (passages.any { value in it.quote }) return value
        val literal = Regex(value.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        return passages.firstNotNullOfOrNull { literal.find(it.quote)?.value } ?: value
    }
    fun validate(reply: String, version: ResearchWorkVersion, source: String, retainFieldGaps: Boolean = false): ValidatedScientificAppraisal {
        val fieldGaps = mutableListOf<String>()
        require(reply.toByteArray().size <= 96 * 1024 && researchHash(source) == version.contentHash) { "Appraisal source hash changed or output exceeds 96 KiB" }
        val root = Json.parseToJsonElement(reply) as? JsonObject ?: error("Appraisal requires an object")
        require(root.keys.all { it in setOf("schemaVersion", "design", "passages", "fields", "judgments", "limitations") })
        require((root["schemaVersion"] as? JsonPrimitive)?.intOrNull == 1)
        val design = StudyDesign.valueOf(requireNotNull(string(root, "design")))
        val rawPassages = root["passages"] as? JsonArray ?: error("Passages missing")
        require(rawPassages.size <= 24)
        val passages = rawPassages.map { value ->
            val row = value as? JsonObject ?: error("Malformed passage")
            require(row.keys.all { it in setOf("id", "quote", "locator", "start") })
            val id = requireNotNull(string(row, "id"))
            val quote = requireNotNull(string(row, "quote"))
            val locator = requireNotNull(string(row, "locator"))
            val start = (row["start"] as? JsonPrimitive)?.intOrNull ?: error("Exact quotation offset required")
            require(id.matches(Regex("P[0-9]{1,3}")) && quote.length in 1..2000 && locator.length in 1..240 && start >= 0 && start <= source.length - quote.length && source.substring(start, start + quote.length) == quote) { "Passage $id must have a valid ID, locator and exact source quotation at its UTF-16 offset" }
            EvidencePassage("${version.versionId}:$id", version.versionId, locator, start, start + quote.length, researchHash(quote), quote)
        }
        require(passages.map { it.passageId }.distinct().size == passages.size)
        val byId = passages.associateBy { it.passageId.substringAfterLast(':') }
        fun resolve(ids: List<String>): List<EvidencePassage> {
            require(ids.size <= 12 && ids.distinct().size == ids.size)
            return ids.map { requireNotNull(byId[it]) { "Unknown passage" } }
        }
        val rawFields = root["fields"] as? JsonArray ?: error("Fields missing")
        require(rawFields.size <= 48)
        val fields = rawFields.map { value ->
            val row = value as? JsonObject ?: error("Malformed study field")
            require(row.keys.all { it in setOf("name", "outcomeKey", "value", "unit", "missingness", "passageIds") })
            val name = requireNotNull(string(row, "name"))
            require(name in fieldNames)
            val outcome = string(row, "outcomeKey")?.takeIf { it.length in 1..120 } ?: "overall"
            val missing = Missingness.valueOf(requireNotNull(string(row, "missingness")))
            val proposedValue = string(row, "value")?.takeIf { it.length <= 2000 }
            val proposedUnit = string(row, "unit")?.takeIf { it.length <= 80 }
            val cited = resolve(requireNotNull(strings(row, "passageIds")))
            // Resolve case/whitespace-only differences to actual source bytes, never a paraphrase.
            val fieldValue = if (retainFieldGaps) sourceSpelling(proposedValue, cited) else proposedValue
            // Unit capitalization is meaningful (for example, mM and mm).
            val unit = if (retainFieldGaps) sourceSpelling(proposedUnit, cited, ignoreCase = false) else proposedUnit
            // Keep strict schema and quote checks. A bad optional field must not discard
            // all the exact passages and other grounded fields in an inspected batch.
            val groundingIssue = if (missing == Missingness.PRESENT) {
                when {
                    fieldValue.isNullOrBlank() || cited.isEmpty() -> "Present field needs a value and source passages"
                    cited.none { fieldValue in it.quote } -> "Study value is not extractive"
                    unit != null && cited.none { unit in it.quote } -> "Unit not found in source"
                    name in setOf("randomized_n", "analyzed_n", "arm_n") &&
                        (fieldValue.replace(",", "").toLongOrNull()?.let { it > 0 } != true || cited.none { reportsSampleCount(it.quote, name, fieldValue) }) -> "Sample count is not directly linked to its reported methodological label"
                    else -> null
                }
            } else if (fieldValue != null || unit != null) {
                "Missing data cannot carry an invented value"
            } else {
                null
            }
            if (groundingIssue != null) {
                require(retainFieldGaps) { "$name ($outcome): $groundingIssue" }
                fieldGaps += "$name ($outcome) unavailable: $groundingIssue; model value discarded"
            }
            StudyField(
                researchHash("${version.versionId}|$outcome|$name"), version.versionId, outcome, name,
                fieldValue.takeIf { groundingIssue == null }, unit.takeIf { groundingIssue == null },
                if (groundingIssue == null) missing else Missingness.UNAVAILABLE,
                Json.encodeToString(cited.map { it.passageId }),
                if (groundingIssue == null) "validated_model_extraction" else "unverified_model_value_discarded"
            )
        }
        require(retainFieldGaps || fields.map { it.fieldId }.distinct().size == fields.size) { "Duplicate study field and outcome keys" }
        val retainedFields = if (retainFieldGaps) {
            fields.groupBy { it.fieldId }.values.map { duplicates -> duplicates.reduce { previous, next -> mergeScientificField(previous, next) } }
        } else {
            fields
        }
        val rawJudgments = root["judgments"] as? JsonArray ?: error("Judgments missing")
        require(rawJudgments.size <= domains.size)
        val judgments = rawJudgments.map { value ->
            val row = value as? JsonObject ?: error("Malformed judgment")
            require(row.keys.all { it in setOf("domain", "concern", "rationale", "passageIds", "applicability") })
            val domain = requireNotNull(string(row, "domain"))
            require(domain in domains)
            val concern = ScientificConcern.valueOf(requireNotNull(string(row, "concern")))
            val rationale = requireNotNull(string(row, "rationale"))
            require(rationale.length in 1..1600)
            val cited = resolve(requireNotNull(strings(row, "passageIds")))
            require(concern in setOf(ScientificConcern.UNKNOWN, ScientificConcern.NOT_APPLICABLE) || cited.isNotEmpty())
            AppraisalJudgment(domain, concern, rationale, cited.map { it.passageId }, string(row, "applicability")?.take(600).orEmpty())
        }
        require(judgments.map { it.domain }.distinct().size == judgments.size)
        val completeJudgments = domains.map { domain -> judgments.firstOrNull { it.domain == domain } ?: AppraisalJudgment(domain, ScientificConcern.UNKNOWN, "Not assessed from available source passages", emptyList(), "Coverage incomplete") }
        val limitations = requireNotNull(strings(root, "limitations"))
        require(limitations.size <= 12 && limitations.all { it.length in 1..1000 })
        return ValidatedScientificAppraisal(passages, retainedFields, completeJudgments, design, limitations + fieldGaps)
    }
    fun unknown(versionId: String, reason: String): EvidenceAppraisal = EvidenceAppraisal(researchHash("$versionId|unassessed"), versionId, "overall", 1, "deterministic-baseline-v1", StudyDesign.UNKNOWN, "UNASSESSED", Json.encodeToString(domains.map { AppraisalJudgment(it, ScientificConcern.UNKNOWN, reason.take(1000), emptyList(), "Source coverage insufficient for assessment") }), createdAt = System.currentTimeMillis())
}
