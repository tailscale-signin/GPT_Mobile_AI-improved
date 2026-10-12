package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Both arms see the same synthetic evidence. No claim of savings is made from input reduction alone. */
@Serializable
data class PairedDelegationComparison(
    val rubricVersion: Int = 2,
    val taskClass: String = "supplied-evidence-extraction",
    val primaryOnlyMillis: Long,
    val delegatedMillis: Long,
    val primaryOnlyTokens: Long?,
    val delegatedTokens: Long?,
    val primaryOnlyFacts: Int,
    val delegatedFacts: Int,
    val requiredFacts: Int = 5,
    val tokenProvenance: String,
    val cost: String = "Unavailable",
    val order: String,
    val policyFingerprint: String,
    val warmState: String = "Not controlled; paired order alternates by seed"
)

internal fun pairedFixtureScore(answer: String, code: String, mass: Int): Int {
    val text = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val fields = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return 0
    if (fields.keys != setOf("parcel_code", "current_mass_kg", "observed_on", "approved", "source_url")) return 0
    fun string(key: String) = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val weight = (fields["current_mass_kg"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    val approved = (fields["approved"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    return listOf(string("parcel_code") == code, weight == mass, string("observed_on") == "2026-10-10", approved == false, string("source_url") == "https://example.org/parcel").count { it }
}
