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
    const val OBSERVATION_FEATURE = "ACCEPTANCE_OBSERVATION_CAUSES_V1"
    const val KEY = "publication_observation"
    const val TRUST_KEY = "trust_observation"
    private const val SCHEMA = "github.acceptance-publication.v1"
    private const val TRUST_SCHEMA = "github.receipt-trust.v1"

    enum class Source { STATUS, COVERAGE, CI_OBSERVATION, LOOKUP_OBSERVATION, CLAIM_EXTRACTION, PARTIAL_FALLBACK, EVIDENCE_ADVANCED }
    enum class Extraction { NOT_NEEDED, NO_DRAFT, PLAN_BATCH_LIMIT, PLAN_INPUT_LIMIT, PLAN_LIMIT_CHANGED, COMPLETED, FAILED }
    enum class Draft { ABSENT, EMPTY, PRESENT }
    enum class TrustFailure { TOOL_NOT_IN_AUTHORIZED_CATALOG, UNRECOGNIZED_EVIDENCE_OPERATION, TARGET_OUTSIDE_ACCEPTED_SCOPE }
    enum class Entry {
        EVIDENCE_RETENTION_INCOMPLETE,
        TASK_DEADLINE,
        APPROVAL_CHECKPOINT,
        SAVED_CLAIM_EXTRACTION,
        CI_OBSERVATION,
        LOOKUP_OBSERVATION,
        INTEGRATION_COOLDOWN,
        INTEGRATION_CHECKPOINT,
        INTEGRATION_BLOCKED,
        STATUS_COLLECTION,
        STATUS_SNAPSHOT,
        COVERAGE_CHECKPOINT,
        REVIEW_COMPARISON_BLOCKED,
        PROSE_ONLY_CORRECTION,
        COMPLETED_DRAFT,
        RETAINED_REPORT_UNAVAILABLE,
        RETAINED_OBSERVATIONS,
        RETAINED_FAILURE
    }

    private val enums = mapOf(
        "acceptance_publication_source" to Source.entries.map { it.name }.toSet(),
        "acceptance_extraction_observation" to Extraction.entries.map { it.name }.toSet(),
        "acceptance_draft_state" to Draft.entries.map { it.name }.toSet(),
        "acceptance_publication_entry" to Entry.entries.map { it.name }.toSet()
    )
    private val counts = setOf("acceptance_extraction_planned_batches", "acceptance_receipt_snapshot_count", "acceptance_claim_count", "acceptance_advertised_tool_count")
    private val tokens = setOf("acceptance_extraction_required_tokens", "acceptance_extraction_input_limit_tokens")
    val observationFields = setOf("acceptance_publication_entry", "acceptance_claim_trust_failures")
    val fields = enums.keys + counts + tokens + observationFields

    fun trustObservation(failure: TrustFailure): JsonObject = buildJsonObject {
        put("schema", TRUST_SCHEMA)
        put("first_failure", failure.name)
    }

    /** Only the original app-owned receipt can supply this historical decision. */
    fun recordedTrustFailure(receipt: JsonObject): TrustFailure? {
        if (receipt["schema"] != JsonPrimitive("github.evidence.v1") || receipt["trusted"] != JsonPrimitive(false)) return null
        val observed = receipt[TRUST_KEY] as? JsonObject ?: return null
        if (observed["schema"] != JsonPrimitive(TRUST_SCHEMA)) return null
        val value = (observed["first_failure"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        val failure = TrustFailure.entries.firstOrNull { it.name == value } ?: return null
        if (failure == TrustFailure.TARGET_OUTSIDE_ACCEPTED_SCOPE && receipt["repository_role"] != JsonPrimitive("UNAUTHORIZED_TARGET")) return null
        return failure
    }

    fun record(
        source: Source,
        receiptSnapshotCount: Int,
        draft: String?,
        extraction: Extraction?,
        plannedBatches: Int?,
        requiredTokens: Int? = null,
        inputLimitTokens: Int? = null,
        entry: Entry? = null,
        trustFailures: List<TrustFailure> = emptyList(),
        advertisedToolCount: Int? = null
    ): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        put("acceptance_publication_source", source.name)
        put("acceptance_receipt_snapshot_count", receiptSnapshotCount)
        // The earlier extraction belongs to another snapshot. Do not attach its
        // state or guard operands to the replacement report.
        if (source != Source.EVIDENCE_ADVANCED) {
            entry?.let { put("acceptance_publication_entry", it.name) }
            advertisedToolCount?.takeIf { it in 0..16_000_000 }?.let { put("acceptance_advertised_tool_count", it) }
            if (source == Source.CLAIM_EXTRACTION && trustFailures.isNotEmpty()) put("acceptance_claim_trust_failures", JsonArray(trustFailures.distinct().map { JsonPrimitive(it.name) }))
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
            val untrustedClaim = source == JsonPrimitive(Source.CLAIM_EXTRACTION.name) &&
                (verdict["unresolved_claims"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                    .any { it["status"] == JsonPrimitive("NOT_MEASURED") && it["reason"] == JsonPrimitive("UNTRUSTED_RECEIPT") }
            for (key in fields - setOf("acceptance_publication_source", "acceptance_receipt_snapshot_count", "acceptance_claim_count")) {
                // A restored marker must carry the matching local guard observation;
                // individually valid numbers cannot establish that a comparison ran.
                if (key in tokens && !observedLimit) continue
                if (key == "acceptance_claim_trust_failures" && !untrustedClaim) continue
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
        if (key == "acceptance_claim_trust_failures") {
            val values = value as? JsonArray ?: return null
            if (values.size !in 1..TrustFailure.entries.size || values.distinct().size != values.size) return null
            val allowed = TrustFailure.entries.map { it.name }.toSet()
            if (values.any { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.contentOrNull !in allowed }) return null
            return JsonArray(values.toList())
        }
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
