package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable

/** Both arms see the same synthetic evidence. No claim of savings is made from input reduction alone. */
@Serializable
data class PairedDelegationComparison(
    val rubricVersion: Int = 1,
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

internal fun pairedFixtureScore(answer: String, code: String, mass: Int): Int = listOf(
    hasExpectedParcelCode(answer, code),
    Regex("\\b$mass\\s*kg\\b", RegexOption.IGNORE_CASE).containsMatchIn(answer),
    "2026-10-10" in answer,
    answer.contains("not approved", true),
    "https://example.org/parcel" in answer
).count { it }
