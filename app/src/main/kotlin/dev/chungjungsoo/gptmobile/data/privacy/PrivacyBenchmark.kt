package dev.chungjungsoo.gptmobile.data.privacy

import kotlin.math.ceil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PrivacyBenchmarkCase(
    val id: String,
    val input: String,
    val sensitiveValues: List<String> = emptyList(),
    val requiredPhrases: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val customTerms: List<String> = emptyList()
)

internal data class PrivacyBenchmarkResult(
    val cases: Int,
    val protectedValues: Int,
    val expectedPrivateValues: Int,
    val preservedPhrases: Int,
    val expectedPublicPhrases: Int,
    val exactRestorations: Int,
    val isolatedMaps: Boolean,
    val failures: List<String>,
    val timingSamples: Int,
    val maskingMedianMicros: Long,
    val maskingP95Micros: Long,
    val restorationMedianMicros: Long,
    val restorationP95Micros: Long,
    val detectorRevision: String = PrivateTextDetector.REVISION,
    val measurementScope: String = "SYNTHETIC_LOCAL_CPU",
    val providerAnswerUtility: String = "NOT_MEASURED"
) {
    val passed: Boolean get() = failures.isEmpty() && isolatedMaps
}

/** An offline, cancellable acceptance probe. It never reads chats, custom settings or a provider. */
internal object PrivacyBenchmark {
    val cases = listOf(
        PrivacyBenchmarkCase("identity_field", "Name: Mira Elara Voss\nPlease draft a reply.", listOf("Mira Elara Voss"), listOf("Name:", "Please draft a reply.")),
        PrivacyBenchmarkCase("long_identity", "Full name: Nora Elin Anja Lise Mira Voss\nReview the terms.", listOf("Nora Elin Anja Lise Mira Voss"), listOf("Review the terms.")),
        PrivacyBenchmarkCase("lowercase_identity", "Full name: mira elara voss\nPlease draft a reply.", listOf("mira elara voss"), listOf("Full name:", "Please draft a reply.")),
        PrivacyBenchmarkCase("surname_history", "Dear Ms. Voss, please confirm receipt.", listOf("Voss"), listOf("please confirm receipt."), history = listOf("My name is Mira Voss.")),
        PrivacyBenchmarkCase("public_headings", "SERVICE AGREEMENT\nIndependent Contractor\nProject Overview\nPlease summarize the agreement.", requiredPhrases = listOf("SERVICE AGREEMENT", "Independent Contractor", "Project Overview", "Please summarize the agreement.")),
        PrivacyBenchmarkCase("artifact_name", "Create a file named Report. I am happy with the project outline.", requiredPhrases = listOf("Create a file named Report.", "I am happy with the project outline.")),
        PrivacyBenchmarkCase("family_travel", "My wife and I will travel on February 4th. Help draft a note.", listOf("February 4th"), listOf("My wife and I will travel on", "Help draft a note.")),
        PrivacyBenchmarkCase("license_intent", "Please get the license fixed and finish the project before Friday.", listOf("Friday"), listOf("Please get the license fixed and finish the project before")),
        PrivacyBenchmarkCase("ordinal_date", "My birthday is the 21st of March. Draft a short invitation.", listOf("21st of March"), listOf("Draft a short invitation.")),
        PrivacyBenchmarkCase("locale_amounts", "Fee: 1 234,50 EUR; deposit: CAD 800. Review the payment terms.", listOf("1 234,50 EUR", "CAD 800"), listOf("Fee:", "deposit:", "Review the payment terms.")),
        PrivacyBenchmarkCase("linked_domain", "Email: mira@cedarlab.example; website: https://cedarlab.example/team. Draft a reply.", listOf("mira@cedarlab.example", "cedarlab.example"), listOf("Draft a reply.")),
        PrivacyBenchmarkCase("residence", "I live in Larkspur. Find nearby classes.", listOf("Larkspur"), listOf("Find nearby classes.")),
        PrivacyBenchmarkCase("search_region", "Find language classes in Larkspur.", requiredPhrases = listOf("Find language classes in Larkspur.")),
        PrivacyBenchmarkCase("health_request", "My brothers are dealing with anxiety, but I want to ask for an extension.", listOf("My brothers are dealing with anxiety"), listOf("I want to ask for an extension.")),
        PrivacyBenchmarkCase("address", "Home address: 42 Willow Road\nPlease format the letter.", listOf("42 Willow Road"), listOf("Home address:", "Please format the letter.")),
        PrivacyBenchmarkCase("document_id", "Employee ID: ZX-672918\nCheck the form.", listOf("ZX-672918"), listOf("Employee ID:", "Check the form.")),
        PrivacyBenchmarkCase("project_name", "Project: Cedar Lantern\nSummarize its milestones.", listOf("Cedar Lantern"), listOf("Project:", "Summarize its milestones.")),
        PrivacyBenchmarkCase("credential", "password: fictional-example-only\nExplain how to rotate it.", listOf("fictional-example-only"), listOf("Explain how to rotate it.")),
        PrivacyBenchmarkCase("network", "The local host is 192.0.2.37. Explain the connection error.", listOf("192.0.2.37"), listOf("Explain the connection error.")),
        PrivacyBenchmarkCase("custom_term", "Please compare Amber Finch with the public option.", listOf("Amber Finch"), listOf("Please compare", "with the public option."), customTerms = listOf("Amber Finch"))
    )

    suspend fun run(iterations: Int = 5): PrivacyBenchmarkResult {
        require(iterations in 1..25)
        val maskTimes = mutableListOf<Long>()
        val restoreTimes = mutableListOf<Long>()
        val failures = linkedSetOf<String>()
        var privatePasses = 0
        var publicPasses = 0
        var restores = 0
        // The first complete pass warms the local runtime and is excluded from timing.
        repeat(iterations + 1) { iteration ->
            cases.forEach { fixture ->
                currentCoroutineContext().ensureActive()
                val start = System.nanoTime()
                val mask = PrivateTextMask(PrivacyCategory.entries.toSet(), fixture.customTerms)
                mask.seedIdentities(fixture.history + fixture.input)
                val protected = mask.protectWithReceipt(fixture.input)
                val maskedAt = System.nanoTime()
                val restore = mask.sealWithReceipts()
                val sealedAt = System.nanoTime()
                val restored = restore(protected.text)
                val restoredAt = System.nanoTime()
                if (iteration > 0) {
                    // Include sealing/encryption in masking cost; restoration is measured separately.
                    maskTimes += ((maskedAt - start) + (sealedAt - maskedAt)) / 1_000
                    restoreTimes += (restoredAt - sealedAt) / 1_000
                }
                fixture.sensitiveValues.forEach { value ->
                    if (!protected.text.contains(value, ignoreCase = true) && everyOccurrenceProtected(fixture.input, value, protected.receipt)) {
                        if (iteration == 0) privatePasses++
                    } else {
                        failures += "${fixture.id}:PRIVATE_VALUE_MISSED"
                    }
                }
                fixture.requiredPhrases.forEach { value ->
                    if (protected.text.contains(value)) {
                        if (iteration == 0) publicPasses++
                    } else {
                        failures += "${fixture.id}:TASK_MEANING_REMOVED"
                    }
                }
                if (restored.text == fixture.input && restored.receipt.validFor(restored.text)) {
                    if (iteration == 0) restores++
                } else {
                    failures += "${fixture.id}:RESTORATION_CHANGED_TEXT"
                }
            }
        }
        val first = PrivateTextMask(setOf(PrivacyCategory.CUSTOM), listOf("Fictional Alpha"))
        val second = PrivateTextMask(setOf(PrivacyCategory.CUSTOM), listOf("Fictional Beta"))
        val firstToken = first.protect("Fictional Alpha")
        val secondToken = second.protect("Fictional Beta")
        val restoreFirst = first.sealForResponse()
        val restoreSecond = second.sealForResponse()
        val isolated = firstToken != secondToken && restoreFirst(secondToken) == secondToken && restoreSecond(firstToken) == firstToken
        if (!isolated) failures += "request_isolation:FOREIGN_MAP_USED"
        return PrivacyBenchmarkResult(
            cases.size, privatePasses, cases.sumOf { it.sensitiveValues.size }, publicPasses,
            cases.sumOf { it.requiredPhrases.size }, restores, isolated, failures.toList(), maskTimes.size,
            percentile(maskTimes, 0.5), percentile(maskTimes, 0.95), percentile(restoreTimes, 0.5), percentile(restoreTimes, 0.95)
        )
    }

    private fun everyOccurrenceProtected(text: String, value: String, receipt: PrivacyProtectionReceipt): Boolean {
        if (!receipt.confirmed || !receipt.validFor(text)) return false
        var index = text.indexOf(value, ignoreCase = true)
        if (index < 0) return false
        while (index >= 0) {
            if (receipt.ranges.none { it.start <= index && it.endExclusive >= index + value.length }) return false
            index = text.indexOf(value, index + 1, ignoreCase = true)
        }
        return true
    }

    internal fun percentile(samples: List<Long>, quantile: Double): Long {
        require(samples.isNotEmpty() && quantile in 0.0..1.0)
        val sorted = samples.sorted()
        if (quantile == 0.5 && sorted.size % 2 == 0) {
            val lower = sorted[sorted.size / 2 - 1]
            return lower + (sorted[sorted.size / 2] - lower) / 2
        }
        return sorted[(ceil(samples.size * quantile).toInt() - 1).coerceIn(samples.indices)]
    }
}
