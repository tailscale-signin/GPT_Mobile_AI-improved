package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Local observations from the exact publication snapshot; never reconstructed from a failure message. */
internal object GitHubAcceptancePublicationEvidence {
    const val FEATURE = "ACCEPTANCE_PUBLICATION_EVIDENCE_V1"
    const val KEY = "publication_observation"
    private const val SCHEMA = "github.acceptance-publication.v1"

    enum class Source { STATUS, COVERAGE, CI_OBSERVATION, CLAIM_EXTRACTION, PARTIAL_FALLBACK, EVIDENCE_ADVANCED }
    enum class Extraction { NOT_NEEDED, NO_DRAFT, PLAN_BATCH_LIMIT, PLAN_INPUT_LIMIT, PLAN_LIMIT_CHANGED, COMPLETED, FAILED }
    enum class Draft { ABSENT, EMPTY, PRESENT }

    private val enums = mapOf(
        "acceptance_publication_source" to Source.entries.map { it.name }.toSet(),
        "acceptance_extraction_observation" to Extraction.entries.map { it.name }.toSet(),
        "acceptance_draft_state" to Draft.entries.map { it.name }.toSet()
    )
    private val counts = setOf("acceptance_extraction_planned_batches", "acceptance_receipt_snapshot_count", "acceptance_claim_count")
    private val tokens = setOf("acceptance_extraction_required_tokens", "acceptance_extraction_input_limit_tokens")
    val fields = enums.keys + counts + tokens

    fun record(
        source: Source,
        receiptSnapshotCount: Int,
        draft: String?,
        extraction: Extraction?,
        plannedBatches: Int?,
        requiredTokens: Int? = null,
        inputLimitTokens: Int? = null
    ): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        put("acceptance_publication_source", source.name)
        put("acceptance_receipt_snapshot_count", receiptSnapshotCount)
        // The earlier extraction belongs to another snapshot. Do not attach its
        // state or guard operands to the replacement report.
        if (source != Source.EVIDENCE_ADVANCED) {
            put(
                "acceptance_draft_state",
                when {
                    draft == null -> Draft.ABSENT
                    draft.isEmpty() -> Draft.EMPTY
                    else -> Draft.PRESENT
                }.name
            )
            extraction?.let { put("acceptance_extraction_observation", it.name) }
            plannedBatches?.let { put("acceptance_extraction_planned_batches", it) }
            if (extraction in setOf(Extraction.PLAN_INPUT_LIMIT, Extraction.PLAN_LIMIT_CHANGED)) {
                requiredTokens?.takeIf { it >= 0 }?.let { put("acceptance_extraction_required_tokens", it) }
                inputLimitTokens?.takeIf { it >= 0 }?.let { put("acceptance_extraction_input_limit_tokens", it) }
            }
        }
    }

    fun fields(verdict: JsonObject): JsonObject = buildJsonObject {
        val recorded = verdict[KEY] as? JsonObject ?: return@buildJsonObject
        if (recorded["schema"] != JsonPrimitive(SCHEMA)) return@buildJsonObject
        val source = recorded["acceptance_publication_source"]?.let { validValue("acceptance_publication_source", it) } ?: return@buildJsonObject
        val receipts = recorded["acceptance_receipt_snapshot_count"]?.let { validValue("acceptance_receipt_snapshot_count", it) } ?: return@buildJsonObject
        put("acceptance_publication_source", source)
        put("acceptance_receipt_snapshot_count", receipts)
        if (source != JsonPrimitive(Source.EVIDENCE_ADVANCED.name)) {
            val extraction = recorded["acceptance_extraction_observation"]?.let { validValue("acceptance_extraction_observation", it) }
            val observedLimit = extraction in setOf(JsonPrimitive(Extraction.PLAN_INPUT_LIMIT.name), JsonPrimitive(Extraction.PLAN_LIMIT_CHANGED.name))
            for (key in fields - setOf("acceptance_publication_source", "acceptance_receipt_snapshot_count", "acceptance_claim_count")) {
                // A restored marker must carry the matching local guard observation;
                // individually valid numbers cannot establish that a comparison ran.
                if (key in tokens && !observedLimit) continue
                recorded[key]?.let { validValue(key, it) }?.let { put(key, it) }
            }
        }
        // A missing array is unknown, including old verdicts. Never infer zero
        // from extraction failure or from an empty requirements/acceptance map.
        (verdict["claims"] as? JsonArray)?.let { claims ->
            validValue("acceptance_claim_count", JsonPrimitive(claims.size))?.let { put("acceptance_claim_count", it) }
        }
    }

    fun validValue(key: String, value: JsonElement): JsonElement? {
        val primitive = value as? JsonPrimitive ?: return null
        enums[key]?.let { allowed ->
            return primitive.takeIf { it.isString }?.contentOrNull?.takeIf { it in allowed }?.let(::JsonPrimitive)
        }
        val maximum = when (key) {
            in counts -> 16_000_000L
            in tokens -> Int.MAX_VALUE.toLong()
            else -> return null
        }
        return primitive.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0L..maximum }?.let(::JsonPrimitive)
    }
}
